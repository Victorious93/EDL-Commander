package ai.edlcommander.companion.bridge

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the local remote-control switch in app-private SharedPreferences (allowBackup=false).
 * Default OFF. Only the in-app UI calls [setEnabled].
 */
class PreferencesRemoteControlSetting(context: Context) : RemoteControlSetting {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    companion object {
        private const val PREFS = "edl_remote_control"
        private const val KEY_ENABLED = "remote_control_enabled"
    }
}
