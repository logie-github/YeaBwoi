package com.logie.wifecraft.ui

import com.logie.wifecraft.simulation.ClockSpeed
import com.logie.wifecraft.simulation.DayPhase
import com.logie.wifecraft.simulation.FriendTier
import com.logie.wifecraft.simulation.InteractionId
import com.logie.wifecraft.simulation.LifeLines
import com.logie.wifecraft.simulation.LifeSpeed
import com.logie.wifecraft.simulation.LifeThought
import com.logie.wifecraft.simulation.Mood
import com.logie.wifecraft.simulation.Need
import com.logie.wifecraft.simulation.Relationship
import com.logie.wifecraft.simulation.RelationshipRules
import com.logie.wifecraft.simulation.RelationshipView
import com.logie.wifecraft.simulation.RomanceTier
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.WorldClock
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Pure Kotlin, no android.* and no Compose (JVM-tested: unit-tests/.../ui/LifeHudTest): the life simulation's HUD
// (LifeOverlay.kt and LifePanels.kt draw it). The mood gem's colour, the need bars' colours and words, the reputation-style
// relationship bars, the party frames' relation glyph, which thought bubbles show (ThoughtBoard), the radial menu's angles and
// hit test (Radial), its items and why one is greyed, the world clock's face, and the clock's and life's speeds mapped.

/** The gem over an NPC's head (WoW's quest-marker diamond in a mood's colour): green happy, yellow content, orange a need, red upset, blue restless or inspired. */
enum class MoodGem(val argb: Long, val label: String) {
    GREEN(0xFF5BD65F, "Happy"),
    YELLOW(0xFFF3D04A, "Content"),
    ORANGE(0xFFF08A2E, "Needs something"),
    RED(0xFFE5483B, "Upset"),
    BLUE(0xFF55AEF2, "Restless"),
}

/** What the controlled NPC is to another, as the party frame marks it: nothing, a friend, a sweetheart, a rival. */
enum class RelationGlyph(val label: String) { NONE(""), FRIEND("Friend"), ROMANCE("Sweetheart"), RIVAL("Rival") }

/** A reputation-style bar: the standing's [label], how far through it ([fraction] 0..1), its colour, and the words a reader hears. */
data class RepBar(val label: String, val fraction: Float, val argb: Long, val value: Int, val description: String)

/** One need's bar: its [need], [value] 0..1, colour, "72%", and the long-press words. */
data class NeedBar(val need: Need, val value: Float, val argb: Long, val percent: String, val tooltip: String)

/** The clock widget: "8:05 AM", "Morning", "Day 2", sun or moon, the speed, and the words a reader hears. */
data class ClockFace(val time: String, val phase: String, val day: String, val night: Boolean, val speed: ClockSpeed, val description: String)

/** One item of the radial menu: the life interaction, its words and icon key, whether it can be had, why not, a warning. */
data class RadialItem(
    val id: InteractionId?,
    val label: String,
    val icon: String,
    val enabled: Boolean = true,
    /** Why it is greyed (shown when tapped), or null. */
    val reason: String? = null,
    /** A soft warning on an enabled social one ("May refuse: not close enough yet"), or null. */
    val hint: String? = null,
    /** A non-life item: the object's Play animation window. */
    val animations: Boolean = false,
)

object LifeHud {
    /** The UI's own refresh of life's numbers (4 a second: the bars move smoothly, the screen isn't redrawn per frame). */
    const val UI_MS = 250L
    /** The gem's bob: one rise and fall in this long, this high (dp); none under reduced motion. */
    const val BOB_MS = 2400
    const val BOB_DP = 3f
    /** The feed's last events shown. */
    const val FEED = 20
    /** A result toast shows this long. */
    const val TOAST_MS = 2600L

    fun gem(mood: Mood): MoodGem = when (mood) {
        Mood.HAPPY, Mood.FLIRTY, Mood.PLAYFUL -> MoodGem.GREEN
        Mood.CONTENT, Mood.FINE -> MoodGem.YELLOW
        Mood.TIRED, Mood.HUNGRY, Mood.THIRSTY, Mood.UNCOMFORTABLE, Mood.GRUBBY -> MoodGem.ORANGE
        Mood.IRRITABLE, Mood.LONELY, Mood.SAD -> MoodGem.RED
        Mood.BORED, Mood.INSPIRED -> MoodGem.BLUE
    }

