package io.github.victorious93.edlcommander.companion

import android.content.Context
import io.github.victorious93.edlcommander.BuildConfig
import io.github.victorious93.edlcommander.command.CommandRouter
import io.github.victorious93.edlcommander.nativecore.UnlinkedNativeCore
import io.github.victorious93.edlcommander.usb.AndroidUsbDeviceSource

object CompanionFactory {
    fun createRouter(context: Context): CommandRouter =
        CommandRouter(AndroidUsbDeviceSource(context), UnlinkedNativeCore, BuildConfig.VERSION_NAME)
}
