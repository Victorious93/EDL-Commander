package io.github.victorious93.edlcommander

import io.github.victorious93.edlcommander.audit.AuditTrail
import io.github.victorious93.edlcommander.audit.FileAuditStore
import io.github.victorious93.edlcommander.audit.InMemoryAuditStore
import io.github.victorious93.edlcommander.nativecore.UnlinkedNativeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AuditStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun fileStoreRoundTripsAndCreatesParents() {
        val f = File(tmp.root, "a/b/audit.jsonl")
        val s = FileAuditStore(f)
        assertEquals("", s.read())
        s.write("one\n")
        s.write("one\ntwo\n")
        assertEquals("one\ntwo\n", s.read())
        assertFalse(File(f.path + ".tmp").exists())
    }

    @Test
    fun oversizedLogIsRotatedNotTruncated() {
        val f = File(tmp.root, "audit.jsonl")
        val s = FileAuditStore(f, maxBytes = 10)
        s.write("0123456789ABC\n")
        s.write("fresh\n")
        assertEquals("fresh\n", s.read())
        assertEquals("0123456789ABC\n", File(f.path + ".1").readText())
    }

    @Test
    fun quarantineKeepsTheBadFile() {
        val f = File(tmp.root, "audit.jsonl")
        val s = FileAuditStore(f, nowMillis = { 123 })
        s.write("garbage\n")
        s.quarantine()
        assertEquals("", s.read())
        assertEquals("garbage\n", File(f.path + ".corrupt-123").readText())
        s.quarantine()
    }

    @Test
    fun trailFailsClosedWithoutNativeCore() {
        val trail = AuditTrail(UnlinkedNativeCore, InMemoryAuditStore())
        assertFalse(trail.available)
        val r = trail.append("a", "b", "c")
        assertFalse(r.ok)
        assertEquals("native_core_unavailable", r.code)
        assertFalse(r.persisted)
    }

    @Test
    fun invalidLogIsQuarantinedAndAFreshChainStarts() {
        val core = object : io.github.victorious93.edlcommander.nativecore.NativeCore by UnlinkedNativeCore {
            override val isLinked = true
            override fun auditAppend(audit: String, timestamp: String, action: String, outcome: String, detail: String) =
                if (audit == "tampered") org.json.JSONObject().put("ok", false).put("code", "audit_invalid")
                else org.json.JSONObject().put("ok", true).put("audit", audit + "$action\n")
        }
        val store = InMemoryAuditStore("tampered")
        val r = AuditTrail(core, store).append("x", "y", "z")
        assertTrue(r.ok && r.persisted)
        assertEquals(listOf("tampered"), store.quarantined)
        assertEquals("x\n", store.read())
    }
}
