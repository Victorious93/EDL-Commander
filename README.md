<p align="center">
  <img src="assets/logo.png" alt="EDL-Commander logo" width="200">
</p>

<p align="center">
  <img src="docs/logo-512.png" alt="EDL Commander logo" width="200">
</p>

# EDL Commander

Open-source, **safety-first** framework for Qualcomm **Emergency Download (EDL)** device recovery, built around a shared Rust engine with planned Ubuntu and Android frontends. On Android it is a bound companion APK controlled by Droidcommand-AI over AIDL (same signing key).

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Rust](https://img.shields.io/badge/Rust-1.80%2B-orange?logo=rust)](https://www.rust-lang.org/)
![Status: Phase 1 + Phase 3 in progress](https://img.shields.io/badge/status-Phase%201%20%2B%20Phase%203%20in%20progress-orange)

> **Status: Phase 1 (foundation) complete; Phase 3 (Android companion) in progress.** **No real hardware has been tested**, and no device is supported or verified. Nothing here can flash a device.
> There is no Firehose, no programmer upload and no Ubuntu app. The Android app (draft, unverified on devices) can list USB devices, ask the user for USB permission for a `05c6:9008` device, and run a **read-only** Sahara identify (serial, hardware ID, OEM PK hash) through a Rust JNI library. Only host/JVM and simulator tests have run. See [ROADMAP.md](ROADMAP.md) and [docs/ANDROID.md](docs/ANDROID.md).

> **New here?** Read the [user manual](docs/MANUAL.md).

## What exists today

| Component | State |
|---|---|
| `core/` Sahara packet codec + host state machine + session runner | Implemented; tested against synthetic fixtures and the simulator only |
| `core/` `Transport` / `UsbEnumerator` traits | Implemented (no real backend yet) |
| `core/` device/chipset/manufacturer/programmer schema + validated DB loader | Implemented |
| `core/` safety policy (read-only default, gated writes, protected partitions) + hash-chained audit log | Implemented |
| `simulator/` deterministic fake EDL target with fault injection | Implemented |
| `core/` read-only Sahara command-mode identify (`run_identify`) + `IdentifyDevice` authorization | Implemented; simulator and synthetic fixtures only |
| `tools/edl-cli` (`validate-db`, `sim`, `sim-identify`) | Implemented; **no USB I/O** |
| Firehose, GPT parsing, firmware import, backup, flashing | Not started |
| `android/` companion APK (Kotlin, Compose) | In progress: builds a debug APK with the native library; user-initiated USB permission flow, open/claim of the 9008 bulk interface and read-only identify are written but **never run on a device or emulator** |
| AIDL bound service for Droidcommand-AI (`IEdlCompanion`, command envelopes) | Commands: capabilities, status, `usb.list`, `usb.request_permission`, `sahara.identify` (dry-run default; live is read-only and audit-logged); write-class commands are denied. Same-signing enforced by signature permission; hub package pin is a TODO |
| `android/native/edl-jni` JNI bridge (callback `Transport` over Kotlin-owned USB bulk transfers; built with cargo-ndk into the APK) | Implemented; adapter tested against `edl-sim` on the host, JNI glue exercised on a host JVM only |
| Real-device Sahara on Android, OnePlus 7T HD1905 path | **No hardware evidence.** HD1905 has a research-only profile at capability `unknown` |
| Ubuntu app | Not started |

## Principles

- Read-only by default; every write passes `security::authorize`.
- Support is a per-device capability level backed by recorded evidence. Simulator results are never evidence.
- No authentication bypasses, secure-boot circumvention, or programmer patching. Devices needing vendor-authorized programmers are reported as such, not attacked.

## Screenshots

| CLI `validate-db` | CLI `sim` fault injection | Future Ubuntu GUI | Android companion |
|---|---|---|---|
| *TODO* | *TODO* | *Not started* | *TODO* |

## Requirements

- Rust **1.80+** (workspace `rust-version`)
- Cargo (workspace members: `core`, `simulator`, `tools/edl-cli`, `android/native/edl-jni`)
- Optional, for the Android companion: JDK 17+, the Android SDK (platform 35) and NDK, Rust Android targets and cargo-ndk; see [docs/ANDROID.md](docs/ANDROID.md)

## Build and test

```sh
git clone https://github.com/Victorious93/EDL-Commander.git
cd EDL-Commander

cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo run -p edl-cli -- validate-db database
cargo run -p edl-cli -- sim [none|silent|malformed|disconnect]
cargo run -p edl-cli -- sim-identify

# Android companion (needs ANDROID_HOME, NDK 27.2.12479018, Rust Android targets, cargo-ndk)
cd android && ./gradlew testDebugUnitTest assembleDebug
```

## Project structure

```text
core/           edl-core — Sahara, device DB, security, diagnostics
simulator/      edl-sim — fake EDL target + fault injection
tools/edl-cli/  CLI (validate-db, sim, sim-identify)
android/        Android companion app (Kotlin/Compose, AIDL service, USB permission/open, JNI)
android/native/edl-jni/  Rust JNI bridge to edl-core (cargo-ndk)
database/       chipsets, devices, manufacturers, programmers
docs/           MANUAL, ANDROID, FLASHING, PROTOCOLS, TESTING, UBUNTU
ARCHITECTURE.md · COMPATIBILITY.md · SECURITY.md · ROADMAP.md · CONTRIBUTING.md
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Keep the safety model: no bypass tooling, no claiming hardware support without evidence. Prefer tests against the simulator over speculative USB code.

## License

[MIT](LICENSE). Details and threat boundaries: [SECURITY.md](SECURITY.md), [ARCHITECTURE.md](ARCHITECTURE.md), [COMPATIBILITY.md](COMPATIBILITY.md).
