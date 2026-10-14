package io.github.victorious93.edlcommander.nativecore

/**
 * Raw JNI declarations. Symbol names in `android/native/edl-jni/src/jni_glue.rs` are derived from
 * this class's package and name, so moving or renaming it breaks the native link.
 * Use [JniNativeCore]; do not call these directly.
 */
internal object NativeLib {
    external fun nativeVersion(): String
    external fun nativeFeatures(): String
    external fun nativeAuthorizeIdentify(audit: String, timestamp: String): String
    external fun nativeAuditAppend(audit: String, timestamp: String, action: String, outcome: String, detail: String): String
    external fun nativeRecordDeniedWrite(audit: String, timestamp: String, command: String): String
    external fun nativeSaharaIdentify(transport: BulkTransport, audit: String, timestamp: String, timeoutMs: Int): String
    external fun nativeSaharaIdentifySimulated(audit: String, timestamp: String): String
}
