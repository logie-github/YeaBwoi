package com.logie.wifecraft.creator

import com.logie.wifecraft.bodyparts.EveOptions
import org.json.JSONObject

/** Wrath++'s own body options (WrathppBody.kt, the realm's registry 1101 to 1103): Shape, Style and Density, by value. */
data class Body(val shape: Int = 0, val style: Int = 0, val density: Int = 0)

/**
 * A named character's look (assets/characters.json, characters/NamedCast): [character] its id, [variant] the chosen
 * variant's key (an outfit, "underwear", "bare", "<outfit>_nude", or "<outfit>_partial": a model-parts set). The NPC is
 * drawn as that variant (engine/Dresser); the rest of the [Appearance] is the variant's body (or its stand-in's), so the
 * scene knows its race and sex.
 */
data class NamedLook(val character: String, val variant: String)

/** The body-part add-on's size: which of the imported parts an NPC uses (bodyparts/PartChoice). */
enum class PartSize(val label: String) { SMALL("Small"), MEDIUM("Medium"), LARGE("Large") }

/** The body-part add-on's cut. */
enum class PartCut(val label: String) { UNCUT("Uncut"), CUT("Cut"), RETRACTED("Retracted") }

/** The body-part add-on's placement (the setting called Scrotum on the NPC's menu). */
enum class PartPlacement(val label: String) { HIGH("High"), LOW("Low") }

/**
 * An NPC's body-part settings (male NPCs; bodyparts/PartChoice picks the part files by them). Medium, Uncut, High by
 * default; saved as `"parts":{"size":"MEDIUM","cut":"UNCUT","placement":"HIGH"}`, left out while at the default.
 */
data class PartOptions(val size: PartSize = PartSize.MEDIUM, val cut: PartCut = PartCut.UNCUT, val placement: PartPlacement = PartPlacement.HIGH) {
    /** The parts' variant label as the import writes it (PartVariant.label: "Medium Uncut High"). */
    val label: String get() = "${size.label} ${cut.label} ${placement.label}"

    fun toJson(): JSONObject = JSONObject().put("size", size.name).put("cut", cut.name).put("placement", placement.name)

    companion object {
        fun fromJson(o: JSONObject?): PartOptions? {
            if (o == null) return null
            fun <E : Enum<E>> pick(values: Array<E>, key: String, fallback: E): E = values.firstOrNull { it.name == o.optString(key) } ?: fallback
            return PartOptions(pick(PartSize.values(), "size", PartSize.MEDIUM), pick(PartCut.values(), "cut", PartCut.UNCUT),
                pick(PartPlacement.values(), "placement", PartPlacement.HIGH))
        }
    }
}

/**
 * What an NPC's look is: the race and sex, the choice picked for each of the game's options (ChrCustomizationOption id
 * to ChrCustomizationChoice id; an option not in [picks] is at its first choice), Wrath++'s body options and whether
 * the starting outfit shows. Saved in the project as
 * `{"race":4,"sex":"FEMALE","picks":{"123":456},"body":{"shape":0,"style":0,"density":0},"gear":true}`.
 * [gear] is the Gear switch: on, the NPC wears its class's default clothing ([classId]: ClassOutfits, 0 Civilian: the
 * recruit's shirt, pants and boots, saved as `"class":2` when not 0); off, nothing (the body drawn bare). [clothing] is
 * never saved: it is what a look wears as drawn (ClassOutfits.dressed fills it; the scene's undressing takes pieces off
 * it). Saves from before the switch kept a Transmog outfit in `"clothing"`: one with any piece loads as Gear on, one whose
 * every slot was cleared (`"clothing":{}`) or saved with `"gear":false` as Gear off; their pieces are not kept.
 * Wrath++'s Undead switch ([undead], the imported undead skins: [undeadSkin] the one on, 0 the first) adds
 * `"undead":true,"undeadSkin":2` while it is on; without them (every look saved before) it is off. The skin colour picked
 * among the race's own stays in [picks], so turning Undead off goes back to it.
 */
