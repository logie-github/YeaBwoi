package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.story.Vec3
import kotlin.math.sqrt
import kotlin.random.Random

// Pure Kotlin (no Android): cinematic mode's auto-play. Once the mode starts, the two people play one partner animation after
// another, on the places the room offers (a bed, the couch, a chair, the table, the open floor) where their bodies fit
// (FurnitureFit), each for a random while; every change is made under a cut: the picture goes to black (a hard cut or a quick
// fade), the next animation is started with both already on their places (nobody seen walking unless Show movement is on), the
// camera cuts to a new shot of it (CinematicDirector.cutNow: another size or another angle), and only then is the picture shown
// again, so the change of pose is never seen. The planner ([AutoplayPlanner]) chooses; the state machine ([CinematicAutoplay])
// times it and says what the screen does ([AutoStep]). JVM-tested (unit-tests/.../simulation/CinematicAutoplayTest.kt).

/** A place auto-play may use: [key] (the object's name, or "floor"), the scene's [item] (null: the open floor), its [furniture]. */
data class AutoPlace(val key: String, val item: SceneObject?, val furniture: Furniture) {
    val floor: Boolean get() = item == null
}

/**
 * A two-person animation auto-play may play: [id] the partner pair's id, its [name] and what it needs ([requirement], the
 * player's Treat as applied); [floorRests]: needing furniture, lowered onto the open floor it rests there
 * ([FurnitureAvailability.floorRests]), so it may play on the floor where no furniture here takes it.
 */
data class AutoClip(val id: String, val name: String, val requirement: Requirement, val floorRests: Boolean = false)

/** [clip] playable on [place], where [fit] puts it. */
data class AutoOption(val place: AutoPlace, val clip: AutoClip, val fit: FurnitureFit)

/**
 * One part of the sequence: [clip] on [place] for [dwell] seconds; [newPlace]: the place changed (the camera's side is chosen
 * again). [clip] null: a base partner animation (the models' own; nothing installed fits).
 */
data class AutoBeat(val place: AutoPlace?, val clip: AutoClip?, val fit: FurnitureFit?, val dwell: Float, val newPlace: Boolean) {
    val fallback: Boolean get() = clip == null
}

/** What the panel says about the sequence's pool. */
enum class AutoplayNotice(val text: String?) {
    NONE(null),
    NO_PARTNER("Auto-play needs two people: choose a partner."),
    NO_ANIMATIONS("No partner animations installed: playing the base ones."),
    NOTHING_FITS("None of the installed animations fits a place here: playing the base ones."),
}

/**
 * Chooses the sequence: where to go next and what to play there. Only animations every one of whose actors lands on the
 * place at their posture and height ([FurnitureFit.fit], for these bodies' size: [bodyUnit]); no clip twice running while
 * another can be had, and the last [RECENT] left alone where the pool allows; a clip in the same posture as the last weighed
 * down (variety); the same place kept with [STAY_CHANCE] while it has something fresh. Seeded: the same seed, the same film.
 */
