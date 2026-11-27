package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/*
 * HD Models' app side (pure JVM, unit-tested: HdModelsTest): the user's own high-detail characters, converted on the desktop
 * (tools/hd/whd_convert.py: a .whd set per race and sex, zipped) and imported here (Options -> Mods -> HD Models). For a race
 * and sex with a set, the HD figure IS the character everywhere (the creator, the scene, the pictures, Gear on and off): the
 * engine draws it instead of the game's model on the model's own skeleton (engine/hdbody.h). The pieces:
 *   [HdSetInfo] a set's index (its .whd header: race, sex, hair styles, outfits by class, eyes, marks, tints, jewellery);
 *   [HdStore] files/bodyparts/hd: the imported sets, one per race and sex, imported (copied, checked, replaced atomically),
 *   listed and removed; [HdImportRunner] the import on its own thread;
 *   [HdOptions] one NPC's choices (saved in her look as "hd"); [HdDress] when an NPC is drawn as its HD figure and with what
 *   (the decision every dressing path takes: engine/Dresser, engine/Thumbs).
 */

/** One NPC's HD figure choices. -1: the set's default (hair: its first; eyes: the first; marks: none). [outfit] -1: by her
 *  class with Gear on (HdDress.outfitFor), -2: bare whatever Gear says, else an outfit's index. [tint]/[hairTint]: preset
 *  indices (0: the set's first: "her own" / the race's default hair colour). [jewelry]: the pieces worn (indices). */
data class HdOptions(
    val hair: Int = -1,
    val outfit: Int = -1,
    val eyes: Int = -1,
    val faceMark: Int = -1,
    val bodyMarks: Boolean = false,
    val tint: Int = 0,
    val hairTint: Int = 0,
    val jewelry: List<Int> = emptyList(),
    /** The set's body choice ([HdSetInfo.bodies]: 0 its own; a tail's length, bones showing, male parts...). */
    val body: Int = 0,
    /** The set's shape-key sliders ([HdSetInfo.shapes]), 0..1 each; missing ones 0. */
    val shapes: List<Float> = emptyList(),
    /** "Use game model instead": this character is drawn as the game's own model even where a Liard set exists for its race and sex. */
    val useGame: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("hair", hair).put("outfit", outfit).put("eyes", eyes).put("faceMark", faceMark)
        .put("bodyMarks", bodyMarks).put("tint", tint).put("hairTint", hairTint).put("jewelry", JSONArray(jewelry))
        .also { if (body != 0) it.put("body", body) }
        .also { if (useGame) it.put("useGame", true) }
        .also { if (shapes.any { v -> v != 0f }) it.put("shapes", JSONArray(shapes.map { v -> v.toDouble() })) }

    /** [shapes] with slider [i] at [v] (the list grown as needed). */
    fun withShape(i: Int, v: Float): HdOptions = copy(shapes = List(maxOf(shapes.size, i + 1)) { k -> if (k == i) v else shapes.getOrElse(k) { 0f } })

    val isDefault: Boolean get() = this == HdOptions()

    companion object {
        fun fromJson(o: JSONObject?): HdOptions = if (o == null) HdOptions() else HdOptions(
            o.optInt("hair", -1), o.optInt("outfit", -1), o.optInt("eyes", -1), o.optInt("faceMark", -1),
            o.optBoolean("bodyMarks", false), o.optInt("tint", 0).coerceAtLeast(0), o.optInt("hairTint", 0).coerceAtLeast(0),
            o.optJSONArray("jewelry")?.let { a -> List(a.length()) { a.optInt(it, -1) }.filter { it >= 0 } }.orEmpty(),
            o.optInt("body", 0).coerceAtLeast(0),
            o.optJSONArray("shapes")?.let { a -> List(a.length()) { a.optDouble(it, 0.0).toFloat().coerceIn(-1f, 2f) } }.orEmpty(),
            o.optBoolean("useGame", false))
    }
}

