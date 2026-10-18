package ai.edlcommander.companion.command

import ai.edlcommander.companion.audit.AuditTrail
import ai.edlcommander.companion.nativecore.NativeCore
import ai.edlcommander.companion.usb.EdlSessionOpener
import ai.edlcommander.companion.usb.NoPermissionGate
import ai.edlcommander.companion.usb.NoSessionOpener
import ai.edlcommander.companion.usb.OpenOutcome
import ai.edlcommander.companion.usb.PermissionState
import ai.edlcommander.companion.usb.RequestOutcome
import ai.edlcommander.companion.usb.UsbDeviceSnapshot
import ai.edlcommander.companion.usb.UsbDeviceSource
import ai.edlcommander.companion.usb.UsbPermissionGate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure-Kotlin dispatcher behind the AIDL service and the on-device UI. No Android framework
 * types, so it runs in JVM unit tests with fake sources.
 *
 * Policy in this build: nothing here can write to a device. Write-class command names are
 * refused with `writes_not_available` before any lookup. The only handler that opens a device is
 * `sahara.identify` with `dryRun=false`, which needs a user-granted USB permission, passes
 * `security::authorize` (in the native core), is audit-logged, and speaks read-only Sahara
 * command mode only: no programmer upload, no Firehose.
 *
 * Every command with `dryRun=false` that reaches a handler is audited; if the audit log cannot be
 * recorded the command does not run.
 */
