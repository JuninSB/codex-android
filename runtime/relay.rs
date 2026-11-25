use std::ffi::OsString;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::os::raw::{c_int, c_ulong};
use std::os::unix::process::CommandExt;
use std::process::{self, Command, Stdio};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

const MAX_FRAME: usize = 64 * 1024 * 1024;
const SHUTDOWN_GRACE: Duration = Duration::from_secs(2);
const PR_SET_PDEATHSIG: c_int = 1;
const SIGKILL: c_ulong = 9;

extern "C" {
    fn getppid() -> c_int;
    fn prctl(option: c_int, ...) -> c_int;
}

fn forward(mut input: impl BufRead, mut output: impl Write) -> io::Result<()> {
    let mut frame = Vec::new();
    loop {
        frame.clear();
        let length = input.by_ref().take((MAX_FRAME + 1) as u64).read_until(b'\n', &mut frame)?;
        if length == 0 {
            return Ok(());
        }
        if length > MAX_FRAME || !frame.ends_with(b"\n") {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "quadro JSON-RPC incompleto ou grande demais"));
        }
        output.write_all(&frame)?;
        output.flush()?;
    }
}

enum Event {
    InputClosed(io::Result<()>),
    OutputClosed,
}

pub fn run(mut args: impl Iterator<Item = OsString>) -> io::Result<i32> {
    let program = args.next().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "processo Codex ausente"))?;
    let mut command = Command::new(program);
    command.args(args).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::inherit());
    let parent = process::id();
    // Close inherited processes even if the Node bridge kills the relay during a restart.
    unsafe {
        command.pre_exec(move || {
            if prctl(PR_SET_PDEATHSIG, SIGKILL, 0 as c_ulong, 0 as c_ulong, 0 as c_ulong) != 0 {
                return Err(io::Error::last_os_error());
            }
            if getppid() as u32 != parent {
                return Err(io::Error::new(io::ErrorKind::Interrupted, "relay encerrado"));
            }
            Ok(())
        });
    }
    let mut child = command.spawn()?;
    let stdin = child.stdin.take().expect("stdin configurado");
    let stdout = child.stdout.take().expect("stdout configurado");
    let (send, recv) = mpsc::channel();
    let input_events = send.clone();
    thread::spawn(move || {
        let result = forward(io::stdin().lock(), stdin);
        let _ = input_events.send(Event::InputClosed(result));
    });
    let output = thread::spawn(move || {
        let result = forward(BufReader::new(stdout), io::stdout().lock());
        let _ = send.send(Event::OutputClosed);
        result
    });

    let mut deadline = None;
    let mut input_error = None;
    while child.try_wait()?.is_none() {
        match recv.recv_timeout(Duration::from_millis(50)) {
            Ok(Event::InputClosed(result)) => {
                input_error = result.err().filter(|error| error.kind() != io::ErrorKind::BrokenPipe);
                deadline = Some(Instant::now() + SHUTDOWN_GRACE);
            }
            Ok(Event::OutputClosed) | Err(mpsc::RecvTimeoutError::Disconnected) => {
                deadline.get_or_insert_with(|| Instant::now() + SHUTDOWN_GRACE);
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
        }
        if input_error.is_some() || deadline.is_some_and(|at| Instant::now() >= at) {
            let _ = child.kill();
            break;
        }
    }
    let status = child.wait()?;
    let drain_deadline = Instant::now() + SHUTDOWN_GRACE;
    while !output.is_finished() {
        if Instant::now() >= drain_deadline {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "a saída do Codex não foi encerrada"));
        }
        thread::sleep(Duration::from_millis(10));
    }
    output.join().map_err(|_| io::Error::other("falha no fluxo de saída do Codex"))??;
    if let Some(error) = input_error {
        return Err(error);
    }
    use std::os::unix::process::ExitStatusExt;
    Ok(status.code().unwrap_or_else(|| 128 + status.signal().unwrap_or(1)))
}

#[cfg(test)]
mod tests {
    use super::forward;
    use std::io::{Cursor, ErrorKind};

    #[test]
    fn preserves_complete_frames() {
        let input = b"{\"id\":1}\n{\"method\":\"turn/started\"}\n";
        let mut output = Vec::new();
        forward(Cursor::new(input), &mut output).unwrap();
        assert_eq!(output, input);
    }

    #[test]
    fn never_forwards_a_partial_frame() {
        let mut output = Vec::new();
        let error = forward(Cursor::new(b"{}\n{"), &mut output).unwrap_err();
        assert_eq!(error.kind(), ErrorKind::InvalidData);
        assert_eq!(output, b"{}\n");
    }
}
