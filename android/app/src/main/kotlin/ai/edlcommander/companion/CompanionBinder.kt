package ai.edlcommander.companion

import android.os.Binder
import ai.edlcommander.companion.command.CommandResult
import ai.edlcommander.companion.bridge.BridgeProtocol
import ai.edlcommander.companion.bridge.BridgeRequest
import ai.edlcommander.companion.bridge.RemoteDispatcher
import ai.edlcommander.companion.command.CommandRouter
import ai.edlcommander.companion.command.Status
import ai.edlcommander.companion.aidl.CommandEnvelope
import ai.edlcommander.companion.aidl.IEdlCompanion
import ai.edlcommander.companion.aidl.ResultEnvelope

class CompanionBinder(
    private val verifier: CallerVerifier,
    private val dispatcher: RemoteDispatcher,
    private val identity: CallerIdentityResolver,
) : IEdlCompanion.Stub() {

    override fun getProtocolVersion(): Int {
        enforceCaller()
        return IEdlCompanion.PROTOCOL_VERSION
    }

    override fun execute(command: CommandEnvelope?): ResultEnvelope {
        val uid = enforceCaller()
        if (command == null) {
            return toEnvelope(CommandResult("", Status.INVALID, "null_envelope", "No envelope supplied."))
        }
        if (command.bridgeProtocolMajor != 0) {
            // sourceNodeRef comes from the Binder calling uid, never from the envelope.
            val r = dispatcher.bridge(bridgeRequest(command), identity.nodeRef(uid))
            return toEnvelope(r.result).apply {
                bridgeProtocolMajor = BridgeProtocol.MAJOR
                bridgeProtocolMinor = BridgeProtocol.MINOR
                op = r.op
                errorCode = r.error?.wire.orEmpty()
                sourceNodeRef = r.sourceNodeRef
            }
        }
        return toEnvelope(
            dispatcher.legacy(
                schemaVersion = command.schemaVersion,
                requestId = command.requestId,
                name = command.command,
                argsJson = command.argsJson,
                dryRun = command.dryRun,
            ),
        )
    }

    private fun enforceCaller(): Int {
        val uid = Binder.getCallingUid()
        if (!verifier.isAllowed(uid)) {
            throw SecurityException("EDL-Commander companion: caller uid $uid is not signed with the fleet key")
        }
        return uid
    }

    companion object {
        fun bridgeRequest(c: CommandEnvelope) = BridgeRequest(
            major = c.bridgeProtocolMajor,
            minor = c.bridgeProtocolMinor,
            op = c.op,
            requestId = c.requestId,
            targetNodeRef = c.targetNodeRef,
            actionRef = c.actionRef,
            paramsJson = c.paramsJson,
            nonce = c.nonce,
            timestampMs = c.timestampMs,
            ttlMs = c.ttlMs,
            destructive = c.destructive,
            triggerJson = c.triggerJson,
            macroId = c.macroId,
            stepIndex = c.stepIndex,
            onError = c.onError,
            stepsJson = c.stepsJson,
            dryRun = c.dryRun,
        )

        fun toEnvelope(r: CommandResult): ResultEnvelope = ResultEnvelope().apply {
            schemaVersion = CommandRouter.SCHEMA_VERSION
            requestId = r.requestId
            status = r.status.wire
            code = r.code
            message = r.message
            payloadJson = r.payload.toString()
            simulated = r.simulated
            dryRun = r.dryRun
        }
    }
}
