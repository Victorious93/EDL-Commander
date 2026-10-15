package io.github.victorious93.edlcommander.companion

import android.os.Binder
import io.github.victorious93.edlcommander.command.CommandResult
import io.github.victorious93.edlcommander.command.CommandRouter
import io.github.victorious93.edlcommander.command.Status
import io.github.victorious93.edlcommander.companion.aidl.CommandEnvelope
import io.github.victorious93.edlcommander.companion.aidl.IEdlCompanion
import io.github.victorious93.edlcommander.companion.aidl.ResultEnvelope

class CompanionBinder(
    private val verifier: CallerVerifier,
    private val router: CommandRouter,
) : IEdlCompanion.Stub() {

    override fun getProtocolVersion(): Int {
        enforceCaller()
        return IEdlCompanion.PROTOCOL_VERSION
    }

    override fun execute(command: CommandEnvelope?): ResultEnvelope {
        enforceCaller()
        if (command == null) {
            return toEnvelope(CommandResult("", Status.INVALID, "null_envelope", "No envelope supplied."))
        }
        return toEnvelope(
            router.handleRaw(
                schemaVersion = command.schemaVersion,
                requestId = command.requestId,
                name = command.command,
                argsJson = command.argsJson,
                dryRun = command.dryRun,
            ),
        )
    }

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (!verifier.isAllowed(uid)) {
            throw SecurityException("EDL-Commander companion: caller uid $uid is not signed with the fleet key")
        }
    }

    companion object {
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
