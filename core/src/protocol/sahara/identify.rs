//! Read-only Sahara identification over command mode.
//!
//! Sequence: device HELLO -> host HELLO_RESP requesting command mode -> device CMD_READY ->
//! three `CMD_EXEC` queries (serial, MSM hardware ID, OEM PK hash), each answered by
//! `CMD_EXEC_RESP` followed, after `CMD_EXEC_DATA`, by raw bytes -> a best-effort
//! `SWITCH_MODE` back to image-transfer mode.
//!
//! This never uploads an image, never enters Firehose and never writes to storage. The packet
//! layouts and the hardware-ID field split come from publicly documented descriptions and have
//! NOT been validated against a real device (see docs/PROTOCOLS.md). Simulator success proves
//! only that this host logic is self-consistent.

use super::packet::{client_command, Mode, Packet, PacketError};
use super::packet::{HelloResp, MAX_RAW_RESPONSE};
use super::state::{HOST_VERSION, HOST_VERSION_COMPAT};
use super::SaharaError;
use crate::diagnostics::{DiagLog, Level};
use crate::transport::Transport;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct IdentifyOptions {
    pub timeout: Duration,
    /// Upper bound on device transfers read, so a hostile device cannot loop us forever.
    pub max_reads: u64,
}

impl Default for IdentifyOptions {
    fn default() -> Self {
        Self {
            timeout: Duration::from_secs(5),
            max_reads: 64,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceIdentity {
    /// Sahara protocol version negotiated with the device.
    pub sahara_version: u32,
    /// Chip serial number; `None` if the device reported an empty response.
    pub serial: Option<u32>,
    /// Raw 64-bit MSM hardware ID.
    pub msm_hw_id: Option<u64>,
    /// OEM public-key hash as returned by the device (a hash, not a key).
    pub oem_pk_hash: Option<Vec<u8>>,
}

impl DeviceIdentity {
    /// `(hw_id >> 32) & 0xFFFFFF`, per public descriptions of the field layout. Unverified.
    pub fn msm_id(&self) -> Option<u32> {
        self.msm_hw_id.map(|v| ((v >> 32) & 0x00FF_FFFF) as u32)
    }
    /// `(hw_id >> 16) & 0xFFFF`. Unverified.
    pub fn oem_id(&self) -> Option<u16> {
        self.msm_hw_id.map(|v| ((v >> 16) & 0xFFFF) as u16)
    }
    /// `hw_id & 0xFFFF`. Unverified.
    pub fn model_id(&self) -> Option<u16> {
        self.msm_hw_id.map(|v| (v & 0xFFFF) as u16)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IdentifyReport {
    pub identity: DeviceIdentity,
    /// True if the closing `SWITCH_MODE` to image-transfer mode was written successfully.
    pub returned_to_image_mode: bool,
    pub reads: u64,
}

struct Io<'a, T: Transport> {
    transport: &'a mut T,
    opts: &'a IdentifyOptions,
    log: &'a mut DiagLog,
    reads: u64,
    buf: Vec<u8>,
}

impl<T: Transport> Io<'_, T> {
    fn next_read(&mut self) -> Result<usize, SaharaError> {
        if self.reads >= self.opts.max_reads {
            return Err(SaharaError::PacketBudgetExceeded(self.opts.max_reads));
        }
        self.reads += 1;
        let n = self
            .transport
            .read(&mut self.buf, self.opts.timeout)
            .inspect_err(|e| {
                self.log.push(
                    Level::Error,
                    "sahara.transport",
                    format!("read failed: {e}"),
                );
            })?;
        Ok(n)
    }

    fn packet(&mut self) -> Result<Packet, SaharaError> {
        let n = self.next_read()?;
        let pkt = Packet::decode(&self.buf[..n]).inspect_err(|e: &PacketError| {
            self.log
                .push(Level::Error, "sahara.malformed", e.to_string());
        })?;
        self.log.push(
            Level::Debug,
            "sahara.rx",
            format!("cmd=0x{:x}", pkt.command()),
        );
        Ok(pkt)
    }

    fn send(&mut self, p: &Packet) -> Result<(), SaharaError> {
        self.transport.write(&p.encode(), self.opts.timeout)?;
        Ok(())
    }

    fn raw(&mut self, expected: usize) -> Result<Vec<u8>, SaharaError> {
        let mut out = Vec::with_capacity(expected);
        while out.len() < expected {
            let n = self.next_read()?;
            out.extend_from_slice(&self.buf[..n]);
        }
        if out.len() != expected {
            return Err(SaharaError::BadExecResponse {
                client_command: 0,
                reason: "device sent more raw data than announced",
            });
        }
        Ok(out)
    }

    fn unexpected(&mut self, stage: &'static str, p: &Packet) -> SaharaError {
        self.log.push(
            Level::Error,
            "sahara.protocol",
            format!(
                "unexpected cmd 0x{:x} while waiting for {stage}",
                p.command()
            ),
        );
        SaharaError::UnexpectedInStage {
            stage,
            cmd: p.command(),
        }
    }

    /// Run one client command; `Ok(None)` when the device reports an empty response.
    fn query(
        &mut self,
        cc: u32,
        valid_len: fn(usize) -> bool,
    ) -> Result<Option<Vec<u8>>, SaharaError> {
        self.send(&Packet::CmdExec { client_command: cc })?;
        let resp_length = match self.packet()? {
            Packet::CmdExecResp {
                client_command,
                resp_length,
            } if client_command == cc => resp_length,
            Packet::CmdExecResp { .. } => {
                return Err(SaharaError::BadExecResponse {
                    client_command: cc,
                    reason: "response is for a different client command",
                })
            }
            other => return Err(self.unexpected("exec response", &other)),
        };
        if resp_length == 0 {
            self.log.push(
                Level::Warn,
                "sahara.identify",
                format!("client command 0x{cc:x}: empty response"),
            );
            return Ok(None);
        }
        if resp_length as usize > MAX_RAW_RESPONSE || !valid_len(resp_length as usize) {
            return Err(SaharaError::BadExecResponse {
                client_command: cc,
                reason: "announced response length is not acceptable",
            });
        }
        self.send(&Packet::CmdExecData { client_command: cc })?;
        self.raw(resp_length as usize)
            .map(Some)
            .map_err(|e| match e {
                SaharaError::BadExecResponse { reason, .. } => SaharaError::BadExecResponse {
                    client_command: cc,
                    reason,
                },
                other => other,
            })
    }
}

fn le_u32(b: &[u8]) -> Option<u32> {
    Some(u32::from_le_bytes(b.try_into().ok()?))
}

fn le_u64(b: &[u8]) -> Option<u64> {
    Some(u64::from_le_bytes(b.try_into().ok()?))
}

/// Identify the device in command mode. Read-only; see the module docs.
pub fn run_identify<T: Transport>(
    transport: &mut T,
    opts: &IdentifyOptions,
    log: &mut DiagLog,
) -> Result<IdentifyReport, SaharaError> {
    let mut io = Io {
        transport,
        opts,
        log,
        reads: 0,
        buf: vec![0u8; 4096],
    };

    let hello = match io.packet()? {
        Packet::Hello(h) => h,
        other => return Err(io.unexpected("hello", &other)),
    };
    if hello.version_compat > HOST_VERSION {
        return Err(SaharaError::IncompatibleVersion {
            device: hello.version,
            compat: hello.version_compat,
            host: HOST_VERSION,
        });
    }
    if !matches!(hello.mode, Mode::ImageTxPending | Mode::Command) {
        return Err(SaharaError::UnsupportedMode(hello.mode));
    }
    let version = HOST_VERSION.min(hello.version);
    io.send(&Packet::HelloResp(HelloResp {
        version,
        version_compat: HOST_VERSION_COMPAT,
        status: 0,
        mode: Mode::Command,
    }))?;

    match io.packet()? {
        Packet::CmdReady => {}
        other => return Err(io.unexpected("command-mode ready", &other)),
    }

    let serial = io
        .query(client_command::SERIAL_NUM_READ, |n| n == 4)?
        .and_then(|b| le_u32(&b));
    let msm_hw_id = io
        .query(client_command::MSM_HW_ID_READ, |n| n == 8)?
        .and_then(|b| le_u64(&b));
    let oem_pk_hash = io.query(client_command::OEM_PK_HASH_READ, |_| true)?;

    let returned_to_image_mode = match io.send(&Packet::CmdSwitchMode {
        mode: Mode::ImageTxPending,
    }) {
        Ok(()) => true,
        Err(e) => {
            io.log.push(
                Level::Warn,
                "sahara.identify",
                format!("could not switch back to image mode: {e}"),
            );
            false
        }
    };
    io.log.push(
        Level::Info,
        "sahara.identify",
        "identification finished (read-only)".to_string(),
    );
    Ok(IdentifyReport {
        identity: DeviceIdentity {
            sahara_version: version,
            serial,
            msm_hw_id,
            oem_pk_hash,
        },
        returned_to_image_mode,
        reads: io.reads,
    })
}
