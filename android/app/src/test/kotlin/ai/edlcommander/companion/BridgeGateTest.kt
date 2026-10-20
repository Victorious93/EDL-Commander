package ai.edlcommander.companion

import ai.edlcommander.companion.audit.AuditTrail
import ai.edlcommander.companion.audit.InMemoryAuditStore
import ai.edlcommander.companion.bridge.ActionClassifier
import ai.edlcommander.companion.bridge.ActionKind
import ai.edlcommander.companion.bridge.BridgeError
import ai.edlcommander.companion.bridge.BridgeGate
import ai.edlcommander.companion.bridge.BridgeProtocol
import ai.edlcommander.companion.bridge.BridgeRequest
import ai.edlcommander.companion.bridge.InMemoryRemoteControlSetting
import ai.edlcommander.companion.bridge.NonceCache
import ai.edlcommander.companion.bridge.RemoteDispatcher
import ai.edlcommander.companion.command.CommandResult
import ai.edlcommander.companion.command.CommandRouter
import ai.edlcommander.companion.command.Status
import ai.edlcommander.companion.nativecore.UnlinkedNativeCore
import ai.edlcommander.companion.usb.OpenOutcome
import ai.edlcommander.companion.usb.PermissionState
import ai.edlcommander.companion.usb.RequestOutcome
import ai.edlcommander.companion.usb.UsbDeviceSnapshot
import ai.edlcommander.companion.usb.UsbDeviceSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeGateTest {
    private val now = 1_800_000_000_000L
    private val source = "android:uid/10123/com.example.hub"

    private inner class Harness(enabled: Boolean = true, nonceCapacity: Int = 1000) {
        val core = FakeNativeCore()
        val gate = FakeGate(outcome = RequestOutcome.PROMPTED)
        val sessions = FakeSessions { OpenOutcome.Failed("open_failed", "should not be reached") }
        val router = CommandRouter(
            UsbDeviceSource { listOf(UsbDeviceSnapshot(0x05C6, 0x9008, "/dev/bus/usb/001/002")) },
            core, "test", AuditTrail(core, InMemoryAuditStore()), gate, sessions,
        )
        val setting = InMemoryRemoteControlSetting(enabled)
        var clock = now
        val bridge = BridgeGate(router, setting, NonceCache(capacity = nonceCapacity), clock = { clock })
        val remote = RemoteDispatcher(router, setting, gate = bridge)

        /** No device I/O, no permission prompt, no authorize/identify happened. */
        fun assertUntouched() {
            assertTrue("sessions opened: ${sessions.opened}", sessions.opened.isEmpty())
            assertTrue("permission prompts: ${gate.requests}", gate.requests.isEmpty())
            assertTrue("native calls: ${core.calls}", core.calls.none { it.startsWith("identify") || it == "authorizeIdentify" })
        }
    }

    private var nonceCounter = 0
    private fun nonce() = "%032x".format(++nonceCounter)

    private fun req(
        op: String? = "remote.command",
        actionRef: String? = "usb.list",
        params: String = "{}",
        nonce: String? = nonce(),
        timestampMs: Long = now,
        ttlMs: Long = 120_000,
        destructive: Boolean = false,
        macroId: String? = null,
        stepIndex: Int = -1,
        onError: String? = null,
        steps: String = "[]",
        dryRun: Boolean = false,
        major: Int = 1,
    ) = BridgeRequest(
        major, 1, op, "req-1", "node-companion", actionRef, params, nonce, timestampMs, ttlMs, destructive,
        "{\"source\":\"test\"}", macroId, stepIndex, onError, steps, dryRun,
    )

    private fun steps(vararg actions: String) =
        JSONArray().apply { actions.forEach { put(JSONObject().put("actionRef", it).put("params", JSONObject())) } }.toString()

    @Test
    fun versionsAreFrozenAt1_1() {
        assertEquals(1, BridgeProtocol.MAJOR)
        assertEquals(1, BridgeProtocol.MINOR)
    }

    @Test
    fun readOnlyRemoteCommandSucceeds() {
        val r = Harness().bridge.handle(req(), source)
        assertNull(r.error)
        assertEquals(Status.OK, r.result.status)
        assertEquals(source, r.sourceNodeRef)
        assertEquals("remote.command", r.op)
        assertEquals(1, r.result.payload.getJSONArray("devices").length())
    }

    @Test
    fun wrongMajorIsInvalid() {
        assertEquals(BridgeError.INVALID_PARAMETERS, Harness().bridge.handle(req(major = 2), source).error)
    }

    @Test
    fun expiredWhenPastTtl() {
        val h = Harness()
        h.clock = now + 60_001
        val r = h.bridge.handle(req(ttlMs = 60_000), source)
        assertEquals(BridgeError.EXPIRED, r.error)
        assertEquals("expired", r.result.code)
    }

    @Test
    fun withinTtlIsAccepted() {
        val h = Harness()
        h.clock = now + 59_999
        assertNull(h.bridge.handle(req(ttlMs = 60_000), source).error)
    }

    @Test
    fun skewInPastBeyond120sIsExpiredEvenWithLongTtl() {
        val h = Harness()
        h.clock = now + 120_001
        assertEquals(BridgeError.EXPIRED, h.bridge.handle(req(ttlMs = 300_000), source).error)
    }

    @Test
    fun skewInFutureBeyond120sIsExpired() {
        val h = Harness()
        assertEquals(BridgeError.EXPIRED, h.bridge.handle(req(timestampMs = now + 120_001), source).error)
        assertNull(h.bridge.handle(req(timestampMs = now + 120_000), source).error)
    }

    @Test
    fun duplicateNonceFromSameSourceIsReplay() {
        val h = Harness()
        val n = nonce()
        assertNull(h.bridge.handle(req(nonce = n), source).error)
        assertEquals(BridgeError.REPLAY_DETECTED, h.bridge.handle(req(nonce = n.uppercase()), source).error)
    }

    @Test
    fun sameNonceFromDifferentSourceIsIndependent() {
        val h = Harness()
        val n = nonce()
        assertNull(h.bridge.handle(req(nonce = n), source).error)
        assertNull(h.bridge.handle(req(nonce = n), "android:uid/10999/other").error)
    }

    @Test
    fun nonceRememberedForAtLeastTenMinutes() {
        val cache = NonceCache()
        assertEquals(NonceCache.Outcome.FRESH, cache.checkAndRecord("a", "n", 0))
        assertEquals(NonceCache.Outcome.DUPLICATE, cache.checkAndRecord("a", "n", 10 * 60_000L - 1))
        assertEquals(NonceCache.Outcome.FRESH, cache.checkAndRecord("a", "n", 10 * 60_000L + 1))
    }

    @Test
    fun fullNonceCacheFailsClosed() {
        val h = Harness(nonceCapacity = 1)
        assertNull(h.bridge.handle(req(), source).error)
        assertEquals(BridgeError.INTERNAL, h.bridge.handle(req(), source).error)
    }

    @Test
    fun badNonceIsInvalid() {
        val h = Harness()
        assertEquals(BridgeError.INVALID_PARAMETERS, h.bridge.handle(req(nonce = "abc"), source).error)
        assertEquals(BridgeError.INVALID_PARAMETERS, h.bridge.handle(req(nonce = null), source).error)
        assertEquals(BridgeError.INVALID_PARAMETERS, h.bridge.handle(req(nonce = "z".repeat(32)), source).error)
    }

    @Test
    fun ttlCapAndDefault() {
        val h = Harness()
        assertEquals(BridgeError.INVALID_PARAMETERS, h.bridge.handle(req(ttlMs = 300_001), source).error)
        assertEquals(BridgeError.INVALID_PARAMETERS, h.bridge.handle(req(ttlMs = -1), source).error)
        assertNull(h.bridge.handle(req(ttlMs = 300_000), source).error)
        // 0 means "use default 120000": 119 s later still valid, 121 s later expired.
        h.clock = now + 119_000
        assertNull(h.bridge.handle(req(ttlMs = 0), source).error)
        h.clock = now + 121_000
        assertEquals(BridgeError.EXPIRED, h.bridge.handle(req(ttlMs = 0), source).error)
    }

    @Test
    fun unknownOpIsUnsupported() {
        val r = Harness().bridge.handle(req(op = "remote.flash"), source)
        assertEquals(BridgeError.UNSUPPORTED_OP, r.error)
        assertEquals(Status.UNSUPPORTED, r.result.status)
    }

    @Test
    fun unknownActionIsNotFound() {
        assertEquals(BridgeError.NOT_FOUND, Harness().bridge.handle(req(actionRef = "camera.snap"), source).error)
    }

    @Test
    fun missingActionRefIsInvalid() {
        assertEquals(BridgeError.INVALID_PARAMETERS, Harness().bridge.handle(req(actionRef = null), source).error)
    }

    @Test
    fun badParamsIsInvalid() {
        assertEquals(BridgeError.INVALID_PARAMETERS, Harness().bridge.handle(req(params = "[1]"), source).error)
    }

    @Test
    fun badTriggerIsInvalid() {
        val r = Harness().bridge.handle(req().copy(triggerJson = "not json"), source)
        assertEquals(BridgeError.INVALID_PARAMETERS, r.error)
    }

    @Test
    fun unresolvedCallerIsNotPaired() {
        assertEquals(BridgeError.NOT_PAIRED, Harness().bridge.handle(req(), null).error)
    }

    @Test
    fun destructiveRemoteDeniedForEveryWriteFamily() {
        val actions = listOf("flash.partition", "firehose.program", "edl.write", "erase.userdata", "bootloader.unlock", "unlock.oem", "partition.erase", "fastboot.flash")
        for (action in actions) {
            val h = Harness()
            val r = h.bridge.handle(req(actionRef = action), source)
            assertEquals(action, BridgeError.UNAUTHORIZED, r.error)
            assertEquals(Status.DENIED, r.result.status)
            h.assertUntouched()
            assertTrue(h.core.calls.none { it.startsWith("deniedWrite") || it.startsWith("append") })
        }
    }

    @Test
    fun destructiveDryRunAlsoDeniedAndUntouched() {
        val h = Harness()
        assertEquals(BridgeError.UNAUTHORIZED, h.bridge.handle(req(actionRef = "flash.all", dryRun = true), source).error)
        h.assertUntouched()
    }

    @Test
    fun advisoryDestructiveFlagRaisesClassification() {
        assertEquals(BridgeError.UNAUTHORIZED, Harness().bridge.handle(req(actionRef = "usb.list", destructive = true), source).error)
    }

    @Test
    fun advisoryFlagCannotLowerClassification() {
        val policy = ActionClassifier.classify("flash.boot")
        assertEquals(ActionKind.DESTRUCTIVE, ActionClassifier.effectiveKind(policy, advisoryDestructive = false))
        assertFalse(policy.remoteAllowed)
    }

    @Test
    fun liveIdentifyWithoutUsbPermissionNeedsConfirmation() {
        val h = Harness()
        h.gate.state = PermissionState.NOT_REQUESTED
        val r = h.bridge.handle(req(actionRef = "sahara.identify"), source)
        assertEquals(BridgeError.CONFIRMATION_REQUIRED, r.error)
        assertTrue(h.sessions.opened.isEmpty())
        assertEquals(ActionKind.READ, ActionClassifier.classify("sahara.identify").kind)
    }

    @Test
    fun usbPermissionRequestReturnsConfirmationRequired() {
        val h = Harness()
        val r = h.bridge.handle(req(actionRef = "usb.request_permission"), source)
        assertEquals(BridgeError.CONFIRMATION_REQUIRED, r.error)
        assertEquals("permission_prompt_shown", r.result.code)
        assertEquals(1, h.gate.requests.size)
    }

    @Test
    fun capabilitiesOutput() {
        val r = Harness(enabled = false).bridge.handle(req(op = "capabilities.get", actionRef = null), source)
        assertNull(r.error)
        val p = r.result.payload
        assertEquals(1, p.getInt("bridgeProtocolMajor"))
        assertEquals(1, p.getInt("bridgeProtocolMinor"))
        assertFalse(p.getBoolean("externalControlEnabled"))
        val ops = p.getJSONArray("ops")
        val byName = (0 until ops.length()).map { ops.getJSONObject(it) }.associateBy { it.getString("name") }
        for (o in byName.values) {
            assertEquals(setOf("name", "kind", "remoteAllowed", "requiresConfirm"), o.keys().asSequence().toSet())
            assertTrue(o.getString("kind") in setOf("read", "action", "destructive"))
            if (o.getString("kind") == "destructive") {
                assertFalse(o.getBoolean("remoteAllowed"))
                assertTrue(o.getBoolean("requiresConfirm"))
            }
        }
        assertEquals("read", byName.getValue("sahara.identify").getString("kind"))
        assertEquals("read", byName.getValue("usb.list").getString("kind"))
        assertEquals("action", byName.getValue("usb.request_permission").getString("kind"))
        assertTrue(byName.getValue("usb.request_permission").getBoolean("requiresConfirm"))
        assertEquals("destructive", byName.getValue("flash.*").getString("kind"))
        assertEquals("destructive", byName.getValue("edl.write").getString("kind"))
        assertEquals(
            listOf("capabilities.get", "remote.command", "macro.step", "macro.run", "node.status.get"),
            p.getJSONArray("envelopeOps").let { a -> (0 until a.length()).map { a.getString(it) } },
        )
    }

    @Test
    fun macroStepCarriesFields() {
        val r = Harness().bridge.handle(req(op = "macro.step", macroId = "m1", stepIndex = 2, onError = "continue"), source)
        assertNull(r.error)
        assertEquals("m1", r.result.payload.getString("macroId"))
        assertEquals(2, r.result.payload.getInt("stepIndex"))
        assertEquals("continue", r.result.payload.getString("onError"))
    }

    @Test
    fun macroStepValidation() {
        val h = Harness()
        assertEquals(BridgeError.INVALID_PARAMETERS, h.bridge.handle(req(op = "macro.step", macroId = null, stepIndex = 0), source).error)
        assertEquals(BridgeError.INVALID_PARAMETERS, h.bridge.handle(req(op = "macro.step", macroId = "m", stepIndex = -1), source).error)
        assertEquals(BridgeError.INVALID_PARAMETERS, h.bridge.handle(req(op = "macro.step", macroId = "m", stepIndex = 0, onError = "retry"), source).error)
        assertEquals(BridgeError.UNAUTHORIZED, h.bridge.handle(req(op = "macro.step", macroId = "m", stepIndex = 0, actionRef = "erase.all"), source).error)
    }

    @Test
    fun macroRunDryRunWithDestructiveStepTouchesNothing() {
        val h = Harness()
        val r = h.bridge.handle(req(op = "macro.run", actionRef = null, macroId = "m", steps = steps("usb.list", "sahara.identify", "flash.boot"), dryRun = true), source)
        assertNull(r.error)
        assertEquals(Status.DRY_RUN, r.result.status)
        h.assertUntouched()
        val plan = r.result.payload.getJSONArray("steps")
        assertEquals(3, plan.length())
        assertEquals("allowed", plan.getJSONObject(0).getString("verdict"))
        assertEquals("destructive", plan.getJSONObject(2).getString("kind"))
        assertEquals("unauthorized", plan.getJSONObject(2).getString("verdict"))
        assertFalse(r.result.payload.has("results"))
    }

    @Test
    fun macroRunLiveWithDestructiveStepRunsNothing() {
        val h = Harness()
        val r = h.bridge.handle(req(op = "macro.run", actionRef = null, macroId = "m", steps = steps("usb.request_permission", "flash.boot")), source)
        assertEquals(BridgeError.UNAUTHORIZED, r.error)
        h.assertUntouched()
    }

    @Test
    fun macroRunLiveReadOnlyRuns() {
        val r = Harness().bridge.handle(req(op = "macro.run", actionRef = null, macroId = "m", steps = steps("companion.status", "usb.list")), source)
        assertNull(r.error)
        assertEquals(2, r.result.payload.getJSONArray("results").length())
    }

    @Test
    fun macroRunAbortsOnFirstErrorByDefault() {
        val r = Harness().bridge.handle(req(op = "macro.run", actionRef = null, macroId = "m", steps = steps("sahara.identify", "usb.list")), source)
        assertEquals(BridgeError.CONFIRMATION_REQUIRED, r.error)
        assertEquals(1, r.result.payload.getJSONArray("results").length())
    }

    @Test
    fun macroRunValidation() {
        val h = Harness()
        assertEquals(BridgeError.INVALID_PARAMETERS, h.bridge.handle(req(op = "macro.run", macroId = "m", steps = "[]"), source).error)
        assertEquals(BridgeError.INVALID_PARAMETERS, h.bridge.handle(req(op = "macro.run", macroId = null, steps = steps("usb.list")), source).error)
        val tooMany = steps(*Array(BridgeProtocol.MAX_MACRO_STEPS + 1) { "usb.list" })
        assertEquals(BridgeError.INVALID_PARAMETERS, h.bridge.handle(req(op = "macro.run", macroId = "m", steps = tooMany), source).error)
        assertEquals(BridgeError.NOT_FOUND, h.bridge.handle(req(op = "macro.run", macroId = "m", steps = steps("nope.x")), source).error)
    }

    // ---- Local remote-control switch (OFF by default) ----

    @Test
    fun remoteControlIsOffByDefault() {
        assertFalse(InMemoryRemoteControlSetting().isEnabled())
    }

    @Test
    fun whileOffEveryOpExceptCapabilitiesIsDisabled() {
        val h = Harness(enabled = false)
        val cases = listOf(
            req(op = "remote.command"),
            req(op = "remote.command", actionRef = "flash.boot"),
            req(op = "macro.step", macroId = "m", stepIndex = 0),
            req(op = "macro.run", actionRef = null, macroId = "m", steps = steps("usb.list"), dryRun = true),
        )
        for (c in cases) {
            val r = h.bridge.handle(c, source)
            assertEquals(c.op, BridgeError.EXTERNAL_CONTROL_DISABLED, r.error)
            assertEquals(Status.DENIED, r.result.status)
        }
        h.assertUntouched()
        assertNull(h.bridge.handle(req(op = "capabilities.get", actionRef = null), source).error)
    }

    @Test
    fun enablingLocallyAllowsReadOps() {
        val h = Harness(enabled = false)
        assertEquals(BridgeError.EXTERNAL_CONTROL_DISABLED, h.bridge.handle(req(), source).error)
        h.setting.enabled = true
        assertNull(h.bridge.handle(req(), source).error)
    }

    @Test
    fun legacyPathAlsoGatedBySwitch() {
        val h = Harness(enabled = false)
        val denied = h.remote.legacy(1, "r", "usb.list", "{}", false)
        assertEquals("external_control_disabled", denied.code)
        assertEquals(Status.DENIED, denied.status)
        assertEquals(Status.OK, h.remote.legacy(1, "r", "companion.capabilities", "{}", true).status)
        h.setting.enabled = true
        assertEquals(Status.OK, h.remote.legacy(1, "r", "usb.list", "{}", false).status)
        // Existing router gate still applies when enabled.
        assertEquals("writes_not_available", h.remote.legacy(1, "r", "flash.boot", "{}", true).code)
    }

    @Test
    fun routerResultMapping() {
        fun res(s: Status, code: String) = CommandResult("r", s, code, "")
        assertEquals(BridgeError.CAPABILITY_OFF, BridgeGate.mapRouter(res(Status.UNSUPPORTED, "native_core_unavailable")))
        assertEquals(BridgeError.NOT_FOUND, BridgeGate.mapRouter(res(Status.UNSUPPORTED, "unknown_command")))
        assertEquals(BridgeError.UNAUTHORIZED, BridgeGate.mapRouter(res(Status.DENIED, "writes_not_available")))
        assertEquals(BridgeError.INTERNAL, BridgeGate.mapRouter(res(Status.ERROR, "open_failed")))
        assertEquals(BridgeError.INVALID_PARAMETERS, BridgeGate.mapRouter(res(Status.INVALID, "invalid_args")))
        assertNull(BridgeGate.mapRouter(res(Status.DRY_RUN, "dry_run")))
    }

    @Test
    fun capabilityOffWhenNativeCoreMissing() {
        val router = CommandRouter(UsbDeviceSource { emptyList() }, UnlinkedNativeCore, "t", AuditTrail(UnlinkedNativeCore, InMemoryAuditStore()))
        val r = BridgeGate(router, InMemoryRemoteControlSetting(true), clock = { now }).handle(req(actionRef = "sahara.identify"), source)
        assertEquals(BridgeError.CAPABILITY_OFF, r.error)
    }

    @Test
    fun nodeRefDerivesFromUidAndPackages() {
        assertEquals("android:uid/10001/a.b+c.d", CallerPolicy.nodeRef(10001, listOf("c.d", "a.b"), isSelf = false))
        assertNull(CallerPolicy.nodeRef(10001, emptyList(), isSelf = false))
        assertEquals("android:self", CallerPolicy.nodeRef(5, emptyList(), isSelf = true))
    }
}
