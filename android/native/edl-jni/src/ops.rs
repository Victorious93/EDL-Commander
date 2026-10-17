//! The operations the companion can ask of the native core. Pure Rust (no JNI types) so they
//! are unit-testable on the host. Every function returns a JSON string:
//!
//! * `{"ok":true,  ..., "audit":"<full JSONL>"}` on success,
//! * `{"ok":false, "code":..., "message":..., "audit":"<full JSONL>"}` on a handled failure,
//! * `{"ok":false, "code":"audit_invalid", ...}` (no `audit` key) when the supplied audit log
//!   does not verify. Nothing touches a device in that case.
//!
//! The caller owns persistence of the audit log: it passes the current JSONL in and stores the
//! returned JSONL. Timestamps are supplied by the caller (RFC 3339) to keep the core clock-free.
//! Audit records never contain serial numbers or key hashes.

use crate::bridge::{ByteBridge, CallbackTransport};
use edl_core::diagnostics::audit::AuditLog;
use edl_core::diagnostics::DiagLog;
use edl_core::hex;
use edl_core::protocol::sahara::{run_identify, IdentifyOptions, IdentifyReport, SaharaError};
use edl_core::security::policy::{authorize, authorize_audited, Operation, SafetyContext};
use edl_core::transport::{Transport, TransportError};
use serde_json::{json, Value};
use std::time::Duration;

pub const ACTION_IDENTIFY: &str = "sahara.identify";

pub fn version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

pub fn features() -> String {
    json!({
        "simulator": cfg!(feature = "sim"),
        "coreVersion": version(),
    })
    .to_string()
}

fn load(audit_jsonl: &str) -> Result<AuditLog, String> {
    AuditLog::from_jsonl(audit_jsonl).map_err(|e| {
        json!({"ok": false, "code": "audit_invalid", "message": e.to_string()}).to_string()
    })
}

fn dump(log: &AuditLog) -> String {
    let mut out = Vec::new();
    log.write_jsonl(&mut out)
        .expect("writing to a Vec cannot fail");
    String::from_utf8(out).expect("audit JSON is UTF-8")
}

fn failure(code: &str, message: &str, log: &AuditLog, diag: Option<&DiagLog>) -> String {
    json!({
        "ok": false,
        "code": code,
        "message": message,
        "diag": diag_json(diag),
        "audit": dump(log),
    })
    .to_string()
}

fn diag_json(diag: Option<&DiagLog>) -> Value {
    let events: Vec<Value> = diag
        .map(|d| {
            d.events
                .iter()
                .map(|e| json!({"level": format!("{:?}", e.level).to_lowercase(), "code": e.code, "message": e.message}))
                .collect()
        })
        .unwrap_or_default();
    Value::Array(events)
}

/// Stable machine-readable code for a Sahara failure.
pub fn error_code(e: &SaharaError) -> &'static str {
    match e {
        SaharaError::Transport(TransportError::Timeout) => "device_timeout",
        SaharaError::Transport(TransportError::Disconnected) => "device_disconnected",
        SaharaError::Transport(TransportError::Io(_)) => "transport_error",
        SaharaError::Packet(_) => "malformed_packet",
        SaharaError::IncompatibleVersion { .. } => "incompatible_version",
        SaharaError::UnsupportedMode(_) => "unsupported_mode",
        SaharaError::PacketBudgetExceeded(_) => "packet_budget_exceeded",
        _ => "protocol_error",
    }
}

/// Append one free-form record (e.g. a denial decided on the Kotlin side).
pub fn audit_append(
    audit_jsonl: &str,
    timestamp: &str,
    action: &str,
    outcome: &str,
    detail: &str,
) -> String {
    let mut log = match load(audit_jsonl) {
        Ok(l) => l,
        Err(e) => return e,
    };
    log.append(timestamp, action, outcome, detail);
    json!({"ok": true, "audit": dump(&log)}).to_string()
}