class AutoplayPlanner(seed: Long = Random.nextLong()) {
    companion object {
        /** Each animation plays this long (seconds) at the middle pace, before the pace's scale. */
        const val DWELL_MIN = 25f
        const val DWELL_MAX = 90f
        /** How many of the last clips are left alone while the pool has others. */
        const val RECENT = 4
        /** The chance the next animation stays on the place of the last (while it has a fresh one). */
        const val STAY_CHANCE = 0.55f
        /** A clip in the same posture as the last is this much less likely. */
        const val SAME_POSTURE = 0.35f
        /** The open floor is chosen this far (yards) from any furniture's box when it can be. */
        const val FLOOR_CLEAR = 0.8f

        /** The pace (0 slow .. 100 quick, 50 as designed) as a factor on each animation's time: 1.5 .. 0.5. */
        fun paceScale(pace: Int): Float = 1.5f - pace.coerceIn(0, 100) / 100f

        /** The shortest and longest dwell at [pace]. */
        fun dwellRange(pace: Int): ClosedFloatingPointRange<Float> {
            val k = paceScale(pace)
            return DWELL_MIN * k..DWELL_MAX * k
        }

        /**
         * Every clip on every place it fits, for bodies of [bodyUnit] (world units of a standing pelvis); as the scene plays
         * them ([FurnitureAvailability]): a clip that fits no piece here goes where it nearly fits ([FurnitureFit.fit] relaxed),
         * else loosely on any piece, else on the open floor (never left out; [AutoClip.floorRests] only says whether it rests on it).
         */
        fun options(places: List<AutoPlace>, clips: List<AutoClip>, bodyUnit: Float = FurnitureFit.DEFAULT_BODY_UNIT): List<AutoOption> {
            val out = ArrayList<AutoOption>()
            for (c in clips) {
                if (c.requirement.actors.size != 2) continue
                val before = out.size
                for (p in places) FurnitureFit.fit(c.requirement, p.furniture, bodyUnit)?.let { out += AutoOption(p, c, it) }
                if (out.size > before || c.requirement.surface in Requirement.FLOOR_LEVEL) continue
                for (p in places) if (!p.floor) FurnitureFit.fit(c.requirement, p.furniture, bodyUnit, relaxed = true)?.let { out += AutoOption(p, c, it) }
                if (out.size > before) continue
                // what a clip is marked for is only a preference: loosely on any piece (the marked ones came first), else the floor
                for (p in places) if (!p.floor) FurnitureFit.fit(c.requirement, p.furniture, bodyUnit, loose = true)?.let { out += AutoOption(p, c, it) }
                if (out.size > before) continue
                places.firstOrNull { it.floor }?.let { p ->
                    out += AutoOption(p, c, FurnitureFit(p.key, p.furniture.x, p.furniture.y, p.furniture.z, 0f, emptyList(), emptyList(), 0f, 0f))
                }
            }
            return out
        }

        /**
         * Where on the open floor the pair goes: the first of [starts] (where they stood) clear of every piece's box by
         * [FLOOR_CLEAR], else a spot that far in front of a piece (beside the first clear one), else the first start; at [floorZ].
         */
        fun floorSpot(starts: List<Vec3>, furniture: List<Furniture>, floorZ: Float): Vec3? {
            fun clear(x: Float, y: Float) = furniture.none { f ->
                val (lx, ly) = f.toLocal(x, y)
                kotlin.math.abs(lx) < f.spec.halfX + FLOOR_CLEAR && kotlin.math.abs(ly) < f.spec.halfY + FLOOR_CLEAR
            }
            starts.firstOrNull { clear(it.x, it.y) }?.let { return Vec3(it.x, it.y, floorZ) }
            for (f in furniture) {
                for (side in listOf(1f, -1f)) {
                    val p = f.toWorld(side * (f.spec.halfX + FLOOR_CLEAR + 0.6f), 0f)
                    if (clear(p.x, p.y)) return Vec3(p.x, p.y, floorZ)
                }
            }
            return starts.firstOrNull()?.let { Vec3(it.x, it.y, floorZ) }
        }
    }

    private val random = Random(seed)
    private val recent = ArrayDeque<String>()
    /** Clip-on-place pairs the scene refused (it didn't fit these bodies there, the place was held): not tried again. */
    private val refused = HashSet<Pair<String, String>>()

    /** A random dwell at [pace] (seconds). */
    fun dwell(pace: Int): Float {
        val r = dwellRange(pace)
        return r.start + random.nextFloat() * (r.endInclusive - r.start)
    }

    /** [beat] could not be played: its clip is not tried on its place again (this scene). */
    fun refuse(beat: AutoBeat) {
        val c = beat.clip ?: return
        val p = beat.place ?: return
        refused += p.key to c.id
    }

    /**
     * The next beat after [previous] from [options] for [people] people at [pace]: null when there aren't two; a base partner
     * animation ([AutoBeat.fallback]) when no option is left.
     */
    fun next(options: List<AutoOption>, previous: AutoBeat?, pace: Int, people: Int = 2): AutoBeat? {
        if (people < 2) return null
        val usable = options.filter { (it.place.key to it.clip.id) !in refused }
        if (usable.isEmpty()) return AutoBeat(null, null, null, dwell(pace), newPlace = previous?.place != null || previous == null)
        val lastClip = previous?.clip?.id
        val lastPlace = previous?.place?.key
        fun fresh(list: List<AutoOption>): List<AutoOption> {
            val other = list.filter { it.clip.id != lastClip }
            return other.filter { it.clip.id !in recent }.ifEmpty { other }
        }
        val byPlace = usable.groupBy { it.place.key }
        val here = lastPlace?.let { byPlace[it] }?.let(::fresh).orEmpty()
        val elsewhere = byPlace.filterKeys { it != lastPlace }.mapValues { fresh(it.value) }.filterValues { it.isNotEmpty() }
        val pool: List<AutoOption> = when {
            here.isNotEmpty() && (elsewhere.isEmpty() || random.nextFloat() < STAY_CHANCE) -> here
            elsewhere.isNotEmpty() -> {
                // another place: each about as likely, a little more by how much it offers
                val keys = elsewhere.keys.sorted()
                val w = FloatArray(keys.size) { sqrt(elsewhere.getValue(keys[it]).size.toFloat()) }
                elsewhere.getValue(keys[weighted(w)])
            }
            // only the clip just played is left: it plays again (with a new shot)
            else -> usable.filter { it.place.key == lastPlace }.ifEmpty { usable }
        }
        val lastPosture = previous?.clip?.requirement?.let { it.actors.getOrNull(it.primary)?.posture }
        val w = FloatArray(pool.size) { i ->
            val r = pool[i].clip.requirement
            if (lastPosture != null && r.actors.getOrNull(r.primary)?.posture == lastPosture) SAME_POSTURE else 1f
        }
        val o = pool[weighted(w)]
        recent.addLast(o.clip.id)
        while (recent.size > RECENT) recent.removeFirst()
        return AutoBeat(o.place, o.clip, o.fit, dwell(pace), newPlace = previous == null || o.place.key != lastPlace)
    }

