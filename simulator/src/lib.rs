//! Deterministic simulated EDL target. It speaks the *host-facing* side of Sahara as
//! implemented from public protocol descriptions. Passing tests against it proves the
//! host logic is self-consistent, NOT that any real device behaves this way.

use edl_core::protocol::sahara::packet::{Hello, Mode, Packet};
use edl_core::transport::{Transport, TransportError};
use edl_core::usb::{UsbDeviceInfo, UsbEnumerator, QDLOADER_9008_PID, QUALCOMM_VID};
use std::collections::VecDeque;
use std::time::Duration;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Fault {
    None,
    /// HELLO with a corrupted length field.
    MalformedHello,
    /// Device never speaks (no HELLO) -> read times out.
    Silent,
    /// Device goes quiet after the host's HELLO_RESP.
    SilentAfterHelloResp,
    /// Disconnect once this many programmer bytes were received.
    DisconnectAfterBytes(u64),
    /// Device announces Command mode (unsupported by the Phase 1 host).
    CommandModeHello,
    /// Device requires a newer protocol than the host supports.
    IncompatibleVersion,
    /// Device asks for bytes beyond the end of the image.
    ReadBeyondImage,
    /// Device rejects the image at END_IMAGE_TX.
    RejectImage,
    /// Device sends DONE_RESP before the transfer finished.
    OutOfOrderDoneResp,
    /// Device keeps asking for 1-byte chunks (budget exhaustion test).
    EndlessTinyReads,
    /// Command mode: device answers a query with an oversized `resp_length`.
    OversizedExecResponse,
    /// Command mode: device answers a query for a different client command.
    WrongExecClient,
    /// Command mode: device reports an empty OEM PK hash response.
    EmptyPkHash,
    /// Command mode: device goes quiet after CMD_READY (queries time out).
    SilentAfterReady,
    /// Command mode: device sends more raw bytes than it announced.
    ExtraRawData,
}

/// Values the simulated device reports in command mode. Synthetic, not any real device's.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SimIdentity {
    pub serial: u32,
    pub hw_id: u64,
    pub pk_hash: Vec<u8>,
}

