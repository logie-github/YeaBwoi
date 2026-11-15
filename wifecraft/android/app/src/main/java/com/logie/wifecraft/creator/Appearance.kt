package com.logie.wifecraft.creator

import org.json.JSONObject

/** Wrath++'s own body options (WrathppBody.kt, the realm's registry 1101 to 1103): Shape, Style and Density, by value. */
data class Body(val shape: Int = 0, val style: Int = 0, val density: Int = 0)

/**
 * What an NPC's look is: the race and sex, the choice picked for each of the game's options (ChrCustomizationOption id
 * to ChrCustomizationChoice id; an option not in [picks] is at its first choice), Wrath++'s body options and whether
 * the starting outfit shows. Saved in the project as
 * `{"race":4,"sex":"FEMALE","picks":{"123":456},"body":{"shape":0,"style":0,"density":0},"gear":true}`.
 */
data class Appearance(val raceId: Int, val sex: Sex, val picks: Map<Int, Int> = emptyMap(), val body: Body = Body(), val gear: Boolean = true) {
    fun toJson(): JSONObject {
        val p = JSONObject()
        for ((k, v) in picks.toSortedMap()) p.put(k.toString(), v)
        return JSONObject()
            .put("race", raceId)
            .put("sex", sex.name)
            .put("picks", p)
            .put("body", JSONObject().put("shape", body.shape).put("style", body.style).put("density", body.density))
            .put("gear", gear)
    }

    companion object {
        fun fromJson(o: JSONObject): Appearance {
            val picks = LinkedHashMap<Int, Int>()
            o.optJSONObject("picks")?.let { p -> for (k in p.keys()) k.toIntOrNull()?.let { picks[it] = p.getInt(k) } }
            val b = o.optJSONObject("body")
            val body = if (b == null) Body() else Body(b.optInt("shape"), b.optInt("style"), b.optInt("density"))
            val sex = runCatching { Sex.valueOf(o.optString("sex", "FEMALE")) }.getOrDefault(Sex.FEMALE)
            return Appearance(o.getInt("race"), sex, picks, body, o.optBoolean("gear", true))
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
