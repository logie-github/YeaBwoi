package com.logie.wifecraft.simulation

import kotlin.math.abs
import kotlin.math.hypot

// Pure Kotlin (JVM-tested: ToyInteractionTest): Interact mode's toys. The tray lists the imported pack's props that are toys
// (classified by the package's name and the prop's size), a tap picks one up, the finger carries it in front of her, near the entry it
// snaps to the entry's axis (a haptic tick) and the finger's travel along that axis in screen space pushes it in or pulls it out (to
// 85 % of its insertable length); lifting the finger leaves it where it is (the body holds it); Remove pulls it out, Drop puts it away.
// The engine (engine/orificedraw.cpp) does the placing and the opening; this decides what the finger means. Nothing here touches Android.

/** What a prop is, by its package's name and its size. [insertable]: it goes into her; [vibrates]: it has an on/off and a strength. */
enum class ToyKind(val label: String, val insertable: Boolean, val doubleEnded: Boolean = false, val vibrates: Boolean = false, val usable: Boolean = true) {
    DILDO("Dildo", true),
    DOUBLE("Double-ended", true, doubleEnded = true),
    VIBRATOR("Vibrator", false, vibrates = true),
    MASTURBATOR("Masturbator", false, vibrates = true),
    DOLL("Doll", false, usable = false),
    CUFFS("Handcuffs", false, usable = false),
    OTHER("Object", false, usable = false);

    companion object {
        /** [source] is the package's file name, [size] the prop's size in metres (any order of axes). */
        fun classify(source: String, size: List<Float>): ToyKind {
            val s = source.lowercase()
            return when {
                "handcuff" in s || "cuff" in s -> CUFFS
                "doll" in s -> DOLL
                "masturbator" in s || "stroker" in s || "fleshlight" in s -> MASTURBATOR
                "vibrator" in s || "vibe" in s || "wand" in s -> VIBRATOR
                "double" in s -> DOUBLE
                "dildo" in s || "dong" in s -> DILDO
                else -> {
                    val d = size.sortedDescending()
                    if (d.size == 3 && d[2] > 1e-4f && d[0] / d[1] >= 3f) DILDO else OTHER
                }
            }
        }
    }
}

/** One toy in the tray: the prop's id, its kind and a name for the tile. */
data class ToyItem(val guid: Long, val kind: ToyKind, val label: String)

object ToyTray {
    /** The tray's items for the props the store knows ([infos]: guid, source name, size): one per prop, named by the package and a number for its variants. */
    fun items(infos: List<Triple<Long, String, List<Float>>>): List<ToyItem> {
        val out = ArrayList<ToyItem>()
        val seen = HashMap<String, Int>()
        for ((guid, source, size) in infos) {
            val kind = ToyKind.classify(source, size)
            if (!kind.usable && kind == ToyKind.OTHER) continue
            val base = source.removeSuffix(".package").removePrefix("0nizu_").replace('_', ' ').replace(" - ", " ").trim()
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            out += ToyItem(guid, kind, if (n == 1) base else "$base · $n")
        }
        return out.sortedWith(compareBy({ !it.kind.insertable }, { !it.kind.usable }, { it.label }))
    }
}

/** What the engine says of the entry on screen (Native toyInfo): pixels, the way in on screen (unit) and the pixels one metre of it covers. */
data class ToyGeometry(val entryX: Float, val entryY: Float, val axisX: Float, val axisY: Float, val pxPerMetre: Float, val insertLength: Float, val hd: Boolean = true) {
    val ok: Boolean get() = pxPerMetre > 1f && insertLength > 0f
}

/** What goes to the engine each frame (Native setToy). [mode] 0 none, 1 held free at ([fx], [fy]), 2 snapped to the entry at [depth] metres. */
data class ToyCommand(val mode: Int, val fx: Float, val fy: Float, val depth: Float, val vibration: Float, val roll: Float = 0f)

enum class ToyEvent { NONE, PICKED, SNAPPED, TICK, LET_GO, REMOVED, DROPPED }

/** A haptic tick as an on/off pattern in ms (first on) with its amplitudes (1..255): the same shape as [HapticPattern]. */
object ToyHaptics {
    fun snap() = HapticPattern(longArrayOf(18, 30, 24), intArrayOf(150, 0, 220))
    fun tick() = HapticPattern(longArrayOf(10), intArrayOf(70))
    fun pick() = HapticPattern(longArrayOf(14), intArrayOf(110))
    /** The vibrator's buzz for [intensity] 0..1, one pulse [PULSE_MS] long, the amplitude in proportion; null: off. */
    fun buzz(intensity: Float): HapticPattern? {
        val i = intensity.coerceIn(0f, 1f)
        return if (i <= 0.01f) null else HapticPattern(longArrayOf(PULSE_MS), intArrayOf((20 + 235 * i).toInt().coerceIn(1, 255)))
    }
    const val PULSE_MS = 90L
}

class ToyController {
    enum class Phase { NONE, HELD, ENGAGED }

