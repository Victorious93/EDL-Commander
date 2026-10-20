package ai.edlcommander.companion.bridge

/** Receiver-side classification of an actionRef. Never trusts the caller's `destructive` flag. */
data class ActionPolicy(
    val name: String,
    val kind: ActionKind,
    val remoteAllowed: Boolean,
    val requiresConfirm: Boolean,
    /** True if the companion actually implements this action (router command). */
    val known: Boolean,
)

object ActionClassifier {
    /**
     * Any actionRef with a segment naming one of these is destructive: flashing, bootloader, EDL
     * writes, erase and friends. Matching is per segment so `edl.write`, `bootloader.unlock` and
     * `partition.erase` are all caught.
     */
    private val DESTRUCTIVE_SEGMENTS = setOf(
        "flash", "firehose", "write", "erase", "format", "unlock", "lock", "bootloader",
        "fastboot", "program", "programmer", "patch", "wipe", "reset", "provision", "oem",
        "upload", "restore", "sideload", "reboot_edl", "rawprogram",
    )

    private val KNOWN: Map<String, ActionPolicy> = listOf(
        ActionPolicy("companion.capabilities", ActionKind.READ, remoteAllowed = true, requiresConfirm = false, known = true),
        ActionPolicy("companion.status", ActionKind.READ, remoteAllowed = true, requiresConfirm = false, known = true),
        ActionPolicy("usb.list", ActionKind.READ, remoteAllowed = true, requiresConfirm = false, known = true),
        // Shows Android's USB permission dialog; the user confirms on the device.
        ActionPolicy("usb.request_permission", ActionKind.ACTION, remoteAllowed = true, requiresConfirm = true, known = true),
        // Read-only Sahara command mode (serial, HW ID, OEM PK hash). Still needs a user-granted USB permission.
        // Fleet status tray: built from app state only, never touches USB/Sahara.
        ActionPolicy("node.status.get", ActionKind.READ, remoteAllowed = true, requiresConfirm = false, known = true),
        // Fleet UNIFIED_BACKUP: local only by default; remote callers are refused.
        ActionPolicy("config.export", ActionKind.ACTION, remoteAllowed = false, requiresConfirm = false, known = true),
        ActionPolicy("config.import", ActionKind.ACTION, remoteAllowed = false, requiresConfirm = true, known = true),
        ActionPolicy("sahara.identify", ActionKind.READ, remoteAllowed = true, requiresConfirm = false, known = true),
    ).associateBy { it.name }

    /** Representative destructive families advertised by capabilities.get (none exist in this build). */
    val ADVERTISED_DESTRUCTIVE = listOf("flash.*", "firehose.*", "edl.write", "erase.*", "bootloader.*", "unlock.*")

    fun isDestructiveName(actionRef: String): Boolean =
        actionRef.split('.').any { it in DESTRUCTIVE_SEGMENTS }

    fun classify(actionRef: String): ActionPolicy {
        if (isDestructiveName(actionRef)) {
            return ActionPolicy(actionRef, ActionKind.DESTRUCTIVE, remoteAllowed = false, requiresConfirm = true, known = false)
        }
        return KNOWN[actionRef] ?: ActionPolicy(actionRef, ActionKind.ACTION, remoteAllowed = false, requiresConfirm = true, known = false)
    }

    /** Effective kind: the receiver's classification, raised (never lowered) by the advisory flag. */
    fun effectiveKind(policy: ActionPolicy, advisoryDestructive: Boolean): ActionKind =
        if (advisoryDestructive && policy.kind.rank < ActionKind.DESTRUCTIVE.rank) ActionKind.DESTRUCTIVE else policy.kind

    fun catalog(): List<ActionPolicy> = KNOWN.values.toList() +
        ADVERTISED_DESTRUCTIVE.map { ActionPolicy(it, ActionKind.DESTRUCTIVE, remoteAllowed = false, requiresConfirm = true, known = false) }
}
