package com.logie.wifecraft.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.logie.wifecraft.engine.Native
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** How far the art is: [loaded] of [total] pieces and icons, the files that failed (and why), and whether it is still at work. */
data class ArtStatus(
    val loaded: Int = 0,
    val total: Int = 0,
    val failures: List<String> = emptyList(),
    val busy: Boolean = false,
    /** Nothing more will happen by itself (everything here, or the failed files out of tries until Retry). */
    val settled: Boolean = false,
    /** When a failed file is tried again by itself (ms), or null. */
    val nextRetryAt: Long? = null,
)

/**
 * The editor's build-mode art: pieces of the game's current interface (the housing editor's tool buttons and picture
 * cards, the spellbook's page and side tabs, the talent tree's rings, parchment, ribbons, the gold glyphs) and ~60 item and
 * spell icons, cut on the phone and softened with [WarTint] (warmer, a little less saturated, the darks lifted).
 *
 * assets/ui_art.json (tools/ui_art.py, from the real UiTextureAtlas* tables and ManifestInterfaceData at the app's build)
 * holds ids and boxes only. The sheets and icons are fetched from wago.tools by id at that build into [dir] (about 58 MB,
 * once; four at a time), each checked to be a BLP2 texture (wago.tools answers a file it lacks with JSON or nothing), cut
 * and tinted, kept in observable state maps so that every widget drawing a piece draws again when it arrives. A file that
 * fails is tried again by itself after a growing wait ([ArtLoadBook], [ArtBackoff]) and by the Options' Retry; one that is
 * here but can't be read is deleted and fetched again. Until a picture is here WarWidgets draws a vector glyph in its place
 * ([WarGlyphs]). Nothing of the game is in the app or the repository.
 */
class WarArt private constructor(private val dir: File, private val doc: JSONObject) {
    val build: String = doc.getString("build")
    private val pieceDefs = doc.getJSONObject("pieces")
    private val sheetDefs = doc.getJSONObject("sheets")
    private val iconDefs = doc.getJSONObject("icons")
    private val pieces = mutableStateMapOf<String, ImageBitmap>()
    private val icons = mutableStateMapOf<String, ImageBitmap>()

    private val iconsByFile: Map<Int, List<String>> = iconDefs.keys().asSequence().toList()
        .groupBy { iconDefs.getJSONObject(it).getInt("f") }
    private val piecesBySheet: Map<Int, List<String>> = pieceDefs.keys().asSequence().toList()
        .groupBy { pieceDefs.getJSONObject(it).getInt("f") }

    /** Every file the art needs: the icons first (small, and the bar shows them at once), then the sheets with most pieces. */
    fun fileIds(): List<Int> = iconsByFile.keys.sorted() + piecesBySheet.entries.sortedByDescending { it.value.size }.map { it.key }

    val total: Int = pieceDefs.length() + iconDefs.length()
    private val book = ArtLoadBook(fileIds(), maxAuto = 5)

    /** Whether the art had to be fetched when the app started (the editor's first open shows "Preparing the interface"). */
    val firstOpen: Boolean = !fileIds().all { cached(it) }

    var status by mutableStateOf(ArtStatus(total = total))
        private set

    /** The player went on without waiting for the art (the overlay's Continue). */
    var overlayDismissed by mutableStateOf(false)

    fun has(name: String): Boolean = pieceDefs.has(name)

    /** An atlas piece, cut and warmed, or null (observable: a draw that read null draws again when it arrives). */
    fun piece(name: String?): ImageBitmap? = name?.let { pieces[it] }

    /** An icon by its key ("tab.props", "cat.lighting", "tool.focus"...: WarIcons), its own black edge cut off, or null. */
    fun icon(key: String?): ImageBitmap? = key?.let { icons[it] }

