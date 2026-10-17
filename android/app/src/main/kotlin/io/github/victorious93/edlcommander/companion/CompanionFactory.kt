package io.github.victorious93.edlcommander.companion

import android.content.Context
import io.github.victorious93.edlcommander.BuildConfig
import io.github.victorious93.edlcommander.audit.AuditTrail
import io.github.victorious93.edlcommander.audit.FileAuditStore
import io.github.victorious93.edlcommander.command.CommandRouter
import io.github.victorious93.edlcommander.nativecore.JniNativeCore
import io.github.victorious93.edlcommander.nativecore.NativeCore
import io.github.victorious93.edlcommander.usb.AndroidEdlSessionOpener
import io.github.victorious93.edlcommander.usb.AndroidUsbDeviceSource
import io.github.victorious93.edlcommander.usb.AndroidUsbPermissionGate
import io.github.victorious93.edlcommander.usb.UsbPermissionTracker
import java.io.File
import java.util.concurrent.Executors

/**
 * Process-wide wiring shared by the UI and the bound service, so USB permission state, the audit
 * log and the device lock are the same no matter who issues a command.
 */
class CompanionRuntime private constructor(context: Context) {
    val nativeCore: NativeCore = JniNativeCore.load()
    val audit = AuditTrail(nativeCore, FileAuditStore(File(context.applicationContext.filesDir, "audit/edl-audit.jsonl")))
    val router: CommandRouter

    init {
        val app = context.applicationContext
        // Off the main thread: the audit lock is held for the whole duration of an identify run.
        val auditWorker = Executors.newSingleThreadExecutor()
        val permissions = AndroidUsbPermissionGate(app, UsbPermissionTracker()) { deviceName, granted ->
            auditWorker.execute { audit.append("usb.permission", if (granted) "granted" else "denied", "device=$deviceName") }
        }
        router = CommandRouter(
            usb = AndroidUsbDeviceSource(app),
            nativeCore = nativeCore,
            appVersion = BuildConfig.VERSION_NAME,
            audit = audit,
            permissions = permissions,
            sessions = AndroidEdlSessionOpener(app),
        )
    }

    companion object {
        @Volatile
        private var instance: CompanionRuntime? = null

        fun get(context: Context): CompanionRuntime = instance ?: synchronized(this) {
            instance ?: CompanionRuntime(context).also { instance = it }
        }
    }
}

object CompanionFactory {
    fun createRouter(context: Context): CommandRouter = CompanionRuntime.get(context).router
}
