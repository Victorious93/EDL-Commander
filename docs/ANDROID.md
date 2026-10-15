# Android companion

**Status: scaffold started.** The `android/` Gradle project builds a debug APK with a bound AIDL service, a list-only USB Host detector and a small Compose screen. It does **not** open USB devices, run Sahara or Firehose, or flash anything, and no device is validated on it. Root is not required by the design.

## Direction: bound companion for Droidcommand-AI (fleet model)

EDL-Commander on Android is a companion app that **Droidcommand-AI** (the hub) controls over a bound service, following the same pattern as the Pentest-Swarm companion:

```
Droidcommand-AI (hub)  ── bindService + AIDL ──►  EDL-Commander companion APK
   same signing key                                 EdlCompanionService (IEdlCompanion)
                                                    CommandRouter  ──► usb.list (enumeration only)
                                                                   ──► sahara.identify (dry-run plan only)
                                                    NativeCore hook ──► edl-core over JNI (not linked yet)
```

The companion keeps its own UI (`MainActivity`) so it is usable standalone. The hub never gets a path around the safety model: commands are routed through `CommandRouter`, and write-class command names are refused.

## Layout

| Path | Purpose |
|---|---|
| `android/` | Gradle project (AGP 8.7, Kotlin 2.0, Compose, Gradle wrapper 8.10.2) |
| `app/src/main/aidl/.../companion/aidl/` | The wire contract: `IEdlCompanion`, `CommandEnvelope`, `ResultEnvelope` |
| `.../command/` | `CommandRouter` (pure Kotlin, JVM-testable), command/result types |
| `.../companion/` | `EdlCompanionService`, `CompanionBinder`, `CallerVerifier` |
| `.../usb/` | `QdLoader` (VID/PID, mirrors `edl_core::usb`), `UsbDeviceSource` (enumeration only) |
| `.../nativecore/` | `NativeCore` hook for the Rust library; only `UnlinkedNativeCore` exists |
| `.../ui/` | Compose screen |

Identifiers: `applicationId` and AIDL package are `io.github.victorious93.edlcommander` (AIDL: `...companion.aidl`). These are placeholders chosen by this repo; **TODO(droidcommand)**: align them with the fleet naming scheme before the first fleet release. The AIDL package is part of the wire contract, so rename it before the hub copies the files.

## AIDL contract (protocol version 1)

Self-contained: no Droidcommand classes or dependency. The hub copies `app/src/main/aidl/io/github/victorious93/edlcommander/companion/aidl/*.aidl` verbatim into its own source set (or consumes them from a shared artifact later).

- `IEdlCompanion.getProtocolVersion()`: handshake.
- `IEdlCompanion.execute(CommandEnvelope) -> ResultEnvelope`: single entry point.
- `CommandEnvelope`: `schemaVersion`, `requestId`, `command` (dotted lowercase name), `argsJson` (JSON object, max 64 KiB), `dryRun` (default `true`).
- `ResultEnvelope`: `status` (`OK`, `DRY_RUN`, `UNSUPPORTED`, `DENIED`, `INVALID`, `ERROR`), stable `code`, human `message`, `payloadJson`, `simulated`, `dryRun`.

Structured parcelables with JSON payloads keep the hub and companion decoupled: new commands and fields are additive, and neither side needs the other's Kotlin classes. Only additive changes are allowed within a protocol version.

Commands in this build:

| Command | Behaviour |
|---|---|
| `companion.capabilities` | Lists commands, features (all write-capable features `false`), `verifiedDevices: []` |
| `companion.status` | `phase: scaffold`, `hardwareValidated: false`, native core status |
| `usb.list` | Enumerates attached USB devices via `UsbManager.deviceList` (arg `edl_only`); flags `05c6:9008`. Never requests permission or opens a device |
| `sahara.identify` | `dryRun=true`: returns the plan, nothing is sent. `dryRun=false`: `UNSUPPORTED` (`native_core_unavailable`) |
| `flash.*`, `firehose.*`, `write.*`, `erase.*`, `format.*`, `unlock.*` | `DENIED` (`writes_not_available`). These do not exist |

A VID/PID match is a USB-level hint only; it is not a compatibility claim and never raises a device's capability level. Results with `simulated: true` are never hardware evidence.

## Same-signing and permissions

