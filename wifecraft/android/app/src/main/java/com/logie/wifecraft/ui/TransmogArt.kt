package com.logie.wifecraft.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.logie.wifecraft.engine.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A piece of the Transmogrify window's art: its pixels, the size the game lays it out at ([w] x [h], the window's units) and
 * its nine-slice margins in those units (left, top, right, bottom), or null.
 */
class TransmogPiece(val image: ImageBitmap, val w: Float, val h: Float, val nine: IntArray?)

/**
 * The game's own Transmogrify window art (Midnight's Blizzard_Transmog atlases, build 12.1.7.70892), cut on the phone.
 *
 * assets/transmog_art.json (tools/transmog_art.py, from the real UiTextureAtlas* tables) holds ids and boxes only: the sheets
 * (and the paging arrows, plain files) are fetched from wago.tools by id at that build into files/transmog-art when the window
 * is first opened (about 9 MB, once), each checked to be a BLP2 texture ([ArtCheck]), cut and kept in an observable map, so
 * that what draws a piece draws again when it arrives. Until then the window draws plain shapes in the same places. A sheet
 * that fails is tried again the next time the window opens. Nothing of the game is in the app or the repository.
 */
class TransmogArt private constructor(private val dir: File, private val doc: JSONObject) {
    val build: String = doc.getString("build")
    private val pieceDefs = doc.getJSONObject("pieces")
    private val sheetDefs = doc.getJSONObject("sheets")
    private val fileDefs = doc.optJSONObject("files") ?: JSONObject()
    private val pieces = mutableStateMapOf<String, TransmogPiece>()
    private val lock = Mutex()

    /** The piece, or null (not here yet, or not in the table). Observable. */
    fun piece(name: String): TransmogPiece? = pieces[name]

    private fun file(id: Int) = File(dir, "$id-$build")
    private fun cached(id: Int) = file(id).let { it.isFile && it.length() > 0 }

    /** Fetches what is missing and cuts everything not cut yet. Off the main thread; one at a time; idempotent. */
    suspend fun load(): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            var ok = true
            val bySheet = pieceDefs.keys().asSequence().toList().groupBy { pieceDefs.getJSONObject(it).getInt("f") }
            for ((sheetId, names) in bySheet) {
                if (names.all { it in pieces }) continue
                try {
                    if (!cached(sheetId)) download(sheetId)
                    if (!cutSheet(sheetId, names)) { file(sheetId).delete(); ok = false }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    ok = false
                }
            }
            for (name in fileDefs.keys()) {
                if (name in pieces) continue
                val def = fileDefs.getJSONObject(name)
                val id = def.getInt("f")
                try {
                    if (!cached(id)) download(id)
                    val bmp = decode(id)
                    if (bmp == null) { file(id).delete(); ok = false; continue }
                    val s = def.getJSONArray("s")
                    val img = bmp.asImageBitmap()
                    withContext(Dispatchers.Main) { pieces[name] = TransmogPiece(img, s.getDouble(0).toFloat(), s.getDouble(1).toFloat(), null) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    ok = false
                }
            }
            ok
        }
    }

    private suspend fun cutSheet(id: Int, names: List<String>): Boolean {
        val sheet = decode(id) ?: return false
        val cut = HashMap<String, TransmogPiece>()
        try {
            // the sheet's own size may differ from the atlas's (a smaller mip): the boxes are scaled to it
            val size = sheetDefs.getJSONArray(id.toString())
            val sx = sheet.width / size.getInt(0).toFloat(); val sy = sheet.height / size.getInt(1).toFloat()
            for (name in names) {
                val def = pieceDefs.getJSONObject(name)
                val b = def.getJSONArray("b"); val s = def.getJSONArray("s")
                val l = (b.getInt(0) * sx).toInt().coerceIn(0, sheet.width - 1); val t = (b.getInt(1) * sy).toInt().coerceIn(0, sheet.height - 1)
                val r = (b.getInt(2) * sx).toInt().coerceIn(l + 1, sheet.width); val bt = (b.getInt(3) * sy).toInt().coerceIn(t + 1, sheet.height)
                val part = Bitmap.createBitmap(sheet, l, t, r - l, bt - t).copy(Bitmap.Config.ARGB_8888, false)
                part.prepareToDraw()
                val n = def.optJSONArray("n")?.let { a -> IntArray(4) { a.getInt(it) } }
                cut[name] = TransmogPiece(part.asImageBitmap(), s.getDouble(0).toFloat(), s.getDouble(1).toFloat(), n)
            }
        } finally {
            sheet.recycle()
        }
        withContext(Dispatchers.Main) { pieces.putAll(cut) }
        return true
    }

    private fun decode(id: Int): Bitmap? {
        val f = file(id)
        if (!f.isFile) return null
        val px = Native.decodeBlp(f.path, 4096) ?: return null
        val w = px[0]; val h = px[1]
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 2, w, 0, 0, w, h)
        return bmp
    }

    private fun download(id: Int) {
        dir.mkdirs()
        val part = File(dir, "$id-$build.part")
        val c = URL("https://wago.tools/api/casc/$id?download&version=$build").openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("User-Agent", "wifecraft/1.0")
            c.connectTimeout = 30000; c.readTimeout = 120000
            ArtCheck.answer(c.responseCode, c.contentType)?.let { throw IOException("file $id: $it") }
            c.inputStream.use { i -> part.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        } finally {
            c.disconnect()
        }
        val head = ByteArray(4)
        val n = if (part.length() >= 4) part.inputStream().use { it.read(head) } else 0
        ArtCheck.body(part.length(), if (n == 4) head else ByteArray(0))?.let { part.delete(); throw IOException("file $id: $it") }
        if (!part.renameTo(file(id))) { part.delete(); throw IOException("file $id: not saved") }
    }

    companion object {
        /** From assets/transmog_art.json; the cache is the app's files/transmog-art. */
        fun open(context: Context): TransmogArt {
            val text = context.assets.open("transmog_art.json").bufferedReader().use { it.readText() }
            return TransmogArt(File(context.filesDir, "transmog-art"), JSONObject(text))
        }
    }
}
