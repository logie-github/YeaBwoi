package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.PartOptions
import com.logie.wifecraft.creator.Sex
import org.json.JSONObject

/**
 * Which of an imported set's parts an NPC wears (files/bodyparts/<set>/parts.jsonl, written by [PartPackage.export]), by
 * its [PartOptions]: one DEFAULT part and one ON part whose variant is the options' label ("Medium Uncut High"). Pure JVM.
 *
 * Two entries of one state can carry the same variant: the user's mod has a second DEFAULT "Medium Uncut High"
 * (A45EFB6003C7A1EB, a CAS part named "..._SimplePenisDefault_Medium_High_Unc...") whose mesh has exactly the vertex
 * and triangle counts of DEFAULT "Medium Cut High" (986 / 1722): a copy of that one under the wrong name. So among
 * entries sharing a state and variant the one whose (vertices, triangles) no other variant of its state has wins
 * (E853F196527D7CF4, 1169 / 2057, for DEFAULT and BC041B5233235A43 for ON in that set); a tie goes to the first in the file.
 * A variant the set lacks falls back to the nearest: the most of size, cut and placement matching, size counting most.
 */
object PartChoice {
    data class Entry(val id: String, val name: String, val state: String, val variant: String, val vertices: Int, val triangles: Int, val texture: String?)

    fun parse(jsonl: String): List<Entry> = jsonl.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
        runCatching {
            val o = JSONObject(line)
            Entry(o.getString("id"), o.optString("name"), o.optString("state"), o.optString("variant"), o.optInt("vertices"),
                o.optInt("triangles"), o.optString("texture").takeIf { it.isNotEmpty() && it != "null" })
        }.getOrNull()
    }.toList()

    /** The DEFAULT and ON parts for [options] (either null when the set has no part of that state). */
    fun select(entries: List<Entry>, options: PartOptions): Pair<Entry?, Entry?> =
        pick(entries, PartState.DEFAULT.name, options) to pick(entries, PartState.ON.name, options)

    fun pick(entries: List<Entry>, state: String, options: PartOptions): Entry? {
        val ofState = entries.filter { it.state == state }
        if (ofState.isEmpty()) return null
        val want = listOf(options.size.label, options.cut.label, options.placement.label)
        fun score(e: Entry): Int {
            val have = e.variant.split(' ')
            return (if (want[0] in have) 4 else 0) + (if (want[1] in have) 2 else 0) + (if (want[2] in have) 1 else 0)
        }
        val best = ofState.maxOf { score(it) }
        val candidates = ofState.filter { score(it) == best }
        if (candidates.size == 1) return candidates[0]
        // a copy of another variant's mesh under this variant's name: its size is another variant's
        val sizesOfOthers = ofState.filter { it.variant != candidates[0].variant }.map { it.vertices to it.triangles }.toSet()
        return candidates.firstOrNull { (it.vertices to it.triangles) !in sizesOfOthers } ?: candidates[0]
    }

    /**
     * Whether [a]'s lower body is unclothed (the part may show, [PartDraw.visible]'s bare): No Gear (its underwear or bare),
     * or worn clothing with no chest or robe (slot 5) and no legs (slot 7). The recruit clothing (starter clothing not yet
     * put on) covers it.
     */
    fun lowerBodyBare(a: Appearance): Boolean = when {
        !a.gear -> true
        a.starterClothing && a.clothing.isEmpty() -> false
        else -> 5 !in a.clothing && 7 !in a.clothing
    }

    /** Whether the add-on applies to [a] at all: a man (the add-on is generic; the look decides whether it shows). */
    fun applies(a: Appearance): Boolean = a.sex == Sex.MALE
}

/**
 * The scene's body-part state per NPC (by NPC id): [PartDraw.NONE] for one without the parts, else [PartDraw.DEFAULT] or
 * [PartDraw.ON]. Immutable (each change a new value), so a screen can keep it as its state. Pure.
 */
data class PartStates(private val states: Map<String, Int> = emptyMap()) {
    /** The NPC [id] wears the parts now (its state starts at DEFAULT; one already on keeps its state). */
    fun register(id: String): PartStates = if (id in states) this else copy(states = states + (id to PartDraw.DEFAULT))

    fun remove(id: String): PartStates = copy(states = states - id)

    fun has(id: String): Boolean = id in states

    fun state(id: String): Int = states[id] ?: PartDraw.NONE

    fun isOn(id: String): Boolean = states[id] == PartDraw.ON

    /** ON or back to DEFAULT; nothing for an NPC without the parts. */
    fun setOn(id: String, on: Boolean): PartStates =
        if (id !in states) this else copy(states = states + (id to if (on) PartDraw.ON else PartDraw.DEFAULT))

    fun toggle(id: String): PartStates = setOn(id, !isOn(id))
}
