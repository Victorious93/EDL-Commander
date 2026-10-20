package ai.edlcommander.companion

import android.content.Context
import ai.edlcommander.companion.BuildConfig
import ai.edlcommander.companion.audit.AuditTrail
import ai.edlcommander.companion.audit.FileAuditStore
import ai.edlcommander.companion.bridge.NodeStatusProvider
import ai.edlcommander.companion.bridge.PreferencesRemoteControlSetting
import ai.edlcommander.companion.bridge.RemoteDispatcher
import ai.edlcommander.companion.command.CommandRouter
import ai.edlcommander.companion.nativecore.JniNativeCore
import ai.edlcommander.companion.nativecore.NativeCore
import ai.edlcommander.companion.usb.AndroidEdlSessionOpener
import ai.edlcommander.companion.usb.AndroidUsbDeviceSource
import ai.edlcommander.companion.usb.AndroidUsbPermissionGate
import ai.edlcommander.companion.usb.UsbPermissionTracker
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

    /** Local remote-control switch; OFF by default, written only by the in-app UI. */
    val remoteControl = PreferencesRemoteControlSetting(context.applicationContext)
    val remote: RemoteDispatcher

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
        val nodeStatus = NodeStatusProvider(
            nodeId = BuildConfig.APPLICATION_ID,
            packageName = app.packageName,
            nativeCoreLinked = { nativeCore.isLinked },
        )
        remote = RemoteDispatcher(router, remoteControl, nodeStatus)
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
