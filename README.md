<div align="center">

<img src="assets/logo.png" width="200" alt="EDL-Commander logo"/>

# EDL-Commander

**Safety-first Qualcomm EDL recovery framework with an Android companion.**

<p>
  <a href="https://github.com/Victorious93/EDL-Commander/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/Victorious93/EDL-Commander/ci.yml?branch=main&style=flat-square&labelColor=0B0F1A&logo=githubactions&logoColor=00E5FF&label=CI" alt="CI"/></a>
  <a href="https://github.com/Victorious93/EDL-Commander/releases"><img src="https://img.shields.io/github/v/release/Victorious93/EDL-Commander?include_prereleases&style=flat-square&labelColor=0B0F1A&color=00E5FF&label=release" alt="Release"/></a>
  <img src="https://img.shields.io/badge/license-MIT-FF2BD6?style=flat-square&labelColor=0B0F1A" alt="License"/>
  <img src="https://img.shields.io/badge/platform-Rust%20%26%20Android-00E5FF?style=flat-square&labelColor=0B0F1A" alt="Platform"/>
  <a href="https://victorious-framework.pages.dev"><img src="https://img.shields.io/badge/Victorious-Framework-FF2BD6?style=flat-square&labelColor=0B0F1A" alt="Victorious Framework"/></a>
</p>

<p><a href="https://github.com/Victorious93/EDL-Commander/releases">Releases</a> · <a href="https://victorious-framework.pages.dev">Victorious Framework</a></p>

</div>

---

Open-source, **safety-first** framework for Qualcomm **Emergency Download (EDL)** device recovery, built around a shared Rust engine with an Android companion app and a planned Ubuntu frontend. Android package id: `ai.edlcommander.companion`.

> **Status: early. Phase 1 (foundation) is done in simulation; Phase 3 (Android companion) is in progress.** No real hardware has been tested. Nothing here can flash a device: there is no Firehose and no write path. See [ROADMAP.md](ROADMAP.md).

## What exists today

| Component | State |
|---|---|
| `core/` Sahara packet codec, host state machine, session runner, read-only command-mode identify | Implemented; tested against synthetic fixtures and the simulator only |
| `core/` `Transport` / `UsbEnumerator` traits | Implemented (no libusb backend yet) |
| `core/` device / chipset / manufacturer / programmer schema + validated DB loader | Implemented |
| `core/` safety policy (read-only default, gated writes, protected partitions) + hash-chained audit log | Implemented |
| `simulator/` deterministic fake EDL target with fault injection | Implemented |
| `tools/edl-cli` (`validate-db`, `sim`, `sim-identify`, `scrcpy`) | Implemented. Database and simulator commands perform **no USB I/O**. The optional `scrcpy` command talks only to host `adb`/`scrcpy` and never to EDL/9008 devices |
| `android/` companion app (Kotlin/Compose, AIDL `IEdlCompanion` service, USB 9008 list/detect, user-initiated USB permission flow, JNI core, audit log, in-app manual, fleet bridge) | Scaffold; builds a debug APK. Written but **unverified on devices**; not run on an emulator or real phone yet |
| Firehose, GPT parsing, firmware import, backup, flashing | Planned (not started) |
| Ubuntu app | Planned (not started) |

## Principles

- Read-only by default; every write passes `security::authorize`.
- Support is a per-device capability level backed by recorded evidence. Simulator results are never evidence.
- No authentication bypasses, secure-boot circumvention, or programmer patching. Devices needing vendor-authorized programmers are reported as such, not attacked.

## Install

