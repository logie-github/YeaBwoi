package com.logie.wifecraft.dialogue

import org.json.JSONArray
import org.json.JSONObject

/*
 * The kink and JOI knowledge pack: researched notes the on-device model is handed as short prompt snippets (nothing is
 * trained). Data lives in assets/dialogue/kink (six JSON files); this file loads it and picks the few snippets a prompt can afford.
 * Pure Kotlin plus org.json, so the JVM tests run it. Retrieval is deterministic: the same query gives the same snippets.
 * Everything here is for the app's adult (18+) characters; every entry assumes consent, a way to stop, and care.
 */

/** How comfortable the two are, strangers .. uninhibited. [maxIntensity] caps which entries may be shown. */
enum class Band(val wire: String, val maxIntensity: Int) {
    STRANGERS("strangers", 1), ACQUAINTANCES("acquaintances", 2), FRIENDLY("friendly", 3),
    INTIMATE("intimate", 4), UNINHIBITED("uninhibited", 5);

    companion object {
        fun fromWire(s: String?): Band = entries.firstOrNull { it.wire == s?.lowercase() } ?: STRANGERS
    }
}

/** What the prompt is for. Lower-case words: personalities ("dominant", "shy"), races ("undead", "gnome"), classes ("paladin"). */
data class KinkQuery(
    val band: Band,
    val personalities: Set<String> = emptySet(),
    /** The speaking NPC's role in the dynamic: "dom", "sub", "switch" or null (any). */
    val role: String? = null,
    val races: List<String> = emptyList(),
    val classes: List<String> = emptyList(),
    /** The stage kind: "flirt", "negotiate", "scene", "joi", "care", "aftercare", "talk" ... */
    val stage: String? = null,
    val tags: Set<String> = emptySet(),
    val limit: Int = 3,
    /** The dirty talk level the notes are for (null: none chosen; the comfort band alone decides). From Spicy up a note is the filthy idea, not the safety guidance, and no care line is added to it. */
    val level: DirtyLevel? = null,
)

data class KinkEntry(
    val id: String, val cat: String, val intensity: Int, val role: String, val band: Band,
    val tags: List<String>, val concept: String, val voice: Map<String, String>, val twists: Map<String, String>,
    /** What this is in a filthy mouth, one sentence: shown instead of [concept] from the Spicy level up (null: the concept is). */
    val hot: String? = null,
)

data class KinkExample(
    val id: String, val band: Band, val personalities: List<String>, val races: List<String>,
    val stage: String, val role: String, val tags: List<String>, val lines: List<Pair<String, String>>,
) {
    /** "A: ...\nB: ..." for a prompt. */
    fun render(): String = lines.joinToString("\n") { "${it.first}: ${it.second}" }
}

data class CareEntry(val id: String, val kind: String, val intensity: Int, val band: Band, val text: String, val examples: List<String>)

data class JoiStage(val id: String, val hint: String, val care: String?, val minBand: Band, val pace: String)

/** What the persona worker calls. */
interface KinkContext {
    /** At most [KinkQuery.limit] (max 4) short snippets, about 60 tokens each; includes a care line when the content is intense. */
    fun snippets(query: KinkQuery): List<String>
    /** Few-shot exchanges that fit the query, best first. */
    fun examples(query: KinkQuery, n: Int = 2): List<KinkExample>
    /** One consent-care line (check in, safeword, aftercare) for the query's stage, or null. */
    fun care(query: KinkQuery): String?
    /** A hint for one stage of an in-world JOI, flavoured by personality, race and band; null when the pair is not ready. */
    fun joiHint(stageId: String, query: KinkQuery): String?
    /** The few-shot lines and register of the dirty talk levels (null: the pack has none). */
    fun dirty(): DirtyPack? = null
}

