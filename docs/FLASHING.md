# Flashing

Not implemented. The safety gate that any future flashing must pass already exists: `security::authorize` (see SECURITY.md). Destructive commands with unknown completion state must never be auto-retried. No rollback is promised.