    // the need bar's colour: red when empty through orange and yellow to green when full
    private val NEED_STOPS = listOf(0f to 0xFFD8402F, 0.3f to 0xFFEE8A2C, 0.55f to 0xFFF0CB45, 1f to 0xFF4FD45C)

    /** The bar colour for a need at [value] (0..1), ARGB. */
    fun needArgb(value: Float): Long {
        val v = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
        for (i in 1 until NEED_STOPS.size) {
            val (b, cb) = NEED_STOPS[i]
            if (v <= b) {
                val (a, ca) = NEED_STOPS[i - 1]
                return lerpArgb(ca, cb, (v - a) / (b - a))
            }
        }
        return NEED_STOPS.last().second
    }

    /** a to b by [t] (0..1), per channel. */
    fun lerpArgb(a: Long, b: Long, t: Float): Long {
        val k = t.coerceIn(0f, 1f)
        var out = 0L
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            val c = (x + (y - x) * k).roundToInt().toLong().coerceIn(0, 255)
            out = out or (c shl shift)
        }
        return out
    }

    /** "72%" (never "100%" before it is full, never "0%" while there is any). */
    fun percent(value: Float): String {
        val v = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
        val p = when {
            v >= 1f -> 100
            v <= 0f -> 0
            else -> (v * 100f).roundToInt().coerceIn(1, 99)
        }
        return "$p%"
    }

    /** How a need stands in a word. */
    fun standing(value: Float): String = when {
        value >= 0.8f -> "Full"
        value >= 0.5f -> "Fine"
        value >= 0.25f -> "Low"
        else -> "Urgent"
    }

    /** What fills [need], in a line. */
    fun fills(need: Need): String = when (need) {
        Need.ENERGY -> "A night in bed, or a nap on the couch."
        Need.HUNGER -> "A meal at the table, or something from the pack."
        Need.THIRST -> "A drink at the table."
        Need.SOCIAL -> "Talking, joking, a hug or a dance with someone."
        Need.FUN -> "Dancing, a joke, a good book."
        Need.HYGIENE -> "A wash at a basin, or freshening up."
        Need.COMFORT -> "A seat, the couch, or a rest on the bed."
    }

    fun needBar(need: Need, value: Float): NeedBar {
        val v = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
        val pc = percent(v)
        return NeedBar(need, v, needArgb(v), pc, "${need.wow} · $pc · ${standing(v)}. ${fills(need)}")
    }

    /** All seven bars of [needs] (in [Need] order; a missing one is empty). */
    fun needBars(needs: List<Float>): List<NeedBar> = Need.entries.map { needBar(it, needs.getOrElse(it.ordinal) { 0f }) }

    // reputation colours, as the game's reputation bars: hated to exalted
    private val FRIEND_ARGB = mapOf(
        FriendTier.HATED to 0xFFCC2222, FriendTier.HOSTILE to 0xFFE03A2A, FriendTier.UNFRIENDLY to 0xFFEE6622,
        FriendTier.NEUTRAL to 0xFFE8D040, FriendTier.FRIENDLY to 0xFF40C040, FriendTier.HONORED to 0xFF2FCB7A,
        FriendTier.REVERED to 0xFF22C4B0, FriendTier.EXALTED to 0xFF22C8E0,
    )
    const val ROMANCE_ARGB = 0xFFE36A9A
    const val SPURNED_ARGB = 0xFF8E3B5A

    /** How far [value] is through the standing that begins at [mins][i] (the last runs to 100). */
    private fun through(value: Float, mins: List<Float>): Pair<Int, Float> {
        val v = value.coerceIn(-100f, 100f)
        var i = 0
        for (k in mins.indices) if (v >= mins[k]) i = k
        val lo = mins[i]
        val hi = mins.getOrNull(i + 1) ?: 100f
        val f = if (hi <= lo) 1f else ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        return i to f
    }

    /** The friendship bar for [value] (-100..100): its standing, how far through it, the standing's colour. */
    fun friendBar(value: Int): RepBar {
        val (i, f) = through(value.toFloat(), FriendTier.entries.map { it.min })
        val tier = FriendTier.entries[i]
        return RepBar(tier.label, f, FRIEND_ARGB.getValue(tier), value, "Friendship ${tier.label}, ${(f * 100).roundToInt()}% to the next")
    }

    /** The romance bar for [value]: its step on the track, how far through it, rose (a darker rose when spurned). */
    fun romanceBar(value: Int): RepBar {
        val (i, f) = through(value.toFloat(), RomanceTier.entries.map { it.min })
        val tier = RomanceTier.entries[i]
        val argb = if (tier == RomanceTier.SPURNED) SPURNED_ARGB else ROMANCE_ARGB
        return RepBar(tier.label, f, argb, value, "Romance ${tier.label}, ${(f * 100).roundToInt()}% to the next")
    }

    /** The party frame's mark for what the controlled NPC is to [r]'s other: a heart from Flirting, a handshake from Friendly, a rival below Neutral. */
    fun relation(r: RelationshipView?): RelationGlyph = when {
        r == null -> RelationGlyph.NONE
        r.romance >= RomanceTier.FLIRTING.min -> RelationGlyph.ROMANCE
        r.friendship >= FriendTier.FRIENDLY.min -> RelationGlyph.FRIEND
        r.friendship < FriendTier.NEUTRAL.min -> RelationGlyph.RIVAL
        else -> RelationGlyph.NONE
    }

    /** Relationships closest first (the strongest feeling either way), then by name order as given. */
    fun sorted(relationships: List<RelationshipView>): List<RelationshipView> =
        relationships.withIndex().sortedWith(compareByDescending<IndexedValue<RelationshipView>> {
            max(kotlin.math.abs(it.value.friendship), max(it.value.romance, 0))
        }.thenBy { it.index }).map { it.value }

    // ---- the clock

    fun lifeSpeed(speed: ClockSpeed): LifeSpeed = when (speed) {
        ClockSpeed.PAUSED -> LifeSpeed.PAUSE
        ClockSpeed.X1 -> LifeSpeed.NORMAL
        ClockSpeed.X3 -> LifeSpeed.FAST
        ClockSpeed.X6 -> LifeSpeed.FASTEST
    }

    fun clockSpeed(speed: LifeSpeed): ClockSpeed = when (speed) {
        LifeSpeed.PAUSE -> ClockSpeed.PAUSED
        LifeSpeed.NORMAL -> ClockSpeed.X1
        LifeSpeed.FAST -> ClockSpeed.X3
        LifeSpeed.FASTEST -> ClockSpeed.X6
    }

    /** The speed buttons, in order. */
    val SPEEDS: List<ClockSpeed> = listOf(ClockSpeed.PAUSED, ClockSpeed.X1, ClockSpeed.X3, ClockSpeed.X6)

    /** The moon from 20:00 to 5:30 (when the place's night sounds play), the sun otherwise. */
    fun night(hour: Double): Boolean {
        val h = WorldClock.wrap(hour)
        return h >= 20.0 || h < 5.5
    }

    /** The clock widget for [hourOfDay] of [day] at [speed] ([h24]: the phone's 24-hour setting). */
    fun clockFace(hourOfDay: Double, day: Int, speed: ClockSpeed, h24: Boolean): ClockFace {
        val h = WorldClock.wrap(hourOfDay)
        val m = floor(h * 60.0).toInt().coerceIn(0, 1439)
        val time = SceneHud.clock(m / 60, m % 60, h24)
        val phase = DayPhase.at(h).label
        val d = "Day ${max(1, day)}"
        val sp = if (speed == ClockSpeed.PAUSED) "paused" else "speed ${speed.label}"
        return ClockFace(time, phase, d, night(h), speed, "$d, $time, $phase, $sp. Tap for the speed.")
    }

    /** A key that changes when the face would (the minute, the day, the speed): the GL thread posts the clock only then. */
    fun clockKey(simHours: Double, speed: ClockSpeed): Long =
        floor(max(0.0, simHours) * 60.0).toLong() * 8 + speed.ordinal

    /** "Day 2, 08:05" for an event at [hours] since day 1's midnight. */
    fun eventTime(hours: Double): String {
        val h = max(0.0, if (hours.isNaN()) 0.0 else hours)
        return "Day ${floor(h / 24.0).toInt() + 1}, ${WorldClock.format24(h)}"
    }

    // ---- the radial menu's items

    /** The solo interactions a piece of furniture offers (long press on it): what its provider gives. */
    fun forObject(item: SceneObject?): List<InteractionId> = when (item) {
        null -> InteractionId.SOLO_MENU
        SceneObject.BED -> listOf(InteractionId.REST)
        SceneObject.COUCH, SceneObject.CHAIR -> listOf(InteractionId.REST)
        SceneObject.TABLE -> listOf(InteractionId.EAT, InteractionId.DRINK, InteractionId.READ)
    }

    /**
     * The items of a radial menu: [ids] in order, greyed where [available] (LifeControl.menu's answer; null: not heard yet,
     * all shown as possible) says no, with why ([LifeLines.missing]); a social one carries a warning when the other is
     * likely to refuse by their standing ([with]: the actor's relationship to them). [animations]: the object's Play window
     * is offered last.
     */
    fun items(
        ids: List<InteractionId>,
        available: List<Pair<InteractionId, Boolean>>?,
        with: RelationshipView? = null,
        animations: Boolean = false,
    ): List<RadialItem> {
        val can = available?.toMap()
        val out = ids.map { id ->
            val ok = can?.get(id) ?: true
            val hint = if (id.social && with != null && !standingAllows(id, with)) "May refuse: not close enough yet." else null
            RadialItem(id, id.label, LifeIcons.interactionKey(id), ok, if (ok) null else LifeLines.missing(id), hint)
        }
        return if (animations) out + RadialItem(null, "Animations", "radial_animations", animations = true) else out
    }

    /** Whether [r]'s standing reaches what [id] usually needs ([RelationshipRules.needs]). */
    fun standingAllows(id: InteractionId, r: RelationshipView): Boolean =
        RelationshipRules.needs(id, Relationship(0, 1, r.friendship.toFloat(), r.romance.toFloat()))
}

