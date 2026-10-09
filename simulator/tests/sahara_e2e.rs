use edl_core::diagnostics::DiagLog;
use edl_core::protocol::sahara::{run_programmer_upload, SaharaError, SessionOptions};
use edl_core::sha256_hex;
use edl_core::transport::TransportError;
use edl_core::usb::{edl_candidates, UsbEnumerator};
use edl_sim::{Fault, SimulatedBus, SimulatedEdlDevice};

fn image(n: usize) -> Vec<u8> {
    (0..n).map(|i| (i * 7 + 3) as u8).collect()
}

fn run(
    fault: Fault,
    img: &[u8],
    chunk: u32,
    opts: SessionOptions,
) -> (
    Result<edl_core::protocol::sahara::SessionReport, SaharaError>,
    SimulatedEdlDevice,
    DiagLog,
) {
    let mut dev = SimulatedEdlDevice::new(img.len(), chunk, fault);
    let mut log = DiagLog::new();
    let r = run_programmer_upload(&mut dev, img, &opts, &mut log);
    (r, dev, log)
}

#[test]
fn discovery_finds_only_edl_candidate() {
    let devs = SimulatedBus.enumerate().unwrap();
    let c = edl_candidates(&devs);
    assert_eq!(c.len(), 1);
    assert_eq!(c[0].address, 3);
}

#[test]
fn full_upload_delivers_identical_bytes() {
    let img = image(10_000);
    let (r, dev, log) = run(Fault::None, &img, 4096, SessionOptions::default());
    let rep = r.unwrap();
    assert_eq!(rep.bytes_sent, 10_000);
    assert_eq!(rep.negotiated_version, 2);
    assert!(dev.is_complete());
    assert_eq!(sha256_hex(&dev.received_image), sha256_hex(&img));
    assert!(log.has_code("sahara.done"));
}

#[test]
fn malformed_hello_is_rejected() {
    let (r, _, log) = run(
        Fault::MalformedHello,
        &image(64),
        64,
        SessionOptions::default(),
    );
    assert!(matches!(r, Err(SaharaError::Packet(_))));
    assert!(log.has_code("sahara.malformed"));
}

#[test]
fn silent_device_times_out() {
    for f in [Fault::Silent, Fault::SilentAfterHelloResp] {
        let (r, _, _) = run(f, &image(64), 64, SessionOptions::default());
        assert_eq!(r, Err(SaharaError::Transport(TransportError::Timeout)));
    }
}

#[test]
fn disconnect_mid_transfer_is_reported_not_success() {
    let (r, dev, _) = run(
        Fault::DisconnectAfterBytes(4096),
        &image(10_000),
        4096,
        SessionOptions::default(),
    );
    assert_eq!(r, Err(SaharaError::Transport(TransportError::Disconnected)));
    assert!(!dev.is_complete());
}

#[test]
fn unsupported_or_incompatible_hello_rejected() {
    let (r, _, _) = run(
        Fault::CommandModeHello,
        &image(64),
        64,
        SessionOptions::default(),
    );
    assert!(matches!(r, Err(SaharaError::UnsupportedMode(_))));
    let (r, _, _) = run(
        Fault::IncompatibleVersion,
        &image(64),
        64,
        SessionOptions::default(),
    );
    assert!(matches!(r, Err(SaharaError::IncompatibleVersion { .. })));
}

#[test]
fn out_of_range_request_and_out_of_order_packets_rejected() {
    let (r, _, _) = run(
        Fault::ReadBeyondImage,
        &image(64),
        64,
        SessionOptions::default(),
    );
    assert!(matches!(r, Err(SaharaError::ReadOutOfRange { .. })));
    let (r, _, _) = run(
        Fault::OutOfOrderDoneResp,
        &image(64),
        64,
        SessionOptions::default(),
    );
    assert!(matches!(r, Err(SaharaError::UnexpectedPacket { .. })));
}

#[test]
fn device_rejection_is_not_success() {
    let (r, dev, _) = run(
        Fault::RejectImage,
        &image(128),
        64,
        SessionOptions::default(),
    );
    assert_eq!(r, Err(SaharaError::DeviceRejectedImage(3)));
    assert!(!dev.is_complete());
}

#[test]
fn packet_budget_stops_hostile_device() {
    let opts = SessionOptions {
        max_packets: 50,
        ..Default::default()
    };
    let (r, _, _) = run(Fault::EndlessTinyReads, &image(64), 64, opts);
    assert_eq!(r, Err(SaharaError::PacketBudgetExceeded(50)));
}
