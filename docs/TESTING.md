# Testing

- Unit tests: `cargo test --workspace` (packet codec, state machine, policy, audit chain, DB validation).
- Simulator end-to-end: `simulator/tests/sahara_e2e.rs` (malformed, silent, disconnect, out-of-range, out-of-order, rejection, budget).
- Database: `core/tests/database.rs` and `edl-cli validate-db`.
- Hardware tests: none exist. They must live apart from simulated tests and be the only source of `hardware: true` evidence.
- Simulator success never implies hardware compatibility.
- Identify: `simulator/tests/identify_e2e.rs` (command-mode identify, only read-only packets sent, hostile/faulty devices) and `edl-cli sim-identify`.
- JNI crate: unit tests in `android/native/edl-jni` drive the callback `Transport` adapter and identify ops through `edl-sim` using the same integer return codes the Kotlin side uses.
- Android: `cd android && ./gradlew testDebugUnitTest assembleDebug` (needs NDK + cargo-ndk, or `-Pedl.skipNative=true`). JVM tests use fakes for permission/session/audit handling and, in `NativeBridgeJvmTest`, load a host build of `libedl_jni` to exercise the real JNI glue against a Kotlin Sahara test double. No emulator, instrumented or hardware tests exist yet; Android USB classes (`AndroidUsb.kt`) are not covered.
