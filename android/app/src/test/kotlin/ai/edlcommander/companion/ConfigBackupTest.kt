package ai.edlcommander.companion

import ai.edlcommander.companion.audit.AuditTrail
import ai.edlcommander.companion.audit.InMemoryAuditStore
import ai.edlcommander.companion.bridge.ActionClassifier
import ai.edlcommander.companion.bridge.ActionKind
import ai.edlcommander.companion.bridge.BridgeError
import ai.edlcommander.companion.bridge.BridgeGate
import ai.edlcommander.companion.bridge.BridgeRequest
import ai.edlcommander.companion.bridge.ConfigBackup
import ai.edlcommander.companion.bridge.InMemoryPreferenceStore
import ai.edlcommander.companion.bridge.InMemoryRemoteControlSetting
import ai.edlcommander.companion.bridge.JceFleetBackupAead
import ai.edlcommander.companion.command.CommandRouter
import ai.edlcommander.companion.usb.UsbDeviceSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/** config.export / config.import per droidcommand-AI docs/specs/UNIFIED_BACKUP.md (frozen). */
class ConfigBackupTest {
    private val pkg = "ai.edlcommander.companion"
    private val keys = setOf("ui.theme", "ui.verbose", "remote_control_enabled", "usb.default_timeout_ms")
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private inner class H(initial: Map<String, Any?> = mapOf("ui.theme" to "dark"), remoteOn: Boolean = true) {
        val store = InMemoryPreferenceStore(initial)
        val remote = InMemoryRemoteControlSetting(remoteOn)
        val backup = ConfigBackup(store, pkg, pkg, keys, { remote.enabled = false }, JceFleetBackupAead(key)) { 1_000L }
    }

    private fun clearBundle(prefs: JSONObject, packageName: String = pkg, extraEntry: JSONObject? = null, version: Int = 1): String {
        val entries = JSONArray().put(
            JSONObject().put("packageName", packageName).put("schemaVersion", 1).put("clearPayload", JSONObject().put("preferences", prefs).toString()),
        )
        extraEntry?.let { entries.put(it) }
        return JSONObject().put("version", version).put("createdAtMs", 1).put("sourceNodeRef", "controller").put("wrapping", "none").put("entries", entries).toString()
    }

    @Test
    fun capabilitiesDescriptorsPerSpec() {
        val export = ActionClassifier.classify("config.export")
        assertEquals(ActionKind.ACTION, export.kind)
        assertFalse(export.remoteAllowed)
        assertFalse(export.requiresConfirm)
        val import = ActionClassifier.classify("config.import")
        assertEquals(ActionKind.ACTION, import.kind)
        assertFalse(import.remoteAllowed)
        assertTrue(import.requiresConfirm)
        // Destructive flash ops stay remote-denied.
        assertFalse(ActionClassifier.classify("flash.boot").remoteAllowed)
        assertEquals(ActionKind.DESTRUCTIVE, ActionClassifier.classify("flash.boot").kind)
    }

    @Test
    fun remoteCallsAreRefusedUnauthorizedAndTouchNothing() {
        val core = FakeNativeCore()
        val router = CommandRouter(UsbDeviceSource { emptyList() }, core, "t", AuditTrail(core, InMemoryAuditStore()))
        val gate = BridgeGate(router, InMemoryRemoteControlSetting(true), clock = { 5_000_000L })
        var n = 0
        fun req(op: String, action: String?, dryRun: Boolean) = BridgeRequest(
            1, 1, op, "r", null, action, """{"bundle":"{}"}""", "%032x".format(++n), 5_000_000L, 0, false, "{}", null, -1, null, "[]", dryRun,
        )
        for (r in listOf(
            req("config.export", null, false), req("config.import", null, false), req("config.import", null, true),
            req("remote.command", "config.export", false), req("remote.command", "config.import", true),
        )) {
            assertEquals(BridgeError.UNAUTHORIZED, gate.handle(r, "android:uid/1/x").error)
        }
        assertTrue(core.calls.isEmpty())
    }

