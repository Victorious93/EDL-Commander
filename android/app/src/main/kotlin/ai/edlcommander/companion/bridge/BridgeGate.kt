package ai.edlcommander.companion.bridge

import ai.edlcommander.companion.command.Command
import ai.edlcommander.companion.command.CommandResult
import ai.edlcommander.companion.command.CommandRouter
import ai.edlcommander.companion.command.Status
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Outcome of a bridge request: the router-style result plus the frozen error code (null on success). */
data class BridgeResult(
    val result: CommandResult,
    val error: BridgeError?,
    val op: String,
    val sourceNodeRef: String,
)

/**
 * Receiver-side enforcement of the frozen remote-control envelope v1. Order of checks:
 * version -> op -> field validation -> caller identity -> expiry/skew -> duplicate nonce ->
 * local remote-control switch -> receiver classification -> existing [CommandRouter] gates.
 *
 * The router keeps every existing gate (write families denied, USB permission, security::authorize,
 * audit-before-run). This class only ever adds checks in front of it; it never bypasses one.
 */
class BridgeGate(
    private val router: CommandRouter,
    private val remoteControl: RemoteControlSetting,
    private val nonces: NonceCache = NonceCache(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val nodeStatus: NodeStatusProvider? = null,
) {
    /** @param sourceNodeRef derived by the service from the Binder calling uid; null = unknown caller. */
    fun handle(req: BridgeRequest, sourceNodeRef: String?): BridgeResult {
        val id = req.requestId.orEmpty().take(MAX_REQUEST_ID)
        val opWire = req.op.orEmpty().take(64)
        val source = sourceNodeRef.orEmpty()
        fun fail(error: BridgeError, message: String) =
            BridgeResult(CommandResult(id, statusFor(error), error.wire, message, dryRun = req.dryRun), error, opWire, source)

        if (req.major != BridgeProtocol.MAJOR) {
            return fail(BridgeError.INVALID_PARAMETERS, "bridgeProtocolMajor ${req.major}; expected ${BridgeProtocol.MAJOR}")
        }
        val op = BridgeOp.parse(req.op) ?: return fail(BridgeError.UNSUPPORTED_OP, "Unknown op '$opWire'.")
        if (req.requestId.isNullOrEmpty() || req.requestId.length > MAX_REQUEST_ID) {
            return fail(BridgeError.INVALID_PARAMETERS, "requestId must be 1..$MAX_REQUEST_ID characters")
        }
        val nonce = req.nonce?.lowercase()
        if (nonce == null || !NONCE_PATTERN.matches(nonce)) {
            return fail(BridgeError.INVALID_PARAMETERS, "nonce must be 128 bits as 32 hex characters")
        }
        if (req.timestampMs <= 0) return fail(BridgeError.INVALID_PARAMETERS, "timestampMs must be set (epoch ms)")
        val ttl = if (req.ttlMs == 0L) BridgeProtocol.DEFAULT_TTL_MS else req.ttlMs
        if (ttl < 0 || ttl > BridgeProtocol.MAX_TTL_MS) {
            return fail(BridgeError.INVALID_PARAMETERS, "ttlMs must be 1..${BridgeProtocol.MAX_TTL_MS}")
        }
        val params = parseObject(req.paramsJson) ?: return fail(BridgeError.INVALID_PARAMETERS, "params is not a JSON object (max 64 KiB)")
        parseObject(req.triggerJson) ?: return fail(BridgeError.INVALID_PARAMETERS, "trigger is not a JSON object (max 64 KiB)")
        if ((req.targetNodeRef?.length ?: 0) > BridgeProtocol.MAX_REF_CHARS || (req.actionRef?.length ?: 0) > BridgeProtocol.MAX_REF_CHARS) {
            return fail(BridgeError.INVALID_PARAMETERS, "targetNodeRef/actionRef too long")
        }
        if (source.isEmpty()) return fail(BridgeError.NOT_PAIRED, "The caller identity could not be resolved.")

        val now = clock()
        if (now > req.timestampMs + ttl || kotlin.math.abs(now - req.timestampMs) > BridgeProtocol.MAX_SKEW_MS) {
            return fail(BridgeError.EXPIRED, "Request expired or clock skew exceeds ${BridgeProtocol.MAX_SKEW_MS} ms.")
        }
        when (nonces.checkAndRecord(source, nonce, now)) {
            NonceCache.Outcome.DUPLICATE -> return fail(BridgeError.REPLAY_DETECTED, "Nonce already used by this caller.")
            NonceCache.Outcome.FULL -> return fail(BridgeError.INTERNAL, "Replay cache is full; retry later.")
            NonceCache.Outcome.FRESH -> Unit
        }

        if (op != BridgeOp.CAPABILITIES_GET && !remoteControl.isEnabled()) {
            return fail(
                BridgeError.EXTERNAL_CONTROL_DISABLED,
                "Remote control is off. The user must enable it in the EDL Commander app on this device.",
            )
        }

        val ctx = Ctx(id, opWire, source, req)
        return when (op) {
            BridgeOp.CAPABILITIES_GET -> BridgeResult(
                CommandResult(id, Status.OK, "ok", "EDL Commander bridge capabilities (v${BridgeProtocol.MAJOR}.${BridgeProtocol.MINOR}).", capabilities(), dryRun = req.dryRun),
                null, opWire, source,
            )
            BridgeOp.REMOTE_COMMAND -> single(ctx, req.actionRef, params, JSONObject())
            BridgeOp.MACRO_STEP -> macroStep(ctx, params)
            BridgeOp.MACRO_RUN -> macroRun(ctx)
            BridgeOp.CONFIG_EXPORT, BridgeOp.CONFIG_IMPORT -> single(ctx, op.wire, params, JSONObject())
            // STATUS_TRAY spec section 5: `destructive` is advisory and ignored for gating on this read.
            BridgeOp.NODE_STATUS_GET -> {
                val r = runAction(id, NodeStatusProvider.OP, params, req.dryRun)
                BridgeResult(r, mapRouter(r), opWire, source)
            }
        }
    }

    private class Ctx(val id: String, val op: String, val source: String, val req: BridgeRequest)

    private fun err(ctx: Ctx, error: BridgeError, message: String, payload: JSONObject = JSONObject()) = BridgeResult(
        CommandResult(ctx.id, statusFor(error), error.wire, message, payload, dryRun = ctx.req.dryRun), error, ctx.op, ctx.source,
    )

    fun capabilities(): JSONObject {
        val ops = JSONArray()
        ActionClassifier.catalog().forEach {
            ops.put(
                JSONObject()
                    .put("name", it.name)
                    .put("kind", it.kind.wire)
                    .put("remoteAllowed", it.remoteAllowed)
                    .put("requiresConfirm", it.requiresConfirm),
            )
        }
        return JSONObject()
            .put("bridgeProtocolMajor", BridgeProtocol.MAJOR)
            .put("bridgeProtocolMinor", BridgeProtocol.MINOR)
            .put("envelopeOps", JSONArray(BridgeOp.entries.map { it.wire }))
            .put("externalControlEnabled", remoteControl.isEnabled())
            .put("ops", ops)
            .put("defaultTtlMs", BridgeProtocol.DEFAULT_TTL_MS)
            .put("maxTtlMs", BridgeProtocol.MAX_TTL_MS)
            .put("maxSkewMs", BridgeProtocol.MAX_SKEW_MS)
            .put("hardwareValidated", false)
    }

    /** Pre-execution decision for one action; null means it may be handed to the router. */
    private fun preflight(actionRef: String, advisoryDestructive: Boolean): Pair<BridgeError, String>? {
        val policy = ActionClassifier.classify(actionRef)
        // STATUS_TRAY spec: the advisory flag is ignored for gating on node.status.get (read-only, no device I/O).
        val advisory = advisoryDestructive && actionRef != NodeStatusProvider.OP
        if (ActionClassifier.effectiveKind(policy, advisory) == ActionKind.DESTRUCTIVE) {
            return BridgeError.UNAUTHORIZED to
                "'$actionRef' is treated as destructive; remote execution is never allowed (local user approval on this device only)."
        }
        if (!policy.known) return BridgeError.NOT_FOUND to "Unknown action '$actionRef'."
        if (!policy.remoteAllowed) return BridgeError.UNAUTHORIZED to "'$actionRef' is not remotely allowed."
        return null
    }

    private fun single(ctx: Ctx, actionRef: String?, params: JSONObject, extra: JSONObject): BridgeResult {
        if (actionRef.isNullOrEmpty()) return err(ctx, BridgeError.INVALID_PARAMETERS, "actionRef is required", extra)
        preflight(actionRef, ctx.req.destructive)?.let { (e, m) -> return err(ctx, e, m, extra.put("actionRef", actionRef)) }
        val r = runAction(ctx.id, actionRef, params, ctx.req.dryRun)
        extra.keys().forEach { r.payload.put(it, extra.get(it)) }
        return BridgeResult(r, mapRouter(r), ctx.op, ctx.source)
    }

    private fun macroStep(ctx: Ctx, params: JSONObject): BridgeResult {
        val macroId = ctx.req.macroId
        if (macroId.isNullOrEmpty() || macroId.length > BridgeProtocol.MAX_REF_CHARS) {
            return err(ctx, BridgeError.INVALID_PARAMETERS, "macroId is required")
        }
        if (ctx.req.stepIndex < 0) return err(ctx, BridgeError.INVALID_PARAMETERS, "stepIndex must be >= 0")
        val onError = OnError.parse(ctx.req.onError) ?: return err(ctx, BridgeError.INVALID_PARAMETERS, "onError must be abort|continue")
        val extra = JSONObject().put("macroId", macroId).put("stepIndex", ctx.req.stepIndex).put("onError", onError.wire)
        return single(ctx, ctx.req.actionRef, params, extra)
    }

    private fun macroRun(ctx: Ctx): BridgeResult {
        val macroId = ctx.req.macroId
        if (macroId.isNullOrEmpty() || macroId.length > BridgeProtocol.MAX_REF_CHARS) {
            return err(ctx, BridgeError.INVALID_PARAMETERS, "macroId is required")
        }
        val steps = parseSteps(ctx.req.stepsJson)
            ?: return err(ctx, BridgeError.INVALID_PARAMETERS, "steps must be a JSON array of 1..${BridgeProtocol.MAX_MACRO_STEPS} {actionRef, params, onError?} objects")

        val verdicts = steps.map { preflight(it.step.actionRef, ctx.req.destructive) }
        val plan = JSONArray()
        steps.forEachIndexed { i, s ->
            val policy = ActionClassifier.classify(s.step.actionRef)
            plan.put(
                JSONObject()
                    .put("stepIndex", i)
                    .put("actionRef", s.step.actionRef)
                    .put("kind", ActionClassifier.effectiveKind(policy, ctx.req.destructive).wire)
                    .put("remoteAllowed", policy.remoteAllowed)
                    .put("requiresConfirm", policy.requiresConfirm)
                    .put("onError", s.onError.wire)
                    .put("verdict", verdicts[i]?.first?.wire ?: "allowed"),
            )
        }
        val base = JSONObject().put("macroId", macroId).put("steps", plan)

        if (ctx.req.dryRun) {
            // A dry run never reaches the router or any device: classification and plan only.
            return BridgeResult(
                CommandResult(ctx.id, Status.DRY_RUN, "dry_run", "Macro plan only; nothing was executed and no device was touched.", base, dryRun = true),
                null, ctx.op, ctx.source,
            )
        }
        // All-or-nothing preflight: if any step would be refused, nothing runs.
        verdicts.firstOrNull { it != null }?.let { (e, m) -> return err(ctx, e, "Macro refused before execution: $m", base) }

        val results = JSONArray()
        var firstError: BridgeError? = null
        for ((i, s) in steps.withIndex()) {
            val r = runAction(ctx.id, s.step.actionRef, JSONObject(s.step.paramsJson), false)
            val e = mapRouter(r)
            results.put(
                JSONObject().put("stepIndex", i).put("actionRef", s.step.actionRef).put("status", r.status.name.lowercase())
                    .put("code", r.code).put("errorCode", e?.wire ?: JSONObject.NULL).put("payload", r.payload),
            )
            if (e != null) {
                if (firstError == null) firstError = e
                if (s.onError == OnError.ABORT) break
            }
        }
        base.put("results", results)
        return if (firstError == null) {
            BridgeResult(CommandResult(ctx.id, Status.OK, "ok", "Macro completed.", base, dryRun = false), null, ctx.op, ctx.source)
        } else {
            err(ctx, firstError, "Macro step failed.", base)
        }
    }

    /** Dispatch for actions the bridge serves itself (node.status.get); everything else goes to the router. */
    private fun runAction(id: String, actionRef: String, params: JSONObject, dryRun: Boolean): CommandResult {
        if (actionRef != NodeStatusProvider.OP) return router.handle(Command(id, actionRef, params, dryRun))
        val provider = nodeStatus
            ?: return CommandResult(id, Status.UNSUPPORTED, BridgeError.CAPABILITY_OFF.wire, "node.status.get is not available in this build.", dryRun = dryRun)
        val requested = params.optString("nodeId", "").ifEmpty { params.optJSONObject("args")?.optString("nodeId", "").orEmpty() }
        if (!provider.matches(requested)) {
            return CommandResult(id, Status.UNSUPPORTED, BridgeError.NOT_FOUND.wire, "nodeId does not match this node.", dryRun = dryRun)
        }
        val snapshot = provider.snapshot()
            ?: return CommandResult(id, Status.INVALID, BridgeError.INVALID_PARAMETERS.wire, "Snapshot refused by the privacy rules.", dryRun = dryRun)
        return CommandResult(id, Status.OK, "ok", "Node status snapshot (app state only; not hardware evidence).", snapshot, dryRun = dryRun)
    }

    private class ParsedStep(val step: BridgeStep, val onError: OnError)

    private fun parseSteps(raw: String?): List<ParsedStep>? {
        if (raw.isNullOrBlank() || raw.length > MAX_JSON_CHARS) return null
        val arr = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            return null
        }
        if (arr.length() !in 1..BridgeProtocol.MAX_MACRO_STEPS) return null
        return (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: return null
            val action = o.optString("actionRef", "")
            if (action.isEmpty() || action.length > BridgeProtocol.MAX_REF_CHARS) return null
            val params = o.opt("params") ?: JSONObject()
            if (params !is JSONObject) return null
            val onError = OnError.parse(o.optString("onError", "")) ?: return null
            ParsedStep(BridgeStep(action, params.toString()), onError)
        }
    }

    companion object {
        private const val MAX_REQUEST_ID = 128
        private const val MAX_JSON_CHARS = 64 * 1024
        private val NONCE_PATTERN = Regex("[0-9a-f]{32}")

        fun statusFor(e: BridgeError): Status = when (e) {
            BridgeError.INVALID_PARAMETERS, BridgeError.REPLAY_DETECTED, BridgeError.EXPIRED -> Status.INVALID
            BridgeError.NOT_PAIRED, BridgeError.UNAUTHORIZED, BridgeError.EXTERNAL_CONTROL_DISABLED,
            BridgeError.CONFIRMATION_REQUIRED -> Status.DENIED
            BridgeError.NOT_FOUND, BridgeError.UNSUPPORTED_OP, BridgeError.CAPABILITY_OFF -> Status.UNSUPPORTED
            BridgeError.INTERNAL -> Status.ERROR
        }

        /** Maps an existing router result onto the frozen vocabulary (null = success). */
        fun mapRouter(r: CommandResult): BridgeError? = when {
            r.status != Status.OK && r.status != Status.DRY_RUN && BridgeError.entries.any { it.wire == r.code } ->
                BridgeError.entries.first { it.wire == r.code }
            r.code == "permission_prompt_shown" -> BridgeError.CONFIRMATION_REQUIRED
            r.status == Status.OK || r.status == Status.DRY_RUN -> null
            r.code == "unknown_command" -> BridgeError.NOT_FOUND
            r.code == "writes_not_available" -> BridgeError.UNAUTHORIZED
            r.code == "usb_permission_required" -> BridgeError.CONFIRMATION_REQUIRED
            r.status == Status.UNSUPPORTED -> BridgeError.CAPABILITY_OFF
            r.status == Status.INVALID -> BridgeError.INVALID_PARAMETERS
            r.status == Status.DENIED -> BridgeError.UNAUTHORIZED
            else -> BridgeError.INTERNAL
        }

        private fun parseObject(raw: String?): JSONObject? {
            val s = raw?.takeIf { it.isNotBlank() } ?: "{}"
            if (s.length > MAX_JSON_CHARS) return null
            return try {
                JSONObject(s)
            } catch (_: JSONException) {
                null
            }
        }
    }
}