    private fun weighted(w: FloatArray): Int {
        val total = w.sum()
        if (!(total > 0f)) return random.nextInt(w.size)
        var x = random.nextFloat() * total
        for (i in w.indices) { x -= w[i]; if (x <= 0f) return i }
        return w.size - 1
    }
}

/** Where auto-play is: off; an animation playing; going to black; the next one being started; held black; coming back. */
enum class AutoPhase { OFF, PLAY, COVER, SWAP, HOLD, REVEAL, WALK }

/** What the screen is asked to do. */
sealed interface AutoStep {
    /** Start [beat] (both on their places at once when [teleport]); answer with [CinematicAutoplay.swapped]. */
    data class Swap(val beat: AutoBeat, val teleport: Boolean) : AutoStep
    /** Cut the camera to a new shot of what now plays (another size or angle); [resetSide]: the place changed (the 180-degree side chosen again). */
    data class Cut(val resetSide: Boolean) : AutoStep
}

/**
 * Auto-play's timing: [start] it as cinematic mode starts; [tick] it every frame; answer each [AutoStep.Swap] with [swapped].
 * Every change goes: choose, cover (the picture to black: [cover] 0..1, over the cut style's fade, at once for a hard cut),
 * swap (the next animation started under it), hold (the engine's pose crossfade and the camera's cut happen in the dark),
 * reveal (back over the same fade). [next] skips to the next now, [paused] stops the clock, [stay] keeps this animation.
 * With [showMovement] a change of place is made in the open (they walk there; the cut comes as they start).
 */
class CinematicAutoplay(private val planner: AutoplayPlanner = AutoplayPlanner()) {
    companion object {
        /** Black at least this long before the swap (the screen has drawn it). */
        const val BLACK_S = 0.05f
        /** Held black after the swap: the engine's pose crossfade (0.25 s, JoinEase.JOIN) and the camera's cut inside it. */
        const val HOLD_S = 0.32f
        /** When in the hold the camera is asked to cut (the new clip's points are in the stage by then). */
        const val CUT_AT_S = 0.12f
        /** No answer to a swap in this long: refused. */
        const val SWAP_TIMEOUT_S = 2f
        /** Refused swaps tried again with another choice before the base animations are used. */
        const val MAX_TRIES = 4
        /** With Show movement: a walk not done in this long is cut anyway. */
        const val WALK_TIMEOUT_S = 20f
    }

    var options: List<AutoOption> = emptyList()
    /** How many people take part (two, or fewer: nothing plays). */
    var people = 2
    /** Whether any partner animation is installed at all (for the notice when nothing fits). */
    var installed = 0
    var pace = 50
    var cutStyle = CutStyle.FADE
    var showMovement = false
    var paused = false
    /** Keep the animation now playing until this is off again (its dwell then starts afresh). */
    var stay = false
        set(v) { if (field && !v) elapsed = 0f; field = v }

    var phase = AutoPhase.OFF
        private set
    /** 0..1: how black the picture is now (the screen draws it over the 3D view). */
    var cover = 0f
        private set
    /** The animation playing (or being started). */
    var beat: AutoBeat? = null
        private set
    /** Seconds the beat has played. */
    var elapsed = 0f
        private set
    val notice: AutoplayNotice get() = when {
        people < 2 -> AutoplayNotice.NO_PARTNER
        beat?.fallback == true || (phase != AutoPhase.OFF && options.isEmpty()) ->
            if (installed == 0) AutoplayNotice.NO_ANIMATIONS else AutoplayNotice.NOTHING_FITS
        else -> AutoplayNotice.NONE
    }
    /** Every step of every change, in order ("choose", "cover", "swap", "cut", "reveal", "play"; tests and the log). */
    val trace = ArrayList<String>()