impl Default for SimIdentity {
    fn default() -> Self {
        Self {
            serial: 0x1234_ABCD,
            hw_id: 0x0009_B0E1_0123_0045,
            pk_hash: (0..32u8)
                .map(|i| i.wrapping_mul(7).wrapping_add(1))
                .collect(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct SimulatedEdlDevice {
    image_len: u32,
    chunk: u32,
    fault: Fault,
    outbound: VecDeque<Vec<u8>>,
    expect_raw: Option<usize>,
    next_offset: u32,
    pub received_image: Vec<u8>,
    pub host_packets: Vec<Packet>,
    got_done: bool,
    disconnected: bool,
    identity: SimIdentity,
    /// Mode the host asked for via CMD_SWITCH_MODE, if any.
    pub switched_to: Option<Mode>,
}

impl SimulatedEdlDevice {
    /// `image_len` is the programmer size the device will request, in `chunk`-sized reads.
    pub fn new(image_len: usize, chunk: u32, fault: Fault) -> Self {
        let mut d = Self {
            image_len: image_len as u32,
            chunk,
            fault: fault.clone(),
            outbound: VecDeque::new(),
            expect_raw: None,
            next_offset: 0,
            received_image: Vec::new(),
            host_packets: Vec::new(),
            got_done: false,
            disconnected: false,
            identity: SimIdentity::default(),
            switched_to: None,
        };
        let mut hello = Packet::Hello(Hello {
            version: 2,
            version_compat: 1,
            max_cmd_pkt_len: 1024,
            mode: Mode::ImageTxPending,
        })
        .encode();
        match fault {
            Fault::Silent => {}
            Fault::MalformedHello => {
                hello[4] = 0x31;
                d.outbound.push_back(hello);
            }
            Fault::CommandModeHello => d.outbound.push_back(
                Packet::Hello(Hello {
                    version: 2,
                    version_compat: 1,
                    max_cmd_pkt_len: 1024,
                    mode: Mode::Command,
                })
                .encode(),
            ),
            Fault::IncompatibleVersion => d.outbound.push_back(
                Packet::Hello(Hello {
                    version: 9,
                    version_compat: 9,
                    max_cmd_pkt_len: 1024,
                    mode: Mode::ImageTxPending,
                })
                .encode(),
            ),
            _ => d.outbound.push_back(hello),
        }
        d
    }

    pub fn with_identity(mut self, identity: SimIdentity) -> Self {
        self.identity = identity;
        self
    }

    pub fn is_complete(&self) -> bool {
        self.got_done
    }

    fn queue(&mut self, p: Packet) {
        self.outbound.push_back(p.encode());
    }

    fn exec_bytes(&self, client_command: u32) -> Vec<u8> {
        use edl_core::protocol::sahara::packet::client_command as cc;
        match client_command {
            cc::SERIAL_NUM_READ => self.identity.serial.to_le_bytes().to_vec(),
            cc::MSM_HW_ID_READ => self.identity.hw_id.to_le_bytes().to_vec(),
            cc::OEM_PK_HASH_READ if self.fault == Fault::EmptyPkHash => Vec::new(),
            cc::OEM_PK_HASH_READ => self.identity.pk_hash.clone(),
            _ => Vec::new(),
        }
    }

    fn answer_exec(&mut self, client_command: u32) {
        if self.fault == Fault::SilentAfterReady {
            return;
        }
        let announced = if self.fault == Fault::OversizedExecResponse {
            4096
        } else {
            self.exec_bytes(client_command).len() as u32
        };
        let echoed = if self.fault == Fault::WrongExecClient {
            client_command + 1
        } else {
            client_command
        };
        self.queue(Packet::CmdExecResp {
            client_command: echoed,
            resp_length: announced,
        });
    }

    fn request_next(&mut self) {
        if self.fault == Fault::EndlessTinyReads {
            self.queue(Packet::ReadData {
                image_id: 1,
                offset: 0,
                length: 1,
            });
            self.expect_raw = Some(1);
            return;
        }
        let remaining = self.image_len - self.next_offset;
        if self.fault == Fault::ReadBeyondImage {
            self.queue(Packet::ReadData {
                image_id: 1,
                offset: self.image_len,
                length: 16,
            });
            return;
        }
        if remaining == 0 {
            let status = if self.fault == Fault::RejectImage {
                3
            } else {
                0
            };
            self.queue(Packet::EndImageTx {
                image_id: 1,
                status,
            });
            return;
        }
        let len = remaining.min(self.chunk);
        self.queue(Packet::ReadData {
            image_id: 1,
            offset: self.next_offset,
            length: len,
        });
        self.expect_raw = Some(len as usize);
    }
}

impl Transport for SimulatedEdlDevice {
    fn write(&mut self, data: &[u8], _timeout: Duration) -> Result<usize, TransportError> {
        if self.disconnected {
            return Err(TransportError::Disconnected);
        }
        if let Some(n) = self.expect_raw.take() {
            if data.len() != n {
                return Err(TransportError::Io(format!(
                    "sim: expected {n} raw bytes, got {}",
                    data.len()
                )));
            }
            if self.fault != Fault::EndlessTinyReads {
                self.received_image.extend_from_slice(data);
                self.next_offset += n as u32;
            }
            if let Fault::DisconnectAfterBytes(limit) = self.fault {
                if self.received_image.len() as u64 >= limit {
                    self.disconnected = true;
                    return Ok(data.len());
                }
            }
            self.request_next();
            return Ok(data.len());
        }
        let pkt = Packet::decode(data)
            .map_err(|e| TransportError::Io(format!("sim: bad host packet: {e}")))?;
        self.host_packets.push(pkt.clone());
        match pkt {
            Packet::HelloResp(h) if h.mode == Mode::Command => {
                if self.fault != Fault::SilentAfterHelloResp {
                    self.queue(Packet::CmdReady);
                }
            }
            Packet::CmdExec { client_command } => self.answer_exec(client_command),
            Packet::CmdExecData { client_command } => {
                let mut raw = self.exec_bytes(client_command);
                if self.fault == Fault::ExtraRawData {
                    raw.extend_from_slice(&[0xEE; 8]);
                }
                self.outbound.push_back(raw);
            }
            Packet::CmdSwitchMode { mode } => self.switched_to = Some(mode),
            Packet::HelloResp(_) if self.fault == Fault::SilentAfterHelloResp => {}
            Packet::HelloResp(_) if self.fault == Fault::OutOfOrderDoneResp => {
                self.queue(Packet::DoneResp { image_tx_status: 1 })
            }
            Packet::HelloResp(_) => self.request_next(),
            Packet::Done => {
                self.got_done = true;
                self.queue(Packet::DoneResp { image_tx_status: 1 });
            }
            other => {
                return Err(TransportError::Io(format!(
                    "sim: unexpected host packet {other:?}"
                )))
            }
        }
        Ok(data.len())
    }

    fn read(&mut self, buf: &mut [u8], _timeout: Duration) -> Result<usize, TransportError> {
        if self.disconnected {
            return Err(TransportError::Disconnected);
        }
        let Some(p) = self.outbound.pop_front() else {
            return Err(TransportError::Timeout);
        };
        let n = p.len().min(buf.len());
        buf[..n].copy_from_slice(&p[..n]);
        Ok(n)
    }
}

/// Fake USB bus: one EDL-looking device plus decoys.
pub struct SimulatedBus;

impl UsbEnumerator for SimulatedBus {
    fn enumerate(&self) -> Result<Vec<UsbDeviceInfo>, TransportError> {
        let mk = |v, p, addr, prod: &str| UsbDeviceInfo {
            vendor_id: v,
            product_id: p,
            bus: 1,
            address: addr,
            manufacturer: Some("sim".into()),
            product: Some(prod.into()),
            serial: None,
        };
        Ok(vec![
            mk(0x18d1, 0x4ee7, 2, "sim-adb-phone"),
            mk(QUALCOMM_VID, QDLOADER_9008_PID, 3, "sim QDLoader 9008"),
            mk(QUALCOMM_VID, 0x901d, 4, "sim diag (not EDL)"),
        ])
    }
}
