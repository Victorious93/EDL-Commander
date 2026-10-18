package ai.edlcommander.companion.usb

/** Plain copies of the USB descriptor fields we need, so selection is testable without Android. */
data class EndpointInfo(
    val address: Int,
    /** `bmAttributes & 0x03` (2 = bulk), as in `UsbEndpoint.getType()`. */
    val type: Int,
    val maxPacketSize: Int,
) {
    val isIn: Boolean get() = (address and DIR_IN) != 0
    val isBulk: Boolean get() = type == XFER_BULK && maxPacketSize > 0

    companion object {
        const val DIR_IN = 0x80
        const val XFER_BULK = 2
    }
}

data class InterfaceInfo(
    val id: Int,
    val alternateSetting: Int,
    val usbClass: Int,
    val endpoints: List<EndpointInfo>,
)

data class BulkSelection(
    val interfaceId: Int,
    val alternateSetting: Int,
    val inAddress: Int,
    val outAddress: Int,
)

/**
 * Picks the interface and bulk endpoints to talk Sahara over. Nothing here assumes a fixed
 * layout: the interface must expose at least one bulk IN and one bulk OUT endpoint, vendor-specific
 * class (0xFF) is preferred, then the lowest interface id and alternate setting.
 */
object EndpointSelector {
    private const val CLASS_VENDOR = 0xFF

    fun select(interfaces: List<InterfaceInfo>): BulkSelection? {
        val usable = interfaces.filter { i -> i.endpoints.any { it.isBulk && it.isIn } && i.endpoints.any { it.isBulk && !it.isIn } }
        val best = usable.sortedWith(
            compareBy<InterfaceInfo>({ if (it.usbClass == CLASS_VENDOR) 0 else 1 }, { it.id }, { it.alternateSetting }),
        ).firstOrNull() ?: return null
        val inEp = best.endpoints.first { it.isBulk && it.isIn }
        val outEp = best.endpoints.first { it.isBulk && !it.isIn }
        return BulkSelection(best.id, best.alternateSetting, inEp.address, outEp.address)
    }
}
