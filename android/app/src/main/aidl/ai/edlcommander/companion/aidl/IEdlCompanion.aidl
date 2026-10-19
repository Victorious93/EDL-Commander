// EDL-Commander companion wire contract, version 1.
//
// Droidcommand-AI (the hub) copies this directory verbatim into its own source set. The package
// name `ai.edlcommander.companion.aidl` is part of the contract and must not
// be changed on either side. Only additive changes are allowed within a protocol version;
// anything else bumps PROTOCOL_VERSION.
//
// Callers must be signed with the same key as the companion (signature-level permission
// `ai.edlcommander.companion.permission.BIND_COMPANION`). Every call is additionally
// re-checked in the service. See docs/ANDROID.md.
package ai.edlcommander.companion.aidl;

import ai.edlcommander.companion.aidl.CommandEnvelope;
import ai.edlcommander.companion.aidl.ResultEnvelope;

interface IEdlCompanion {
    const int PROTOCOL_VERSION = 1;

    /** Cheap handshake: returns PROTOCOL_VERSION of the companion. */
    int getProtocolVersion();

    /**
     * Single entry point. Never throws for bad input; failures are reported in the
     * ResultEnvelope status/code. Standard commands: companion.capabilities,
     * companion.status, usb.list, sahara.identify (dry-run only in this version).
     */
    ResultEnvelope execute(in CommandEnvelope command);
}
