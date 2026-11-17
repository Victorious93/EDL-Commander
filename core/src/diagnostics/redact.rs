//! Keeps raw USB device paths (e.g. `/dev/bus/usb/001/002`) out of the audit log.
//!
//! Records carry a short, stable SHA-256 tag instead so entries about the same
//! device can still be correlated. This is the same scheme as the Android
//! `AuditRedaction` helper.
//!
//! This is pseudonymisation, not secrecy: the input space of USB paths (and of
//! ADB serials) is small, so a determined reader could brute-force the tag. It
//! only stops the log from recording paths or serials verbatim.

use crate::sha256_hex;

const TAG_HEX_CHARS: usize = 12;

/// Stable tag: `usb#` plus the first 12 lowercase hex characters of SHA-256.
pub fn device_tag(device_name: &str) -> String {
    let hex = sha256_hex(device_name.as_bytes());
    format!("usb#{}", &hex[..TAG_HEX_CHARS])
}

/// Audit detail for a device: `device=usb#<tag>`.
pub fn device_detail(device_name: &str) -> String {
    format!("device={}", device_tag(device_name))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tag_is_stable_and_hides_the_path() {
        let path = "/dev/bus/usb/001/002";
        let tag = device_tag(path);
        assert_eq!(tag, device_tag(path));
        assert!(tag.starts_with("usb#"));
        assert_eq!(tag.len(), 4 + TAG_HEX_CHARS);
        assert!(tag[4..].chars().all(|c| c.is_ascii_hexdigit()));
        assert!(!device_detail(path).contains("/dev/"));
        assert!(!device_detail(path).contains("001"));
    }

    #[test]
    fn different_paths_get_different_tags() {
        assert_ne!(
            device_tag("/dev/bus/usb/001/002"),
            device_tag("/dev/bus/usb/001/003")
        );
    }

    #[test]
    fn empty_string_matches_the_android_helper() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assert_eq!("usb#e3b0c44298fc", device_tag(""));
    }
}
