<p align="center">
  <img src="docs/logo-512.png" alt="EDL Commander logo" width="200">
</p>

# EDL Commander

Open-source, **safety-first** framework for Qualcomm **Emergency Download (EDL)** device recovery, built around a shared Rust engine with planned Ubuntu and Android frontends. On Android it is a bound companion APK controlled by Droidcommand-AI over AIDL (same signing key).

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Rust](https://img.shields.io/badge/Rust-1.80%2B-orange?logo=rust)](https://www.rust-lang.org/)
![Status: Phase 1 + Android scaffold](https://img.shields.io/badge/status-Phase%201%20%2B%20Android%20scaffold-orange)

> **Status: Phase 1 (foundation) plus an Android companion scaffold.** No real hardware has been tested. Nothing here can flash a device.
> There is no Firehose, no Ubuntu app, no JNI/native link, and no USB open/transfer path. The Android app only lists USB devices and answers dry-run/status commands. See [ROADMAP.md](ROADMAP.md) and [docs/ANDROID.md](docs/ANDROID.md).

## What exists today

| Component | State |
|---|---|
| `core/` Sahara packet codec + host state machine + session runner | Implemented; tested against synthetic fixtures and the simulator only |
| `core/` `Transport` / `UsbEnumerator` traits | Implemented (no real backend yet) |
| `core/` device/chipset/manufacturer/programmer schema + validated DB loader | Implemented |
| `core/` safety policy (read-only default, gated writes, protected partitions) + hash-chained audit log | Implemented |
| `simulator/` deterministic fake EDL target with fault injection | Implemented |
| `tools/edl-cli` (`validate-db`, `sim`) | Implemented; **no USB I/O** |
| Firehose, GPT parsing, firmware import, backup, flashing | Not started |
| `android/` companion APK (Kotlin, Compose) | Scaffold started: builds a debug APK; USB Host 9008 **list/detect only** (VID/PID); no device is opened |
| AIDL bound service for Droidcommand-AI (`IEdlCompanion`, command envelopes) | Stub live: capabilities, status, `usb.list`, dry-run `sahara.identify`; write-class commands are denied. Same-signing enforced by signature permission; hub package pin is a TODO |
| JNI link from the Android app to `edl-core` (Sahara) | Planned (hook and plan only; not linked) |
| Real USB open/Sahara on Android, OnePlus 7T HD1905 path | Not started (HD1905 has a research-only profile at `unknown`) |
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
- Cargo (workspace members: `core`, `simulator`, `tools/edl-cli`)
- Optional, for the Android companion: JDK 17+ and the Android SDK (platform 35); see [docs/ANDROID.md](docs/ANDROID.md)

## Build and test

```sh
git clone https://github.com/Victorious93/EDL-Commander.git
cd EDL-Commander

cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo run -p edl-cli -- validate-db database
cargo run -p edl-cli -- sim [none|silent|malformed|disconnect]

# Android companion (needs ANDROID_HOME)
cd android && ./gradlew testDebugUnitTest assembleDebug
```

## Project structure

```text
core/           edl-core — Sahara, device DB, security, diagnostics
simulator/      edl-sim — fake EDL target + fault injection
tools/edl-cli/  CLI (validate-db, sim)
android/        Android companion app (Kotlin/Compose, AIDL service, USB detect stubs)
database/       chipsets, devices, manufacturers, programmers
docs/           ANDROID, FLASHING, PROTOCOLS, TESTING, UBUNTU
ARCHITECTURE.md · COMPATIBILITY.md · SECURITY.md · ROADMAP.md · CONTRIBUTING.md
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Keep the safety model: no bypass tooling, no claiming hardware support without evidence. Prefer tests against the simulator over speculative USB code.

## License

[MIT](LICENSE). Details and threat boundaries: [SECURITY.md](SECURITY.md), [ARCHITECTURE.md](ARCHITECTURE.md), [COMPATIBILITY.md](COMPATIBILITY.md).
