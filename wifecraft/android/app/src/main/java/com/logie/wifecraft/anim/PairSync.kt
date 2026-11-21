package com.logie.wifecraft.anim

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The clips of one performance (two or more roles) kept on one clock when their lengths differ by more than a frame (16 of
 * Anarcis' stages: the audit's LENGTH). The engine plays every role for the longest clip's time and starts them again
 * together, so a shorter clip either stood still at its last pose for the rest of each cycle or, where it loops, jumped back
 * to its start out of step with its partner's. Each shorter clip is made as long as the longest: a loop ([Anim.loop], or its
 * last pose its first) as whole cycles of itself ([Way.LOOP]: the number of its cycles nearest the longer clip's length,
 * each a little slower or faster to fill it exactly: 20 s against 20.4 s plays 2 % slower), anything else holds its last
 * pose to the end ([Way.HOLD]: a key at the end repeating its last). Applied at playback (the library's clips are not
 * changed). Pure.
 */
object PairSync {
    /** How each clip was made to fit: as it was, its cycles stretched or repeated, or its last pose held. */
    enum class Way { SAME, LOOP, HOLD }

    /** Clips within this of the longest (seconds, a frame at 30 a second) are left as they are. */
    const val FRAME = 1f / 30f

    /** Quaternion and position components within this of each other count as the same pose (a clip whose end is its start). */
    private const val SAME_POSE = 0.02f

    /** [anims] each as long as the longest (the same list when none differs by more than [FRAME]). */
    fun synced(anims: List<Anim>): List<Anim> = ways(anims).let { w -> anims.mapIndexed { i, a -> fitted(a, longest(anims), w[i]) } }

    /** The two of a pair, synced. */
    fun synced(a: Anim, b: Anim): Pair<Anim, Anim> = synced(listOf(a, b)).let { it[0] to it[1] }

    /** How each of [anims] is fitted to the longest ([Way.SAME]: not changed). */
    fun ways(anims: List<Anim>): List<Way> {
        val long = longest(anims)
        return anims.map { a ->
            when {
                !(a.duration > 0f) || !a.duration.isFinite() || long - a.duration <= FRAME -> Way.SAME
                a.loop || endIsStart(a) -> Way.LOOP
                else -> Way.HOLD
            }
        }
    }

    private fun longest(anims: List<Anim>): Float = anims.maxOfOrNull { if (it.duration.isFinite()) it.duration else 0f } ?: 0f

    /** [a] fitted to [length] by [way]. */
    fun fitted(a: Anim, length: Float, way: Way): Anim = when (way) {
        Way.SAME -> a
        Way.HOLD -> a.copy(duration = length, tracks = a.tracks.map { held(it, length) })
        Way.LOOP -> {
            val cycles = max(1, (length / a.duration).roundToInt())
            val scale = length / (cycles * a.duration)
            a.copy(duration = length, tracks = a.tracks.map { repeated(it, a.duration, cycles, scale) })
        }
    }

    /** [t] with a key at [length] repeating its last (nothing added when its last key is there already). */
    private fun held(t: BoneTrack, length: Float): BoneTrack {
        val n = t.times.size
        val per = per(t)
        if (n == 0 || t.values.size != n * per || t.times[n - 1] >= length) return t
        val times = t.times.copyOf(n + 1).also { it[n] = length }
        val values = t.values.copyOf((n + 1) * per)
        for (c in 0 until per) values[n * per + c] = t.values[(n - 1) * per + c]
        return BoneTrack(t.bone, t.channel, times, values)
    }

    /** [t] (a cycle [cycle] seconds long) played [cycles] times, its times scaled by [scale]. */
    private fun repeated(t: BoneTrack, cycle: Float, cycles: Int, scale: Float): BoneTrack {
        val n = t.times.size
        val per = per(t)
        if (n == 0 || t.values.size != n * per) return t
        val times = ArrayList<Float>(n * cycles + 1)
        val values = ArrayList<Float>((n * cycles + 1) * per)
        for (k in 0 until cycles) for (i in 0 until n) {
            val at = (t.times[i] + k * cycle) * scale
            // the next cycle's first key where the last one ended: one key, not two at one time
            if (times.isNotEmpty() && at <= times.last() + 1e-6f) continue
            times += at
            for (c in 0 until per) values += t.values[i * per + c]
        }
        // a track whose last key comes before its cycle's end: held there, so the next cycle starts on time
        val end = cycles * cycle * scale
        if (times.last() < end - 1e-6f && t.times[n - 1] < cycle) {
            times += end
            for (c in 0 until per) values += t.values[(n - 1) * per + c]
        }
        return BoneTrack(t.bone, t.channel, times.toFloatArray(), values.toFloatArray())
    }

    /** Whether [a]'s last pose is its first (each track's last key the same as its first): a loop made without the flag. */
    fun endIsStart(a: Anim): Boolean = a.tracks.isNotEmpty() && a.tracks.all { t ->
        val n = t.times.size
        val per = per(t)
        if (n < 2 || t.values.size != n * per) return@all true
        var same = true
        var flip = true
        for (c in 0 until per) {
            val x = t.values[c]; val y = t.values[(n - 1) * per + c]
            if (abs(x - y) > SAME_POSE) same = false
            if (abs(x + y) > SAME_POSE) flip = false
        }
        // a rotation may end on the same turn written with the other sign
        same || (per == 4 && flip)
    }

    private fun per(t: BoneTrack) = if (t.channel == com.logie.wifecraft.sims.Channel.ROTATION) 4 else 3
}
