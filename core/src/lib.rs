//! EDL Commander shared recovery engine.
//!
//! Protocol logic is independent of USB backends and UI: it talks only to the
//! [`transport::Transport`] trait. Platform crates (Ubuntu/libusb, Android/Kotlin
//! bridge) implement that trait.

pub mod device;
pub mod diagnostics;
pub mod protocol;
pub mod security;
pub mod transport;
pub mod usb;

/// Lower-case hex encoding (avoids an extra dependency).
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// SHA-256 of `data` as lower-case hex.
pub fn sha256_hex(data: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    hex(&Sha256::digest(data))
}
