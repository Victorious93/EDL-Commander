package ai.edlcommander.companion.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import ai.edlcommander.companion.nativecore.BulkTransport

private fun Context.usbManager(): UsbManager? = applicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager

private fun UsbDevice.isQdLoader() = QdLoader.isQdLoader9008(vendorId, productId)

/**
 * Asks Android to show its USB permission dialog for a 05c6:9008 device. The grant is the user's
 * decision on the system dialog; this class never grants anything and no manifest device filter
 * exists that could pre-authorize access.
 */
class AndroidUsbPermissionGate(
    context: Context,
    private val tracker: UsbPermissionTracker,
    private val onResult: (deviceName: String, granted: Boolean) -> Unit = { _, _ -> },
) : UsbPermissionGate {
    private val app = context.applicationContext
    private val manager = app.usbManager()

    override val supported: Boolean get() = manager != null

    init {
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION_PERMISSION) return
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    tracker.onResult(device.deviceName, granted)
                    onResult(device.deviceName, granted)
                }
            },
            IntentFilter(ACTION_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun state(deviceName: String): PermissionState {
        val usb = manager ?: return PermissionState.NOT_REQUESTED
        val devices = usb.deviceList
        tracker.retainOnly(devices.keys)
        val device = devices[deviceName] ?: return PermissionState.NOT_REQUESTED
        return tracker.stateFor(deviceName, usb.hasPermission(device))
    }

    override fun request(deviceName: String): RequestOutcome {
        val usb = manager ?: return RequestOutcome.REFUSED
        val device = usb.deviceList[deviceName] ?: return RequestOutcome.DEVICE_GONE
        if (!device.isQdLoader()) return RequestOutcome.REFUSED
        if (usb.hasPermission(device)) return RequestOutcome.ALREADY_GRANTED
        tracker.markRequested(deviceName)
        usb.requestPermission(device, pendingIntent())
        return RequestOutcome.PROMPTED
    }

    private fun pendingIntent(): PendingIntent {
        // The system fills in EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED, so the PendingIntent must be
        // mutable; it is made explicit with setPackage so only this app receives it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val intent = Intent(ACTION_PERMISSION).setPackage(app.packageName)
        return PendingIntent.getBroadcast(app, 0, intent, flags)
    }

    companion object {
        const val ACTION_PERMISSION = "ai.edlcommander.companion.USB_PERMISSION"
    }
}

/**
 * Opens a permission-granted 05c6:9008 device, finds its bulk interface from the descriptors and
 * claims it. It never requests permission and never reads or writes anything itself.
 */
class AndroidEdlSessionOpener(context: Context) : EdlSessionOpener {
    private val app = context.applicationContext
    private val manager = app.usbManager()

    override val supported: Boolean get() = manager != null

    override fun open(deviceName: String): OpenOutcome {
        val usb = manager ?: return OpenOutcome.Failed("usb_host_unavailable", "USB host is not available.")
        val device = usb.deviceList[deviceName]
            ?: return OpenOutcome.Failed("device_gone", "The device is no longer attached.")
        if (!device.isQdLoader()) return OpenOutcome.Failed("not_an_edl_candidate", "Only 05c6:9008 devices are opened.")
        if (!usb.hasPermission(device)) {
            return OpenOutcome.Failed("usb_permission_required", "USB permission has not been granted by the user.")
        }
        val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
        val infos = interfaces.map { iface ->
            InterfaceInfo(
                iface.id,
                iface.alternateSetting,
                iface.interfaceClass,
                (0 until iface.endpointCount).map {
                    val ep = iface.getEndpoint(it)
                    EndpointInfo(ep.address, ep.type, ep.maxPacketSize)
                },
            )
        }
        val selection = EndpointSelector.select(infos)
            ?: return OpenOutcome.Failed("no_bulk_interface", "No interface with a bulk IN and bulk OUT endpoint was found.")
        val iface = interfaces.first { it.id == selection.interfaceId && it.alternateSetting == selection.alternateSetting }

        val connection = try {
            usb.openDevice(device)
        } catch (_: SecurityException) {
            return OpenOutcome.Failed("usb_permission_required", "Permission was revoked before the device was opened.")
        } ?: return OpenOutcome.Failed("open_failed", "UsbManager.openDevice returned null.")

        // force=true detaches a kernel driver that may be bound to the interface.
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            return OpenOutcome.Failed("claim_failed", "Could not claim interface ${iface.id}.")
        }
        return OpenOutcome.Opened(AndroidSession(usb, deviceName, connection, iface, selection))
    }
}

private class AndroidSession(
    private val usb: UsbManager,
    private val deviceName: String,
    private val connection: UsbDeviceConnection,
    private val iface: android.hardware.usb.UsbInterface,
    selection: BulkSelection,
) : EdlUsbSession {
    override val transport: BulkTransport = UsbBulkPipe(
        object : BulkConnection {
            override fun bulkTransfer(endpointAddress: Int, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
                val ep = (0 until iface.endpointCount).map { iface.getEndpoint(it) }.first { it.address == endpointAddress }
                return connection.bulkTransfer(ep, buffer, offset, length, timeoutMs)
            }

            override fun isAttached(): Boolean = usb.deviceList.containsKey(deviceName)
        },
        selection.inAddress,
        selection.outAddress,
    )

    override fun close() {
        connection.releaseInterface(iface)
        connection.close()
    }
}
