package ai.edlcommander.companion.bridge

import android.content.Context

/**
 * App preferences that `config.export`/`config.import` may carry. Separate file from the
 * remote-control switch, which is never part of a backup.
 */
class SharedPreferenceStore(context: Context, name: String = PREFS) : PreferenceStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun snapshot(): Map<String, Any?> = prefs.all.toMap()

    override fun apply(values: Map<String, Any?>) {
        val e = prefs.edit()
        values.forEach { (k, v) ->
            when (v) {
                null -> e.remove(k)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Double -> e.putFloat(k, v.toFloat())
                is String -> e.putString(k, v)
            }
        }
        e.commit()
    }

    companion object {
        const val PREFS = "edl_settings"

        /**
         * Exportable preference keys. This build has no user-tunable preferences besides the
         * remote-control switch, which is deliberately excluded, so the list is empty and an
         * export carries an empty `preferences` object. Add keys here as settings appear.
         */
        val EXPORTABLE_KEYS: Set<String> = emptySet()
    }
}
