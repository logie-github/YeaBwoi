package com.logie.wifecraft.simulation

import kotlin.random.Random

// Pure Kotlin (JVM-tested: InteractPinchTest): what a pinch of her skin (two fingers on one body, closing in) means for her and the phone:
// the consent of each finger, her own reaction (a gasp, a flinch, a giggle by region and nature, rate-limited), and the haptics (a short
// pattern as the pinch starts, a gentle continuous one while the fingers close).

/** Allows something at most once every [gapMs] (the clock is the caller's, in ms). */
class ReactionLimiter(private val gapMs: Long) {
    private var last = Long.MIN_VALUE / 4
    /** True (and the clock restarts) when [nowMs] is at least [gapMs] after the last time it said true. */
    fun allow(nowMs: Long): Boolean {
        if (nowMs - last < gapMs) return false
        last = nowMs
        return true
    }
    fun reset() { last = Long.MIN_VALUE / 4 }
}

object PinchReactions {
    /** The reaction kinds a pinch of [region] may get when she allows it, with their weights before her nature. */
    fun allowedKinds(region: TouchRegion): List<Pair<ReactionKind, Float>> = when (region) {
        TouchRegion.CHEST, TouchRegion.BELLY, TouchRegion.HIP, TouchRegion.BUTT, TouchRegion.THIGH ->
            listOf(ReactionKind.FLINCH to 3f, ReactionKind.GIGGLE to 2.5f, ReactionKind.BLUSH to 1.5f, ReactionKind.SHIVER to 1f)
        TouchRegion.GROIN -> listOf(ReactionKind.SHIVER to 3f, ReactionKind.BLUSH to 2.5f, ReactionKind.FLINCH to 2f)
        TouchRegion.NECK, TouchRegion.FACE, TouchRegion.HEAD, TouchRegion.HAIR -> listOf(ReactionKind.FLINCH to 3f, ReactionKind.GIGGLE to 2f, ReactionKind.TURN_HEAD to 1.5f)
        else -> listOf(ReactionKind.GIGGLE to 3f, ReactionKind.FLINCH to 2f, ReactionKind.SMILE to 1f, ReactionKind.TURN_HEAD to 1f)
    }

    private val LINES: Map<String, List<String>> = mapOf(
        "shy" to listOf("Ah! ...that pinches, a little.", "Ow... be gentle.", "H-hey, that's... a bit much."),
        "bold" to listOf("Hey! Careful with the pinching.", "Ha, cheeky. Do that again and see.", "Ow! Keep your fingers to yourself, or don't."),
        "tender" to listOf("Ouch, love, softly.", "Mm, that tickles.", "Easy, easy."),
        "dominant" to listOf("Mind yourself.", "Careful. I notice.", "That's enough of that."),
        "natural" to listOf("Ow, hey!", "Ha, that pinches.", "Oi, careful."),
    )

    /** One line for an allowed pinch, in her style. */
    fun line(p: InteractPersona, rnd: Random): String {
        val pool = LINES[p.disposition.style] ?: LINES.getValue("natural")
        return pool[rnd.nextInt(pool.size)]
    }
}

/**
 * The consent of a pinch, finger by finger. Each finger's body part is judged on its own (a Won't on that part's category, or her nature's
 * resistance, declines that finger: it makes no dent, and she answers once, in character); a finger she allows presses. The pinch over a part
 * that is a Won't therefore never forms (the engine pairs only fingers that both press). Her reaction to the pinch itself is rate-limited.
 */