/** A set's index as the menus need it (read from its .whd header alone). */
class HdSetInfo(
    val file: File,
    val race: Int,
    val sex: Int,
    val label: String,
    val hair: List<String>,
    val outfits: List<Outfit>,
    val eyes: List<String>,
    val faceMarks: List<String>,
    val bodyMarks: Boolean,
    val tints: List<String>,
    val hairTints: List<String>,
    val jewelry: List<String>,
    val triangles: Int,
    val gpuBytes: Long,
    /** Who made the model, as its recipe says ("Model by Liard's Art (liardart.com)"); shown with the set. "" when unsaid. */
    val credit: String = "",
    /** The bodies to choose from (the first its own) and whether each has genitals of its own (the add-on parts' mask). */
    val bodies: List<String> = listOf("Body"),
    val bodyGenitals: List<Boolean> = listOf(false),
    /** The shape keys offered as sliders. */
    val shapes: List<String> = emptyList(),
) {
    class Outfit(val label: String, val classes: List<Int>)

    val sexEnum: Sex get() = if (sex == 1) Sex.FEMALE else Sex.MALE
    val key: Int get() = race * 10 + sex

    companion object {
        const val MAGIC = "WHD1"
        const val VERSION = 1

        /** Reads and checks [f]'s header (its index and its parts' table against its size). Throws [BodyPartFormatException]. */
        fun read(f: File): HdSetInfo {
            val name = f.name
            RandomAccessFile(f, "r").use { r ->
                val size = r.length()
                if (size < 16) throw BodyPartFormatException("$name is not an HD model set.")
                val head = ByteArray(16)
                r.readFully(head)
                if (String(head, 0, 4, Charsets.US_ASCII) != MAGIC) throw BodyPartFormatException("$name is not an HD model set (.whd).")
                fun le(o: Int) = (head[o].toLong() and 0xFF) or ((head[o + 1].toLong() and 0xFF) shl 8) or
                    ((head[o + 2].toLong() and 0xFF) shl 16) or ((head[o + 3].toLong() and 0xFF) shl 24)
                val version = le(4); val jl = le(8); val nb = le(12)
                if (version > VERSION) throw BodyPartFormatException("$name was made by a newer converter (version $version): update the app.")
                if (jl <= 0 || 16 + jl > size || jl > 64L * 1024 * 1024) throw BodyPartFormatException("$name is cut short.")
                val js = ByteArray(jl.toInt())
                r.readFully(js)
                val o = runCatching { JSONObject(String(js, Charsets.UTF_8)) }.getOrNull()
                    ?: throw BodyPartFormatException("$name's index can't be read.")
                val table = (16 + jl + 15) and 15L.inv()
                if (table + 16 * nb > size) throw BodyPartFormatException("$name is cut short.")
                r.seek(table)
                val tb = ByteArray((16 * nb).toInt())
                r.readFully(tb)
                for (i in 0 until nb.toInt()) {
                    var off = 0L; var len = 0L
                    for (k in 0 until 8) off = off or ((tb[i * 16 + k].toLong() and 0xFF) shl (8 * k))
                    for (k in 0 until 8) len = len or ((tb[i * 16 + 8 + k].toLong() and 0xFF) shl (8 * k))
                    if (off < 0 || len < 0 || off > size || len > size - off) throw BodyPartFormatException("$name is cut short.")
                }
                return parse(f, o, nb.toInt())
            }
        }

        /** The index's JSON (already read) as the menus need it; [blobs] the parts' count. */
        fun parse(f: File, o: JSONObject, blobs: Int): HdSetInfo {
            val race = o.optInt("race", 0); val sex = o.optInt("sex", -1)
            if (race <= 0 || sex !in 0..1) throw BodyPartFormatException("${f.name} doesn't say which race and sex it is for.")
            val meshes = o.optJSONArray("meshes") ?: JSONArray()
            var body = false
            for (i in 0 until meshes.length()) {
                val m = meshes.optJSONObject(i) ?: continue
                if (m.optString("kind") == "body") body = true
                for (k in listOf("vertexBlob", "indexBlob")) if (m.optInt(k, -1) !in 0 until blobs) throw BodyPartFormatException("${f.name} is damaged (a mesh without its data).")
            }
            if (!body) throw BodyPartFormatException("${f.name} has no body.")
            // the fit scales and stands the figure by its neck and pelvis joints (engine/hdbody.cpp fitHd): a set without them could
            // only ever be drawn as the game's model, so it isn't taken (Bodies would call it Liard)
            val joints = o.optJSONObject("joints")
            if (joints == null || !joints.has("Neck1") || !joints.has("Pelvis"))
                throw BodyPartFormatException("${f.name} has no neck and pelvis joints: convert the model again.")
            fun labels(key: String): List<String> = o.optJSONArray(key)?.let { a -> List(a.length()) { a.optJSONObject(it)?.optString("label").orEmpty() } }.orEmpty()
            val outfits = o.optJSONArray("outfits")?.let { a ->
                List(a.length()) { i ->
                    val x = a.optJSONObject(i) ?: JSONObject()
                    Outfit(x.optString("label"), x.optJSONArray("classes")?.let { c -> List(c.length()) { c.optInt(it) } }.orEmpty())
                }
            }.orEmpty()
            val bodies = o.optJSONArray("bodies")?.let { a ->
                List(a.length()) { i -> (a.optJSONObject(i)?.optString("label", "Body") ?: "Body") to (a.optJSONObject(i)?.has("genitalBlob") == true) }
            }.orEmpty()
            val overlays = o.optJSONArray("overlays") ?: JSONArray()
            val faces = ArrayList<String>(); var bodyMarks = false
            for (i in 0 until overlays.length()) {
                val x = overlays.optJSONObject(i) ?: continue
                if (x.optString("slot") == "face") faces += x.optString("label") else bodyMarks = true
            }
            return HdSetInfo(f, race, sex, o.optString("label", f.nameWithoutExtension), labels("hair"), outfits, labels("eyeTextures"),
                faces, bodyMarks, labels("tints"), labels("hairTints"), labels("jewelry"),
                o.optJSONObject("triangles")?.optInt("max", 0) ?: 0, o.optLong("gpuBytes", 0), o.optString("credit", ""),
                bodies.map { it.first }.ifEmpty { listOf("Body") }, bodies.map { it.second }.ifEmpty { listOf(false) },
                labels("shapes"))
        }
    }
}

