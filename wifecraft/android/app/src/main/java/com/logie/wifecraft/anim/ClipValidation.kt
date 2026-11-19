package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The import's check of a converted clip, before it is offered for the library: a clip that would play broken is skipped
 * with a plain reason ([ImportPipeline]: ImportEvent.Skipped) rather than saved. Beyond [ClipInspection]'s (no tracks,
 * missing or unsorted keys, values that aren't numbers, rotations that aren't unit, a moving clip with no length):
 * a clip that moves none of the body's main bones, one longer than [MAX_SECONDS], keys far past the clip's end, a root
 * that jumps further than [MAX_ROOT_STEP] between two keys, and a bone that turns more than [MAX_TURN_DEG] between two
 * keys under [FAST_KEYS] seconds apart (a broken curve: the limb would flip or twist in one frame).
 */
object ClipValidation {
    const val MAX_SECONDS = 600f
    const val MAX_ROOT_STEP = 2.0f
    const val MAX_TURN_DEG = 120.0
    const val FAST_KEYS = 0.1f

    /** The body's main bones (WoW-side names): a clip must drive at least one. */
    val BODY = setOf("Root", "Pelvis", "Spine1", "Spine2", "Chest", "Neck1", "Head", "L_Clavicle", "R_Clavicle", "L_UpperArm",
        "R_UpperArm", "L_Forearm", "R_Forearm", "L_Thigh", "R_Thigh", "L_Calf", "R_Calf")

    /** Why [a] would play broken (a sentence for the player), or null when it is fine. */
    fun problem(a: Anim): String? {
        ClipInspection.inspect(a).takeIf { it.type == ClipType.INVALID }?.let { return it.reason ?: "The clip can't be played." }
        if (a.duration > MAX_SECONDS) return "The clip is longer than ten minutes."
        if (a.tracks.none { it.bone in BODY }) return "The clip moves none of the body's main bones (only hands, face or helpers)."
        val end = a.duration
        if (end > 0f && a.tracks.any { it.times.last() > end + 1f }) return "Keyframes run past the clip's end."
        for (t in a.tracks) {
            if (t.channel == Channel.POSITION) {
                if (t.bone != "Root") continue
                for (k in 1 until t.times.size) {
                    val dx = t.values[k * 3] - t.values[k * 3 - 3]
                    val dy = t.values[k * 3 + 1] - t.values[k * 3 - 2]
                    val dz = t.values[k * 3 + 2] - t.values[k * 3 - 1]
                    if (sqrt(dx * dx + dy * dy + dz * dz) > MAX_ROOT_STEP) return "The body jumps away between two keyframes."
                }
            } else {
                for (k in 1 until t.times.size) {
                    if (t.times[k] - t.times[k - 1] > FAST_KEYS) continue
                    var dot = 0.0
                    for (c in 0 until 4) dot += t.values[(k - 1) * 4 + c].toDouble() * t.values[k * 4 + c]
                    // the turn between the two keys, the short way (q and -q are the same turn)
                    val deg = Math.toDegrees(2 * acos(min(1.0, abs(dot))))
                    if (deg > MAX_TURN_DEG) return "A bone (${t.bone}) flips between two keyframes; the clip's curves are damaged."
                }
            }
        }
        return null
    }

    /** A partner animation's [problem]: either clip's, said with its actor. */
    fun problem(p: PairAnim): String? =
        problem(p.a)?.let { "Actor A: $it" } ?: problem(p.b)?.let { "Actor B: $it" }
}
