package ai.edlcommander.companion.bridge

/**
 * Frozen fleet remote-control envelope, v1 (BRIDGE_PROTOCOL_MAJOR=1, MINOR=1).
 * See docs/BRIDGE_PROTOCOL.md. Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 */
object BridgeProtocol {
    const val MAJOR = 1
    const val MINOR = 1

    const val DEFAULT_TTL_MS = 120_000L
    const val MAX_TTL_MS = 300_000L
    const val MAX_SKEW_MS = 120_000L

    /** Duplicate nonces are remembered for at least this long (spec: >= 10 minutes). */
    const val NONCE_RETENTION_MS = 10 * 60_000L

    /** Upper bound on remembered nonces; when full of live entries new requests fail closed. */
    const val NONCE_CACHE_CAPACITY = 10_000

    const val MAX_MACRO_STEPS = 32
    const val MAX_REF_CHARS = 256
}

enum class BridgeOp(val wire: String) {
    CAPABILITIES_GET("capabilities.get"),
    REMOTE_COMMAND("remote.command"),
    MACRO_STEP("macro.step"),
    MACRO_RUN("macro.run"),

    /** Fleet STATUS-TRAY op (droidcommand-AI docs/specs/STATUS_TRAY.md). Read-only. */
    NODE_STATUS_GET("node.status.get"),

    /** Fleet UNIFIED_BACKUP ops; `remoteAllowed: false`, so always refused over the bridge. */
    CONFIG_EXPORT("config.export"),
    CONFIG_IMPORT("config.import"),
    ;

    companion object {
        fun parse(wire: String?): BridgeOp? = entries.firstOrNull { it.wire == wire }
    }
}

/** Frozen error vocabulary. `wire` is what goes on the parcel. */
enum class BridgeError(val wire: String) {
    INVALID_PARAMETERS("invalid_parameters"),
    NOT_PAIRED("not_paired"),
    NOT_FOUND("not_found"),
    INTERNAL("internal"),
    UNAUTHORIZED("unauthorized"),
    REPLAY_DETECTED("replay_detected"),
    EXPIRED("expired"),
    EXTERNAL_CONTROL_DISABLED("external_control_disabled"),
    CONFIRMATION_REQUIRED("confirmation_required"),
    UNSUPPORTED_OP("unsupported_op"),
    CAPABILITY_OFF("capability_off"),
}

enum class ActionKind(val wire: String, val rank: Int) {
    READ("read", 0),
    ACTION("action", 1),
    DESTRUCTIVE("destructive", 2),
}

enum class OnError(val wire: String) {
    ABORT("abort"),
    CONTINUE("continue"),
    ;

    companion object {
        fun parse(wire: String?): OnError? = when (wire) {
            null, "" -> ABORT
            else -> entries.firstOrNull { it.wire == wire }
        }
    }
}

/** One step of a macro (macro.run `steps[]`). */
data class BridgeStep(val actionRef: String, val paramsJson: String)

/** Flat view of the frozen fields carried on CommandEnvelope. */
data class BridgeRequest(
    val major: Int,
    val minor: Int,
    val op: String?,
    val requestId: String?,
    val targetNodeRef: String?,
    val actionRef: String?,
    val paramsJson: String?,
    val nonce: String?,
    val timestampMs: Long,
    val ttlMs: Long,
    val destructive: Boolean,
    val triggerJson: String?,
    val macroId: String?,
    val stepIndex: Int,
    val onError: String?,
    val stepsJson: String?,
    val dryRun: Boolean,
)