    @Test
    fun exportCarriesAllowlistedPreferencesOnlyAndNeverRemoteControl() {
        val h = H(mapOf("ui.theme" to "dark", "remote_control_enabled" to true, "not.listed" to "x", "ui.verbose" to false))
        val out = h.backup.export(clearPayload = true)
        assertNull(out.error)
        val bundle = out.payload.getJSONObject("bundle")
        assertEquals(1, bundle.getInt("version"))
        assertEquals("none", bundle.getString("wrapping"))
        val entry = bundle.getJSONArray("entries").getJSONObject(0)
        assertEquals(pkg, entry.getString("packageName"))
        val prefs = JSONObject(entry.getString("clearPayload")).getJSONObject("preferences")
        assertEquals(setOf("ui.theme", "ui.verbose"), prefs.keys().asSequence().toSet())
    }

    @Test
    fun encryptedExportRoundTripsAndBindsAad() {
        val h = H(mapOf("ui.theme" to "light"))
        val bundle = h.backup.export(clearPayload = false).payload.getJSONObject("bundle")
        assertEquals("raw-aes-256-gcm", bundle.getString("wrapping"))
        val entry = bundle.getJSONArray("entries").getJSONObject(0)
        assertTrue(entry.getString("ciphertext").startsWith("v1."))
        assertFalse(entry.has("clearPayload"))
        val target = H(emptyMap())
        val res = target.backup.import(bundle.toString(), dryRun = true, conflictPolicy = null, confirmedOnDevice = false)
        assertNull(res.error)
        assertEquals("add", res.payload.getJSONArray("plan").getJSONObject(0).getString("action"))
        // Tampered exporter (AAD mismatch) fails closed as internal.
        bundle.put("sourceNodeRef", "someone-else")
        assertEquals(BridgeError.INTERNAL, target.backup.import(bundle.toString(), true, null, false).error)
    }

    @Test
    fun exportOfOtherPackageIsNotFound() {
        assertEquals(BridgeError.NOT_FOUND, H().backup.export(true, "com.other").error)
    }

    @Test
    fun dryRunIsDefaultPlanAndWritesNothing() {
        val h = H()
        val res = h.backup.import(clearBundle(JSONObject().put("ui.theme", "light").put("ui.verbose", true)), dryRun = true, conflictPolicy = null, confirmedOnDevice = false)
        assertNull(res.error)
        assertEquals("dry_run", res.code)
        assertEquals("dark", h.store.values["ui.theme"])
        assertFalse(h.store.values.containsKey("ui.verbose"))
        assertTrue(h.remote.enabled)
    }

    @Test
    fun applyWithoutOnDeviceConfirmationIsRefused() {
        val h = H()
        val res = h.backup.import(clearBundle(JSONObject().put("ui.verbose", true)), dryRun = false, conflictPolicy = null, confirmedOnDevice = false)
        assertEquals(BridgeError.CONFIRMATION_REQUIRED, res.error)
        assertFalse(h.store.values.containsKey("ui.verbose"))
    }

    @Test
    fun importNeverEnablesRemoteControlAndForcesItOff() {
        val h = H(remoteOn = false)
        val prefs = JSONObject().put("remote_control_enabled", true).put("externalControlEnabled", true).put("remote.command", true).put("ui.verbose", true)
        val res = h.backup.import(clearBundle(prefs), dryRun = false, conflictPolicy = "overwrite", confirmedOnDevice = true)
        assertNull(res.error)
        assertFalse(h.remote.enabled)
        assertFalse(h.store.values.containsKey("remote_control_enabled"))
        assertFalse(h.store.values.containsKey("externalControlEnabled"))
        assertFalse(h.store.values.containsKey("remote.command"))
        assertEquals(true, h.store.values["ui.verbose"])
        val stripped = res.payload.getJSONArray("stripped")
        val reasons = (0 until stripped.length()).map { stripped.getJSONObject(it) }.filter { it.getString("reason") == "remote_control_never_imported" }
        assertEquals(3, reasons.size)
        // A switch that was ON is turned OFF by an applied import (spec section 4 step 7).
        val on = H(remoteOn = true)
        on.backup.import(clearBundle(JSONObject().put("ui.verbose", true)), false, null, true)
        assertFalse(on.remote.enabled)
    }

