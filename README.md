<p align="center">
  <img src="docs/logo-512.png" alt="EDL Commander logo" width="200">
</p>

# EDL Commander

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
| `tools/edl-cli` (`validate-db`, `sim`) | Implemented; **no USB I/O** |
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
```

## Project structure

```text
core/           edl-core — Sahara, device DB, security, diagnostics
simulator/      edl-sim — fake EDL target + fault injection
tools/edl-cli/  CLI (validate-db, sim)
database/       chipsets, devices, manufacturers, programmers
docs/           ANDROID, FLASHING, PROTOCOLS, TESTING, UBUNTU
ARCHITECTURE.md · COMPATIBILITY.md · SECURITY.md · ROADMAP.md · CONTRIBUTING.md
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Keep the safety model: no bypass tooling, no claiming hardware support without evidence. Prefer tests against the simulator over speculative USB code.

## License

[MIT](LICENSE). Details and threat boundaries: [SECURITY.md](SECURITY.md), [ARCHITECTURE.md](ARCHITECTURE.md), [COMPATIBILITY.md](COMPATIBILITY.md).
