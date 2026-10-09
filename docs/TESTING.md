# Testing

- Unit tests: `cargo test --workspace` (packet codec, state machine, policy, audit chain, DB validation).
- Simulator end-to-end: `simulator/tests/sahara_e2e.rs` (malformed, silent, disconnect, out-of-range, out-of-order, rejection, budget).
- Database: `core/tests/database.rs` and `edl-cli validate-db`.
- Hardware tests: none exist. They must live apart from simulated tests and be the only source of `hardware: true` evidence.
- Simulator success never implies hardware compatibility.
