package com.logie.wifecraft.simulation

import org.json.JSONObject

// Pure Kotlin (no Android): the Consent and limits screen's data. [ActCatalogue] groups the animation catalogue into categories
// (read from the acts' own kind and pack category, so a new pack appears by itself); [ConsentEdit] is what the screen's buttons do
// to an NPC's limits (set a category, set every act of a category, copy, reset); [ConsentBook] keeps every character's limits (and,
// optionally, a scene's own) as JSON. JVM-tested (unit-tests/.../simulation/ConsentBookTest.kt).

/** One category and the acts listed under it. */
data class ActGroup(val category: String, val acts: List<ConsentAct>)

object ActCatalogue {
    /**
     * [acts] (two-person ones; each once) grouped by category in listing order ([ActCategories.sorted]); an act in several
     * categories is listed under each, by name within it.
     */
    fun group(acts: Collection<ConsentAct>): List<ActGroup> {
        val by = LinkedHashMap<String, MutableList<ConsentAct>>()
        for (a in acts.distinctBy { it.id }) if (a.people == 2) for (c in a.categories) by.getOrPut(c) { ArrayList() } += a
        return ActCategories.sorted(by.keys).map { c -> ActGroup(c, by.getValue(c).sortedBy { it.name.lowercase() }) }
    }

    /** [groups] cut to the acts whose name, kind or category has [query] (blank: all); a category named by it keeps all its acts. */
    fun search(groups: List<ActGroup>, query: String): List<ActGroup> {
        val q = query.trim()
        if (q.isEmpty()) return groups
        return groups.mapNotNull { g ->
            if (g.category.contains(q, ignoreCase = true)) g
            else g.acts.filter { it.name.contains(q, ignoreCase = true) || it.kind.contains(q, ignoreCase = true) }.takeIf { it.isNotEmpty() }?.let { ActGroup(g.category, it) }
        }
    }
}

/** What the screen's buttons do to limits (pure: each returns the new limits). */
object ConsentEdit {
    /** [category] set to [stance] for all its [acts]: every act's own choice in it is cleared, so the category speaks for them all. */
    fun setCategory(l: NpcLimits, category: String, stance: Stance, acts: Collection<ConsentAct>): NpcLimits {
        val ids = acts.map { it.id }.toSet()
        return l.withCategory(category, stance).copy(
            acts = l.acts.filterKeys { it !in ids },
            roles = l.roles.filterKeys { k -> k.substringBeforeLast('#') !in ids },
        )
    }

    /** One act's own stance ([stance] null: back to its category's). */
    fun setAct(l: NpcLimits, act: ConsentAct, stance: Stance?): NpcLimits =
        l.withAct(act.id, stance).copy(roles = l.roles.filterKeys { it.substringBeforeLast('#') != act.id })

    /** [from]'s limits given to every one of [to] ([from] itself kept); the rest of [all] as it was. */
    fun copyToAll(all: Map<String, NpcLimits>, from: String, to: Collection<String>): Map<String, NpcLimits> {
        val src = all[from] ?: NpcLimits.EMPTY
        return to.filter { it != from }.associateWith { src }
    }

    /** [to]'s limits made [from]'s. */
    fun copyFrom(all: Map<String, NpcLimits>, from: String, to: String): NpcLimits = if (from == to) all[to] ?: NpcLimits.EMPTY else all[from] ?: NpcLimits.EMPTY

    /** Where a stance comes from, as the screen says it under an act ("set for this act", "from Oral", "default for Oral"). */
    fun describe(r: Resolved): String = when (r.source) {
        Resolved.Source.ROLE -> "set for this role"
        Resolved.Source.ACT -> "set for this act"
        Resolved.Source.CATEGORY -> "from ${r.via}"
        Resolved.Source.DEFAULT -> "default for ${r.via}"
    }

    /** Back to the defaults (their nature's). */
    fun reset(@Suppress("UNUSED_PARAMETER") l: NpcLimits): NpcLimits = NpcLimits.EMPTY

