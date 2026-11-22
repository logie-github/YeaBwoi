package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * How far an animation bends a body at its worst, over its loop (engine-test measures them: tools/gesture_poses.py):
 * [tilt] the spine's lean from upright (pelvis to head), [bend] the head's bend against the chest (pelvis-to-chest against
 * chest-to-head), both in degrees; [head] the lowest the head gets as a share of its standing height over the pelvis;
 * [reach] the farthest a hand goes from the chest and [handUp] the highest it goes above the head, both in standing torso
 * heights (pelvis to head).
 */
data class PoseMetrics(val tilt: Float, val bend: Float, val head: Float, val reach: Float, val handUp: Float)

/** One pose's joints (pivots, metres, any one frame of reference with z up): what [PoseSanity.measure] reads. */
data class PoseSample(val pelvis: Vec3, val chest: Vec3, val head: Vec3, val leftHand: Vec3? = null, val rightHand: Vec3? = null)

/** What [PoseSanity.judge] found: [ok], and for each bound crossed a short reason. */
data class PoseVerdict(val ok: Boolean, val reasons: List<String>, val metrics: PoseMetrics)

/**
 * Whether a pose is sane for two people talking: a stand with small gestures. The limits sit between the animations that
 * read well in a conversation (Talk, Talk Question, No, a wave) and the ones that fold the body (Laugh bends her to
 * 49 degrees of lean and drops her head to half its height, Exclamation leans 33, the nod (Yes) leans a dwarf and a night elf woman 33, Bow, Cheer, Shout and Rude more): a
 * folded body puts the shoulder armour over the head and the arms out behind her, as the user's two screenshots showed.
 */
object PoseSanity {
    const val MAX_TILT = 20f
    const val MAX_BEND = 30f
    const val MIN_HEAD = 0.92f
    const val MAX_REACH = 2.4f
    const val MAX_HAND_UP = 0.9f

    private fun minus(a: Vec3, b: Vec3) = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
    private fun len(a: Vec3) = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
    private fun degrees(a: Vec3, b: Vec3): Float {
        val la = len(a); val lb = len(b)
        if (la < 1e-6f || lb < 1e-6f) return 0f
        val c = ((a.x * b.x + a.y * b.y + a.z * b.z) / (la * lb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c.toDouble())).toFloat()
    }

    /** The worst of [samples] (one animation's frames over its loop); [standing] is the body's upright pelvis-to-head height. */
    fun measure(samples: List<PoseSample>, standing: Float): PoseMetrics {
        var tilt = 0f; var bend = 0f; var head = Float.MAX_VALUE; var reach = 0f; var handUp = -Float.MAX_VALUE
        val up = Vec3(0f, 0f, 1f)
        for (s in samples) {
            tilt = maxOf(tilt, degrees(minus(s.head, s.pelvis), up))
            bend = maxOf(bend, degrees(minus(s.chest, s.pelvis), minus(s.head, s.chest)))
            head = minOf(head, (s.head.z - s.pelvis.z) / standing)
            for (h in listOfNotNull(s.leftHand, s.rightHand)) {
                reach = maxOf(reach, len(minus(h, s.chest)) / standing)
                handUp = maxOf(handUp, (h.z - s.head.z) / standing)
            }
        }
        if (samples.isEmpty()) return PoseMetrics(0f, 0f, 1f, 0f, -1f)
        return PoseMetrics(tilt, bend, head, reach, handUp)
    }

    fun judge(m: PoseMetrics): PoseVerdict {
        val why = ArrayList<String>()
        if (m.tilt > MAX_TILT) why += "leans ${"%.0f".format(m.tilt)} degrees (limit ${MAX_TILT.toInt()})"
        if (m.bend > MAX_BEND) why += "head bends ${"%.0f".format(m.bend)} degrees against the chest (limit ${MAX_BEND.toInt()})"
        if (m.head < MIN_HEAD) why += "head drops to ${"%.2f".format(m.head)} of its height (limit $MIN_HEAD)"
        if (m.reach > MAX_REACH) why += "a hand reaches ${"%.2f".format(m.reach)} torsos from the chest (limit $MAX_REACH)"
        if (m.handUp > MAX_HAND_UP) why += "a hand rises ${"%.2f".format(m.handUp)} torsos over the head (limit $MAX_HAND_UP)"
        return PoseVerdict(why.isEmpty(), why, m)
    }

    fun judge(samples: List<PoseSample>, standing: Float): PoseVerdict = judge(measure(samples, standing))
}

/**
 * The animations a conversation may play: each game animation id it asks for is looked up in the measured poses
 * ([GesturePoseData]) and played only if [PoseSanity] passes it; an animation that fails, or that nobody measured, is replaced
 * (by plain talking for the speaker, by standing for the listener's nod) and its id logged once to [log] (default: standard error, the phone's logcat).
 */
object ConversationPoses {
    var log: (String) -> Unit = { System.err.println(it) }
    private val reported = HashSet<Int>()
    private val verdicts = HashMap<Int, PoseVerdict?>()

    /** The verdict on game animation [anim] from the measured table, or null when it was not measured. */
    fun verdict(anim: Int): PoseVerdict? = verdicts.getOrPut(anim) { GesturePoseData.ROWS[anim]?.let { PoseSanity.judge(it) } }

    /** What replaces a rejected [anim] in a conversation: plain talking. */
    fun replacement(anim: Int): Int = Gestures.TALK

    /** [anim] if a conversation may play it (the stand, or a measured sane pose), else [instead] (talking for a speaker); -1 stays -1. */
    fun vet(anim: Int, instead: Int = replacement(anim)): Int {
        if (anim < 0 || anim == Gestures.STAND) return anim
        val v = verdict(anim)
        if (v != null && v.ok) return anim
        val swap = instead
        if (synchronized(reported) { reported.add(anim) })
            log("Wifecraft: conversation gesture $anim rejected (${v?.reasons?.joinToString("; ") ?: "its pose was never measured"}); plays $swap instead")
        return swap
    }

    /** Animation ids that were rejected so far (tests). */
    fun rejected(): Set<Int> = synchronized(reported) { reported.toSet() }
    fun resetForTests() { synchronized(reported) { reported.clear() } }
}
