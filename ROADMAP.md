# Roadmap

Status key: ✅ done and tested (simulated) · ⏳ not started

## Phase 1 — Foundation
- ✅ Rust workspace, transport/USB traits, Sahara codec/state machine, safety policy, audit log, profile schema + validator, simulator, CI
- ⏳ Not in Phase 1 by design: firmware/partition/recovery modules (Phases 4–6)
- Hardware validation: none performed.

## Phase 2 — Ubuntu detection + Sahara (libusb backend, command-mode device identification, GUI toolkit decision)
## Phase 3 — Android companion: bound APK for Droidcommand-AI (AIDL, same signing) + USB Host bridge + JNI
- ✅ (scaffold) `android/` Gradle/Compose app builds a debug APK; AIDL `IEdlCompanion` command-envelope service stub; USB 9008 list/detect only; CI assembles the debug APK
- ⏳ Pin the Droidcommand-AI hub package and align `applicationId`/AIDL package with fleet naming
- ⏳ User-initiated USB permission + open/claim of the 9008 interface
- ⏳ JNI crate + `cargo ndk` build; callback `Transport` into `edl-core` Sahara (needs Android NDK); emulator tests against `edl-sim`
- ⏳ Droidcommand-AI side of the binding (lives in that repo)
- Hardware validation: none performed.
## Phase 4 — Firehose, GPT, OnePlus profiles (needs authorized hardware)
- ⏳ OnePlus 7T HD1905 (SDM855+) is the first planned real-device target; a research-only profile (`unknown`, no evidence) exists. Read-only Sahara identification precedes any programmer or write work
## Phase 5 — Firmware parsing, hashing, backup
## Phase 6 — Recovery automation
## Phase 7 — Manufacturer expansion
## Phase 8 — Release hardening
