package com.logie.wifecraft.props

import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsFormatException
import com.logie.wifecraft.sims.SimsHash
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A prop's own animation (`prop_animation_clip_name`): where its root bone is over time in the animation's frame (the frame the
 * actors' clips share), sampled every [step] seconds, in Warcraft axes: x forward, y left, z up (the Sims' x left, y up, z forward
 * turned as [PropFile] turns a model). A frame is a position and a rotation (x, y, z, w). The prop is drawn at the animation's
 * origin moved by [sample]; the rest of the object's rig is not skinned (the prop is drawn rigid).
 *
 * File `.wcpa`: u32 "WCPA" 0x41504357, u32 version 1, f32 duration, f32 step, u32 frame count, 7 f32 a frame, u32 CRC-32.
 */
class PropClip(val duration: Float, val step: Float, val frames: FloatArray) {
    val frameCount: Int get() = frames.size / 7

    /** The root's position (x, y, z) and rotation (x, y, z, w) at [t] seconds: held at the ends, linear between frames (the rotation normalised). */
    fun sample(t: Float, out: FloatArray = FloatArray(7)): FloatArray {
        val n = frameCount
        if (n == 0) { out.fill(0f); out[6] = 1f; return out }
        val f = (t / step).coerceIn(0f, (n - 1).toFloat())
        val i = floor(f).toInt().coerceAtMost(n - 1)
        val j = minOf(i + 1, n - 1)
        val a = f - i
        for (k in 0 until 3) out[k] = frames[i * 7 + k] * (1 - a) + frames[j * 7 + k] * a
        var dot = 0f
        for (k in 3 until 7) dot += frames[i * 7 + k] * frames[j * 7 + k]
        val s = if (dot < 0) -1f else 1f
        var len = 0f
        for (k in 3 until 7) { out[k] = frames[i * 7 + k] * (1 - a) + frames[j * 7 + k] * a * s; len += out[k] * out[k] }
        len = sqrt(len).takeIf { it > 1e-6f } ?: 1f
        for (k in 3 until 7) out[k] /= len
        return out
    }

    fun toBytes(): ByteArray {
        val b = ByteBuffer.allocate(20 + frames.size * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC).putInt(1).putFloat(duration).putFloat(step).putInt(frameCount)
        for (v in frames) b.putFloat(v)
        val crc = java.util.zip.CRC32().also { it.update(b.array(), 0, b.position()) }.value.toInt()
        b.putInt(crc)
        return b.array()
    }

    companion object {
        const val MAGIC = 0x41504357
        const val STEP = 1f / 30f
        private const val MAX_FRAMES = 20000

        fun fromBytes(bytes: ByteArray): PropClip {
            fun fail(why: String): Nothing = throw SimsFormatException("The prop animation file is damaged ($why).")
            if (bytes.size < 28) fail("too short")
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val crc = java.util.zip.CRC32().also { it.update(bytes, 0, bytes.size - 4) }.value.toInt()
            if (b.getInt(bytes.size - 4) != crc) fail("checksum")
            if (b.getInt(0) != MAGIC || b.getInt(4) != 1) fail("not a prop animation")
            val n = b.getInt(16)
            if (n < 0 || n > MAX_FRAMES || 20 + n * 28 + 4 != bytes.size) fail("frame count")
            return PropClip(b.getFloat(8), b.getFloat(12), FloatArray(n * 7) { b.getFloat(20 + it * 4) })
        }

        /**
         * The root bone's track of [clip] sampled at [STEP]: the root is the rig's first bone ([rootBone], its hash the clip names; the
         * bone a clip's tracks of a prop's rig name `b__ROOT__` in all the packs seen). A clip with no track for it holds still.
         */
        fun of(clip: SimsClip, rootBone: String = "b__ROOT__"): PropClip {
            val hash = SimsHash.fnv32(rootBone)
            val pos = clip.tracks.firstOrNull { it.boneHash == hash && it.channel == Channel.POSITION }
            val rot = clip.tracks.firstOrNull { it.boneHash == hash && it.channel == Channel.ROTATION }
            val dur = clip.duration.coerceIn(STEP, 600f)
            val n = (kotlin.math.ceil(dur / STEP - 1e-3f).toInt() + 1).coerceIn(2, MAX_FRAMES)
            val frames = FloatArray(n * 7)
            val tmp = FloatArray(4)
            for (i in 0 until n) {
                val t = minOf(i * STEP, dur)
                val p = pos?.let { interp(it.times, it.values, 3, t, tmp) }
                val q = rot?.let { interp(it.times, it.values, 4, t, tmp) }
                // Sims (x, y, z) to Warcraft (x = z, y = x, z = y); a rotation's vector part turns the same way
                val px = p?.get(0) ?: 0f; val py = p?.get(1) ?: 0f; val pz = p?.get(2) ?: 0f
                frames[i * 7] = pz; frames[i * 7 + 1] = px; frames[i * 7 + 2] = py
                if (q != null) {
                    frames[i * 7 + 3] = q[2]; frames[i * 7 + 4] = q[0]; frames[i * 7 + 5] = q[1]; frames[i * 7 + 6] = q[3]
                } else frames[i * 7 + 6] = 1f
            }
            return PropClip(dur, STEP, frames)
        }

        private fun interp(times: FloatArray, values: FloatArray, w: Int, t: Float, out: FloatArray): FloatArray {
            val n = times.size
            if (n == 0) return FloatArray(w).also { if (w == 4) it[3] = 1f }
            if (t <= times[0] || n == 1) return FloatArray(w) { values[it] }
            if (t >= times[n - 1]) return FloatArray(w) { values[(n - 1) * w + it] }
            var hi = 1
            while (hi < n - 1 && times[hi] < t) hi++
            val lo = hi - 1
            val a = (t - times[lo]) / (times[hi] - times[lo]).coerceAtLeast(1e-6f)
            var dot = 0f
            if (w == 4) for (k in 0 until 4) dot += values[lo * 4 + k] * values[hi * 4 + k]
            val s = if (dot < 0) -1f else 1f
            val r = FloatArray(w) { values[lo * w + it] * (1 - a) + values[hi * w + it] * a * (if (w == 4) s else 1f) }
            if (w == 4) { val l = sqrt(r.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 1e-6f } ?: 1f; for (k in 0 until 4) r[k] /= l }
            return r
        }
    }
}
