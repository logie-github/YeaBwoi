package com.logie.wifecraft.dialogue

import org.json.JSONArray
import org.json.JSONObject

/*
 * Character profiles and eras: every named NPC is played by an expert on them, in the era the scene is set in.
 * Data (assets/dialogue/profiles/<id>.json, assets/dialogue/eras/<id>.json, README "Character profiles" and "Eras") is
 * loaded here; the prompt gets only compact retrieved pieces under strict token budgets ([PersonaBudget]):
 *   - the character core ([PersonaKit.core]): a trimmed sheet, kept in the stable system prefix;
 *   - the era slice ([PersonaKit.eraSlice]): the era's world and the NPC's state in it, also in the system prompt (an era change
 *     changes it, so the director flushes the NPC's caches);
 *   - per turn ([PersonaKit.turnNotes]): relationship snippets for the NPCs present and topic sections the talk mentions.
 * Pure JVM (org.json).
 */

/** Token budgets, in [Tokens.approx] units (the README states them; PersonaKitTest holds every named NPC to them). */
object PersonaBudget {
    const val CORE = 280
    const val ERA = 150
    const val REL = 60            // each, at most REL_MAX of them
    const val REL_MAX = 2
    const val TOPIC = 70          // each, at most TOPIC_MAX of them
    const val TOPIC_MAX = 2
    const val CUSTOM = 90         // the era's note on a made-up NPC's race, class and side
    /** Everything the kit adds to one request, at the most. */
    const val TURN = REL * REL_MAX + TOPIC * TOPIC_MAX
    const val TOTAL = CORE + ERA + TURN
}

object Tokens {
    /** A cheap estimate: the larger of 1.35 tokens a word and a quarter of the characters. */
    fun approx(s: String): Int {
        val words = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        return maxOf(Math.ceil(words * 1.35).toInt(), (s.length + 3) / 4)
    }

    /** [s] cut at a sentence end (else a word) to fit [max] tokens. */
    fun fit(s: String, max: Int): String {
        if (approx(s) <= max) return s
        val parts = Regex("[^.!?]+[.!?]+|[^.!?]+$").findAll(s).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        val out = StringBuilder()
        for (p in parts) { val next = if (out.isEmpty()) p else "$out $p"; if (approx(next) > max) break; out.clear(); out.append(next) }
        if (out.isNotEmpty()) return out.toString()
        val words = s.split(Regex("\\s+")).toMutableList()
        while (words.size > 1 && approx(words.joinToString(" ")) > max) words.removeAt(words.lastIndex)
        return words.joinToString(" ")
    }
}

data class EraPack(
    val id: String, val label: String, val order: Int, val release: Int, val until: Int, val years: String,
    val present: String, val world: String, val idiom: String,
    val folk: Map<String, String>, val classes: Map<String, String>, val races: Map<String, String>, val stances: Map<String, String>,
    /** Names and terms that first appear in this era (an earlier era rejects them, see [PersonaKit.blockedAfter]). */
    val introduces: List<String>,
    /** How a place stands in this age, by the same keys as the lore pack's places. */
    val places: Map<String, String> = emptyMap(),
)

data class EraState(val status: String, val text: String, val unverified: Boolean) {
    val outOfEra: Boolean get() = status == "out_of_era" || status == "dead"
}

data class Topic(val keys: List<String>, val text: String, val from: String?, val until: String?)
data class Relation2(val name: String, val address: String, val default: String, val eras: Map<String, String>, val from: String? = null)

