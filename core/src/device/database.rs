use super::profile::*;
use serde::de::DeserializeOwned;
use std::collections::BTreeMap;
use std::path::Path;

#[derive(Debug, thiserror::Error)]
pub enum DbError {
    #[error("{path}: {msg}")]
    Load { path: String, msg: String },
    #[error("validation failed:\n{}", .0.join("\n"))]
    Invalid(Vec<String>),
}

#[derive(Debug, Default)]
pub struct Database {
    pub manufacturers: BTreeMap<String, Manufacturer>,
    pub chipsets: BTreeMap<String, Chipset>,
    pub programmers: BTreeMap<String, Programmer>,
    pub devices: BTreeMap<String, DeviceProfile>,
}

fn load_dir<T: DeserializeOwned>(dir: &Path) -> Result<Vec<T>, DbError> {
    let mut out = Vec::new();
    let Ok(rd) = std::fs::read_dir(dir) else {
        return Ok(out);
    };
    let mut paths: Vec<_> = rd.filter_map(|e| e.ok()).map(|e| e.path()).collect();
    paths.sort();
    for p in paths {
        if p.extension().and_then(|e| e.to_str()) != Some("json") {
            continue;
        }
        let err = |msg: String| DbError::Load {
            path: p.display().to_string(),
            msg,
        };
        let text = std::fs::read_to_string(&p).map_err(|e| err(e.to_string()))?;
        out.push(serde_json::from_str(&text).map_err(|e| err(e.to_string()))?);
    }
    Ok(out)
}

fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'_')
}

