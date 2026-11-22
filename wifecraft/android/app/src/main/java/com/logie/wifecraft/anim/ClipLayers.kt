package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural bone clips: a small table of pose keyframes (pitch, yaw and roll in degrees, with the easing of the segment each
 * key starts) per bone, sampled and baked into the engine's track format ([Anim]: rotation tracks, the same arrays as an
 * imported animation). Used for stages only some bodies take part in (Sindragosa's, [com.logie.wifecraft.characters.DragonStages]):
 * the engine lays a baked clip over the body's own game animation (cpp engine/bonelayer.h), so her breathing and her tail's sway
 * go on under it. Pure JVM; the maths is unit-tested (`ClipLayersTest`).
 *
 * Axes are the model's (WoW's): x forward, y left, z up. **Pitch** turns about y, positive tipping the nose down (+x toward -z);
 * **yaw** about z, positive turning to the left; **roll** about x, positive lifting the left side. A key's angles are composed
 * as yaw, then pitch, then roll ([quaternion]).
 *
 * A clip's time is its own: `time` in a key is seconds in the authored clip; [speed] plays it faster (2: twice as fast, half
 * the duration) and [amount] scales every angle (0: nothing moves). A looping clip is baked so its last moment runs back into
 * its first ([sample] wraps from the last key to the first at the duration), so a loop has no seam.
 */
enum class Ease(val code: String) {
    /** Smoothstep: slow in, slow out. */
    SMOOTH("s"),
    LINEAR("l"),
    /** The key's value holds until the next key, then jumps. */
    HOLD("h");

    companion object {
        fun of(code: String?): Ease = entries.firstOrNull { it.code == code } ?: SMOOTH
    }
}

/** One key of a pose track: [time] seconds, the angles in degrees, [ease] the easing of the segment that starts here. */
data class PoseKey(val time: Float, val pitch: Float, val yaw: Float, val roll: Float, val ease: Ease = Ease.SMOOTH)

/** The keys of one bone ([bone]: a WoW-side bone name or, for a creature, "#N": the model's bone N). */
class PoseTrack(val bone: String, keys: List<PoseKey>) {
    val keys: List<PoseKey> = keys.sortedBy { it.time }
    init {
        require(this.keys.isNotEmpty()) { "A pose track needs a key" }
        require(this.keys.all { it.time.isFinite() && it.pitch.isFinite() && it.yaw.isFinite() && it.roll.isFinite() }) { "A key is not a number" }
    }
}

/** One key of a weight curve (0..1 over time): the value at [time], [ease] the easing of the segment that starts here. */
data class WeightKey(val time: Float, val value: Float, val ease: Ease = Ease.SMOOTH)

object ClipMath {
    /** The easing of a segment: [f] 0..1 along it to 0..1. */
    fun ease(ease: Ease, f: Float): Float {
        val x = f.coerceIn(0f, 1f)
        return when (ease) {
            Ease.HOLD -> 0f
            Ease.LINEAR -> x
            Ease.SMOOTH -> x * x * (3f - 2f * x)
        }
    }

    /**
     * The value at [t] seconds of keys ([times] ascending, [values] one per time, the segment from key i to i+1 eased by
     * [eases][i]): before the first key the first holds; with [loop] and a [duration] past the last key, the value runs on from
     * the last key back to the first, which comes again at [duration] (so the clip has no seam); otherwise the last holds.
     */
    fun curve(times: FloatArray, values: FloatArray, eases: Array<Ease>, t: Float, duration: Float, loop: Boolean): Float {
        val n = times.size
        if (n == 0) return 0f
        if (n == 1 || !(t > times[0])) return values[0]
        for (i in 0 until n - 1) {
            if (t < times[i + 1]) {
                val span = times[i + 1] - times[i]
                val f = if (span > 0f) (t - times[i]) / span else 1f
                return values[i] + (values[i + 1] - values[i]) * ease(eases[i], f)
            }
        }
        val last = n - 1
        if (loop && duration > times[last] + 1e-6f) {
            val f = (t - times[last]) / (duration - times[last])
            return values[last] + (values[0] - values[last]) * ease(eases[last], f)
        }
        return values[last]
    }

    /** A pose track's angles (pitch, yaw, roll degrees) at [t]. */
    fun sample(track: PoseTrack, t: Float, duration: Float, loop: Boolean): FloatArray {
        val times = FloatArray(track.keys.size) { track.keys[it].time }
        val eases = Array(track.keys.size) { track.keys[it].ease }
        return floatArrayOf(
            curve(times, FloatArray(times.size) { track.keys[it].pitch }, eases, t, duration, loop),
            curve(times, FloatArray(times.size) { track.keys[it].yaw }, eases, t, duration, loop),
            curve(times, FloatArray(times.size) { track.keys[it].roll }, eases, t, duration, loop),
        )
    }