    private fun file(id: Int) = File(dir, "$id-$build")
    private fun cached(id: Int) = file(id).let { it.isFile && it.length() > 0 }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val fetchGate = Semaphore(4)
    // a 2048 x 1024 sheet is 8 MB of pixels as it is cut: sheets are decoded one at a time
    private val decodeGate = Semaphore(1)
    private val warm = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(WarTint.MATRIX)) }

    /** Fetches what is missing and cuts everything, off the main thread; at once again after a failure's wait. Idempotent. */
    fun start() {
        synchronized(this) {
            if (job != null) return
            job = scope.launch {
                try { runLoad() } finally { synchronized(this@WarArt) { if (job === coroutineContext[Job]) job = null } }
            }
        }
    }

    // wakes the loader from its wait for a failed file's next try (Retry)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The Options' Retry: the failed files from their first try, now. */
    fun retry() {
        synchronized(book) { book.retryFailed() }
        publish()
        wake.trySend(Unit)
        start()
    }

    private suspend fun runLoad() = coroutineScope {
        while (true) {
            val due = synchronized(book) { book.due(System.currentTimeMillis()).onEach { book.started(it) } }
            publish()
            if (due.isNotEmpty()) {
                due.map { id -> async { one(id) } }.awaitAll()
                continue
            }
            val next = synchronized(book) { book.nextRetryAt() }
            if (next == null) {
                // done: but a Retry may have just set files waiting (it starts a loader only when none is running)
                val again = synchronized(this@WarArt) {
                    synchronized(book) { book.due(System.currentTimeMillis()).isNotEmpty() }.also { if (!it) job = null }
                }
                if (again) continue else break
            }
            withTimeoutOrNull((next - System.currentTimeMillis()).coerceAtLeast(250L)) { wake.receive() }
        }
        publish()
    }

    private suspend fun one(id: Int) {
        try {
            if (!cached(id)) fetchGate.withPermit { download(id) }
            val ok = if (id in piecesBySheet) decodeGate.withPermit { cutSheet(id) } else cutIcon(id)
            if (!ok) {
                // here but unreadable (a cut-off or wrong answer kept by an older version): gone, to be fetched again
                file(id).delete()
                throw IOException("couldn't be read (fetched again)")
            }
            synchronized(book) { book.loaded(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {   // OutOfMemoryError too: the next file still loads
            synchronized(book) { book.failed(id, t.message ?: t.javaClass.simpleName, System.currentTimeMillis()) }
        }
        publish()
    }

    private fun publish() {
        val s = synchronized(book) {
            val now = System.currentTimeMillis()
            ArtStatus(
                loaded = pieces.size + icons.size, total = total,
                failures = book.failures().map { (id, why) -> describe(id) + ": " + why },
                busy = book.busy, settled = book.settled, nextRetryAt = book.nextRetryAt()?.takeIf { it > now },
            )
        }
        status = s
    }

    /** "file 3487944 (17 pieces: common-icon-backarrow, ...)" */
    private fun describe(id: Int): String {
        val names = piecesBySheet[id] ?: iconsByFile[id] ?: emptyList()
        val what = if (id in piecesBySheet) "piece" else "icon"
        return "file $id (${names.size} $what${if (names.size == 1) "" else "s"}: ${names.take(3).joinToString()}${if (names.size > 3) ", …" else ""})"
    }

    private fun cutIcon(id: Int): Boolean {
        val bmp = decode(id) ?: return false
        val k = (bmp.width * WarSize.ICON_INSET).toInt()
        val cut = Bitmap.createBitmap(bmp, k, k, bmp.width - 2 * k, bmp.height - 2 * k)
        cut.prepareToDraw()
        val img = cut.asImageBitmap()
        synchronized(icons) { for (key in iconsByFile[id].orEmpty()) icons[key] = img }
        return true
    }

    private fun cutSheet(id: Int): Boolean {
        val sheet = decode(id) ?: return false
        try {
            // the sheet's own size may differ from the atlas's (a smaller mip): scale the boxes
            val size = sheetDefs.getJSONArray(id.toString())
            val sx = sheet.width / size.getInt(0).toFloat(); val sy = sheet.height / size.getInt(1).toFloat()
            val cut = HashMap<String, ImageBitmap>()
            for (name in piecesBySheet[id].orEmpty()) {
                val b = pieceDefs.getJSONObject(name).getJSONArray("b")
                val l = (b.getInt(0) * sx).toInt().coerceIn(0, sheet.width - 1); val t = (b.getInt(1) * sy).toInt().coerceIn(0, sheet.height - 1)
                val r = (b.getInt(2) * sx).toInt().coerceIn(l + 1, sheet.width); val bt = (b.getInt(3) * sy).toInt().coerceIn(t + 1, sheet.height)
                val out = Bitmap.createBitmap(r - l, bt - t, Bitmap.Config.ARGB_8888)
                val part = Bitmap.createBitmap(sheet, l, t, r - l, bt - t)
                Canvas(out).drawBitmap(part, 0f, 0f, if (name in PLAIN) null else warm)
                if (part !== sheet) part.recycle()
                out.prepareToDraw()
                cut[name] = out.asImageBitmap()
            }
            synchronized(pieces) { pieces.putAll(cut) }
            return true
        } finally {
            sheet.recycle()   // only the sheet: the cut pieces are copies, never recycled while drawn
        }
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
            val code = c.responseCode
            ArtCheck.answer(code, c.contentType)?.let { throw IOException(it) }
            c.inputStream.use { i -> part.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        } finally {
            c.disconnect()
        }
        val head = ByteArray(4)
        val n = if (part.length() >= 4) part.inputStream().use { it.read(head) } else 0
        ArtCheck.body(part.length(), if (n == 4) head else ByteArray(0))?.let { part.delete(); throw IOException(it) }
        if (!part.renameTo(file(id))) { part.delete(); throw IOException("not saved") }
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
