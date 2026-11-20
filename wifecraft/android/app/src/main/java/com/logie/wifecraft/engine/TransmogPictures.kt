package com.logie.wifecraft.engine

import android.graphics.Bitmap
import com.logie.wifecraft.creator.CardOrder
import com.logie.wifecraft.creator.CardQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Transmog window's card pictures, made as the page on show needs them: the queue (creator/CardQueue: the page's cards in
 * grid order, two at a time, what an earlier page still had waiting dropped) runs here on the main thread; each card is
 * [Thumbs.card] (memory, disk, else fetched and drawn on the GL thread, one at a time). A card that couldn't be made is not
 * asked for again while this lives (one per opening of the window). Main thread for every call; [show]'s answers come on it.
 */
class TransmogPictures(private val thumbs: Thumbs) {
    private val queue = CardQueue<CardOrder>(PARALLEL) { it.key }
    private val failed = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var onReady: ((String, Bitmap) -> Unit)? = null
    private var closed = false

    init {
        scope.launch { runCatching { thumbs.pruneCards() } }
    }

    /** A card's picture if it is in memory now (the grid's first frame shows it at once). */
    fun cached(key: String): Bitmap? = thumbs.cachedCard(key)

    /** The page on show is [page] (grid order); each picture made for it comes to [ready] (main thread). Empty: nothing more. */
    fun show(page: List<CardOrder>, ready: (String, Bitmap) -> Unit) {
        if (closed) return
        onReady = ready
        queue.show(page) { k -> k in failed || thumbs.cachedCard(k) != null }
        pump()
    }

    private fun pump() {
        while (!closed) {
            val order = queue.next() ?: return
            scope.launch {
                val bmp = try {
                    thumbs.card(order)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                main.post {
                    queue.finished(order.key)
                    if (bmp == null) failed += order.key else onReady?.invoke(order.key, bmp)
                    pump()
                }
            }
        }
    }

    /** The window is gone: nothing more is made (what is being fetched stops; a picture already on the disk stays there). */
    fun close() {
        closed = true
        onReady = null
        queue.clear()
        scope.cancel()
    }

    companion object {
        /** Two at once: one's files are fetched while the other is drawn (the drawing itself is one at a time: Thumbs.card). */
        const val PARALLEL = 2
    }
}
