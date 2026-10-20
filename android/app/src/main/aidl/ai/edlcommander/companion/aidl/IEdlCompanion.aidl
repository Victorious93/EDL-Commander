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
    /** Frozen fleet remote-control envelope version carried in CommandEnvelope (v1.1). */
    const int BRIDGE_PROTOCOL_MAJOR = 1;
    const int BRIDGE_PROTOCOL_MINOR = 1;

    /** Cheap handshake: returns PROTOCOL_VERSION of the companion. */
    int getProtocolVersion();

    /**
     * Single entry point. Never throws for bad input; failures are reported in the
     * ResultEnvelope status/code. Standard commands: companion.capabilities,
     * companion.status, usb.list, usb.request_permission, sahara.identify (dryRun=true returns a
     * plan; dryRun=false runs a live read-only Sahara identify, which needs a user-granted USB
     * permission). Nothing can write, flash or erase.
     *
     * Remote control is OFF by default: until the user enables it in the app, every call except
     * a bridge capabilities.get (or legacy companion.capabilities) returns
     * external_control_disabled. Set bridgeProtocolMajor=1 to use the bridge envelope.
     */
    ResultEnvelope execute(in CommandEnvelope command);
}
