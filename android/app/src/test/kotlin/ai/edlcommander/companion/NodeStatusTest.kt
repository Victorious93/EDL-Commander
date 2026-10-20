package ai.edlcommander.companion

import ai.edlcommander.companion.audit.AuditTrail
import ai.edlcommander.companion.audit.InMemoryAuditStore
import ai.edlcommander.companion.bridge.BridgeError
import ai.edlcommander.companion.bridge.BridgeGate
import ai.edlcommander.companion.bridge.BridgeRequest
import ai.edlcommander.companion.bridge.InMemoryRemoteControlSetting
import ai.edlcommander.companion.bridge.NodeStatusCodec
import ai.edlcommander.companion.bridge.NodeStatusProvider
import ai.edlcommander.companion.bridge.NonceCache
import ai.edlcommander.companion.command.CommandRouter
import ai.edlcommander.companion.command.Status
import ai.edlcommander.companion.usb.OpenOutcome
import ai.edlcommander.companion.usb.UsbDeviceSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** node.status.get per droidcommand-AI docs/specs/STATUS_TRAY.md (frozen). */
class NodeStatusTest {
    private val now = 1_800_000_000_000L
    private val source = "android:uid/10123/com.example.hub"
    private var n = 0
    private fun nonce() = "%032x".format(++n)

    private inner class Harness(enabled: Boolean = true, linked: Boolean = true, provider: Boolean = true) {
        var usbCalls = 0
        val core = FakeNativeCore()
        val gate = FakeGate()
        val sessions = FakeSessions { OpenOutcome.Failed("open_failed", "must not be reached") }
        val router = CommandRouter(UsbDeviceSource { usbCalls++; emptyList() }, core, "test", AuditTrail(core, InMemoryAuditStore()), gate, sessions)
        val setting = InMemoryRemoteControlSetting(enabled)
        val status = if (provider) NodeStatusProvider("ai.edlcommander.companion", "ai.edlcommander.companion", { linked }) { now } else null
        val bridge = BridgeGate(router, setting, NonceCache(), { now }, status)

        fun assertNoHardware() {
            assertEquals(0, usbCalls)
            assertTrue(sessions.opened.isEmpty())
            assertTrue(gate.requests.isEmpty())
            assertTrue(core.calls.isEmpty())
        }
    }

    private fun req(op: String = "node.status.get", actionRef: String? = null, params: String = "{}", destructive: Boolean = false) =
        BridgeRequest(1, 1, op, "req-s", "node", actionRef, params, nonce(), now, 120_000, destructive, "{}", null, -1, null, "[]", false)

    @Test
    fun returnsSnapshotWithNodeHealthOnly() {
        val h = Harness()
        val r = h.bridge.handle(req(), source)
        assertNull(r.error)
        assertEquals(Status.OK, r.result.status)
        val p = r.result.payload
        assertEquals(setOf("nodeId", "packageName", "connectionClass", "updatedAtMs", "domains"), p.keys().asSequence().toSet())
        assertEquals("ai.edlcommander.companion", p.getString("nodeId"))
        assertEquals("BOUND_COMPANION", p.getString("connectionClass"))
        assertEquals(now, p.getLong("updatedAtMs"))
        val domains = p.getJSONArray("domains")
        assertEquals(1, domains.length())
        assertEquals("node_health", domains.getJSONObject(0).getString("name"))
        assertEquals("ok", domains.getJSONObject(0).getString("state"))
        assertTrue(domains.getJSONObject(0).getString("detail").length <= NodeStatusCodec.MAX_DETAIL)
        h.assertNoHardware()
    }

    @Test
    fun degradedWhenNativeCoreMissing() {
        val r = Harness(linked = false).bridge.handle(req(), source)
        assertNull(r.error)
        assertEquals("degraded", r.result.payload.getJSONArray("domains").getJSONObject(0).getString("state"))
    }

    @Test
    fun externalControlOffFailsClosed() {
        val h = Harness(enabled = false)
        assertEquals(BridgeError.EXTERNAL_CONTROL_DISABLED, h.bridge.handle(req(), source).error)
        assertEquals(BridgeError.EXTERNAL_CONTROL_DISABLED, h.bridge.handle(req(op = "remote.command", actionRef = "node.status.get"), source).error)
        h.assertNoHardware()
    }

    @Test
    fun alsoReachableAsRemoteCommandAction() {
        val h = Harness()
        val r = h.bridge.handle(req(op = "remote.command", actionRef = "node.status.get"), source)
        assertNull(r.error)
        assertEquals("BOUND_COMPANION", r.result.payload.getString("connectionClass"))
        h.assertNoHardware()
    }

    @Test
    fun matchingOrEmptyNodeIdAnswersMismatchIsNotFound() {
        val h = Harness()
        assertNull(h.bridge.handle(req(params = """{"nodeId":"ai.edlcommander.companion"}"""), source).error)
        assertNull(h.bridge.handle(req(params = """{"args":{"nodeId":"ai.edlcommander.companion"}}"""), source).error)
        assertEquals(BridgeError.NOT_FOUND, h.bridge.handle(req(params = """{"nodeId":"mesh-1"}"""), source).error)
    }

    @Test
    fun advisoryDestructiveIgnoredForThisRead() {
        assertNull(Harness().bridge.handle(req(destructive = true), source).error)
    }

    @Test
    fun capabilityOffWithoutProvider() {
        assertEquals(BridgeError.CAPABILITY_OFF, Harness(provider = false).bridge.handle(req(), source).error)
    }

    @Test
    fun replayAndExpiryStillApply() {
        val h = Harness()
        val r = req()
        assertNull(h.bridge.handle(r, source).error)
        assertEquals(BridgeError.REPLAY_DETECTED, h.bridge.handle(r, source).error)
        assertEquals(BridgeError.EXPIRED, h.bridge.handle(req().copy(timestampMs = now - 200_000), source).error)
    }

    @Test
    fun capabilitiesAdvertiseFrozenDescriptor() {
        val p = Harness().bridge.handle(req(op = "capabilities.get"), source).result.payload
        val ops = p.getJSONArray("ops")
        val entry = (0 until ops.length()).map { ops.getJSONObject(it) }.single { it.getString("name") == "node.status.get" }
        assertEquals("read", entry.getString("kind"))
        assertTrue(entry.getBoolean("remoteAllowed"))
        assertFalse(entry.getBoolean("requiresConfirm"))
    }

    @Test
    fun codecRefusesForbiddenOrOversizedDetail() {
        assertNull(NodeStatusCodec.encode("n", null, "BOUND_COMPANION", 1, listOf(NodeStatusCodec.domain("node_health", "ok", "api_token=abc"))))
        assertNull(NodeStatusCodec.encode("n", null, "BOUND_COMPANION", 1, listOf(NodeStatusCodec.domain("node_health", "ok", "x".repeat(201)))))
        assertNull(NodeStatusCodec.encode("n", null, "BOUND_COMPANION", 1, listOf(NodeStatusCodec.domain("node_health", "great"))))
        assertNull(NodeStatusCodec.encode("n", null, "NOPE", 1, emptyList()))
        assertNull(NodeStatusCodec.encode(" ", null, "BOUND_COMPANION", 1, emptyList()))
        val ok = NodeStatusCodec.encode("n", null, "BOUND_COMPANION", 1, listOf(NodeStatusCodec.domain("node_health", "down")))!!
        assertEquals(JSONObject.NULL, ok.get("packageName"))
        assertFalse(ok.getJSONArray("domains").getJSONObject(0).has("detail"))
        assertEquals("other", NodeStatusCodec.domain("weird", "ok").getString("name"))
    }
}
