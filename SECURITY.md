# Security

- Report vulnerabilities privately to the repository owner (add a contact before first release).
- Defaults: read-only; no write without profile level ≥ `write_supported`, `authentication.required == no`, verified firmware, write mode enabled, plan digest confirmed, and protected-partition rules satisfied (`core/src/security/policy.rs`).
- Device input is untrusted: packets are length/size-checked, requests are range- and size-capped, sessions have a packet budget.
- Out of scope by policy: authentication bypass, secure-boot/anti-rollback circumvention, exploit-based access, patching vendor programmers.
- Privacy: nothing is uploaded; backups stay local.
- Protected-partition defaults are conservative name matches, not an exhaustive security classification.
