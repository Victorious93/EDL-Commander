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
- **Android transport**: Kotlin owns `UsbDeviceConnection`; Rust (`android/native/edl-jni`) receives a callback-based `Transport` over JNI, so `edl-core` stays free of USB and JNI. Desktop libusb is not assumed to work on Android. Implemented and tested against the simulator and a host JVM; unverified on devices.
- **Android control plane**: the Android app is a bound companion for Droidcommand-AI (AIDL `IEdlCompanion`, JSON command envelopes, signature-level permission). The command router sits above the safety model: write-class commands are refused, `dryRun` defaults to true, and every non-dry-run command is audit-logged (fail closed). The only device-touching command is a read-only Sahara identify that needs user-granted USB permission. See docs/ANDROID.md.
- **Capability ladder** is data, not code: `CapabilityLevel` in profiles, validated against evidence.
- **Audit log** is hash-chained JSONL; the core takes timestamps from callers to stay deterministic.
- Ubuntu GUI toolkit (Tauri vs GTK4) is **not yet chosen**; evaluation is a Phase 2 task.
