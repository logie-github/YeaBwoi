package com.logie.wifecraft.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.logie.wifecraft.engine.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The editor's build-mode art: pieces of the game's current interface (the housing editor's tool buttons and picture
 * cards, the spellbook's page and side tabs, the talent tree's rings, parchment, ribbons, the gold glyphs) and ~60 item and
 * spell icons, cut on the phone and softened with [WarTint] (warmer, a little less saturated, the darks lifted).
 *
 * assets/ui_art.json (tools/ui_art.py, from the real UiTextureAtlas* tables and ManifestInterfaceData at the app's build)
 * holds ids and boxes only. The sheets and icons are fetched from wago.tools by id at that build into [dir] (about 58 MB,
 * once), cut and tinted, kept in memory as ImageBitmaps. Until they are here (or when one can't be fetched) [piece] and
 * [icon] give null and WarWidgets draws plainly in the same palette. Nothing of the game is in the app or the repository.
 */
class WarArt private constructor(private val dir: File, private val doc: JSONObject) {
    val build: String = doc.getString("build")
    private val pieceDefs = doc.getJSONObject("pieces")
    private val sheetDefs = doc.getJSONObject("sheets")
    private val iconDefs = doc.getJSONObject("icons")
    private val pieces = HashMap<String, ImageBitmap>()
    private val icons = HashMap<String, ImageBitmap>()

    /** Bumps as pieces arrive, so that what reads them draws again. */
    var version by mutableIntStateOf(0)
        private set

    @Volatile var error: String? = null
        private set

    fun has(name: String): Boolean = pieceDefs.has(name)

    /** An atlas piece, cut and warmed, or null. Reads [version]. */
    fun piece(name: String): ImageBitmap? {
        @Suppress("UNUSED_EXPRESSION") version
        return synchronized(pieces) { pieces[name] }
    }

    /** An icon by its key ("tab.props", "cat.lighting", "tool.focus"...: WarIcons), its own black edge cut off, or null. */
    fun icon(key: String?): ImageBitmap? {
        key ?: return null
        @Suppress("UNUSED_EXPRESSION") version
        return synchronized(icons) { icons[key] }
    }

    /** Every file the art needs: the sheets and the icons. */
    fun fileIds(): List<Int> =
        sheetDefs.keys().asSequence().map { it.toInt() }.toList() +
            iconDefs.keys().asSequence().map { iconDefs.getJSONObject(it).getInt("f") }.distinct()

    private fun file(id: Int) = File(dir, "$id-$build")

    private val lock = Mutex()
    @Volatile private var done = false

    /**
     * Fetches what is missing, then cuts and warms every piece (the sheets one by one, so few are decoded at once). Off the
     * main thread; one load at a time, and none again once everything is cut.
     */
    suspend fun load(fetch: Boolean = true): Boolean = lock.withLock { if (done) true else withContext(Dispatchers.IO) { loadNow(fetch) } }

    private fun loadNow(fetch: Boolean): Boolean {
        var failed: String? = null
        val warm = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(WarTint.MATRIX)) }
        // icons first: they are small and the bar shows them at once
        val iconsByFile = HashMap<Int, MutableList<String>>()
        for (k in iconDefs.keys()) iconsByFile.getOrPut(iconDefs.getJSONObject(k).getInt("f")) { ArrayList() }.add(k)
        for ((id, keys) in iconsByFile) {
            try {
                if (fetch) ensure(id)
                val bmp = decode(id) ?: continue
                val k = (bmp.width * WarSize.ICON_INSET).toInt()
                val cut = Bitmap.createBitmap(bmp, k, k, bmp.width - 2 * k, bmp.height - 2 * k).asImageBitmap()
                synchronized(icons) { for (key in keys) icons[key] = cut }
            } catch (e: Exception) {
                failed = e.message ?: e.javaClass.simpleName
            }
        }
        synchronized(icons) { version++ }
        val bySheet = HashMap<Int, MutableList<String>>()
        for (name in pieceDefs.keys()) bySheet.getOrPut(pieceDefs.getJSONObject(name).getInt("f")) { ArrayList() }.add(name)
        for ((sheetId, names) in bySheet) {
            try {
                if (fetch) ensure(sheetId)
                val sheet = decode(sheetId) ?: continue
                // the sheet's own size may differ from the atlas's (a smaller mip): scale the boxes
                val size = sheetDefs.getJSONArray(sheetId.toString())
                val sx = sheet.width / size.getInt(0).toFloat(); val sy = sheet.height / size.getInt(1).toFloat()
                for (name in names) {
                    val b = pieceDefs.getJSONObject(name).getJSONArray("b")
                    val l = (b.getInt(0) * sx).toInt().coerceIn(0, sheet.width - 1); val t = (b.getInt(1) * sy).toInt().coerceIn(0, sheet.height - 1)
                    val r = (b.getInt(2) * sx).toInt().coerceIn(l + 1, sheet.width); val bt = (b.getInt(3) * sy).toInt().coerceIn(t + 1, sheet.height)
                    val out = Bitmap.createBitmap(r - l, bt - t, Bitmap.Config.ARGB_8888)
                    Canvas(out).drawBitmap(Bitmap.createBitmap(sheet, l, t, r - l, bt - t), 0f, 0f, if (name in PLAIN) null else warm)
                    synchronized(pieces) { pieces[name] = out.asImageBitmap() }
                }
                sheet.recycle()
                synchronized(pieces) { version++ }
            } catch (e: Exception) {
                failed = e.message ?: e.javaClass.simpleName
            }
        }
        error = failed
        done = failed == null
        return done
    }

    private fun ensure(id: Int) {
        if (!file(id).let { it.isFile && it.length() > 0 }) download(id)
    }

    private fun decode(id: Int): Bitmap? {
        val f = file(id)
        if (!f.isFile) return null
        val px = Native.decodeBlp(f.path, 4096) ?: return null
        val w = px[0]; val h = px[1]
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 2, w, 0, 0, w, h)
        return bmp
    }

    private fun download(id: Int) {
        dir.mkdirs()
        val url = URL("https://wago.tools/api/casc/$id?download&version=$build")
        var last: Exception? = null
        for (attempt in 1..4) {
            try {
                val c = url.openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "wifecraft/1.0")
                c.connectTimeout = 30000; c.readTimeout = 120000
                if (c.responseCode != 200) throw java.io.IOException("file $id: HTTP ${c.responseCode}")
                val part = File(dir, "$id-$build.part")
                c.inputStream.use { i -> part.outputStream().use { o -> i.copyTo(o) } }
                if (!part.renameTo(file(id))) throw java.io.IOException("file $id: not saved")
                return
            } catch (e: Exception) {
                last = e
                Thread.sleep(1500L * attempt)
            }
        }
        throw last ?: java.io.IOException("file $id")
    }

    companion object {
        /** Pieces drawn as the game has them (masks: their alpha is all that is read). */
        private val PLAIN = setOf("house-chest-list-item-mask", "ui-hud-actionbar-iconframe-mask")

        /** From assets/ui_art.json; the cache is the app's files/war-art. */
        fun open(context: Context): WarArt {
            val text = context.assets.open("ui_art.json").bufferedReader().use { it.readText() }
            return WarArt(File(context.filesDir, "war-art"), JSONObject(text))
        }
    }
}

/** The build-mode art for the editor's screens (null: plain drawing in the same palette). */
val LocalWar = staticCompositionLocalOf<WarArt?> { null }
