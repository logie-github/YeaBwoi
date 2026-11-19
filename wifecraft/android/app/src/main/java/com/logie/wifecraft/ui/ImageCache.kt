package com.logie.wifecraft.ui

// Pure Kotlin (no Android, no coroutines): copied alone into the JVM tests (unit-tests: pureUi). GameIcons wires it to
// the phone's bitmaps and a shared two-thread decoder.

/**
 * Pictures decoded off the main thread into a cache bounded by bytes, for grids that scroll fast (the Transmog panel's
 * icons, the creator's race icons):
 * - [peek] is what a composition reads: an already-decoded picture or null, never a decode;
 * - [request] asks for one: cached, [onReady] is called at once; else it is queued, and [onReady] is called with the
 *   picture (or null: it couldn't be made) on the worker thread that made it;
 * - the same key asked twice is decoded once (both are told);
 * - the newest request is decoded first (a fast scroll does not make the cells now in sight wait behind those scrolled
 *   past), at most [maxQueued] wait (the oldest are dropped), and a [Ticket.cancel]led request no one else waits for is
 *   never decoded (a cell that left the screen);
 * - least recently used pictures go once the cache holds more than [maxBytes]; nothing is recycled (a dropped bitmap may
 *   still be drawn: the garbage collector frees it once nothing draws it).
 *
 * [run] runs one step of the decoder on a worker (the phone: a shared pool of two threads; the tests: a list they run).
 */
class ImageCache<K : Any, V : Any>(
    val maxBytes: Long,
    private val sizeOf: (V) -> Long,
    private val run: (Runnable) -> Unit,
    private val decode: (K) -> V?,
    private val maxQueued: Int = 64,
) {
    /** A request's handle: [cancel] when the picture is no longer wanted (the cell left composition). */
    fun interface Ticket { fun cancel() }

    private class Waiting<V>(val onReady: (V?) -> Unit)

    private val lru = LinkedHashMap<K, V>(64, 0.75f, true)
    private var bytes = 0L
    /** Waiting to be decoded, oldest first (the decoder takes the last). */
    private val queue = ArrayDeque<K>()
    private val waiters = HashMap<K, MutableList<Waiting<V>>>()
    private val running = HashSet<K>()

    /** Decodes done (for the measurements and tests). */
    @Volatile var decodes = 0
        private set

    @Synchronized fun peek(key: K): V? = lru[key]

    @Synchronized fun sizeBytes(): Long = bytes

    @Synchronized fun queued(): Int = queue.size

    fun request(key: K, onReady: (V?) -> Unit): Ticket {
        val waiting = Waiting<V>(onReady)
        val cached: V?
        synchronized(this) {
            cached = lru[key]
            if (cached == null) {
                waiters.getOrPut(key) { mutableListOf() }.add(waiting)
                if (key !in running) {
                    // asked again: to the front (the newest is decoded first)
                    queue.remove(key)
                    queue.addLast(key)
                    while (queue.size > maxQueued) {
                        val dropped = queue.removeFirst()
                        waiters.remove(dropped)
                    }
                }
            }
        }
        if (cached != null) {
            onReady(cached)
            return Ticket { }
        }
        run(Runnable { step() })
        return Ticket { cancel(key, waiting) }
    }

    @Synchronized
    private fun cancel(key: K, waiting: Waiting<V>) {
        val list = waiters[key] ?: return
        list.remove(waiting)
        if (list.isEmpty()) {
            waiters.remove(key)
            // not begun: never decoded (one already decoding finishes and is kept)
            queue.remove(key)
        }
    }

    /** One decoder step: the newest waiting key, if any (each request submits one step; extra steps find nothing). */
    private fun step() {
        val key = synchronized(this) {
            val k = queue.removeLastOrNull() ?: return
            running += k
            k
        }
        val value = try {
            decode(key)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            synchronized(this) { trimTo(maxBytes / 2) }
            null
        }
        val told: List<Waiting<V>>
        synchronized(this) {
            decodes++
            running -= key
            if (value != null) put(key, value)
            told = waiters.remove(key).orEmpty()
        }
        for (w in told) w.onReady(value)
    }

    private fun put(key: K, value: V) {
        lru.put(key, value)?.let { bytes -= sizeOf(it) }
        bytes += sizeOf(value)
        trimTo(maxBytes)
    }

    private fun trimTo(limit: Long) {
        val it = lru.entries.iterator()
        while (bytes > limit && it.hasNext()) {
            val e = it.next()
            bytes -= sizeOf(e.value)
            it.remove()
        }
    }

    /** Forgets every picture (the memory is low): those drawn now stay drawn until they're asked for again. */
    @Synchronized
    fun clear() {
        lru.clear()
        bytes = 0
    }
}
