//! Drives [`SaharaHost`] over a [`Transport`] with timeouts and a packet budget.

use super::packet::Packet;
use super::state::{SaharaHost, Step};
use super::SaharaError;
use crate::diagnostics::{DiagLog, Level};
use crate::transport::{Transport, TransportError};
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct SessionOptions {
    pub timeout: Duration,
    /// Upper bound on device packets handled, so a hostile device cannot loop us forever.
    pub max_packets: u64,
}

impl Default for SessionOptions {
    fn default() -> Self {
        Self {
            timeout: Duration::from_secs(5),
            max_packets: 1_000_000,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionReport {
    pub negotiated_version: u32,
    pub bytes_sent: u64,
    pub packets_handled: u64,
}

/// Perform HELLO negotiation and upload `image` (a programmer) when the device asks for it.
///
/// Success means the device acknowledged the transfer (DONE_RESP). It does NOT mean
/// the programmer is running or supports any Firehose operation.
pub fn run_programmer_upload<T: Transport>(
    transport: &mut T,
    image: &[u8],
    opts: &SessionOptions,
    log: &mut DiagLog,
) -> Result<SessionReport, SaharaError> {
    let mut host = SaharaHost::new();
    let mut buf = vec![0u8; 4096];
    let mut packets = 0u64;
    loop {
        if packets >= opts.max_packets {
            return Err(SaharaError::PacketBudgetExceeded(opts.max_packets));
        }
        let n = transport.read(&mut buf, opts.timeout).inspect_err(|e| {
            log.push(
                Level::Error,
                "sahara.transport",
                format!("read failed: {e}"),
            );
        })?;
        let Some(rx) = buf.get(..n) else {
            log.push(
                Level::Error,
                "sahara.transport",
                format!(
                    "transport reported {n} bytes for a {}-byte buffer",
                    buf.len()
                ),
            );
            return Err(TransportError::Io(
                "transport reported more bytes than the buffer holds".into(),
            )
            .into());
        };
        let pkt = Packet::decode(rx).inspect_err(|e| {
            log.push(Level::Error, "sahara.malformed", e.to_string());
        })?;
        packets += 1;
        log.push(
            Level::Debug,
            "sahara.rx",
            format!("cmd=0x{:x} state={:?}", pkt.command(), host.state()),
        );
        let step = host.handle(&pkt, image).inspect_err(|e| {
            log.push(Level::Error, "sahara.protocol", e.to_string());
        })?;
        match step {
            Step::Send(p) => {
                transport.write(&p.encode(), opts.timeout)?;
            }
            Step::SendData(d) => {
                transport.write(&d, opts.timeout)?;
            }
            Step::Finished => {
                log.push(
                    Level::Info,
                    "sahara.done",
                    format!("{} bytes acknowledged", host.bytes_sent),
                );
                return Ok(SessionReport {
                    negotiated_version: host.negotiated_version.unwrap_or(0),
                    bytes_sent: host.bytes_sent,
                    packets_handled: packets,
                });
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Liar;
    impl Transport for Liar {
        fn write(&mut self, _: &[u8], _: Duration) -> Result<usize, TransportError> {
            panic!("must not write after a bad read");
        }
        fn read(&mut self, buf: &mut [u8], _: Duration) -> Result<usize, TransportError> {
            Ok(buf.len() + 1)
        }
    }

    #[test]
    fn oversized_read_count_is_an_error_not_a_panic() {
        let r = run_programmer_upload(
            &mut Liar,
            &[0u8; 16],
            &SessionOptions::default(),
            &mut DiagLog::new(),
        );
        assert!(matches!(
            r,
            Err(SaharaError::Transport(TransportError::Io(_)))
        ));
    }
}
