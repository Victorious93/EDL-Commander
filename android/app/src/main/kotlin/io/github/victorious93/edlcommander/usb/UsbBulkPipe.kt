package io.github.victorious93.edlcommander.usb

import io.github.victorious93.edlcommander.nativecore.BulkTransport

/** The slice of `UsbDeviceConnection` the pipe needs; faked in JVM tests. */
interface BulkConnection {
    /** Same contract as `UsbDeviceConnection.bulkTransfer`: bytes transferred, or -1 on any failure. */
    fun bulkTransfer(endpointAddress: Int, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** False once the device has gone away. */
    fun isAttached(): Boolean
}

/**
 * [BulkTransport] over one IN and one OUT bulk endpoint.
 *
 * `bulkTransfer` reports every failure as -1, so failures are classified here: device gone ->
 * [BulkTransport.DISCONNECTED]; the call used (nearly) its whole timeout -> [BulkTransport.TIMEOUT];
 * otherwise [BulkTransport.IO_ERROR]. A timeout of 0 would mean "wait forever" on Android and is
 * never passed through. The elapsed-time heuristic has not been checked on real hardware.
 */
class UsbBulkPipe(
    private val connection: BulkConnection,
    private val inAddress: Int,
    private val outAddress: Int,
    private val nanoTime: () -> Long = System::nanoTime,
    private val maxChunk: Int = MAX_CHUNK,
) : BulkTransport {

    override fun write(data: ByteArray, timeoutMs: Int): Int {
        val timeout = timeoutMs.coerceAtLeast(1)
        var sent = 0
        while (sent < data.size) {
            val chunk = minOf(maxChunk, data.size - sent)
            val started = nanoTime()
            val n = safeTransfer(outAddress, data, sent, chunk, timeout)
            if (n < 0) return classify(started, timeout)
            if (n == 0) return BulkTransport.IO_ERROR
            sent += n
        }
        return sent
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val timeout = timeoutMs.coerceAtLeast(1)
        val started = nanoTime()
        val n = safeTransfer(inAddress, buf, 0, buf.size, timeout)
        return if (n < 0) classify(started, timeout) else n
    }

    private fun safeTransfer(endpoint: Int, buf: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int = try {
        connection.bulkTransfer(endpoint, buf, offset, length, timeoutMs)
    } catch (_: RuntimeException) {
        -1
    }

    private fun classify(startedNanos: Long, timeoutMs: Int): Int {
        if (!connection.isAttached()) return BulkTransport.DISCONNECTED
        val elapsedMs = (nanoTime() - startedNanos) / 1_000_000
        return if (elapsedMs >= timeoutMs - TIMEOUT_SLACK_MS) BulkTransport.TIMEOUT else BulkTransport.IO_ERROR
    }

    companion object {
        /** Pre-API-28 `bulkTransfer` rejects larger single transfers. */
        const val MAX_CHUNK = 16 * 1024
        private const val TIMEOUT_SLACK_MS = 10
    }
}
