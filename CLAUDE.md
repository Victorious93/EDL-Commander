# CLAUDE.md — guidance for AI/automation working in this repo

- Build/test: `cargo fmt --all --check && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo run -p edl-cli -- validate-db database`
- Protocol code in `core/` must stay free of USB/UI dependencies; talk only through `transport::Transport`.
- Never add: auth/secure-boot bypasses, programmer patching, automatic flashing, guessed partition addresses or programmer compatibility, downloads of firmware from unverified sources.
- Never raise a device's `capability_level` without hardware `evidence` (the validator enforces this). Simulator runs are not evidence.
- Do not claim tests passed or builds succeeded unless run. Do not fabricate APKs/binaries.
- Third-party code: check licence compatibility with MIT first; do not copy GPL code.
- Pushing, PRs and any destructive hardware operation require explicit user approval.
