<!-- victorious-fleet-readme-refresh:start -->
<p align="center">
  <img src="assets/logo.png" alt="EDL-Commander logo" width="200">
</p>

# EDL-Commander

Safety-first Qualcomm EDL (Emergency Download mode) companion for the fleet.

**Package id:** `ai.edlcommander.companion`

> **Package note:** From `android/app/build.gradle.kts`. Native core via Rust/`cargo-ndk`.

**Status:** scaffold / CI *(from [fleet-manifest ROADMAP](https://github.com/Victorious93/fleet-manifest/blob/main/ROADMAP.md))*

## Install / build

Android under `android/` plus Rust workspace. Needs Rust Android targets and `cargo-ndk` for native libs; property `edl.skipNative=true` may skip native. Typical: `cd android && ./gradlew :app:assembleDebug` — verify README/CI.

_Steps above are taken from this repo’s build files / existing docs; anything not exercised in this refresh is unlabeled as verified on a device._

## Victorious fleet

- Monorepo: [https://github.com/Victorious93/victorious-fleet](https://github.com/Victorious93/victorious-fleet)
- Fleet site: [https://victorious-framework.pages.dev](https://victorious-framework.pages.dev)

<!-- victorious-fleet-readme-refresh:end -->

Open-source, **safety-first** framework for Qualcomm **Emergency Download (EDL)** device recovery, built around a shared Rust engine with planned Ubuntu and Android-to-Android (USB OTG) frontends.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Rust](https://img.shields.io/badge/Rust-1.80%2B-orange?logo=rust)](https://www.rust-lang.org/)
![Status: Phase 1](https://img.shields.io/badge/status-Phase%201%20foundation-orange)

> **Status: Phase 1 (foundation) only.** No real hardware has been tested. Nothing here can flash a device.
> There is no USB backend, no Firehose, no GUI, and no Android app yet. See [ROADMAP.md](ROADMAP.md).

## What exists today

| Component | State |
|---|---|
| `core/` Sahara packet codec + host state machine + session runner | Implemented; tested against synthetic fixtures and the simulator only |
| `core/` `Transport` / `UsbEnumerator` traits | Implemented (no real backend yet) |
| `core/` device/chipset/manufacturer/programmer schema + validated DB loader | Implemented |
| `core/` safety policy (read-only default, gated writes, protected partitions) + hash-chained audit log | Implemented |
| `simulator/` deterministic fake EDL target with fault injection | Implemented |
| `tools/edl-cli` (`validate-db`, `sim`, `sim-identify`, `scrcpy`) | Implemented. Database and simulator commands perform **no USB I/O**. The optional `scrcpy` command talks only to host `adb`/`scrcpy`; it never talks to EDL/9008 devices. |
| Firehose, GPT parsing, firmware import, backup, flashing | Not started |
| Ubuntu app, Android app | Not started |

## Principles

- Read-only by default; every write passes `security::authorize`.
- Support is a per-device capability level backed by recorded evidence. Simulator results are never evidence.
- No authentication bypasses, secure-boot circumvention, or programmer patching. Devices needing vendor-authorized programmers are reported as such, not attacked.

## Screenshots

| CLI `validate-db` | CLI `sim` fault injection | Future Ubuntu GUI | Future Android OTG |
|---|---|---|---|
| *TODO* | *TODO* | *Not started* | *Not started* |

## Requirements

- Rust **1.80+** (workspace `rust-version`)
- Cargo (workspace members: `core`, `simulator`, `tools/edl-cli`)

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
```

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
core/           edl-core — Sahara, device DB, security, diagnostics
simulator/      edl-sim — fake EDL target + fault injection
tools/edl-cli/  CLI (validate-db, sim, sim-identify, scrcpy)
database/       chipsets, devices, manufacturers, programmers
docs/           ANDROID, FLASHING, PROTOCOLS, TESTING, UBUNTU
ARCHITECTURE.md · COMPATIBILITY.md · SECURITY.md · ROADMAP.md · CONTRIBUTING.md
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Keep the safety model: no bypass tooling, no claiming hardware support without evidence. Prefer tests against the simulator over speculative USB code.


## Credits

scrcpy by Genymobile, Apache-2.0, https://github.com/Genymobile/scrcpy. It is not bundled. See [NOTICE](NOTICE).

## License

[MIT](LICENSE). Details and threat boundaries: [SECURITY.md](SECURITY.md), [ARCHITECTURE.md](ARCHITECTURE.md), [COMPATIBILITY.md](COMPATIBILITY.md).
