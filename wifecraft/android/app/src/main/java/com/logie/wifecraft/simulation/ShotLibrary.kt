package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.NameHints
import com.logie.wifecraft.anim.Posture
import org.json.JSONObject
import kotlin.random.Random

// Pure Kotlin (no Android): cinematic mode's shot library (assets/shot_library.json, docs/cinematic-shots.md), read by the
// CinematicDirector. JVM-tested (unit-tests/.../simulation/ShotLibraryTest.kt).

/** How much of the people a shot shows: an establishing wide, a medium (the pair), a close-up (faces), a detail (an insert). */
enum class ShotSize { WIDE, MEDIUM, CLOSE, DETAIL }

/** What a shot looks at: the pair's middle, both faces, one face (A: the lead role, B: the other), the hips' middle, one hip. */
enum class ShotAnchor { PAIR, FACES, HEAD_A, HEAD_B, PELVIS, HIP_A, HIP_B }

/** How a shot is reached: always by a cut (when cuts are allowed), always by a move, or either. */
enum class ShotTransition { CUT, BLEND, ANY }

/**
 * The kind of action an animation is, for the camera: a position class (docs/cinematic-shots.md). [PARTNER]: two people,
 * nothing more known; [SOLO]: one person (a dance, an emote).
 */
enum class ShotClass { KISS, FACING_LYING, REAR, RIDING, SEATED, STANDING, SIDE_LYING, ORAL, MANUAL, PARTNER, SOLO;
    companion object { fun of(name: String?): ShotClass? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
}

/** Options' Shot style: which sizes the director favours. */
enum class ShotStyle(val label: String) {
    CLASSIC("Classic film"), INTIMATE("Intimate"), CLOSE_UPS("Close-ups"), WIDE("Wide"), RANDOM("Random");

    companion object { fun of(name: String?): ShotStyle = entries.firstOrNull { it.name == name } ?: CLASSIC }
}

/** A range [lo]..[hi] a shot picks from. */
data class Span(val lo: Float, val hi: Float) {
    val mid: Float get() = (lo + hi) / 2f
    val half: Float get() = (hi - lo) / 2f
    fun pick(r: Random): Float = if (hi <= lo) lo else lo + r.nextFloat() * (hi - lo)
    operator fun contains(v: Float) = v >= lo - 1e-4f && v <= hi + 1e-4f

