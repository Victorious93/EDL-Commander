# EDL Commander

Open-source, safety-first framework for Qualcomm Emergency Download (EDL) device recovery, built around a shared Rust engine with planned Ubuntu and Android-to-Android (USB OTG) frontends.

> **Status: Phase 1 (foundation) only.** No real hardware has been tested. Nothing here can flash a device.
> There is no USB backend, no Firehose, no GUI and no Android app yet. See [ROADMAP.md](ROADMAP.md).

## What exists today

| Component | State |
|---|---|
| `core/` Sahara packet codec + explicit host state machine + session runner | Implemented, tested against synthetic fixtures and the simulator only |
| `core/` `Transport` / `UsbEnumerator` traits | Implemented (no real backend yet) |
| `core/` device/chipset/manufacturer/programmer schema + validated database loader | Implemented |
| `core/` safety policy (read-only default, gated writes, protected partitions) + hash-chained audit log | Implemented |
| `simulator/` deterministic fake EDL target with fault injection | Implemented |
| `tools/edl-cli` (`validate-db`, `sim`) | Implemented; performs no USB I/O |
| Firehose, GPT parsing, firmware import, backup, flashing | Not started |
| Ubuntu app, Android app | Not started |

## Build and test

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo run -p edl-cli -- validate-db database
cargo run -p edl-cli -- sim [none|silent|malformed|disconnect]
```

## Principles

- Read-only by default; every write passes `security::authorize`.
- Support is a per-device capability level backed by recorded evidence. Simulator results are never evidence.
- No authentication bypasses, secure-boot circumvention or programmer patching. Devices needing vendor-authorized programmers are reported as such, not attacked.

License: MIT. See [ARCHITECTURE.md](ARCHITECTURE.md), [COMPATIBILITY.md](COMPATIBILITY.md), [SECURITY.md](SECURITY.md).