class CharProfile(json: JSONObject) {
    val id: String = json.getString("id")
    val name: String = json.getString("name")
    val canon: Boolean = json.optBoolean("canon", true)
    private val coreJson = json.getJSONObject("core")
    private val variants: List<JSONObject> = json.optJSONArray("variants")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    val voice: List<Topic> = json.getJSONArray("voice").let { a -> (0 until a.length()).map { i ->
        val v = a.get(i)
        if (v is JSONObject) Topic(emptyList(), v.getString("t"), v.optString("from").ifEmpty { null }, v.optString("until").ifEmpty { null }) else Topic(emptyList(), v.toString(), null, null)
    } }
    val backstory: List<Topic> = topics(json.optJSONArray("backstory"))
    val topics: List<Topic> = topics(json.optJSONArray("topics"))
    val likes: String = json.optString("likes")
    val fears: String = json.optString("fears")
    val secrets: String = json.optString("secrets")
    val relations: Map<String, Relation2> = json.optJSONObject("relationships")?.let { o ->
        o.keys().asSequence().associateWith { k ->
            val r = o.getJSONObject(k)
            Relation2(r.optString("name", k), r.optString("address"), r.optString("default"),
                r.optJSONObject("eras")?.let { e -> e.keys().asSequence().associateWith { e.getString(it) } }.orEmpty(), r.optString("from").ifEmpty { null })
        }
    }.orEmpty()
    val states: Map<String, EraState> = json.optJSONObject("eraStates")?.let { o ->
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { EraState(it.optString("s", "active"), it.getString("t"), it.optBoolean("u", false)) } }
    }.orEmpty()
    val sources: List<String> = strings(json.optJSONArray("sources"))
    val unverified: List<String> = strings(json.optJSONArray("unverified"))
    val first: String get() = name.substringBefore(' ')

    /** One core field as it reads in [era] (the base value, overlaid by the last variant that covers the era). */
    fun field(key: String, eraOrder: Int, order: (String) -> Int): String {
        var v = coreJson.optString(key)
        for (x in variants) {
            val from = x.optString("from").ifEmpty { null }?.let(order) ?: Int.MIN_VALUE
            val until = x.optString("until").ifEmpty { null }?.let(order) ?: Int.MAX_VALUE
            if (eraOrder in from..until && x.has(key)) v = x.getString(key)
        }
        return v
    }

    companion object {
        private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getString(i) } }.orEmpty()
        private fun topics(a: JSONArray?): List<Topic> = a?.let { arr -> (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Topic(strings(o.optJSONArray("keys")), o.getString("text"), o.optString("from").ifEmpty { null }, o.optString("until").ifEmpty { null })
        } }.orEmpty()
    }
}

class PersonaKit(val eras: List<EraPack>, val profiles: Map<String, CharProfile>, val defaultEra: String = "wotlk", private val placeEras: Map<String, String> = emptyMap()) {
    private val byId = eras.associateBy { it.id }
    private val byName: Map<String, CharProfile> = profiles.values.associateBy { it.name.lowercase() }

    @Volatile private var eraId: String = if (defaultEra in byId) defaultEra else eras.first().id
    @Volatile private var customYear: Int = 0

    /** The era in force. */
    val era: EraPack get() = resolve(eraId, customYear)

    /** A stable key of the era in force (it changes exactly when the persona does). */
    val eraKey: String get() = era.id

    /** Sets the era (an id, or "custom" with a release [year], which is mapped to the nearest era). Also sets the era-aware Anachronism filter. */
    fun setEra(id: String?, year: Int = 0) {
        val newId = if (id != null && (id in byId || id == CUSTOM)) id else defaultEra
        if (newId == eraId && year == customYear && applied) return
        eraId = newId
        customYear = year
        applied = true
        Anachronisms.era = blockedRegex(era)
    }
    @Volatile private var applied = false

    /** The era an id (or a custom [year]) means. */
    fun resolve(id: String, year: Int = 0): EraPack {
        if (id == CUSTOM && year > 0) return forYear(year)
        return byId[id] ?: byId[defaultEra] ?: eras.first()
    }

    /** The era that covers a release [year] (the one whose span holds it; before the first, the first; after the last, the last). */
    fun forYear(year: Int): EraPack =
        eras.lastOrNull { year >= it.release } ?: eras.first()

    private fun orderOf(id: String) = byId[id]?.order ?: 0

    /** The profile of an NPC: by the named character's id, else the NPC's id, else the name. Null: a made-up NPC. */
    fun profileOf(p: NpcProfile): CharProfile? =
        (p.named?.let(profiles::get)) ?: profiles[p.id] ?: byName[p.name.lowercase()]

    // ---- the era's blocklist

    /** The terms that appear only after [e]. */
    fun blockedAfter(e: EraPack): List<String> = eras.filter { it.order > e.order }.flatMap { it.introduces }.distinct()

    private val regexes = HashMap<String, Regex?>()

