package com.logie.wifecraft.simulation

import com.logie.wifecraft.catalog.Emote
import kotlin.math.exp
import kotlin.random.Random

// Pure Kotlin: the solo animations a JOI mixes in (sexy dancing, naughty gestures, teasing poses, hair play, a blown kiss, a
// look over the shoulder) from the app's single-person library and the model's emotes: classified by their names, picked
// at random with cooldowns, matched to the stage's energy. JVM-tested (JoiAnimationsTest).

/** What a solo clip is for a JOI. */
enum class JoiMove(val energy: Float) {
    DANCE(0.75f), TEASE(0.6f), FLIRT(0.5f), KISS(0.4f), GESTURE(0.4f), POSE(0.35f), HAIR(0.3f), LOOK_BACK(0.3f)
}

/** One solo clip offered: its play key (an imported clip's id, or `emote:<anim>`), its words, its length. */
data class JoiClip(val key: String, val label: String, val group: String = "", val seconds: Float = 6f, val loop: Boolean = false,
                   val emote: Boolean = false)

object JoiAnimations {
    private val SKIP = listOf(" sit", "sleep", " lie", "lying", "kneel", "crouch", "cry", "attack", "fight", "combat", "death", "dead",
        " walk", " run", "swim", " mount", " cast", "mining", "fishing", "cook", "work", "bow ", "salute", "partner", "pair:",
        " sex ", "penetrat", "oral", "blowjob", "fuck", " cum", "orgasm", "stage ", "kill", "stun", "wound")
    private val RULES = listOf(
        JoiMove.DANCE to listOf("dance", "danc", "twerk", "shimmy", "sway", "grind", "hips", "belly"),
        JoiMove.KISS to listOf("blow kiss", "blowkiss", "blow_kiss", "kiss"),
        JoiMove.HAIR to listOf("hair", "ponytail"),
        JoiMove.LOOK_BACK to listOf("look back", "lookback", "over shoulder", "over the shoulder", "glance", "peek"),
        JoiMove.TEASE to listOf("tease", "teasing", "naughty", "seduc", "sensual", "sexy", "strip", "unbutton", "lick", "bite"),
        JoiMove.FLIRT to listOf("flirt", "wink", "bat eyes", "coy", "charm", "bedroom"),
        JoiMove.POSE to listOf("pose", "posing", "hands on hips", "model", "lean", "stretch", "arch"),
        JoiMove.GESTURE to listOf("gesture", "beckon", "come here", "come_here", "come hither", "wave", "point", "shush", "no no", "fan"),
    )

    /** The moves [c]'s words name (empty: not a JOI solo: a sit, a fight, a partner stage...). */
    fun classify(c: JoiClip): Set<JoiMove> {
        val t = " ${c.label} ${c.group} ${c.key}".lowercase()
        if (SKIP.any { it in t }) return emptySet()
        val out = LinkedHashSet<JoiMove>()
        for ((m, words) in RULES) if (words.any { it in t }) out += m
        // "blow kiss" is a kiss, and not also a plain gesture: the first matches win in order, extras only when two kinds differ
        return out
    }

    /** The clips that are JOI solos, and what each is. */
    fun catalogue(clips: List<JoiClip>): Map<JoiClip, Set<JoiMove>> = clips.associateWith(::classify).filterValues { it.isNotEmpty() }

    /** The model's emotes as clips (a text emote or animation; Dance is held, the rest play once). */
    fun fromEmotes(emotes: List<Emote>, key: (Int) -> String = { PlaybackKeys.emote(it) }): List<JoiClip> =
        emotes.filter { it.anim >= 0 }.map { JoiClip(key(it.anim), it.label, it.category, if (it.loop) 8f else 3.5f, it.loop, emote = true) }
            .distinctBy { it.key }
}

/**
 * Picks solo animations at random: each by how near its energy is to the stage's, never the one just played nor any played
 * within [COOLDOWN] seconds, and never two of the same kind in a row. [now] is the caller's clock (seconds).
 */
class JoiAnimationPicker(seed: Long = Random.nextLong()) {
    companion object {
        const val COOLDOWN = 120f
        const val SIGMA = 0.28f
    }
    private val rnd = Random(seed)
    private val last = HashMap<String, Float>()
    private var lastMoves: Set<JoiMove> = emptySet()
    var lastKey: String? = null
        private set

    /** The match of a clip with moves [moves] to a stage of [energy] (0..1): 1 at the same energy. */
    fun fit(moves: Set<JoiMove>, energy: Float): Float =
        moves.maxOfOrNull { m -> exp(-((m.energy - energy) * (m.energy - energy)) / (2 * SIGMA * SIGMA)) } ?: 0f

    /** One clip from [catalogue] for [energy] at [now]; null when none is eligible. The pick is remembered. */
    fun pick(catalogue: Map<JoiClip, Set<JoiMove>>, energy: Float, now: Float): JoiClip? {
        var best: JoiClip? = null
        var bestScore = 0f
        for ((c, moves) in catalogue) {
            if (c.key == lastKey) continue
            if ((last[c.key] ?: Float.NEGATIVE_INFINITY) + COOLDOWN > now) continue
            if (moves.isNotEmpty() && moves == lastMoves && catalogue.size > 3) continue
            val score = fit(moves, energy) * (0.7f + 0.6f * rnd.nextFloat())
            if (score > bestScore) { bestScore = score; best = c }
        }
        val pick = best ?: return null
        last[pick.key] = now
        lastKey = pick.key
        lastMoves = catalogue[pick].orEmpty()
        return pick
    }

    fun reset() { last.clear(); lastKey = null; lastMoves = emptySet() }
}
