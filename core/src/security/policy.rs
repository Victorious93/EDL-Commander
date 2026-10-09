//! Central authorization for device operations. Default is read-only; every
//! destructive operation must pass every check, and each decision is auditable.

use crate::device::{CapabilityLevel, DeviceProfile, Tri};
use crate::diagnostics::audit::AuditLog;

/// Conservative defaults for boot-chain / security partitions (matched case-insensitively,
/// with a trailing `_a`/`_b` slot suffix ignored). Writing these needs the profile to list
/// the partition in `recovery_writable_protected` AND explicit per-run authorization.
pub const DEFAULT_PROTECTED_PARTITIONS: &[&str] = &[
    "sbl1",
    "xbl",
    "xbl_config",
    "aboot",
    "abl",
    "tz",
    "hyp",
    "rpm",
    "devcfg",
    "keymaster",
    "cmnlib",
    "cmnlib64",
    "storsec",
    "sec",
    "apdp",
    "qupfw",
    "uefisecapp",
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Operation {
    EnumeratePartitions,
    ReadPartition(String),
    UploadProgrammer { programmer_id: String },
    WritePartition(String),
    ErasePartition(String),
    ResetDevice,
}

impl Operation {
    pub fn is_destructive(&self) -> bool {
        matches!(
            self,
            Operation::WritePartition(_) | Operation::ErasePartition(_)
        )
    }
    fn label(&self) -> String {
        match self {
            Operation::EnumeratePartitions => "enumerate_partitions".into(),
            Operation::ReadPartition(p) => format!("read_partition:{p}"),
            Operation::UploadProgrammer { programmer_id } => {
                format!("upload_programmer:{programmer_id}")
            }
            Operation::WritePartition(p) => format!("write_partition:{p}"),
            Operation::ErasePartition(p) => format!("erase_partition:{p}"),
            Operation::ResetDevice => "reset_device".into(),
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct SafetyContext<'a> {
    /// `None` = device not matched to a profile (chipset-only recognition is not enough).
    pub profile: Option<&'a DeviceProfile>,
    /// Caller verified the programmer's SHA-256 against the database entry.
    pub programmer_hash_verified: bool,
    pub firmware_verified: bool,
    /// Writes are off unless the user explicitly enabled them for this session.
    pub write_enabled: bool,
    /// Digest of the exact recovery plan the user approved; must equal `plan_digest`.
    pub approved_plan_digest: Option<String>,
    pub plan_digest: Option<String>,
    /// Per-run explicit authorization to touch protected partitions.
    pub protected_override_authorized: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum Denial {
    #[error("no matching device profile")]
    NoProfile,
    #[error("device capability level {have:?} below required {need:?}")]
    InsufficientCapability {
        have: CapabilityLevel,
        need: CapabilityLevel,
    },
    #[error("programmer {0} is not listed for this device")]
    ProgrammerNotListed(String),
    #[error("programmer hash not verified")]
    ProgrammerUnverified,
    #[error("write mode not enabled (read-only default)")]
    ReadOnlyMode,
    #[error("firmware not verified")]
    FirmwareUnverified,
    #[error("authentication requirement unknown or unmet")]
    AuthenticationUnresolved,
    #[error("no matching explicit user confirmation of the recovery plan")]
    NotConfirmed,
    #[error("partition {0} is protected")]
    ProtectedPartition(String),
}

fn is_protected(name: &str) -> bool {
    let n = name.to_ascii_lowercase();
    let base = n
        .strip_suffix("_a")
        .or_else(|| n.strip_suffix("_b"))
        .unwrap_or(&n);
    DEFAULT_PROTECTED_PARTITIONS.contains(&base)
}

pub fn authorize(op: &Operation, ctx: &SafetyContext<'_>) -> Result<(), Denial> {
    if let Operation::ResetDevice = op {
        return Ok(());
    }
    let profile = ctx.profile.ok_or(Denial::NoProfile)?;
    let need_level = |need: CapabilityLevel| {
        if profile.capability_level >= need {
            Ok(())
        } else {
            Err(Denial::InsufficientCapability {
                have: profile.capability_level,
                need,
            })
        }
    };
    match op {
        Operation::ResetDevice => Ok(()),
        Operation::UploadProgrammer { programmer_id } => {
            need_level(CapabilityLevel::CompatibleProgrammerAvailable)?;
            if !profile.programmers.contains(programmer_id) {
                return Err(Denial::ProgrammerNotListed(programmer_id.clone()));
            }
            if !ctx.programmer_hash_verified {
                return Err(Denial::ProgrammerUnverified);
            }
            Ok(())
        }
        Operation::EnumeratePartitions | Operation::ReadPartition(_) => {
            need_level(CapabilityLevel::ReadOnlyVerified)
        }
        Operation::WritePartition(p) | Operation::ErasePartition(p) => {
            if !ctx.write_enabled {
                return Err(Denial::ReadOnlyMode);
            }
            need_level(CapabilityLevel::WriteSupported)?;
            if profile.authentication.required != Tri::No {
                // Authenticated flows need a vendor-authorized path that this engine does not implement.
                return Err(Denial::AuthenticationUnresolved);
            }
            if !ctx.firmware_verified {
                return Err(Denial::FirmwareUnverified);
            }
            match (&ctx.approved_plan_digest, &ctx.plan_digest) {
                (Some(a), Some(b)) if a == b => {}
                _ => return Err(Denial::NotConfirmed),
            }
            if is_protected(p) {
                let listed = profile
                    .recovery_writable_protected
                    .iter()
                    .any(|x| x.eq_ignore_ascii_case(p));
                if !(listed && ctx.protected_override_authorized) {
                    return Err(Denial::ProtectedPartition(p.clone()));
                }
            }
            Ok(())
        }
    }
}

/// Authorize and record the decision (allowed or denied) in the audit log.
pub fn authorize_audited(
    op: &Operation,
    ctx: &SafetyContext<'_>,
    log: &mut AuditLog,
    timestamp: &str,
) -> Result<(), Denial> {
    let r = authorize(op, ctx);
    match &r {
        Ok(()) => log.append(timestamp, &op.label(), "allowed", ""),
        Err(d) => log.append(timestamp, &op.label(), "denied", &d.to_string()),
    };
    r
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::device::{Authentication, StorageKind, UsbId};

    fn profile(level: CapabilityLevel, auth: Tri) -> DeviceProfile {
        DeviceProfile {
            schema_version: 1,
            id: "t".into(),
            manufacturer: "m".into(),
            marketing_name: "T".into(),
            model_number: "T1".into(),
            hardware_variant: "v".into(),
            chipset: "c".into(),
            usb_ids: vec![UsbId {
                vendor_id: 0x05c6,
                product_id: 0x9008,
            }],
            storage: StorageKind::Ufs,
            capability_level: level,
            authentication: Authentication {
                required: auth,
                mechanism: None,
            },
            programmers: vec!["prog".into()],
            firmware_compatibility_rules: vec![],
            recovery_writable_protected: vec![],
            partition_layout_notes: String::new(),
            verified_operations: vec![],
            known_limitations: vec![],
            evidence: vec![],
            last_verified: None,
        }
    }

    fn ready<'a>(p: &'a DeviceProfile) -> SafetyContext<'a> {
        SafetyContext {
            profile: Some(p),
            programmer_hash_verified: true,
            firmware_verified: true,
            write_enabled: true,
            approved_plan_digest: Some("d".into()),
            plan_digest: Some("d".into()),
            protected_override_authorized: false,
        }
    }

    fn write(p: &str) -> Operation {
        Operation::WritePartition(p.into())
    }

    #[test]
    fn no_profile_denies_everything_but_reset() {
        let ctx = SafetyContext::default();
        assert_eq!(
            authorize(&Operation::EnumeratePartitions, &ctx),
            Err(Denial::NoProfile)
        );
        assert_eq!(authorize(&write("userdata"), &ctx), Err(Denial::NoProfile));
        assert_eq!(authorize(&Operation::ResetDevice, &ctx), Ok(()));
    }

    #[test]
    fn happy_write_and_each_gate_denies() {
        let p = profile(CapabilityLevel::WriteSupported, Tri::No);
        assert_eq!(authorize(&write("userdata"), &ready(&p)), Ok(()));

        let mut c = ready(&p);
        c.write_enabled = false;
        assert_eq!(authorize(&write("userdata"), &c), Err(Denial::ReadOnlyMode));
        let mut c = ready(&p);
        c.firmware_verified = false;
        assert_eq!(
            authorize(&write("userdata"), &c),
            Err(Denial::FirmwareUnverified)
        );
        let mut c = ready(&p);
        c.approved_plan_digest = None;
        assert_eq!(authorize(&write("userdata"), &c), Err(Denial::NotConfirmed));
        let mut c = ready(&p);
        c.approved_plan_digest = Some("other".into());
        assert_eq!(authorize(&write("userdata"), &c), Err(Denial::NotConfirmed));

        let low = profile(CapabilityLevel::ReadOnlyVerified, Tri::No);
        assert!(matches!(
            authorize(&write("userdata"), &ready(&low)),
            Err(Denial::InsufficientCapability { .. })
        ));
        for a in [Tri::Yes, Tri::Unknown] {
            let pa = profile(CapabilityLevel::WriteSupported, a);
            assert_eq!(
                authorize(&write("userdata"), &ready(&pa)),
                Err(Denial::AuthenticationUnresolved)
            );
        }
    }

    #[test]
    fn protected_partitions_need_profile_listing_and_override() {
        let mut p = profile(CapabilityLevel::WriteSupported, Tri::No);
        assert_eq!(
            authorize(&write("xbl_a"), &ready(&p)),
            Err(Denial::ProtectedPartition("xbl_a".into()))
        );
        let mut c = ready(&p);
        c.protected_override_authorized = true;
        assert!(
            authorize(&write("XBL_A"), &c).is_err(),
            "override alone is not enough"
        );
        p.recovery_writable_protected = vec!["xbl_a".into()];
        assert_eq!(
            authorize(&write("xbl_a"), &ready(&p)),
            Err(Denial::ProtectedPartition("xbl_a".into()))
        );
        let mut c = ready(&p);
        c.protected_override_authorized = true;
        assert_eq!(authorize(&write("xbl_a"), &c), Ok(()));
    }

    #[test]
    fn programmer_upload_requires_listing_and_hash() {
        let p = profile(CapabilityLevel::CompatibleProgrammerAvailable, Tri::Unknown);
        let up = |id: &str| Operation::UploadProgrammer {
            programmer_id: id.into(),
        };
        assert_eq!(authorize(&up("prog"), &ready(&p)), Ok(()));
        assert_eq!(
            authorize(&up("other"), &ready(&p)),
            Err(Denial::ProgrammerNotListed("other".into()))
        );
        let mut c = ready(&p);
        c.programmer_hash_verified = false;
        assert_eq!(
            authorize(&up("prog"), &c),
            Err(Denial::ProgrammerUnverified)
        );
        let low = profile(CapabilityLevel::SaharaIdentified, Tri::Unknown);
        assert!(matches!(
            authorize(&up("prog"), &ready(&low)),
            Err(Denial::InsufficientCapability { .. })
        ));
    }

    #[test]
    fn decisions_are_audited() {
        let p = profile(CapabilityLevel::ReadOnlyVerified, Tri::No);
        let mut log = AuditLog::new();
        let _ = authorize_audited(&write("userdata"), &ready(&p), &mut log, "t0");
        let _ = authorize_audited(
            &Operation::ReadPartition("boot".into()),
            &ready(&p),
            &mut log,
            "t1",
        );
        assert_eq!(log.records()[0].outcome, "denied");
        assert_eq!(log.records()[1].outcome, "allowed");
        assert!(AuditLog::verify(log.records()));
    }
}
