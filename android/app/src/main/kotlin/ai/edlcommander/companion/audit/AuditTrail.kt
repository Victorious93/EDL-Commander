package ai.edlcommander.companion.audit

import ai.edlcommander.companion.nativecore.BulkTransport
import ai.edlcommander.companion.nativecore.NativeCore
import org.json.JSONObject
import java.time.Instant

/**
 * Audit logging for every non-dry-run command. The hash chain lives in Rust (`edl-core`); this
 * class only moves the log between the [AuditStore] and the native calls, serialises access, and
 * fails closed: when the log cannot be recorded, the command that needed it does not run.
 *
 * Each `native` call takes the whole current log and returns the whole new log, so a record is
 * only as durable as the store write that follows it. Records never contain device identifiers.
 */
class AuditTrail(
    private val core: NativeCore,
    private val store: AuditStore,
    private val timestamp: () -> String = { Instant.now().toString() },
) {
    /** Audit records can only be produced when the native core is linked. */
    val available: Boolean get() = core.isLinked

    private val lock = Any()

    fun append(action: String, outcome: String, detail: String): Result =
        transact { audit -> core.auditAppend(audit, timestamp(), action, outcome, detail) }

    /** Run `security::authorize` for read-only identification and record the decision. */
    fun authorizeIdentify(): Result = transact { audit -> core.authorizeIdentify(audit, timestamp()) }

    fun recordDeniedWrite(command: String): Result =
        transact { audit -> core.recordDeniedWrite(audit, timestamp(), command) }

    fun identify(transport: BulkTransport, timeoutMs: Int): Result =
        transact { audit -> core.saharaIdentify(transport, audit, timestamp(), timeoutMs) }

    fun identifySimulated(): Result = transact { audit -> core.saharaIdentifySimulated(audit, timestamp()) }

    private fun transact(op: (audit: String) -> JSONObject): Result = synchronized(lock) {
        if (!core.isLinked) return Result.unavailable("native_core_unavailable", "Native core is not loaded; cannot audit.")
        var result = op(store.read())
        if (result.optString("code") == "audit_invalid") {
            // Tampered or corrupt log: keep it for inspection and start a new chain.
            store.quarantine()
            result = op("")
        }
        if (!result.has("audit")) return Result.of(result, persisted = false)
        val persisted = try {
            store.write(result.getString("audit"))
            true
        } catch (_: java.io.IOException) {
            false
        }
        Result.of(result, persisted)
    }

    /** The native result without the (large) audit text, plus whether the log reached storage. */
    class Result private constructor(val json: JSONObject, val persisted: Boolean) {
        val ok: Boolean get() = json.optBoolean("ok", false)
        val code: String get() = json.optString("code", if (ok) "ok" else "unknown")
        val message: String get() = json.optString("message", "")

        companion object {
            fun of(raw: JSONObject, persisted: Boolean): Result {
                val copy = JSONObject(raw.toString())
                copy.remove("audit")
                return Result(copy, persisted)
            }

            fun unavailable(code: String, message: String) =
                Result(JSONObject().put("ok", false).put("code", code).put("message", message), persisted = false)
        }
    }
}