    /** The Limits line of the scene menu. */
    fun summary(limits: Collection<NpcLimits>): String {
        val wont = limits.sumOf { it.wonts }
        val set = limits.count { !it.isEmpty }
        return when {
            set == 0 -> "Limits: defaults"
            wont == 0 -> "Limits: ${if (set == 1) "1 person" else "$set people"} with their own settings"
            else -> "Limits: $wont set to Won't"
        }
    }
}

/**
 * Every character's limits, remembered between runs (the settings keep [toJson]): per character, and optionally per scene (a
 * scene's own, [sceneKey]: its place and cast; where there is one it is used in place of the character's).
 */
class ConsentBook(
    val characters: Map<String, NpcLimits> = emptyMap(),
    val scenes: Map<String, Map<String, NpcLimits>> = emptyMap(),
) {
    /** [npc]'s limits as this scene sees them: the scene's own if it has one, else the character's, else the defaults. */
    fun limitsFor(npc: String, sceneKey: String? = null): NpcLimits =
        (sceneKey?.let { scenes[it]?.get(npc) }) ?: characters[npc] ?: NpcLimits.EMPTY

    /** Whether the scene has limits of its own for [npc]. */
    fun hasScene(npc: String, sceneKey: String): Boolean = scenes[sceneKey]?.containsKey(npc) == true

    /** The book with [npc]'s [limits] kept for the character ([sceneKey] null) or for this scene alone; empty ones are forgotten. */
    fun with(npc: String, limits: NpcLimits, sceneKey: String? = null): ConsentBook =
        if (sceneKey == null) ConsentBook(if (limits.isEmpty) characters - npc else characters + (npc to limits), scenes)
        else {
            val scene = scenes[sceneKey].orEmpty().let { if (limits.isEmpty) it - npc else it + (npc to limits) }
            ConsentBook(characters, if (scene.isEmpty()) scenes - sceneKey else scenes + (sceneKey to scene))
        }

    /** The cast's limits for a scene, as the policy starts with them. */
    fun forCast(ids: Collection<String>, sceneKey: String? = null): Map<String, NpcLimits> =
        ids.associateWith { limitsFor(it, sceneKey) }.filterValues { !it.isEmpty }

    fun toJson(): JSONObject {
        fun chars(m: Map<String, NpcLimits>) = JSONObject().also { o -> for ((k, v) in m.toSortedMap()) if (!v.isEmpty) o.put(k, v.toJson()) }
        return JSONObject().put("v", VERSION).put("characters", chars(characters))
            .put("scenes", JSONObject().also { o -> for ((k, v) in scenes.toSortedMap()) o.put(k, chars(v)) })
    }

    companion object {
        const val VERSION = 1
        val EMPTY = ConsentBook()

        /** The key of a scene: its [place] and its cast (in any order). */
        fun sceneKey(place: String, cast: Collection<String>): String = place + "|" + cast.sorted().joinToString(",")

        /** The book of [json] (null, empty or unreadable: nothing remembered, never an error). */
        fun parse(json: String?): ConsentBook {
            if (json.isNullOrBlank()) return EMPTY
            return runCatching {
                val o = JSONObject(json)
                fun chars(c: JSONObject?): Map<String, NpcLimits> {
                    c ?: return emptyMap()
                    val out = LinkedHashMap<String, NpcLimits>()
                    for (k in c.keys()) NpcLimits.fromJson(c.optJSONObject(k)).takeIf { !it.isEmpty }?.let { out[k] = it }
                    return out
                }
                val scenes = LinkedHashMap<String, Map<String, NpcLimits>>()
                o.optJSONObject("scenes")?.let { s -> for (k in s.keys()) chars(s.optJSONObject(k)).takeIf { it.isNotEmpty() }?.let { scenes[k] = it } }
                ConsentBook(chars(o.optJSONObject("characters")), scenes)
            }.getOrDefault(EMPTY)
        }
    }
}
