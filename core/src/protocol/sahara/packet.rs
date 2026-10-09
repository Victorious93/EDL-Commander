//! Sahara packet encoding/decoding (little-endian, `cmd:u32, len:u32, ...`).
//!
//! Layouts follow publicly documented Sahara structures. They have NOT been
//! validated against hardware captures in this repository; see docs/PROTOCOLS.md.
//! Decoding is strict: the declared length must equal the buffer length and the
//! exact size for the command, otherwise the packet is rejected.

pub const HEADER_LEN: usize = 8;

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
}

pub const CMD_HELLO: u32 = 0x1;
pub const CMD_HELLO_RESP: u32 = 0x2;
pub const CMD_READ_DATA: u32 = 0x3;
pub const CMD_END_IMAGE_TX: u32 = 0x4;
pub const CMD_DONE: u32 = 0x5;
pub const CMD_DONE_RESP: u32 = 0x6;
pub const CMD_RESET: u32 = 0x7;
pub const CMD_RESET_RESP: u32 = 0x8;

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
            Packet::Done | Packet::Reset | Packet::ResetResp => put(&mut out, &[cmd, 8]),
            Packet::DoneResp { image_tx_status } => put(&mut out, &[cmd, 12, *image_tx_status]),
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
        ]
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
