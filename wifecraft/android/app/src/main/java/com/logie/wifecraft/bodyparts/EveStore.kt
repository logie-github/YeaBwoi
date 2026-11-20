package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import org.json.JSONObject
import java.io.File

/*
 * The Eve body's app side (pure JVM, unit-tested: EveStoreTest): the converted set on disk ([EveStore]: files/bodyparts/eve,
 * imported, summarised, removed), the import run off the main thread ([EveImportRunner]), the fit table as the menus read it
 * ([EveFitTable]: assets/eve_fit.json, the same file the engine fits by), when an NPC is drawn with it ([EveDress]: what
 * engine/Dresser and engine/Thumbs ask, and the pictures' cache mark) and what the creator's Eve rows offer ([EveOptionsEditor]).
 */

/** assets/eve_fit.json as the menus need it: per race, whether Eve is offered and why not. */
class EveFitTable(val rows: List<Row>) {
    class Row(val race: Int, val name: String, val models: List<Int>, val enabled: Boolean, val quality: String, val reason: String)

    fun forModel(model: Int): Row? = if (model <= 0) null else rows.firstOrNull { model in it.models }
    fun forRace(race: Int): Row? = rows.firstOrNull { it.race == race }

    /** Null when Eve may be drawn on [raceId]'s woman (its model [model] when known: the engine's own key), else why not. */
    fun whyNot(raceId: Int, model: Int = 0): String? {
        if (raceId in EveDress.NEVER) return EveDress.NEVER_REASON
        val row = forModel(model) ?: forRace(raceId) ?: return "not offered for this race (it isn't in the fit table)"
        return if (row.enabled) null else "not offered for ${row.name}: ${row.reason.ifEmpty { row.quality }}"
    }

    companion object {
        fun parse(json: String): EveFitTable {
            val o = JSONObject(json)
            val a = o.optJSONArray("races") ?: return EveFitTable(emptyList())
            val rows = ArrayList<Row>()
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                val models = r.optJSONArray("models")?.let { m -> List(m.length()) { m.optInt(it) } }.orEmpty()
                rows += Row(r.optInt("race"), r.optString("name"), models, r.optBoolean("enabled", false), r.optString("quality"), r.optString("reason"))
            }
            return EveFitTable(rows)
        }
    }
}

/** What the dressing paths know of the Eve set: whether one is ready to draw with, the fit table, Options' default, its signature. */
class EveContext(val ready: Boolean, val fit: EveFitTable?, val defaultOn: Boolean, val signature: String)

/**
 * When an NPC is drawn with the Eve body. Every dressing path asks this one place: the live scene and the creator
 * (engine/Dresser), the portraits, the library's pictures, the placing ghost and the Transmog cards (engine/Thumbs).
 *
 * - The options: the NPC's own ([Appearance.eve]); without them (the named characters, the starters, NPCs saved before)
 *   Options' "Use the Eve body for new women" ([EveContext.defaultOn]) gives Eve with the set's defaults.
 * - Drawn only on a playable body ([drawn]: what is actually drawn, a named character's variant or her partial look's
 *   stand-in; a display drawn as the game has it never), a woman's ([EveRule.applies]: unclothed, the male parts' rule's
 *   twin: the men's parts are another thing, PartChoice), of a race the fit table offers (by the drawn model, the engine's
 *   own key; gnome, goblin, mechagnome and vulpera women never, whatever the table says), with a set imported and loaded.
 * - The Eve body replaces her body below the neck, so an imported Ralia body texture isn't seen under it; the skin she is
 *   tinted to is sampled at her neck from her (Ralia or game) skin, so Ralia's skin colour still tints Eve.
 */
object EveDress {
    /** Never offered, whatever the table says: gnome, goblin, mechagnome and vulpera women. */
    val NEVER = setOf(7, 9, 35, 37)
    const val NEVER_REASON = "never offered for this race"
    const val RALIA_NOTE = "Eve replaces the Ralia body: an imported body texture isn't drawn under it (her skin colour still tints Eve)."

