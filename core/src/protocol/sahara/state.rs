//! Pure, I/O-free host state machine. Every (state, packet) pair not listed
//! is rejected and moves the machine to `Failed`.

use super::packet::{HelloResp, Mode, Packet};
use super::SaharaError;

pub const HOST_VERSION: u32 = 2;
pub const HOST_VERSION_COMPAT: u32 = 1;
/// Largest single chunk we will honour from a device request.
pub const MAX_CHUNK: u32 = 1 << 20;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HostState {
    AwaitHello,
    Transferring,
    AwaitDoneResp,
    Complete,
    Failed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Step {
    /// Send this control packet.
    Send(Packet),
    /// Send these raw image bytes (answer to READ_DATA).
    SendData(Vec<u8>),
    /// Handshake and upload finished.
    Finished,
}

#[derive(Debug)]
pub struct SaharaHost {
    state: HostState,
    pub negotiated_version: Option<u32>,
    pub image_id: Option<u32>,
    pub bytes_sent: u64,
}

impl Default for SaharaHost {
    fn default() -> Self {
        Self::new()
    }
}

impl SaharaHost {
    pub fn new() -> Self {
        Self {
            state: HostState::AwaitHello,
            negotiated_version: None,
            image_id: None,
            bytes_sent: 0,
        }
    }

    pub fn state(&self) -> HostState {
        self.state
    }

    pub fn handle(&mut self, pkt: &Packet, image: &[u8]) -> Result<Step, SaharaError> {
        let res = self.handle_inner(pkt, image);
        if res.is_err() {
            self.state = HostState::Failed;
        }
        res
    }

    fn handle_inner(&mut self, pkt: &Packet, image: &[u8]) -> Result<Step, SaharaError> {
        match (self.state, pkt) {
            (HostState::Complete | HostState::Failed, _) => Err(SaharaError::Terminated),
            (HostState::AwaitHello, Packet::Hello(h)) => {
                if h.version_compat > HOST_VERSION {
                    return Err(SaharaError::IncompatibleVersion {
                        device: h.version,
                        compat: h.version_compat,
                        host: HOST_VERSION,
                    });
                }
                if h.mode != Mode::ImageTxPending {
                    return Err(SaharaError::UnsupportedMode(h.mode));
                }
                let version = HOST_VERSION.min(h.version);
                self.negotiated_version = Some(version);
                self.state = HostState::Transferring;
                Ok(Step::Send(Packet::HelloResp(HelloResp {
                    version,
                    version_compat: HOST_VERSION_COMPAT,
                    status: 0,
                    mode: h.mode,
                })))
            }
            (
                HostState::Transferring,
                Packet::ReadData {
                    image_id,
                    offset,
                    length,
                },
            ) => {
                if *length == 0 || *length > MAX_CHUNK {
                    return Err(SaharaError::BadChunkLength(*length));
                }
                let start = *offset as usize;
                let end = start.checked_add(*length as usize);
                match end {
                    Some(end) if end <= image.len() => {
                        match self.image_id {
                            None => self.image_id = Some(*image_id),
                            Some(id) if id != *image_id => {
                                return Err(SaharaError::UnexpectedPacket {
                                    state: self.state,
                                    cmd: pkt.command(),
                                })
                            }
                            _ => {}
                        }
                        self.bytes_sent += u64::from(*length);
                        Ok(Step::SendData(image[start..end].to_vec()))
                    }
                    _ => Err(SaharaError::ReadOutOfRange {
                        offset: *offset,
                        length: *length,
                        image_len: image.len(),
                    }),
                }
            }
            (HostState::Transferring, Packet::EndImageTx { status, .. }) => {
                if *status != 0 {
                    return Err(SaharaError::DeviceRejectedImage(*status));
                }
                self.state = HostState::AwaitDoneResp;
                Ok(Step::Send(Packet::Done))
            }
            (HostState::AwaitDoneResp, Packet::DoneResp { image_tx_status }) => {
                if *image_tx_status == 1 {
                    self.state = HostState::Complete;
                    Ok(Step::Finished)
                } else {
                    Err(SaharaError::MoreImagesRequested)
                }
            }
            (state, p) => Err(SaharaError::UnexpectedPacket {
                state,
                cmd: p.command(),
            }),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::super::packet::Hello;
    use super::*;

    fn hello(mode: Mode, compat: u32) -> Packet {
        Packet::Hello(Hello {
            version: 2,
            version_compat: compat,
            max_cmd_pkt_len: 1024,
            mode,
        })
    }

    #[test]
    fn happy_path() {
        let img = vec![7u8; 100];
        let mut h = SaharaHost::new();
        assert!(matches!(
            h.handle(&hello(Mode::ImageTxPending, 1), &img),
            Ok(Step::Send(Packet::HelloResp(_)))
        ));
        let r = h
            .handle(
                &Packet::ReadData {
                    image_id: 1,
                    offset: 10,
                    length: 20,
                },
                &img,
            )
            .unwrap();
        assert_eq!(r, Step::SendData(vec![7; 20]));
        h.handle(
            &Packet::EndImageTx {
                image_id: 1,
                status: 0,
            },
            &img,
        )
        .unwrap();
        assert_eq!(
            h.handle(&Packet::DoneResp { image_tx_status: 1 }, &img),
            Ok(Step::Finished)
        );
        assert_eq!(h.state(), HostState::Complete);
    }

    #[test]
    fn rejects_bad_transitions() {
        let mut h = SaharaHost::new();
        assert!(matches!(
            h.handle(
                &Packet::ReadData {
                    image_id: 1,
                    offset: 0,
                    length: 1
                },
                &[0]
            ),
            Err(SaharaError::UnexpectedPacket { .. })
        ));
        assert_eq!(h.state(), HostState::Failed);
        assert_eq!(
            h.handle(&hello(Mode::ImageTxPending, 1), &[]),
            Err(SaharaError::Terminated)
        );
    }

    #[test]
    fn rejects_unsupported_mode_and_version() {
        assert!(matches!(
            SaharaHost::new().handle(&hello(Mode::Command, 1), &[]),
            Err(SaharaError::UnsupportedMode(Mode::Command))
        ));
        assert!(matches!(
            SaharaHost::new().handle(&hello(Mode::ImageTxPending, 3), &[]),
            Err(SaharaError::IncompatibleVersion { .. })
        ));
    }

    #[test]
    fn rejects_out_of_range_and_oversized_reads() {
        let img = vec![0u8; 16];
        for (off, len, big) in [
            (8u32, 16u32, false),
            (u32::MAX, 16, false),
            (0, 0, true),
            (0, MAX_CHUNK + 1, true),
        ] {
            let mut h = SaharaHost::new();
            h.handle(&hello(Mode::ImageTxPending, 1), &img).unwrap();
            let e = h
                .handle(
                    &Packet::ReadData {
                        image_id: 1,
                        offset: off,
                        length: len,
                    },
                    &img,
                )
                .unwrap_err();
            assert!(if big {
                matches!(e, SaharaError::BadChunkLength(_))
            } else {
                matches!(e, SaharaError::ReadOutOfRange { .. })
            });
        }
    }
}
