package io.github.victorious93.edlcommander

import io.github.victorious93.edlcommander.audit.AuditTrail
import io.github.victorious93.edlcommander.audit.FileAuditStore
import io.github.victorious93.edlcommander.command.CommandRouter
import io.github.victorious93.edlcommander.command.Status
import io.github.victorious93.edlcommander.nativecore.BulkTransport
import io.github.victorious93.edlcommander.nativecore.JniNativeCore
import io.github.victorious93.edlcommander.nativecore.NativeCore
import io.github.victorious93.edlcommander.usb.OpenOutcome
import io.github.victorious93.edlcommander.usb.PermissionState
import io.github.victorious93.edlcommander.usb.UsbDeviceSnapshot
import io.github.victorious93.edlcommander.usb.UsbDeviceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Sahara target implemented independently in Kotlin (command mode only), speaking through
 * [BulkTransport]. Together with the host build of `libedl_jni` this exercises the real JNI glue
 * (callbacks, byte arrays, exceptions) on the JVM. It is a test double, not evidence about devices.
 */
class KotlinSaharaTarget(
    private val serial: Int = 0x1234ABCD,
    private val hwId: Long = 0x0009B0E101230045L,
    private val pkHash: ByteArray = ByteArray(32) { (it * 7 + 1).toByte() },
    var mode: Mode = Mode.NORMAL,
) : BulkTransport {
    enum class Mode { NORMAL, SILENT, DISCONNECTED, THROW_ON_READ }

    private val out = ArrayDeque<ByteArray>()
    val hostCommands = mutableListOf<Int>()
    var switchedTo: Int? = null

    init {
        out += words(1, 48, 2, 1, 1024, 0, 0, 0, 0, 0, 0, 0)
    }

    private fun words(vararg w: Int): ByteArray {
        val b = ByteBuffer.allocate(w.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        w.forEach { b.putInt(it) }
        return b.array()
    }

    private fun data(cc: Int): ByteArray = when (cc) {
        1 -> words(serial)
        2 -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(hwId).array()
        else -> pkHash
    }

    override fun write(data: ByteArray, timeoutMs: Int): Int {
        if (mode == Mode.DISCONNECTED) return BulkTransport.DISCONNECTED
        val w = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = w.getInt(0)
        hostCommands += cmd
        when (cmd) {
            2 -> if (w.getInt(20) == 3) out += words(0xb, 8)
            0xd -> w.getInt(8).let { out += words(0xe, 16, it, data(it).size) }
            0xf -> out += data(w.getInt(8))
            0xc -> switchedTo = w.getInt(8)
        }
        return data.size
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        when (mode) {
            Mode.DISCONNECTED -> return BulkTransport.DISCONNECTED
            Mode.THROW_ON_READ -> throw IllegalStateException("boom from Kotlin")
            Mode.SILENT -> return BulkTransport.TIMEOUT
            Mode.NORMAL -> {}
        }
        val next = out.removeFirstOrNull() ?: return BulkTransport.TIMEOUT
        next.copyInto(buf)
        return next.size
    }
}

class NativeBridgeJvmTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var core: NativeCore

    @Before
    fun loadHostLibrary() {
        val path: String? = System.getProperty("edl.jni.host.lib")
        assumeTrue("host libedl_jni not built (-Pedl.skipNative=true?)", path != null && File(path).isFile)
        core = JniNativeCore.load { System.load(path!!) }
        assertTrue(core.isLinked)
    }

    private fun audit(r: org.json.JSONObject) = r.getString("audit")

    @Test
    fun versionAndFeaturesComeFromRust() {
        assertEquals("0.1.0", core.version)
        assertTrue(core.simulatorAvailable)
    }

    @Test
    fun identifyRunsRealSaharaOverKotlinCallbacks() {
        val target = KotlinSaharaTarget()
        val r = core.saharaIdentify(target, "", "2026-01-01T00:00:00Z", 1000)
        assertTrue(r.toString(), r.getBoolean("ok"))
        val rep = r.getJSONObject("report")
        assertEquals("1234abcd", rep.getString("serial"))
        assertEquals("0009b0e101230045", rep.getString("msmHwId"))
        assertEquals("09b0e1", rep.getString("msmId"))
        assertEquals(32, rep.getInt("oemPkHashBytes"))
        assertEquals(64, rep.getString("oemPkHash").length)
        assertTrue(rep.getBoolean("returnedToImageMode"))
        // Only HELLO_RESP, EXEC, EXEC_DATA and SWITCH_MODE: no READ_DATA, DONE, or anything else.
        assertEquals(setOf(2, 0xd, 0xf, 0xc), target.hostCommands.toSet())
        assertEquals(0, target.switchedTo)
    }

    @Test
    fun auditChainSurvivesTheJniBoundaryAndExtends() {
        val a = core.authorizeIdentify("", "t0")
        assertTrue(a.getBoolean("ok"))
        val b = core.saharaIdentify(KotlinSaharaTarget(), audit(a), "t1", 1000)
        val c = core.auditAppend(audit(b), "t2", "usb.request_permission", "requested", "")
        assertEquals(3, audit(c).lines().filter { it.isNotBlank() }.size)
        val bad = core.auditAppend(audit(c).replace("allowed", "denied"), "t3", "x", "y", "")
        assertFalse(bad.getBoolean("ok"))
        assertEquals("audit_invalid", bad.getString("code"))
        assertFalse(bad.has("audit"))
        assertEquals("audit_invalid", core.authorizeIdentify("not json\n", "t").getString("code"))
    }

    @Test
    fun failuresAreReportedNotThrown() {
        assertEquals("device_timeout", core.saharaIdentify(KotlinSaharaTarget(mode = KotlinSaharaTarget.Mode.SILENT), "", "t", 50).getString("code"))
        assertEquals("device_disconnected", core.saharaIdentify(KotlinSaharaTarget(mode = KotlinSaharaTarget.Mode.DISCONNECTED), "", "t", 50).getString("code"))
        val thrown = core.saharaIdentify(KotlinSaharaTarget(mode = KotlinSaharaTarget.Mode.THROW_ON_READ), "", "t", 50)
        assertFalse(thrown.getBoolean("ok"))
        assertEquals("transport_error", thrown.getString("code"))
        assertTrue("failed runs are audited", audit(thrown).contains("\"outcome\":\"error\""))
    }

    @Test
    fun simulatedIdentifyIsLabelledAndNeedsNoTransport() {
        val r = core.saharaIdentifySimulated("", "t")
        assertTrue(r.getBoolean("ok"))
        assertEquals("sim", r.getString("source"))
    }

    @Test
    fun deniedWritesAreRecordedViaTheCoreAuthorizer() {
        val r = core.recordDeniedWrite("", "t", "flash.partition")
        assertTrue(audit(r).contains("command:flash.partition"))
        assertTrue(audit(r).contains("no matching device profile"))
    }

    @Test
    fun routerEndToEndOverJniWithFileAuditStore() {
        val edl = UsbDeviceSnapshot(0x05C6, 0x9008, "/dev/bus/usb/001/002")
        val store = FileAuditStore(File(tmp.root, "audit.jsonl"))
        val trail = AuditTrail(core, store)
        val gate = FakeGate(PermissionState.GRANTED)
        val sessions = FakeSessions { OpenOutcome.Failed("x", "x") }
        sessions.next = { sessions.session(KotlinSaharaTarget()) }
        val router = CommandRouter(UsbDeviceSource { listOf(edl) }, core, "test", trail, gate, sessions)

        val res = router.handleRaw(1, "r1", "sahara.identify", "{}", false)
        assertEquals(res.message, Status.OK, res.status)
        assertEquals("1234abcd", res.payload.getJSONObject("report").getString("serial"))
        assertEquals(1, sessions.closed)

        gate.state = PermissionState.NOT_REQUESTED
        assertEquals("usb_permission_required", router.handleRaw(1, "r2", "sahara.identify", "{}", false).code)
        assertEquals(Status.DENIED, router.handleRaw(1, "r3", "flash.partition", "{}", false).status)

        val lines = store.read().lines().filter { it.isNotBlank() }
        assertEquals(
            listOf("identify_device", "sahara.identify", "sahara.identify", "command:flash.partition"),
            lines.map { org.json.JSONObject(it).getString("action") },
        )
        assertEquals(listOf("allowed", "ok", "denied", "denied"), lines.map { org.json.JSONObject(it).getString("outcome") })
        assertFalse("no identifiers in the audit log", store.read().contains("1234abcd"))

        File(tmp.root, "audit.jsonl").writeText(store.read().replace("allowed", "denied"))
        assertEquals(Status.OK, router.also { gate.state = PermissionState.GRANTED }.handleRaw(1, "r4", "sahara.identify", "{}", false).status)
        assertTrue("tampered log kept aside", tmp.root.listFiles()!!.any { it.name.startsWith("audit.jsonl.corrupt-") })
    }

    @Test
    fun missingLibraryFallsBackToUnlinked() {
        val fallback = JniNativeCore.load { throw UnsatisfiedLinkError("no lib") }
        assertFalse(fallback.isLinked)
    }
}
