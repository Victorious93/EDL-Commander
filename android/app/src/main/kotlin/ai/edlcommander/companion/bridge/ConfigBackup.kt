package ai.edlcommander.companion.bridge

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * `config.export` / `config.import` per the fleet UNIFIED_BACKUP spec
 * (droidcommand-AI docs/specs/UNIFIED_BACKUP.md, frozen, bundle envelope version 1).
 *
 * Carries **preferences only**, from an explicit allowlist. Never signing material, secrets,
 * firehose/programmer paths, or the remote-control switch. Import never enables remote control:
 * those keys are stripped, and after an applied import the switch is forced OFF (spec section 4,
 * step 7). Applying needs on-device confirmation; remote callers are refused before reaching here
 * (`remoteAllowed: false`).
 */
interface PreferenceStore {
    fun snapshot(): Map<String, Any?>
    fun apply(values: Map<String, Any?>)
}

class InMemoryPreferenceStore(initial: Map<String, Any?> = emptyMap()) : PreferenceStore {
    val values = initial.toMutableMap()
    override fun snapshot(): Map<String, Any?> = values.toMap()
    override fun apply(values: Map<String, Any?>) {
        this.values.putAll(values)
    }
}

/** AES-256-GCM seam (spec section 3). Record format `v1.<b64url iv>.<b64url ct+tag>`. */
interface FleetBackupAead {
    fun encrypt(plain: String, aad: String): String
    fun decrypt(record: String, aad: String): String?
}

class JceFleetBackupAead(private val key: SecretKey, private val random: SecureRandom = SecureRandom()) : FleetBackupAead {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    override fun encrypt(plain: String, aad: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return "v1." + enc.encodeToString(iv) + "." + enc.encodeToString(c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    override fun decrypt(record: String, aad: String): String? = try {
        val parts = record.split('.')
        if (parts.size != 3 || parts[0] != "v1") {
            null
        } else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, dec.decode(parts[1])))
            c.updateAAD(aad.toByteArray(Charsets.UTF_8))
            String(c.doFinal(dec.decode(parts[2])), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }
}

enum class ConflictPolicy(val wire: String) {
    SKIP("skip"),
    OVERWRITE("overwrite"),
    MERGE_BY_ID("merge-by-id"),
    ;

