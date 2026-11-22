package com.logie.wifecraft.simulation

import com.logie.wifecraft.catalog.Emote
import kotlin.math.exp
import kotlin.random.Random

// Pure Kotlin: the solo animations a JOI mixes in (sexy dancing, naughty gestures, teasing poses, hair play, a blown kiss, a
// look over the shoulder) from the app's single-person library and the model's emotes: classified by their names, picked
// at random with cooldowns, matched to the stage's energy. JVM-tested (JoiAnimationsTest).

/** What a solo clip is for a JOI. */
enum class JoiMove(val energy: Float) {
    DANCE(0.75f), TEASE(0.6f), FLIRT(0.5f), KISS(0.4f), GESTURE(0.4f), POSE(0.35f), HAIR(0.3f), LOOK_BACK(0.3f),
    /** She strokes the air in front of her hips, as if giving a handjob to someone standing close in front of her ([JoiHandjobs]). */
    HANDJOB(0.65f)
}

/** One solo clip offered: its play key (an imported clip's id, or `emote:<anim>`), its words, its length. */
data class JoiClip(val key: String, val label: String, val group: String = "", val seconds: Float = 6f, val loop: Boolean = false,
                   val emote: Boolean = false,
                   /** The partner stage's pack category ([com.logie.wifecraft.anim.AnimationChoice.kind]: HANDJOB...); empty: none. */
                   val kind: String = "",
                   /** A two-person stage's giving role (the index of the role whose single track she plays); -1: not a partner clip. */
                   val giver: Int = -1,
                   /** Where the unseen receiver stands from the giver in the clip, in her frame (yards: forward, left); the picker's placement reads it. */
                   val ghostForward: Float = 0f, val ghostSide: Float = 0f,
                   /** The procedural stroke ([HandStroke]) instead of a library clip. */
                   val procedural: Boolean = false) {
    /** Whether this is a handjob clip she can give alone: the procedural stroke, or a library stage with a known giver. */
    val handjob: Boolean get() = procedural || (giver >= 0 && JoiHandjobs.named(this))

    companion object {
        /** The procedural stroke's key. */
        const val STROKE_KEY = "procedural:handstroke"
        fun stroke() = JoiClip(STROKE_KEY, "Hand stroke", "Handjob", 30f, loop = true, kind = "HANDJOB", procedural = true)
    }
}

