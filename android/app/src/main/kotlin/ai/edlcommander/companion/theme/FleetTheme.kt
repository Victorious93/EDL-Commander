package ai.edlcommander.companion.theme

import org.json.JSONObject

/**
 * Fleet theme client, FLEET_THEME schemaVersion 1 (Victorious93/victorlaunch docs/specs/FLEET_THEME.md).
 * Pure JVM logic (parsing, schema check, pin decision, fallback) so it is unit-testable.
 * This app only READS VictorLaunch's provider; it never writes it.
 */
object FleetThemeContract {
    const val AUTHORITY = "com.victorious.launch.theme"
    const val URI = "content://$AUTHORITY/current"
    const val ACTION_CHANGED = "com.victorious.launch.action.FLEET_THEME_CHANGED"
    const val PROVIDER_PACKAGE = "com.victorious.launch"
    const val SCHEMA_VERSION = 1
    const val COL_SCHEMA = "schemaVersion"
    const val COL_MODE = "mode"
    const val COL_JSON = "json"
    val PALETTE_KEYS = listOf(
        "background", "surface", "primary", "secondary", "onBackground", "muted", "danger", "stale", "tile",
    )
}

/** Colours as ARGB ints (0xAARRGGBB). */
data class FleetPalette(
    val background: Int,
    val surface: Int,
    val primary: Int,
    val secondary: Int,
    val onBackground: Int,
    val muted: Int,
    val danger: Int,
    val stale: Int,
    val tile: Int,
)

data class FleetThemeSnapshot(
    val mode: String,
    val palette: FleetPalette,
    val updatedAtMs: Long,
    /** False for the built-in default (provider not pinned / unavailable / rejected). */
    val fromProvider: Boolean,
)

sealed interface FleetParse {
    data class Ok(val snapshot: FleetThemeSnapshot) : FleetParse
    data class Rejected(val reason: String) : FleetParse
}

object FleetThemeCodec {
    /** `#RRGGBB` (opaque) or `#AARRGGBB`; anything else is null. */
    fun parseHex(s: String?): Int? {
        if (s == null || !s.startsWith("#")) return null
        val h = s.substring(1)
        if (h.length != 6 && h.length != 8) return null
        if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val v = h.toLong(16)
        return (if (h.length == 6) v or 0xFF000000L else v).toInt()
    }

    fun paletteFrom(get: (String) -> String?): FleetPalette? {
        val c = FleetThemeContract.PALETTE_KEYS.map { parseHex(get(it)) ?: return null }
        return FleetPalette(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8])
    }

    /** Parses the `json` column. Rejects any schemaVersion other than 1 and malformed palettes. */
    fun parseJson(json: String?): FleetParse {
        if (json.isNullOrBlank()) return FleetParse.Rejected("empty")
        val o = try { JSONObject(json) } catch (e: Exception) { return FleetParse.Rejected("malformed json") }
        val v = o.optInt(FleetThemeContract.COL_SCHEMA, -1)
        if (v != FleetThemeContract.SCHEMA_VERSION) return FleetParse.Rejected("schemaVersion $v")
        val p = o.optJSONObject("palette") ?: return FleetParse.Rejected("no palette")
        val palette = paletteFrom { k -> if (p.has(k)) p.optString(k) else null }
            ?: return FleetParse.Rejected("bad palette")
        return FleetParse.Ok(FleetThemeSnapshot(o.optString("mode", ""), palette, o.optLong("updatedAtMs", 0L), true))
    }

    /** Fallback path when the row has no usable json: flat colour columns. */
    fun parseColumns(schemaVersion: Int?, mode: String?, updatedAtMs: Long, get: (String) -> String?): FleetParse {
        if (schemaVersion != FleetThemeContract.SCHEMA_VERSION) return FleetParse.Rejected("schemaVersion $schemaVersion")
        val palette = paletteFrom(get) ?: return FleetParse.Rejected("bad palette")
        return FleetParse.Ok(FleetThemeSnapshot(mode.orEmpty(), palette, updatedAtMs, true))
    }
}

sealed interface PinDecision {
    data object Trusted : PinDecision
    data class Untrusted(val reason: String) : PinDecision
}

object FleetThemePins {
    /**
     * Pinned SHA-256 digests (lowercase hex, no colons) of VictorLaunch's signing certificate.
     *
     * TODO(fleet-theme): FLEET_THEME.md (schemaVersion 1) does not publish VictorLaunch's
     * signing-cert SHA-256. Add the real release digest here once Don publishes it. Until then
     * this set is empty and the client FAILS CLOSED to the built-in default theme.
     * Never invent a value.
     */
    val VICTORLAUNCH_CERT_SHA256: Set<String> = emptySet()

    fun normalize(d: String) = d.replace(":", "").trim().lowercase()

    /**
     * Pure pin decision for the package that actually owns the provider authority.
     * Requires the exact package AND at least one of its signing certs in the pin set.
     */
    fun decide(
        providerPackage: String?,
        providerCertDigests: List<String>,
        pins: Set<String> = VICTORLAUNCH_CERT_SHA256,
    ): PinDecision {
        if (providerPackage == null) return PinDecision.Untrusted("provider missing")
        if (providerPackage != FleetThemeContract.PROVIDER_PACKAGE) return PinDecision.Untrusted("package mismatch")
        val pinned = pins.map(::normalize).toSet()
        if (pinned.isEmpty()) return PinDecision.Untrusted("no cert pinned")
        if (providerCertDigests.none { normalize(it) in pinned }) return PinDecision.Untrusted("cert mismatch")
        return PinDecision.Trusted
    }
}

object FleetThemeDefaults {
    /** Built-in default (the not-pinned fallback): the spec's NEON_FLEET reference palette. */
    val DEFAULT = FleetThemeSnapshot(
        mode = "DEFAULT",
        palette = FleetPalette(
            background = 0xFF0B0C12.toInt(), surface = 0xFF161821.toInt(), primary = 0xFF00E5FF.toInt(),
            secondary = 0xFFE840FF.toInt(), onBackground = 0xFFF5F7FF.toInt(), muted = 0xFF9AA3B5.toInt(),
            danger = 0xFFFF6B8A.toInt(), stale = 0xFFFFC14D.toInt(), tile = 0xCC1A1D2B.toInt(),
        ),
        updatedAtMs = 0L,
        fromProvider = false,
    )

    /** Pin + parse outcome -> theme to apply. Anything but Trusted + Ok yields [DEFAULT]. */
    fun resolve(pin: PinDecision, parse: () -> FleetParse): FleetThemeSnapshot {
        if (pin !is PinDecision.Trusted) return DEFAULT
        return when (val r = try { parse() } catch (e: SecurityException) { FleetParse.Rejected("security") }) {
            is FleetParse.Ok -> r.snapshot
            is FleetParse.Rejected -> DEFAULT
        }
    }
}
