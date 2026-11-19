package com.logie.wifecraft.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.logie.wifecraft.data.GameFiles
import com.logie.wifecraft.engine.Native
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class GameIcons(private val files: GameFiles) {
    private val cache = LruCache<Int, ImageBitmap>(160)
    private val fetching = Semaphore(4)

    suspend fun load(id: Int): ImageBitmap? = fetching.withPermit {
        cache[id]?.let { return@withPermit it }
        withContext(Dispatchers.IO) {
            try {
                files.fetchNow(id)
                Native.decodeBlp(files.file(id).path, 128)?.let { pixels ->
                    val width = pixels[0]
                    val height = pixels[1]
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                        setPixels(pixels, 2, width, 0, 0, width, height)
                    }.asImageBitmap().also { cache.put(id, it) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
    }
}

@Composable
fun gameIcon(icons: GameIcons?, id: Int): ImageBitmap? {
    val image by produceState<ImageBitmap?>(null, icons, id) {
        value = if (id > 0) icons?.load(id) else null
    }
    return image
}
