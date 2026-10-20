package ai.edlcommander.companion.usb

/**
 * Mirrors `edl_core::usb` (`QUALCOMM_VID`, `QDLOADER_9008_PID`, `looks_like_edl`).
 * A match is a USB-level hint only: it says nothing about loader compatibility or write capability.
 */
object QdLoader {
    const val QUALCOMM_VID = 0x05C6
    const val QDLOADER_9008_PID = 0x9008

    fun isQdLoader9008(vendorId: Int, productId: Int): Boolean =
        vendorId == QUALCOMM_VID && productId == QDLOADER_9008_PID
}
