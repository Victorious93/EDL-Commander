# CLAUDE.md — guidance for AI/automation working in this repo

## What this is (verified 2026-10-15)
EDL Commander: a safety-first Rust framework for Qualcomm Emergency Download (EDL) device recovery. **Phase 1 (foundation) only**: Sahara codec/host state machine, transport traits, device database schema + validator, safety policy and hash-chained audit log, a deterministic simulator, and a CLI. No USB backend, no Firehose, no GUI/Android app, and no real hardware has been tested. See `README.md`, `ROADMAP.md`, `ARCHITECTURE.md`.

## Layout
- Cargo workspace (`Cargo.toml`, edition 2021, `rust-version = "1.80"`, MIT): members `core`, `simulator`, `tools/edl-cli`.
- `core/src/`: `protocol/sahara`, `transport.rs` (`Transport`), `usb.rs` (`UsbEnumerator` trait, no backend), `device/` (profile + database loader), `security/` (`policy.rs`, `authorize`), `diagnostics/audit.rs`. Integration test: `core/tests/database.rs`.
- `simulator/`: fake EDL target with fault injection; test `simulator/tests/sahara_e2e.rs`.
- `tools/edl-cli`: subcommands `validate-db <dir>` and `sim [none|silent|malformed|disconnect]`.
- `database/`: JSON profiles — `chipsets/sdm845.json`, `devices/oneplus-6.json`, `manufacturers/oneplus.json`; `programmers/` is empty.
- `docs/`: ANDROID, FLASHING, PROTOCOLS, TESTING, UBUNTU notes; plus `COMPATIBILITY.md`, `CONTRIBUTING.md`, `SECURITY.md` at root.

## Commands
- Build/test (same as CI): `cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo run -p edl-cli -- validate-db database`
- Simulator: `cargo run -p edl-cli -- sim [none|silent|malformed|disconnect]`

## CI
`.github/workflows/ci.yml` ("CI") runs on every push and pull_request: job `rust` (fmt, clippy `-D warnings`, tests, `validate-db`) and job `audit` (`cargo install cargo-audit --locked && cargo audit`). Latest runs on `main` passed (checked 2026-10-15).

## Rules
- Protocol code in `core/` must stay free of USB/UI dependencies; talk only through `transport::Transport`.
- Never add: auth/secure-boot bypasses, programmer patching, automatic flashing, guessed partition addresses or programmer compatibility, downloads of firmware from unverified sources.
- Never raise a device's `capability_level` without hardware `evidence` (the validator enforces this). Simulator runs are not evidence.
- Do not commit firmware, programmers or copyrighted vendor files; reference them by hash (CONTRIBUTING.md).
- Do not claim tests passed or builds succeeded unless run. Do not fabricate APKs/binaries.
- Third-party code: check licence compatibility with MIT first; do not copy GPL code.
- Pushing, PRs and any destructive hardware operation require explicit user approval.

## Don's standing rules (apply to every change)
- Work on a branch and open a PR. Never push to `main`.
- Never merge a PR, and never ask to merge one, until every test/check on the PR passes.
- Never commit secrets (API keys, tokens, keystore passwords, `.env` files, credentials).
- Don't claim something works unless it was actually tested; say what was and wasn't run.