    companion object {
        fun parse(s: String?): ConflictPolicy? = if (s.isNullOrEmpty()) SKIP else entries.firstOrNull { it.wire == s }
    }
}

data class BackupOutcome(
    val error: BridgeError?,
    val code: String,
    val message: String,
    val payload: JSONObject = JSONObject(),
)

class ConfigBackup(
    private val store: PreferenceStore,
    private val packageName: String,
    private val sourceNodeRef: String,
    /** Preference keys that may be exported/imported. Anything else is dropped. */
    private val exportableKeys: Set<String>,
    /** Forces the remote-control switch OFF; called after every applied import. */
    private val forceRemoteControlOff: () -> Unit,
    private val aead: FleetBackupAead? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun fail(e: BridgeError, msg: String, payload: JSONObject = JSONObject()) = BackupOutcome(e, e.wire, msg, payload)

    private fun aad(schemaVersion: Int, exporter: String) = "$packageName|$schemaVersion|$exporter"

    private fun allowed(key: String) = key in exportableKeys && !isRemoteControlKey(key) && !containsSecretName(key)

    /** Exports this node's bundle (one entry). `clearPayload=true` is local-dev only (wrapping `none`). */
    fun export(clearPayload: Boolean, requestedPackage: String? = null): BackupOutcome {
        if (!requestedPackage.isNullOrEmpty() && requestedPackage != packageName) {
            return fail(BridgeError.NOT_FOUND, "No entry for package '$requestedPackage' on this node.")
        }
        val prefs = JSONObject()
        store.snapshot().toSortedMap().forEach { (k, v) -> if (allowed(k) && isPlainValue(v)) prefs.put(k, v ?: JSONObject.NULL) }
        val payload = JSONObject().put("preferences", prefs).toString()
        if (containsSecretName(payload)) return fail(BridgeError.INVALID_PARAMETERS, "Refusing to export: payload matches a forbidden secret name.")
        val entry = JSONObject().put("packageName", packageName).put("schemaVersion", PAYLOAD_SCHEMA_VERSION)
        val wrapping: String
        if (clearPayload) {
            entry.put("clearPayload", payload)
            wrapping = WRAPPING_NONE
        } else {
            val a = aead ?: return fail(BridgeError.CAPABILITY_OFF, "Encrypted export needs a key; none is configured on this node.")
            entry.put("ciphertext", a.encrypt(payload, aad(PAYLOAD_SCHEMA_VERSION, sourceNodeRef)))
            wrapping = WRAPPING_RAW
        }
        val bundle = JSONObject()
            .put("version", BUNDLE_VERSION)
            .put("createdAtMs", clock())
            .put("sourceNodeRef", sourceNodeRef)
            .put("wrapping", wrapping)
            .put("entries", JSONArray().put(entry))
        return BackupOutcome(null, "ok", "Exported ${prefs.length()} preference(s).", JSONObject().put("bundle", bundle))
    }

    /**
     * Export meant to leave the app on this device (shown, saved, shared or copied). Fleet rule:
     * until Android Keystore wrapping is implemented, an on-device backup must never use
     * `wrapping: none`, so this refuses unless an encryption key is configured, and it never
     * produces a clear payload.
     */
    fun exportForDevice(): BackupOutcome {
        if (aead == null) {
            return fail(
                BridgeError.CAPABILITY_OFF,
                "On-device backup export is disabled until Android Keystore wrapping is implemented; " +
                    "an unencrypted (wrapping: none) backup file is never written, saved or shared.",
            )
        }
        return export(clearPayload = false)
    }

    /**
     * Import, spec section 4 order. [dryRun] (default) returns the plan and writes nothing.
     * Applying requires [confirmedOnDevice]; otherwise `confirmation_required`.
     */
    fun import(bundleJson: String?, dryRun: Boolean, conflictPolicy: String?, confirmedOnDevice: Boolean): BackupOutcome {
        val policy = ConflictPolicy.parse(conflictPolicy) ?: return fail(BridgeError.INVALID_PARAMETERS, "conflictPolicy must be skip|overwrite|merge-by-id")
        if (bundleJson.isNullOrBlank() || bundleJson.length > MAX_BUNDLE_CHARS) return fail(BridgeError.INVALID_PARAMETERS, "bundle is required (max 256 KiB)")
        val bundle = try {
            JSONObject(bundleJson)
        } catch (_: JSONException) {
            return fail(BridgeError.INVALID_PARAMETERS, "bundle is not JSON")
        }
        if (bundle.optInt("version", -1) != BUNDLE_VERSION) return fail(BridgeError.INVALID_PARAMETERS, "Unknown bundle version.")
        val wrapping = bundle.optString("wrapping")
        if (wrapping !in WRAPPINGS) return fail(BridgeError.INVALID_PARAMETERS, "Unknown wrapping '$wrapping'.")
        val exporter = bundle.optString("sourceNodeRef")
        val entries = bundle.optJSONArray("entries") ?: return fail(BridgeError.INVALID_PARAMETERS, "entries missing")
        val seen = HashSet<String>()
        var mine: JSONObject? = null
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: return fail(BridgeError.INVALID_PARAMETERS, "entry $i is not an object")
            val pkg = e.optString("packageName")
            if (pkg.isEmpty()) return fail(BridgeError.INVALID_PARAMETERS, "entry $i has no packageName")
            if (!seen.add(pkg)) return fail(BridgeError.INVALID_PARAMETERS, "Duplicate packageName '$pkg'.")
            val hasCipher = e.has("ciphertext")
            val hasClear = e.has("clearPayload")
            if (hasCipher == hasClear) return fail(BridgeError.INVALID_PARAMETERS, "Entry '$pkg' must have exactly one of ciphertext/clearPayload.")
            if ((wrapping == WRAPPING_NONE) != hasClear) return fail(BridgeError.INVALID_PARAMETERS, "Entry '$pkg' does not match wrapping '$wrapping'.")
            if (pkg == packageName) mine = e
        }
        val entry = mine ?: return fail(BridgeError.NOT_FOUND, "Bundle has no entry for $packageName.")
        val schemaVersion = entry.optInt("schemaVersion", -1)
        val plain = if (entry.has("clearPayload")) {
            entry.optString("clearPayload")
        } else {
            val a = aead ?: return fail(BridgeError.CAPABILITY_OFF, "Encrypted import needs a key; none is configured on this node.")
            a.decrypt(entry.optString("ciphertext"), "$packageName|$schemaVersion|$exporter")
                ?: return fail(BridgeError.INTERNAL, "Decryption failed (wrong key, package, version or exporter; treated as tamper).")
        }
        if (containsSecretName(plain)) return fail(BridgeError.INVALID_PARAMETERS, "Refusing to import: payload matches a forbidden secret name.")
        val prefs = try {
            JSONObject(plain).optJSONObject("preferences") ?: JSONObject()
        } catch (_: JSONException) {
            return fail(BridgeError.INVALID_PARAMETERS, "Entry payload is not JSON")
        }

        val live = store.snapshot()
        val changes = LinkedHashMap<String, Any?>()
        val plan = JSONArray()
        val stripped = JSONArray()
        for (key in prefs.keys().asSequence().sorted()) {
            val value = prefs.get(key).let { if (it == JSONObject.NULL) null else it }
            if (isRemoteControlKey(key)) {
                stripped.put(JSONObject().put("key", key).put("reason", "remote_control_never_imported"))
                continue
            }
            if (!allowed(key) || !isPlainValue(value)) {
                stripped.put(JSONObject().put("key", key).put("reason", "not_exportable"))
                continue
            }
            val exists = live.containsKey(key)
            val action = when {
                exists && live[key] == value -> "unchanged"
                exists && policy != ConflictPolicy.OVERWRITE -> "kept_live"
                exists -> "overwrite"
                else -> "add"
            }
            if (action == "overwrite" || action == "add") changes[key] = value
            plan.put(JSONObject().put("key", key).put("action", action))
        }
        val payload = JSONObject()
            .put("conflictPolicy", policy.wire)
            .put("plan", plan)
            .put("stripped", stripped)
            .put("changes", changes.size)
            .put("remoteControlAfterImport", false)
        if (dryRun) return BackupOutcome(null, "dry_run", "Import plan only; nothing was written.", payload)
        if (!confirmedOnDevice) {
            return fail(BridgeError.CONFIRMATION_REQUIRED, "Applying an import needs confirmation on this device.", payload)
        }
        store.apply(changes)
        forceRemoteControlOff()
        return BackupOutcome(null, "ok", "Imported ${changes.size} preference(s). Remote control is OFF.", payload.put("applied", true))
    }