    var item: ToyItem? = null; private set
    var phase = Phase.NONE; private set
    /** Metres of the toy past the entry plane (negative: still in front of it). */
    var depth = 0f; private set
    /** Metres a second, + inward, as the finger last made it. */
    var speed = 0f; private set
    var vibrating = false; private set
    var intensity = 0.5f; private set
    var fx = 0f
    var fy = 0f
    private var removing = false
    private var sinceTick = 0f

    val held: Boolean get() = phase != Phase.NONE
    val inserted: Boolean get() = phase == Phase.ENGAGED && depth > 0.002f
    /** The vibration the engine is given: the strength while it buzzes, else 0. */
    val vibration: Float get() = if (vibrating) intensity else 0f

    /** Picks [it] up with the finger at ([x], [y]). Any toy held before is put away. */
    fun pick(it: ToyItem, x: Float, y: Float): ToyEvent {
        item = it; phase = Phase.HELD; depth = -CLEARANCE; speed = 0f; vibrating = false; removing = false; fx = x; fy = y
        return ToyEvent.PICKED
    }

    /** The finger moves to ([x], [y]) while it holds the toy free: near the entry it snaps. */
    fun move(x: Float, y: Float, geo: ToyGeometry?): ToyEvent {
        fx = x; fy = y
        val it = item ?: return ToyEvent.NONE
        if (phase != Phase.HELD || geo == null || !geo.ok || !it.kind.insertable) return ToyEvent.NONE
        val d = hypot(x - geo.entryX, y - geo.entryY) / geo.pxPerMetre
        if (d <= SNAP_METRES) { phase = Phase.ENGAGED; depth = -CLEARANCE; speed = 0f; removing = false; return ToyEvent.SNAPPED }
        return ToyEvent.NONE
    }

    /** The finger moves by ([dx], [dy]) pixels in [dt] seconds while the toy is snapped: its travel along the entry's axis pushes or pulls. */
    fun drag(dx: Float, dy: Float, dt: Float, geo: ToyGeometry?): ToyEvent {
        if (phase != Phase.ENGAGED || geo == null || !geo.ok) return ToyEvent.NONE
        removing = false
        val along = (dx * geo.axisX + dy * geo.axisY) / geo.pxPerMetre
        val before = depth
        depth = (depth + along).coerceIn(-CLEARANCE, maxDepth(geo))
        speed = if (dt > 1e-4f) (depth - before) / dt else 0f
        val moved = abs(depth - before)
        sinceTick += moved
        if (sinceTick >= TICK_METRES) { sinceTick = 0f; return ToyEvent.TICK }
        return ToyEvent.NONE
    }

    /** The finger lifts: the toy stays where it is (the body holds it); a toy still outside the entry goes back to being held free. */
    fun release(): ToyEvent {
        speed = 0f
        if (phase == Phase.ENGAGED && depth <= 0.002f) { phase = Phase.HELD }
        return ToyEvent.LET_GO
    }

    /** Remove: the toy is drawn out at [REMOVE_SPEED] (step), then held free again in front of her. */
    fun remove(): Boolean {
        if (phase != Phase.ENGAGED) return false
        removing = true
        return true
    }

    /** Puts the toy away. */
    fun drop(): ToyEvent { item = null; phase = Phase.NONE; depth = 0f; speed = 0f; vibrating = false; removing = false; return ToyEvent.DROPPED }

    fun setVibration(on: Boolean, level: Float = intensity) { vibrating = on; intensity = level.coerceIn(0f, 1f) }

    /** Advances [dt] seconds (a Remove in progress). Returns [ToyEvent.REMOVED] when the toy has come out. */
    fun step(dt: Float): ToyEvent {
        if (removing && phase == Phase.ENGAGED) {
            val before = depth
            depth = (depth - REMOVE_SPEED * dt).coerceAtLeast(-CLEARANCE)
            speed = if (dt > 1e-4f) (depth - before) / dt else 0f
            if (depth <= -CLEARANCE + 1e-4f) { removing = false; phase = Phase.HELD; speed = 0f; return ToyEvent.REMOVED }
        } else if (!removing) {
            speed *= 0.8f   // (a finger that stopped: the speed dies away)
        }
        return ToyEvent.NONE
    }

    /** The command for the engine for a [width] x [height] view (null: no toy held). */
    fun command(width: Int, height: Int): ToyCommand? = when (phase) {
        Phase.NONE -> null
        Phase.HELD -> ToyCommand(1, fx, fy, -CLEARANCE, vibration)
        Phase.ENGAGED -> ToyCommand(2, fx, fy, depth, vibration)
    }

    private fun maxDepth(geo: ToyGeometry) = geo.insertLength * MAX_FRACTION

    companion object {
        const val SNAP_METRES = 0.04f      // the tip within 4 cm of the entry snaps
        const val CLEARANCE = 0.03f        // snapped, the tip waits this far in front of the entry plane
        const val MAX_FRACTION = 0.85f     // the base and its flare stay outside
        const val REMOVE_SPEED = 0.25f     // metres a second
        const val TICK_METRES = 0.01f      // a haptic tick every centimetre of travel
    }
}
