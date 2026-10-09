//! USB identification and enumeration interface.

use serde::{Deserialize, Serialize};

pub const QUALCOMM_VID: u16 = 0x05C6;
/// Product ID commonly used by Qualcomm's "HS-USB QDLoader 9008" (EDL) interface.
pub const QDLOADER_9008_PID: u16 = 0x9008;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UsbDeviceInfo {
    pub vendor_id: u16,
    pub product_id: u16,
    pub bus: u8,
    pub address: u8,
    pub manufacturer: Option<String>,
    pub product: Option<String>,
    pub serial: Option<String>,
}

impl UsbDeviceInfo {
    /// USB-level hint only. A match means "USB detected", nothing more: it says
    /// nothing about loader compatibility or write capability.
    pub fn looks_like_edl(&self) -> bool {
        self.vendor_id == QUALCOMM_VID && self.product_id == QDLOADER_9008_PID
    }
}

/// Platform backends (libusb, Android USB Host bridge, simulator) implement this.
pub trait UsbEnumerator {
    fn enumerate(&self) -> Result<Vec<UsbDeviceInfo>, crate::transport::TransportError>;
}

pub fn edl_candidates(devs: &[UsbDeviceInfo]) -> Vec<&UsbDeviceInfo> {
    devs.iter().filter(|d| d.looks_like_edl()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    fn d(v: u16, p: u16) -> UsbDeviceInfo {
        UsbDeviceInfo {
            vendor_id: v,
            product_id: p,
            bus: 1,
            address: 2,
            manufacturer: None,
            product: None,
            serial: None,
        }
    }
    #[test]
    fn only_9008_is_candidate() {
        let devs = [d(0x05c6, 0x9008), d(0x05c6, 0x901d), d(0x18d1, 0x4ee7)];
        assert_eq!(edl_candidates(&devs).len(), 1);
    }
}
