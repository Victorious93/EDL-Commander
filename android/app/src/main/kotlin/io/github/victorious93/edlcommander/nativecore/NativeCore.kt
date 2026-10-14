package io.github.victorious93.edlcommander.nativecore

/**
 * Hook for the Rust `edl-core` library (Sahara state machine, device DB, security policy).
 *
 * Planned wiring (docs/ANDROID.md, "JNI Transport plan"): Kotlin owns the `UsbDeviceConnection`
 * and exposes bulk read/write to Rust through a callback object that implements
 * `edl_core::transport::Transport`. Nothing is linked yet; [UnlinkedNativeCore] is the only
 * implementation and every device-session command therefore reports `native_core_unavailable`.
 */
interface NativeCore {
    val isLinked: Boolean

    /** `edl-core` crate version once linked, otherwise null. */
    val version: String?
}

object UnlinkedNativeCore : NativeCore {
    override val isLinked: Boolean = false
    override val version: String? = null
}
