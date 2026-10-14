package io.github.victorious93.edlcommander.companion.aidl;

/** A request from the hub. `argsJson` is a JSON object (use "{}" for none). */
parcelable CommandEnvelope {
    int schemaVersion = 1;
    /** Caller-chosen correlation id, echoed back in the result. */
    String requestId;
    /** Dotted command name, e.g. "usb.list". */
    String command;
    String argsJson = "{}";
    /**
     * Defaults to true. Commands that would open a device or change state only ever return a
     * plan when this is true. In this version no command can write to a device regardless of
     * this flag.
     */
    boolean dryRun = true;
}
