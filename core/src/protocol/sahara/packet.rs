//! Sahara packet encoding/decoding (little-endian, `cmd:u32, len:u32, ...`).
//!
//! Layouts follow publicly documented Sahara structures. They have NOT been
//! validated against hardware captures in this repository; see docs/PROTOCOLS.md.
//! Decoding is strict: the declared length must equal the buffer length and the
//! exact size for the command, otherwise the packet is rejected.

pub const HEADER_LEN: usize = 8;
/// Largest raw `CMD_EXEC_DATA` payload this host will accept (hashes are 32-64 bytes).
pub const MAX_RAW_RESPONSE: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    ImageTxPending = 0,
    ImageTxComplete = 1,
    MemoryDebug = 2,
    Command = 3,
}

impl Mode {
    fn from_u32(v: u32) -> Option<Mode> {
        Some(match v {
            0 => Mode::ImageTxPending,
            1 => Mode::ImageTxComplete,
            2 => Mode::MemoryDebug,
            3 => Mode::Command,
            _ => return None,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Hello {
    pub version: u32,
    pub version_compat: u32,
    pub max_cmd_pkt_len: u32,
    pub mode: Mode,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HelloResp {
    pub version: u32,
    pub version_compat: u32,
    pub status: u32,
    pub mode: Mode,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Packet {
    Hello(Hello),
    HelloResp(HelloResp),
    ReadData {
        image_id: u32,
        offset: u32,
        length: u32,
    },
    EndImageTx {
        image_id: u32,
        status: u32,
    },
    Done,
    DoneResp {
        image_tx_status: u32,
    },
    Reset,
    ResetResp,
    /// Command mode: device is ready to accept `CmdExec` requests.
    CmdReady,
    /// Command mode: ask the device to switch to `mode`.
    CmdSwitchMode {
        mode: Mode,
    },
    /// Command mode: execute a client command (see [`client_command`]).
    CmdExec {
        client_command: u32,
    },
    /// Command mode: device announces `resp_length` bytes of raw data for `client_command`.
    CmdExecResp {
        client_command: u32,
        resp_length: u32,
    },
    /// Command mode: host asks the device to send the raw data announced by `CmdExecResp`.
    CmdExecData {
        client_command: u32,
    },
}

/// Read-only Sahara client commands used for identification.
pub mod client_command {
    pub const SERIAL_NUM_READ: u32 = 0x1;
    pub const MSM_HW_ID_READ: u32 = 0x2;
    pub const OEM_PK_HASH_READ: u32 = 0x3;
}

pub const CMD_HELLO: u32 = 0x1;
pub const CMD_HELLO_RESP: u32 = 0x2;
pub const CMD_READ_DATA: u32 = 0x3;
pub const CMD_END_IMAGE_TX: u32 = 0x4;
pub const CMD_DONE: u32 = 0x5;
pub const CMD_DONE_RESP: u32 = 0x6;
pub const CMD_RESET: u32 = 0x7;
pub const CMD_RESET_RESP: u32 = 0x8;
pub const CMD_CMD_READY: u32 = 0xb;
pub const CMD_SWITCH_MODE: u32 = 0xc;
pub const CMD_EXEC: u32 = 0xd;
pub const CMD_EXEC_RESP: u32 = 0xe;
pub const CMD_EXEC_DATA: u32 = 0xf;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum PacketError {
    #[error("packet shorter than header ({0} bytes)")]
    Truncated(usize),
    #[error("declared length {declared} != received {actual}")]
    LengthMismatch { declared: u32, actual: usize },
    #[error("unsupported command 0x{0:x}")]
    UnsupportedCommand(u32),
    #[error("command 0x{cmd:x} has invalid length {len} (expected {expected})")]
    BadSize {
        cmd: u32,
        len: usize,
        expected: usize,
    },
    #[error("invalid field value in command 0x{cmd:x}: {what}")]
    InvalidField { cmd: u32, what: &'static str },
}

fn put(out: &mut Vec<u8>, words: &[u32]) {
    for w in words {
        out.extend_from_slice(&w.to_le_bytes());
    }
}

fn word(b: &[u8], idx: usize) -> u32 {
    u32::from_le_bytes(b[idx * 4..idx * 4 + 4].try_into().unwrap())
}

impl Packet {
    pub fn command(&self) -> u32 {
        match self {
            Packet::Hello(_) => CMD_HELLO,
            Packet::HelloResp(_) => CMD_HELLO_RESP,
            Packet::ReadData { .. } => CMD_READ_DATA,
            Packet::EndImageTx { .. } => CMD_END_IMAGE_TX,
            Packet::Done => CMD_DONE,
            Packet::DoneResp { .. } => CMD_DONE_RESP,
            Packet::Reset => CMD_RESET,
            Packet::ResetResp => CMD_RESET_RESP,
            Packet::CmdReady => CMD_CMD_READY,
            Packet::CmdSwitchMode { .. } => CMD_SWITCH_MODE,
            Packet::CmdExec { .. } => CMD_EXEC,
            Packet::CmdExecResp { .. } => CMD_EXEC_RESP,
            Packet::CmdExecData { .. } => CMD_EXEC_DATA,
        }
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let cmd = self.command();
        match self {
            Packet::Hello(h) => {
                put(
                    &mut out,
                    &[
                        cmd,
                        48,
                        h.version,
                        h.version_compat,
                        h.max_cmd_pkt_len,
                        h.mode as u32,
                    ],
                );
                put(&mut out, &[0; 6]);
            }
            Packet::HelloResp(h) => {
                put(
                    &mut out,
                    &[
                        cmd,
                        48,
                        h.version,
                        h.version_compat,
                        h.status,
                        h.mode as u32,
                    ],
                );
                put(&mut out, &[0; 6]);
            }
            Packet::ReadData {
                image_id,
                offset,
                length,
            } => put(&mut out, &[cmd, 20, *image_id, *offset, *length]),
            Packet::EndImageTx { image_id, status } => {
                put(&mut out, &[cmd, 16, *image_id, *status])
            }
            Packet::Done | Packet::Reset | Packet::ResetResp | Packet::CmdReady => {
                put(&mut out, &[cmd, 8])
            }
            Packet::DoneResp { image_tx_status } => put(&mut out, &[cmd, 12, *image_tx_status]),
            Packet::CmdSwitchMode { mode } => put(&mut out, &[cmd, 12, *mode as u32]),
            Packet::CmdExec { client_command } | Packet::CmdExecData { client_command } => {
                put(&mut out, &[cmd, 12, *client_command])
            }
            Packet::CmdExecResp {
                client_command,
                resp_length,
            } => put(&mut out, &[cmd, 16, *client_command, *resp_length]),
        }
        out
    }

    pub fn decode(b: &[u8]) -> Result<Packet, PacketError> {
        if b.len() < HEADER_LEN {
            return Err(PacketError::Truncated(b.len()));
        }
        let cmd = word(b, 0);
        let declared = word(b, 1);
        if declared as usize != b.len() {
            return Err(PacketError::LengthMismatch {
                declared,
                actual: b.len(),
            });
        }
        let expect = |n: usize| -> Result<(), PacketError> {
            if b.len() == n {
                Ok(())
            } else {
                Err(PacketError::BadSize {
                    cmd,
                    len: b.len(),
                    expected: n,
                })
            }
        };
        let mode =
            |v: u32| Mode::from_u32(v).ok_or(PacketError::InvalidField { cmd, what: "mode" });
        match cmd {
            CMD_HELLO => {
                expect(48)?;
                Ok(Packet::Hello(Hello {
                    version: word(b, 2),
                    version_compat: word(b, 3),
                    max_cmd_pkt_len: word(b, 4),
                    mode: mode(word(b, 5))?,
                }))
            }
            CMD_HELLO_RESP => {
                expect(48)?;
                Ok(Packet::HelloResp(HelloResp {
                    version: word(b, 2),
                    version_compat: word(b, 3),
                    status: word(b, 4),
                    mode: mode(word(b, 5))?,
                }))
            }
            CMD_READ_DATA => {
                expect(20)?;
                Ok(Packet::ReadData {
                    image_id: word(b, 2),
                    offset: word(b, 3),
                    length: word(b, 4),
                })
            }
            CMD_END_IMAGE_TX => {
                expect(16)?;
                Ok(Packet::EndImageTx {
                    image_id: word(b, 2),
                    status: word(b, 3),
                })
            }
            CMD_DONE => expect(8).map(|_| Packet::Done),
            CMD_DONE_RESP => {
                expect(12)?;
                Ok(Packet::DoneResp {
                    image_tx_status: word(b, 2),
                })
            }
            CMD_RESET => expect(8).map(|_| Packet::Reset),
            CMD_RESET_RESP => expect(8).map(|_| Packet::ResetResp),
            CMD_CMD_READY => expect(8).map(|_| Packet::CmdReady),
            CMD_SWITCH_MODE => {
                expect(12)?;
                Ok(Packet::CmdSwitchMode {
                    mode: mode(word(b, 2))?,
                })
            }
            CMD_EXEC => {
                expect(12)?;
                Ok(Packet::CmdExec {
                    client_command: word(b, 2),
                })
            }
            CMD_EXEC_RESP => {
                expect(16)?;
                Ok(Packet::CmdExecResp {
                    client_command: word(b, 2),
                    resp_length: word(b, 3),
                })
            }
            CMD_EXEC_DATA => {
                expect(12)?;
                Ok(Packet::CmdExecData {
                    client_command: word(b, 2),
                })
            }
            other => Err(PacketError::UnsupportedCommand(other)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn samples() -> Vec<Packet> {
        vec![
            Packet::Hello(Hello {
                version: 2,
                version_compat: 1,
                max_cmd_pkt_len: 1024,
                mode: Mode::ImageTxPending,
            }),
            Packet::HelloResp(HelloResp {
                version: 2,
                version_compat: 1,
                status: 0,
                mode: Mode::ImageTxPending,
            }),
            Packet::ReadData {
                image_id: 7,
                offset: 0,
                length: 4096,
            },
            Packet::EndImageTx {
                image_id: 7,
                status: 0,
            },
            Packet::Done,
            Packet::DoneResp { image_tx_status: 1 },
            Packet::Reset,
            Packet::ResetResp,
            Packet::CmdReady,
            Packet::CmdSwitchMode {
                mode: Mode::ImageTxPending,
            },
            Packet::CmdExec {
                client_command: client_command::SERIAL_NUM_READ,
            },
            Packet::CmdExecResp {
                client_command: client_command::MSM_HW_ID_READ,
                resp_length: 8,
            },
            Packet::CmdExecData {
                client_command: client_command::OEM_PK_HASH_READ,
            },
        ]
    }

    #[test]
    fn command_mode_fixture_bytes() {
        // Synthetic fixtures for the command-mode packets (not hardware captures).
        assert_eq!(Packet::CmdReady.encode(), [0xb, 0, 0, 0, 8, 0, 0, 0]);
        assert_eq!(
            Packet::CmdExec { client_command: 2 }.encode(),
            [0xd, 0, 0, 0, 12, 0, 0, 0, 2, 0, 0, 0]
        );
        assert_eq!(
            Packet::decode(&[0xe, 0, 0, 0, 16, 0, 0, 0, 3, 0, 0, 0, 32, 0, 0, 0]).unwrap(),
            Packet::CmdExecResp {
                client_command: 3,
                resp_length: 32
            }
        );
        assert!(matches!(
            Packet::decode(&[0xc, 0, 0, 0, 12, 0, 0, 0, 9, 0, 0, 0]),
            Err(PacketError::InvalidField { .. })
        ));
        assert!(matches!(
            Packet::decode(&[0xf, 0, 0, 0, 8, 0, 0, 0]),
            Err(PacketError::BadSize { .. })
        ));
    }

    #[test]
    fn roundtrip_all() {
        for p in samples() {
            assert_eq!(Packet::decode(&p.encode()).unwrap(), p);
        }
    }

    #[test]
    fn hello_fixture_bytes() {
        // Synthetic fixture: HELLO, v2, compat 1, max 0x400, mode 0.
        let mut fx = vec![
            1, 0, 0, 0, 48, 0, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0,
        ];
        fx.extend_from_slice(&[0; 24]);
        assert_eq!(Packet::decode(&fx).unwrap(), samples()[0]);
        assert_eq!(samples()[0].encode(), fx);
    }

    #[test]
    fn rejects_malformed() {
        assert!(matches!(
            Packet::decode(&[1, 0, 0]),
            Err(PacketError::Truncated(3))
        ));
        let mut p = Packet::Done.encode();
        p.push(0);
        assert!(matches!(
            Packet::decode(&p),
            Err(PacketError::LengthMismatch { .. })
        ));
        let mut bad = Packet::Hello(Hello {
            version: 2,
            version_compat: 1,
            max_cmd_pkt_len: 1,
            mode: Mode::Command,
        })
        .encode();
        bad[20] = 9; // invalid mode
        assert!(matches!(
            Packet::decode(&bad),
            Err(PacketError::InvalidField { .. })
        ));
        let unknown = [0xEE, 0, 0, 0, 8, 0, 0, 0];
        assert!(matches!(
            Packet::decode(&unknown),
            Err(PacketError::UnsupportedCommand(0xEE))
        ));
        // Correct header length but wrong size for the command.
        let rd = [CMD_READ_DATA as u8, 0, 0, 0, 12, 0, 0, 0, 0, 0, 0, 0];
        assert!(matches!(
            Packet::decode(&rd),
            Err(PacketError::BadSize { .. })
        ));
    }

    #[test]
    fn decode_never_panics_on_garbage() {
        for len in 0..80usize {
            for seed in 0..=255u8 {
                let buf: Vec<u8> = (0..len)
                    .map(|i| seed.wrapping_mul(31).wrapping_add(i as u8))
                    .collect();
                let _ = Packet::decode(&buf);
            }
        }
    }
}
