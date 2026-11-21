package com.logie.wifecraft.creator

import org.json.JSONObject

/** A class an NPC can be (ChrClasses id and name); [ClassOutfits.CIVILIAN] (0) is no class. */
data class GameClass(val id: Int, val name: String)

/**
 * The Gear switch's clothing (assets/class_outfits.json, made by tools/class_outfits.py): Gear on wears the NPC's class's
 * default clothing, the game's starting outfit for that race, sex and class (CharStartOutfit's worn pieces as item display
 * ids by equipment slot); Gear off wears nothing (the body drawn bare: ImportedLooks). An NPC with no class (Civilian, 0)
 * wears the civilian clothes: the recruit's shirt, pants and boots. Pure JVM.
 *
 * Where the table has no row for the race, sex and class, the outfit is looked for in order: the race's own row, its
 * proxy's (the race an allied race was made from), a human's, any race's of that class and sex, any race's of that class,
 * and last the civilian clothes ([source] says which).
 */
class ClassOutfits(
    val classes: List<GameClass>,
    private val races: Map<Int, List<Int>>,
    private val proxy: Map<Int, Int>,
    val civilian: Map<Int, Int>,
    private val outfits: Map<String, Map<Int, Int>>,
) {
    /** Where an outfit came from. */
    enum class Source { OWN, PROXY, OTHER_RACE, CIVILIAN }

    /** The class picker's list for a race: Civilian, then the classes the race may be (all of them for a race not listed). */
    fun classesFor(raceId: Int): List<GameClass> {
        val ids = races[raceId]
        return listOf(CIVILIAN_CLASS) + if (ids == null) classes else classes.filter { it.id in ids }
    }

    fun valid(raceId: Int, classId: Int): Boolean = classId == CIVILIAN || classesFor(raceId).any { it.id == classId }

    /** [classId] when the race may be it, else Civilian (a race changed to one that can't be that class). */
    fun effectiveClass(raceId: Int, classId: Int): Int = if (valid(raceId, classId)) classId else CIVILIAN

    fun name(classId: Int): String = if (classId == CIVILIAN) CIVILIAN_CLASS.name else classes.firstOrNull { it.id == classId }?.name ?: CIVILIAN_CLASS.name

    /** The default clothing of [classId] for a [raceId] [sex] body, and where it came from. */
    fun lookup(raceId: Int, sex: Sex, classId: Int): Pair<Map<Int, Int>, Source> {
        val c = effectiveClass(raceId, classId)
        if (c == CIVILIAN) return civilian to Source.CIVILIAN
        val s = if (sex == Sex.FEMALE) "F" else "M"
        outfits[key(raceId, c, s)]?.let { return it to Source.OWN }
        proxy[raceId]?.let { p -> outfits[key(p, c, s)]?.let { return it to Source.PROXY } }
        outfits[key(1, c, s)]?.let { return it to Source.OTHER_RACE }
        outfits.entries.firstOrNull { it.key.endsWith(":$c:$s") }?.let { return it.value to Source.OTHER_RACE }
        outfits.entries.firstOrNull { it.key.split(':').getOrNull(1) == c.toString() }?.let { return it.value to Source.OTHER_RACE }
        return civilian to Source.CIVILIAN
    }

    fun outfit(raceId: Int, sex: Sex, classId: Int): Map<Int, Int> = lookup(raceId, sex, classId).first

    fun source(raceId: Int, sex: Sex, classId: Int): Source = lookup(raceId, sex, classId).second

    /**
     * [a] as it is drawn: Gear on wearing its class's default clothing, Gear off wearing nothing. A named character's look
     * is its chosen variant's (her signature outfit or her nude look: characters/GearSwitch) and comes back as it is.
     */
    fun dressed(a: Appearance): Appearance = when {
        a.named != null -> a
        !a.gear -> a.copy(clothing = emptyMap(), starterClothing = false)
        else -> a.copy(clothing = outfit(a.raceId, a.sex, a.classId), starterClothing = false)
    }

    /** Every item display any outfit wears (what the downloads could fetch for them). */
    fun displays(): Set<Int> = (outfits.values.flatMap { it.values } + civilian.values).toSet()

    companion object {
        const val CIVILIAN = 0
        val CIVILIAN_CLASS = GameClass(CIVILIAN, "Civilian")

        /** The recruit's shirt, pants and boots (items 38, 39, 40) at the app's build: when the file can't be read. */
        val RECRUIT = mapOf(4 to 5729, 7 to 6050, 8 to 6051)

        /** No file: every class wears the recruit's clothes. */
        val EMPTY = ClassOutfits(emptyList(), emptyMap(), emptyMap(), RECRUIT, emptyMap())

        private fun key(race: Int, cls: Int, sex: String) = "$race:$cls:$sex"

        private fun slots(o: JSONObject?): Map<Int, Int> {
            if (o == null) return emptyMap()
            val out = LinkedHashMap<Int, Int>()
            for (k in o.keys()) { val slot = k.toIntOrNull() ?: continue; val d = o.optInt(k); if (d > 0) out[slot] = d }
            return out
        }

        /** assets/class_outfits.json read; [EMPTY] when it can't be. */
        fun parse(json: String?): ClassOutfits {
            if (json.isNullOrBlank()) return EMPTY
            return runCatching {
                val o = JSONObject(json)
                val classes = ArrayList<GameClass>()
                o.optJSONArray("classes")?.let { a ->
                    for (i in 0 until a.length()) a.optJSONObject(i)?.let { c -> classes += GameClass(c.getInt("id"), c.getString("name")) }
                }
                val races = LinkedHashMap<Int, List<Int>>()
                o.optJSONObject("races")?.let { r ->
                    for (k in r.keys()) {
                        val id = k.toIntOrNull() ?: continue
                        val a = r.optJSONArray(k) ?: continue
                        races[id] = (0 until a.length()).map { a.getInt(it) }
                    }
                }
                val proxy = LinkedHashMap<Int, Int>()
                o.optJSONObject("proxy")?.let { p -> for (k in p.keys()) k.toIntOrNull()?.let { proxy[it] = p.getInt(k) } }
                val outfits = LinkedHashMap<String, Map<Int, Int>>()
                o.optJSONObject("outfits")?.let { m -> for (k in m.keys()) slots(m.optJSONObject(k)).takeIf { it.isNotEmpty() }?.let { outfits[k] = it } }
                val civilian = slots(o.optJSONObject("civilian")).ifEmpty { RECRUIT }
                ClassOutfits(classes, races, proxy, civilian, outfits)
            }.getOrDefault(EMPTY)
        }
    }
}
