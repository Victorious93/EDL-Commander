package ai.edlcommander.companion

import ai.edlcommander.companion.theme.FleetParse
import ai.edlcommander.companion.theme.FleetThemeCodec
import ai.edlcommander.companion.theme.FleetThemeDefaults
import ai.edlcommander.companion.theme.FleetThemePins
import ai.edlcommander.companion.theme.PinDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetThemeTest {
    private fun json(version: Int = 1, primary: String = "#FF00E5FF") = """
        {"schemaVersion":$version,"mode":"LIGHT","iconStyle":"SYSTEM","iconPackPackage":"","updatedAtMs":42,
         "unknownField":true,
         "palette":{"background":"#FFFFFF","surface":"#FF161821","primary":"$primary","secondary":"#FFE840FF",
         "onBackground":"#FFF5F7FF","muted":"#FF9AA3B5","danger":"#FFFF6B8A","stale":"#FFFFC14D","tile":"#CC1A1D2B"}}
    """.trimIndent()

    private val pin = "AB:CD:" + "00".repeat(30).chunked(2).joinToString(":")

    @Test fun hexParsing() {
        assertEquals(0xFF112233.toInt(), FleetThemeCodec.parseHex("#112233"))
        assertEquals(0xCC1A1D2B.toInt(), FleetThemeCodec.parseHex("#CC1A1D2B"))
        assertNull(FleetThemeCodec.parseHex("112233"))
        assertNull(FleetThemeCodec.parseHex("#12345"))
        assertNull(FleetThemeCodec.parseHex("#GG112233"))
        assertNull(FleetThemeCodec.parseHex(null))
    }

    @Test fun parsesV1Json() {
        val r = FleetThemeCodec.parseJson(json()) as FleetParse.Ok
        assertEquals("LIGHT", r.snapshot.mode)
        assertEquals(42L, r.snapshot.updatedAtMs)
        assertEquals(0xFFFFFFFF.toInt(), r.snapshot.palette.background)
        assertTrue(r.snapshot.fromProvider)
    }

    @Test fun rejectsOtherSchemaVersions() {
        for (v in listOf(0, 2, -1)) assertTrue(FleetThemeCodec.parseJson(json(v)) is FleetParse.Rejected)
        assertTrue(FleetThemeCodec.parseJson("""{"palette":{}}""") is FleetParse.Rejected)
        assertTrue(FleetThemeCodec.parseColumns(2, "DARK", 0) { "#FFFFFF" } is FleetParse.Rejected)
    }

    @Test fun rejectsMalformed() {
        assertTrue(FleetThemeCodec.parseJson("not json") is FleetParse.Rejected)
        assertTrue(FleetThemeCodec.parseJson("") is FleetParse.Rejected)
        assertTrue(FleetThemeCodec.parseJson(json(primary = "red")) is FleetParse.Rejected)
    }

    @Test fun columnsFallback() {
        assertTrue(FleetThemeCodec.parseColumns(1, "DARK", 5) { "#FF000000" } is FleetParse.Ok)
        assertTrue(FleetThemeCodec.parseColumns(1, "DARK", 5) { null } is FleetParse.Rejected)
    }

    @Test fun pinDecisions() {
        val pins = setOf(pin)
        val digest = pin.replace(":", "").lowercase()
        assertEquals(PinDecision.Trusted, FleetThemePins.decide("com.victorious.launch", listOf(digest), pins))
        assertTrue(FleetThemePins.decide(null, listOf(digest), pins) is PinDecision.Untrusted)
        assertTrue(FleetThemePins.decide("com.evil.launch", listOf(digest), pins) is PinDecision.Untrusted)
        assertTrue(FleetThemePins.decide("com.victorious.launch", listOf("00".repeat(32)), pins) is PinDecision.Untrusted)
        assertTrue(FleetThemePins.decide("com.victorious.launch", emptyList(), pins) is PinDecision.Untrusted)
    }

    @Test fun shippedPinsAcceptOnlyVictorLaunchDebugCert() {
        val expected = "B9:9A:AF:E2:C1:41:8B:5D:64:AA:0D:4B:AC:61:FC:F7:A7:BA:81:44:35:E0:EF:89:E8:AA:55:BE:56:4E:4A:69"
        assertEquals(setOf(expected), FleetThemePins.VICTORLAUNCH_CERT_SHA256)
        val pinned = expected.replace(":", "").lowercase()
        assertEquals(PinDecision.Trusted, FleetThemePins.decide("com.victorious.launch", listOf(pinned)))
        assertEquals(PinDecision.Trusted, FleetThemePins.decide("com.victorious.launch", listOf(expected)))
        assertEquals(PinDecision.Trusted, FleetThemePins.decide("com.victorious.launch", listOf("00".repeat(32), pinned)))
        assertTrue(FleetThemePins.decide("com.victorious.launch", listOf("00".repeat(32))) is PinDecision.Untrusted)
        // EDL Commander's own debug cert must not be accepted as VictorLaunch.
        val edl = "E5:60:B6:92:BD:E6:51:9E:B7:CB:B5:2C:E4:DC:91:5A:19:E4:41:A0:1A:5D:03:68:82:71:AC:99:78:8A:58:C8"
        assertTrue(FleetThemePins.decide("com.victorious.launch", listOf(edl)) is PinDecision.Untrusted)
        assertTrue(FleetThemePins.decide("com.victorious.launch", emptyList()) is PinDecision.Untrusted)
        assertTrue(FleetThemePins.decide("com.evil.launch", listOf(pinned)) is PinDecision.Untrusted)
    }

    @Test fun fallbackToDefault() {
        val ok = { FleetThemeCodec.parseJson(json()) }
        assertSame(FleetThemeDefaults.DEFAULT, FleetThemeDefaults.resolve(PinDecision.Untrusted("x"), ok))
        assertSame(FleetThemeDefaults.DEFAULT, FleetThemeDefaults.resolve(PinDecision.Trusted) { FleetThemeCodec.parseJson(json(2)) })
        assertSame(FleetThemeDefaults.DEFAULT, FleetThemeDefaults.resolve(PinDecision.Trusted) { throw SecurityException("not pinned") })
        assertFalse(FleetThemeDefaults.DEFAULT.fromProvider)
        assertTrue(FleetThemeDefaults.resolve(PinDecision.Trusted, ok).fromProvider)
    }

    @Test fun untrustedPinNeverQueries() {
        var called = false
        FleetThemeDefaults.resolve(PinDecision.Untrusted("no cert pinned")) { called = true; FleetThemeCodec.parseJson(json()) }
        assertFalse(called)
    }
}
