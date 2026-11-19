package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.PairDefinition
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsHash
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Which clips of one package are the two actors of a partner animation, in order of trust:
 *  1. the package's tuning ([PairDefinition]s from sims/AnimTuning): its two actors' clip names, matched to the clips;
 *  2. the names: two clips whose names are the same but for an actor suffix (`_x`/`_y`, `_a`/`_b`, `_0`/`_1`,
 *     `actor0`/`actor1`, `_xx`/`_yy`, `_male`/`_female`, `_p1`/`_p2`), as long as their lengths agree to a frame
 *     (numbered suffixes also need the two clips' actor names to differ: a pose pack numbers its poses);
 *  3. the geometry: exactly two clips left, with different actor names, the same length, and roots that start apart
 *     (0.05 to 1.5 m horizontally, as two actors placed around one origin do).
 * What is left is a single animation, except clips that look like one actor of a pair (another actor's name in the
 * package, an actor suffix, or a tuning entry that could not be completed): those are [Result.candidates], to be
 * paired by hand on the review screen (and still offered as singles). Real two-actor packages were not seen: these
 * rules follow the format's documentation and tools, not tested files.
 */
object Pairing {
    enum class Mode { TUNING, NAME, GEOMETRY, HAND }

    class Found(val a: SimsClip, val b: SimsClip, val def: PairDefinition?, val mode: Mode)

    class Result(val pairs: List<Found>, val singles: List<SimsClip>, val candidates: List<SimsClip>, val notes: List<String>)

    private class Suffix(val t0: String, val t1: String, val numbered: Boolean)

    // longest first, so "_xx" is not read as "_x"
    private val SUFFIXES = listOf(
        Suffix("male", "female", false),
        Suffix("actor_0", "actor_1", true), Suffix("actor0", "actor1", true),
        Suffix("xx", "yy", false), Suffix("p1", "p2", true),
        Suffix("x", "y", false), Suffix("a", "b", false), Suffix("0", "1", true),
    )

    /** [name] split into (stem, suffix kind, role 0 or 1) when it ends in an actor suffix, else null. */
    fun split(name: String): Triple<String, String, Int>? {
        val n = name.trim()
        val tokens = SUFFIXES.flatMap { s -> listOf(Triple(s.t0, s, 0), Triple(s.t1, s, 1)) }.sortedByDescending { it.first.length }
        for ((tok, s, role) in tokens) {
            if (n.length <= tok.length + 1 || !n.endsWith(tok, ignoreCase = true)) continue
            val before = n[n.length - tok.length - 1]
            val glued = tok.startsWith("actor") && before.isLetterOrDigit()   // "kissActor0"
            if (before != '_' && before != '-' && before != ' ' && !glued) continue
            val stem = if (glued) n.dropLast(tok.length) else n.dropLast(tok.length + 1)
            if (stem.isBlank()) continue
            return Triple(stem, s.t0 + "/" + s.t1, role)
        }
        return null
    }

    private fun numbered(kind: String) = SUFFIXES.first { it.t0 + "/" + it.t1 == kind }.numbered

    fun find(clips: List<SimsClip>, defs: List<PairDefinition>): Result {
        val pairs = ArrayList<Found>()
        val notes = ArrayList<String>()
        val used = HashSet<SimsClip>()
        val flagged = LinkedHashSet<SimsClip>()

        // 1. the tuning
        for (def in defs) {
            if (def.actors.size < 2) continue
            val resolved = def.actors.map { s -> s to resolve(clips, s.clipName) }
            if (def.actors.size > 2) {
                notes += "\"${def.name}\" has ${def.actors.size} actors; only animations for two are imported"
                resolved.mapNotNull { it.second }.forEach { flagged += it }
                continue
            }
            val ca = resolved[0].second
            val cb = resolved[1].second
            if (ca == null || cb == null || ca === cb) {
                val missing = resolved.filter { it.second == null }.joinToString { it.first.clipName }
                notes += "\"${def.name}\": " + (if (missing.isNotEmpty()) "its clip $missing is not in this package" else "both actors name the same clip")
                listOfNotNull(ca, cb).forEach { flagged += it }
                continue
            }
            if (!lengthsAgree(ca, cb)) {
                notes += "\"${def.name}\": its two clips' lengths differ (${fmt(ca.duration)} s and ${fmt(cb.duration)} s)"
                flagged += ca; flagged += cb
                continue
            }
            pairs += Found(ca, cb, def, Mode.TUNING)
            used += ca; used += cb
        }

        // 2. the names
        val byStem = LinkedHashMap<Pair<String, String>, MutableList<Pair<SimsClip, Int>>>()
        for (c in clips) {
            if (c in used) continue
            val (stem, kind, role) = split(c.name) ?: continue
            byStem.getOrPut(stem.lowercase() to kind) { ArrayList() } += c to role
        }
        for ((key, members) in byStem) {
            val zero = members.filter { it.second == 0 }
            val one = members.filter { it.second == 1 }
            if (zero.size != 1 || one.size != 1) {
                if (!numbered(key.second)) members.forEach { flagged += it.first }
                continue
            }
            val a = zero[0].first
            val b = one[0].first
            val sameActor = a.actorName.isNotBlank() && a.actorName.equals(b.actorName, ignoreCase = true)
            if (numbered(key.second) && sameActor) continue
            if (!lengthsAgree(a, b)) {
                notes += "${a.name} and ${b.name} look like a pair, but their lengths differ (${fmt(a.duration)} s and ${fmt(b.duration)} s)"
                flagged += a; flagged += b
                continue
            }
            pairs += Found(a, b, null, Mode.NAME)
            used += a; used += b
        }

        // 3. the geometry: the last two clips of a package
        val left = clips.filter { it !in used }
        if (left.size == 2) {
            val (a, b) = left.sortedBy { it.actorName.lowercase() }
            val apart = rootStart(a)?.let { ra -> rootStart(b)?.let { rb -> sqrt((ra[0] - rb[0]) * (ra[0] - rb[0]) + (ra[1] - rb[1]) * (ra[1] - rb[1])) } }
            val actorsDiffer = a.actorName.isNotBlank() && b.actorName.isNotBlank() && !a.actorName.equals(b.actorName, ignoreCase = true)
            if (actorsDiffer && lengthsAgree(a, b) && apart != null && apart in 0.05..1.5) {
                pairs += Found(a, b, null, Mode.GEOMETRY)
                used += a; used += b
            }
        }

        // what is left: singles, and the clips that look like half of a pair
        val actors = clips.map { it.actorName.lowercase() }.filter { it.isNotBlank() }.toSet()
        val candidates = ArrayList<SimsClip>()
        val singles = ArrayList<SimsClip>()
        for (c in clips) {
            if (c in used) continue
            val other = (c.slots.map { it.actor } + c.explicitNamespaces).any { it.isNotBlank() && !it.equals(c.actorName, ignoreCase = true) }
            val looksHalf = c in flagged || actors.size > 1 || other || split(c.name)?.let { !numbered(it.second) } == true
            if (looksHalf) candidates += c else singles += c
        }
        return Result(pairs, singles, candidates, notes)
    }

    /** The tuning's clip name as one of [clips]: exactly, then ignoring case, then without the creator's "name:" prefix. */
    fun resolve(clips: List<SimsClip>, name: String): SimsClip? {
        val n = name.trim()
        if (n.isEmpty()) return null
        return clips.firstOrNull { it.name == n } ?: clips.firstOrNull { it.name.equals(n, ignoreCase = true) }
            ?: clips.firstOrNull { it.name.substringAfterLast(':').equals(n.substringAfterLast(':'), ignoreCase = true) }
    }

    private val ROOT_BIND = SimsHash.fnv32("b__ROOT_bind__")

    /** The root's first horizontal position (Sims x, z), from the b__ROOT_bind__ translation curve; null without one. */
    fun rootStart(c: SimsClip): DoubleArray? {
        val t = c.tracks.firstOrNull { it.boneHash == ROOT_BIND && it.channel == Channel.POSITION && it.values.size >= 3 } ?: return null
        return doubleArrayOf(t.values[0].toDouble(), t.values[2].toDouble())
    }

    /** One frame: the coarser of the two clips' ticks. */
    fun frame(a: SimsClip, b: SimsClip): Float = maxOf(a.tickLength, b.tickLength).takeIf { it > 0f && it.isFinite() } ?: (1f / 30f)

    fun lengthsAgree(a: SimsClip, b: SimsClip): Boolean = abs(a.duration - b.duration) <= frame(a, b) + 1e-4f

    private fun fmt(f: Float) = "%.2f".format(java.util.Locale.ROOT, f)
}

/** Puts two converted actors together as a [PairAnim]: one length, the loop, the sounds, the name. */
object PairAssembly {
    /** Throws when [a] and [b] cannot share a clock: lengths that differ by more than a frame. */
    fun checkLengths(a: SimsClip, b: SimsClip) {
        require(Pairing.lengthsAgree(a, b)) {
            "The two clips' lengths differ (%.2f s and %.2f s), by more than a frame: they were not made to play together"
                .format(java.util.Locale.ROOT, a.duration, b.duration)
        }
    }

    fun assemble(a: SimsClip, b: SimsClip, animA: Anim, animB: Anim, def: PairDefinition?, offA: PairOffset, offB: PairOffset): PairAnim {
        checkLengths(a, b)
        val d = minOf(animA.duration, animB.duration).takeIf { it.isFinite() && it >= 0f } ?: 0f
        val loops = def?.loops ?: (animA.loop || animB.loop)
        val sounds = (a.events.map { it to 0 } + b.events.map { it to 1 })
            .filter { (e, _) -> e.isSound && e.name.isNotBlank() && e.timeSec >= 0f && e.timeSec <= d + 1e-4f }
            .map { (e, actor) -> SoundEvent(e.timeSec.coerceAtMost(d), e.name, actor) }
            .sortedWith(compareBy({ it.timeSec }, { it.actor }))
        val name = def?.name?.takeIf { it.isNotBlank() }
            ?: Pairing.split(a.name)?.first?.substringAfterLast(':')?.trim()?.takeIf { it.isNotEmpty() }
            ?: animA.name.ifBlank { a.name.substringAfterLast(':') }
        return PairAnim(
            id = "", name = name, category = PairAnim.CATEGORY,
            a = trim(animA, d, loops), b = trim(animB, d, loops),
            offsetA = offA, offsetB = offB, loops = loops,
            locations = PairLocations.normalize(def?.locations.orEmpty()), sounds = sounds,
            source = PairAnim.sourceOf(animA.source.ifBlank { a.sourceKey }, animB.source.ifBlank { b.sourceKey }),
            importedAt = System.currentTimeMillis(),
        )
    }

    /** [x] played for [d] seconds (a key past it, the longer clip's last frame, is never reached) and set to [loops]. */
    private fun trim(x: Anim, d: Float, loops: Boolean): Anim = x.copy(duration = d, loop = loops)
}
