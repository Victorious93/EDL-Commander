//! Append-only, hash-chained audit log of recovery decisions (JSON Lines).
//! Tamper-*evident* only: anyone with file access can rewrite the whole chain.

use crate::sha256_hex;
use serde::{Deserialize, Serialize};
use std::io::Write;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuditRecord {
    pub seq: u64,
    pub timestamp: String,
    pub action: String,
    pub outcome: String,
    pub detail: String,
    pub prev_hash: String,
    pub hash: String,
}

#[derive(Debug, Default)]
pub struct AuditLog {
    records: Vec<AuditRecord>,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum AuditLoadError {
    #[error("audit line {line} is not a valid record: {reason}")]
    Parse { line: usize, reason: String },
    #[error("audit hash chain does not verify")]
    ChainBroken,
}

const GENESIS: &str = "0000000000000000000000000000000000000000000000000000000000000000";

fn digest(seq: u64, ts: &str, action: &str, outcome: &str, detail: &str, prev: &str) -> String {
    sha256_hex(format!("{seq}\n{ts}\n{action}\n{outcome}\n{detail}\n{prev}").as_bytes())
}

impl AuditLog {
    pub fn new() -> Self {
        Self::default()
    }

    /// `timestamp` is supplied by the caller (RFC 3339) so the core stays clock-free and testable.
    pub fn append(
        &mut self,
        timestamp: &str,
        action: &str,
        outcome: &str,
        detail: &str,
    ) -> &AuditRecord {
        let seq = self.records.len() as u64;
        let prev = self
            .records
            .last()
            .map_or(GENESIS.to_string(), |r| r.hash.clone());
        let hash = digest(seq, timestamp, action, outcome, detail, &prev);
        self.records.push(AuditRecord {
            seq,
            timestamp: timestamp.into(),
            action: action.into(),
            outcome: outcome.into(),
            detail: detail.into(),
            prev_hash: prev,
            hash,
        });
        self.records.last().unwrap()
    }

    /// Rebuild a log from JSON Lines produced by [`AuditLog::write_jsonl`], verifying the whole
    /// hash chain so new records extend it. Blank input is an empty log.
    pub fn from_jsonl(text: &str) -> Result<AuditLog, AuditLoadError> {
        let mut records = Vec::new();
        for (i, line) in text.lines().enumerate() {
            if line.trim().is_empty() {
                continue;
            }
            let r: AuditRecord = serde_json::from_str(line).map_err(|e| AuditLoadError::Parse {
                line: i + 1,
                reason: e.to_string(),
            })?;
            records.push(r);
        }
        if !Self::verify(&records) {
            return Err(AuditLoadError::ChainBroken);
        }
        Ok(AuditLog { records })
    }

    pub fn records(&self) -> &[AuditRecord] {
        &self.records
    }

    pub fn verify(records: &[AuditRecord]) -> bool {
        let mut prev = GENESIS.to_string();
        for (i, r) in records.iter().enumerate() {
            if r.seq != i as u64
                || r.prev_hash != prev
                || r.hash != digest(r.seq, &r.timestamp, &r.action, &r.outcome, &r.detail, &prev)
            {
                return false;
            }
            prev = r.hash.clone();
        }
        true
    }

    pub fn write_jsonl<W: Write>(&self, mut w: W) -> std::io::Result<()> {
        for r in &self.records {
            serde_json::to_writer(&mut w, r).map_err(std::io::Error::other)?;
            w.write_all(b"\n")?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chain_verifies_and_detects_tampering() {
        let mut log = AuditLog::new();
        log.append(
            "2026-01-01T00:00:00Z",
            "authorize.write",
            "denied",
            "read-only mode",
        );
        log.append("2026-01-01T00:00:01Z", "sahara.upload", "ok", "");
        assert!(AuditLog::verify(log.records()));
        let mut t = log.records().to_vec();
        t[0].outcome = "allowed".into();
        assert!(!AuditLog::verify(&t));
        let mut dropped = log.records().to_vec();
        dropped.remove(0);
        assert!(!AuditLog::verify(&dropped));
        let mut out = Vec::new();
        log.write_jsonl(&mut out).unwrap();
        assert_eq!(out.iter().filter(|&&b| b == b'\n').count(), 2);
    }

    #[test]
    fn jsonl_roundtrip_extends_the_chain_and_rejects_tampering() {
        let mut log = AuditLog::new();
        log.append("t0", "a", "ok", "");
        log.append("t1", "b", "denied", "why");
        let mut out = Vec::new();
        log.write_jsonl(&mut out).unwrap();
        let text = String::from_utf8(out).unwrap();

        let mut loaded = AuditLog::from_jsonl(&text).unwrap();
        loaded.append("t2", "c", "ok", "");
        assert_eq!(loaded.records().len(), 3);
        assert!(AuditLog::verify(loaded.records()));

        assert_eq!(AuditLog::from_jsonl("").unwrap().records().len(), 0);
        assert_eq!(
            AuditLog::from_jsonl(&text.replace("denied", "allowed")).unwrap_err(),
            AuditLoadError::ChainBroken
        );
        assert!(matches!(
            AuditLog::from_jsonl("not json\n"),
            Err(AuditLoadError::Parse { line: 1, .. })
        ));
    }
}