/**
 * The imported sets in [root]/hd (files/bodyparts/hd: one .whd per race and sex, named "<race>_<sex>.whd"). An import copies
 * what was picked (.whd files or .zip archives of them) into [root]/hd.new with the sets already there, checks each, and
 * replaces the folder only when every picked set was good: a failed import keeps the old sets.
 */
class HdStore(val root: File) {
    val dir = File(root, "hd")
    private val staging = File(root, "hd.new")

    fun sets(): List<HdSetInfo> = (dir.listFiles { f -> f.isFile && f.name.endsWith(".whd") } ?: emptyArray())
        .sortedBy { it.name }.mapNotNull { runCatching { HdSetInfo.read(it) }.getOrNull() }

    fun installed(): Boolean = sets().isNotEmpty()

    fun forRace(race: Int, sex: Sex): HdSetInfo? = File(dir, fileName(race, if (sex == Sex.FEMALE) 1 else 0)).takeIf { it.isFile }
        ?.let { runCatching { HdSetInfo.read(it) }.getOrNull() }

    /** What changes when a set does (the pictures' cache mark). */
    fun signature(): String = (dir.listFiles() ?: emptyArray()).sortedBy { it.name }.joinToString(",") { "${it.name}:${it.length()}:${it.lastModified()}" }

    fun summary(): String {
        val s = sets()
        return if (s.isEmpty()) "Not imported" else "HD Models: " + s.joinToString(", ") { it.label }
    }