class KinkPack(
    val entries: List<KinkEntry>, val care: List<CareEntry>, val examples: List<KinkExample>,
    val joiStages: List<JoiStage>, val joiVariants: Map<String, String>, val joiTwists: Map<String, String>,
    val joiByBand: Map<String, String>, val twists: Map<String, String>,
    val dirtyPack: DirtyPack? = null,
) : KinkContext {

    override fun dirty(): DirtyPack? = dirtyPack


    private fun roleOk(entryRole: String, q: KinkQuery): Boolean =
        entryRole == "any" || q.role == null || q.role == "switch" || entryRole == q.role

    private fun lower(s: Collection<String>) = s.map { it.lowercase() }.toSet()

    /** Higher is better; negative means not eligible. */
    internal fun score(e: KinkEntry, q: KinkQuery): Double {
        if (e.band.ordinal > q.band.ordinal || e.intensity > q.band.maxIntensity || !roleOk(e.role, q)) return -1.0
        val words = lower(e.tags) + e.cat.lowercase()
        var s = 0.0
        s += 3.0 * lower(q.tags).count { t -> words.any { it == t || it.contains(t) } }
        s += 2.0 * lower(q.personalities).count { it in words }
        val st = q.stage?.lowercase()
        if (st != null) {
            if (st in words) s += 3.0
            if (e.cat in (STAGE_CATS[st] ?: emptyList())) s += 2.5
        }
        s += 2.0 * minOf(2, (lower(q.races) + lower(q.classes)).count { it in e.twists })
        s += 0.2 * e.intensity - 0.1 * (q.band.maxIntensity - e.intensity)  // near the band's ceiling, mildly
        return s
    }

    fun retrieve(q: KinkQuery): List<KinkEntry> {
        val n = q.limit.coerceIn(1, 4)
        return entries.map { it to score(it, q) }.filter { it.second >= 0.0 }
            .sortedWith(compareByDescending<Pair<KinkEntry, Double>> { it.second }.thenBy { it.first.id })
            .take(n).map { it.first }
    }

    private fun twistFor(e: KinkEntry, q: KinkQuery): String? {
        for (k in lower(q.races).sorted() + lower(q.classes).sorted()) e.twists[k]?.let { return it }
        return null
    }

    internal fun render(e: KinkEntry, q: KinkQuery): String {
        val key = when (q.stage?.lowercase()) {
            "care", "aftercare" -> "care"; "negotiate" -> "talk"; "joi" -> "order"; else -> if (q.role == "sub") "beg" else "order"
        }
        if (q.level?.explicit == true && e.hot != null) {
            val sbh = StringBuilder(e.hot)
            twistFor(e, q)?.let { sbh.append(' ').append(it) }
            return clip(sbh.toString(), MAX_CHARS)
        }
        val v = e.voice[key] ?: e.voice["praise"] ?: e.voice.values.firstOrNull()
        val sb = StringBuilder(e.concept)
        if (v != null) sb.append(" e.g. \"").append(v).append('"')
        twistFor(e, q)?.let { sb.append(' ').append(it) }
        return clip(sb.toString(), MAX_CHARS)
    }

    override fun care(query: KinkQuery): String? {
        val kinds = when (query.stage?.lowercase()) {
            "aftercare" -> listOf("aftercare", "drop")
            "negotiate" -> listOf("negotiate", "safeword", "limit")
            "care" -> listOf("checkin", "safeword", "aftercare")
            "joi" -> listOf("checkin", "safeword")
            else -> listOf("checkin", "safeword", "character")
        }
        val ok = care.filter { it.band.ordinal <= query.band.ordinal }
        for (k in kinds) ok.firstOrNull { it.kind == k }?.let { return clip(it.text, MAX_CHARS) }
        return ok.firstOrNull()?.let { clip(it.text, MAX_CHARS) }
    }

    override fun snippets(query: KinkQuery): List<String> {
        val n = query.limit.coerceIn(1, 4)
        val picked = retrieve(query.copy(limit = n))
        val out = picked.map { render(it, query) }.toMutableList()
        val careStage = query.stage?.lowercase() in setOf("care", "aftercare", "negotiate")
        val intense = picked.any { it.intensity >= 3 } || careStage || query.stage?.lowercase() in setOf("scene", "joi")
        // a dirty talk level that is explicit gets no consent-care line mixed into its notes (it makes her sound like a therapist): care and aftercare stages only
        if (intense && (query.level?.explicit != true || careStage)) care(query)?.let { c -> if (out.size >= n) out[out.size - 1] = c else out.add(c) }
        return out
    }

    override fun examples(query: KinkQuery, n: Int): List<KinkExample> {
        val ps = lower(query.personalities)
        fun sc(x: KinkExample): Double {
            if (x.band.ordinal > query.band.ordinal) return -1.0
            var s = 0.0
            if (query.stage != null && x.stage == query.stage.lowercase()) s += 4.0
            s += 2.0 * x.personalities.count { it.lowercase() in ps }
            s += 1.5 * lower(x.races).count { it in lower(query.races) }
            s += 3.0 * lower(query.tags).count { t -> x.tags.any { it.lowercase() == t } }
            if (query.role != null && query.role != "switch" && x.role == query.role) s += 1.0
            if (x.band == query.band) s += 1.0
            return s
        }
        return examples.map { it to sc(it) }.filter { it.second >= 0.0 }
            .sortedWith(compareByDescending<Pair<KinkExample, Double>> { it.second }.thenBy { it.first.id })
            .take(n.coerceIn(0, 4)).map { it.first }
    }

    override fun joiHint(stageId: String, query: KinkQuery): String? {
        val st = joiStages.firstOrNull { it.id == stageId } ?: return null
        if (query.band.ordinal < st.minBand.ordinal) return null
        val pers = lower(query.personalities).sorted().firstNotNullOfOrNull { joiVariants[it] }
        val tw = (lower(query.races).sorted() + lower(query.classes).sorted()).firstNotNullOfOrNull { joiTwists[it] }
        val sb = StringBuilder(st.hint)
        pers?.let { sb.append(' ').append(it) }
        tw?.let { sb.append(' ').append(it) }
        return clip(sb.toString(), MAX_CHARS + 140)
    }

    /**
     * The order of stages for one JOI, deterministic from [seed]: setup, warmup, build, then [edges] cycles of
     * edge + hold, a countdown, and permit (or the release the pair agreed); aftercare always closes it. Band decides how many edges.
     */
    fun joiPlan(band: Band, seed: Long): List<String> {
        if (band.ordinal < Band.INTIMATE.ordinal) return emptyList()
        val rnd = java.util.Random(seed)
        val edges = if (band == Band.UNINHIBITED) 2 + rnd.nextInt(3) else 1 + rnd.nextInt(2)
        val out = mutableListOf("setup", "warmup", "build")
        repeat(edges) { out += "edge"; out += "hold"; out += "build" }
        out += "countdown"; out += "permit"; out += "afterglow"
        return out
    }

    /** A countdown start and a beat length in ms for one JOI, deterministic from [seed]. */
    fun countdown(band: Band, seed: Long): Pair<Int, Int> {
        val rnd = java.util.Random(seed xor 0x5DEECE66DL)
        val start = if (band == Band.UNINHIBITED) listOf(10, 5)[rnd.nextInt(2)] else listOf(5, 3)[rnd.nextInt(2)]
        return start to (1500 + rnd.nextInt(1501))
    }

    companion object {
        const val MAX_CHARS = 260  // about 60 tokens
        private val STAGE_CATS = mapOf(
            "flirt" to listOf("tease", "praise"), "talk" to listOf("tease", "praise"),
            "negotiate" to listOf("care", "protocol", "power"), "care" to listOf("care"), "aftercare" to listOf("care"),
            "scene" to listOf("roleplay", "bondage", "impact", "sensation", "power", "roles"),
            "joi" to listOf("orgasm control", "praise", "sensation", "tease"),
        )

        private fun clip(s: String, max: Int): String {
            if (s.length <= max) return s
            val cut = s.lastIndexOf(' ', max - 1).let { if (it < max / 2) max - 1 else it }
            return s.substring(0, cut).trimEnd(',', ';', ' ') + "…"
        }

        private fun strs(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()
        private fun strMap(o: JSONObject?): Map<String, String> =
            o?.let { x -> x.keys().asSequence().associateWith { k -> x.getString(k) } } ?: emptyMap()

        /** [read] returns the text of a file under the kink folder ("concepts.json", ...) or null. Concepts is required. */
        fun load(read: (String) -> String?): KinkPack {
            val concepts = JSONObject(read("concepts.json") ?: error("kink/concepts.json missing")).getJSONArray("entries")
            val entries = (0 until concepts.length()).map {
                val o = concepts.getJSONObject(it)
                KinkEntry(o.getString("id"), o.getString("cat"), o.getInt("i"), o.optString("role", "any"),
                    Band.fromWire(o.optString("band")), strs(o.optJSONArray("tags")), o.getString("concept"),
                    strMap(o.optJSONObject("voice")), strMap(o.optJSONObject("tw")), o.optString("hot").ifEmpty { null })
            }
            val careArr = read("care.json")?.let { JSONObject(it).getJSONArray("entries") }
            val care = careArr?.let { a -> (0 until a.length()).map {
                val o = a.getJSONObject(it)
                CareEntry(o.getString("id"), o.getString("kind"), o.optInt("i", 1), Band.fromWire(o.optString("band")), o.getString("text"), strs(o.optJSONArray("examples")))
            } } ?: emptyList()
            val exArr = read("examples.json")?.let { JSONObject(it).getJSONArray("examples") }
            val examples = exArr?.let { a -> (0 until a.length()).map {
                val o = a.getJSONObject(it)
                val ls = o.getJSONArray("lines")
                KinkExample(o.getString("id"), Band.fromWire(o.optString("band")), strs(o.optJSONArray("pers")), strs(o.optJSONArray("races")),
                    o.optString("stage"), o.optString("role", "any"), strs(o.optJSONArray("tags")),
                    (0 until ls.length()).map { j -> ls.getJSONObject(j).let { l -> l.getString("who") to l.getString("text") } })
            } } ?: emptyList()
            val joi = read("joi.json")?.let { JSONObject(it) }
            val stages = joi?.optJSONArray("stages")?.let { a -> (0 until a.length()).map {
                val o = a.getJSONObject(it)
                JoiStage(o.getString("id"), o.getString("hint"), o.optString("care").ifEmpty { null }, Band.fromWire(o.optString("min_band", "strangers")), o.optString("pace"))
            } } ?: emptyList()
            val twists = read("twists.json")?.let { strMap(JSONObject(it).optJSONObject("twists")) } ?: emptyMap()
            val dirty = read("dirty.json")?.let { runCatching { DirtyPack.parse(it) }.getOrNull() }
            return KinkPack(entries, care, examples, stages, strMap(joi?.optJSONObject("variants")), strMap(joi?.optJSONObject("twists")),
                strMap(joi?.optJSONObject("by_band")), twists, dirty)
        }
    }
}
