# Android companion

**Status: Phase 3 in progress; nothing has been run on real hardware.** The `android/` Gradle project builds a debug APK that contains a Rust native library (`libedl_jni.so`, built with cargo-ndk). The app can list USB devices, ask the user for USB permission for a `05c6:9008` device, open and claim its bulk interface, and run a **read-only** Sahara identification (serial, hardware ID, OEM PK hash). It does **not** upload programmers, speak Firehose, or write, flash, erase or unlock anything, and no device is validated on it. Root is not required by the design.

What was exercised, and how, is listed under [Verification status](#verification-status). The short version: JVM and host tests (including the real JNI glue and a Kotlin Sahara test double) pass; the Android-specific USB code, the system permission dialog and every real device interaction have **never run**.

## Direction: bound companion for Droidcommand-AI (fleet model)

EDL-Commander on Android is a companion app that **Droidcommand-AI** (the hub) controls over a bound service, following the same pattern as the Pentest-Swarm companion:

```
Droidcommand-AI (hub)  ── bindService + AIDL ──►  EDL-Commander companion APK
   same signing key                                 EdlCompanionService (IEdlCompanion)
                                                    CommandRouter ──► usb.list
                                                                  ──► usb.request_permission  (user dialog)
                                                                  ──► sahara.identify         (read-only)
                                                    AuditTrail ──► hash-chained log (Rust)
                                                    UsbBulkPipe ◄─► libedl_jni ──► edl-core Sahara (command mode)
```

The companion keeps its own UI (`MainActivity`) so it is usable standalone. The hub never gets a path around the safety model: commands are routed through `CommandRouter`, and write-class command names are refused.

## Layout

| Path | Purpose |
|---|---|
| `android/` | Gradle project (AGP 8.7, Kotlin 2.0, Compose, Gradle wrapper 8.10.2) |
| `android/native/edl-jni/` | Rust crate `edl-jni` (workspace member): JNI glue, callback `Transport` adapter, read-only identify ops |
| `app/src/main/aidl/.../companion/aidl/` | The wire contract: `IEdlCompanion`, `CommandEnvelope`, `ResultEnvelope` |
| `.../bridge/` | Bridge envelope v1.1: `BridgeGate` (validation, TTL/skew, nonce cache, classification), `RemoteDispatcher` (remote entry point, remote-control switch), `PreferencesRemoteControlSetting` |
| `.../command/` | `CommandRouter` (pure Kotlin, JVM-testable), command/result types |
| `.../companion/` | `EdlCompanionService`, `CompanionBinder`, `CallerVerifier`, `CompanionRuntime` (process-wide wiring shared by UI and service) |
| `.../usb/` | `QdLoader` (VID/PID), `UsbDeviceSource`, permission gate + tracker, `EndpointSelector`, `UsbBulkPipe`, Android implementations (`AndroidUsb.kt`) |
| `.../nativecore/` | `NativeCore` interface, `BulkTransport`, `JniNativeCore` (loads `libedl_jni`), raw `NativeLib` JNI declarations, `UnlinkedNativeCore` fallback |
| `.../audit/` | `AuditTrail` (fail-closed audit recording), `AuditStore` (file / in-memory) |
| `.../ui/` | Compose screen |

Identifiers (renamed from the `io.github.victorious93.edlcommander` placeholders): `applicationId`, Gradle `namespace` and Kotlin root package are `ai.edlcommander.companion`; the service is `ai.edlcommander.companion.EdlCompanionService` (bind action `ai.edlcommander.companion.BIND`, permission `ai.edlcommander.companion.permission.BIND_COMPANION`); the AIDL package is `ai.edlcommander.companion.aidl` (interface `IEdlCompanion`, parcelables `CommandEnvelope`/`ResultEnvelope`). The AIDL package is part of the wire contract: the hub must copy the files from the new path and use the new package. The JNI symbol names derive from the `nativecore.NativeLib` class name and package (`Java_ai_edlcommander_companion_nativecore_NativeLib_*`); renaming either requires renaming the `Java_...` functions in `jni_glue.rs`.

## AIDL contract (protocol version 1)

Self-contained: no Droidcommand classes or dependency. The hub copies `app/src/main/aidl/ai/edlcommander/companion/aidl/*.aidl` verbatim into its own source set (or consumes them from a shared artifact later). The AIDL interface itself is unchanged apart from the package rename to `ai.edlcommander.companion.aidl`; everything new is additive commands and JSON fields.

- `IEdlCompanion.getProtocolVersion()`: handshake.
- `IEdlCompanion.execute(CommandEnvelope) -> ResultEnvelope`: single entry point. Remote callers are subject to the remote-control switch (off by default); see [Bridge envelope v1.1](#bridge-envelope-v11-and-the-remote-control-switch).
- `CommandEnvelope`: `schemaVersion`, `requestId`, `command` (dotted lowercase name), `argsJson` (JSON object, max 64 KiB), `dryRun` (default `true`).
- `ResultEnvelope`: `status` (`OK`, `DRY_RUN`, `UNSUPPORTED`, `DENIED`, `INVALID`, `ERROR`), stable `code`, human `message`, `payloadJson`, `simulated`, `dryRun`.

Commands:

| Command | Behaviour |
|---|---|
| `companion.capabilities` | Lists commands, features (computed from what is actually linked), `verifiedDevices: []` |
| `companion.status` | `phase: phase3_in_progress`, `hardwareValidated: false`, native core version, simulator availability |
| `usb.list` | Enumerates attached USB devices via `UsbManager.deviceList` (arg `edl_only`); flags `05c6:9008` and reports each 9008 device's `permission` state (`not_requested`, `requested`, `granted`, `denied`). Never requests permission or opens a device |
| `usb.request_permission` | `dryRun=true`: plan only. `dryRun=false`: asks Android to show its USB permission dialog for the single `05c6:9008` device (or `device_name`, which must also be 9008). Returns `OK/permission_prompt_shown` immediately; the user answers on the device and the caller polls `usb.list`. `OK/permission_already_granted` if nothing needs asking. Refused (`DENIED`) for any non-9008 device. Audited |
| `sahara.identify` | `dryRun=true`: returns the plan, nothing is sent. `dryRun=false`: read-only identification over Sahara command mode, see below. Args: `device_name`, `timeout_ms` (100..30000, default 5000), `simulate` |
| `flash.*`, `firehose.*`, `write.*`, `erase.*`, `format.*`, `unlock.*` | `DENIED` (`writes_not_available`). These do not exist. A `dryRun=false` attempt is additionally recorded in the audit log |

A VID/PID match is a USB-level hint only; it is not a compatibility claim and never raises a device's capability level. Results with `simulated: true` are never hardware evidence.

### `sahara.identify` with `dryRun=false`

In order, stopping at the first failure:

1. Native core must be loaded (otherwise `UNSUPPORTED/native_core_unavailable`). With `simulate: true` the built-in simulated target is used instead of USB (result has `simulated: true`; `UNSUPPORTED/simulator_not_built` in release builds) and steps 2 to 4 are skipped except authorization and audit.
2. Pick the single `05c6:9008` device (`ambiguous_device` if several, `no_edl_device` if none).
3. **The user must already have granted USB permission** (`usb_permission_required` otherwise; the denial is audited). Run `usb.request_permission` first.
4. `security::authorize(Operation::IdentifyDevice)` in `edl-core`, audited. This operation is read-only and needs no device profile, because identification is how a profile gets matched.
5. Open the device, choose the first interface with a bulk IN and a bulk OUT endpoint (vendor class preferred), `claimInterface(force=true)`. Failures: `usb_permission_required`, `open_failed`, `no_bulk_interface`, `claim_failed`.
6. Run `edl_core::protocol::sahara::run_identify` over the JNI callback transport: device HELLO, host HELLO_RESP requesting **command mode**, `CMD_READY`, then `CMD_EXEC` for serial, MSM hardware ID and OEM PK hash (each followed by `CMD_EXEC_DATA` and raw bytes), then a best-effort `SWITCH_MODE` back to image-transfer mode. No `READ_DATA`, no image, no `DONE`.
7. Append an outcome record, release the interface, close the connection.

The payload contains `report` (`saharaVersion`, `serial`, `msmHwId`, `msmId`, `oemId`, `modelId`, `oemPkHash`, `oemPkHashBytes`, `returnedToImageMode`), `device`, `diag`, and `auditPersisted`. Failure codes include `device_timeout`, `device_disconnected`, `transport_error`, `malformed_packet`, `incompatible_version`, `unsupported_mode`, `protocol_error`, `packet_budget_exceeded`.

The serial number and PK hash are device identifiers. They are returned to the (same-signed) caller and shown in the on-device UI but are never written to the audit log. The `msmId`/`oemId`/`modelId` split of the 64-bit hardware ID follows public descriptions and is unverified; none of this establishes compatibility or capability.

## Bridge envelope v1.1 and the remote-control switch

The frozen fleet remote-control envelope (`BRIDGE_PROTOCOL_MAJOR=1`, `MINOR=1`) is carried by appended, flat fields on `CommandEnvelope`/`ResultEnvelope`; set `bridgeProtocolMajor=1` to use it. Enforcement lives in `.../bridge/` (`BridgeGate`, `ActionClassifier`, `NonceCache`, `RemoteDispatcher`), in front of the unchanged `CommandRouter`. Full spec: [BRIDGE_PROTOCOL.md](BRIDGE_PROTOCOL.md).

- **Remote control is OFF by default.** The user enables it with "Allow remote control" in the companion UI (stored in app-private preferences; no AIDL command can change it). While off, every remote call except `capabilities.get` (bridge) or `companion.capabilities` (legacy) returns `external_control_disabled`. The on-device UI is unaffected.
- Expiry (TTL default 120 s, max 300 s; skew limit 120 s), duplicate-nonce rejection keyed by `(sourceNodeRef, nonce)` for 10 minutes, and `sourceNodeRef` derived from the Binder calling uid.
- Flash, bootloader, EDL-write, erase, unlock and similar actions are classified `destructive`, `remoteAllowed: false`, and refused with `unauthorized` (also in dry runs), before the router is reached. The advisory `destructive` flag can only raise the classification.
- `macro.run` with `dryRun=true` only returns a classified plan; it never reaches the router or a device.
- `node.status.get` (fleet STATUS-TRAY) returns a `NodeStatusSnapshot` with a single `node_health` domain, built from app state only (no USB or Sahara). It is refused with `external_control_disabled` while remote control is off.
- `config.export` / `config.import` (fleet UNIFIED_BACKUP) are local only (`remoteAllowed: false`, remote calls get `unauthorized`). The in-app "Settings backup" card exports allowlisted preferences only. Import previews by default, applies only after an on-device confirmation, never imports the remote-control switch, and turns remote control OFF after applying.

## Same-signing and permissions

1. The manifest declares `ai.edlcommander.companion.permission.BIND_COMPANION` with `protectionLevel="signature"` and requires it on `EdlCompanionService` (`exported=true`, action `ai.edlcommander.companion.BIND`). The system grants the permission only to apps signed with the same key.
2. Droidcommand-AI must declare `<uses-permission android:name="ai.edlcommander.companion.permission.BIND_COMPANION"/>`, `<queries><package android:name="ai.edlcommander.companion"/></queries>` (Android 11+ package visibility), and bind with `Intent(action).setPackage(...)`.
3. Defence in depth: `CompanionBinder` re-checks every transaction with `SignatureCallerVerifier` (`PackageManager.checkSignatures(callingUid, myUid)`), throwing `SecurityException` on mismatch. **TODO(droidcommand)**: the hub package name is unknown to this repo, so `HUB_PACKAGE_ALLOWLIST` is empty and any fleet-signed app is accepted; pin the hub package once known.
4. **Debug builds** are always signed with the committed per-app debug key `android/signing/edl-commander-debug.keystore` (alias `androiddebugkey`, passwords `android`, SHA-256 `E5:60:B6:92:BD:E6:51:9E:B7:CB:B5:2C:E4:DC:91:5A:19:E4:41:A0:1A:5D:03:68:82:71:AC:99:78:8A:58:C8`), so the debug fingerprint is the same on every laptop and CI run. It is public and non-secret, used for EDL-Commander debug builds only, and must **never** be used for release. A debug APK is therefore **not** bind-compatible with a fleet-signed Droidcommand. See `android/signing/README.md`.
5. **Release builds**: the fleet signing key is never in the repo. Set `EDL_FLEET_KEYSTORE`, `EDL_FLEET_KEYSTORE_PASSWORD`, `EDL_FLEET_KEY_ALIAS`, `EDL_FLEET_KEY_PASSWORD` to sign release builds with the fleet key. That is intended: builds not signed by the fleet key cannot be driven by the hub.
6. No `INTERNET` permission is requested; `allowBackup` is off.

## USB permission model

- Permission is the standard Android per-device grant: `UsbManager.requestPermission` shows the **system** dialog and only the user can accept it. Nothing in the app grants it, there is no `USB_DEVICE_ATTACHED` intent filter or `device_filter.xml` (which could pre-authorize access), and `request` refuses anything that is not `05c6:9008`.
- The prompt is only issued by `usb.request_permission` with `dryRun=false` (the UI button, or a bound same-signed caller; the user still decides on the device). Opening a device never prompts: `AndroidEdlSessionOpener` fails with `usb_permission_required` unless `UsbManager.hasPermission` is already true.
- `UsbManager.hasPermission` is authoritative for `granted`. `UsbPermissionTracker` only remembers `requested` and `denied`, which the system does not expose, and forgets devices that detach. Android grants last until the device is unplugged or the system revokes them; a re-plugged device in EDL needs a new prompt.
- The `PendingIntent` for the result is mutable (the system adds extras) but explicit (`setPackage`), and the receiver is registered `RECEIVER_NOT_EXPORTED`.

## Audit log

Every command with `dryRun=false` that reaches a handler is recorded in the hash-chained audit log from `edl-core` (`AuditLog`, JSON Lines, tamper-evident only): permission requests and their outcome, identify authorization, denials (including missing permission and write-class attempts) and the identify outcome. Records hold actions, outcomes and codes, never serial numbers or key hashes.

- The chain is computed in Rust; Kotlin passes the current log in and persists the returned log (`filesDir/audit/edl-audit.jsonl`, atomic replace, rotated to `.1` above 512 KiB).
- **Fail closed:** if the native core is not loaded, or a record cannot be stored, the command that needed it does not run (`audit_unavailable` / `native_core_unavailable`). The authorization record is written before the device is opened.
- A log that fails verification is moved aside to `edl-audit.jsonl.corrupt-<millis>` (never deleted) and a new chain starts.
- Write-class commands are refused by the router regardless; the denial record carries the reason produced by `security::authorize`.

## Native core (`edl-jni`) and the JNI transport

Per ARCHITECTURE.md: Kotlin owns `UsbDeviceConnection`; Rust only sees a `Transport`. `edl-core` has no USB or JNI dependency.

- `BulkTransport` (Kotlin) has `write(ByteArray, timeoutMs): Int` and `read(ByteArray, timeoutMs): Int` returning bytes transferred or `-1` timeout / `-2` I/O error / `-3` disconnected. `UsbBulkPipe` implements it over `UsbDeviceConnection.bulkTransfer`, chunking writes at 16 KiB and **classifying failures**, because `bulkTransfer` reports every failure as `-1`: device no longer in `deviceList` means disconnected; a call that used (nearly) its whole timeout means timeout; anything else is an I/O error. A timeout of 0 (infinite on Android) is never passed through. The elapsed-time classification is a heuristic that has not been checked on hardware.
- `edl_jni::bridge::CallbackTransport` adapts the byte bridge to `edl_core::transport::Transport` (codes to `TransportError`, short writes and oversized read reports rejected). `jni_glue.rs` implements the bridge by calling the Kotlin methods by name and signature (`write([BI)I`, `read([BI)I`), clears Java exceptions into an I/O error, deletes local references per call, and wraps every entry point in `catch_unwind` so a panic becomes a `native_panic` JSON error instead of crossing the FFI boundary.
- JNI entry points (class `nativecore.NativeLib`): version, features, authorize identify, audit append, record denied write, Sahara identify, simulated identify. All return JSON strings (`ops.rs`). The `sim` Cargo feature (default) bundles `edl-sim` for the `simulate` argument; release builds use `--no-default-features`.
- The library is built by Gradle (`buildEdlJni<Variant>`, `cargo ndk`) into the variant's `jniLibs`: `arm64-v8a`, `armeabi-v7a`, `x86_64` by default (`-Pedl.abis=arm64-v8a` to narrow). `x86` is not built. `libedl_jni` is also loaded in JVM unit tests from a host build (`buildEdlJniHost`).

If the library fails to load (unsupported ABI, stripped build), `JniNativeCore.load` falls back to `UnlinkedNativeCore` and every device command reports `native_core_unavailable`.

## Building

Requirements: JDK 17+, Android SDK with platform 35 and build-tools (`ANDROID_HOME`), **NDK 27.2.12479018** (`sdkmanager "ndk;27.2.12479018"`), Rust (stable) with the Android targets, and cargo-ndk:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked
cd android
./gradlew testDebugUnitTest assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk  (contains lib/<abi>/libedl_jni.so)
```

`-Pedl.skipNative=true` builds and tests without NDK/Rust (the app then runs without the native library, and the JNI tests are skipped). cargo-ndk 4.x needs a recent Rust to install; with an older toolchain install `cargo-ndk --version 3.5.4`.

Tests:

- Rust: `cargo test --workspace` covers the command-mode packets, `run_identify` against the simulator (including hostile/faulty devices), the audit reload, `IdentifyDevice` authorization, and the `edl-jni` adapter and ops (the adapter is driven by `edl-sim` through the same integer return-code convention Kotlin uses).
- Kotlin (JVM): router and permission/state handling with fakes (`IdentifyFlowTest`), `UsbPermissionTracker`, `EndpointSelector`, `UsbBulkPipe`, audit store/trail, and `NativeBridgeJvmTest`, which loads the real host `libedl_jni` and runs the router end to end (router, audit, JNI, `UsbBulkPipe`-shaped callbacks) against an independent Kotlin Sahara test double.
- CI: the `android` job installs the NDK, Rust targets and cargo-ndk, runs both Gradle tasks and checks the APK contains `libedl_jni.so` for each ABI.

## Verification status

| Piece | Status |
|---|---|
| Sahara command-mode codec, `run_identify`, authorization, audit reload | Unit/integration tested against synthetic fixtures and the simulator only |
| `edl-jni` adapter and ops | Unit tested on the host against `edl-sim` |
| JNI glue (callbacks, arrays, exceptions) | Exercised on a **host JVM** with the host build of the library; not on Android/bionic, not on an emulator |
| APK packaging of `libedl_jni.so` (arm64-v8a, armeabi-v7a, x86_64) | Built and inspected; never installed or launched |
| `AndroidUsbPermissionGate`, `AndroidEdlSessionOpener`, `UsbBulkPipe` on a real `UsbManager` | **Never run**; only the pure logic (selection, chunking, error classification, permission state) is tested |
| System permission dialog behaviour from a bound service | **Unverified** (it should not need an activity, but this has not been tried) |
| Real device (any), real 9008 enumeration, real Sahara bytes | **Never run.** Packet layouts and the HW-ID field split are from public descriptions, not captures |

## OnePlus 7T (HD1905)

`database/devices/oneplus-7t-hd1905.json` is a research-target profile at capability level `unknown` with no evidence, no programmers and unknown authentication requirements. It is the first planned real-device target and the intended subject of the first read-only identify attempt, but nothing has been run, so it stays at `unknown`. A successful identify would, at most, be recorded as hardware evidence for `sahara_identified` through the normal evidence rules, by a person, from a real capture; the app never edits the database. The Snapdragon 855+ read/flash path is a later phase and requires authorized hardware, a verified programmer, and the existing capability ladder. Many devices of this generation require vendor-authorized programmers; this project does not attempt to bypass that.

## Not done / follow-ups

- Run on a real device and an emulator; capture real Sahara traffic and add capture-based tests before trusting any layout.
- Pin the Droidcommand-AI hub package and align identifiers with fleet naming.
- Droidcommand-AI must adopt the bridge envelope v1.1 fields (see BRIDGE_PROTOCOL.md); then consider retiring the legacy path.
- Decide how a hub should be told that a permission dialog was answered (today: poll `usb.list`).
- Evidence-recording workflow for a successful identify (outside the app).
- Everything beyond identification (programmer upload, Firehose) stays out until a device has evidence and a verified programmer.
