# Architecture

```
frontends (Ubuntu app, Android app, edl-cli)
        │ use
        ▼
edl-core ── protocol::sahara (packet, state, session)
        │── device (profiles, Database)      ── security (authorize)
        │── diagnostics (DiagLog, AuditLog)
        │ talks only through
        ▼
transport::Transport / usb::UsbEnumerator   ◄── implemented per platform
        ▲                                        (libusb on Ubuntu, Kotlin USB Host bridge on Android, edl-sim in tests)
```

Layout deviation from the original brief: protocol, device, security and diagnostics live as modules of one `edl-core` crate rather than separate directories, to keep Phase 1 small. `firmware/`, `partition/`, `recovery/` and `protocol/firehose` will be added in the phases that need them. Integration tests sit beside their crates (`simulator/tests`, `core/tests`); a top-level `tests/hardware/` will be added with the first hardware tests.

## Key decisions
- **Pure state machine** (`SaharaHost::handle`) with no I/O; `session::run_programmer_upload` is a thin loop. Any invalid (state, packet) pair → `Failed`, terminal.
- **Hostile-device assumptions**: strict length checks, chunk cap (1 MiB), range checks, packet budget.
- **Android transport (Phase 3 plan)**: Kotlin owns `UsbDeviceConnection`; Rust receives a callback-based `Transport` over JNI. Desktop libusb will not be assumed to work on Android.
- **Capability ladder** is data, not code: `CapabilityLevel` in profiles, validated against evidence.
- **Audit log** is hash-chained JSONL; the core takes timestamps from callers to stay deterministic.
- Ubuntu GUI toolkit (Tauri vs GTK4) is **not yet chosen**; evaluation is a Phase 2 task.
