use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufReader, Write};
use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt};
use std::path::{Path, PathBuf};

fn read_json(path: &Path) -> io::Result<Value> {
    Ok(serde_json::from_reader(BufReader::new(File::open(path)?))?)
}

fn stamp(path: &Path) -> Option<(i64, i64)> {
    fs::metadata(path).ok().map(|m| (m.mtime(), m.mtime_nsec()))
}

fn replace_file(path: &Path, body: &[u8]) -> io::Result<()> {
    let staging = path.with_extension("tmp");
    let mut file = OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(&staging)?;
    file.write_all(body)?;
    file.sync_data()?;
    fs::rename(staging, path)?;
    File::open(path.parent().expect("parent"))?.sync_all()
}

#[derive(Deserialize, Serialize)]
struct Summary {
    id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    title: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[serde(rename = "createdAt")]
    created_at: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pinned: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    archived: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    deleted: Option<bool>,
}

pub struct Catalog {
    directory: PathBuf,
    index: PathBuf,
    legacy: PathBuf,
    dirty: PathBuf,
    rows: Vec<Value>,
    positions: HashMap<String, usize>,
}

impl Catalog {
    pub fn open(directory: PathBuf) -> io::Result<Self> {
        fs::DirBuilder::new().recursive(true).mode(0o700).create(&directory)?;
        let mut catalog = Self {
            index: directory.with_extension("index.json"),
            legacy: directory.with_file_name("chats.json"),
            dirty: directory.with_extension("dirty"),
            directory,
            rows: Vec::new(),
            positions: HashMap::new(),
        };
        if let Ok(snapshot) = read_json(&catalog.index) {
            let (directory, legacy) = catalog.stamps();
            if !catalog.dirty.exists() && snapshot["directory"] == directory && snapshot["legacy"] == legacy {
                if let Some(rows) = snapshot["rows"].as_array() {
                    for row in rows {
                        catalog.insert(row.clone())?;
                    }
                    return Ok(catalog);
                }
            }
        }
        catalog.rebuild()?;
        Ok(catalog)
    }

    fn stamps(&self) -> (Value, Value) {
        (json!(stamp(&self.directory)), json!(stamp(&self.legacy)))
    }

    fn insert(&mut self, row: Value) -> io::Result<()> {
        let id = row["id"].as_str().filter(|id| super::valid_id(id))
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid conversation id"))?;
        if let Some(&index) = self.positions.get(id) {
            self.rows[index] = row;
        } else {
            self.positions.insert(id.to_owned(), self.rows.len());
            self.rows.push(row);
        }
        Ok(())
    }

    fn summary(document: &Value) -> Value {
        let mut row = json!({"id": document["id"]});
        for key in ["title", "createdAt", "pinned", "archived", "deleted"] {
            if let Some(value) = document.get(key) {
                row[key] = value.clone();
            }
        }
        row
    }

    fn write_index(&self) -> io::Result<()> {
        let (directory, legacy) = self.stamps();
        let index = json!({ "directory": directory, "legacy": legacy, "rows": self.rows });
        replace_file(&self.index, &serde_json::to_vec(&index)?)
    }

    fn mark_dirty(&self) -> io::Result<()> {
        replace_file(&self.dirty, b"")
    }

    fn rebuild(&mut self) -> io::Result<()> {
        self.mark_dirty()?;
        let mut segmented = HashSet::new();
        for entry in fs::read_dir(&self.directory)? {
            let entry = entry?;
            let name = entry.file_name();
            let Some(id) = name.to_str().and_then(|name| name.strip_suffix(".json")) else { continue };
            if super::valid_id(id) {
                segmented.insert(id.to_owned());
            }
        }
        match read_json(&self.legacy) {
            Ok(Value::Array(chats)) => {
                for document in chats {
                    let id = document["id"].as_str().filter(|id| super::valid_id(id))
                        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid legacy conversation id"))?;
                    self.insert(Self::summary(&document))?;
                    if !segmented.contains(id) {
                        replace_file(&self.directory.join(format!("{id}.json")), &serde_json::to_vec(&document)?)?;
                    }
                }
            }
            Ok(_) => return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid legacy conversation list")),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {},
            Err(error) => return Err(error),
        }
        let mut sorted: Vec<_> = segmented.into_iter().collect();
        sorted.sort();
        for id in sorted {
            let file = File::open(self.directory.join(format!("{id}.json")))?;
            let summary: Summary = serde_json::from_reader(BufReader::new(file))?;
            if summary.id != id {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "conversation id does not match its file"));
            }
            self.insert(serde_json::to_value(summary)?)?;
        }
        self.write_index()?;
        fs::remove_file(&self.dirty)
    }

    pub fn print(&self, output: impl Write) -> io::Result<()> {
        Ok(serde_json::to_writer(output, &self.rows)?)
    }

    pub fn save(&mut self, id: &str, body: &str) -> io::Result<()> {
        if !super::valid_id(id) {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid conversation id"));
        }
        let summary: Summary = serde_json::from_str(body)?;
        if summary.id != id {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "conversation id does not match its file"));
        }
        // Rebuild after an interrupted write before changing the catalog again.
        if self.dirty.exists() {
            *self = Self::open(self.directory.clone())?;
        }
        self.mark_dirty()?;
        replace_file(&self.directory.join(format!("{id}.json")), body.as_bytes())?;
        self.insert(serde_json::to_value(summary)?)?;
        self.write_index()?;
        fs::remove_file(&self.dirty)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    const CHAT: &str = "bb78aeb3-d849-45e7-80de-a0ac3d25be23";

    #[test]
    fn index_migrates_legacy_and_does_not_open_transcripts_on_warm_start() {
        let id = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let parent = std::env::temp_dir().join(format!("codex-catalog-test-{}-{id}", std::process::id()));
        let directory = parent.join("chats-v2");
        fs::create_dir(&parent).unwrap();
        fs::write(parent.join("chats.json"), format!(r#"[{{"id":"{CHAT}","title":"Legado","messages":[{{"text":"abc"}}]}}]"#)).unwrap();

        let catalog = Catalog::open(directory.clone()).unwrap();
        assert_eq!(catalog.rows[0]["title"], "Legado");
        assert!(catalog.rows[0].get("messages").is_none());

        let history = directory.join(format!("{CHAT}.json"));
        let contents = fs::read_to_string(&history).unwrap();
        assert!(contents.contains("abc"));
        fs::write(&history, b"unreadable JSON").unwrap();

        let mut catalog = Catalog::open(directory.clone()).unwrap();
        assert_eq!(catalog.rows[0]["title"], "Legado");
        fs::write(history, contents).unwrap();
        catalog.save(CHAT, &format!(r#"{{"id":"{CHAT}","title":"Renomeada","messages":[]}}"#)).unwrap();
        assert_eq!(Catalog::open(directory).unwrap().rows[0]["title"], "Renomeada");
        fs::remove_dir_all(parent).unwrap();
    }
}