    @Test
    fun conflictPolicies() {
        val incoming = JSONObject().put("ui.theme", "light").put("ui.verbose", true)
        val skip = H()
        skip.backup.import(clearBundle(incoming), false, "skip", true)
        assertEquals("dark", skip.store.values["ui.theme"])
        assertEquals(true, skip.store.values["ui.verbose"])
        val merge = H()
        merge.backup.import(clearBundle(incoming), false, "merge-by-id", true)
        assertEquals("dark", merge.store.values["ui.theme"])
        assertEquals(true, merge.store.values["ui.verbose"])
        val over = H()
        over.backup.import(clearBundle(incoming), false, "overwrite", true)
        assertEquals("light", over.store.values["ui.theme"])
        assertEquals(BridgeError.INVALID_PARAMETERS, H().backup.import(clearBundle(incoming), true, "replace-all", false).error)
    }

    @Test
    fun secretsAndLoaderPathsAreRefusedNotStripped() {
        for (bad in listOf("api_key", "apiKey", "signingKey", "keystore_password", "authToken", "firehose_loader_path", "numberPool")) {
            val res = H().backup.import(clearBundle(JSONObject().put(bad, "x")), true, null, false)
            assertEquals(bad, BridgeError.INVALID_PARAMETERS, res.error)
        }
        assertTrue(ConfigBackup.containsSecretName("""{"programmer":"/sdcard/prog_firehose.elf"}"""))
    }

    @Test
    fun exportRefusesSecretLookingValues() {
        val h = H(mapOf("ui.theme" to "token=abc"))
        assertEquals(BridgeError.INVALID_PARAMETERS, h.backup.export(true).error)
    }

    @Test
    fun envelopeValidation() {
        val b = H().backup
        assertEquals(BridgeError.INVALID_PARAMETERS, b.import(clearBundle(JSONObject(), version = 2), true, null, false).error)
        assertEquals(BridgeError.INVALID_PARAMETERS, b.import("not json", true, null, false).error)
        assertEquals(BridgeError.INVALID_PARAMETERS, b.import(null, true, null, false).error)
        val dup = JSONObject().put("packageName", pkg).put("schemaVersion", 1).put("clearPayload", "{}")
        assertEquals(BridgeError.INVALID_PARAMETERS, b.import(clearBundle(JSONObject(), extraEntry = dup), true, null, false).error)
        val both = JSONObject().put("packageName", "x.y").put("schemaVersion", 1).put("clearPayload", "{}").put("ciphertext", "v1.a.b")
        assertEquals(BridgeError.INVALID_PARAMETERS, b.import(clearBundle(JSONObject(), extraEntry = both), true, null, false).error)
        val wrongWrap = JSONObject(clearBundle(JSONObject())).put("wrapping", "raw-aes-256-gcm").toString()
        assertEquals(BridgeError.INVALID_PARAMETERS, b.import(wrongWrap, true, null, false).error)
        val unknownWrap = JSONObject(clearBundle(JSONObject())).put("wrapping", "rot13").toString()
        assertEquals(BridgeError.INVALID_PARAMETERS, b.import(unknownWrap, true, null, false).error)
        assertEquals(BridgeError.NOT_FOUND, b.import(clearBundle(JSONObject(), packageName = "com.other"), true, null, false).error)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val h = H()
        val res = h.backup.import(clearBundle(JSONObject().put("totally.unknown", 1).put("ui.verbose", true)), false, null, true)
        assertNull(res.error)
        assertFalse(h.store.values.containsKey("totally.unknown"))
    }
}
