use edl_core::device::{CapabilityLevel, Database};
use std::path::Path;

#[test]
fn shipped_database_validates_and_makes_no_unearned_claims() {
    let db = Database::load(&Path::new(env!("CARGO_MANIFEST_DIR")).join("../database")).unwrap();
    assert!(!db.devices.is_empty());
    for d in db.devices.values() {
        assert_eq!(
            d.capability_level,
            CapabilityLevel::Unknown,
            "{} claims support without hardware evidence in repo",
            d.id
        );
    }
}
