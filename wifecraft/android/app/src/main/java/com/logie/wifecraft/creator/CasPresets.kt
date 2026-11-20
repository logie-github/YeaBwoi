package com.logie.wifecraft.creator

import org.json.JSONObject
import java.io.File

/**
 * A look kept under a name (the creator's Presets): the whole [appearance] (race, sex, every choice, the body options, the
 * sculpt, the personality, the outfit) and the character's [characterName] if she had one. [builtIn] ones come with the app
 * (the named characters' presets: Chromie's) and can't be deleted. As a file: `{"wifecraftPreset":1,"name":..,
 * "character":..,"appearance":{...}}`, what Export writes and Import reads.
 */
data class CasPreset(val name: String, val appearance: Appearance, val characterName: String = "", val builtIn: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put(FORMAT_KEY, FORMAT).put("name", name).put("character", characterName).put("appearance", appearance.toJson())

    companion object {
        const val FORMAT_KEY = "wifecraftPreset"
        const val FORMAT = 1
        const val MAX_NAME = 48

        /** A preset from its file's text; null when it is no preset (or a newer format). */
        fun parse(text: String): CasPreset? = runCatching {
            val o = JSONObject(text)
            if (o.optInt(FORMAT_KEY, 0) !in 1..FORMAT) return null
            val name = o.optString("name").trim().take(MAX_NAME)
            val a = Appearance.fromJson(o.getJSONObject("appearance"))
            if (name.isEmpty()) null else CasPreset(name, a, o.optString("character").trim().take(MAX_NAME))
        }.getOrNull()

        /** The named characters' looks ([LookPreset]s of the Transmog panel's named outfits) as presets, built in. */
        fun builtIns(named: List<ClothingSet>): List<CasPreset> = named.mapNotNull { set ->
            val look = set.look ?: return@mapNotNull null
            CasPreset(set.name, Appearance(look.raceId, look.sex, look.picks, clothing = set.displays, starterClothing = set.displays.isEmpty()), builtIn = true)
        }
    }
}

/**
 * The presets the player saved, one file each in [dir] (files/cas-presets/): listed by name, saved (a name used again
 * replaces that preset), deleted, imported from a shared file's text. Every write goes to a temporary file first and is then
 * moved over the old, so a preset is never half written. Pure JVM (java.io).
 */
class CasPresetStore(private val dir: File) {
    fun list(): List<CasPreset> = (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f -> runCatching { CasPreset.parse(f.readText()) }.getOrNull() }
        .sortedBy { it.name.lowercase() }

    fun save(p: CasPreset): File {
        dir.mkdirs()
        val file = fileFor(p.name)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(p.copy(builtIn = false).toJson().toString(2))
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) { tmp.delete(); error("could not save the preset") } }
        return file
    }

    fun delete(name: String): Boolean = fileFor(name).delete()

    /** A shared preset's text saved here; null when it is no preset. A name already used gets " (2)", " (3)"... */
    fun import(text: String): CasPreset? {
        val p = CasPreset.parse(text) ?: return null
        val taken = list().map { it.name.lowercase() }.toSet()
        var name = p.name; var n = 2
        while (name.lowercase() in taken) name = "${p.name.take(CasPreset.MAX_NAME - 5)} (${n++})"
        val saved = p.copy(name = name)
        save(saved)
        return saved
    }

    /** The text a preset is shared as (the same as its file). */
    fun export(p: CasPreset): String = p.toJson().toString(2)

    fun fileFor(name: String): File = File(dir, safeName(name) + ".json")

    companion object {
        /** A preset's file name: letters, digits, spaces, dashes and underscores kept, the rest as underscores; never empty. */
        fun safeName(name: String): String =
            name.trim().take(CasPreset.MAX_NAME).map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
                .joinToString("").trim().ifEmpty { "preset" }
    }
}
