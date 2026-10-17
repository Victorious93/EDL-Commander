package io.github.victorious93.edlcommander.nativecore

import org.json.JSONObject

/**
 * Kotlin-owned byte pipe handed to Rust. The native side calls these two methods by name and
 * JNI signature (`([BI)I`), so they must not be renamed or reshaped.
 *
 * Return value: `>= 0` bytes transferred; [TIMEOUT], [IO_ERROR] or [DISCONNECTED] on failure.
 * Mirrors `BRIDGE_*` in `android/native/edl-jni/src/bridge.rs`.
 */
interface BulkTransport {
    fun write(data: ByteArray, timeoutMs: Int): Int
    fun read(buf: ByteArray, timeoutMs: Int): Int

    companion object {
        const val TIMEOUT = -1
        const val IO_ERROR = -2
        const val DISCONNECTED = -3
    }
}

/**
 * The Rust `edl-core` library behind JNI (Sahara read-only identify, audit hash chain).
 *
 * Every method returns the decoded JSON documented in `edl-jni/src/ops.rs`: `ok`, `code`,
 * `message`, and, except for `audit_invalid`, the full new audit log under `audit`.
 */
interface NativeCore {
    val isLinked: Boolean

    /** `edl-jni` crate version once linked, otherwise null. */
    val version: String?

    /** True if the library was built with the simulated target. */
    val simulatorAvailable: Boolean

    fun authorizeIdentify(audit: String, timestamp: String): JSONObject
    fun auditAppend(audit: String, timestamp: String, action: String, outcome: String, detail: String): JSONObject
    fun recordDeniedWrite(audit: String, timestamp: String, command: String): JSONObject
    fun saharaIdentify(transport: BulkTransport, audit: String, timestamp: String, timeoutMs: Int): JSONObject
    fun saharaIdentifySimulated(audit: String, timestamp: String): JSONObject
}

/** Used when the native library is absent or fails to load; every operation reports it. */
object UnlinkedNativeCore : NativeCore {
    override val isLinked: Boolean = false
    override val version: String? = null
    override val simulatorAvailable: Boolean = false

    private fun unavailable() = JSONObject()
        .put("ok", false)
        .put("code", "native_core_unavailable")
        .put("message", "The native edl-core library is not loaded.")

    override fun authorizeIdentify(audit: String, timestamp: String) = unavailable()
    override fun auditAppend(audit: String, timestamp: String, action: String, outcome: String, detail: String) = unavailable()
    override fun recordDeniedWrite(audit: String, timestamp: String, command: String) = unavailable()
    override fun saharaIdentify(transport: BulkTransport, audit: String, timestamp: String, timeoutMs: Int) = unavailable()
    override fun saharaIdentifySimulated(audit: String, timestamp: String) = unavailable()
}
