// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.logie.warcraft.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Pictures from the web (add-on logos and screenshots): kept in memory and in the app's cache. */
object RemoteImages {
    private val memory = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    /** A few at a time is plenty for a page of logos. */
    private val fetching = Semaphore(4)

    /** The picture at [url], no bigger than [maxPx] on its longer side; null when it can't be had. */
    suspend fun load(context: Context, url: String, maxPx: Int): ImageBitmap? {
        val key = "$maxPx $url"
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = cached(context, url)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
                val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@runCatching null
                bitmap.asImageBitmap().also { memory.put(key, it) }
            }.getOrNull()
        }
    }

    private suspend fun cached(context: Context, url: String): File {
        val dir = File(context.cacheDir, "images")
        val file = File(dir, sha1(url))
        if (file.isFile) return file
        fetching.withPermit {
            if (file.isFile) return file
            dir.mkdirs()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "WoW335Android/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            }
            try {
                if (connection.responseCode !in 200..299) throw java.io.IOException("HTTP ${connection.responseCode}")
                val part = File(dir, file.name + "." + System.nanoTime() + ".part")
                connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                part.renameTo(file)
            } finally {
                connection.disconnect()
            }
            trim(dir)
        }
        return file
    }

    /** The cache stays under about 60 MB: the oldest pictures go first. */
    private fun trim(dir: File) {
        val files = dir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total < 60L * 1024 * 1024) break
            total -= file.length()
            file.delete()
        }
    }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** A picture from the web, with [placeholder] until it's there (and if it never is). */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    maxPx: Int = 256,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(WowColors.Deep)) },
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, url, maxPx) {
        value = url?.let { RemoteImages.load(context, it, maxPx) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val shown = image
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = contentScale, modifier = Modifier.matchParentSize())
        } else {
            Box(Modifier.matchParentSize()) { placeholder() }
        }
    }
}
