package ai.edlcommander.companion

import ai.edlcommander.companion.audit.AuditRedaction
import ai.edlcommander.companion.audit.AuditTrail
import ai.edlcommander.companion.audit.AuditStore
import ai.edlcommander.companion.audit.InMemoryAuditStore
import ai.edlcommander.companion.command.CommandResult
import ai.edlcommander.companion.command.CommandRouter
import ai.edlcommander.companion.command.Status
import ai.edlcommander.companion.nativecore.BulkTransport
import ai.edlcommander.companion.usb.OpenOutcome
import ai.edlcommander.companion.usb.PermissionState
import ai.edlcommander.companion.usb.RequestOutcome
import ai.edlcommander.companion.usb.UsbDeviceSnapshot
import ai.edlcommander.companion.usb.UsbDeviceSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Permission, authorization, audit and open/claim handling in the router, with fakes only. */
class IdentifyFlowTest {
    private val edl = UsbDeviceSnapshot(0x05C6, 0x9008, "/dev/bus/usb/001/002")
    private val other = UsbDeviceSnapshot(0x18D1, 0x4EE7, "/dev/bus/usb/001/003")

    private class Harness(devices: List<UsbDeviceSnapshot>, store: AuditStore = InMemoryAuditStore()) {
        val core = FakeNativeCore()
        val store = store
        val gate = FakeGate()
        val sessions = FakeSessions { OpenOutcome.Failed("open_failed", "unset") }
        val router = CommandRouter(UsbDeviceSource { devices }, core, "test", AuditTrail(core, store), gate, sessions)

        fun call(name: String, args: String = "{}", dryRun: Boolean = false): CommandResult =
            router.handleRaw(1, "r", name, args, dryRun)

        val auditLines: List<String> get() = store.read().lines().filter { it.isNotEmpty() }
    }

    @Test
    fun permissionRequestDryRunDoesNotPrompt() {
        val h = Harness(listOf(edl))
        val res = h.call("usb.request_permission", dryRun = true)
        assertEquals(Status.DRY_RUN, res.status)
        assertTrue(h.gate.requests.isEmpty())
        assertTrue(h.core.calls.isEmpty())
    }

    @Test
    fun permissionRequestPromptsTheUserAndIsAudited() {
        val h = Harness(listOf(edl, other))
        val res = h.call("usb.request_permission")
        assertEquals(Status.OK, res.status)
        assertEquals("permission_prompt_shown", res.code)
        assertEquals("requested", res.payload.getString("permission"))
        assertEquals(listOf(edl.deviceName), h.gate.requests)
        assertEquals(listOf("usb.request_permission:requested"), h.auditLines)
        assertEquals(listOf(AuditRedaction.deviceDetail(edl.deviceName)), h.core.details)
        assertFalse("raw USB path must not reach the audit log", h.core.details.any { "/dev/bus/usb" in it })
        assertTrue("nothing is opened by a permission request", h.sessions.opened.isEmpty())
    }

    @Test
    fun alreadyGrantedIsReportedWithoutPrompt() {
        val h = Harness(listOf(edl))
        h.gate.outcome = RequestOutcome.ALREADY_GRANTED
        assertEquals("permission_already_granted", h.call("usb.request_permission").code)
    }

    @Test
    fun permissionIsNeverRequestedForNon9008Devices() {
        val h = Harness(listOf(edl, other))
        val res = h.call("usb.request_permission", """{"device_name":"${other.deviceName}"}""")
        assertEquals(Status.DENIED, res.status)
        assertEquals("not_an_edl_candidate", res.code)
        assertTrue(h.gate.requests.isEmpty())
        assertEquals("device_not_found", h.call("usb.request_permission", """{"device_name":"/dev/nope"}""").code)
    }

    @Test
    fun noDeviceOrAmbiguousDeviceNeverPrompts() {
        val none = Harness(listOf(other))
        assertEquals("no_edl_device", none.call("usb.request_permission").code)
        val two = Harness(listOf(edl, UsbDeviceSnapshot(0x05C6, 0x9008, "/dev/bus/usb/001/009")))
        val res = two.call("usb.request_permission")
        assertEquals(Status.INVALID, res.status)
        assertEquals("ambiguous_device", res.code)
        assertTrue(two.gate.requests.isEmpty() && none.gate.requests.isEmpty())
    }

