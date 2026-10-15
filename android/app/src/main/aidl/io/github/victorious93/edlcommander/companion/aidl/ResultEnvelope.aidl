package io.github.victorious93.edlcommander.companion.aidl;

/** The companion's reply. `payloadJson` is a JSON object (never null). */
parcelable ResultEnvelope {
    const int STATUS_OK = 0;
    /** Nothing was executed against a device; the payload describes what would happen. */
    const int STATUS_DRY_RUN = 1;
    /** Command is known but not implemented in this build (e.g. native core not linked). */
    const int STATUS_UNSUPPORTED = 2;
    /** Command refused by policy (e.g. any write/flash command). */
    const int STATUS_DENIED = 3;
    /** Malformed envelope or arguments. */
    const int STATUS_INVALID = 4;
    const int STATUS_ERROR = 5;

    int schemaVersion = 1;
    String requestId;
    int status;
    /** Stable machine-readable code, e.g. "ok", "native_core_unavailable", "writes_not_available". */
    String code;
    /** Human-readable explanation; not for parsing. */
    String message;
    String payloadJson = "{}";
    /** True if the data came from a simulator or fixture. Simulated data is never hardware evidence. */
    boolean simulated;
    /** Echo of the dry-run mode the command was evaluated under. */
    boolean dryRun = true;
}
