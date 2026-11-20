package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Body
import com.logie.wifecraft.creator.Sex
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a variant shows: [OUTFIT] the character's own clothes (an NPC display of the game), [UNDERWEAR] every worn layer
 * taken off, the body model's own underwear kept (the creator's No Gear), [BARE] that and the skin colour's underwear
 * layers taken off too (the race's base skin as the model draws it; no geometry or texture is added), [NUDE] an outfit's
 * own items without its torso, leg and feet pieces (CharacterLooks.NUDE_REMOVED: shirt, chest or robe, tabard, belt, legs,
 * feet) over that bare body: the head piece, shoulders, wrists, gloves and cloak stay; [TABARD] an outfit's own items
 * without its shirt, chest or robe and legs (CharacterLooks.TABARD_REMOVED), the tabard and everything else kept, over
 * that bare body.
 */
enum class VariantKind(val key: String) {
    OUTFIT("outfit"), UNDERWEAR("underwear"), BARE("bare"), NUDE("nude"), TABARD("tabard");

    /** Wears its own items (an outfit, or one with some of them taken off). */
    val worn: Boolean get() = this == OUTFIT || this == NUDE || this == TABARD

    /** Drawn without the skin colour's underwear layers. */
    val bare: Boolean get() = this == BARE || this == NUDE || this == TABARD

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("variant kind \"$key\"")
    }
}

/**
 * One look of a named character (assets/characters.json, made by tools/characters.py from the game's tables): either the
 * game's own NPC display drawn as it is ([display] > 0 and [raceId] 0: a model of its own, not a playable race's), or a
 * playable race's body ([raceId], [sex]) with the customization choices [picks] (ChrCustomizationOption id to
 * ChrCustomizationChoice id), Wrath++'s [body] options and the worn [equipment] (equipment slot, as ClothingSlot's ids,
 * to ItemDisplayInfo id; empty for the undressed kinds). [sourceDisplay] and [sourceCreatures]: where it was read from
 * (for an undressed variant of a character the game draws with a model of its own, the display whose identity it stands
 * in for); [era]: the expansion the display is from, as tools/characters.py judged it; [note]: what is approximate.
 */
data class CharacterVariant(
    val key: String,
    val label: String,
    val kind: VariantKind,
    val display: Int = 0,
    val raceId: Int = 0,
    val sex: Sex = Sex.FEMALE,
    val picks: Map<Int, Int> = emptyMap(),
    val body: Body = Body(),
    val equipment: Map<Int, Int> = emptyMap(),
    val sourceDisplay: Int = 0,
    val sourceCreatures: List<Int> = emptyList(),
    val era: String = "",
    val note: String = "",
) {
    /** Drawn as the game's NPC display itself (its own model), not as a playable race's body. */
    val isDisplay: Boolean get() = raceId == 0 && display > 0
}

/**
 * A named character: her [variants], and her creator [presets] (body variants of a playable race, [VariantKind.OUTFIT]:
 * her choices and worn items, offered in the creator's Transmog panel as a named outfit: [CreatorPresets]).
 */
data class Character(val id: String, val name: String, val variants: List<CharacterVariant>, val presets: List<CharacterVariant> = emptyList()) {
    fun variant(key: String): CharacterVariant? = variants.firstOrNull { it.key == key }
}

/** assets/characters.json read and written. Pure JVM (org.json). */
object CharacterRoster {
    fun parse(json: String): List<Character> {
        val root = JSONObject(json)
        val list = root.optJSONArray("characters") ?: JSONArray()
        return (0 until list.length()).map { i -> character(list.getJSONObject(i)) }
    }

    private fun character(o: JSONObject): Character {
        val vs = o.optJSONArray("variants") ?: JSONArray()
        val ps = o.optJSONArray("presets") ?: JSONArray()
        return Character(o.getString("id"), o.getString("name"), (0 until vs.length()).map { variant(vs.getJSONObject(it)) },
            (0 until ps.length()).map { variant(ps.getJSONObject(it)) })
    }

    private fun ints(o: JSONObject?): Map<Int, Int> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<Int, Int>()
        for (k in o.keys()) { val key = k.toIntOrNull() ?: continue; out[key] = o.getInt(k) }
        return out
    }

    private fun variant(o: JSONObject): CharacterVariant {
        val b = o.optJSONObject("body")
        val creatures = o.optJSONArray("sourceCreatures")
        val equipment = o.opt("equipment")
        return CharacterVariant(
            key = o.getString("key"),
            label = o.optString("label", o.getString("key")),
            kind = VariantKind.of(o.optString("kind", "outfit")),
            display = o.optInt("display", 0),
            raceId = o.optInt("race", 0),
            sex = if (o.optString("sex", "FEMALE") == "MALE") Sex.MALE else Sex.FEMALE,
            picks = ints(o.optJSONObject("picks")),
            body = if (b == null) Body() else Body(b.optInt("shape"), b.optInt("style"), b.optInt("density")),
            // "nude" (or none) for the undressed kinds
            equipment = if (equipment is JSONObject) ints(equipment) else emptyMap(),
            sourceDisplay = o.optInt("sourceDisplay", 0),
            sourceCreatures = if (creatures == null) emptyList() else (0 until creatures.length()).map { creatures.getInt(it) },
            era = o.optString("era", ""),
            note = o.optString("note", ""),
        )
    }

    fun toJson(characters: List<Character>): JSONObject {
        val list = JSONArray()
        for (c in characters) {
            val o = JSONObject().put("id", c.id).put("name", c.name).put("variants", variants(c.variants))
            if (c.presets.isNotEmpty()) o.put("presets", variants(c.presets))
            list.put(o)
        }
        return JSONObject().put("characters", list)
    }

    private fun variants(list: List<CharacterVariant>): JSONArray {
        val vs = JSONArray()
        for (v in list) {
            val o = JSONObject().put("key", v.key).put("label", v.label).put("kind", v.kind.key)
            if (v.display > 0) o.put("display", v.display)
            if (v.raceId > 0) {
                o.put("race", v.raceId).put("sex", v.sex.name)
                o.put("picks", JSONObject().also { p -> v.picks.toSortedMap().forEach { (k, x) -> p.put(k.toString(), x) } })
                o.put("body", JSONObject().put("shape", v.body.shape).put("style", v.body.style).put("density", v.body.density))
                if (v.kind.worn) o.put("equipment", JSONObject().also { e -> v.equipment.toSortedMap().forEach { (k, x) -> e.put(k.toString(), x) } })
                else o.put("equipment", "nude")
            }
            if (v.sourceDisplay > 0) o.put("sourceDisplay", v.sourceDisplay)
            if (v.sourceCreatures.isNotEmpty()) o.put("sourceCreatures", JSONArray(v.sourceCreatures))
            if (v.era.isNotEmpty()) o.put("era", v.era)
            if (v.note.isNotEmpty()) o.put("note", v.note)
            vs.put(o)
        }
        return vs
    }
}
