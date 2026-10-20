package ai.edlcommander.companion.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * `node.status.get` (fleet STATUS-TRAY spec, droidcommand-AI docs/specs/STATUS_TRAY.md, frozen).
 * Builds one `NodeStatusSnapshot` with a single `node_health` domain from in-process app state
 * only. It never touches USB, Sahara, the audit log or any write path.
 */
class NodeStatusProvider(
    private val nodeId: String,
    private val packageName: String?,
    private val nativeCoreLinked: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(nodeId.isNotBlank()) { "nodeId must be non-blank" }
    }

    /** Snapshot as the frozen wire object, or null if it would violate the privacy rules. */
    fun snapshot(): JSONObject? {
        val linked = nativeCoreLinked()
        val health = if (linked) {
            NodeStatusCodec.domain("node_health", "ok", "companion running; native core linked")
        } else {
            NodeStatusCodec.domain("node_health", "degraded", "companion running; native core unavailable")
        }
        return NodeStatusCodec.encode(nodeId, packageName, CONNECTION_CLASS, clock(), listOf(health))
    }

    fun matches(requestedNodeId: String?): Boolean = requestedNodeId.isNullOrEmpty() || requestedNodeId == nodeId

    companion object {
        const val OP = "node.status.get"
        const val CONNECTION_CLASS = "BOUND_COMPANION"
    }
}

object NodeStatusCodec {
    val DOMAINS = setOf("mesh", "vpn", "browser", "sms", "node_health", "other")
    val STATES = setOf("ok", "degraded", "down", "unknown", "off")
    val CONNECTION_CLASSES = setOf("EXTERNAL_NODE", "BOUND_COMPANION", "TERMUX_IPC", "HOST_PROCESS_TOOL")
    const val MAX_DETAIL = 200

    private val FORBIDDEN = listOf(
        "password", "apikey", "api_key", "api_token", "token", "secret", "codeword", "code_word",
        "whitelist", "signingkey", "signing_key", "privatekey", "private_key", "cookie", "keystore",
    )

    fun containsForbidden(text: String): Boolean {
        val t = text.lowercase()
        return FORBIDDEN.any { t.contains(it) }
    }

    fun domain(name: String, state: String, detail: String? = null): JSONObject {
        val o = JSONObject().put("name", if (name in DOMAINS) name else "other").put("state", state)
        if (!detail.isNullOrEmpty()) o.put("detail", detail)
        return o
    }

    /** Refuses (returns null) instead of silently stripping, per the spec's privacy section. */
    fun encode(nodeId: String, packageName: String?, connectionClass: String, updatedAtMs: Long, domains: List<JSONObject>): JSONObject? {
        if (nodeId.isBlank() || connectionClass !in CONNECTION_CLASSES) return null
        val arr = JSONArray()
        for (d in domains) {
            if (d.optString("state") !in STATES) return null
            val detail = d.optString("detail", "")
            if (detail.length > MAX_DETAIL || containsForbidden(detail)) return null
            arr.put(d)
        }
        return JSONObject()
            .put("nodeId", nodeId)
            .put("packageName", packageName ?: JSONObject.NULL)
            .put("connectionClass", connectionClass)
            .put("updatedAtMs", updatedAtMs)
            .put("domains", arr)
    }
}