    private var pending: AutoBeat? = null
    private var t = 0f
    private var tries = 0
    private var cutSent = false
    private var walking = false
    private var cutOnly = false

    private fun log(s: String) { trace += s; if (trace.size > 400) trace.subList(0, 100).clear() }

    /** Cinematic mode started: the first animation is chosen and started under a cover. */
    fun start(): List<AutoStep> {
        if (phase != AutoPhase.OFF) return emptyList()
        beat = null
        elapsed = 0f
        phase = AutoPhase.PLAY
        return begin()
    }

    /** Cinematic mode ended: nothing more is changed (what plays goes on), the cover goes at once. */
    fun stop() {
        phase = AutoPhase.OFF
        cover = 0f
        pending = null
    }

    /** Next scene: the change now (while an animation plays). */
    fun next(): List<AutoStep> = if (phase == AutoPhase.PLAY) begin() else emptyList()

    fun tick(dt: Float): List<AutoStep> {
        if (phase == AutoPhase.OFF || !(dt >= 0f) || !dt.isFinite()) return emptyList()
        val d = dt.coerceAtMost(0.25f)
        t += d
        val fade = cutStyle.fadeS
        return when (phase) {
            AutoPhase.PLAY -> {
                if (!paused && !stay && beat != null) elapsed += d
                val b = beat
                if (people >= 2 && (b == null || (elapsed >= b.dwell && !paused && !stay))) begin() else emptyList()
            }
            AutoPhase.COVER -> {
                cover = if (fade <= 0f) 1f else (t / fade).coerceAtMost(1f)
                if (cover >= 1f && t >= fade + BLACK_S) {
                    if (cutOnly) {
                        cutOnly = false
                        phase = AutoPhase.HOLD; t = 0f; cutSent = false
                        emptyList()
                    } else {
                        val p = pending ?: return reveal()
                        phase = AutoPhase.SWAP; t = 0f
                        log("swap")
                        listOf(AutoStep.Swap(p, teleport = !(showMovement && p.newPlace)))
                    }
                } else emptyList()
            }
            AutoPhase.SWAP -> if (t >= SWAP_TIMEOUT_S) swapped(false) else emptyList()
            AutoPhase.HOLD -> {
                val out = ArrayList<AutoStep>(1)
                if (!cutSent && t >= CUT_AT_S) {
                    cutSent = true
                    log("cut")
                    out += AutoStep.Cut(beat?.newPlace == true)
                }
                if (cutSent && t >= HOLD_S) reveal()
                out
            }
            AutoPhase.REVEAL -> {
                cover = if (fade <= 0f) 0f else (1f - t / fade).coerceAtLeast(0f)
                if (cover <= 0f) {
                    phase = if (walking) AutoPhase.WALK else AutoPhase.PLAY
                    t = 0f
                    log(if (walking) "walk" else "play")
                }
                emptyList()
            }
            AutoPhase.WALK -> if (t >= WALK_TIMEOUT_S) arrived() else emptyList()
            AutoPhase.OFF -> emptyList()
        }
    }

    /**
     * The screen's answer to a [AutoStep.Swap]: [ok], it plays (both on their places); else another choice is tried under the
     * same cover, then the base animations, then the picture comes back as it is.
     */
    fun swapped(ok: Boolean): List<AutoStep> {
        if (phase != AutoPhase.SWAP) return emptyList()
        val p = pending
        if (ok && p != null) {
            beat = p
            pending = null
            elapsed = 0f
            tries = 0
            if (showMovement && p.newPlace) {
                // they walk there in the open: the cut comes as they arrive ([arrived])
                walking = true
                return reveal()
            }
            phase = AutoPhase.HOLD; t = 0f; cutSent = false
            return emptyList()
        }
        if (p != null) planner.refuse(p)
        tries++
        val again = when {
            tries < MAX_TRIES -> planner.next(options, beat, pace, people)
            tries == MAX_TRIES -> AutoBeat(null, null, null, planner.dwell(pace), newPlace = beat?.place != null)
            else -> null
        }
        if (again == null) {
            // nothing could be started: the picture comes back on what was there, the next change tried later
            pending = null
            tries = 0
            elapsed = 0f
            phase = AutoPhase.HOLD; t = 0f; cutSent = false
            return emptyList()
        }
        pending = again
        t = 0f
        log("swap")
        return listOf(AutoStep.Swap(again, teleport = !(showMovement && again.newPlace)))
    }

