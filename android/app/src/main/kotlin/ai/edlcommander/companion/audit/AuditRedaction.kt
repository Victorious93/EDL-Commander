package ai.edlcommander.companion.audit

import java.security.MessageDigest

/**
 * Keeps raw USB device paths (e.g. `/dev/bus/usb/001/002`) out of the audit log. Records carry a
 * short, stable SHA-256 tag instead so entries about the same device can still be correlated.
 *
 * This is pseudonymisation, not secrecy: the input space of USB paths is small, so a determined
 * reader could brute-force the tag. It only stops the log from recording paths verbatim.
 */
object AuditRedaction {
    private const val TAG_HEX_CHARS = 12

    fun deviceTag(deviceName: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(deviceName.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "usb#" + hex.take(TAG_HEX_CHARS)
    }

    /** Audit detail for a device: `device=usb#<tag>`. */
    fun deviceDetail(deviceName: String): String = "device=" + deviceTag(deviceName)
}