/**
 * The radial (pie) menu's geometry: [n] items round a ring, the first at the top, going clockwise (screen coordinates: y
 * down). Angles in radians.
 */
object Radial {
    /** The item buttons' drawn size and their touch size, dp. */
    const val ITEM_DP = 52f
    const val TOUCH_DP = 56f
    /** The centre's cancel button, dp. */
    const val HUB_DP = 64f
    /** Room for an item's words under it, dp. */
    const val LABEL_DP = 18f

    /** The ring's radius for [n] items, dp: the items' touch boxes never overlap (at least [TOUCH_DP] + 16 apart round it). */
    fun radius(n: Int): Float {
        if (n <= 1) return 88f
        val chord = TOUCH_DP + 16f
        val r = chord / (2f * sin(PI / n).toFloat())
        return r.coerceIn(88f, 150f)
    }

    /** Item [i] of [n]'s angle (radians; -PI/2 is up). */
    fun angle(i: Int, n: Int): Double = -PI / 2 + 2 * PI * i / max(1, n)

    /** Item [i] of [n]'s centre from the ring's centre, at [radius]. */
    fun position(i: Int, n: Int, radius: Float): Pair<Float, Float> {
        val a = angle(i, n)
        return (radius * cos(a)).toFloat() to (radius * sin(a)).toFloat()
    }

