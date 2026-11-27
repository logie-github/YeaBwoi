package com.logie.wifecraft.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Pure Kotlin (JVM-tested: StockSoundTest): the stockings' fabric sounds, synthesised, no files. The friction of the weave under a pulling
// finger is band-limited noise whose pitch and loudness follow the tension and how fast it changes; the rip is a short, deterministic burst
// of crackle with a sharp attack. StockSound.kt plays them through an AudioTrack on a background thread (default off: Interact mode's
// Stockings panel, "Fabric sound").

/** What the friction voice is told: [gain] 0..1, the band's [centerHz] and its sharpness [q]. */
data class Friction(val gain: Float, val centerHz: Float, val q: Float) {
    val silent: Boolean get() = gain < 0.003f
}

object StockSoundCore {
    const val RATE = 22050
    /** The friction is loudest at this much change in tension per second (a quick pull). */
    const val SLOPE_FULL = 2.0f

    /**
     * The friction for the tension [tension] (0..1) that is changing at [slope] per second (positive: pulling harder; negative: springing
     * back). Nothing is heard while the finger is still on slack fabric; a taut fabric held still hums a little; a quick pull is loud, high
     * and narrow; the springback is a short soft hiss.
     */
    fun friction(tension: Float, slope: Float, pulling: Boolean): Friction {
        val t = tension.coerceIn(0f, 1f)
        val speed = min(abs(slope) / SLOPE_FULL, 1f)
        val base = if (pulling) 0.10f * t * t else 0f
        val move = speed * (0.18f + 0.5f * t) * (if (slope < 0f) 0.6f else 1f)
        val gain = (base + move).coerceIn(0f, 0.8f)
        // taut yarn squeaks higher and tighter; a slack one is a low, wide rub
        val hz = 700f + 3300f * t + 600f * (if (slope > 0f) speed else 0f)
        val q = 1.2f + 5.5f * t
        return Friction(if (gain < 0.01f) 0f else gain, hz, q)
    }

    /** The rip's length in samples at [rate]. */
    fun ripSamples(rate: Int = RATE): Int = (rate * 0.42f).toInt()

    /**
     * The rip's amplitude envelope at [t] seconds: a 2 ms attack, a hard first tear that falls fast and a ragged tail of ladder cracks (a fixed
     * set of bumps: the same every time).
     */
    fun ripEnvelope(t: Float): Float {
        if (t < 0f || t > 0.42f) return 0f
        val attack = min(t / 0.002f, 1f)
        val body = exp(-t * 14f)
        var tail = 0f
        for ((at, amp) in CRACKS) { val d = t - at; if (d >= 0f) tail += amp * exp(-d * 90f) }
        val fade = min(1f, (0.42f - t) / 0.06f)
        return (attack * (0.75f * body + tail) * fade).coerceIn(0f, 1f)
    }
    private val CRACKS = listOf(0.045f to 0.35f, 0.085f to 0.28f, 0.12f to 0.4f, 0.17f to 0.22f, 0.2f to 0.3f, 0.26f to 0.16f, 0.31f to 0.12f)

    /** The rip as 16-bit samples: noise (a seeded generator) under the envelope, brighter at the start. Deterministic for [seed]. */
    fun renderRip(seed: Int = 7, rate: Int = RATE): ShortArray {
        val n = ripSamples(rate)
        val out = ShortArray(n)
        var s = seed * 2654435761L.toInt() + 12345
        var lp = 0f
        for (i in 0 until n) {
            s = s * 1664525 + 1013904223
            val white = ((s ushr 8) and 0xFFFF) / 32768f - 1f
            val t = i / rate.toFloat()
            // the noise is high-passed less as the tear goes on: bright at first, dull at the end
            val a = 0.25f + 0.6f * min(t / 0.3f, 1f)
            lp += a * (white - lp)
            val band = white - lp * (0.5f + 0.5f * min(t / 0.3f, 1f))
            out[i] = (band * ripEnvelope(t) * 26000f).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }
}

/** The friction voice: seeded noise through a band-pass whose settings glide to the target (no clicks); [render] fills 16-bit samples. */
class FrictionVoice(seed: Int = 1, private val rate: Int = StockSoundCore.RATE) {
    private var s = seed * 747796405 + 2891336453L.toInt()
    private var z1 = 0f
    private var z2 = 0f
    private var gain = 0f
    private var hz = 1500f
    private var q = 2f

    /** One block of [n] samples toward [target]. */
    fun render(target: Friction, out: ShortArray, n: Int = out.size) {
        for (i in 0 until n) {
            gain += (target.gain - gain) * 0.002f
            hz += (target.centerHz - hz) * 0.002f
            q += (target.q - q) * 0.002f
            s = s * 1664525 + 1013904223
            val x = ((s ushr 8) and 0xFFFF) / 32768f - 1f
            // an RBJ band-pass (constant skirt gain), state-variable form
            val w = 2f * sin((PI * min(hz, rate * 0.45f) / rate).toFloat())
            val damp = 1f / max(q, 0.3f)
            val hp = x - z2 - damp * z1
            z1 += w * hp
            z2 += w * z1
            out[i] = (z1 * gain * 1.6f * 20000f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }
}