    /**
     * Imports [sources] (.whd files, .zip archives holding them), telling [onProgress] how far it is. Returns the sets imported.
     * Throws [BodyPartFormatException] with what to tell the user (the old sets kept).
     */
    fun importFrom(sources: List<File>, onProgress: (String) -> Unit = {}): List<HdSetInfo> {
        if (sources.isEmpty()) throw BodyPartFormatException("No files were picked.")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".whd")) it.copyTo(File(staging, it.name), overwrite = true) }
            val got = ArrayList<HdSetInfo>()
            val tmp = File(staging, "_in.whd")
            var n = 0
            fun take(f: File) {
                val info = HdSetInfo.read(f)
                val dest = File(staging, fileName(info.race, info.sex))
                dest.delete()
                if (!f.renameTo(dest)) { f.copyTo(dest, overwrite = true); f.delete() }
                got += HdSetInfo.read(dest)
            }
            for (src in sources) {
                if (src.name.lowercase().endsWith(".zip")) {
                    ZipFile(src).use { z ->
                        for (e in z.entries()) {
                            if (e.isDirectory || !e.name.lowercase().endsWith(".whd")) continue
                            if (e.size > MAX_SET_BYTES) throw BodyPartFormatException("${e.name.substringAfterLast('/')} is too large for an HD set.")
                            onProgress("Reading ${e.name.substringAfterLast('/')} (${++n})…")
                            z.getInputStream(e).use { i -> tmp.outputStream().use { o -> copyLimited(i, o, MAX_SET_BYTES) } }
                            take(tmp)
                        }
                    }
                } else if (src.name.lowercase().endsWith(".whd")) {
                    onProgress("Reading ${src.name} (${++n})…")
                    src.copyTo(tmp, overwrite = true)
                    take(tmp)
                }
            }
            if (got.isEmpty()) throw BodyPartFormatException("No HD model set (.whd) was in what was picked: pick the converted zip (Wifecraft-HD-*.zip) or its .whd files.")
            onProgress("Putting the sets in place…")
            val old = File(root, "hd.old")
            old.deleteRecursively()
            if (dir.exists() && !dir.renameTo(old)) dir.deleteRecursively()
            if (!staging.renameTo(dir)) {
                dir.mkdirs()
                staging.listFiles()?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            }
            old.deleteRecursively()
            return got.map { HdSetInfo.read(File(dir, fileName(it.race, it.sex))) }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** One race and sex's set deleted; [race] 0: every set. */
    fun remove(race: Int = 0, sex: Int = 1) {
        staging.deleteRecursively()
        if (race == 0) dir.deleteRecursively() else File(dir, fileName(race, sex)).delete()
    }

    companion object {
        const val MAX_SET_BYTES = 400L * 1024 * 1024
        fun fileName(race: Int, sex: Int) = "${race}_$sex.whd"

        private fun copyLimited(i: java.io.InputStream, o: java.io.OutputStream, limit: Long) {
            val buf = ByteArray(1 shl 16)
            var total = 0L
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw BodyPartFormatException("An HD set in the archive is too large.")
                o.write(buf, 0, n)
            }
        }
    }
}

