<p align="center"><img src="assets/manual-header.svg" alt="EDL Commander user manual" width="720"></p>

# EDL Commander user manual

This manual describes what the code in this repository does today (version `0.2.0-phase3`). It does not describe planned features as if they existed.

> **Important:** Nothing in EDL Commander has been tested on real hardware. No device is supported or verified. The app and tools cannot flash, write, erase or unlock anything. Simulator results are never hardware evidence.

## 1. What EDL Commander is

EDL Commander is a safety-first toolkit for Qualcomm Emergency Download (EDL, USB `05c6:9008` "QDLoader 9008") devices. It has these parts:

- **edl-core** (Rust): the Sahara protocol (packet codec, state machine, session runner, read-only identify), the security policy (`security::authorize`), a hash-chained audit log, and the device database loader.
- **edl-sim** (Rust): a simulated EDL device with fault injection, used in tests and by the CLI.
- **edl-cli** (Rust): a developer command-line tool. It does no USB I/O.
- **Android companion app** (`ai.edlcommander.companion`, Kotlin/Compose) with a Rust JNI library (`edl-jni`) that runs edl-core on the phone.

### Fleet role: BOUND_COMPANION

In the Droidcommand fleet, the Android app is a **bound companion**. The controller, Droidcommand-AI, binds to the app's service over AIDL (`IEdlCompanion`) and sends `CommandEnvelope` requests. The app replies with `ResultEnvelope` results. You can also use the app on its own from its main screen. Both paths share the same command router, USB permission state and audit log.

## 2. Install and setup

### Build from source

Requirements: Rust 1.80+, JDK 17, Android SDK platform 35, NDK `27.2.12479018`, the Rust Android targets, and `cargo-ndk`. See [ANDROID.md](ANDROID.md).