    /**
     * Which item a touch at [dx], [dy] from the centre is on: the nearest item within its touch box, else by its slice when
     * it is between [inner] and [outer] from the centre; -1 for the hub or outside.
     */
    fun hit(dx: Float, dy: Float, n: Int, radius: Float, inner: Float = HUB_DP / 2, outer: Float = radius + TOUCH_DP): Int {
        if (n <= 0) return -1
        val d = hypot(dx, dy)
        if (d < inner || d > outer) return -1
        for (i in 0 until n) {
            val (x, y) = position(i, n, radius)
            if (hypot(dx - x, dy - y) <= TOUCH_DP / 2) return i
        }
        // the slice: the angle from the top, clockwise
        var a = atan2(dy.toDouble(), dx.toDouble()) + PI / 2
        val slice = 2 * PI / n
        a = ((a + slice / 2) % (2 * PI) + 2 * PI) % (2 * PI)
        return (a / slice).toInt().coerceIn(0, n - 1)
    }

    /** The ring's centre moved so all of it (radius, item, words) is on a [w] x [h] screen with [margin]; it stays if it fits. */
    fun clampCenter(cx: Float, cy: Float, w: Float, h: Float, radius: Float, margin: Float = 8f): Pair<Float, Float> {
        val half = radius + TOUCH_DP / 2 + margin
        val down = half + LABEL_DP
        val x = if (w <= 2 * half) w / 2 else cx.coerceIn(half, w - half)
        val y = if (h <= half + down) h / 2 else cy.coerceIn(half, h - down)
        return x to y
    }
}

