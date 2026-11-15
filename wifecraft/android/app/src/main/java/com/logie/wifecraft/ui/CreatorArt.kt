package com.logie.wifecraft.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.logie.wifecraft.engine.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One cut piece of Shadowlands' art: its pixels, and the size Shadowlands draws it at (UiTextureAtlasMember's override or its own box), in its 1080-high units. */
class ArtPiece(val image: ImageBitmap, val drawn: IntSize)

/**
 * Shadowlands' character-creator art (build 9.2.7.45745), cut on the phone from the game's own sheets.
 *
 * assets/creator_art.json (made by tools/creator_art.py from the real UiTextureAtlas* tables) holds ids and boxes only:
 * which sheet (a file id), where the piece is in it, the size Shadowlands draws it at. The sheets are fetched from wago.tools
 * by id at that build (a sheet is repacked from build to build, so the table's build is the sheet's) into [dir], cut piece
 * by piece, and each round button's picture is trimmed with the game's own round mask (TempPortraitAlphaMask) exactly as
 * sl_ui_art.py does for Wrath++: "<name>.<kind>" for kind category, race, sex. Nothing of the game is in the app or the repository.
 */
class ArtAtlas private constructor(private val dir: File, private val doc: JSONObject) {
    val build: String = doc.getString("build")
    private val maskId = doc.getInt("mask")
    private val pieceDefs = doc.getJSONObject("pieces")
    private val sheets = doc.getJSONObject("sheets")
    private val masked = doc.getJSONObject("masked")
    /** Pictures that are plain files, not atlas pieces (the switch's check box): name -> {"f": file id, "s": [w, h]}. */
    private val fileDefs = doc.optJSONObject("files") ?: JSONObject()
    private val cut = HashMap<String, ArtPiece>()

    /** Bumps when pieces arrive, so that what reads [piece] is drawn again. */
    var version by mutableIntStateOf(0)
        private set

    @Volatile var error: String? = null
        private set

    /**
     * Whether the table has the piece at all (before it is cut). The woman's Body tab picture ([FIGURE]) is made on the phone
     * from the Face piece (BodyIcon, body_icon.py's port), so it is there where the Face piece is.
     */
    fun has(name: String): Boolean = pieceDefs.has(name) || fileDefs.has(name) || (name == FIGURE && pieceDefs.has(HEAD))

    /** The piece, or null (not cut yet, or not in the table). Reads [version]. */
    fun piece(name: String): ArtPiece? {
        @Suppress("UNUSED_EXPRESSION") version
        return synchronized(cut) { cut[name] }
    }

    /** Every file id the art needs (the sheets and the mask). */
    fun fileIds(): List<Int> =
        sheets.keys().asSequence().map { it.toInt() }.toList() + maskId + fileDefs.keys().asSequence().map { fileDefs.getJSONObject(it).getInt("f") }

    private fun file(id: Int) = File(dir, "$id-$build")