    companion object {
        fun of(o: JSONObject, key: String, default: Span): Span {
            val a = o.optJSONArray(key) ?: return o.optDouble(key, Double.NaN).takeIf { !it.isNaN() }?.toFloat()?.let { Span(it, it) } ?: default
            if (a.length() == 0) return default
            val lo = a.optDouble(0, default.lo.toDouble()).toFloat()
            val hi = a.optDouble(if (a.length() > 1) 1 else 0, lo.toDouble()).toFloat()
            return if (hi < lo) Span(hi, lo) else Span(lo, hi)
        }
    }
}

/**
 * One shot of the library: its [id], [size], what it looks at ([anchor]), where the eye is: [azimuth] degrees round the line
 * of action (0: beyond B's end, 90: square to the line, the profile; 180: beyond A's end, over A's shoulder; always on the
 * scene's one side of the line), [pitch] degrees up (negative: a low angle looking up), [distance] in body heights from the
 * anchor, [fov] degrees; its move: [orbit] degrees a second round the anchor (random way), [push] the share of the distance
 * closed over the shot (negative: a pull out), [sway] degrees of handheld sway; [dwell] seconds; [transition]; [over] the
 * person whose shoulder it looks over ("A", "B": they may stand in the line to the face); [crossLine]: a top-down or end-on
 * shot that may sit on the line. [type] is the director's ShotType it is reported as.
 */
data class ShotSpec(
    val id: String, val size: ShotSize, val anchor: ShotAnchor, val type: ShotType,
    val azimuth: Span, val pitch: Span, val distance: Span, val fov: Span,
    val orbit: Span, val push: Span, val sway: Float, val dwell: Span,
    val transition: ShotTransition, val over: Int = -1, val crossLine: Boolean = false,
)

/** One candidate of a class's set and its weight. */
data class ShotChoice(val shot: String, val weight: Float)

/**
 * What is being played, for the camera: its [cls], its stage part ([part]: START, LOOP, END; null: not known) and a [key] that
 * changes whenever the animation or its stage does (the director picks again then).
 */
data class ShotAction(val cls: ShotClass, val part: SegmentKind? = null, val key: String = cls.name)

/**
 * The shot library: [shots] by id, each class's [sets], the words of an animation's names that say its class ([words]: whole
 * tokens as [NameHints.tokens] splits them, the first class found in [classOrder] wins), how each [ShotStyle] and each stage
 * part weighs each size ([styleBias], [stageBias]), the [cadence] the Classic style follows (a wide to set the scene, then
 * medium, close, detail...).
 */
class ShotLibrary(
    val shots: Map<String, ShotSpec>,
    val sets: Map<ShotClass, List<ShotChoice>>,
    val words: Map<String, ShotClass>,
    val classOrder: List<ShotClass>,
    val styleBias: Map<ShotStyle, Map<ShotSize, Float>>,
    val stageBias: Map<SegmentKind, Map<ShotSize, Float>>,
    val cadence: List<ShotSize>,
) {
    /** [cls]'s candidates (only shots the library has), else [ShotClass.PARTNER]'s. */
    fun set(cls: ShotClass): List<Pair<ShotSpec, Float>> {
        val list = (sets[cls] ?: sets[ShotClass.PARTNER] ?: emptyList()).mapNotNull { c -> shots[c.shot]?.let { it to c.weight } }
        return list.ifEmpty { (sets[ShotClass.PARTNER] ?: emptyList()).mapNotNull { c -> shots[c.shot]?.let { it to c.weight } } }
    }

    fun styleWeight(style: ShotStyle, size: ShotSize): Float = styleBias[style]?.get(size) ?: 1f
    fun stageWeight(part: SegmentKind?, size: ShotSize): Float = part?.let { stageBias[it]?.get(size) } ?: 1f

    /**
     * The class of an animation from its [names] (its own, its clips', its pack's: a word of [words]), else from its people's
     * [postures] (the classifier's: anim/Requirements), else from [count] (one person: SOLO); else PARTNER.
     */
    fun classify(names: List<String>, postures: List<Posture> = emptyList(), count: Int = 2): ShotClass {
        if (count <= 1) return ShotClass.SOLO
        val found = HashSet<ShotClass>()
        for (n in names) for (t in NameHints.tokens(n.substringAfterLast(':'))) words[t]?.let { found += it }
        classOrder.firstOrNull { it in found }?.let { return it }
        fromPostures(postures)?.let { return it }
        return ShotClass.PARTNER
    }

    companion object {
        /** The classes' order when several words match (the more specific first). */
        val DEFAULT_ORDER = listOf(ShotClass.ORAL, ShotClass.MANUAL, ShotClass.RIDING, ShotClass.REAR, ShotClass.SIDE_LYING,
            ShotClass.FACING_LYING, ShotClass.SEATED, ShotClass.STANDING, ShotClass.KISS)

        /** The postures' reading: two bodies' postures to a class (null: they say nothing). */
        fun fromPostures(p: List<Posture>): ShotClass? {
            val known = p.filter { it != Posture.UNKNOWN }
            if (known.size < 2) return null
            val s = known.toSet()
            return when {
                Posture.ALL_FOURS in s || Posture.LEAN in s -> ShotClass.REAR
                Posture.LIE_BACK in s && Posture.LIE_FRONT in s -> ShotClass.FACING_LYING
                Posture.LIE_FRONT in s -> ShotClass.REAR
                Posture.LIE_SIDE in s -> ShotClass.SIDE_LYING
                // a squat over someone lying is a straddle; kneeling between or over them reads as partner-facing
                Posture.LIE_BACK in s && Posture.CROUCH in s -> ShotClass.RIDING
                Posture.LIE_BACK in s -> ShotClass.FACING_LYING
                Posture.KNEEL in s && Posture.STAND in s -> ShotClass.ORAL
                s.any { it.seated } -> ShotClass.SEATED
                s == setOf(Posture.STAND) -> ShotClass.STANDING
                else -> null
            }
        }

        /** The library from its JSON (assets/shot_library.json); a broken shot or set is left out, never the whole. */
        fun parse(text: String): ShotLibrary {
            val o = JSONObject(text)
            val shots = LinkedHashMap<String, ShotSpec>()
            val sa = o.optJSONObject("shots") ?: JSONObject()
            for (id in sa.keys()) {
                val s = sa.optJSONObject(id) ?: continue
                runCatching { shots[id] = spec(id, s) }
            }
            val sets = LinkedHashMap<ShotClass, List<ShotChoice>>()
            val so = o.optJSONObject("sets") ?: JSONObject()
            for (name in so.keys()) {
                val cls = ShotClass.of(name) ?: continue
                val arr = so.optJSONObject(name) ?: continue
                val list = ArrayList<ShotChoice>()
                for (id in arr.keys()) {
                    val w = arr.optDouble(id, 1.0).toFloat()
                    if (w > 0f && w.isFinite()) list += ShotChoice(id, w)
                }
                sets[cls] = list
            }
            val words = HashMap<String, ShotClass>()
            val wo = o.optJSONObject("words") ?: JSONObject()
            for (name in wo.keys()) {
                val cls = ShotClass.of(name) ?: continue
                val arr = wo.optJSONArray(name) ?: continue
                for (i in 0 until arr.length()) arr.optString(i).trim().lowercase().takeIf { it.isNotEmpty() }?.let { words[it] = cls }
            }
            val order = o.optJSONArray("classOrder")?.let { a -> (0 until a.length()).mapNotNull { ShotClass.of(a.optString(it)) } }
                ?.takeIf { it.isNotEmpty() } ?: DEFAULT_ORDER
            val styles = HashMap<ShotStyle, Map<ShotSize, Float>>()
            o.optJSONObject("styles")?.let { st -> for (k in st.keys()) ShotStyle.entries.firstOrNull { it.name == k }?.let { styles[it] = sizes(st.optJSONObject(k)) } }
            val stages = HashMap<SegmentKind, Map<ShotSize, Float>>()
            o.optJSONObject("stages")?.let { st -> for (k in st.keys()) SegmentKind.entries.firstOrNull { it.name == k }?.let { stages[it] = sizes(st.optJSONObject(k)) } }
            val cadence = o.optJSONArray("cadence")?.let { a -> (0 until a.length()).mapNotNull { i -> ShotSize.entries.firstOrNull { it.name == a.optString(i) } } }
                ?.takeIf { it.isNotEmpty() } ?: listOf(ShotSize.WIDE, ShotSize.MEDIUM, ShotSize.CLOSE, ShotSize.DETAIL, ShotSize.MEDIUM, ShotSize.CLOSE)
            return ShotLibrary(shots, sets, words, order, styles, stages, cadence)
        }

        private fun sizes(o: JSONObject?): Map<ShotSize, Float> {
            val out = HashMap<ShotSize, Float>()
            if (o == null) return out
            for (k in o.keys()) ShotSize.entries.firstOrNull { it.name == k }?.let { out[it] = o.optDouble(k, 1.0).toFloat().coerceAtLeast(0f) }
            return out
        }

        private fun spec(id: String, s: JSONObject): ShotSpec {
            val size = ShotSize.valueOf(s.getString("size"))
            val anchor = ShotAnchor.valueOf(s.optString("anchor", "PAIR"))
            val type = ShotType.entries.firstOrNull { it.name == s.optString("type") } ?: when (size) {
                ShotSize.WIDE -> ShotType.WIDE
                ShotSize.MEDIUM -> ShotType.ORBIT
                ShotSize.CLOSE, ShotSize.DETAIL -> ShotType.CLOSE_UP
            }
            return ShotSpec(
                id, size, anchor, type,
                azimuth = Span.of(s, "azimuth", Span(60f, 120f)).let { Span(it.lo.coerceIn(0f, 180f), it.hi.coerceIn(0f, 180f)) },
                pitch = Span.of(s, "pitch", Span(10f, 25f)),
                distance = Span.of(s, "distance", Span(1.2f, 1.6f)).let { Span(it.lo.coerceAtLeast(0.3f), it.hi.coerceAtLeast(0.3f)) },
                fov = Span.of(s, "fov", Span(45f, 50f)),
                orbit = Span.of(s, "orbit", Span(0f, 0f)),
                push = Span.of(s, "push", Span(0f, 0f)),
                sway = s.optDouble("sway", 0.0).toFloat().coerceIn(0f, 3f),
                dwell = Span.of(s, "dwell", Span(4f, 8f)),
                transition = ShotTransition.entries.firstOrNull { it.name == s.optString("transition") } ?: ShotTransition.ANY,
                over = when (s.optString("over")) { "A" -> 0; "B" -> 1; else -> -1 },
                crossLine = s.optBoolean("crossLine", false),
            )
        }
    }
}
