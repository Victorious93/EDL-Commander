//! Adapter from a callback-style byte pipe (Kotlin's `bulkTransfer`) to `edl_core::transport::Transport`.
//!
//! Return-code convention shared with the Kotlin `BulkTransport` interface: a value `>= 0` is
//! the number of bytes transferred; negative values are the `BRIDGE_*` codes below. Android's
//! `UsbDeviceConnection.bulkTransfer` only returns `-1` on any failure, so the Kotlin side is
//! responsible for classifying failures into these codes.

use edl_core::transport::{Transport, TransportError};
use std::time::Duration;

pub const BRIDGE_TIMEOUT: i32 = -1;
pub const BRIDGE_IO: i32 = -2;
pub const BRIDGE_DISCONNECTED: i32 = -3;

pub trait ByteBridge {
    fn bulk_write(&mut self, data: &[u8], timeout_ms: i32) -> i32;
    fn bulk_read(&mut self, buf: &mut [u8], timeout_ms: i32) -> i32;
}

impl<B: ByteBridge + ?Sized> ByteBridge for &mut B {
    fn bulk_write(&mut self, data: &[u8], timeout_ms: i32) -> i32 {
        (**self).bulk_write(data, timeout_ms)
    }
    fn bulk_read(&mut self, buf: &mut [u8], timeout_ms: i32) -> i32 {
        (**self).bulk_read(buf, timeout_ms)
    }
}

pub struct CallbackTransport<B> {
    bridge: B,
}

impl<B: ByteBridge> CallbackTransport<B> {
    pub fn new(bridge: B) -> Self {
        Self { bridge }
    }

    pub fn into_inner(self) -> B {
        self.bridge
    }
}

/// Android treats a zero timeout as "wait forever"; never pass that to the bridge.
fn timeout_ms(d: Duration) -> i32 {
    i32::try_from(d.as_millis()).unwrap_or(i32::MAX).max(1)
}

fn map_code(code: i32) -> TransportError {
    match code {
        BRIDGE_TIMEOUT => TransportError::Timeout,
        BRIDGE_DISCONNECTED => TransportError::Disconnected,
        BRIDGE_IO => TransportError::Io("bulk transfer failed".into()),
        other => TransportError::Io(format!("bridge returned unknown code {other}")),
    }
}

impl<B: ByteBridge> Transport for CallbackTransport<B> {
    fn write(&mut self, data: &[u8], timeout: Duration) -> Result<usize, TransportError> {
        let n = self.bridge.bulk_write(data, timeout_ms(timeout));
        if n < 0 {
            return Err(map_code(n));
        }
        let n = n as usize;
        if n != data.len() {
            return Err(TransportError::Io(format!(
                "short write: {n} of {} bytes",
                data.len()
            )));
        }
        Ok(n)
    }

    fn read(&mut self, buf: &mut [u8], timeout: Duration) -> Result<usize, TransportError> {
        let n = self.bridge.bulk_read(buf, timeout_ms(timeout));
        if n < 0 {
            return Err(map_code(n));
        }
        let n = n as usize;
        if n > buf.len() {
            return Err(TransportError::Io(format!(
                "bridge reported {n} bytes for a {} byte buffer",
                buf.len()
            )));
        }
        Ok(n)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::SimBridge;
    use edl_sim::Fault;

    struct Scripted(Vec<i32>);
    impl ByteBridge for Scripted {
        fn bulk_write(&mut self, _: &[u8], _: i32) -> i32 {
            self.0.remove(0)
        }
        fn bulk_read(&mut self, _: &mut [u8], _: i32) -> i32 {
            self.0.remove(0)
        }
    }

    #[test]
    fn error_codes_map_to_transport_errors() {
        let mut t = CallbackTransport::new(Scripted(vec![-1, -2, -3, -9, 99]));
        let d = Duration::from_millis(5);
        let mut b = [0u8; 8];
        assert_eq!(t.read(&mut b, d), Err(TransportError::Timeout));
        assert!(matches!(t.read(&mut b, d), Err(TransportError::Io(_))));
        assert_eq!(t.read(&mut b, d), Err(TransportError::Disconnected));
        assert!(matches!(t.read(&mut b, d), Err(TransportError::Io(m)) if m.contains("-9")));
        assert!(matches!(t.read(&mut b, d), Err(TransportError::Io(m)) if m.contains("99")));
    }

    #[test]
    fn short_write_is_an_error() {
        let mut t = CallbackTransport::new(Scripted(vec![3, 4]));
        let d = Duration::from_millis(5);
        assert!(matches!(t.write(&[0; 4], d), Err(TransportError::Io(_))));
        assert_eq!(t.write(&[0; 4], d), Ok(4));
    }

    #[test]
    fn timeouts_are_clamped_to_at_least_one_ms_and_at_most_i32_max() {
        assert_eq!(timeout_ms(Duration::ZERO), 1);
        assert_eq!(timeout_ms(Duration::from_micros(10)), 1);
        assert_eq!(timeout_ms(Duration::from_secs(5)), 5000);
        assert_eq!(timeout_ms(Duration::from_secs(u64::MAX / 2)), i32::MAX);
        let mut t = CallbackTransport::new(SimBridge::new(Fault::None));
        let mut b = [0u8; 4096];
        t.read(&mut b, Duration::ZERO).unwrap();
        assert_eq!(t.into_inner().last_timeout_ms, 1);
    }

    #[test]
    fn adapter_passes_simulated_sahara_frames_through_unchanged() {
        use edl_core::protocol::sahara::Packet;
        let mut t = CallbackTransport::new(SimBridge::new(Fault::None));
        let mut b = [0u8; 4096];
        let n = t.read(&mut b, Duration::from_secs(1)).unwrap();
        assert!(matches!(Packet::decode(&b[..n]), Ok(Packet::Hello(_))));
        assert_eq!(
            t.read(&mut b, Duration::from_millis(10)),
            Err(TransportError::Timeout)
        );
    }

    #[test]
    fn oversized_read_report_is_rejected() {
        let mut t = CallbackTransport::new(Scripted(vec![100]));
        let mut b = [0u8; 8];
        assert!(matches!(
            t.read(&mut b, Duration::from_millis(1)),
            Err(TransportError::Io(_))
        ));
    }
}