    private fun blockedRegex(e: EraPack): Regex? = synchronized(regexes) {
        regexes.getOrPut(e.id) {
            val words = blockedAfter(e)
            if (words.isEmpty()) null
            else Regex("(?<![\\p{L}\\p{N}'])(${words.joinToString("|") { Regex.escape(it) }})(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        }
    }

    /** The first term in [line] that this era does not know, or null. */
    fun anachronism(line: String, e: EraPack = era): String? = blockedRegex(e)?.find(line)?.value

    // ---- the pieces

    private fun state(c: CharProfile, e: EraPack): EraState? = c.states[e.id]

    /** The character core: the sheet, trimmed to [max] tokens (the least important parts dropped first). */
    fun core(c: CharProfile, e: EraPack = era, max: Int = PersonaBudget.CORE): String {
        fun f(k: String) = c.field(k, e.order, ::orderOf).trim()
        val voice = c.voice.filter { v -> (v.from?.let(::orderOf) ?: Int.MIN_VALUE) <= e.order && (v.until?.let(::orderOf) ?: Int.MAX_VALUE) >= e.order }
        fun sample(n: Int): List<String> {
            if (voice.isEmpty() || n == 0) return emptyList()
            val start = (e.order * 3) % voice.size
            return (0 until minOf(n, voice.size)).map { voice[(start + it) % voice.size].text }
        }
        // all the fields with three, two, one sample lines; then without the values, without the look, without the intimacy; last, no sample lines
        val attempts = (0..3).flatMap { drop -> listOf(3, 2, 1).map { drop to it } } + (0..3).map { it to 0 }
        for ((drop, lines) in attempts) {
            val b = StringBuilder()
            b.append("Who you are: ").append(f("who")).append('\n')
            b.append("Nature: ").append(f("temper")).append('\n')
            b.append("How you speak: ").append(f("speech")).append('\n')
            if (drop < 1) b.append("What you hold dear: ").append(f("values")).append('\n')
            if (drop < 2) b.append("Your look: ").append(f("look")).append('\n')
            if (drop < 3) b.append("In intimacy: ").append(f("intimate")).append('\n')
            val s = sample(lines)
            if (s.isNotEmpty()) b.append("You sound like this: ").append(s.joinToString(" / ") { "\"$it\"" }).append('\n')
            b.append("Stay in this voice and this mind in every line.")
            val out = b.toString()
            if (Tokens.approx(out) <= max) return out
        }
        return Tokens.fit("Who you are: ${f("who")} Nature: ${f("temper")}", max)
    }

    /** The era slice for a named NPC, or the era's plain frame (no NPC state) when [c] is null; at most [max] tokens. */
    fun eraSlice(c: CharProfile?, e: EraPack = era, max: Int = PersonaBudget.ERA): String {
        val st = c?.let { state(it, e) }
        val state = st?.let {
            if (it.outOfEra) "You are placed here out of your own time, so live it as you would be then: ${it.text}" else "Your life now: ${it.text}"
        }
        val cut = "Nothing later than this age is known to you."
        val tries = listOf(
            listOfNotNull("The age: ${e.label}, ${e.years}.", e.world, e.idiom, cut, state),
            listOfNotNull("The age: ${e.label}.", e.world, cut, state),
            listOfNotNull("The age: ${e.label}.", cut, state),
        )
        for (t in tries) { val s = t.joinToString(" "); if (Tokens.approx(s) <= max) return s }
        val stateText = Tokens.fit(state.orEmpty(), max - 20)
        return Tokens.fit("The age: ${e.label}. $cut $stateText", max)
    }

    /** What a made-up NPC of this race, class and side knows of the age (at most [max] tokens): by faction, then a frame for a race or calling that does not exist yet. */
    fun customNote(raceKey: String?, faction: Faction, className: String?, e: EraPack = era, max: Int = PersonaBudget.CUSTOM): String? {
        val parts = ArrayList<String>()
        e.folk[faction.key]?.let { parts += it }
        raceKey?.let { e.races[it] }?.let { parts += it }
        className?.lowercase()?.let { e.classes[it] }?.let { parts += it }
        if (parts.isEmpty()) return null
        return Tokens.fit(parts.joinToString(" "), max)
    }

    /** The whole system-prompt addition for [p]: the core and era slice of a named NPC, the era frame and custom note otherwise. */
    fun systemAddition(p: NpcProfile, lore: Lore): String {
        val c = profileOf(p)
        val e = era
        if (c != null) return core(c, e) + "\n" + eraSlice(c, e)
        return listOfNotNull(eraSlice(null, e), customNote(lore.raceKey(p.raceId), lore.faction(p.raceId), lore.className(p.classId), e)).joinToString("\n")
    }

    // ---- per turn

    private fun wordHit(text: String, key: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}'])${Regex.escape(key.lowercase())}(?![\\p{L}\\p{N}])").containsMatchIn(text)

    private fun applies(t: Topic, e: EraPack) = (t.from?.let(::orderOf) ?: Int.MIN_VALUE) <= e.order && (t.until?.let(::orderOf) ?: Int.MAX_VALUE) >= e.order

    private fun relationFor(c: CharProfile, id: String, name: String, e: EraPack): Relation2? =
        (c.relations[id] ?: c.relations.values.firstOrNull { it.name.equals(name, true) })?.takeIf { (it.from?.let(::orderOf) ?: Int.MIN_VALUE) <= e.order }

    /** The relationship snippet of [c] toward [r] in [e]: the default, then the era's own line. */
    fun relationText(c: CharProfile, r: Relation2, e: EraPack = era, max: Int = PersonaBudget.REL): String {
        val line = r.eras.entries.filter { orderOf(it.key) <= e.order }.maxByOrNull { orderOf(it.key) }?.value   // the latest line of the era or before it
        val body = if (line != null) "${r.default} Now: $line" else r.default
        val addr = if (r.address.isNotBlank()) " You call them: ${r.address}." else ""
        return Tokens.fit("${r.name}: $body$addr", max)
    }

    /**
     * The turn's notes for [me]: relationship snippets for the NPCs present (and for a person the talk mentions), and the topic
     * sections the talk mentions (a person, a place, a thing in the NPC's past that has happened by the era). [present]: the others in
     * the scene; [text]: what was just said and what the NPC is to answer. Null: nothing to add.
     */
    fun turnNotes(me: NpcProfile, present: List<NpcProfile>, text: String, e: EraPack = era): String? {
        val c = profileOf(me) ?: return null
        val lower = text.lowercase()
        val out = ArrayList<String>()
        val used = HashSet<String>()
        for (o in present) {
            if (out.size >= PersonaBudget.REL_MAX) break
            val r = relationFor(c, o.named ?: o.id, o.name, e) ?: continue
            if (used.add(r.name)) out += relationText(c, r, e)
        }
        if (out.size < PersonaBudget.REL_MAX) for (r in c.relations.values) {
            if (out.size >= PersonaBudget.REL_MAX) break
            if (r.name in used || (r.from?.let(::orderOf) ?: Int.MIN_VALUE) > e.order) continue
            if (wordHit(lower, r.name.lowercase()) || wordHit(lower, r.name.substringBefore(' ').lowercase())) { used += r.name; out += relationText(c, r, e) }
        }
        val topics = ArrayList<Pair<Int, String>>()
        for (t in c.backstory + c.topics) {
            if (!applies(t, e)) continue
            val hits = t.keys.count { wordHit(lower, it) }
            if (hits > 0) topics += hits to t.text
        }
        fun generic(words: List<String>, label: String, body: String) {
            if (body.isNotBlank() && words.any { wordHit(lower, it) }) topics += 1 to "$label $body"
        }
        generic(listOf("like", "love", "favourite", "favorite", "enjoy", "fond", "hate", "dislike"), "What you like and dislike:", c.likes)
        generic(listOf("afraid", "fear", "scared", "dread", "nightmare"), "What you fear:", c.fears)
        generic(listOf("secret", "hide", "hiding", "confess", "truth"), "A secret you keep, told only if trust is earned:", c.secrets)
        val best = topics.sortedByDescending { it.first }.take(PersonaBudget.TOPIC_MAX).map { Tokens.fit(it.second, PersonaBudget.TOPIC) }
        val all = out.map { "About them: $it" } + best.map { "You recall: $it" }
        return if (all.isEmpty()) null else all.joinToString("\n")
    }

    /** The era a place has of its own (the Broken Isles' Dalaran is Legion's), or null: the scene's chosen era stands. */
    fun eraForPlace(placeName: String): String? { val n = placeName.lowercase(); return placeEras.entries.firstOrNull { it.key in n }?.value?.takeIf { it in byId } }

    /** The era's note on the place named [lowerName] (the first of the era's place keys found in it), or null. */
    fun placeNote(lowerName: String): String? = era.places.entries.firstOrNull { it.key in lowerName }?.value

    /** The stance the era gives [fromKey] toward [toKey] (race keys; factions resolved by the caller), or null. */
    fun stance(keys: List<String>): String? { val s = era.stances; return keys.firstNotNullOfOrNull { s[it] } }

    companion object {
        const val CUSTOM = "custom"

        /** The eras that exist (ids in order) and the default, from assets/dialogue/eras/index.json. */
        fun load(list: (String) -> List<String>, read: (String) -> String?): PersonaKit? {
            val index = JSONObject(read("dialogue/eras/index.json") ?: return null)
            val ids = index.getJSONArray("eras").let { a -> (0 until a.length()).map { a.getString(it) } }
            val eras = ids.mapNotNull { id -> read("dialogue/eras/$id.json")?.let { parseEra(JSONObject(it)) } }.sortedBy { it.order }
            if (eras.isEmpty()) return null
            val profiles = list("dialogue/profiles").filter { it.endsWith(".json") }.mapNotNull { f ->
                runCatching { CharProfile(JSONObject(read("dialogue/profiles/$f") ?: return@mapNotNull null)) }.getOrNull()
            }.associateBy { it.id }
            return PersonaKit(eras, profiles, index.optString("default", "wotlk"), map(index.optJSONObject("placeEras")))
        }

        private fun map(o: JSONObject?): Map<String, String> = o?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty()

        fun parseEra(j: JSONObject) = EraPack(
            j.getString("id"), j.getString("label"), j.getInt("order"), j.getInt("release"), j.optInt("until", j.getInt("release")), j.optString("years"),
            j.getString("present"), j.getString("world"), j.optString("idiom"),
            map(j.optJSONObject("folk")), map(j.optJSONObject("classes")), map(j.optJSONObject("races")), map(j.optJSONObject("stances")),
            j.optJSONArray("introduces")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            map(j.optJSONObject("places")),
        )
    }
}