1. The manifest declares `io.github.victorious93.edlcommander.permission.BIND_COMPANION` with `protectionLevel="signature"` and requires it on `EdlCompanionService` (`exported=true`, action `io.github.victorious93.edlcommander.companion.BIND`). The system grants the permission only to apps signed with the same key.
2. Droidcommand-AI must declare `<uses-permission android:name="io.github.victorious93.edlcommander.permission.BIND_COMPANION"/>`, `<queries><package android:name="io.github.victorious93.edlcommander"/></queries>` (Android 11+ package visibility), and bind with `Intent(action).setPackage(...)`.
3. Defence in depth: `CompanionBinder` re-checks every transaction with `SignatureCallerVerifier` (`PackageManager.checkSignatures(callingUid, myUid)`), throwing `SecurityException` on mismatch. **TODO(droidcommand)**: the hub package name is unknown to this repo, so `HUB_PACKAGE_ALLOWLIST` is empty and any fleet-signed app is accepted; pin the hub package once known.
4. The signing key is never in the repo. Set `EDL_FLEET_KEYSTORE`, `EDL_FLEET_KEYSTORE_PASSWORD`, `EDL_FLEET_KEY_ALIAS`, `EDL_FLEET_KEY_PASSWORD` to sign debug and release builds with the fleet key. Without them Gradle uses the local debug key, so a CI or laptop debug APK will **not** be bind-compatible with a differently signed Droidcommand. That is intended: unsigned-by-fleet builds cannot be driven by the hub.
5. No `INTERNET` permission is requested; `allowBackup` is off.

## Building

Requirements: JDK 17+, Android SDK with platform 35 and build-tools 35 (`ANDROID_HOME` set). No NDK is needed yet.

```sh
cd android
./gradlew testDebugUnitTest assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

Unit tests cover the command router (including write-command denial, dry-run behaviour and malformed envelopes), caller policy and VID/PID matching, using a fake `UsbDeviceSource` so they run without hardware. The Android job in `.github/workflows/ci.yml` runs the same two tasks and uploads the debug APK. The Rust jobs do not depend on it. If the Android SDK is unavailable (as in some dev containers), skip this section; `cargo test --workspace` is unaffected.

What was **not** run for this scaffold: on-device or emulator tests of the bound service, and any real USB device.

## USB Host (current state and plan)

Today: `UsbDeviceSource` lists devices from `UsbManager` and `QdLoader` flags `05c6:9008`. Nothing is opened. The manifest has no `USB_DEVICE_ATTACHED` filter and no device filter, by design.

Next (separate PR): a runtime `UsbManager.requestPermission` flow initiated by the user in the companion UI (not silently by the hub), then `openDevice`/`claimInterface` of the 9008 interface, with endpoints discovered from the descriptors.

## JNI Transport plan (reaching Sahara in `edl-core`)

Per ARCHITECTURE.md: Kotlin owns `UsbDeviceConnection`; Rust only sees a `Transport`. `edl-core` stays free of USB and JNI dependencies.

1. Add a thin Rust crate (for example `android/native/edl-jni`, `crate-type = ["cdylib"]`, depends on `edl-core` and the `jni` crate) that exposes only what the companion needs: version, Sahara identify, and later the programmer-upload session.
2. Kotlin defines a bridge object with `write(data: ByteArray, timeoutMs: Int): Int` and `read(buf: ByteArray, timeoutMs: Int): Int` backed by `UsbDeviceConnection.bulkTransfer`. The Rust side wraps it as a `Transport` (`write`/`read` with `Duration` timeouts, mapping timeouts to `TransportError::Timeout` and failures to `Disconnected`/`Io`), calls `protocol::sahara` and returns the `DiagLog`, which the companion converts to a `ResultEnvelope`.
3. Build with `cargo ndk` for `arm64-v8a` (and `x86_64` for emulators), output into `app/src/main/jniLibs/`, driven by a Gradle task. This needs the Android NDK and the Rust targets `aarch64-linux-android` and `x86_64-linux-android`.
4. Implement `NativeCore` over the library (`isLinked = true`, `version` from `edl-core`). `sahara.identify` with `dryRun=false` stays gated on user USB permission and `security::authorize`.
5. Tests: run the same Sahara code against `edl-sim` through the JNI boundary on an emulator or host JVM before any hardware, understanding that simulator results are never hardware evidence.

## OnePlus 7T (HD1905)

`database/devices/oneplus-7t-hd1905.json` is a research-target profile at capability level `unknown` with no evidence, no programmers and unknown authentication requirements. It exists so the first real-device work has a profile to attach evidence to. The Snapdragon 855+ read/flash path is a later phase and requires authorized hardware, a verified programmer, and the existing capability ladder.
