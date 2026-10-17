# Roadmap

Status key: ✅ done and tested (simulated) · ⏳ not started

## Phase 1 — Foundation
- ✅ Rust workspace, transport/USB traits, Sahara codec/state machine, safety policy, audit log, profile schema + validator, simulator, CI
- ⏳ Not in Phase 1 by design: firmware/partition/recovery modules (Phases 4–6)
- Hardware validation: none performed.

## Phase 2 — Ubuntu detection + Sahara (libusb backend, command-mode device identification, GUI toolkit decision)
- Note: the core's read-only command-mode identify (`run_identify`) now exists and is shared with Android; it is simulator-tested only. The libusb backend, Ubuntu app and toolkit decision are not started.
## Phase 3 — Android companion: bound APK for Droidcommand-AI (AIDL, same signing) + USB Host bridge + JNI — **in progress**
- ✅ (scaffold) `android/` Gradle/Compose app builds a debug APK; AIDL `IEdlCompanion` command-envelope service; USB 9008 list/detect
- ✅ (written, unverified on devices) User-initiated USB permission flow (`usb.request_permission`, system dialog only), open/claim of the 9008 bulk interface, fail-closed audit log
- ✅ (simulated/host only) `edl-jni` crate + cargo-ndk build packaged into the APK (arm64-v8a, armeabi-v7a, x86_64); callback `Transport` into `edl-core`; read-only Sahara command-mode identify (serial, HW ID, OEM PK hash) wired to `sahara.identify` with `dryRun=false`; tests against `edl-sim` and a Kotlin Sahara test double on a host JVM
- ⏳ Run on an Android emulator and on a real device; capture real Sahara traffic and add capture-based tests
- ⏳ Pin the Droidcommand-AI hub package and align `applicationId`/AIDL package with fleet naming
- ⏳ Droidcommand-AI side of the binding (lives in that repo)
- Out of scope for this phase: programmer upload, Firehose, any write path
- Hardware validation: none performed.
## Phase 4 — Firehose, GPT, OnePlus profiles (needs authorized hardware)
- ⏳ OnePlus 7T HD1905 (SDM855+) is the first planned real-device target; a research-only profile (`unknown`, no evidence) exists. The read-only identify path now exists in code but has never run on a device; it precedes any programmer or write work, and the profile stays `unknown` until real evidence is recorded
## Phase 5 — Firmware parsing, hashing, backup
## Phase 6 — Recovery automation
## Phase 7 — Manufacturer expansion
## Phase 8 — Release hardening