    /** The NPC's options as chosen, else Options' default (a woman's only). */
    fun effective(own: Appearance, defaultOn: Boolean): EveOptions? =
        own.eve ?: if (defaultOn && own.sex == Sex.FEMALE) EveOptions(on = true) else null

    /** The decision and, when it is off, why (for the menus and the desktop check). */
    class Decision(val options: EveOptions?, val why: String?)

    /**
     * Whether [own] (the roster's or the creator's appearance) is drawn with Eve, its look resolved as [drawn] (null: a display
     * as the game has it) on model [model]. Options to put on, or null to take Eve off.
     */
    fun decide(own: Appearance?, drawn: Appearance?, model: Int, ctx: EveContext?): Decision {
        if (own == null) return Decision(null, "not an NPC's look")
        val o = effective(own, ctx?.defaultOn == true)
        if (o == null || !o.on) return Decision(null, "off")
        if (drawn == null) return Decision(null, "drawn as the game's own model")
        if (drawn.sex != Sex.FEMALE) return Decision(null, "for women only")
        if (drawn.raceId in NEVER) return Decision(null, NEVER_REASON)
        if (!EveRule.applies(drawn, o)) return Decision(null, "her look is clothed")
        ctx?.fit?.whyNot(drawn.raceId, model)?.let { return Decision(null, it) }
        if (ctx == null || !ctx.ready) return Decision(null, "no Eve set is imported")
        return Decision(o, null)
    }

    /**
     * A picture's cache mark for [a]: "" when Eve can't be on it (no set, no options), else "_e" and a stable hash of the set's
     * signature and the options she would wear, so a picture is drawn anew when either changes (the race and clothing are in
     * the picture's own name already).
     */
    fun cacheMark(ctx: EveContext?, a: Appearance?): String {
        if (ctx == null || !ctx.ready || a == null) return ""
        val o = effective(a, ctx.defaultOn)?.takeIf { it.on } ?: return ""
        return "_e" + fnv(ctx.signature + "|" + o.toJson().toString())
    }

    fun fnv(s: String): String {
        var h = 0x811C9DC5.toInt()
        for (c in s) { h = h xor c.code; h *= 0x01000193 }
        return "%08x".format(h)
    }
}

/**
 * The converted Eve set in [root]/eve (files/bodyparts/eve: set.json, eve_<variant>.weve, skin_neutral.png, tone_NN.png,
 * ov_<slot>_NN.png). An import converts into [root]/eve.new and replaces the old set only when it worked; a failed one
 * leaves the old set as it was.
 */
class EveStore(val root: File) {
    val dir = File(root, "eve")
    private val staging = File(root, "eve.new")
    @Volatile private var cached: Pair<Long, EveSetIndex>? = null

    fun installed(): Boolean = File(dir, "set.json").isFile

    /** The set's index (null: none, or it can't be read). */
    fun index(): EveSetIndex? {
        val f = File(dir, "set.json")
        if (!f.isFile) return null
        val stamp = f.lastModified() xor f.length()
        cached?.let { if (it.first == stamp) return it.second }
        return runCatching { EveSetIndex.parse(f.readText()) }.getOrNull()?.also { cached = stamp to it }
    }

    /** What changes when the set does (the pictures' cache mark): set.json's size and time. */
    fun signature(): String {
        val f = File(dir, "set.json")
        return if (f.isFile) "${f.length()}-${f.lastModified()}" else ""
    }

    /** "Eve body: 3 meshes, 34 skin tones, 4 overlays" or "Not imported". */
    fun summary(): String = index()?.let { summaryOf(it) } ?: if (installed()) "Eve body: its index can't be read: import it again" else "Not imported"