data class Appearance(
    val raceId: Int, val sex: Sex, val picks: Map<Int, Int> = emptyMap(), val body: Body = Body(), val gear: Boolean = true,
    val undead: Boolean = false, val undeadSkin: Int = 0,
    val clothing: Map<Int, Int> = emptyMap(),
    val starterClothing: Boolean = clothing.isEmpty(),
    /** A named character's variant ([NamedLook]); null for a created NPC. Saved as `"named":{"character":..,"variant":..}`. */
    val named: NamedLook? = null,
    /** The body-part settings ([PartOptions]); null: the defaults. */
    val parts: PartOptions? = null,
    /** The Body tab's sculpt sliders ([BodySculpt]); null: the body as made. Saved as `"sculpt":{...}` only when set. */
    val sculpt: BodySculpt? = null,
    /** The Personality tab ([CasPersonality]: traits, preferences, bio, colour); null: the defaults. Saved as `"personality":{...}`. */
    val personality: CasPersonality? = null,
    /**
     * The Eve body's options (bodyparts/EveOptions, EveDress); null: not chosen (Options' "Use the Eve body for new women"
     * decides). Saved as `"eve":{...}` only when set: older saves load as null and are written back the same.
     */
    val eve: EveOptions? = null,
    /** The class whose default clothing Gear on wears (ChrClasses id; [ClassOutfits.CIVILIAN] 0: none). */
    val classId: Int = 0,
    /**
     * The HD figure's choices (bodyparts/HdModels: hair, eyes, marks, tints, jewellery, an outfit of her own); null: the set's
     * defaults. Saved as `"hd":{...}` only when set: older saves load as null and are written back the same.
     */
    val hd: com.logie.wifecraft.bodyparts.HdOptions? = null,
) {
    fun toJson(): JSONObject {
        val p = JSONObject()
        for ((k, v) in picks.toSortedMap()) p.put(k.toString(), v)
        return JSONObject()
            .put("race", raceId)
            .put("sex", sex.name)
            .put("picks", p)
            .put("body", JSONObject().put("shape", body.shape).put("style", body.style).put("density", body.density))
            .put("gear", gear)
            .also { if (classId != 0) it.put("class", classId) }
            .also { if (undead) it.put("undead", true).put("undeadSkin", undeadSkin) }
            .also { json -> named?.let { json.put("named", JSONObject().put("character", it.character).put("variant", it.variant)) } }
            .also { json -> parts?.takeIf { it != PartOptions() }?.let { json.put("parts", it.toJson()) } }
            .also { json -> sculpt?.takeIf { !it.isNeutral }?.let { json.put("sculpt", it.toJson()) } }
            .also { json -> personality?.takeIf { !it.isDefault }?.let { json.put("personality", it.toJson()) } }
            .also { json -> eve?.let { json.put("eve", it.toJson()) } }
            .also { json -> hd?.let { json.put("hd", it.toJson()) } }
    }

    companion object {
        fun fromJson(o: JSONObject): Appearance {
            val picks = LinkedHashMap<Int, Int>()
            o.optJSONObject("picks")?.let { p -> for (k in p.keys()) k.toIntOrNull()?.let { picks[it] = p.getInt(k) } }
            val b = o.optJSONObject("body")
            val body = if (b == null) Body() else Body(b.optInt("shape"), b.optInt("style"), b.optInt("density"))
            val sex = runCatching { Sex.valueOf(o.optString("sex", "FEMALE")) }.getOrDefault(Sex.FEMALE)
            // an older save's Transmog outfit: any piece is Gear on, every slot cleared is Gear off (Appearance's doc)
            val cleared = o.optJSONObject("clothing")?.let { json ->
                ClothingSlot.entries.none { slot -> json.optInt(slot.id.toString()) > 0 }
            } ?: false
            val named = o.optJSONObject("named")?.let { n ->
                val character = n.optString("character"); val variant = n.optString("variant")
                if (character.isEmpty() || variant.isEmpty()) null else NamedLook(character, variant)
            }
            return Appearance(o.getInt("race"), sex, picks, body, o.optBoolean("gear", true) && !cleared,
                o.optBoolean("undead", false), o.optInt("undeadSkin", 0).coerceAtLeast(0),
                named = named, parts = PartOptions.fromJson(o.optJSONObject("parts")),
                sculpt = BodySculpt.fromJson(o.optJSONObject("sculpt")), personality = CasPersonality.fromJson(o.optJSONObject("personality")),
                eve = o.optJSONObject("eve")?.let { EveOptions.fromJson(it) }, classId = o.optInt("class", 0).coerceAtLeast(0),
                hd = o.optJSONObject("hd")?.let { com.logie.wifecraft.bodyparts.HdOptions.fromJson(it) })
        }
    }
}

/**
 * Wrath++'s body options as its creator lists them (WrathppBody.kt: RACES, SHAPES 4, STYLES 16, DENSITIES 4; the
 * registry's names): a woman of the ten races of 3.3.5. Option ids are negative, apart from the game's.
 */
object BodyOptions {
    const val SHAPE = -1101
    const val STYLE = -1102
    const val DENSITY = -1103
    /** The creator's Body tab where a woman's looks have no Body category (WrathppCreate.lua's BODY_TAB). */
    const val BODY_TAB = 9001
    val RACES = setOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11)
    val SHAPES = listOf("As drawn", "Petite", "Curvy", "Voluptuous")
    val STYLES = listOf("None") + (1..16).map { it.toString() }
    val DENSITIES = listOf("None", "25%", "50%", "75%", "100%")
    const val NOT_IMPORTED = "Import the body options (wrathpp-body.zip) in Options"

    fun eligible(raceId: Int, sex: Sex) = sex == Sex.FEMALE && raceId in RACES

    fun names(optionId: Int): List<String> = when (optionId) {
        SHAPE -> SHAPES; STYLE -> STYLES; DENSITY -> DENSITIES; else -> emptyList()
    }

    fun option(optionId: Int, categoryId: Int): Option {
        val name = when (optionId) { SHAPE -> "Shape"; STYLE -> "Style"; else -> "Density" }
        return Option(optionId, name, categoryId, false, names(optionId).mapIndexed { i, n -> Choice(i, n, null, null) })
    }

    fun value(body: Body, optionId: Int) = when (optionId) { SHAPE -> body.shape; STYLE -> body.style; else -> body.density }

    fun with(body: Body, optionId: Int, value: Int) = when (optionId) {
        SHAPE -> body.copy(shape = value); STYLE -> body.copy(style = value); else -> body.copy(density = value)
    }
}
