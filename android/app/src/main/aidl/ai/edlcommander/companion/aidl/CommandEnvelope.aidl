package ai.edlcommander.companion.aidl;

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

    // ---- Bridge envelope v1.1 (frozen fleet remote-control schema). Appended fields only. ----
    // bridgeProtocolMajor == 0 means a legacy (pre-bridge) caller; the fields below are ignored
    // and the legacy `command`/`argsJson` path is used. 1 selects the bridge path, in which
    // `command`/`argsJson` are ignored. See docs/BRIDGE_PROTOCOL.md.
    int bridgeProtocolMajor = 0;
    int bridgeProtocolMinor = 0;
    /** capabilities.get | remote.command | macro.step | macro.run */
    String op;
    String targetNodeRef;
    /** Dotted action name, e.g. "usb.list" (remote.command, macro.step). */
    String actionRef;
    /** JSON object (the frozen `params`). */
    String paramsJson = "{}";
    /** 128-bit nonce as 32 hex characters. */
    String nonce;
    /** Sender clock, epoch milliseconds. */
    long timestampMs = 0;
    /** 0 = default 120000; max 300000. */
    long ttlMs = 120000;
    /** Advisory only: the receiver re-classifies and only ever raises it. */
    boolean destructive = false;
    /** JSON object, informational only. */
    String triggerJson = "{}";
    /** macro.step / macro.run */
    String macroId;
    /** macro.step: zero-based step index (-1 = unset). */
    int stepIndex = -1;
    /** macro.step: "abort" (default) or "continue". */
    String onError;
    /** macro.run: JSON array of {actionRef, params, onError?}. macro.run uses `dryRun` above. */
    String stepsJson = "[]";
}