    /**
     * Converts [sources] (.package files, folders, .zip archives) with EveConvert into the set ([profileJson]:
     * assets/sims_profile.json), telling [onProgress] how far it is in words. Throws [BodyPartFormatException] with what to
     * tell the user when it can't (the old set kept).
     */
    fun importFrom(sources: List<File>, profileJson: String, limits: EveConvert.Limits = EveConvert.Limits(),
                   onProgress: (String) -> Unit = {}): EveConvert.Result {
        if (sources.isEmpty()) throw BodyPartFormatException("No files were picked.")
        staging.deleteRecursively()
        try {
            onProgress("Reading the packages…")
            val result = EveConvert.convert(sources, staging, profileJson, limits) { done, total, what -> onProgress(progressLine(done, total, what)) }
            onProgress("Putting the set in place…")
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) {
                dir.mkdirs()
                staging.listFiles()?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            }
            cached = null
            return EveConvert.Result(dir, result.index, result.problems, result.decoded, result.peakHeapBytes)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** The set deleted (Remove). */
    fun remove() {
        staging.deleteRecursively()
        dir.deleteRecursively()
        cached = null
    }

    companion object {
        fun summaryOf(ix: EveSetIndex): String =
            "Eve body: ${plural(ix.meshes.size, "mesh", "meshes")}, ${plural(ix.tones.size, "skin tone", "skin tones")}, " +
                plural(ix.overlays.size, "overlay", "overlays") + if (ix.skin != null) "" else " (no neutral skin: tones only)"

        private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

        /** EveConvert's progress in words: "Reading the body…", then "Reading skins and overlays 2/31…", "Writing the set…". */
        fun progressLine(done: Int, total: Int, what: String): String = when {
            done >= total -> "Writing the set…"
            done < 2 -> "Reading the body…"
            else -> "Reading skins and overlays ${done - 1}/${total - 2}…"
        }
    }
}

/**
 * Runs an Eve import on its own thread, away from any screen (leaving Options doesn't stop it), one at a time. [state] says
 * how far it is; listeners hear every change (on the import's thread: post to the main thread to show it).
 */
class EveImportRunner(private val store: EveStore, private val profileJson: () -> String, private val limits: EveConvert.Limits = EveConvert.Limits()) {
    sealed class State {
        object Idle : State()
        class Running(val line: String) : State()
        class Done(val message: String, val problems: List<String>) : State()
        class Failed(val message: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()

    val busy: Boolean get() = state is State.Running

    fun listen(l: (State) -> Unit): () -> Unit { listeners += l; l(state); return { listeners -= l } }

    private fun set(s: State) { state = s; for (l in listeners) l(s) }

    /**
     * Starts an import: [stage] copies what was picked into the folder it is given (Android's picker hands out documents,
     * not files) and returns the files to convert; [onDone] (on the import's thread) with the result, null when it failed.
     * False when one is already running.
     */
    fun start(work: File, stage: (File) -> List<File>, onDone: (EveConvert.Result?) -> Unit = {}): Boolean {
        synchronized(this) {
            if (busy) return false
            set(State.Running("Copying your files…"))
        }
        val t = Thread({ run(work, stage, onDone) }, "eve-import")
        t.isDaemon = true
        t.start()
        return true
    }

    /** The same, on the caller's thread (the tests; Thread is [start]'s only difference). */
    fun run(work: File, stage: (File) -> List<File>, onDone: (EveConvert.Result?) -> Unit = {}) {
        if (state !is State.Running) set(State.Running("Copying your files…"))
        var result: EveConvert.Result? = null
        try {
            work.deleteRecursively(); work.mkdirs()
            val sources = stage(work)
            result = store.importFrom(sources, profileJson(), limits) { set(State.Running(it)) }
            val p = result.problems
            set(State.Done("Imported. " + store.summary() + ".", p))
        } catch (e: BodyPartFormatException) {
            set(State.Failed(e.message ?: "The Eve body couldn't be imported."))
        } catch (e: OutOfMemoryError) {
            set(State.Failed("The phone ran out of memory converting the Eve body: pick fewer packages at a time."))
        } catch (e: Exception) {
            set(State.Failed("The Eve body couldn't be imported: ${e.message ?: e}"))
        } finally {
            work.deleteRecursively()
        }
        onDone(result)
    }

    /** Remove: the set deleted (not while an import runs). */
    fun remove(): Boolean {
        synchronized(this) { if (busy) return false }
        store.remove()
        set(State.Done("Removed.", emptyList()))
        return true
    }
}

/**
 * What the creator's Eve rows offer for a converted set (the Create-a-Sim Body tab binds to this; ui/CreatorImports.kt
 * casEveBinding makes its CasEveBinding from it): the lower bodies, the skin tones with their colours, the overlay slots
 * with their shades' swatches, the shade the engine would pick for a skin, the strength slider's range, and why a race is
 * greyed. Pure.
 */
class EveOptionsEditor(val index: EveSetIndex, val fit: EveFitTable?) {
    class Tone(val index: Int, val label: String, val colour: Int)
    class Shade(val index: Int, val label: String, val swatch: Int)
    class Slot(val index: Int, val label: String, val shades: List<Shade>)

