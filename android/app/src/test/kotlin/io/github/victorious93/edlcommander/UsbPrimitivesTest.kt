package io.github.victorious93.edlcommander

import io.github.victorious93.edlcommander.nativecore.BulkTransport
import io.github.victorious93.edlcommander.usb.BulkConnection
import io.github.victorious93.edlcommander.usb.EndpointInfo
import io.github.victorious93.edlcommander.usb.EndpointSelector
import io.github.victorious93.edlcommander.usb.InterfaceInfo
import io.github.victorious93.edlcommander.usb.PermissionState
import io.github.victorious93.edlcommander.usb.UsbBulkPipe
import io.github.victorious93.edlcommander.usb.UsbPermissionTracker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointSelectorTest {
    private fun ep(addr: Int, type: Int = 2, mps: Int = 512) = EndpointInfo(addr, type, mps)

    @Test
    fun picksBulkInAndOutOfTheVendorInterface() {
        val sel = EndpointSelector.select(
            listOf(
                InterfaceInfo(0, 0, 0xFF, listOf(ep(0x81), ep(0x01))),
            ),
        )!!
        assertEquals(0, sel.interfaceId)
        assertEquals(0x81, sel.inAddress)
        assertEquals(0x01, sel.outAddress)
    }

    @Test
    fun ignoresInterruptAndIncompleteInterfaces() {
        val sel = EndpointSelector.select(
            listOf(
                InterfaceInfo(0, 0, 0xFF, listOf(ep(0x82, type = 3, mps = 8), ep(0x02, type = 3, mps = 8))),
                InterfaceInfo(1, 0, 0xFF, listOf(ep(0x83))),
                InterfaceInfo(2, 0, 0xFF, listOf(ep(0x84), ep(0x04))),
            ),
        )!!
        assertEquals(2, sel.interfaceId)
    }

    @Test
    fun prefersVendorClassThenLowestInterface() {
        val bulk = listOf(ep(0x81), ep(0x01))
        val sel = EndpointSelector.select(
            listOf(InterfaceInfo(0, 0, 0x08, bulk), InterfaceInfo(3, 0, 0xFF, bulk), InterfaceInfo(2, 0, 0xFF, bulk)),
        )!!
        assertEquals(2, sel.interfaceId)
    }

    @Test
    fun zeroPacketSizeAndMissingDirectionsAreRejected() {
        assertNull(EndpointSelector.select(listOf(InterfaceInfo(0, 0, 0xFF, listOf(ep(0x81, mps = 0), ep(0x01))))))
        assertNull(EndpointSelector.select(listOf(InterfaceInfo(0, 0, 0xFF, listOf(ep(0x81), ep(0x82))))))
        assertNull(EndpointSelector.select(emptyList()))
    }
}

class UsbBulkPipeTest {
    private class FakeConn(var attached: Boolean = true) : BulkConnection {
        val transfers = mutableListOf<Triple<Int, Int, Int>>()
        val written = java.io.ByteArrayOutputStream()
        var script: (endpoint: Int, buf: ByteArray, off: Int, len: Int, timeout: Int) -> Int = { _, _, _, len, _ -> len }
        var onTransfer: () -> Unit = {}
        override fun bulkTransfer(endpointAddress: Int, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            transfers += Triple(endpointAddress, length, timeoutMs)
            onTransfer()
            val n = script(endpointAddress, buffer, offset, length, timeoutMs)
            if (n > 0 && endpointAddress and 0x80 == 0) written.write(buffer, offset, n)
            return n
        }

        override fun isAttached() = attached
    }

    private var now = 0L
    private fun pipe(c: FakeConn, maxChunk: Int = 16 * 1024) = UsbBulkPipe(c, 0x81, 0x01, { now }, maxChunk)

    @Test
    fun largeWritesAreChunkedAndCompleteInOrder() {
        val c = FakeConn()
        val data = ByteArray(10) { it.toByte() }
        assertEquals(10, pipe(c, maxChunk = 4).write(data, 100))
        assertEquals(listOf(4, 4, 2), c.transfers.map { it.second })
        assertEquals(setOf(0x01), c.transfers.map { it.first }.toSet())
        assertArrayEquals(data, c.written.toByteArray())
    }

