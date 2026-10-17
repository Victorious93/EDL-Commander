//! Read-only Sahara identification against the simulator. Passing proves host self-consistency,
//! not behaviour of any real device.

use edl_core::diagnostics::DiagLog;
use edl_core::protocol::sahara::{
    run_identify, IdentifyOptions, IdentifyReport, Mode, Packet, SaharaError,
};
use edl_core::transport::TransportError;
use edl_sim::{Fault, SimIdentity, SimulatedEdlDevice};

fn run(
    fault: Fault,
) -> (
    Result<IdentifyReport, SaharaError>,
    SimulatedEdlDevice,
    DiagLog,
) {
    let mut dev = SimulatedEdlDevice::new(0, 4096, fault);
    let mut log = DiagLog::new();
    let r = run_identify(&mut dev, &IdentifyOptions::default(), &mut log);
    (r, dev, log)
}

#[test]
fn identifies_serial_hw_id_and_pk_hash() {
    let (r, dev, log) = run(Fault::None);
    let rep = r.unwrap();
    let want = SimIdentity::default();
    assert_eq!(rep.identity.sahara_version, 2);
    assert_eq!(rep.identity.serial, Some(want.serial));
    assert_eq!(rep.identity.msm_hw_id, Some(want.hw_id));
    assert_eq!(rep.identity.msm_id(), Some(0x0009_B0E1));
    assert_eq!(rep.identity.oem_id(), Some(0x0123));
    assert_eq!(rep.identity.model_id(), Some(0x0045));
    assert_eq!(rep.identity.oem_pk_hash.as_deref(), Some(&want.pk_hash[..]));
    assert!(rep.returned_to_image_mode);
    assert_eq!(dev.switched_to, Some(Mode::ImageTxPending));
    assert!(log.has_code("sahara.identify"));
}

#[test]
fn only_read_only_packets_are_ever_sent() {
    let (r, dev, _) = run(Fault::None);
    r.unwrap();
    assert!(!dev.is_complete(), "no DONE: no image transfer happened");
    assert!(dev.received_image.is_empty(), "no programmer bytes sent");
    for p in &dev.host_packets {
        assert!(
            matches!(
                p,
                Packet::HelloResp(_)
                    | Packet::CmdExec { .. }
                    | Packet::CmdExecData { .. }
                    | Packet::CmdSwitchMode { .. }
            ),
            "unexpected host packet {p:?}"
        );
        if let Packet::HelloResp(h) = p {
            assert_eq!(h.mode, Mode::Command);
        }
    }
}

#[test]
fn custom_identity_and_empty_pk_hash() {
    let id = SimIdentity {
        serial: 7,
        hw_id: 1,
        pk_hash: vec![9; 48],
    };
    let mut dev = SimulatedEdlDevice::new(0, 4096, Fault::None).with_identity(id);
    let rep = run_identify(&mut dev, &IdentifyOptions::default(), &mut DiagLog::new()).unwrap();
    assert_eq!(rep.identity.oem_pk_hash, Some(vec![9; 48]));

    let (r, _, log) = run(Fault::EmptyPkHash);
    let rep = r.unwrap();
    assert_eq!(rep.identity.oem_pk_hash, None);
    assert!(rep.identity.serial.is_some());
    assert!(log
        .events
        .iter()
        .any(|e| e.message.contains("empty response")));
}

#[test]
fn hostile_or_broken_devices_are_rejected() {
    assert!(matches!(
        run(Fault::Silent).0,
        Err(SaharaError::Transport(TransportError::Timeout))
    ));
    assert!(matches!(
        run(Fault::MalformedHello).0,
        Err(SaharaError::Packet(_))
    ));
    assert!(matches!(
        run(Fault::IncompatibleVersion).0,
        Err(SaharaError::IncompatibleVersion { .. })
    ));
    assert!(matches!(
        run(Fault::SilentAfterHelloResp).0,
        Err(SaharaError::Transport(TransportError::Timeout))
    ));
    assert!(matches!(
        run(Fault::SilentAfterReady).0,
        Err(SaharaError::Transport(TransportError::Timeout))
    ));
    assert!(matches!(
        run(Fault::OversizedExecResponse).0,
        Err(SaharaError::BadExecResponse { .. })
    ));
    assert!(matches!(
        run(Fault::WrongExecClient).0,
        Err(SaharaError::BadExecResponse { .. })
    ));
}

#[test]
fn extra_raw_data_is_rejected() {
    assert!(matches!(
        run(Fault::ExtraRawData).0,
        Err(SaharaError::BadExecResponse {
            client_command: 1,
            ..
        })
    ));
}

#[test]
fn read_budget_is_enforced() {
    let mut dev = SimulatedEdlDevice::new(0, 4096, Fault::None);
    let opts = IdentifyOptions {
        max_reads: 3,
        ..Default::default()
    };
    assert!(matches!(
        run_identify(&mut dev, &opts, &mut DiagLog::new()),
        Err(SaharaError::PacketBudgetExceeded(3))
    ));
}