/** An HD import on its own thread (leaving Options doesn't stop it), one at a time; [state] says how far it is. */
class HdImportRunner(private val store: HdStore) {
    sealed class State {
        object Idle : State()
        class Running(val line: String) : State()
        class Done(val message: String) : State()
        class Failed(val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()
    val busy: Boolean get() = state is State.Running

    fun listen(l: (State) -> Unit): () -> Unit { listeners += l; l(state); return { listeners -= l } }
    private fun set(s: State) { state = s; for (l in listeners) l(s) }

    fun start(work: File, stage: (File) -> List<File>): Boolean {
        synchronized(this) {
            if (busy) return false
            set(State.Running("Copying your files…"))
        }
        val t = Thread({ run(work, stage) }, "hd-import")
        t.isDaemon = true
        t.start()
        return true
    }

    fun run(work: File, stage: (File) -> List<File>) {
        if (state !is State.Running) set(State.Running("Copying your files…"))
        try {
            work.deleteRecursively(); work.mkdirs()
            val got = store.importFrom(stage(work)) { set(State.Running(it)) }
            set(State.Done("Imported " + got.joinToString(", ") { it.label } + "."))
        } catch (e: BodyPartFormatException) {
            set(State.Failed(e.message ?: "The HD models couldn't be imported."))
        } catch (e: java.util.zip.ZipException) {
            set(State.Failed("The archive can't be opened: ${e.message ?: "it is damaged"}."))
        } catch (e: Exception) {
            set(State.Failed("The HD models couldn't be imported: ${e.message ?: e}"))
        } finally {
            work.deleteRecursively()
        }
    }

    fun remove(race: Int = 0, sex: Int = 1): Boolean {
        synchronized(this) { if (busy) return false }
        store.remove(race, sex)
        set(State.Done("Removed."))
        return true
    }
}

/** What the dressing paths know of HD Models: Options' switch, the loaded sets (by race * 10 + sex), their signature. */
class HdContext(val on: Boolean, val sets: Map<Int, HdSetInfo>, val signature: String)

/**
 * When an NPC is drawn as its HD figure, and in what. Every dressing path asks this one place.
 *  - Options' HD Models switch on, and a set loaded for the race and sex as drawn (a named character's variant's race; a
 *    display drawn as the game has it never: its model isn't a playable race's);
 *  - always, whatever the clothing: Gear off is the bare HD body; Gear on wears the set's outfit for her class (the set's
 *    outfits list the classes each is for; Civilian, 0, the civilian outfit), or the nearest when the set has none for it
 *    ([outfitFor]: then the first outfit that isn't the civilian one, and [Decision.note] says so);
 *  - the NPC's own choices (Appearance.hd), else the defaults.
 */
object HdDress {
    class Decision(val set: HdSetInfo?, val options: HdOptions?, val outfit: Int, val why: String?, val note: String? = null) {
        val on: Boolean get() = set != null && options != null
    }

    /** The outfit Gear on wears for [classId] in [set]: (its index, a note when it is a stand-in), or (-1, null) when there are none. */
    fun outfitFor(set: HdSetInfo, classId: Int): Pair<Int, String?> {
        if (set.outfits.isEmpty()) return -1 to "the HD set has no clothing: drawn bare"
        val own = set.outfits.indexOfFirst { classId in it.classes }
        if (own >= 0) return own to null
        val civilian = set.outfits.indexOfFirst { 0 in it.classes }
        if (classId == 0 && civilian >= 0) return civilian to null
        val other = set.outfits.indices.firstOrNull { it != civilian } ?: civilian
        return other to "the HD set has no outfit for this class: ${set.outfits[other].label} instead"
    }

    fun decide(own: Appearance?, drawn: Appearance?, ctx: HdContext?): Decision {
        if (ctx == null || !ctx.on) return Decision(null, null, -1, "HD Models is off")
        if (own == null) return Decision(null, null, -1, "not an NPC's look")
        if (drawn == null) return Decision(null, null, -1, "drawn as the game's own model")
        val set = ctx.sets[Bodies.key(drawn.raceId, drawn.sex)]
            ?: return Decision(null, null, -1, "no HD set for this race and sex")
        val o = own.hd ?: HdOptions()
        if (o.useGame) return Decision(null, null, -1, "this character uses the game's model")
        var note: String? = null
        val outfit = when {
            o.outfit == -2 || !drawn.gear -> -1
            o.outfit >= 0 && o.outfit < set.outfits.size -> o.outfit
            else -> outfitFor(set, drawn.classId).also { note = it.second }.first
        }
        if (drawn.named != null && note == null && drawn.gear) note = "a named character on the HD figure: her outfit is the set's nearest"
        return Decision(set, o, outfit, null, note)
    }

    /** A picture's cache mark: "" without HD, else "_h" and a stable hash of the sets and her choices. */
    fun cacheMark(ctx: HdContext?, a: Appearance?): String {
        if (ctx == null || !ctx.on || a == null) return ""
        if (ctx.sets[Bodies.key(a.raceId, a.sex)] == null || a.hd?.useGame == true) return ""
        return "_h" + LookHash.fnv(ctx.signature + "|" + (a.hd ?: HdOptions()).toJson().toString() + "|" + a.gear + "|" + a.classId)
    }
}
