//! Sahara host side: packet codec, explicit state machine, and a session runner.

pub mod identify;
pub mod packet;
pub mod session;
pub mod state;

pub use identify::{run_identify, DeviceIdentity, IdentifyOptions, IdentifyReport};
pub use packet::{Mode, Packet, PacketError};
pub use session::{run_programmer_upload, SessionOptions, SessionReport};
pub use state::{HostState, SaharaHost, Step};

use crate::transport::TransportError;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum SaharaError {
    #[error("transport: {0}")]
    Transport(#[from] TransportError),
    #[error("malformed packet: {0}")]
    Packet(#[from] PacketError),
    #[error("unexpected packet 0x{cmd:x} in state {state:?}")]
    UnexpectedPacket { state: HostState, cmd: u32 },
    #[error("device protocol version {device} (min host {compat}) incompatible with host version {host}")]
    IncompatibleVersion { device: u32, compat: u32, host: u32 },
    #[error("device mode {0:?} is not supported by this operation")]
    UnsupportedMode(Mode),
    #[error(
        "device requested range offset={offset} length={length} outside image of {image_len} bytes"
    )]
    ReadOutOfRange {
        offset: u32,
        length: u32,
        image_len: usize,
    },
    #[error("device requested zero or oversized chunk ({0} bytes)")]
    BadChunkLength(u32),
    #[error("device rejected image (END_IMAGE_TX status {0})")]
    DeviceRejectedImage(u32),
    #[error("device indicates further images are required; multi-image upload is not supported")]
    MoreImagesRequested,
    #[error("exceeded packet budget of {0}")]
    PacketBudgetExceeded(u64),
    #[error("unexpected packet 0x{cmd:x} while waiting for {stage}")]
    UnexpectedInStage { stage: &'static str, cmd: u32 },
    #[error("bad response to client command 0x{client_command:x}: {reason}")]
    BadExecResponse {
        client_command: u32,
        reason: &'static str,
    },
    #[error("session already finished or failed")]
    Terminated,
}
