//! Test doubles shared by unit tests.

use crate::bridge::{ByteBridge, BRIDGE_DISCONNECTED, BRIDGE_IO, BRIDGE_TIMEOUT};
use edl_core::transport::{Transport, TransportError};
use edl_sim::{Fault, SimulatedEdlDevice};
use std::time::Duration;

/// Test double for the Kotlin side: forwards to a simulated device and flattens errors to
/// the integer convention, exactly as the Kotlin `UsbBulkPipe` does for real transfers.
pub struct SimBridge {
    pub dev: SimulatedEdlDevice,
    pub last_timeout_ms: i32,
}

impl SimBridge {
    pub fn new(fault: Fault) -> Self {
        Self {
            dev: SimulatedEdlDevice::new(0, 4096, fault),
            last_timeout_ms: 0,
        }
    }
}

fn code(e: TransportError) -> i32 {
    match e {
        TransportError::Timeout => BRIDGE_TIMEOUT,
        TransportError::Disconnected => BRIDGE_DISCONNECTED,
        TransportError::Io(_) => BRIDGE_IO,
    }
}

impl ByteBridge for SimBridge {
    fn bulk_write(&mut self, data: &[u8], timeout_ms: i32) -> i32 {
        self.last_timeout_ms = timeout_ms;
        match self
            .dev
            .write(data, Duration::from_millis(timeout_ms as u64))
        {
            Ok(n) => n as i32,
            Err(e) => code(e),
        }
    }
    fn bulk_read(&mut self, buf: &mut [u8], timeout_ms: i32) -> i32 {
        self.last_timeout_ms = timeout_ms;
        match self.dev.read(buf, Duration::from_millis(timeout_ms as u64)) {
            Ok(n) => n as i32,
            Err(e) => code(e),
        }
    }
}
