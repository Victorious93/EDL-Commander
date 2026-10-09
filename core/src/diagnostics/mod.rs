//! Structured diagnostics and a tamper-evident audit log.

pub mod audit;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Level {
    Debug,
    Info,
    Warn,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DiagEvent {
    pub level: Level,
    /// Stable machine-readable code, e.g. `sahara.protocol`.
    pub code: String,
    pub message: String,
}

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
pub struct DiagLog {
    pub events: Vec<DiagEvent>,
}

impl DiagLog {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn push(&mut self, level: Level, code: &str, message: impl Into<String>) {
        self.events.push(DiagEvent {
            level,
            code: code.into(),
            message: message.into(),
        });
    }
    pub fn has_code(&self, code: &str) -> bool {
        self.events.iter().any(|e| e.code == code)
    }
}
