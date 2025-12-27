use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StreamConfig {
    pub name: String,
    pub source_database: String,
    pub source_collection: String,
    pub batch_max_size: u32,
    pub batch_max_wait_ms: u64,
    pub watch_operations: Vec<String>,
}

impl Default for StreamConfig {
    fn default() -> Self {
        Self {
            name: "default".to_string(),
            source_database: "test".to_string(),
            source_collection: "events".to_string(),
            batch_max_size: 100,
            batch_max_wait_ms: 1000,
            watch_operations: vec!["insert".to_string(), "update".to_string(), "replace".to_string()],
        }
    }
}