/// Run `security::authorize` for identification and record the decision.
pub fn authorize_identify(audit_jsonl: &str, timestamp: &str) -> String {
    let mut log = match load(audit_jsonl) {
        Ok(l) => l,
        Err(e) => return e,
    };
    match authorize_audited(
        &Operation::IdentifyDevice,
        &SafetyContext::default(),
        &mut log,
        timestamp,
    ) {
        Ok(()) => json!({"ok": true, "audit": dump(&log)}).to_string(),
        Err(d) => failure("denied", &d.to_string(), &log, None),
    }
}

/// Record a refused write-class command. The refusal itself comes from `security::authorize`
/// with a default (read-only, no profile) context, never from this code.
pub fn record_denied_write(audit_jsonl: &str, timestamp: &str, command: &str) -> String {
    let mut log = match load(audit_jsonl) {
        Ok(l) => l,
        Err(e) => return e,
    };
    let reason = match authorize(
        &Operation::WritePartition(command.to_string()),
        &SafetyContext::default(),
    ) {
        Err(d) => d.to_string(),
        Ok(()) => "refused: write-class commands do not exist in this build".to_string(),
    };
    log.append(timestamp, &format!("command:{command}"), "denied", &reason);
    json!({"ok": true, "audit": dump(&log)}).to_string()
}

fn report_json(r: &IdentifyReport) -> Value {
    let id = &r.identity;
    json!({
        "saharaVersion": id.sahara_version,
        "serial": id.serial.map(|s| format!("{s:08x}")),
        "msmHwId": id.msm_hw_id.map(|v| format!("{v:016x}")),
        "msmId": id.msm_id().map(|v| format!("{v:06x}")),
        "oemId": id.oem_id().map(|v| format!("{v:04x}")),
        "modelId": id.model_id().map(|v| format!("{v:04x}")),
        "oemPkHash": id.oem_pk_hash.as_deref().map(hex),
        "oemPkHashBytes": id.oem_pk_hash.as_ref().map(Vec::len),
        "returnedToImageMode": r.returned_to_image_mode,
        "note": "Field layout follows public descriptions and is unverified against hardware. \
                 Identification does not establish compatibility or capability.",
    })
}

/// Read-only Sahara identification over `transport`. Refuses to run unless
/// `security::authorize` allows `IdentifyDevice` (the audited decision should already be in
/// `audit_jsonl`; this is a second, unlogged gate). Appends one outcome record.
pub fn identify_with<T: Transport>(
    transport: &mut T,
    audit_jsonl: &str,
    timestamp: &str,
    timeout_ms: i32,
    source: &str,
) -> String {
    let mut log = match load(audit_jsonl) {
        Ok(l) => l,
        Err(e) => return e,
    };
    if let Err(d) = authorize(&Operation::IdentifyDevice, &SafetyContext::default()) {
        return failure("denied", &d.to_string(), &log, None);
    }
    let opts = IdentifyOptions {
        timeout: Duration::from_millis(timeout_ms.max(1) as u64),
        ..IdentifyOptions::default()
    };
    let mut diag = DiagLog::new();
    match run_identify(transport, &opts, &mut diag) {
        Ok(rep) => {
            let id = &rep.identity;
            let detail = format!(
                "source={source}; sahara_version={}; serial={}; hw_id={}; pk_hash_bytes={}",
                id.sahara_version,
                presence(id.serial.is_some()),
                presence(id.msm_hw_id.is_some()),
                id.oem_pk_hash.as_ref().map_or(0, Vec::len),
            );
            log.append(timestamp, ACTION_IDENTIFY, "ok", &detail);
            json!({
                "ok": true,
                "source": source,
                "report": report_json(&rep),
                "diag": diag_json(Some(&diag)),
                "audit": dump(&log),
            })
            .to_string()
        }
        Err(e) => {
            let code = error_code(&e);
            log.append(
                timestamp,
                ACTION_IDENTIFY,
                "error",
                &format!("source={source}; code={code}"),
            );
            failure(code, &e.to_string(), &log, Some(&diag))
        }
    }
}

