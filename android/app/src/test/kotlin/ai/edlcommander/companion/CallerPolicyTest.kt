package ai.edlcommander.companion

import ai.edlcommander.companion.CallerPolicy
import ai.edlcommander.companion.usb.QdLoader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerPolicyTest {
    @Test
    fun differentSignatureIsAlwaysRejected() {
        assertFalse(CallerPolicy.decide(false, listOf("hub"), emptySet()))
        assertFalse(CallerPolicy.decide(false, listOf("hub"), setOf("hub")))
    }

    @Test
    fun emptyAllowlistFailsClosed() {
        assertFalse(CallerPolicy.decide(true, listOf("anything"), emptySet()))
        assertFalse(CallerPolicy.decide(true, emptyList(), emptySet()))
    }

    @Test
    fun shippedAllowlistDeniesUnknownFleetSignedApps() {
        val shipped = SignatureCallerVerifier.HUB_PACKAGE_ALLOWLIST
        assertFalse(CallerPolicy.decide(true, listOf("com.example.fleet.app"), shipped))
    }

    @Test
    fun allowlistPinsPackageNames() {
        assertTrue(CallerPolicy.decide(true, listOf("a", "hub"), setOf("hub")))
        assertFalse(CallerPolicy.decide(true, listOf("other"), setOf("hub")))
        assertFalse(CallerPolicy.decide(true, emptyList(), setOf("hub")))
    }
}

class QdLoaderTest {
    @Test
    fun matchesRustCoreConstants() {
        assertTrue(QdLoader.isQdLoader9008(0x05C6, 0x9008))
        assertFalse(QdLoader.isQdLoader9008(0x05C6, 0x901D))
        assertFalse(QdLoader.isQdLoader9008(0x18D1, 0x9008))
    }
}
