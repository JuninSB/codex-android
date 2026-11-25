use std::env;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, Write};
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt};
use std::path::Path;

fn valid_id(id: &str) -> bool {
    let bytes = id.as_bytes();
    bytes.len() == 36 && bytes.iter().enumerate().all(|(index, byte)| {
        if matches!(index, 8 | 13 | 18 | 23) {
            *byte == b'-'
        } else {
            byte.is_ascii_hexdigit()
        }
    })
}

fn write_chat(dir: &Path, id: &str, document: &[u8]) -> io::Result<()> {
    if !valid_id(id) || !document.starts_with(b"{") || !document.ends_with(b"}") {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid chat document"));
    }
    let staging = dir.join(format!("{id}.tmp"));
    let mut file = OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(&staging)?;
    file.write_all(document)?;
    file.sync_data()?;
    fs::rename(staging, dir.join(format!("{id}.json")))?;
    File::open(dir)?.sync_all()
}

fn main() -> io::Result<()> {
    let dir = env::args_os().nth(1).ok_or(io::ErrorKind::InvalidInput)?;
    let dir = Path::new(&dir);
    fs::DirBuilder::new().recursive(true).mode(0o700).create(dir)?;
    let output = io::stdout();
    let mut out = output.lock();
    for line in io::stdin().lock().lines() {
        let line = line?;
        let success = line.split_once('\t')
            .is_some_and(|(id, body)| write_chat(dir, id, body.as_bytes()).is_ok());
        writeln!(out, "{}", if success { "ok" } else { "error" })?;
        out.flush()?;
    }
    Ok(())
}