fn valid_sha256(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

impl Database {
    /// Load `manufacturers/ chipsets/ programmers/ devices/` under `root` and validate.
    pub fn load(root: &Path) -> Result<Database, DbError> {
        let mut db = Database::default();
        for m in load_dir::<Manufacturer>(&root.join("manufacturers"))? {
            db.manufacturers.insert(m.id.clone(), m);
        }
        for c in load_dir::<Chipset>(&root.join("chipsets"))? {
            db.chipsets.insert(c.id.clone(), c);
        }
        for p in load_dir::<Programmer>(&root.join("programmers"))? {
            db.programmers.insert(p.id.clone(), p);
        }
        for d in load_dir::<DeviceProfile>(&root.join("devices"))? {
            db.devices.insert(d.id.clone(), d);
        }
        db.validate()?;
        Ok(db)
    }

    pub fn validate(&self) -> Result<(), DbError> {
        let mut e: Vec<String> = Vec::new();
        let ver = |what: &str, id: &str, v: u32, e: &mut Vec<String>| {
            if v != SCHEMA_VERSION {
                e.push(format!(
                    "{what} {id}: schema_version {v} != {SCHEMA_VERSION}"
                ));
            }
        };
        for (k, m) in &self.manufacturers {
            ver("manufacturer", k, m.schema_version, &mut e);
            if !valid_id(k) {
                e.push(format!("manufacturer {k}: invalid id"));
            }
        }
        for (k, c) in &self.chipsets {
            ver("chipset", k, c.schema_version, &mut e);
            if !valid_id(k) {
                e.push(format!("chipset {k}: invalid id"));
            }
        }
        for (k, p) in &self.programmers {
            ver("programmer", k, p.schema_version, &mut e);
            if !valid_id(k) {
                e.push(format!("programmer {k}: invalid id"));
            }
            if !valid_sha256(&p.sha256) {
                e.push(format!(
                    "programmer {k}: sha256 must be 64 lowercase hex chars"
                ));
            }
            if p.evidence.is_empty() {
                e.push(format!(
                    "programmer {k}: at least one evidence entry required"
                ));
            }
            for c in &p.chipsets {
                if !self.chipsets.contains_key(c) {
                    e.push(format!("programmer {k}: unknown chipset {c}"));
                }
            }
        }
        for (k, d) in &self.devices {
            ver("device", k, d.schema_version, &mut e);
            if !valid_id(k) {
                e.push(format!("device {k}: invalid id"));
            }
            if !self.manufacturers.contains_key(&d.manufacturer) {
                e.push(format!(
                    "device {k}: unknown manufacturer {}",
                    d.manufacturer
                ));
            }
            if !self.chipsets.contains_key(&d.chipset) {
                e.push(format!("device {k}: unknown chipset {}", d.chipset));
            }
            for p in &d.programmers {
                match self.programmers.get(p) {
                    None => e.push(format!("device {k}: unknown programmer {p}")),
                    Some(pr) if !pr.chipsets.contains(&d.chipset) => e.push(format!(
                        "device {k}: programmer {p} not listed for chipset {}",
                        d.chipset
                    )),
                    _ => {}
                }
            }
            check_level(k, d, &mut e);
        }
        if e.is_empty() {
            Ok(())
        } else {
            Err(DbError::Invalid(e))
        }
    }
}

/// Capability claims must be backed by evidence; write-related claims by hardware evidence.
fn check_level(k: &str, d: &DeviceProfile, e: &mut Vec<String>) {
    let lvl = d.capability_level;
    if lvl == CapabilityLevel::Unknown {
        return;
    }
    let best = d
        .evidence
        .iter()
        .map(|ev| ev.supports_level)
        .max()
        .unwrap_or(CapabilityLevel::Unknown);
    if best < lvl {
        e.push(format!(
            "device {k}: claims {lvl:?} but strongest evidence supports only {best:?}"
        ));
    }
    if lvl >= CapabilityLevel::SaharaIdentified
        && !d.evidence.iter().any(|ev| {
            ev.hardware && ev.supports_level >= lvl.min(CapabilityLevel::SaharaIdentified)
        })
    {
        e.push(format!(
            "device {k}: levels above usb_detected require hardware evidence"
        ));
    }
    if lvl >= CapabilityLevel::CompatibleProgrammerAvailable && d.programmers.is_empty() {
        e.push(format!(
            "device {k}: {lvl:?} requires at least one verified programmer"
        ));
    }
    if lvl >= CapabilityLevel::ReadOnlyVerified && d.last_verified.is_none() {
        e.push(format!("device {k}: {lvl:?} requires last_verified"));
    }
    if lvl >= CapabilityLevel::WriteSupported && d.authentication.required == Tri::Unknown {
        e.push(format!(
            "device {k}: {lvl:?} requires authentication.required to be yes or no, not unknown"
        ));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    pub fn base() -> (Database, DeviceProfile) {
        let mut db = Database::default();
        db.manufacturers.insert(
            "acme".into(),
            Manufacturer {
                schema_version: 1,
                id: "acme".into(),
                name: "Acme".into(),
            },
        );
        db.chipsets.insert(
            "chip-a".into(),
            Chipset {
                schema_version: 1,
                id: "chip-a".into(),
                marketing_name: "A".into(),
                notes: "".into(),
            },
        );
        let d = DeviceProfile {
            schema_version: 1,
            id: "acme-1".into(),
            manufacturer: "acme".into(),
            marketing_name: "Acme 1".into(),
            model_number: "X1".into(),
            hardware_variant: "global".into(),
            chipset: "chip-a".into(),
            usb_ids: vec![UsbId {
                vendor_id: 0x05c6,
                product_id: 0x9008,
            }],
            storage: StorageKind::Ufs,
            capability_level: CapabilityLevel::Unknown,
            authentication: Authentication {
                required: Tri::Unknown,
                mechanism: None,
            },
            programmers: vec![],
            firmware_compatibility_rules: vec![],
            recovery_writable_protected: vec![],
            partition_layout_notes: "".into(),
            verified_operations: vec![],
            known_limitations: vec![],
            evidence: vec![],
            last_verified: None,
        };
        (db, d)
    }

    fn err_text(db: &Database) -> String {
        db.validate().unwrap_err().to_string()
    }

    #[test]
    fn unknown_level_without_evidence_is_valid() {
        let (mut db, d) = base();
        db.devices.insert(d.id.clone(), d);
        db.validate().unwrap();
    }

    #[test]
    fn level_without_evidence_is_rejected() {
        let (mut db, mut d) = base();
        d.capability_level = CapabilityLevel::WriteSupported;
        db.devices.insert(d.id.clone(), d);
        let t = err_text(&db);
        assert!(t.contains("strongest evidence"), "{t}");
        assert!(
            t.contains("requires at least one verified programmer"),
            "{t}"
        );
        assert!(t.contains("authentication"), "{t}");
    }

    #[test]
    fn simulator_evidence_does_not_count() {
        let (mut db, mut d) = base();
        d.capability_level = CapabilityLevel::SaharaIdentified;
        d.evidence.push(Evidence {
            kind: "simulator".into(),
            reference: "ci".into(),
            date: "2026-01-01".into(),
            supports_level: CapabilityLevel::SaharaIdentified,
            hardware: false,
        });
        db.devices.insert(d.id.clone(), d);
        assert!(err_text(&db).contains("hardware evidence"));
    }

    #[test]
    fn dangling_references_and_bad_ids_rejected() {
        let (mut db, mut d) = base();
        d.chipset = "nope".into();
        d.manufacturer = "nobody".into();
        d.programmers = vec!["ghost".into()];
        d.id = "Bad ID".into();
        db.devices.insert(d.id.clone(), d);
        let t = err_text(&db);
        for needle in [
            "unknown chipset",
            "unknown manufacturer",
            "unknown programmer",
            "invalid id",
        ] {
            assert!(t.contains(needle), "{needle} missing in {t}");
        }
    }

    #[test]
    fn unknown_json_fields_rejected() {
        let r: Result<Manufacturer, _> =
            serde_json::from_str(r#"{"schema_version":1,"id":"a","name":"A","extra":1}"#);
        assert!(r.is_err());
    }
}