    @Test
    fun permissionRequestFailsClosedWhenAuditCannotBeWritten() {
        val h = Harness(listOf(edl), store = failingStore())
        val res = h.call("usb.request_permission")
        assertEquals("audit_unavailable", res.code)
        assertTrue("no prompt without an audit record", h.gate.requests.isEmpty())
    }

    @Test
    fun usbListReportsPermissionStateForEdlDevicesOnly() {
        val h = Harness(listOf(edl, other))
        h.gate.state = PermissionState.DENIED
        val devices = h.call("usb.list", dryRun = true).payload.getJSONArray("devices")
        val byName = (0 until devices.length()).associate { devices.getJSONObject(it).getString("deviceName") to devices.getJSONObject(it) }
        assertEquals("denied", byName.getValue(edl.deviceName).getString("permission"))
        assertEquals("not_applicable", byName.getValue(other.deviceName).getString("permission"))
    }

    @Test
    fun identifyWithoutPermissionIsDeniedAndNeverOpensTheDevice() {
        for (state in listOf(PermissionState.NOT_REQUESTED, PermissionState.REQUESTED, PermissionState.DENIED)) {
            val h = Harness(listOf(edl))
            h.gate.state = state
            val res = h.call("sahara.identify")
            assertEquals(state.name, Status.DENIED, res.status)
            assertEquals("usb_permission_required", res.code)
            assertTrue(h.sessions.opened.isEmpty())
            assertFalse(h.core.calls.any { it.startsWith("identify:") })
            assertEquals(listOf("sahara.identify:denied"), h.auditLines)
        }
    }

    @Test
    fun identifyWithPermissionRunsOverTheOpenedSessionAndIsAudited() {
        val h = Harness(listOf(edl, other))
        h.gate.state = PermissionState.GRANTED
        val pipe = object : BulkTransport {
            override fun write(data: ByteArray, timeoutMs: Int) = data.size
            override fun read(buf: ByteArray, timeoutMs: Int) = 0
        }
        h.sessions.next = { h.sessions.session(pipe) }
        var seen: BulkTransport? = null
        h.core.identify = { t ->
            seen = t
            JSONObject().put("ok", true).put("report", JSONObject().put("serial", "abcd0001"))
        }
        val res = h.call("sahara.identify", """{"timeout_ms":1234}""")
        assertEquals(res.message, Status.OK, res.status)
        assertFalse(res.simulated)
        assertFalse(res.dryRun)
        assertEquals("abcd0001", res.payload.getJSONObject("report").getString("serial"))
        assertEquals(edl.deviceName, res.payload.getJSONObject("device").getString("deviceName"))
        assertTrue(res.payload.getBoolean("auditPersisted"))
        assertTrue("the native core got the session's transport", seen === pipe)
        assertEquals(1, h.sessions.closed)
        assertEquals(listOf("authorizeIdentify", "identify:1234"), h.core.calls)
        assertEquals(listOf("identify_device:allowed", "sahara.identify:run"), h.auditLines)
    }

    @Test
    fun securityAuthorizeDenialStopsBeforeOpening() {
        val h = Harness(listOf(edl))
        h.gate.state = PermissionState.GRANTED
        h.core.authorize = { JSONObject().put("ok", false).put("code", "denied").put("message", "no matching device profile") }
        val res = h.call("sahara.identify")
        assertEquals(Status.DENIED, res.status)
        assertTrue(h.sessions.opened.isEmpty())
        assertEquals(listOf("identify_device:denied"), h.auditLines)
    }

    @Test
    fun openFailureIsReportedAuditedAndNothingIsRun() {
        for (code in listOf("claim_failed", "no_bulk_interface", "open_failed", "usb_permission_required")) {
            val h = Harness(listOf(edl))
            h.gate.state = PermissionState.GRANTED
            h.sessions.next = { OpenOutcome.Failed(code, "x") }
            val res = h.call("sahara.identify")
            assertEquals(Status.ERROR, res.status)
            assertEquals(code, res.code)
            assertFalse(h.core.calls.any { it.startsWith("identify:") })
            assertEquals("sahara.identify:error", h.auditLines.last())
        }
        val h = Harness(listOf(edl))
        h.gate.state = PermissionState.GRANTED
        h.sessions.next = { throw SecurityException("revoked") }
        assertEquals("open_failed", h.call("sahara.identify").code)
    }