class PinchGate(
    private val policy: ConsentPolicy?, private val npc: String, private val persona: () -> InteractPersona,
    private val limiter: ReactionLimiter = ReactionLimiter(GAP_MS),
) {
    /** What a finger's sample comes to: [allowed] (it presses), [reaction] (her answer to a refusal, the first time only for that finger). */
    data class Outcome(val allowed: Boolean, val verdict: Verdict, val reaction: Reaction?)

    private val declined = HashSet<Long>()
    private val pinched = HashSet<Pair<Long, Long>>()
    var accepted = 0; private set

    /** The verdict for finger [id] over [hit] (null: not on her: no contact). [rnd] picks her answer to a refusal. */
    fun finger(id: Long, hit: TouchHit?, rnd: Random): Outcome {
        if (hit == null || hit.region == TouchRegion.NONE) return Outcome(false, Verdict.DECLINE, null)
        val p = persona()
        val v = TouchConsent.verdict(policy, npc, hit.region, TouchKind.PINCH, p)
        if (v == Verdict.ALLOW) { declined.remove(id); return Outcome(true, v, null) }
        val first = declined.add(id)
        return Outcome(false, v, if (first) InteractReactions.choose(hit, TouchKind.PINCH, v, p, rnd) else null)
    }

    /**
     * Both fingers of a pinch press: her reaction to it ([hitA], [hitB] the parts under them; the more tender one is what she answers), or null
     * when she has answered lately ([GAP_MS]) or this pair has been answered already (a pinch is answered once, however long it is held).
     */
    fun pinch(a: Long, b: Long, hitA: TouchHit, hitB: TouchHit, nowMs: Long, rnd: Random, recent: List<ReactionKind> = emptyList()): Reaction? {
        val key = minOf(a, b) to maxOf(a, b)
        if (key in pinched) return null
        pinched += key
        accepted++
        if (!limiter.allow(nowMs)) return null
        val hit = if (TouchConsent.sensitivity(hitA.region) >= TouchConsent.sensitivity(hitB.region)) hitA else hitB
        return InteractReactions.choose(hit, TouchKind.PINCH, Verdict.ALLOW, persona(), rnd, recent)
    }

    fun end(id: Long) { declined.remove(id); pinched.removeAll { it.first == id || it.second == id } }
    fun clear() { declined.clear(); pinched.clear() }

    companion object { const val GAP_MS = 2_500L }
}

/** The pinch's haptics: a short double tick as it starts, then a gentle tick now and then while the fingers keep closing. */
class PinchHaptics(private val gapMs: Long = HOLD_GAP_MS) {
    private var lastSpan = 0f
    private var lastAt = Long.MIN_VALUE / 4
    private var startSpan = 0f

    /** The pinch begins with the fingers [spanPx] apart on [region]. */
    fun begin(region: TouchRegion, spanPx: Float): HapticPattern { startSpan = spanPx; lastSpan = spanPx; lastAt = Long.MIN_VALUE / 4; return start(region) }

    /**
     * The fingers are now [spanPx] apart at [nowMs]: a gentle tick (stronger the more they have closed) while the distance shrinks, at most one
     * every [gapMs]; null when they are still, moving apart, or it was too soon.
     */
    fun hold(spanPx: Float, nowMs: Long): HapticPattern? {
        if (!spanPx.isFinite() || startSpan <= 0f) return null
        val shrinking = spanPx < lastSpan - 2f
        if (!shrinking) { if (spanPx > lastSpan) lastSpan = spanPx; return null }
        lastSpan = spanPx
        if (nowMs - lastAt < gapMs) return null
        lastAt = nowMs
        val closed = (1f - spanPx / startSpan).coerceIn(0f, 1f)
        return HapticPattern(longArrayOf(9), intArrayOf((30 + 90 * closed).toInt().coerceIn(1, 255)))
    }

    companion object {
        const val HOLD_GAP_MS = 90L
        /** The start: two short ticks, the stronger the more tender the part. */
        fun start(region: TouchRegion): HapticPattern {
            val amp = (60 + 140 * TouchConsent.sensitivity(region)).toInt().coerceIn(1, 255)
            return HapticPattern(longArrayOf(14, 28, 14), intArrayOf(amp, 0, (amp * 0.7f).toInt().coerceAtLeast(1)))
        }
    }
}