    /** Fetches what is missing and cuts every piece. Off the main thread (network, decoding). */
    suspend fun load(fetch: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            if (fetch) for (id in fileIds()) if (!file(id).let { it.isFile && it.length() > 0 }) download(id)
            val mask = decode(maskId)
            val heads = HashMap<String, Bitmap>()          // the Face pieces, kept for the Body picture made from them
            val bySheet = HashMap<Int, MutableList<String>>()
            for (name in pieceDefs.keys()) bySheet.getOrPut(pieceDefs.getJSONObject(name).getInt("f")) { ArrayList() }.add(name)
            for ((sheetId, names) in bySheet) {
                val sheet = decode(sheetId) ?: continue
                for (name in names) {
                    val def = pieceDefs.getJSONObject(name)
                    val b = def.getJSONArray("b"); val s = def.getJSONArray("s")
                    val l = b.getInt(0).coerceIn(0, sheet.width - 1); val t = b.getInt(1).coerceIn(0, sheet.height - 1)
                    val r = b.getInt(2).coerceIn(l + 1, sheet.width); val bt = b.getInt(3).coerceIn(t + 1, sheet.height)
                    val bmp = Bitmap.createBitmap(sheet, l, t, r - l, bt - t)
                    val size = IntSize(s.getInt(0), s.getInt(1))
                    put(name, ArtPiece(bmp.asImageBitmap(), size))
                    if (name == HEAD || name == "$HEAD-selected") heads[name] = bmp
                    val kind = KINDS.firstOrNull { it.second(name) }?.first
                    if (kind != null && mask != null) {
                        val m = masked.getJSONArray(kind)
                        put("$name.$kind", ArtPiece(trim(bmp, mask, m.getInt(0), m.getInt(1), m.getInt(2)).asImageBitmap(), size))
                    }
                }
            }
            // the plain-file pictures: the switch's check box, drawn at the size the table of files gives
            for (name in fileDefs.keys()) {
                val def = fileDefs.getJSONObject(name)
                val bmp = decode(def.getInt("f")) ?: continue
                val s = def.getJSONArray("s")
                put(name, ArtPiece(bmp.asImageBitmap(), IntSize(s.getInt(0), s.getInt(1))))
            }
            // the woman's Body tab picture, made from the Face piece: the picture and its round-button trim, chosen and not
            if (mask != null) for (state in listOf("", "-selected")) {
                val head = heads[HEAD + state] ?: continue
                val s = pieceDefs.getJSONObject(HEAD + state).getJSONArray("s")
                val px = IntArray(head.width * head.height)
                head.getPixels(px, 0, head.width, 0, 0, head.width, head.height)
                val made = BodyIcon.figure(px, head.width, head.height, state == "-selected")
                val fig = Bitmap.createBitmap(head.width, head.height, Bitmap.Config.ARGB_8888)
                fig.setPixels(made, 0, head.width, 0, 0, head.width, head.height)
                val size = IntSize(s.getInt(0), s.getInt(1))
                put(FIGURE + state, ArtPiece(fig.asImageBitmap(), size))
                val m = masked.getJSONArray("category")
                put("$FIGURE$state.category", ArtPiece(trim(fig, mask, m.getInt(0), m.getInt(1), m.getInt(2)).asImageBitmap(), size))
            }
            synchronized(cut) { version++ }
            error = null
            true
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            false
        }
    }

    private fun put(name: String, p: ArtPiece) { synchronized(cut) { cut[name] = p } }

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
        // the game at Shadowlands' last build, the one the table says where the pieces are in
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
        const val HEAD = "charactercreate-icon-customize-head"
        const val FIGURE = "charactercreate-icon-customize-figure"
        private val KINDS: List<Pair<String, (String) -> Boolean>> = listOf(
            "category" to { n: String -> n.startsWith("charactercreate-icon-customize-") },
            "race" to { n: String -> n.startsWith("raceicon128-") },
            "sex" to { n: String -> n.startsWith("charactercreate-gendericon-") },
        )

        /** From assets/creator_art.json, the cache in the app's files/creator-art. */
        fun open(context: Context): ArtAtlas {
            val text = context.assets.open("creator_art.json").bufferedReader().use { it.readText() }
            return ArtAtlas(File(context.filesDir, "creator-art"), JSONObject(text))
        }

        /**
         * sl_ui_art.py's masked(): the picture of a round button as Shadowlands shows it. The piece is stretched over the
         * button (width x height); its alpha is multiplied by the mask laid over the button, set in by [inset] on every side.
         */
        fun trim(piece: Bitmap, mask: Bitmap, width: Int, height: Int, inset: Int): Bitmap {
            val w = piece.width; val h = piece.height
            val sx = w / width.toFloat(); val sy = h / height.toFloat()
            val over = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(over).drawBitmap(mask, null, RectF(inset * sx, inset * sy, (width - inset) * sx, (height - inset) * sy), Paint(Paint.FILTER_BITMAP_FLAG))
            val out = piece.copy(Bitmap.Config.ARGB_8888, true)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            Canvas(out).drawBitmap(over, 0f, 0f, paint)
            over.recycle()
            return out
        }
    }
}
