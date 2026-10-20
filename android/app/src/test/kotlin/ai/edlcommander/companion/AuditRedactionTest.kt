package ai.edlcommander.companion

import ai.edlcommander.companion.audit.AuditRedaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditRedactionTest {
    @Test
    fun tagIsStableShortAndHidesThePath() {
        val path = "/dev/bus/usb/001/002"
        val tag = AuditRedaction.deviceTag(path)
        assertEquals(tag, AuditRedaction.deviceTag(path))
        assertTrue(tag.matches(Regex("usb#[0-9a-f]{12}")))
        assertFalse(AuditRedaction.deviceDetail(path).contains("/dev/"))
        assertFalse(AuditRedaction.deviceDetail(path).contains("001"))
    }

    @Test
    fun differentDevicesGetDifferentTags() {
        assertNotEquals(
            AuditRedaction.deviceTag("/dev/bus/usb/001/002"),
            AuditRedaction.deviceTag("/dev/bus/usb/001/003"),
        )
    }

    @Test
    fun knownVector() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb924...
        assertEquals("usb#e3b0c44298fc", AuditRedaction.deviceTag(""))
    }
}
