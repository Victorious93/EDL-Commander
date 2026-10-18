package ai.edlcommander.companion

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Bound service that Droidcommand-AI binds to via action [ACTION_BIND]. Access is guarded by the
 * signature-level permission in the manifest and by [SignatureCallerVerifier] on every call.
 */
class EdlCompanionService : Service() {
    private val binder by lazy {
        CompanionBinder(SignatureCallerVerifier(this), CompanionFactory.createRouter(this))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        const val ACTION_BIND = "ai.edlcommander.companion.BIND"
    }
}