    val bottoms: List<String> = index.meshes.keys.toList()
    val tones: List<Tone> = index.tones.mapIndexed { i, t -> Tone(i, "Tone ${i + 1}", t.mean and 0xFFFFFF) }
    val slots: List<Slot> = index.overlays.mapIndexed { i, s ->
        Slot(i, s.slot.replaceFirstChar { it.uppercase() }, s.shades.mapIndexed { j, sh -> Shade(j, "Shade ${j + 1}", sh.swatch and 0xFFFFFF) })
    }
    /** "Her own" (the neutral skin tinted to hers) when the set has one, then the tones. */
    val hasOwnSkin: Boolean get() = index.skin != null

    /** The strength slider: 0 to 1 in steps of 0.05, 0.6 by default. */
    val opacityRange = 0f..1f
    val opacityStep = 0.05f

    /** The options she has now (her own, or Options' default: on for a woman when it is set). */
    fun read(a: Appearance, defaultOn: Boolean): EveOptions = a.eve ?: EveOptions(on = defaultOn && a.sex == Sex.FEMALE)

    /** [a] with [o], made valid for this set. */
    fun write(a: Appearance, o: EveOptions): Appearance = a.copy(eve = normalise(o))

    /** [o] with what this set lacks put back to its defaults (a lower body it hasn't: its first; a tone or slot past its end: none). */
    fun normalise(o: EveOptions): EveOptions {
        val bottom = if (o.bottom in bottoms || bottoms.isEmpty()) o.bottom else bottoms.first()
        val tone = if (o.tone in tones.indices) o.tone else if (!hasOwnSkin && tones.isNotEmpty()) 0 else -1
        val slot = if (o.overlaySlot in slots.indices) o.overlaySlot else -1
        val shade = if (slot >= 0 && o.overlayShade in slots[slot].shades.indices) o.overlayShade else -1
        return o.copy(bottom = bottom, tone = tone, overlaySlot = slot, overlayShade = shade, opacity = o.opacity.coerceIn(0f, 1f))
    }

    /** The shade (its index) the engine picks for a skin of [skinRgb] in [slot] when none is chosen (EveColour.nearest); -1: none. */
    fun nearestShade(skinRgb: Int, slot: Int): Int = slots.getOrNull(slot)?.let { s -> EveColour.nearest(skinRgb and 0xFFFFFF, s.shades.map { it.swatch }) } ?: -1

    /** Null when the rows may be used for this woman, else why they are greyed. */
    fun unavailable(raceId: Int, sex: Sex, model: Int = 0): String? = when {
        sex != Sex.FEMALE -> "for women only"
        else -> fit?.whyNot(raceId, model) ?: if (raceId in EveDress.NEVER) EveDress.NEVER_REASON else null
    }

    /** The options in a line: "plain, her own skin, freckles (nearest shade) at 60%". */
    fun describe(o: EveOptions): String {
        if (!o.on) return "Off"
        val skin = tones.getOrNull(o.tone)?.label ?: "her own skin"
        val ov = slots.getOrNull(o.overlaySlot)?.let { s ->
            ", " + s.label.lowercase() + " (" + (s.shades.getOrNull(o.overlayShade)?.label?.lowercase() ?: "nearest shade") + ") at ${Math.round(o.opacity * 100)}%"
        } ?: ""
        return "${o.bottom}, ${skin.replaceFirstChar { it.lowercase() }}$ov"
    }
}
