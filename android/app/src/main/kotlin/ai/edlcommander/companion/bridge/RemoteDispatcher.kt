package ai.edlcommander.companion.bridge

import ai.edlcommander.companion.command.CommandResult
import ai.edlcommander.companion.command.CommandRouter
import ai.edlcommander.companion.command.Status

/**
 * Everything a remote (Binder) caller can reach goes through here. The on-device UI talks to
 * [CommandRouter] directly and is not subject to the remote-control switch.
 */
class RemoteDispatcher(
    private val router: CommandRouter,
    private val remoteControl: RemoteControlSetting,
    nodeStatus: NodeStatusProvider? = null,
    val gate: BridgeGate = BridgeGate(router, remoteControl, nodeStatus = nodeStatus),
) {
    fun bridge(req: BridgeRequest, sourceNodeRef: String?): BridgeResult = gate.handle(req, sourceNodeRef)

    /**
     * Legacy (bridgeProtocolMajor == 0) path. Kept for backward compatibility, but now behind the
     * same local switch: while remote control is off only `companion.capabilities` answers.
     */
    fun legacy(schemaVersion: Int, requestId: String?, name: String?, argsJson: String?, dryRun: Boolean): CommandResult {
        if (!remoteControl.isEnabled() && name != LEGACY_CAPABILITIES) {
            return CommandResult(
                requestId.orEmpty().take(128), Status.DENIED, BridgeError.EXTERNAL_CONTROL_DISABLED.wire,
                "Remote control is off. The user must enable it in the EDL Commander app on this device.",
                dryRun = dryRun,
            )
        }
        return router.handleRaw(schemaVersion, requestId, name, argsJson, dryRun)
    }

    companion object {
        const val LEGACY_CAPABILITIES = "companion.capabilities"
    }
}
