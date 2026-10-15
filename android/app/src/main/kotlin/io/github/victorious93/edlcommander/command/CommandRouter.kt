package io.github.victorious93.edlcommander.command

import io.github.victorious93.edlcommander.nativecore.NativeCore
import io.github.victorious93.edlcommander.usb.UsbDeviceSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure-Kotlin dispatcher behind the AIDL service and the on-device UI. No Android framework
 * types, so it runs in JVM unit tests with fake sources.
 *
 * Policy in this build: nothing here can write to a device. Write-class command names are
 * refused with `writes_not_available` before any lookup, and no handler opens a device.
 */
class CommandRouter(
    private val usb: UsbDeviceSource,
    private val nativeCore: NativeCore,
    private val appVersion: String,
) {
    fun handleRaw(
        schemaVersion: Int,
        requestId: String?,
        name: String?,
        argsJson: String?,
        dryRun: Boolean,
    ): CommandResult {
        val id = requestId.orEmpty().take(MAX_REQUEST_ID)
        fun invalid(code: String, msg: String) = CommandResult(id, Status.INVALID, code, msg, dryRun = dryRun)

        if (schemaVersion != SCHEMA_VERSION) {
            return invalid("unsupported_schema_version", "schemaVersion $schemaVersion; expected $SCHEMA_VERSION")
        }
        val commandName = name.orEmpty()
        if (!NAME_PATTERN.matches(commandName)) {
            return invalid("invalid_command_name", "command must match ${NAME_PATTERN.pattern}")
        }
        val raw = argsJson?.takeIf { it.isNotBlank() } ?: "{}"
        if (raw.length > MAX_ARGS_CHARS) {
            return invalid("args_too_large", "argsJson exceeds $MAX_ARGS_CHARS characters")
        }
        val args = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return invalid("invalid_args", "argsJson is not a JSON object")
        }
        return handle(Command(id, commandName, args, dryRun))
    }

    fun handle(cmd: Command): CommandResult {
        val family = cmd.name.substringBefore('.')
        if (family in DENIED_FAMILIES) {
            return result(
                cmd, Status.DENIED, "writes_not_available",
                "Write-class commands do not exist in this build; any future write path must pass edl-core security::authorize.",
            )
        }
        return when (cmd.name) {
            "companion.capabilities" -> capabilities(cmd)
            "companion.status" -> status(cmd)
            "usb.list" -> usbList(cmd)
            "sahara.identify" -> saharaIdentify(cmd)
            else -> result(cmd, Status.UNSUPPORTED, "unknown_command", "Unknown command '${cmd.name}'.")
        }
    }

    private fun result(
        cmd: Command,
        status: Status,
        code: String,
        message: String,
        payload: JSONObject = JSONObject(),
        simulated: Boolean = false,
    ) = CommandResult(cmd.requestId, status, code, message, payload, simulated, cmd.dryRun)

    private fun capabilities(cmd: Command): CommandResult {
        val commands = JSONArray()
        CATALOG.forEach {
            commands.put(
                JSONObject()
                    .put("name", it.name)
                    .put("risk", it.risk.name.lowercase())
                    .put("live", it.live)
                    .put("summary", it.summary),
            )
        }
        val payload = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("appVersion", appVersion)
            .put("commands", commands)
            .put("plannedCommands", JSONArray(PLANNED))
            .put("features", FEATURES.fold(JSONObject()) { acc, (k, v) -> acc.put(k, v) })
            .put("nativeCoreLinked", nativeCore.isLinked)
            .put("verifiedDevices", JSONArray())
        return result(cmd, Status.OK, "ok", "Capabilities of the EDL-Commander companion scaffold.", payload)
    }

    private fun status(cmd: Command): CommandResult {
        val payload = JSONObject()
            .put("phase", "scaffold")
            .put("writeMode", "unavailable")
            .put("nativeCoreLinked", nativeCore.isLinked)
            .put("nativeCoreVersion", nativeCore.version ?: JSONObject.NULL)
            .put("hardwareValidated", false)
        return result(cmd, Status.OK, "ok", "Scaffold only: no hardware support is claimed.", payload)
    }

    private fun usbList(cmd: Command): CommandResult {
        val edlOnly = cmd.args.optBoolean("edl_only", false)
        val snapshots = try {
            usb.list()
        } catch (e: RuntimeException) {
            return result(cmd, Status.ERROR, "usb_enumeration_failed", e.javaClass.simpleName)
        }
        val shown = if (edlOnly) snapshots.filter { it.isQdLoader9008 } else snapshots
        val devices = JSONArray()
        shown.forEach {
            devices.put(
                JSONObject()
                    .put("vendorId", it.vendorId)
                    .put("productId", it.productId)
                    .put("deviceName", it.deviceName)
                    .put("qdloader9008", it.isQdLoader9008),
            )
        }
        val payload = JSONObject()
            .put("devices", devices)
            .put("edlCandidates", snapshots.count { it.isQdLoader9008 })
            .put("note", "05c6:9008 is a USB-level hint only; it is not a compatibility or capability claim.")
        return result(cmd, Status.OK, "ok", "${shown.size} USB device(s) listed (enumeration only, nothing opened).", payload)
    }

    private fun saharaIdentify(cmd: Command): CommandResult {
        if (cmd.dryRun) {
            val plan = JSONArray()
                .put("Require a user-granted USB permission for the 05c6:9008 interface.")
                .put("Open the UsbDeviceConnection in Kotlin.")
                .put("Run the edl-core Sahara host over a JNI callback Transport and read the device HELLO.")
                .put("Stop. No programmer upload, no Firehose, no writes.")
            val payload = JSONObject().put("plan", plan).put("nativeCoreLinked", nativeCore.isLinked)
            return result(cmd, Status.DRY_RUN, "dry_run", "Plan only; nothing was sent to any device.", payload)
        }
        val code = if (nativeCore.isLinked) "not_implemented" else "native_core_unavailable"
        return result(
            cmd, Status.UNSUPPORTED, code,
            "Live Sahara identification is not available in this build. Use dryRun=true to see the plan.",
        )
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val PROTOCOL_VERSION = 1
        private const val MAX_REQUEST_ID = 128
        private const val MAX_ARGS_CHARS = 64 * 1024
        private val NAME_PATTERN = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){0,3}")

        /** First name segments that are refused outright (write / destructive / bypass-adjacent). */
        private val DENIED_FAMILIES = setOf("flash", "firehose", "write", "erase", "format", "unlock")

        val CATALOG = listOf(
            CommandSpec("companion.capabilities", Risk.READ_ONLY, true, "Describe commands and features."),
            CommandSpec("companion.status", Risk.READ_ONLY, true, "Scaffold / native core status."),
            CommandSpec("usb.list", Risk.READ_ONLY, true, "List attached USB devices (enumeration only); arg edl_only:boolean."),
            CommandSpec("sahara.identify", Risk.DEVICE_SESSION, false, "Dry-run plan only; live path needs JNI + USB permission."),
        )

        val PLANNED = listOf("sahara.session", "device.profile", "sim.sahara", "backup.read")

        private val FEATURES = listOf(
            "usbHostEnumeration" to true,
            "usbHostOpen" to false,
            "saharaNative" to false,
            "firehose" to false,
            "flashing" to false,
        )
    }
}
