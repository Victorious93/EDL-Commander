package ai.edlcommander.companion.bridge

/**
 * Remembers (sourceNodeRef, nonce) pairs for [retentionMs]. Thread-safe. Fails closed: when the
 * cache is full of entries that are still live, [checkAndRecord] reports [Outcome.FULL] instead of
 * evicting something that could still be replayed.
 */
class NonceCache(
    private val retentionMs: Long = BridgeProtocol.NONCE_RETENTION_MS,
    private val capacity: Int = BridgeProtocol.NONCE_CACHE_CAPACITY,
) {
    enum class Outcome { FRESH, DUPLICATE, FULL }

    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun checkAndRecord(sourceNodeRef: String, nonce: String, nowMs: Long): Outcome {
        prune(nowMs)
        val key = sourceNodeRef.length.toString() + ":" + sourceNodeRef + "|" + nonce
        if (seen.containsKey(key)) return Outcome.DUPLICATE
        if (seen.size >= capacity) return Outcome.FULL
        seen[key] = nowMs
        return Outcome.FRESH
    }

    @Synchronized
    fun size(): Int = seen.size

    private fun prune(nowMs: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            // Insertion order == time order (if the clock steps backwards, entries are kept longer).
            if (nowMs - e.value >= retentionMs) it.remove() else break
        }
    }
}
