package ai.edlcommander.companion.bridge

/**
 * The local "allow remote control" switch. OFF by default. Only the on-device UI may change it;
 * there is deliberately no AIDL command that writes it.
 */
interface RemoteControlSetting {
    fun isEnabled(): Boolean
}

/** Mutable in-memory implementation (tests). */
class InMemoryRemoteControlSetting(@Volatile var enabled: Boolean = false) : RemoteControlSetting {
    override fun isEnabled(): Boolean = enabled
}
