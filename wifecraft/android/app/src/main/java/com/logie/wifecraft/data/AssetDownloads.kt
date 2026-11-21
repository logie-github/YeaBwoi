package com.logie.wifecraft.data

import android.content.Context
import com.logie.wifecraft.audio.TimeOfDaySound
import com.logie.wifecraft.characters.NamedCast
import com.logie.wifecraft.creator.*
import com.logie.wifecraft.location.LocationInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What each pack of Options' "Download everything" (PackKey) holds, as file ids of wago.tools: the files fetched as
 * they are ([Work.direct]) and the models whose own needs are read from them once here ([Work.models], by [Work.closure]:
 * a character or creature model's skin, skeleton and textures; every animation of a character model; a prop's or a
 * building's rooms, textures and doodads). Read from the app's assets and the game's tables on the phone. The fetching
 * is DownloadService's. [PackKey.BASICS] is GameFiles' own gathering; [PackKey.ART] is [art] (files kept in the art's own
 * caches). Off the main thread.
 */
class AssetDownloads(private val context: Context, private val tables: Tables, private val named: NamedCast?) {
    enum class Closure {
        /** Nothing beyond the files themselves. */
        NONE,
        /** A model with its skin, skeleton and the skeleton's own files (M2Files.closure, no animations). */
        MODEL,
        /** A character model with every animation it names. */
        MODEL_ALL_ANIMATIONS,
        /** A prop or a building (PropLoader.closure: an M2's needs, or a WMO's rooms, textures and doodads). */
        PROP,
    }

    class Work(val direct: Collection<Int>, val models: Collection<Int>, val closure: Closure, val sizes: Map<Int, Long> = emptyMap())

    /** One art file: [id] at the art's [build], kept as [target] (the art classes read `<id>-<build>` from their caches). */
    class ArtFile(val id: Int, val build: String, val target: File)

    private fun asset(name: String): String = context.assets.open(name).bufferedReader().use { it.readText() }

    fun plan(pack: PackKey): Work = when (pack) {
        PackKey.BASICS, PackKey.ART -> Work(emptyList(), emptyList(), Closure.NONE)
        PackKey.PLACES -> places()
        PackKey.SOUNDS -> Work(sounds(), emptyList(), Closure.NONE)
        PackKey.ANIMATIONS -> animations()
        PackKey.NAMED -> namedCharacters()
        PackKey.CREATURES -> creatures()
        PackKey.PROPS -> Work(emptyList(), library("0"), Closure.PROP)
        PackKey.BUILDINGS -> Work(emptyList(), library("1"), Closure.PROP)
    }

    /** Every location's files (their sizes known: assets/locations.json) and every race's backdrop model. */
    private fun places(): Work {
        val sizes = LinkedHashMap<Int, Long>()
        for (loc in LocationInfo.parseAll(asset("locations.json"))) for ((id, size) in loc.files()) sizes.putIfAbsent(id, size)
        val backdrops = tables.rows("ChrRaces").mapNotNullTo(LinkedHashSet()) { row -> RaceBackdrop.file(tables, row.int("ID")) }
        return Work(sizes.keys, backdrops, Closure.PROP, sizes)
    }

    /**
     * The sounds the app plays by id: assets/sounds.json (the title, the music and ambience by time of day, the places'
     * tracks, the interface's clicks), the time of day's flavour and bells (TimeOfDaySound), the immersive fire, rain and
     * cloth (the same ids as audio/Audio's), and every emote's voice for every race and sex (assets/emotes.json's "v").
     */
    fun sounds(): Set<Int> {
        val out = LinkedHashSet<Int>()
        fun walk(o: Any?, key: String?) {
            when (o) {
                is JSONObject -> for (k in o.keys()) {
                    val v = o.get(k)
                    // a place's "ambience" is a SoundAmbience id, the top level's the day and night files
                    if (k == "silenceMs" || (k in NOT_FILES && v is Number)) continue
                    walk(v, k)
                }
                is JSONArray -> for (i in 0 until o.length()) walk(o.get(i), key)
                is Int -> if (o > 0) out += o
            }
        }
        walk(JSONObject(asset("sounds.json")), null)
        for (p in TimeOfDaySound.PLACES.values) { out += p.flavorDay; out += p.flavorNight; out += p.bell }
        out += IMMERSION_SOUNDS
        val models = JSONObject(asset("emotes.json")).optJSONObject("models") ?: JSONObject()
        for (m in models.keys()) {
            val voices = models.getJSONObject(m).optJSONObject("v") ?: continue
            for (e in voices.keys()) { val ids = voices.getJSONArray(e); for (i in 0 until ids.length()) out += ids.getInt(i) }
        }
        return out
    }

    /** Every character model (ChrModel) with every animation it names. */
    private fun animations(): Work {
        val models = tables.rows("ChrModel").mapNotNullTo(LinkedHashSet()) { row -> modelFile(row.int("DisplayID")) }
        return Work(emptyList(), models, Closure.MODEL_ALL_ANIMATIONS)
    }

    /** Every display a named character is drawn as or a partial look is cut from: its model, skin, textures, extras. */
    private fun namedCharacters(): Work {
        val cast = named ?: NamedCast(emptyList())
        val looks = cast.displays().mapNotNull { CreatureLooks.resolve(tables, it) }
        val sets = cast.manifest?.sets.orEmpty().map { it.model }.filter { it > 0 }
        return looksWork(looks, sets)
    }

    /** The placement library's creature models (assets/catalog.tsv kind 2) and the game's named creatures, each display. */
    private fun creatures(): Work {
        val displays = LinkedHashSet<Int>()
        for (a in libraryRows()) if (a.second == "2" && a.third > 0) displays += a.third
        for (row in tables.rows("Creature")) (0..3).map { row.int("DisplayID_$it") }.firstOrNull { it > 0 }?.let { displays += it }
        val looks = displays.mapNotNull { d -> runCatching { CreatureLooks.resolve(tables, d) }.getOrNull() }
        return looksWork(looks, emptyList())
    }

    private fun looksWork(looks: List<ResolvedLook>, extraModels: List<Int>): Work {
        val direct = LinkedHashSet<Int>()
        val models = LinkedHashSet<Int>()
        for (l in looks) {
            direct += l.needsFiles
            if (l.model > 0) models += l.model
            for (x in l.extraModels) { if (x.model > 0) models += x.model; if (x.texture > 0) direct += x.texture }
        }
        models += extraModels
        return Work(direct, models, Closure.MODEL)
    }

    /** The library's entries of [kind] ("0" props, "1" buildings): their file ids. */
    private fun library(kind: String): List<Int> = libraryRows().filter { it.second == kind }.map { it.first }

    /** assets/catalog.tsv's rows as (file id, kind, display). */
    private fun libraryRows(): List<Triple<Int, String, Int>> {
        val out = ArrayList<Triple<Int, String, Int>>(70_000)
        context.assets.open("catalog.tsv").bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val f = line.split('\t')
                if (f.size < 7) continue
                val id = f[0].toIntOrNull() ?: continue
                out += Triple(id, f[5], f[6].toIntOrNull() ?: 0)
            }
        }
        return out
    }

    private fun modelFile(displayId: Int): Int? {
        val modelId = tables.rowBy("CreatureDisplayInfo", "ID", displayId)?.int("ModelID") ?: 0
        return tables.rowBy("CreatureModelData", "ID", modelId)?.int("FileDataID")?.takeIf { it > 0 }
    }

    /**
     * The art's files: the menus' (files/war-art) and the creator's (files/creator-art), each at its own file's build, as
     * those classes keep them.
     */
    fun art(): List<ArtFile> {
        val out = ArrayList<ArtFile>()
        for ((name, dirName, keys) in ART) {
            val doc = JSONObject(asset(name))
            val build = doc.getString("build")
            val dir = File(context.filesDir, dirName)
            val ids = LinkedHashSet<Int>()
            doc.optJSONObject("sheets")?.let { s -> for (k in s.keys()) k.toIntOrNull()?.let { ids += it } }
            for (key in keys) doc.optJSONObject(key)?.let { o -> for (k in o.keys()) o.optJSONObject(k)?.optInt("f")?.takeIf { it > 0 }?.let { ids += it } }
            doc.optInt("mask").takeIf { it > 0 }?.let { ids += it }
            for (id in ids) out += ArtFile(id, build, File(dir, "$id-$build"))
        }
        return out
    }

    companion object {
        /** sounds.json's numbers that aren't files (tables' ids) where they are single numbers; "silenceMs" (times) always. */
        private val NOT_FILES = setOf("area", "zoneMusic", "ambience", "kit")

        /** audio/Audio's immersive sounds: campfiresmallloop, rainlightloop, the seven cloth movements (keep in step). */
        val IMMERSION_SOUNDS = listOf(565519, 538983, 4612169, 4612171, 4612173, 4612175, 4612177, 4612179, 4612181)

        private val ART = listOf(
            Triple("ui_art.json", "war-art", listOf("icons")),
            Triple("creator_art.json", "creator-art", listOf("files")),
        )
    }
}
