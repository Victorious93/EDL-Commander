package io.github.victorious93.edlcommander.command

import org.json.JSONObject

data class Command(
    val requestId: String,
    val name: String,
    val args: JSONObject,
    val dryRun: Boolean,
)

enum class Status(val wire: Int) {
    OK(0),
    DRY_RUN(1),
    UNSUPPORTED(2),
    DENIED(3),
    INVALID(4),
    ERROR(5),
}

data class CommandResult(
    val requestId: String,
    val status: Status,
    val code: String,
    val message: String,
    val payload: JSONObject = JSONObject(),
    val simulated: Boolean = false,
    val dryRun: Boolean = true,
)

enum class Risk { READ_ONLY, DEVICE_SESSION }

data class CommandSpec(
    val name: String,
    val risk: Risk,
    /** False when the command only yields a plan / "unsupported" in this build. */
    val live: Boolean,
    val summary: String,
)
