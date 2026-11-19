package com.logie.wifecraft.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.logie.wifecraft.data.GameFiles
import com.logie.wifecraft.engine.Native
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** The one pool every picture decode of the menus runs on (two threads: the main thread never decodes). */
object Decoders {
    val pool: java.util.concurrent.ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "wifecraft-decode").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
}

/**
 * The game's item and spell icons (the Transmog panel's tiles, the creator's race buttons): fetched when missing (four at
 * a time, on the IO pool), decoded on [Decoders.pool] into an [ImageCache] of up to 24 MB, newest asked first, never on
 * the main thread. The composition only reads what is decoded ([peek]).
 */
class GameIcons(private val files: GameFiles) {
    private val cache = ImageCache<Int, ImageBitmap>(
        maxBytes = 24L shl 20,
        sizeOf = { it.width.toLong() * it.height * 4 },
        run = { Decoders.pool.execute(it) },
        decode = { id -> decode(id) },
    )
    private val fetching = Semaphore(4)

    /** A decoded icon, or null (never decodes: safe in composition). */
    fun peek(id: Int): ImageBitmap? = if (id > 0) cache.peek(id) else null

    suspend fun load(id: Int): ImageBitmap? {
        if (id <= 0) return null
        cache.peek(id)?.let { return it }
        val here = withContext(Dispatchers.IO) {
            if (files.has(id)) true else fetching.withPermit {
                try { files.fetchNow(id); files.has(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            }
        }
        if (!here) return null
        return suspendCancellableCoroutine { cont ->
            val ticket = cache.request(id) { if (cont.isActive) cont.resume(it) }
            cont.invokeOnCancellation { ticket.cancel() }
        }
    }

    private fun decode(id: Int): ImageBitmap? {
        val pixels = Native.decodeBlp(files.file(id).path, 128) ?: return null
        val width = pixels[0]
        val height = pixels[1]
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 2, width, 0, 0, width, height)
            prepareToDraw()
        }.asImageBitmap()
    }
}

/**
 * An icon for a cell: the decoded one at once when it is cached (no placeholder flash when a cell scrolls back in), else
 * null until it is decoded off the main thread. The request is cancelled when the cell leaves composition or [id] changes
 * (and a changed id never shows the previous icon).
 */
@Composable
fun gameIcon(icons: GameIcons?, id: Int): ImageBitmap? {
    val state = remember(icons, id) { mutableStateOf(icons?.peek(id)) }
    LaunchedEffect(icons, id) {
        if (state.value == null && icons != null && id > 0) state.value = icons.load(id)
    }
    return state.value
}

