package io.github.victorious93.edlcommander.usb

import android.content.Context
import android.hardware.usb.UsbManager

/** What the companion is allowed to know about an attached USB device without opening it. */
data class UsbDeviceSnapshot(
    val vendorId: Int,
    val productId: Int,
    /** Android's device path (e.g. /dev/bus/usb/001/002). Not a stable identifier. */
    val deviceName: String,
) {
    val isQdLoader9008: Boolean get() = QdLoader.isQdLoader9008(vendorId, productId)
}

/** Enumeration only. Implementations must not request permission, open, or transfer to devices. */
fun interface UsbDeviceSource {
    fun list(): List<UsbDeviceSnapshot>
}

/** Reads `UsbManager.getDeviceList()`; this never touches the device itself. */
class AndroidUsbDeviceSource(context: Context) : UsbDeviceSource {
    private val usbManager = context.applicationContext.getSystemService(Context.USB_SERVICE) as? UsbManager

    override fun list(): List<UsbDeviceSnapshot> {
        val manager = usbManager ?: return emptyList()
        return manager.deviceList.values
            .map { UsbDeviceSnapshot(it.vendorId, it.productId, it.deviceName) }
            .sortedBy { it.deviceName }
    }
}