object JoiAnimations {
    private val SKIP = listOf(" sit", "sleep", " lie", "lying", "kneel", "crouch", "cry", "attack", "fight", "combat", "death", "dead",
        " walk", " run", "swim", " mount", " cast", "mining", "fishing", "cook", "work", "bow ", "salute", "partner", "pair:",
        " sex ", "penetrat", "oral", "blowjob", "handjob", "hand job", "hand_job", "handy", "jerk", "wank", "fuck", " cum", "orgasm", "stage ", "kill", "stun", "wound")
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
        if (c.handjob) return setOf(JoiMove.HANDJOB)
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
        private val HANDJOBS = setOf(JoiMove.HANDJOB)
    }
    private val rnd = Random(seed)
    private val last = HashMap<String, Float>()
    private var lastMoves: Set<JoiMove> = emptySet()
    var lastKey: String? = null
        private set

    /** The match of a clip with moves [moves] to a stage of [energy] (0..1): 1 at the same energy. */
    fun fit(moves: Set<JoiMove>, energy: Float): Float =
        moves.maxOfOrNull { m -> exp(-((m.energy - energy) * (m.energy - energy)) / (2 * SIGMA * SIGMA)) } ?: 0f

    /**
     * One clip from [catalogue] for [energy] at [now]; null when none is eligible. The pick is remembered. With a [stage] the
     * handjob clips take their share of the picks ([JoiHandjobs.share]: by the stage and what the [framing] shows of her), and the
     * other picks are the other kinds; without one every clip competes by its energy.
     */
    fun pick(catalogue: Map<JoiClip, Set<JoiMove>>, energy: Float, now: Float, stage: String? = null, framing: JoiFraming? = null): JoiClip? {
        if (stage != null) {
            val hands = catalogue.filterValues { JoiMove.HANDJOB in it }
            val rest = catalogue.filterValues { JoiMove.HANDJOB !in it }
            val share = JoiHandjobs.share(stage, framing)
            // (the roll is always drawn: the picks stay the same whether or not a handjob is on offer)
            val roll = rnd.nextFloat()
            if (hands.isNotEmpty() && roll < share && lastMoves != HANDJOBS) pickAmong(hands, energy, now, true)?.let { return it }
            return pickAmong(rest, energy, now, false)
        }
        return pickAmong(catalogue, energy, now, false)
    }

    private fun pickAmong(catalogue: Map<JoiClip, Set<JoiMove>>, energy: Float, now: Float, hands: Boolean): JoiClip? {
        var best: JoiClip? = null
        var bestScore = 0f
        for ((c, moves) in catalogue) {
            if (c.key == lastKey) continue
            // (the procedural stroke has no cooldown of its own: the kind is never twice running)
            if (!c.procedural && (last[c.key] ?: Float.NEGATIVE_INFINITY) + COOLDOWN > now) continue
            if (moves.isNotEmpty() && moves == lastMoves && catalogue.size > 3) continue
            if (moves.isNotEmpty() && moves == lastMoves && hands) continue
            // a library clip is worth more than the procedural one where both are there (the procedural one is the fallback)
            val fit = if (hands) (if (c.procedural) 0.6f else 1f) else fit(moves, energy)
            val score = fit * (0.7f + 0.6f * rnd.nextFloat())
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

/**
 * The handjob clips of a JOI: partner stages of the library whose giving role she plays alone (the other, receiving role is
 * never drawn: its place is an unseen listener's, in front of her), and the procedural stroke ([HandStroke]) as the fallback and
 * for tempo control. Pure.
 */
object JoiHandjobs {
    private val NAME = Regex("hand[ _-]?job|\\bhandy\\b|\\bhandjobs?\\b|jerk|wank|\\bstroking\\b")
    private val NOT = Regex("foot|blow|tit|boob|rim|finger|oral|thigh|anal|vagin|cunni|face|facial|climax")

    /** Whether [c]'s words (or its pack category) say handjob. */
    fun named(c: JoiClip): Boolean {
        if (c.kind.equals("HANDJOB", ignoreCase = true)) return true
        val t = "${c.label} ${c.group} ${c.key}".lowercase()
        return NAME.containsMatchIn(t) && !NOT.containsMatchIn(t)
    }

    /** The pack's wording for a stage (its name, group, id) and its category: whether it is a handjob. */
    fun isHandjob(words: List<String>, kind: String): Boolean =
        kind.equals("HANDJOB", ignoreCase = true) || words.joinToString(" ").lowercase().let { NAME.containsMatchIn(it) && !NOT.containsMatchIn(it) }

    /**
     * The giving role of a two-person stage: the role whose hands come nearest the other's pelvis over the clip (frames of both
     * roles, as [PairSpacing.frames] reads them: their joints are in one frame of reference). -1 when neither role's hands reach
     * the other's body (not a hand stage).
     */
    fun giverRole(a: List<com.logie.wifecraft.anim.BodyFrame>, b: List<com.logie.wifecraft.anim.BodyFrame>, limit: Double = REACH_LIMIT): Int {
        if (a.isEmpty() || b.isEmpty()) return -1
        fun reach(hands: List<com.logie.wifecraft.anim.BodyFrame>, other: List<com.logie.wifecraft.anim.BodyFrame>): Double {
            var best = Double.MAX_VALUE
            for (i in 0 until maxOf(hands.size, other.size)) {
                val h = hands[minOf(i, hands.size - 1)]; val o = other[minOf(i, other.size - 1)]
                val target = o["Pelvis"] ?: o["Root"] ?: continue
                for (j in listOf("L_Hand", "R_Hand")) {
                    val p = h[j] ?: continue
                    val d = Math.sqrt((p[0] - target[0]) * (p[0] - target[0]) + (p[1] - target[1]) * (p[1] - target[1]) + (p[2] - target[2]) * (p[2] - target[2]))
                    if (d < best) best = d
                }
            }
            return best
        }
        val ra = reach(a, b); val rb = reach(b, a)
        if (minOf(ra, rb) > limit) return -1
        return if (ra <= rb) 0 else 1
    }

    /**
     * A handjob among the library's two-person entries as a JOI clip: the giving role found from the clips' own joints (the pack's
     * HANDJOB category, or the words, say it is a handjob; the hands say who gives), and where the unseen receiver stands from her
     * (the stage's slots). Null: not a handjob, not playable, or its giving role can't be told.
     */
    fun fromChoice(c: com.logie.wifecraft.anim.AnimationChoice): JoiClip? {
        if (c.participantCount != 2 || !c.playable) return null
        val words = listOf(c.name, c.group, c.stage) + c.clips.map { it.clip.sourceName.ifEmpty { it.clip.name } }
        if (!isHandjob(words, c.kind)) return null
        val anims = c.clips.map { it.forPlayback() ?: return null }
        val scale = c.roleScale
        val slots = c.roles.map { r -> PartnerSlot.human(r.offset.x * scale, r.offset.y * scale, r.offset.z * scale, Math.toDegrees(r.facing.toDouble()).toFloat()) }
        val a = PairSpacing.frames(slots[0], anims[0]); val b = PairSpacing.frames(slots[1], anims[1])
        // the hands say who gives; a stage the pack itself calls HANDJOB is taken by the nearer hands even if none touches
        val g = giverRole(a, b).takeIf { it >= 0 }
            ?: giverRole(a, b, Double.MAX_VALUE).takeIf { c.kind.equals("HANDJOB", ignoreCase = true) } ?: return null
        val r = 1 - g
        val (f, l) = ghostFrom(slots[g].x, slots[g].y, slots[g].yawDeg, slots[r].x, slots[r].y)
        val seconds = anims.maxOf { it.duration }.takeIf { it > 0f } ?: 6f
        return JoiClip(c.id, c.name, c.group, seconds, loop = c.clips.any { it.inspection.type == com.logie.wifecraft.anim.ClipType.LOOP },
            kind = c.kind, giver = g, ghostForward = f, ghostSide = l)
    }

    /** The hands this near the other's pelvis (body units, about 40 cm) are a hand stage's. */
    const val REACH_LIMIT = 0.35

    /** The receiver's place from the giver ([gx], [gy], [gyawDeg]) in the giver's own frame: (forward, left). Units as given. */
    fun ghostFrom(gx: Float, gy: Float, gyawDeg: Float, rx: Float, ry: Float): Pair<Float, Float> {
        val a = Math.toRadians(gyawDeg.toDouble())
        val dx = rx - gx; val dy = ry - gy
        val f = dx * Math.cos(a) + dy * Math.sin(a)
        val l = -dx * Math.sin(a) + dy * Math.cos(a)
        return f.toFloat() to l.toFloat()
    }

    /** Where the receiver lies from straight ahead of the giver (degrees, left positive). */
    fun ghostAngle(forward: Float, side: Float): Float = Math.toDegrees(Math.atan2(side.toDouble(), forward.toDouble())).toFloat()

    /** The most she is turned from the camera's line to keep the receiver nearer it (degrees). */
    const val MAX_TURN = 35f

    /**
     * How far (degrees, to the left) she is turned from facing the camera, so the receiver (at [ghostAngle] from her front) is
     * as near the line of sight as she is, never past [MAX_TURN]: she keeps facing the camera and her hands stay in front of her.
     */
    fun turn(ghostAngleDeg: Float): Float = (-ghostAngleDeg / 2f).coerceIn(-MAX_TURN, MAX_TURN)

    /**
     * The share of a stage's solo picks that are handjobs: none in the setup and the afterglow, rare in the warm-up, about a third
     * of the picks in the build, the edge and the count, a few at the hold and the permit; none in Face framing, where the hands are
     * out of frame (any other framing, or none known, shows them).
     */
    fun share(stage: String, framing: JoiFraming?): Float {
        if (framing == JoiFraming.FACE) return 0f
        return when (stage) {
            "warmup" -> 0.08f; "build" -> 0.35f; "edge" -> 0.40f; "countdown" -> 0.35f; "hold" -> 0.12f; "permit" -> 0.15f
            else -> 0f
        }
    }

    /**
     * How long a handjob runs: the stage's seconds left (it ends with the stage), at least 8 and at most [MAX_RUN]; a library clip
     * of a known [clipSeconds] repeats over that.
     */
    fun runSeconds(stageLeft: Float): Float = stageLeft.coerceIn(8f, MAX_RUN)
    const val MAX_RUN = 50f
}
