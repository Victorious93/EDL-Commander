package io.github.victorious93.edlcommander.audit

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Persistence for the hash-chained JSONL audit log produced by the native core. */
interface AuditStore {
    fun read(): String

    /** Replace the whole log. Must not leave a half-written file behind. */
    fun write(text: String)

    /** Move a log that fails verification aside (never delete it) so a fresh chain can start. */
    fun quarantine()
}

class InMemoryAuditStore(var text: String = "") : AuditStore {
    var quarantined: List<String> = emptyList()
        private set

    override fun read() = text
    override fun write(text: String) {
        this.text = text
    }

    override fun quarantine() {
        quarantined = quarantined + text
        text = ""
    }
}

/**
 * App-private file store. Logs above [maxBytes] are rotated to `<name>.1` (replacing an older
 * rotation) before the next write, so the log cannot grow without bound.
 */
class FileAuditStore(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AuditStore {
    override fun read(): String = if (file.isFile) file.readText(Charsets.UTF_8) else ""

    override fun write(text: String) {
        file.parentFile?.mkdirs()
        if (file.isFile && file.length() > maxBytes) {
            Files.move(file.toPath(), File(file.path + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun quarantine() {
        if (!file.isFile) return
        Files.move(file.toPath(), File("${file.path}.corrupt-${nowMillis()}").toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024
    }
}