/** One thought bubble on screen: whose ([index]), the thought, since when (ms). */
data class ShownThought(val index: Int, val thought: LifeThought, val since: Long)

/**
 * Which thought bubbles show, so the screen is never spammed: at most [maxVisible] at once and one per NPC; each shows at
 * least [minShowMs] (unless the NPC starts speaking: their speech bubble takes the place) and at most [maxShowMs]; after
 * one goes, that NPC waits [gapMs] before the next, and the same words are not shown twice running. The controlled NPC
 * ([preferred]) goes first, then the others round from the last one picked (nobody is always left out). Pure: [update]
 * takes the time in ms.
 */
class ThoughtBoard(
    val count: Int,
    val maxVisible: Int = 3,
    val minShowMs: Long = 3500L,
    val maxShowMs: Long = 7000L,
    val gapMs: Long = 6000L,
) {
    private val shown = arrayOfNulls<ShownThought>(count)
    private val hiddenAt = LongArray(count) { Long.MIN_VALUE / 2 }
    private val lastText = arrayOfNulls<String>(count)
    private var turn = 0

    /** What shows now, per index (null: no bubble). */
    fun current(): List<ShownThought?> = shown.toList()

    /**
     * The thoughts now ([thoughts] per index, null: none), who is speaking ([speaking]: no thought over a speech bubble), who
     * is on screen ([onScreen]: null, everyone), the controlled one ([preferred], -1 none): true when what shows changed.
     */
    fun update(nowMs: Long, thoughts: List<LifeThought?>, speaking: (Int) -> Boolean, onScreen: ((Int) -> Boolean)? = null, preferred: Int = -1): Boolean {
        var changed = false
        for (i in 0 until count) {
            val s = shown[i] ?: continue
            val now = thoughts.getOrNull(i)
            val age = nowMs - s.since
            val gone = speaking(i) || onScreen?.invoke(i) == false || age >= maxShowMs ||
                (age >= minShowMs && (now == null || now.text != s.thought.text))
            if (gone) { shown[i] = null; hiddenAt[i] = nowMs; changed = true }
        }
        var visible = shown.count { it != null }
        if (visible >= maxVisible || count == 0) return changed
        val order = ArrayList<Int>(count)
        if (preferred in 0 until count) order += preferred
        for (k in 0 until count) { val i = (turn + k) % count; if (i != preferred) order += i }
        for (i in order) {
            if (visible >= maxVisible) break
            if (shown[i] != null) continue
            val t = thoughts.getOrNull(i) ?: continue
            if (speaking(i) || onScreen?.invoke(i) == false) continue
            if (nowMs - hiddenAt[i] < gapMs) continue
            if (t.text == lastText[i]) continue
            shown[i] = ShownThought(i, t, nowMs)
            lastText[i] = t.text
            visible++
            changed = true
            if (i != preferred) turn = (i + 1) % count
        }
        return changed
    }
}

/**
 * The life feed's "new" dot: how many events arrived since the feed was last opened. Events are told apart by their time and
 * words (the feed is newest first).
 */
class FeedSeen {
    private var seenKey: String? = null

    private fun key(hours: Double, text: String) = "$hours|$text"

    /** Events in [newestFirst] newer than the last seen one (all of them when none was seen, up to [LifeHud.FEED]). */
    fun unseen(newestFirst: List<Pair<Double, String>>): Int {
        val k = seenKey ?: return min(newestFirst.size, LifeHud.FEED)
        val at = newestFirst.indexOfFirst { key(it.first, it.second) == k }
        return if (at < 0) min(newestFirst.size, LifeHud.FEED) else at
    }

    /** The feed was opened: everything in it is seen. */
    fun seeAll(newestFirst: List<Pair<Double, String>>) {
        seenKey = newestFirst.firstOrNull()?.let { key(it.first, it.second) } ?: seenKey
    }
}
