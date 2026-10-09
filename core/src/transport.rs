//! Strict boundary between protocol logic and USB backends.

use std::time::Duration;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum TransportError {
    #[error("transfer timed out")]
    Timeout,
    #[error("device disconnected")]
    Disconnected,
    #[error("transport I/O error: {0}")]
    Io(String),
}

/// A bulk-endpoint pipe to one device. One `read` returns at most one USB
/// transfer; protocol code must not assume it returns more than one packet.
pub trait Transport {
    fn write(&mut self, data: &[u8], timeout: Duration) -> Result<usize, TransportError>;
    fn read(&mut self, buf: &mut [u8], timeout: Duration) -> Result<usize, TransportError>;
}
