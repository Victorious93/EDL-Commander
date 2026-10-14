package io.github.victorious93.edlcommander

import io.github.victorious93.edlcommander.nativecore.BulkTransport
import io.github.victorious93.edlcommander.nativecore.NativeCore
import io.github.victorious93.edlcommander.usb.EdlSessionOpener
import io.github.victorious93.edlcommander.usb.EdlUsbSession
import io.github.victorious93.edlcommander.usb.OpenOutcome
import io.github.victorious93.edlcommander.usb.PermissionState
import io.github.victorious93.edlcommander.usb.RequestOutcome
import io.github.victorious93.edlcommander.usb.UsbPermissionGate
import org.json.JSONObject

/** Records calls; "audit" is just newline-joined `action:outcome` entries (no hash chain). */
class FakeNativeCore(
    override val simulatorAvailable: Boolean = true,
    var authorize: () -> JSONObject? = { null },
    var identify: (BulkTransport) -> JSONObject = { JSONObject().put("ok", true).put("report", JSONObject().put("serial", "00000001")) },
) : NativeCore {
    override val isLinked = true
    override val version = "fake"
    val calls = mutableListOf<String>()

    private fun withAudit(audit: String, entry: String, extra: JSONObject = JSONObject().put("ok", true)): JSONObject =
        extra.put("audit", audit + entry + "\n")

    override fun authorizeIdentify(audit: String, timestamp: String): JSONObject {
        calls += "authorizeIdentify"
        authorize()?.let { return withAudit(audit, "identify_device:denied", it) }
        return withAudit(audit, "identify_device:allowed")
    }

    override fun auditAppend(audit: String, timestamp: String, action: String, outcome: String, detail: String): JSONObject {
        calls += "append:$action:$outcome"
        return withAudit(audit, "$action:$outcome")
    }

    override fun recordDeniedWrite(audit: String, timestamp: String, command: String): JSONObject {
        calls += "deniedWrite:$command"
        return withAudit(audit, "command:$command:denied")
    }

    override fun saharaIdentify(transport: BulkTransport, audit: String, timestamp: String, timeoutMs: Int): JSONObject {
        calls += "identify:$timeoutMs"
        return withAudit(audit, "sahara.identify:run", identify(transport))
    }

    override fun saharaIdentifySimulated(audit: String, timestamp: String): JSONObject {
        calls += "identifySimulated"
        return withAudit(audit, "sahara.identify:sim", JSONObject().put("ok", true).put("source", "sim"))
    }
}

class FakeGate(var state: PermissionState = PermissionState.NOT_REQUESTED, var outcome: RequestOutcome = RequestOutcome.PROMPTED) : UsbPermissionGate {
    override val supported = true
    val requests = mutableListOf<String>()
    override fun state(deviceName: String) = state
    override fun request(deviceName: String): RequestOutcome {
        requests += deviceName
        return outcome
    }
}

class FakeSessions(var next: () -> OpenOutcome) : EdlSessionOpener {
    override val supported = true
    val opened = mutableListOf<String>()
    var closed = 0

    override fun open(deviceName: String): OpenOutcome {
        opened += deviceName
        return next()
    }

    fun session(transport: BulkTransport) = OpenOutcome.Opened(
        object : EdlUsbSession {
            override val transport = transport
            override fun close() {
                closed++
            }
        },
    )
}
