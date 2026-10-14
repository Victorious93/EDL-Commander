package io.github.victorious93.edlcommander.nativecore

import org.json.JSONObject

/** [NativeCore] backed by `libedl_jni.so`. Obtain it through [load]. */
class JniNativeCore private constructor(
    override val version: String,
    override val simulatorAvailable: Boolean,
) : NativeCore {
    override val isLinked: Boolean = true

    override fun authorizeIdentify(audit: String, timestamp: String) =
        parse(NativeLib.nativeAuthorizeIdentify(audit, timestamp))

    override fun auditAppend(audit: String, timestamp: String, action: String, outcome: String, detail: String) =
        parse(NativeLib.nativeAuditAppend(audit, timestamp, action, outcome, detail))

    override fun recordDeniedWrite(audit: String, timestamp: String, command: String) =
        parse(NativeLib.nativeRecordDeniedWrite(audit, timestamp, command))

    override fun saharaIdentify(transport: BulkTransport, audit: String, timestamp: String, timeoutMs: Int) =
        parse(NativeLib.nativeSaharaIdentify(transport, audit, timestamp, timeoutMs))

    override fun saharaIdentifySimulated(audit: String, timestamp: String) =
        parse(NativeLib.nativeSaharaIdentifySimulated(audit, timestamp))

    private fun parse(json: String): JSONObject = JSONObject(json)

    companion object {
        const val LIBRARY = "edl_jni"

        /** Falls back to [UnlinkedNativeCore] if the library cannot be loaded (e.g. unsupported ABI). */
        fun load(loadLibrary: () -> Unit = { System.loadLibrary(LIBRARY) }): NativeCore = try {
            loadLibrary()
            val features = JSONObject(NativeLib.nativeFeatures())
            JniNativeCore(NativeLib.nativeVersion(), features.optBoolean("simulator", false))
        } catch (_: LinkageError) {
            UnlinkedNativeCore
        }
    }
}