    @Test
    fun readReturnsBytesTransferredAndUsesInEndpoint() {
        val c = FakeConn()
        c.script = { _, buf, off, _, _ -> buf[off] = 42; 7 }
        val buf = ByteArray(64)
        assertEquals(7, pipe(c).read(buf, 50))
        assertEquals(42, buf[0].toInt())
        assertEquals(0x81, c.transfers.single().first)
    }

    @Test
    fun zeroTimeoutIsNeverPassedThroughBecauseAndroidTreatsItAsInfinite() {
        val c = FakeConn()
        pipe(c).read(ByteArray(8), 0)
        pipe(c).write(ByteArray(1), -5)
        assertEquals(listOf(1, 1), c.transfers.map { it.third })
    }

    @Test
    fun failureAfterFullTimeoutIsATimeout() {
        val c = FakeConn()
        c.script = { _, _, _, _, _ -> -1 }
        c.onTransfer = { now += 100_000_000L }
        assertEquals(BulkTransport.TIMEOUT, pipe(c).read(ByteArray(8), 100))
    }

    @Test
    fun quickFailureIsAnIoError() {
        val c = FakeConn()
        c.script = { _, _, _, _, _ -> -1 }
        assertEquals(BulkTransport.IO_ERROR, pipe(c).read(ByteArray(8), 5000))
        assertEquals(BulkTransport.IO_ERROR, pipe(c).write(ByteArray(8), 5000))
    }

    @Test
    fun failureWithDeviceGoneIsADisconnect() {
        val c = FakeConn(attached = false)
        c.script = { _, _, _, _, _ -> -1 }
        assertEquals(BulkTransport.DISCONNECTED, pipe(c).read(ByteArray(8), 5000))
        assertEquals(BulkTransport.DISCONNECTED, pipe(c).write(ByteArray(8), 5000))
    }

    @Test
    fun exceptionsFromTheConnectionNeverEscape() {
        val c = FakeConn()
        c.script = { _, _, _, _, _ -> throw IllegalStateException("closed") }
        assertEquals(BulkTransport.IO_ERROR, pipe(c).read(ByteArray(8), 5000))
    }

    @Test
    fun zeroByteWriteProgressIsAnErrorNotAnInfiniteLoop() {
        val c = FakeConn()
        c.script = { _, _, _, _, _ -> 0 }
        assertEquals(BulkTransport.IO_ERROR, pipe(c).write(ByteArray(8), 100))
        assertEquals(1, c.transfers.size)
    }
}

class UsbPermissionTrackerTest {
    private val dev = "/dev/bus/usb/001/002"

    @Test
    fun freshDeviceIsNotRequested() {
        assertEquals(PermissionState.NOT_REQUESTED, UsbPermissionTracker().stateFor(dev, systemGranted = false))
    }

    @Test
    fun requestedThenGrantedOrDenied() {
        val t = UsbPermissionTracker()
        t.markRequested(dev)
        assertEquals(PermissionState.REQUESTED, t.stateFor(dev, false))
        t.onResult(dev, granted = false)
        assertEquals(PermissionState.DENIED, t.stateFor(dev, false))
        t.markRequested(dev)
        t.onResult(dev, granted = true)
        assertEquals(PermissionState.GRANTED, t.stateFor(dev, true))
    }

    @Test
    fun systemGrantIsAuthoritativeEvenWithoutOurRequest() {
        assertEquals(PermissionState.GRANTED, UsbPermissionTracker().stateFor(dev, systemGranted = true))
    }

    @Test
    fun aGrantTheSystemNoLongerReportsIsForgotten() {
        val t = UsbPermissionTracker()
        t.onResult(dev, granted = true)
        assertEquals(PermissionState.GRANTED, t.stateFor(dev, true))
        assertEquals(PermissionState.NOT_REQUESTED, t.stateFor(dev, false))
    }

    @Test
    fun detachedDevicesAreForgotten() {
        val t = UsbPermissionTracker()
        t.onResult(dev, granted = false)
        t.retainOnly(emptySet())
        assertEquals(PermissionState.NOT_REQUESTED, t.stateFor(dev, false))
    }
}