    /** A weight curve at [t]. */
    fun weight(keys: List<WeightKey>, t: Float, duration: Float, loop: Boolean): Float {
        val s = keys.sortedBy { it.time }
        return curve(FloatArray(s.size) { s[it].time }, FloatArray(s.size) { s[it].value }, Array(s.size) { s[it].ease }, t, duration, loop)
    }

    /** The unit quaternion (x, y, z, w) of [yawDeg] about z, then [pitchDeg] about y, then [rollDeg] about x. */
    fun quaternion(pitchDeg: Float, yawDeg: Float, rollDeg: Float): FloatArray {
        val d = (PI / 180.0)
        fun q(ax: Int, deg: Float): DoubleArray {
            val h = deg * d * 0.5
            val s = sin(h)
            return doubleArrayOf(if (ax == 0) s else 0.0, if (ax == 1) s else 0.0, if (ax == 2) s else 0.0, cos(h))
        }
        fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
        )
        val r = mul(mul(q(2, yawDeg), q(1, pitchDeg)), q(0, rollDeg))
        return FloatArray(4) { r[it].toFloat() }
    }
}

/** The tuning of a baked clip: [speed] (time passes this many times faster) and [amount] (every angle times this). */
data class ClipTuning(val speed: Float = 1f, val amount: Float = 1f, val fps: Int = 15) {
    init {
        require(speed.isFinite() && speed > 0.05f) { "Speed must be above 0.05" }
        require(amount.isFinite() && amount >= 0f) { "Amount can't be negative" }
        require(fps in 1..120) { "fps is 1 to 120" }
    }
}

object ClipLayers {
    /** The number of keys a clip of [duration] authored seconds bakes to at [fps] (at least 2). */
    fun frames(duration: Float, fps: Int): Int = maxOf(2, Math.round(duration * fps))

    /**
     * [tracks] baked as rotation tracks of an [Anim] (id, [name]), evenly keyed at [ClipTuning.fps] over [duration] authored
     * seconds: the baked clip lasts duration / speed, every angle times [ClipTuning.amount], looping when [loop] (its last key
     * runs back into its first). Bone names go through [boneName].
     */
    fun bake(
        id: String, name: String, tracks: List<PoseTrack>, duration: Float, loop: Boolean, tuning: ClipTuning = ClipTuning(),
        boneName: (String) -> String = { it },
    ): Anim {
        require(duration.isFinite() && duration > 0f) { "A clip needs a duration" }
        val n = frames(duration, tuning.fps)
        val out = ArrayList<BoneTrack>()
        for (tr in tracks) {
            val times = FloatArray(n) { it * duration / n / tuning.speed }
            val values = FloatArray(n * 4)
            for (i in 0 until n) {
                val s = ClipMath.sample(tr, i * duration / n, duration, loop)
                ClipMath.quaternion(s[0] * tuning.amount, s[1] * tuning.amount, s[2] * tuning.amount).copyInto(values, i * 4)
            }
            out += BoneTrack(boneName(tr.bone), Channel.ROTATION, times, values)
        }
        return Anim(id, name, "Stage", duration / tuning.speed, loop, out, "layer:$id", 0L)
    }

    /**
     * A weight curve (and any other scalar) baked as a position track "[bone]" (x the value, y and z 0), over the same
     * [duration] and [tuning] as [bake] (the engine reads "layer.hold" and "reach.w" so).
     */
    fun bakeScalar(bone: String, keys: List<WeightKey>, duration: Float, loop: Boolean, tuning: ClipTuning = ClipTuning()): BoneTrack {
        val n = frames(duration, tuning.fps)
        val times = FloatArray(n) { it * duration / n / tuning.speed }
        val values = FloatArray(n * 3)
        for (i in 0 until n) values[i * 3] = ClipMath.weight(keys, i * duration / n, duration, loop)
        return BoneTrack(bone, Channel.POSITION, times, values)
    }

    /** A position track of one key at time 0 (a constant vector: the reach's direction and pole). */
    fun constant(bone: String, x: Float, y: Float, z: Float): BoneTrack =
        BoneTrack(bone, Channel.POSITION, floatArrayOf(0f), floatArrayOf(x, y, z))
}
