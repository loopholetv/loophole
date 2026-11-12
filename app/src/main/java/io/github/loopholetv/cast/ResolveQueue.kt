package io.github.loopholetv.cast

/**
 * Work to do one at a time, each item at most once in the queue, at most [max] waiting. The platform
 * below API 34 resolves one service at a time, so resolves go through this.
 *
 * Every started item has a token; [finish] and [retry] only act on the current one, so a late
 * callback of an item that timed out or was cleared does nothing.
 */
internal class ResolveQueue<T>(private val max: Int) {
    class Started<T>(val token: Long, val key: String, val item: T)

    private val lock = Any()
    private var queued = LinkedHashMap<String, T>()
    private var current: Started<T>? = null
    private var tokens = 0L

    /** False when [key] is already waiting or running, or the queue is full. */
    fun offer(key: String, item: T): Boolean = synchronized(lock) {
        if (key == current?.key || key in queued || queued.size >= max) return false
        queued[key] = item
        true
    }

    /** The next item to run, or null while one is running or nothing waits. */
    fun start(): Started<T>? = synchronized(lock) {
        if (current != null) return null
        val first = queued.entries.firstOrNull() ?: return null
        queued.remove(first.key)
        Started(++tokens, first.key, first.value).also { current = it }
    }

    /** True when [token] is the running item, which is then done. */
    fun finish(token: Long): Boolean = synchronized(lock) {
        if (current?.token != token) return false
        current = null
        true
    }

    /** Like [finish], but the item goes back to the front of the queue. */
    fun retry(token: Long): Boolean = synchronized(lock) {
        val running = current?.takeIf { it.token == token } ?: return false
        current = null
        val rest = queued
        queued = LinkedHashMap<String, T>().also {
            it[running.key] = running.item
            it.putAll(rest)
        }
        true
    }

    /** Drops everything, the running item included. */
    fun clear() = synchronized(lock) {
        queued.clear()
        current = null
        tokens++
    }
}