    @Test
    fun identifyFailureFromNativeIsAnErrorAndSessionIsStillClosed() {
        val h = Harness(listOf(edl))
        h.gate.state = PermissionState.GRANTED
        h.sessions.next = { h.sessions.session(object : BulkTransport {
            override fun write(data: ByteArray, timeoutMs: Int) = -1
            override fun read(buf: ByteArray, timeoutMs: Int) = -1
        }) }
        h.core.identify = { JSONObject().put("ok", false).put("code", "device_timeout").put("message", "transfer timed out") }
        val res = h.call("sahara.identify")
        assertEquals(Status.ERROR, res.status)
        assertEquals("device_timeout", res.code)
        assertEquals(1, h.sessions.closed)
    }

    @Test
    fun identifyFailsClosedWhenAuditCannotBeWritten() {
        val h = Harness(listOf(edl), store = failingStore())
        h.gate.state = PermissionState.GRANTED
        val res = h.call("sahara.identify")
        assertEquals("audit_unavailable", res.code)
        assertTrue(h.sessions.opened.isEmpty())
    }

    @Test
    fun simulatedIdentifyNeverTouchesUsbAndIsFlagged() {
        val h = Harness(emptyList())
        val res = h.call("sahara.identify", """{"simulate":true}""")
        assertEquals(Status.OK, res.status)
        assertTrue(res.simulated)
        assertTrue(h.sessions.opened.isEmpty() && h.gate.requests.isEmpty())
        assertEquals(listOf("identify_device:allowed", "sahara.identify:sim"), h.auditLines)
    }

    @Test
    fun simulatedIdentifyIsUnsupportedWhenSimulatorNotBuilt() {
        val core = FakeNativeCore(simulatorAvailable = false)
        val router = CommandRouter(UsbDeviceSource { emptyList() }, core, "t", AuditTrail(core, InMemoryAuditStore()))
        val res = router.handleRaw(1, "r", "sahara.identify", """{"simulate":true}""", false)
        assertEquals("simulator_not_built", res.code)
    }

    @Test
    fun dryRunNeverTouchesAnything() {
        val h = Harness(listOf(edl))
        h.gate.state = PermissionState.GRANTED
        val res = h.call("sahara.identify", dryRun = true)
        assertEquals(Status.DRY_RUN, res.status)
        assertTrue(h.core.calls.isEmpty() && h.sessions.opened.isEmpty() && h.gate.requests.isEmpty())
        assertTrue(h.auditLines.isEmpty())
        val simPlan = h.call("sahara.identify", """{"simulate":true}""", dryRun = true)
        assertEquals(Status.DRY_RUN, simPlan.status)
        assertTrue(h.core.calls.isEmpty())
    }

    @Test
    fun writeClassCommandsAreDeniedAndNonDryRunAttemptsAreAudited() {
        val h = Harness(listOf(edl))
        h.gate.state = PermissionState.GRANTED
        for (name in listOf("flash.partition", "firehose.program", "write.partition", "erase.all", "format.userdata", "unlock.bootloader")) {
            val res = h.call(name, dryRun = false)
            assertEquals(Status.DENIED, res.status)
            assertEquals("writes_not_available", res.code)
        }
        assertEquals(6, h.core.calls.count { it.startsWith("deniedWrite:") })
        assertTrue(h.sessions.opened.isEmpty())
        h.core.calls.clear()
        assertEquals(Status.DENIED, h.call("flash.partition", dryRun = true).status)
        assertTrue("dry-run denials are not audited", h.core.calls.isEmpty())
    }

    @Test
    fun capabilitiesReflectWhatIsActuallyAvailable() {
        val h = Harness(listOf(edl))
        val res = h.call("companion.capabilities", dryRun = true)
        val features = res.payload.getJSONObject("features")
        assertTrue(features.getBoolean("saharaIdentifyReadOnly"))
        assertTrue(features.getBoolean("usbPermissionRequest"))
        assertFalse(features.getBoolean("firehose") || features.getBoolean("programmerUpload") || features.getBoolean("flashing"))
        assertEquals(0, res.payload.getJSONArray("verifiedDevices").length())
        val names = (0 until res.payload.getJSONArray("commands").length()).map { res.payload.getJSONArray("commands").getJSONObject(it).getString("name") }
        assertTrue("usb.request_permission" in names)
    }

    private fun failingStore() = object : AuditStore {
        override fun read() = ""
        override fun write(text: String) = throw IOException("disk full")
        override fun quarantine() {}
    }
}