```sh
cd android
./gradlew testDebugUnitTest assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The debug APK includes the native library for `arm64-v8a`, `armeabi-v7a` and `x86_64`. Debug builds also include the simulated device. Release builds leave it out.

### Signing requirement (read this before binding)

The bind service is protected by the permission `ai.edlcommander.companion.permission.BIND_COMPANION`, which has `protectionLevel="signature"`. Android grants it only to apps signed with **the same key** as the companion. The service also re-checks the caller's signature on every call.

- **Debug builds** are signed with the committed, non-secret, per-app debug key (`android/signing/edl-commander-debug.keystore`). A debug build therefore **cannot bind to (or be bound by) a fleet-signed controller**. Their keys differ.
- **Release signing with the shared fleet key is still to come.** The build already reads `EDL_FLEET_KEYSTORE`, `EDL_FLEET_KEYSTORE_PASSWORD`, `EDL_FLEET_KEY_ALIAS` and `EDL_FLEET_KEY_PASSWORD` from the environment for release builds. The fleet key is never stored in the repository.

### Binding from the controller

The controller must:

1. Declare `<uses-permission android:name="ai.edlcommander.companion.permission.BIND_COMPANION" />`.
2. Copy the AIDL files from `android/app/src/main/aidl/ai/edlcommander/companion/aidl/` without changes. The package name is part of the contract.
3. Bind with action `ai.edlcommander.companion.BIND`, package `ai.edlcommander.companion`, component `ai.edlcommander.companion.EdlCompanionService`.
4. Call `getProtocolVersion()` (currently `1`), then `execute(CommandEnvelope)`.

`CommandEnvelope` fields: `schemaVersion` (1), `requestId`, `command` (for example `usb.list`), `argsJson` (a JSON object, `"{}"` for none), and `dryRun` (**defaults to true**).
`ResultEnvelope` fields: `schemaVersion`, `requestId`, `status` (0 OK, 1 DRY_RUN, 2 UNSUPPORTED, 3 DENIED, 4 INVALID, 5 ERROR), `code`, `message`, `payloadJson`, `simulated`, `dryRun`.

## 3. Features and how to use them

### 3.1 On-device main screen

Open **EDL Commander** from the launcher. The buttons are:

- **List USB devices (refresh state)**: runs `usb.list`. This only enumerates devices and opens nothing.
- **Request USB permission for a 9008 device**: shows Android's USB permission dialog. You decide on the dialog.
- **Identify device (read-only, real)**: a real read-only Sahara identify. It needs the USB permission first.
- **Identify simulated device (no USB)**: runs identify against the built-in simulator (debug builds only).
- **Dry-run identify (plan only)**: shows the steps identify would take and touches nothing.
- **Show capabilities**: lists the commands and features of this build.
- **Help / Manual**: opens this manual inside the app.

The result card shows status and code, `(simulated)` when the data came from the simulator, the message, and the JSON payload.

### 3.2 Commands (UI and AIDL)

| Command | Risk | What it does |
|---|---|---|
| `companion.capabilities` | read-only | Lists commands, planned commands, feature flags, and `verifiedDevices` (always empty). |
| `companion.status` | read-only | Phase, `writeMode: "unavailable"`, native core state, `hardwareValidated: false`. |
| `usb.list` | read-only | Lists attached USB devices. Arg `edl_only` (boolean). Shows the permission state of 9008 devices. |
| `usb.request_permission` | user prompt | With `dryRun=true`: a plan only. With `dryRun=false`: shows Android's permission dialog for the single 9008 device (or the one named in `device_name`). Audit-logged. |
| `sahara.identify` | device session | With `dryRun=true`: a plan only. With `dryRun=false`: a read-only identify. Args: `device_name`, `timeout_ms` (100 to 30000, default 5000), and `simulate` (boolean). |

Command names whose first part is `flash`, `firehose`, `write`, `erase`, `format` or `unlock` are **always refused** with `writes_not_available`. Unknown commands return `unknown_command`.

### 3.3 Sahara identify (read-only)

How to run it:

1. Put a device into EDL mode yourself and connect it over USB OTG.
2. Tap **List USB devices** and check that a `05c6:9008` entry appears. VID/PID is only a hint. It is not a compatibility claim.
3. Tap **Request USB permission** and approve the Android dialog.
4. Tap **Identify device (read-only, real)**.

What happens: the app passes `security::authorize(IdentifyDevice)` and records an audit entry. Then it claims the bulk interface and runs Sahara in command mode to read the serial number, the hardware ID and the OEM PK hash. Then it stops. **It does not upload a programmer, does not use Firehose, and does not write anything.** If USB permission was not granted, the command is denied with `usb_permission_required`. If the audit log cannot be written, the command does not run.

This path has **never been run on a real device or emulator**. Only host, JVM and simulator tests exist.

### 3.4 Device database

The `database/` folder holds JSON files for `manufacturers`, `chipsets`, `programmers` and `devices`. It currently contains OnePlus, sdm845 and sdm855plus, plus the OnePlus 6 and the OnePlus 7T HD1905. Both devices are at capability level `unknown`. The levels are `unknown`, `usb_detected`, `sahara_identified`, `compatible_programmer_available`, `read_only_verified`, `backup_verified`, `write_supported` and `recovery_verified`. A level only goes up with recorded evidence.

To validate the database: `cargo run -p edl-cli -- validate-db database`. It prints counts, or the first error it finds.

### 3.5 Simulator

`edl-sim` is a deterministic fake EDL device. Its faults are `none`, `silent`, `malformed` (a bad Hello) and `disconnect` (after 4096 bytes). The tests in `simulator/tests/` and the CLI use it. In the app, the simulator is used only when you pass `simulate: true`, and results are marked `simulated`.

### 3.6 CLI (`edl-cli`)

The CLI is read-only and does no USB I/O.

```sh
cargo run -p edl-cli -- validate-db [dir]                          # check the database
cargo run -p edl-cli -- sim [none|silent|malformed|disconnect]     # SIMULATED programmer-upload session
cargo run -p edl-cli -- sim-identify                               # SIMULATED read-only identify
```

The `sim` command uploads a dummy 16 KiB image to the **simulator only**. It is not hardware evidence.

### 3.7 Safety policy (`security::authorize`)

Read-only is the default. Writes and erases are denied unless **every** one of these holds: write mode is enabled for the session, the device profile is at `write_supported`, authentication is known not to be required, the firmware is verified, and the approved plan digest matches the plan. Writing a protected boot or security partition (`xbl`, `abl`, `tz` and others) also needs a profile allowlist entry and a per-run override. Programmer upload needs a listed programmer whose hash is verified. **No write path is wired into the app or the CLI today.** These gates exist in the core for future use.

## 4. Permissions

From `AndroidManifest.xml`:

- `uses-feature android.hardware.usb.host` (`required="false"`): the app uses USB host mode to list and open devices. It still installs and runs without USB host hardware. It just lists nothing.
- `ai.edlcommander.companion.permission.BIND_COMPANION` (declared, signature level): restricts who can bind the AIDL service to apps signed with the same key.
- Per-device USB access is granted at runtime by Android's USB permission dialog, only when you tap the request button or the controller sends `usb.request_permission` with `dryRun=false`.
- **No INTERNET permission.** Nothing is sent off the device. `allowBackup="false"`.

## 5. Fleet integration and safety gates

- **Controller bind**: see section 2. Two checks apply: the signature permission at bind time, and `SignatureCallerVerifier` on every call. The hub package allowlist contains exactly `ai.droidcommand.app` (release and debug builds share this applicationId) and fails closed: any other package is denied, even one signed with the fleet key. Pinning the hub's signing certificate is a TODO until the release certificate is known.
- **Remote control**: there is **no separate "remote control" on/off switch** in this build. A correctly signed controller can bind at any time. The protection comes from these defaults and gates:
  - `dryRun` defaults to `true`, so an envelope returns only a plan unless the caller sets `dryRun=false`.
  - Write-class commands are refused unconditionally.
  - Live identify needs a USB permission that **the user** granted in Android's dialog on the phone.
  - Every non-dry-run command is audit-logged first. If the log cannot be stored, the command does not run.
  - One device session runs at a time (a device lock).

## 6. Troubleshooting and FAQ

- **Bind fails or `SecurityException ... not signed with the fleet key`**: the controller and companion keys differ. A debug build cannot bind to a fleet-signed controller.
- **`native_core_unavailable`**: the JNI library did not load (for example, a build without native libraries). Identify and audit logging are disabled.
- **`simulator_not_built`**: you are on a release build. The simulator is debug-only.
- **`no_edl_device` / `ambiguous_device`**: no 9008 device is attached, or several are. In the second case, pass `device_name` (from `usb.list`).
- **`usb_permission_required`**: run the permission request and approve the dialog, then poll `usb.list`.
- **`audit_unavailable` / `audit_write_failed`**: the audit log could not be stored, so the command was refused or its result is not trusted.
- **`writes_not_available`**: expected. Flashing does not exist.
- **Can it flash or unbrick my phone?** No. No write path exists, and nothing has been tested on hardware.
- **Does `05c6:9008` mean my device is supported?** No. It is only a USB hint.

## 7. Privacy and data handling

- **What is logged**: one hash-chained JSON Lines record per audited decision. Each record has `seq`, `timestamp`, `action`, `outcome`, `detail`, `prev_hash` and `hash`. Examples: authorization decisions, USB permission requests and answers (with the Android USB device path, for example `device=/dev/bus/usb/...`), denied write attempts (command name), and identify results. Identify records store **only whether** the serial and hardware ID were present, plus the PK-hash length. **The serial, hardware ID and PK hash values themselves are not logged.**
- **Where**: app-private storage, `files/audit/edl-audit.jsonl`. It is not backed up (`allowBackup="false"`) and never leaves the device.
- **Retention**: when the file is larger than 512 KiB, it is moved to `edl-audit.jsonl.1` before the next write, replacing any older rotation. So at most about two files are kept. A log that fails verification is kept as `edl-audit.jsonl.corrupt-<millis>` and a new chain starts. Corrupt files are not cleaned up automatically. Uninstalling the app deletes everything.
- **Tamper-evident, not tamper-proof**: anyone with file access could rewrite the whole chain.
- Identify results go only to the caller, in the `ResultEnvelope` payload (or the on-screen card).
