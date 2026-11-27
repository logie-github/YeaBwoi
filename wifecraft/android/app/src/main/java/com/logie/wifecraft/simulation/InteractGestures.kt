package com.logie.wifecraft.simulation

import kotlin.math.hypot

// Pure Kotlin (JVM-tested: InteractGesturesTest): Interact mode's multi-finger rules. The Compose layer (ui/InteractMulti.kt) tells it
// which fingers went down, what each landed on, where they moved and which lifted; it answers with each finger's role and what changed.
//
//   Touch tool:  1 finger on the body = touch / stroke (the old behaviour)
//                2 fingers, both on the same person's body = a pinch (the engine pairs two close fingers on soft skin itself; both are
//                  pushed as touches, each judged by Consent on its own)
//                2 fingers on two different people = two independent touches
//                2 fingers, at least one on the background = camera (pinch-zoom)
//                3 or more fingers = camera (pan: the centroid orbits)
//   Look tool:   every finger is the camera
//   Stocking:    the first finger on a stocking-covered part, alone, is the stocking grab (another piece of work); a finger that lands
//                while it holds is ignored for contact and for the camera
//   Latches:     once a gesture has become camera (or a pinch has ended with a finger still down) the fingers still down stay that
//                (camera, or ignored) until every finger is up: a lifted finger never turns the other into a stray touch
//   Cancel:      a cancelled pointer, the mode ending or the tool changing lets every contact go (the changes say so: no stuck contact)

/** What a finger is doing. */
enum class FingerRole { PENDING, TOUCH, PINCH, CAMERA, STOCKING, IGNORED }

/** What a finger landed on, as the pick answers (asynchronously: until then it is [UNKNOWN]). */
enum class FingerTarget { UNKNOWN, BODY, BACKGROUND, STOCKING }

/** What the camera does with its fingers. */
enum class CameraGesture { NONE, ORBIT, ZOOM, PAN }

class InteractGestures(private val maxContacts: Int = MAX_CONTACTS) {
    class Finger internal constructor(val id: Long, val order: Int) {
        var x = 0f; internal set
        var y = 0f; internal set
        var downX = 0f; internal set
        var downY = 0f; internal set
        var target = FingerTarget.UNKNOWN; internal set
        /** The person it landed on (null: the background or not known). */
        var actor: String? = null; internal set
        var role = FingerRole.PENDING; internal set
        /** The engine's pointer id it presses with (0..4) while it touches or pinches, else -1. */
        var slot = -1; internal set
    }

    /** One finger's change of role: [slot] is the engine pointer (-1 when it has none, before or after). */
    data class Change(val id: Long, val from: FingerRole, val to: FingerRole, val slot: Int, val actor: String?, val partner: Long? = null)

    private val fingers = ArrayList<Finger>()
    private var order = 0
    private var look = false
    /** Latched until every finger is up: camera (a bigger gesture began) or ignore (a pinch or stocking grab ended with fingers still down). */
    private var latch = Latch.NONE
    private var stockingHeld = false
    private enum class Latch { NONE, CAMERA, IGNORE }

    val count: Int get() = fingers.size
    fun finger(id: Long): Finger? = fingers.firstOrNull { it.id == id }
    fun roleOf(id: Long): FingerRole? = finger(id)?.role
    fun fingers(): List<Finger> = fingers.toList()
    fun withRole(r: FingerRole): List<Finger> = fingers.filter { it.role == r }

    /** True while the Look tool is on (every finger is the camera). */
    val lookTool: Boolean get() = look

    /** The camera's gesture now, from how many fingers are the camera. */
    fun cameraGesture(): CameraGesture = when (withRole(FingerRole.CAMERA).size) { 0 -> CameraGesture.NONE; 1 -> CameraGesture.ORBIT; 2 -> CameraGesture.ZOOM; else -> CameraGesture.PAN }

    /** A finger went down at ([x], [y]); its target arrives with [classified]. */
    fun down(id: Long, x: Float, y: Float): List<Change> {
        if (finger(id) != null) return emptyList()
        if (fingers.isEmpty()) { latch = Latch.NONE; stockingHeld = false }
        val f = Finger(id, order++)
        f.x = x; f.y = y; f.downX = x; f.downY = y
        fingers += f
        return assign()
    }

    /** What finger [id] landed on ([actor]: the person, for [FingerTarget.BODY]). */
    fun classified(id: Long, target: FingerTarget, actor: String? = null): List<Change> {
        val f = finger(id) ?: return emptyList()
        if (f.target != FingerTarget.UNKNOWN) return emptyList()
        f.target = target; f.actor = if (target == FingerTarget.BODY) actor else null
        return assign()
    }

    fun move(id: Long, x: Float, y: Float) { finger(id)?.let { it.x = x; it.y = y } }

    /** A finger lifted. */
    fun up(id: Long): List<Change> = remove(id)

    /** A pointer was cancelled (the system took the gesture, the window lost focus): the same as lifting it, never a tap. */
    fun cancel(id: Long): List<Change> = remove(id)

    /** Every finger gone at once (the mode ended, the layer left the screen). Every contact lets go. */
    fun cancelAll(): List<Change> {
        val out = ArrayList<Change>()
        for (f in fingers) if (f.role != FingerRole.PENDING && f.role != FingerRole.IGNORED) out += Change(f.id, f.role, FingerRole.IGNORED, f.slot, f.actor)
        fingers.clear(); latch = Latch.NONE; stockingHeld = false
        return out
    }

