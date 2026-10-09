use serde::{Deserialize, Serialize};

pub const SCHEMA_VERSION: u32 = 1;

/// Ordered capability ladder. A device's level is only ever raised by recorded
/// evidence, never inferred from USB or chipset detection.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CapabilityLevel {
    Unknown,
    UsbDetected,
    SaharaIdentified,
    CompatibleProgrammerAvailable,
    ReadOnlyVerified,
    BackupVerified,
    WriteSupported,
    RecoveryVerified,
}

/// Three-valued flag: "unknown" is distinct from "no".
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Tri {
    Yes,
    No,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StorageKind {
    Emmc,
    Ufs,
    Nand,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Evidence {
    /// What the evidence is: "hardware_test", "vendor_doc", "community_report", ...
    pub kind: String,
    pub reference: String,
    pub date: String,
    /// Highest level this specific evidence supports.
    pub supports_level: CapabilityLevel,
    /// True only for results obtained on physical hardware (never simulator runs).
    pub hardware: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UsbId {
    pub vendor_id: u16,
    pub product_id: u16,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manufacturer {
    pub schema_version: u32,
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Chipset {
    pub schema_version: u32,
    pub id: String,
    pub marketing_name: String,
    pub notes: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Programmer {
    pub schema_version: u32,
    pub id: String,
    pub description: String,
    pub chipsets: Vec<String>,
    /// SHA-256 of the exact programmer file this entry describes.
    pub sha256: String,
    pub signed_by_vendor: Tri,
    pub evidence: Vec<Evidence>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Authentication {
    pub required: Tri,
    pub mechanism: Option<String>,
}

/// Chipset recognition never implies device support; device entries carry
/// authentication and restrictions as independent fields.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeviceProfile {
    pub schema_version: u32,
    pub id: String,
    pub manufacturer: String,
    pub marketing_name: String,
    pub model_number: String,
    pub hardware_variant: String,
    pub chipset: String,
    pub usb_ids: Vec<UsbId>,
    pub storage: StorageKind,
    pub capability_level: CapabilityLevel,
    pub authentication: Authentication,
    /// Programmer ids (from `programmers/`) verified for this device.
    pub programmers: Vec<String>,
    pub firmware_compatibility_rules: Vec<String>,
    /// Partitions that may be written during recovery despite being on the protected list.
    /// Empty unless a device-specific recovery requirement is documented in `evidence`.
    pub recovery_writable_protected: Vec<String>,
    pub partition_layout_notes: String,
    pub verified_operations: Vec<String>,
    pub known_limitations: Vec<String>,
    pub evidence: Vec<Evidence>,
    pub last_verified: Option<String>,
}
