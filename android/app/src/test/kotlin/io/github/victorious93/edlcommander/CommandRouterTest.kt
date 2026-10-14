package io.github.victorious93.edlcommander

import io.github.victorious93.edlcommander.command.CommandRouter
import io.github.victorious93.edlcommander.command.Status
import io.github.victorious93.edlcommander.nativecore.UnlinkedNativeCore
import io.github.victorious93.edlcommander.usb.UsbDeviceSnapshot
import io.github.victorious93.edlcommander.usb.UsbDeviceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouterTest {
    private val fakeUsb = UsbDeviceSource {
        listOf(
            UsbDeviceSnapshot(0x05C6, 0x9008, "/dev/bus/usb/001/002"),
            UsbDeviceSnapshot(0x18D1, 0x4EE7, "/dev/bus/usb/001/003"),
            UsbDeviceSnapshot(0x05C6, 0x901D, "/dev/bus/usb/001/004"),
        )
    }

    private fun router(usb: UsbDeviceSource = fakeUsb) = CommandRouter(usb, UnlinkedNativeCore, "test")

    private fun call(name: String?, args: String? = "{}", dryRun: Boolean = true, schema: Int = 1, r: CommandRouter = router()) =
        r.handleRaw(schema, "req-1", name, args, dryRun)

    @Test
    fun usbListFlagsOnly9008AsCandidate() {
        val res = call("usb.list")
        assertEquals(Status.OK, res.status)
        assertEquals("req-1", res.requestId)
        assertEquals(1, res.payload.getInt("edlCandidates"))
        assertEquals(3, res.payload.getJSONArray("devices").length())
    }

    @Test
    fun usbListEdlOnlyFilters() {
        val res = call("usb.list", """{"edl_only":true}""")
        val devices = res.payload.getJSONArray("devices")
        assertEquals(1, devices.length())
        assertTrue(devices.getJSONObject(0).getBoolean("qdloader9008"))
    }

    @Test
    fun usbEnumerationFailureIsReportedNotThrown() {
        val res = call("usb.list", r = router { throw SecurityException("nope") })
        assertEquals(Status.ERROR, res.status)
        assertEquals("usb_enumeration_failed", res.code)
    }

    @Test
    fun saharaIdentifyDryRunIsPlanOnly() {
        val res = call("sahara.identify", dryRun = true)
        assertEquals(Status.DRY_RUN, res.status)
        assertTrue(res.payload.getJSONArray("plan").length() > 0)
        assertFalse(res.payload.getBoolean("nativeCoreLinked"))
    }

    @Test
    fun saharaIdentifyLiveIsUnsupportedWithoutNativeCore() {
        val res = call("sahara.identify", dryRun = false)
        assertEquals(Status.UNSUPPORTED, res.status)
        assertEquals("native_core_unavailable", res.code)
    }

    @Test
    fun writeClassCommandsAreDeniedEvenWhenNotDryRun() {
        for (name in listOf("flash.partition", "firehose.program", "write.partition", "erase.all", "format.userdata", "unlock.bootloader")) {
            val res = call(name, dryRun = false)
            assertEquals(name, Status.DENIED, res.status)
            assertEquals("writes_not_available", res.code)
        }
    }

    @Test
    fun capabilitiesDoNotAdvertiseWritesOrVerifiedDevices() {
        val res = call("companion.capabilities")
        assertEquals(Status.OK, res.status)
        val features = res.payload.getJSONObject("features")
        assertFalse(features.getBoolean("flashing"))
        assertFalse(features.getBoolean("firehose"))
        assertFalse(features.getBoolean("saharaNative"))
        assertEquals(0, res.payload.getJSONArray("verifiedDevices").length())
        assertEquals(CommandRouter.PROTOCOL_VERSION, res.payload.getInt("protocolVersion"))
    }

    @Test
    fun statusIsHonestAboutScaffold() {
        val res = call("companion.status")
        assertEquals("scaffold", res.payload.getString("phase"))
        assertFalse(res.payload.getBoolean("hardwareValidated"))
    }

    @Test
    fun unknownCommandIsUnsupported() {
        assertEquals(Status.UNSUPPORTED, call("nope.nothing").status)
    }

    @Test
    fun malformedEnvelopesAreInvalid() {
        assertEquals(Status.INVALID, call(null).status)
        assertEquals(Status.INVALID, call("USB.List").status)
        assertEquals(Status.INVALID, call("usb.list", schema = 2).status)
        assertEquals(Status.INVALID, call("usb.list", args = "[1]").status)
        assertEquals(Status.INVALID, call("usb.list", args = "x".repeat(70_000)).status)
    }

    @Test
    fun blankArgsDefaultToEmptyObject() {
        assertEquals(Status.OK, call("usb.list", args = null).status)
    }
}