    /** The tool changed (Touch <-> Look): contacts let go, the camera takes every finger in Look. */
    fun setLook(on: Boolean): List<Change> { if (look == on) return emptyList(); look = on; return assign() }

    private fun remove(id: Long): List<Change> {
        val f = finger(id) ?: return emptyList()
        val out = ArrayList<Change>()
        val was = f.role
        fingers.remove(f)
        if (was != FingerRole.PENDING && was != FingerRole.IGNORED) out += Change(f.id, was, FingerRole.IGNORED, f.slot, f.actor)
        f.role = FingerRole.IGNORED; f.slot = -1
        if (fingers.isEmpty()) { latch = Latch.NONE; stockingHeld = false; return out }
        // a pinch or a stocking grab that loses a finger leaves the others ignored until all are up; a camera gesture stays camera
        if ((was == FingerRole.PINCH || was == FingerRole.STOCKING) && latch == Latch.NONE) latch = Latch.IGNORE
        return out + assign()
    }

    private fun freeSlot(): Int {
        for (s in 0 until maxContacts) if (fingers.none { it.slot == s }) return s
        return -1
    }

    private fun set(f: Finger, role: FingerRole, out: MutableList<Change>, partner: Long? = null) {
        if (f.role == role) return
        val from = f.role
        val oldSlot = f.slot
        f.role = role
        if (role == FingerRole.TOUCH || role == FingerRole.PINCH) {
            if (f.slot < 0) f.slot = freeSlot()
            if (f.slot < 0) { f.role = FingerRole.IGNORED; out += Change(f.id, from, FingerRole.IGNORED, oldSlot, f.actor); return }
        } else f.slot = -1
        out += Change(f.id, from, role, if (role == FingerRole.TOUCH || role == FingerRole.PINCH) f.slot else oldSlot, f.actor, partner)
    }

    /** Works out every finger's role from the fingers down, their targets, the tool and the latches; returns what changed. */
    private fun assign(): List<Change> {
        val out = ArrayList<Change>()
        val n = fingers.size
        if (n == 0) return out
        // the tool and the latches first
        if (look || latch == Latch.CAMERA) { for (f in fingers) set(f, FingerRole.CAMERA, out); return out }
        if (latch == Latch.IGNORE) { for (f in fingers) set(f, FingerRole.IGNORED, out); return out }
        // the stocking grab: the first finger, alone, on a stocking-covered part
        val first = fingers.first()
        if (stockingHeld || (n == 1 && first.target == FingerTarget.STOCKING)) {
            stockingHeld = true
            set(first, FingerRole.STOCKING, out)
            for (f in fingers) if (f !== first) set(f, FingerRole.IGNORED, out)
            return out
        }
        if (n >= 3 || n > maxContacts) { latch = Latch.CAMERA; for (f in fingers) set(f, FingerRole.CAMERA, out); return out }
        val known = fingers.filter { it.target != FingerTarget.UNKNOWN }
        if (known.any { it.target == FingerTarget.BACKGROUND } && n >= 2) { latch = Latch.CAMERA; for (f in fingers) set(f, FingerRole.CAMERA, out); return out }
        if (n == 1) {
            val f = fingers[0]
            when (f.target) {
                FingerTarget.UNKNOWN -> set(f, FingerRole.PENDING, out)
                FingerTarget.BODY -> set(f, FingerRole.TOUCH, out)
                FingerTarget.BACKGROUND -> set(f, FingerRole.CAMERA, out)
                FingerTarget.STOCKING -> set(f, FingerRole.STOCKING, out)
            }
            return out
        }
        // two fingers, none known to be on the background
        val a = fingers[0]; val b = fingers[1]
        // a stocking-covered part under a second finger counts as the body there (the grab only takes the first, alone)
        fun onBody(f: Finger) = f.target == FingerTarget.BODY || f.target == FingerTarget.STOCKING
        if (!onBody(a) || !onBody(b)) {
            // one is still being picked: the finger that is known keeps what it had (a touch goes on), the other waits
            for (f in listOf(a, b)) if (f.target == FingerTarget.UNKNOWN) set(f, FingerRole.PENDING, out)
            return out
        }
        val sameActor = a.actor != null && a.actor == b.actor
        if (sameActor || a.actor == null || b.actor == null) {
            set(a, FingerRole.PINCH, out, b.id)
            set(b, FingerRole.PINCH, out, a.id)
        } else {
            set(a, FingerRole.TOUCH, out)
            set(b, FingerRole.TOUCH, out)
        }
        return out
    }

    /** The distance between the first two fingers on the screen (0: fewer than two). */
    fun span(): Float { if (fingers.size < 2) return 0f; return hypot(fingers[0].x - fingers[1].x, fingers[0].y - fingers[1].y) }

    /** The centroid of the camera's fingers (null: none). */
    fun cameraCentroid(): Pair<Float, Float>? {
        val c = withRole(FingerRole.CAMERA)
        if (c.isEmpty()) return null
        return c.sumOf { it.x.toDouble() }.toFloat() / c.size to c.sumOf { it.y.toDouble() }.toFloat() / c.size
    }

    companion object {
        /** The engine takes this many fingers on one person at once (pointer ids 0..4). */
        const val MAX_CONTACTS = 5
    }
}
