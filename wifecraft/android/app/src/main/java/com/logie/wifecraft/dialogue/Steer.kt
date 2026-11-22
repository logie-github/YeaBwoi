package com.logie.wifecraft.dialogue

/** How hard a nudge pushes: a gentle one is a suggestion, a firm one is a thing the NPC really brings up (still in their own way). */
enum class SteerStrength(val label: String) { GENTLE("Gentle"), FIRM("Firm") }

/** One nudge: a topic, a direction or free text. It is a hint for the model, never a line anyone says. */
data class Steer(val text: String, val strength: SteerStrength = SteerStrength.GENTLE) {
    /** The hint as the prompt words it. */
    fun hint(): String = when (strength) {
        SteerStrength.GENTLE -> "A gentle nudge: if it comes naturally, let the talk drift toward \"$text\"."
        SteerStrength.FIRM -> "A firm nudge: bring the talk round to \"$text\" now, in your own way; you may push back only if it truly goes against your nature."
    }
}

/** The topic seeds the steer chips offer (the UI's labels; free text is allowed too). */
object SteerPresets {
    val TOPICS = listOf("the Scourge", "the war", "gossip", "flirt", "their past", "the weather", "the inn", "the Light", "old grudges", "desire", "a fantasy", "the others here")
}

/**
 * The steers in force: for the whole scene, for one NPC and for one pair. The player nudges, never scripts; the director reads
 * [hints] when it asks the model for a line. In memory only, cleared with the scene.
 */
class SteerBoard {
    private val scene = ArrayList<Steer>()
    private val npc = HashMap<String, MutableList<Steer>>()
    private val pair = HashMap<String, MutableList<Steer>>()

    private fun pk(a: String, b: String) = if (a <= b) "$a|$b" else "$b|$a"

    fun addScene(s: Steer) { scene.removeAll { it.text.equals(s.text, true) }; scene += s }
    fun addNpc(id: String, s: Steer) { npc.getOrPut(id) { ArrayList() }.also { l -> l.removeAll { it.text.equals(s.text, true) }; l += s } }
    fun addPair(a: String, b: String, s: Steer) { pair.getOrPut(pk(a, b)) { ArrayList() }.also { l -> l.removeAll { it.text.equals(s.text, true) }; l += s } }

    fun clearScene() = scene.clear()
    fun clearNpc(id: String) { npc.remove(id) }
    fun clearPair(a: String, b: String) { pair.remove(pk(a, b)) }
    fun clearAll() { scene.clear(); npc.clear(); pair.clear() }
    fun remove(text: String) { scene.removeAll { it.text == text }; npc.values.forEach { l -> l.removeAll { it.text == text } }; pair.values.forEach { l -> l.removeAll { it.text == text } } }

    fun scene(): List<Steer> = scene.toList()
    fun forNpc(id: String): List<Steer> = npc[id].orEmpty().toList()
    fun forPair(a: String, b: String): List<Steer> = pair[pk(a, b)].orEmpty().toList()
    /** Every steer in force with what it is set on: the whole scene (npc and other null), one NPC, or a pair. */
    fun entries(): List<SteerEntry> =
        scene.map { SteerEntry(null, null, it) } + npc.flatMap { (id, l) -> l.map { SteerEntry(id, null, it) } } +
            pair.flatMap { (k, l) -> l.map { SteerEntry(k.substringBefore('|'), k.substringAfter('|'), it) } }

    /** Takes off one steer as [entries] lists it. */
    fun remove(e: SteerEntry) {
        when {
            e.npc == null -> scene.removeAll { it.text == e.steer.text }
            e.other == null -> npc[e.npc]?.removeAll { it.text == e.steer.text }
            else -> pair[pk(e.npc, e.other)]?.removeAll { it.text == e.steer.text }
        }
    }

    val isEmpty: Boolean get() = scene.isEmpty() && npc.isEmpty() && pair.isEmpty()

    /** The nudges that apply to [who] speaking to [other] (null: to nobody in particular): theirs, the pair's, the scene's. */
    fun hints(who: String, other: String?): List<Steer> =
        (forNpc(who) + (if (other != null) forPair(who, other) else emptyList()) + scene).distinctBy { it.text.lowercase() }
}

/** One steer in force: on the scene (npc null), an NPC ([other] null) or a pair. */
data class SteerEntry(val npc: String?, val other: String?, val steer: Steer)