    companion object {
        const val BUNDLE_VERSION = 1
        const val PAYLOAD_SCHEMA_VERSION = 1
        const val WRAPPING_NONE = "none"
        const val WRAPPING_RAW = "raw-aes-256-gcm"
        const val WRAPPING_KEYSTORE = "android-keystore"
        val WRAPPINGS = setOf(WRAPPING_NONE, WRAPPING_RAW, WRAPPING_KEYSTORE)
        private const val MAX_BUNDLE_CHARS = 256 * 1024

        private val SECRET_NAMES = listOf(
            "password", "passwd", "apikey", "api_key", "api-key", "apitoken", "api_token", "token", "bearer",
            "secret", "privatekey", "private_key", "signingkey", "signing_key", "keystore", "cert_pem", "begin certificate",
            "begin private key", "codeword", "code_word", "whitelist", "twilio", "authtoken", "auth_token", "numberpool",
            "number_pool", "cookie", "firehose", "programmer", "loader_path", "loaderpath",
        )

        /** Case-insensitive substring scan (spec section 3, "obvious variants"). */
        fun containsSecretName(text: String): Boolean {
            val t = text.lowercase()
            return SECRET_NAMES.any { t.contains(it) }
        }

        fun isRemoteControlKey(key: String): Boolean {
            val k = key.lowercase()
            return k.contains("remote") || k.contains("external_control") || k.contains("externalcontrol")
        }

        private fun isPlainValue(v: Any?) = v == null || v is Boolean || v is Number || v is String
    }
}
