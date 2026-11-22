package com.logie.wifecraft.dialogue

import org.json.JSONObject

/** Alliance, Horde or neither (pandaren by id, the Skyborne). */
enum class Faction(val key: String, val label: String) { ALLIANCE("alliance", "Alliance"), HORDE("horde", "Horde"), NEUTRAL("neutral", "neutral") }

/** What the lore pack knows of one race. */
data class RaceLore(val key: String, val name: String, val faction: Faction, val register: String, val about: String, val customs: String)

/**
 * The lore pack (assets/dialogue/lore.json): short original summaries the prompt retrieves by who is in the scene, never the
 * whole of it ([forScene]). Races, classes, factions, places, the stances one kind holds toward another, and the bodies
 * ([body]) the comfortable and the sexual talk may draw on. Pure JVM (org.json).
 */
class Lore(json: String) {
    private val root = JSONObject(json)
    private val raceIds: Map<Int, String> = root.getJSONObject("raceIds").let { o -> o.keys().asSequence().associate { it.toInt() to o.getString(it) } }
    private val pandarenFaction = root.optJSONObject("pandarenFaction")
    private val races: Map<String, RaceLore> = root.getJSONObject("races").let { o ->
        o.keys().asSequence().associateWith { k ->
            val r = o.getJSONObject(k)
            RaceLore(k, r.getString("name"), factionOf(r.optString("faction")), r.optString("register"), r.optString("about"), r.optString("customs"))
        }
    }
    private val classes: Map<Int, Pair<String, String>> = root.getJSONObject("classes").let { o ->
        o.keys().asSequence().associate { it.toInt() to (o.getJSONObject(it).getString("name") to o.getJSONObject(it).optString("about")) }
    }
    private val factions: Map<String, String> = strings(root.getJSONObject("factions"))
    private val places: Map<String, String> = strings(root.getJSONObject("places"))
    private val stances: Map<String, String> = strings(root.getJSONObject("stances"))
    private val bodies: Map<String, String> = strings(root.getJSONObject("bodies")).filterKeys { it != "comment" }
    private val kinks: Map<String, String> = strings(root.getJSONObject("kinks")).filterKeys { it != "comment" }

    private fun factionOf(s: String) = Faction.entries.firstOrNull { it.key == s } ?: Faction.NEUTRAL
    private fun strings(o: JSONObject) = o.keys().asSequence().associateWith { o.getString(it) }

    /** The race key of a ChrRaces id ("undead", "bloodelf"...), or null (unknown, a creature). */
    fun raceKey(raceId: Int): String? = raceIds[raceId]

    fun race(raceId: Int): RaceLore? = raceKey(raceId)?.let { races[it] }

    fun faction(raceId: Int): Faction {
        pandarenFaction?.optString(raceId.toString())?.takeIf { it.isNotEmpty() }?.let { return factionOf(it) }
        return race(raceId)?.faction ?: Faction.NEUTRAL
    }

    fun className(classId: Int): String? = classes[classId]?.first
    fun classAbout(classId: Int): String? = classes[classId]?.second
    fun factionAbout(f: Faction): String = factions[f.key].orEmpty()

    /** The place's snippet: the first of the pack's keys found in [placeName] (case-insensitive), or null. */
    fun place(placeName: String): String? {
        val n = placeName.lowercase()
        return places.entries.firstOrNull { it.key in n }?.value
    }

    /** The most specific stance [from] holds toward [to] (race over faction), or null: nothing special. */
    fun stance(from: Int, to: Int): String? {
        val a = raceKey(from) ?: return null
        val b = raceKey(to) ?: return null
        val fa = faction(from).key
        val fb = faction(to).key
        return stances["$a>$b"] ?: stances["$a>$fb"] ?: stances["$fa>$b"] ?: stances["$fa>$fb"]
    }

    /** What the body of a [raceId] is like, for the erotic talk; null for an unknown race. */
    fun body(raceId: Int): String? = raceKey(raceId)?.let { bodies[it] }

    fun kinkGuide(band: String): String? = kinks[band]

    /**
     * The snippets for a scene, in the order they matter and no more than [maxChars] long altogether: the speaker's race and
     * class, the place, and for each other person in the scene their race's stance (once per race) and, only with [withBodies],
     * their race's body.
     */
    fun forScene(speakerRace: Int, speakerClass: Int, place: String, others: List<Pair<Int, Int>>, withBodies: Boolean, maxChars: Int = 1500,
        includeSelf: Boolean = true): List<String> {
        val out = ArrayList<String>()
        if (includeSelf) {
            race(speakerRace)?.let { out += "You are ${it.name}: ${it.about} Customs: ${it.customs}" }
            className(speakerClass)?.let { out += "Your calling, $it: ${classAbout(speakerClass)}" }
        }
        place(place)?.let { out += "Where you are: $it" }
        if (includeSelf) factionAbout(faction(speakerRace)).takeIf { it.isNotEmpty() }?.let { out += "Your people: $it" }
        val seen = HashSet<Int>()
        for ((race, cls) in others) {
            if (!seen.add(race)) continue
            val r = race(race) ?: continue
            out += "Another here is ${r.name} (${faction(race).label}): ${r.about}"
            stance(speakerRace, race)?.let { out += "How your kind looks on theirs: $it" }
            if (withBodies) body(race)?.let { out += "Their body: $it" }
            className(cls)?.let { out += "Their calling, $it." }
        }
        var total = 0
        val kept = ArrayList<String>()
        for (x in out) {
            if (total + x.length + 1 > maxChars && kept.isNotEmpty()) break
            total += x.length + 1
            kept += x
        }
        return kept
    }
}
