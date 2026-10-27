package ai.edlcommander.companion.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Reads VictorLaunch's fleet theme (read-only) and exposes it as Compose state.
 * Pins the provider by package + signing-cert SHA-256 before querying; any failure
 * (missing provider, pin mismatch, SecurityException, schemaVersion != 1) -> built-in default.
 */
object FleetThemeClient {
    private val uri: Uri = Uri.parse(FleetThemeContract.URI)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    var current by mutableStateOf(FleetThemeDefaults.DEFAULT)
        private set

    fun start(context: Context) {
        val app = context.applicationContext
        if (!started) {
            started = true
            try {
                app.contentResolver.registerContentObserver(uri, false, object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) = refresh(app)
                })
            } catch (e: SecurityException) {
                // Not allowed to observe: stay on the default.
            }
        }
        refresh(app)
    }

    /** Always re-queries the pinned provider; never trusts broadcast extras. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        io.execute {
            val snap = load(app)
            main.post { current = snap }
        }
    }

    private fun load(app: Context): FleetThemeSnapshot = try {
        val pm = app.packageManager
        val info = pm.resolveContentProvider(FleetThemeContract.AUTHORITY, 0)
        val pkg = info?.packageName
        val pin = FleetThemePins.decide(pkg, pkg?.let { certDigests(pm, it) }.orEmpty())
        FleetThemeDefaults.resolve(pin) { query(app) }
    } catch (e: Exception) {
        FleetThemeDefaults.DEFAULT
    }

    private fun query(app: Context): FleetParse {
        app.contentResolver.query(uri, null, null, null, null).use { c ->
            if (c == null || !c.moveToFirst()) return FleetParse.Rejected("no row")
            fun str(col: String): String? = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }
            val schemaIdx = c.getColumnIndex(FleetThemeContract.COL_SCHEMA)
            val schema = if (schemaIdx >= 0) c.getInt(schemaIdx) else null
            if (schema != FleetThemeContract.SCHEMA_VERSION) return FleetParse.Rejected("schemaVersion $schema")
            val fromJson = FleetThemeCodec.parseJson(str(FleetThemeContract.COL_JSON))
            if (fromJson is FleetParse.Ok) return fromJson
            val updIdx = c.getColumnIndex("updatedAtMs")
            return FleetThemeCodec.parseColumns(
                schema, str(FleetThemeContract.COL_MODE), if (updIdx >= 0) c.getLong(updIdx) else 0L, ::str,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun certDigests(pm: PackageManager, pkg: String): List<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        } ?: return emptyList()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
    }
}

/**
 * Exported because VictorLaunch sends an explicit broadcast to this package. It is only a
 * refresh trigger: extras are untrusted and ignored; we re-query the pinned provider.
 */
class FleetThemeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FleetThemeContract.ACTION_CHANGED) return
        FleetThemeClient.refresh(context)
    }
}
