mod catalog;
mod relay;

use std::env;
use std::io::{self, BufRead, Write};
use std::path::PathBuf;

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

fn main() -> io::Result<()> {
    let mut args = env::args_os().skip(1);
    let mode = args.next().ok_or(io::ErrorKind::InvalidInput)?;
    if mode == "--relay" {
        std::process::exit(relay::run(args)?);
    }
    let directory = if mode == "--index" { args.next().ok_or(io::ErrorKind::InvalidInput)? } else { mode.clone() };
    let mut catalog = catalog::Catalog::open(PathBuf::from(directory))?;
    let mut out = io::stdout().lock();
    if mode == "--index" {
        catalog.print(&mut out)?;
        return out.flush();
    }
    for line in io::stdin().lock().lines() {
        let line = line?;
        let success = line.split_once('\t').is_some_and(|(id, body)| catalog.save(id, body).is_ok());
        writeln!(out, "{}", if success { "ok" } else { "error" })?;
        out.flush()?;
    }
    Ok(())
}