fn presence(b: bool) -> &'static str {
    if b {
        "present"
    } else {
        "absent"
    }
}

/// Identification over a byte bridge (the Kotlin USB pipe).
pub fn identify<B: ByteBridge>(
    bridge: B,
    audit_jsonl: &str,
    timestamp: &str,
    timeout_ms: i32,
) -> String {
    let mut t = CallbackTransport::new(bridge);
    identify_with(&mut t, audit_jsonl, timestamp, timeout_ms, "usb")
}

/// Identification of the built-in simulated target. Never touches USB; always `source: "sim"`.
#[cfg(feature = "sim")]
pub fn identify_simulated(audit_jsonl: &str, timestamp: &str) -> String {
    let mut dev = edl_sim::SimulatedEdlDevice::new(0, 4096, edl_sim::Fault::None);
    identify_with(&mut dev, audit_jsonl, timestamp, 1000, "sim")
}

#[cfg(not(feature = "sim"))]
pub fn identify_simulated(audit_jsonl: &str, timestamp: &str) -> String {
    let _ = (audit_jsonl, timestamp);
    json!({"ok": false, "code": "simulator_not_built", "message": "this library was built without the simulator"}).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::SimBridge;
    use edl_sim::Fault;

    fn parse(s: &str) -> Value {
        serde_json::from_str(s).unwrap()
    }

    fn records(v: &Value) -> Vec<Value> {
        v["audit"]
            .as_str()
            .unwrap()
            .lines()
            .map(|l| serde_json::from_str(l).unwrap())
            .collect()
    }

    #[test]
    fn identify_through_the_callback_adapter_matches_the_simulated_device() {
        let out = parse(&identify(SimBridge::new(Fault::None), "", "t1", 2000));
        assert_eq!(out["ok"], true);
        assert_eq!(out["source"], "usb");
        let r = &out["report"];
        assert_eq!(r["serial"], "1234abcd");
        assert_eq!(r["msmHwId"], "0009b0e101230045");
        assert_eq!(r["msmId"], "09b0e1");
        assert_eq!(r["oemId"], "0123");
        assert_eq!(r["modelId"], "0045");
        assert_eq!(r["oemPkHashBytes"], 32);
        assert_eq!(r["oemPkHash"].as_str().unwrap().len(), 64);
        assert_eq!(r["returnedToImageMode"], true);
    }

    #[test]
    fn outcome_is_audited_without_identifiers_and_extends_the_chain() {
        let first = parse(&authorize_identify("", "t0"));
        assert_eq!(first["ok"], true);
        let out = parse(&identify(
            SimBridge::new(Fault::None),
            first["audit"].as_str().unwrap(),
            "t1",
            2000,
        ));
        let recs = records(&out);
        assert_eq!(recs.len(), 2);
        assert_eq!(recs[0]["action"], "identify_device");
        assert_eq!(recs[0]["outcome"], "allowed");
        assert_eq!(recs[1]["action"], ACTION_IDENTIFY);
        assert_eq!(recs[1]["outcome"], "ok");
        let detail = recs[1]["detail"].as_str().unwrap();
        assert!(detail.contains("source=usb"));
        assert!(!detail.contains("1234abcd") && !detail.contains("09b0e1"));
        assert!(AuditLog::from_jsonl(out["audit"].as_str().unwrap()).is_ok());
    }

    #[test]
    fn failures_map_to_stable_codes_and_are_audited() {
        let cases = [
            (Fault::Silent, "device_timeout"),
            (Fault::MalformedHello, "malformed_packet"),
            (Fault::IncompatibleVersion, "incompatible_version"),
            (Fault::OversizedExecResponse, "protocol_error"),
            (Fault::SilentAfterReady, "device_timeout"),
        ];
        for (fault, code) in cases {
            let out = parse(&identify(SimBridge::new(fault.clone()), "", "t", 50));
            assert_eq!(out["ok"], false, "{fault:?}");
            assert_eq!(out["code"], code, "{fault:?}");
            let recs = records(&out);
            assert_eq!(recs.last().unwrap()["outcome"], "error");
            assert!(recs.last().unwrap()["detail"]
                .as_str()
                .unwrap()
                .contains(code));
        }
    }

    #[test]
    fn disconnect_is_reported_as_such() {
        struct Gone;
        impl ByteBridge for Gone {
            fn bulk_write(&mut self, _: &[u8], _: i32) -> i32 {
                crate::bridge::BRIDGE_DISCONNECTED
            }
            fn bulk_read(&mut self, _: &mut [u8], _: i32) -> i32 {
                crate::bridge::BRIDGE_DISCONNECTED
            }
        }
        let out = parse(&identify(Gone, "", "t", 50));
        assert_eq!(out["code"], "device_disconnected");
    }

    #[test]
    fn invalid_audit_input_fails_closed_before_touching_the_device() {
        let mut bridge = SimBridge::new(Fault::None);
        let out = parse(&identify_with(
            &mut CallbackTransport::new(&mut bridge),
            "garbage\n",
            "t",
            50,
            "usb",
        ));
        assert_eq!(out["ok"], false);
        assert_eq!(out["code"], "audit_invalid");
        assert!(out.get("audit").is_none());
        assert!(
            bridge.dev.host_packets.is_empty(),
            "device must not be touched"
        );

        let tampered = {
            let ok = parse(&audit_append("", "t", "a", "allowed", ""));
            ok["audit"].as_str().unwrap().replace("allowed", "denied")
        };
        assert_eq!(
            parse(&authorize_identify(&tampered, "t"))["code"],
            "audit_invalid"
        );
    }

    #[test]
    fn only_read_only_packets_reach_the_device() {
        use edl_core::protocol::sahara::Packet;
        let mut bridge = SimBridge::new(Fault::None);
        let out = parse(&identify_with(
            &mut CallbackTransport::new(&mut bridge),
            "",
            "t",
            2000,
            "usb",
        ));
        assert_eq!(out["ok"], true);
        assert!(!bridge.dev.host_packets.is_empty());
        assert!(bridge.dev.host_packets.iter().all(|p| matches!(
            p,
            Packet::HelloResp(_)
                | Packet::CmdExec { .. }
                | Packet::CmdExecData { .. }
                | Packet::CmdSwitchMode { .. }
        )));
        assert!(bridge.dev.received_image.is_empty());
    }

    #[test]
    fn denied_writes_are_recorded_via_authorize() {
        let out = parse(&record_denied_write("", "t", "flash.partition"));
        assert_eq!(out["ok"], true);
        let recs = records(&out);
        assert_eq!(recs[0]["action"], "command:flash.partition");
        assert_eq!(recs[0]["outcome"], "denied");
        assert_eq!(recs[0]["detail"], "no matching device profile");
    }

    #[test]
    fn audit_append_chains() {
        let a = parse(&audit_append(
            "",
            "t0",
            "usb.request_permission",
            "prompted",
            "x",
        ));
        let b = parse(&audit_append(
            a["audit"].as_str().unwrap(),
            "t1",
            "y",
            "ok",
            "",
        ));
        assert_eq!(records(&b).len(), 2);
    }

    #[test]
    #[cfg(feature = "sim")]
    fn simulated_identify_is_labelled_sim() {
        let out = parse(&identify_simulated("", "t"));
        assert_eq!(out["ok"], true);
        assert_eq!(out["source"], "sim");
        assert!(records(&out)[0]["detail"]
            .as_str()
            .unwrap()
            .starts_with("source=sim"));
    }

    #[test]
    fn features_report_version() {
        let f = parse(&features());
        assert_eq!(f["coreVersion"], env!("CARGO_PKG_VERSION"));
    }
}
