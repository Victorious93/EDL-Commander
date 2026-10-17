package io.github.victorious93.edlcommander.usb

import io.github.victorious93.edlcommander.nativecore.BulkTransport

enum class PermissionState { NOT_REQUESTED, REQUESTED, GRANTED, DENIED }

enum class RequestOutcome {
    /** The system permission dialog was shown; the user decides on the device. */
    PROMPTED,
    ALREADY_GRANTED,
    DEVICE_GONE,

    /** Not a 05c6:9008 device, or no USB host support. */
    REFUSED,
}

/**
 * User-mediated USB permission. Implementations may only ask the system to show its dialog;
 * they must never grant permission themselves, and must refuse anything that is not 05c6:9008.
 */
interface UsbPermissionGate {
    val supported: Boolean
    fun state(deviceName: String): PermissionState
    fun request(deviceName: String): RequestOutcome
}

object NoPermissionGate : UsbPermissionGate {
    override val supported = false
    override fun state(deviceName: String) = PermissionState.NOT_REQUESTED
    override fun request(deviceName: String) = RequestOutcome.REFUSED
}

/**
 * Remembers what happened to our permission requests. The system's `hasPermission` is always
 * authoritative for GRANTED; this only fills in REQUESTED / DENIED, which the system does not expose.
 */
class UsbPermissionTracker {
    private val states = HashMap<String, PermissionState>()

    @Synchronized
    fun markRequested(deviceName: String) {
        states[deviceName] = PermissionState.REQUESTED
    }

    @Synchronized
    fun onResult(deviceName: String, granted: Boolean) {
        states[deviceName] = if (granted) PermissionState.GRANTED else PermissionState.DENIED
    }

    @Synchronized
    fun stateFor(deviceName: String, systemGranted: Boolean): PermissionState {
        if (systemGranted) {
            states[deviceName] = PermissionState.GRANTED
            return PermissionState.GRANTED
        }
        return when (val remembered = states[deviceName]) {
            // A remembered grant that the system no longer reports (device re-plugged, permission revoked).
            PermissionState.GRANTED, null -> PermissionState.NOT_REQUESTED.also { states.remove(deviceName) }
            else -> remembered
        }
    }

    /** Drop state for devices that are no longer attached. */
    @Synchronized
    fun retainOnly(attached: Set<String>) {
        states.keys.retainAll(attached)
    }
}

sealed interface OpenOutcome {
    class Opened(val session: EdlUsbSession) : OpenOutcome
    data class Failed(val code: String, val message: String) : OpenOutcome
}

/** An opened, claimed device. Closing releases the interface and the connection. */
interface EdlUsbSession : AutoCloseable {
    val transport: BulkTransport
}

interface EdlSessionOpener {
    val supported: Boolean

    /** Never prompts: fails with `usb_permission_required` unless the user already granted access. */
    fun open(deviceName: String): OpenOutcome
}

object NoSessionOpener : EdlSessionOpener {
    override val supported = false
    override fun open(deviceName: String) = OpenOutcome.Failed("usb_host_unavailable", "USB host is not available.")
}
