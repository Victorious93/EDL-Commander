package io.github.victorious93.edlcommander

import io.github.victorious93.edlcommander.companion.CallerPolicy
import io.github.victorious93.edlcommander.usb.QdLoader
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
    fun sameSignatureWithoutAllowlistIsAccepted() {
        assertTrue(CallerPolicy.decide(true, listOf("anything"), emptySet()))
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