    /** With Show movement: they reached their places and the animation started: a cut to its first shot. */
    fun arrived(): List<AutoStep> {
        if (phase != AutoPhase.WALK) return emptyList()
        walking = false
        cutOnly = true
        phase = AutoPhase.COVER; t = 0f
        log("cover")
        return emptyList()
    }

    private fun begin(): List<AutoStep> {
        log("choose")
        val b = planner.next(options, beat, pace, people) ?: return emptyList()
        pending = b
        walking = false
        cutOnly = false
        phase = AutoPhase.COVER; t = 0f
        log("cover")
        return emptyList()
    }

    private fun reveal(): List<AutoStep> {
        phase = AutoPhase.REVEAL; t = 0f
        log("reveal")
        if (cutStyle.fadeS <= 0f) cover = 0f
        return emptyList()
    }
}

/**
 * The 3D view's frame in cinematic mode: the largest rectangle of [CinematicFrame.aspect] centred on the screen (black bars
 * round it: above and below on a screen taller than the frame, at the sides on a wider one), and inside it the thin film
 * bars the engine draws ([barScale]). A screen within [SNAP] of the frame's shape is used whole (no one-pixel bars).
 */
object FilmFrame {
    /** A screen this close to the frame's aspect (relative) is used whole. */
    const val SNAP = 0.02f

    /** A rectangle of the screen in pixels. */
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val aspect: Float get() = if (height > 0) width.toFloat() / height else 0f
        fun contains(px: Float, py: Float): Boolean = px >= x && px < x + width && py >= y && py < y + height
    }

    /** The frame for a [screenW] x [screenH] screen at [aspect] (0 or less: the whole screen). */
    fun fit(screenW: Int, screenH: Int, aspect: Float): Rect {
        if (screenW <= 0 || screenH <= 0 || !(aspect > 0f) || !aspect.isFinite()) return Rect(0, 0, maxOf(0, screenW), maxOf(0, screenH))
        val s = screenW.toFloat() / screenH
        if (kotlin.math.abs(s - aspect) <= SNAP * aspect) return Rect(0, 0, screenW, screenH)
        return if (s > aspect) {
            val w = (screenH * aspect).toInt().coerceIn(1, screenW)
            Rect((screenW - w) / 2, 0, w, screenH)
        } else {
            val h = (screenW / aspect).toInt().coerceIn(1, screenH)
            Rect(0, (screenH - h) / 2, screenW, h)
        }
    }

    /** A tap at ([px], [py]) on the screen in the frame's own pixels (the 3D view's), or null in the bars. */
    fun tap(frame: Rect, px: Float, py: Float): Pair<Float, Float>? =
        if (frame.contains(px, py)) (px - frame.x) to (py - frame.y) else null

    /**
     * The engine's letterbox amount that gives bars for a [picture] film frame inside a view of [viewAspect] (the engine draws
     * them for [CinematicLook.ASPECT] at amount 1: engine/grade.cpp letterboxBars): 0..1; 0 when the view is that wide already.
     */
    fun barScale(viewAspect: Float, picture: Float): Float {
        if (!(viewAspect > 0f) || !(picture > 0f)) return 0f
        val full = CinematicLook.bars(viewAspect, CinematicLook.ASPECT)
        if (full <= 0f) return 0f
        return (CinematicLook.bars(viewAspect, picture.coerceAtMost(CinematicLook.ASPECT)) / full).coerceIn(0f, 1f)
    }

    /** The share of the view's height each thin bar takes for [picture] in [viewAspect]. */
    fun barShare(viewAspect: Float, picture: Float): Float = CinematicLook.bars(viewAspect, picture)

    /** Whether [r] is centred on a [screenW] x [screenH] screen (to a pixel). */
    fun centred(screenW: Int, screenH: Int, r: Rect): Boolean =
        kotlin.math.abs((r.x * 2 + r.width) - screenW) <= 1 && kotlin.math.abs((r.y * 2 + r.height) - screenH) <= 1

    /** The point under a screen tap in normalised frame coordinates (0..1 each), or null in the bars. */
    fun normalised(frame: Rect, px: Float, py: Float): Pair<Float, Float>? =
        tap(frame, px, py)?.let { (x, y) -> x / frame.width to y / frame.height }
}