A debug pre-release APK (`v0.1.0-debug`, older than the current source) is on the [Releases page](https://github.com/Victorious93/EDL-Commander/releases). It is a scaffold, not a recovery tool. The Android app requests **no INTERNET permission**; it exposes a bound service guarded by its own `BIND_COMPANION` signature permission for droidcommand-AI.

## Requirements

- Rust **1.80+** and Cargo (workspace members: `core`, `simulator`, `tools/edl-cli`).
- For the Android app: JDK 17, the Android SDK, and Rust Android targets with `cargo-ndk` for the native libraries (the `edl.skipNative=true` property can skip native).

## Build and test

```sh
git clone https://github.com/Victorious93/EDL-Commander.git
cd EDL-Commander

cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo run -p edl-cli -- validate-db database
cargo run -p edl-cli -- sim [none|silent|malformed|disconnect]
cargo run -p edl-cli -- scrcpy --dry-run
cd android && ./gradlew :app:assembleDebug
```

CI runs the Rust formatting, clippy and test steps on every push and pull request.

## Host-side scrcpy (optional)

`edl-cli scrcpy` can launch [scrcpy](https://github.com/Genymobile/scrcpy) on the **host computer** to view and control a device's screen once that device is already back in normal Android / ADB mode during recovery.

This is optional. There is no hard dependency on scrcpy. The Android companion app does not run scrcpy. The command never opens a Sahara or Firehose session, never calls the write gates, and never talks to EDL / Qualcomm 9008 devices.

```sh
# Print the exact command that would run, without launching scrcpy:
cargo run -p edl-cli -- scrcpy --dry-run

# Launch scrcpy for a specific device; extra arguments after -- are passed through:
cargo run -p edl-cli -- scrcpy --serial ABC123 -- --turn-screen-off
```

- scrcpy is resolved from `PATH`, or from `SCRCPY_BIN` if that environment variable is set.
- If scrcpy is not installed, the command prints a short message pointing to https://github.com/Genymobile/scrcpy and exits non-zero. It does not panic.
- The command runs `adb devices` first. It refuses to launch when adb is missing, when no device is listed, or when the device is `unauthorized` or `offline`. The serial is passed to scrcpy as `-s`.
- `--dry-run` prints the exact command and does not start scrcpy.
- If `EDL_AUDIT_LOG` is set to a file path, a hash-chained audit record is appended. Device serials are stored with the same `usb#` plus 12-hex SHA-256 tag used elsewhere in the audit log (pseudonymisation, not secrecy). Raw serials are not written to the log.

This path has been tested only with fake `adb` and `scrcpy` binaries in unit tests. Real scrcpy and a real device have not been tested.

## Project structure

```text
core/           edl-core: Sahara, device DB, security, diagnostics
simulator/      edl-sim: fake EDL target + fault injection
tools/edl-cli/  CLI (validate-db, sim, sim-identify, scrcpy)
android/        Android companion app (Gradle, Kotlin/Compose, AIDL, JNI)
database/       chipsets, devices, manufacturers, programmers
docs/           ANDROID, FLASHING, PROTOCOLS, TESTING, UBUNTU
ARCHITECTURE.md · COMPATIBILITY.md · SECURITY.md · ROADMAP.md · CONTRIBUTING.md
```

## Credits

scrcpy by Genymobile, Apache-2.0, https://github.com/Genymobile/scrcpy. It is not bundled. See [NOTICE](NOTICE). Threat boundaries: [SECURITY.md](SECURITY.md), [ARCHITECTURE.md](ARCHITECTURE.md), [COMPATIBILITY.md](COMPATIBILITY.md).

---

## Victorious fleet

EDL-Commander is part of the [Victorious Framework](https://victorious-framework.pages.dev), a family of Android and PC tools by Don Victor. **Every app works standalone; fleet integrations are optional** and never required.

Related: [droidcommand-AI](https://github.com/Victorious93/droidcommand-AI) (fleet controller), [victorious-flash](https://github.com/Victorious93/victorious-flash) (flashing tool), [VictorRecovery](https://github.com/Victorious93/VictorRecovery) (custom recovery). The full roster lives in [victorious-fleet](https://github.com/Victorious93/victorious-fleet), and live status is in [fleet-manifest](https://github.com/Victorious93/fleet-manifest).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Keep the safety model: no bypass tooling, no claiming hardware support without evidence. Prefer tests against the simulator over speculative USB code.

## License

[MIT](LICENSE). Upstream attribution: see [NOTICE](NOTICE).