class CommandRouter(
    private val usb: UsbDeviceSource,
    private val nativeCore: NativeCore,
    private val appVersion: String,
    private val audit: AuditTrail,
    private val permissions: UsbPermissionGate = NoPermissionGate,
    private val sessions: EdlSessionOpener = NoSessionOpener,
) {
    private val deviceLock = Any()

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
            if (!cmd.dryRun && audit.available) audit.recordDeniedWrite(cmd.name)
            return result(
                cmd, Status.DENIED, "writes_not_available",
                "Write-class commands do not exist in this build; any future write path must pass edl-core security::authorize.",
            )
        }
        return when (cmd.name) {
            "companion.capabilities" -> capabilities(cmd)
            "companion.status" -> status(cmd)
            "usb.list" -> usbList(cmd)
            "usb.request_permission" -> usbRequestPermission(cmd)
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
        catalog().forEach {
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
            .put("features", features())
            .put("nativeCoreLinked", nativeCore.isLinked)
            .put("verifiedDevices", JSONArray())
        return result(cmd, Status.OK, "ok", "Capabilities of the EDL-Commander companion (Phase 3, unverified on hardware).", payload)
    }

    private fun status(cmd: Command): CommandResult {
        val payload = JSONObject()
            .put("phase", "phase3_in_progress")
            .put("writeMode", "unavailable")
            .put("nativeCoreLinked", nativeCore.isLinked)
            .put("nativeCoreVersion", nativeCore.version ?: JSONObject.NULL)
            .put("simulatorAvailable", nativeCore.simulatorAvailable)
            .put("hardwareValidated", false)
        return result(cmd, Status.OK, "ok", "Phase 3 in progress: nothing has been run on real hardware; no device is supported.", payload)
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
                    .put("qdloader9008", it.isQdLoader9008)
                    .put("permission", if (it.isQdLoader9008) permissions.state(it.deviceName).wire() else "not_applicable"),
            )
        }
        val payload = JSONObject()
            .put("devices", devices)
            .put("edlCandidates", snapshots.count { it.isQdLoader9008 })
            .put("note", "05c6:9008 is a USB-level hint only; it is not a compatibility or capability claim.")
        return result(cmd, Status.OK, "ok", "${shown.size} USB device(s) listed (enumeration only, nothing opened).", payload)
    }

    private sealed interface Target {
        class Found(val device: UsbDeviceSnapshot) : Target
        class Rejected(val result: CommandResult) : Target
    }

    /** The single 05c6:9008 device, or the one named by `device_name` (which must also be 9008). */
    private fun resolveTarget(cmd: Command): Target {
        val snapshots = try {
            usb.list()
        } catch (e: RuntimeException) {
            return Target.Rejected(result(cmd, Status.ERROR, "usb_enumeration_failed", e.javaClass.simpleName))
        }
        val named = cmd.args.optString("device_name", "").takeIf { it.isNotEmpty() }
        if (named != null) {
            val device = snapshots.firstOrNull { it.deviceName == named }
                ?: return Target.Rejected(result(cmd, Status.ERROR, "device_not_found", "No attached USB device named '$named'."))
            if (!device.isQdLoader9008) {
                return Target.Rejected(result(cmd, Status.DENIED, "not_an_edl_candidate", "Only 05c6:9008 devices are used."))
            }
            return Target.Found(device)
        }
        val candidates = snapshots.filter { it.isQdLoader9008 }
        return when (candidates.size) {
            0 -> Target.Rejected(result(cmd, Status.ERROR, "no_edl_device", "No 05c6:9008 device is attached."))
            1 -> Target.Found(candidates.single())
            else -> Target.Rejected(
                result(cmd, Status.INVALID, "ambiguous_device", "Several 05c6:9008 devices are attached; pass device_name."),
            )
        }
    }

    private fun auditFailure(cmd: Command, r: AuditTrail.Result): CommandResult =
        result(cmd, Status.ERROR, "audit_unavailable", "The audit log could not be recorded (${r.code}); refusing to continue.")

    private fun usbRequestPermission(cmd: Command): CommandResult {
        if (cmd.dryRun) {
            val plan = JSONArray()
                .put("Pick the single attached 05c6:9008 device (or the one named by device_name).")
                .put("Ask Android to show its USB permission dialog on this device.")
                .put("The user approves or denies on the dialog; nothing is granted silently and nothing is opened.")
            return result(cmd, Status.DRY_RUN, "dry_run", "Plan only; no prompt was shown.", JSONObject().put("plan", plan))
        }
        if (!permissions.supported) {
            return result(cmd, Status.UNSUPPORTED, "usb_host_unavailable", "USB host is not available on this device.")
        }
        if (!audit.available) {
            return result(cmd, Status.UNSUPPORTED, "native_core_unavailable", "Audit logging needs the native core; refusing.")
        }
        val device = when (val t = resolveTarget(cmd)) {
            is Target.Rejected -> return t.result
            is Target.Found -> t.device
        }
        val logged = audit.append("usb.request_permission", "requested", "device=${device.deviceName}")
        if (!logged.ok || !logged.persisted) return auditFailure(cmd, logged)
        val payload = JSONObject().put("deviceName", device.deviceName)
        return when (permissions.request(device.deviceName)) {
            RequestOutcome.ALREADY_GRANTED ->
                result(cmd, Status.OK, "permission_already_granted", "USB permission is already granted.", payload.put("permission", "granted"))
            RequestOutcome.PROMPTED ->
                result(
                    cmd, Status.OK, "permission_prompt_shown",
                    "Waiting for the user to answer the USB permission dialog on the device. Poll usb.list for the outcome.",
                    payload.put("permission", "requested"),
                )
            RequestOutcome.DEVICE_GONE -> result(cmd, Status.ERROR, "device_gone", "The device is no longer attached.")
            RequestOutcome.REFUSED -> result(cmd, Status.DENIED, "not_an_edl_candidate", "USB permission can only be requested for a 05c6:9008 device.")
        }
    }

    private fun saharaIdentify(cmd: Command): CommandResult {
        if (cmd.dryRun) {
            val plan = JSONArray()
                .put("Require a user-granted USB permission for the 05c6:9008 interface (command usb.request_permission).")
                .put("Pass security::authorize (IdentifyDevice) and write an audit record.")
                .put("Open the UsbDeviceConnection in Kotlin and claim the bulk interface.")
                .put("Run the edl-core Sahara host over a JNI callback Transport in command mode: read serial, hardware ID and OEM PK hash.")
                .put("Stop. No programmer upload, no Firehose, no writes.")
            val payload = JSONObject()
                .put("plan", plan)
                .put("nativeCoreLinked", nativeCore.isLinked)
                .put("simulatorAvailable", nativeCore.simulatorAvailable)
            return result(cmd, Status.DRY_RUN, "dry_run", "Plan only; nothing was sent to any device.", payload)
        }
        if (!nativeCore.isLinked) {
            return result(
                cmd, Status.UNSUPPORTED, "native_core_unavailable",
                "The native core is not loaded, so Sahara identification and audit logging are unavailable.",
            )
        }
        return if (cmd.args.optBoolean("simulate", false)) identifySimulated(cmd) else identifyDevice(cmd)
    }

    private fun identifySimulated(cmd: Command): CommandResult {
        if (!nativeCore.simulatorAvailable) {
            return result(cmd, Status.UNSUPPORTED, "simulator_not_built", "This build does not include the simulated target.")
        }
        val auth = audit.authorizeIdentify()
        if (!auth.ok) return if (auth.code == "denied") denied(cmd, auth) else auditFailure(cmd, auth)
        if (!auth.persisted) return auditFailure(cmd, auth)
        val run = audit.identifySimulated()
        return fromNative(cmd, run, simulated = true, extra = JSONObject().put("device", "simulator"))
    }

    private fun identifyDevice(cmd: Command): CommandResult {
        if (!sessions.supported) {
            return result(cmd, Status.UNSUPPORTED, "usb_host_unavailable", "USB host is not available on this device.")
        }
        val device = when (val t = resolveTarget(cmd)) {
            is Target.Rejected -> return t.result
            is Target.Found -> t.device
        }
        val timeoutMs = cmd.args.optInt("timeout_ms", DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        return synchronized(deviceLock) {
            val permission = permissions.state(device.deviceName)
            if (permission != PermissionState.GRANTED) {
                val logged = audit.append("sahara.identify", "denied", "usb_permission_not_granted; state=${permission.wire()}")
                if (!logged.ok || !logged.persisted) return@synchronized auditFailure(cmd, logged)
                return@synchronized result(
                    cmd, Status.DENIED, "usb_permission_required",
                    "The user has not granted USB permission for this device (state: ${permission.wire()}). Run usb.request_permission with dryRun=false first.",
                    JSONObject().put("permission", permission.wire()).put("deviceName", device.deviceName),
                )
            }
            val auth = audit.authorizeIdentify()
            if (!auth.ok) return@synchronized if (auth.code == "denied") denied(cmd, auth) else auditFailure(cmd, auth)
            if (!auth.persisted) return@synchronized auditFailure(cmd, auth)

            val opened = try {
                sessions.open(device.deviceName)
            } catch (e: RuntimeException) {
                OpenOutcome.Failed("open_failed", e.javaClass.simpleName)
            }
            when (opened) {
                is OpenOutcome.Failed -> {
                    audit.append("sahara.identify", "error", "code=${opened.code}")
                    result(cmd, Status.ERROR, opened.code, opened.message, JSONObject().put("deviceName", device.deviceName))
                }
                is OpenOutcome.Opened -> opened.session.use { session ->
                    val run = audit.identify(session.transport, timeoutMs)
                    fromNative(
                        cmd, run, simulated = false,
                        extra = JSONObject().put("device", JSONObject().put("vendorId", device.vendorId).put("productId", device.productId).put("deviceName", device.deviceName)),
                    )
                }
            }
        }
    }

    private fun denied(cmd: Command, r: AuditTrail.Result) =
        result(cmd, Status.DENIED, "denied", r.message.ifEmpty { "Refused by security::authorize." })

    private fun fromNative(cmd: Command, run: AuditTrail.Result, simulated: Boolean, extra: JSONObject): CommandResult {
        val payload = JSONObject(run.json.toString())
        extra.keys().forEach { payload.put(it, extra.get(it)) }
        payload.put("auditPersisted", run.persisted)
        if (!run.ok) {
            return result(cmd, Status.ERROR, run.code, run.message.ifEmpty { "Sahara identification failed." }, payload, simulated)
        }
        if (!run.persisted) {
            return result(cmd, Status.ERROR, "audit_write_failed", "Identification ran but its audit record could not be stored.", payload, simulated)
        }
        val message = if (simulated) "Simulated identification only; not hardware evidence." else
            "Read-only Sahara identification completed. This is not a compatibility or capability claim."
        return result(cmd, Status.OK, "ok", message, payload, simulated)
    }

    private fun PermissionState.wire() = name.lowercase()

    private fun catalog(): List<CommandSpec> = listOf(
        CommandSpec("companion.capabilities", Risk.READ_ONLY, true, "Describe commands and features."),
        CommandSpec("companion.status", Risk.READ_ONLY, true, "Phase / native core status."),
        CommandSpec("usb.list", Risk.READ_ONLY, true, "List attached USB devices (enumeration only); arg edl_only:boolean. Includes the permission state of 05c6:9008 devices."),
        CommandSpec(
            "usb.request_permission", Risk.USER_PROMPT, permissions.supported && audit.available,
            "dryRun=false shows Android's USB permission dialog for a 05c6:9008 device; the user decides on the device. Args: device_name (optional).",
        ),
        CommandSpec(
            "sahara.identify", Risk.DEVICE_SESSION, nativeCore.isLinked && sessions.supported,
            "dryRun=true: plan. dryRun=false: read-only Sahara identify (serial, HW ID, OEM PK hash); needs granted USB permission. Args: device_name, timeout_ms, simulate:boolean (simulator, no USB).",
        ),
    )

    private fun features(): JSONObject = JSONObject()
        .put("usbHostEnumeration", true)
        .put("usbPermissionRequest", permissions.supported)
        .put("usbHostOpen", sessions.supported)
        .put("saharaNative", nativeCore.isLinked)
        .put("saharaIdentifyReadOnly", nativeCore.isLinked && sessions.supported)
        .put("simulator", nativeCore.simulatorAvailable)
        .put("auditLog", audit.available)
        .put("firehose", false)
        .put("programmerUpload", false)
        .put("flashing", false)

    companion object {
        const val SCHEMA_VERSION = 1
        const val PROTOCOL_VERSION = 1
        private const val MAX_REQUEST_ID = 128
        private const val MAX_ARGS_CHARS = 64 * 1024
        private val NAME_PATTERN = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){0,3}")

        /** First name segments that are refused outright (write / destructive / bypass-adjacent). */
        private val DENIED_FAMILIES = setOf("flash", "firehose", "write", "erase", "format", "unlock")

        private const val DEFAULT_TIMEOUT_MS = 5_000
        private const val MIN_TIMEOUT_MS = 100
        private const val MAX_TIMEOUT_MS = 30_000

        val PLANNED = listOf("sahara.session", "device.profile", "backup.read")
    }
}
