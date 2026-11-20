package com.logie.wifecraft.ui

import com.logie.wifecraft.simulation.ClockSpeed
import com.logie.wifecraft.simulation.InteractionId
import com.logie.wifecraft.simulation.LifeLines
import com.logie.wifecraft.simulation.LifeSpeed
import com.logie.wifecraft.simulation.LifeThought
import com.logie.wifecraft.simulation.Mood
import com.logie.wifecraft.simulation.Need
import com.logie.wifecraft.simulation.RelationshipView
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.Trait
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifeHudTest {
    // ---- the mood gem

    @Test
    fun moodGemsFollowTheMoodsColours() {
        assertEquals(MoodGem.GREEN, LifeHud.gem(Mood.HAPPY))
        assertEquals(MoodGem.YELLOW, LifeHud.gem(Mood.CONTENT))
        assertEquals(MoodGem.YELLOW, LifeHud.gem(Mood.FINE))
        for (m in listOf(Mood.TIRED, Mood.HUNGRY, Mood.THIRSTY, Mood.UNCOMFORTABLE, Mood.GRUBBY)) assertEquals(MoodGem.ORANGE, LifeHud.gem(m), m.name)
        for (m in listOf(Mood.IRRITABLE, Mood.LONELY, Mood.SAD)) assertEquals(MoodGem.RED, LifeHud.gem(m), m.name)
        assertEquals(MoodGem.BLUE, LifeHud.gem(Mood.BORED))
        assertEquals(MoodGem.BLUE, LifeHud.gem(Mood.INSPIRED))
        // every good mood is green, yellow or blue; every bad one orange, red or blue
        for (m in Mood.entries) {
            val g = LifeHud.gem(m)
            if (m.good) assertTrue(g in setOf(MoodGem.GREEN, MoodGem.YELLOW, MoodGem.BLUE), m.name)
            else assertTrue(g in setOf(MoodGem.ORANGE, MoodGem.RED, MoodGem.BLUE), m.name)
        }
    }

    // ---- the need bars

    private fun channel(argb: Long, shift: Int) = ((argb shr shift) and 0xFF).toInt()

    @Test
    fun needBarsGoFromRedThroughYellowToGreen() {
        val empty = LifeHud.needArgb(0f)
        val full = LifeHud.needArgb(1f)
        assertTrue(channel(empty, 16) > channel(empty, 8), "empty is red")
        assertTrue(channel(full, 8) > channel(full, 16), "full is green")
        assertEquals(0xFFF0CB45, LifeHud.needArgb(0.55f), "yellow in the middle")
        assertEquals(empty, LifeHud.needArgb(Float.NaN))
        assertEquals(empty, LifeHud.needArgb(-1f))
        assertEquals(full, LifeHud.needArgb(2f))
        // green rises (red falls past the yellow) as the bar fills: never a jump back
        var lastGreen = -1
        for (k in 0..100) {
            val g = channel(LifeHud.needArgb(k / 100f), 8)
            assertTrue(g >= lastGreen, "green at $k")
            lastGreen = g
            assertEquals(0xFF, channel(LifeHud.needArgb(k / 100f), 24), "opaque")
        }
    }

    @Test
    fun percentsNeverRoundToFullOrEmptyTooSoon() {
        assertEquals("0%", LifeHud.percent(0f))
        assertEquals("1%", LifeHud.percent(0.004f))
        assertEquals("99%", LifeHud.percent(0.996f))
        assertEquals("100%", LifeHud.percent(1f))
        assertEquals("72%", LifeHud.percent(0.72f))
        assertEquals("0%", LifeHud.percent(Float.NaN))
    }

    @Test
    fun sevenBarsInNeedOrderWithWordsForTheLongPress() {
        val bars = LifeHud.needBars(listOf(0.9f, 0.1f, 0.5f))
        assertEquals(Need.entries, bars.map { it.need })
        assertEquals(0.9f, bars[0].value)
        assertEquals(0f, bars[6].value, "missing is empty")
        assertTrue(bars[0].tooltip.startsWith("Rested · 90% · Full."), bars[0].tooltip)
        assertTrue("Urgent" in bars[1].tooltip)
        assertTrue("meal" in bars[1].tooltip)
        for (b in bars) assertTrue(b.tooltip.contains(LifeHud.fills(b.need)))
    }

    // ---- relationships

    @Test
    fun friendshipReadsAsTheReputationStandings() {
        val neutral = LifeHud.friendBar(0)
        assertEquals("Neutral", neutral.label)
        assertEquals(0.5f, neutral.fraction, 0.001f)
        assertEquals("Friendly", LifeHud.friendBar(10).label)
        assertEquals(0f, LifeHud.friendBar(10).fraction, 0.001f)
        assertEquals("Exalted", LifeHud.friendBar(100).label)
        assertEquals(1f, LifeHud.friendBar(100).fraction, 0.001f)
        assertEquals("Hated", LifeHud.friendBar(-100).label)
        assertEquals(0f, LifeHud.friendBar(-100).fraction, 0.001f)
        assertEquals("Hated", LifeHud.friendBar(-500).label, "out of range is clamped")
        assertNotEquals(LifeHud.friendBar(-100).argb, LifeHud.friendBar(90).argb)
        assertEquals("Sweethearts", LifeHud.romanceBar(45).label)
        assertEquals(LifeHud.ROMANCE_ARGB, LifeHud.romanceBar(45).argb)
        assertEquals(LifeHud.SPURNED_ARGB, LifeHud.romanceBar(-50).argb)
    }

    private fun rel(other: String, f: Int, r: Int) = RelationshipView(other, f, r, "", "")

    @Test
    fun partyFramesMarkSweetheartsFriendsAndRivals() {
        assertEquals(RelationGlyph.NONE, LifeHud.relation(null))
        assertEquals(RelationGlyph.ROMANCE, LifeHud.relation(rel("a", 0, 25)))
        assertEquals(RelationGlyph.FRIEND, LifeHud.relation(rel("a", 15, 0)))
        assertEquals(RelationGlyph.RIVAL, LifeHud.relation(rel("a", -20, 0)))
        assertEquals(RelationGlyph.NONE, LifeHud.relation(rel("a", 5, 10)))
        val sorted = LifeHud.sorted(listOf(rel("a", 5, 0), rel("b", -60, 0), rel("c", 20, 70), rel("d", 5, 0)))
        assertEquals(listOf("c", "b", "a", "d"), sorted.map { it.other })
    }

    // ---- the clock

    @Test
    fun clockAndLifeSpeedsMapBothWays() {
        for (c in ClockSpeed.entries) assertEquals(c, LifeHud.clockSpeed(LifeHud.lifeSpeed(c)))
        for (l in LifeSpeed.entries) assertEquals(l, LifeHud.lifeSpeed(LifeHud.clockSpeed(l)))
        for (c in ClockSpeed.entries) assertEquals(c.multiplier, LifeHud.lifeSpeed(c).multiplier)
        assertEquals(listOf(ClockSpeed.PAUSED, ClockSpeed.X1, ClockSpeed.X3, ClockSpeed.X6), LifeHud.SPEEDS)
    }

    @Test
    fun clockFaceSaysTheTimeThePartOfTheDayAndSunOrMoon() {
        val morning = LifeHud.clockFace(8 + 5 / 60.0 + 0.001, 2, ClockSpeed.X1, h24 = false)
        assertEquals("8:05 AM", morning.time)
        assertEquals("Morning", morning.phase)
        assertEquals("Day 2", morning.day)
        assertFalse(morning.night)
        assertTrue("Day 2, 8:05 AM, Morning, speed x1" in morning.description)
        assertEquals("08:05", LifeHud.clockFace(8 + 5 / 60.0 + 0.001, 1, ClockSpeed.X1, h24 = true).time)
        assertTrue(LifeHud.clockFace(21.0, 1, ClockSpeed.X3, false).night)
        assertTrue(LifeHud.clockFace(5.0, 1, ClockSpeed.X3, false).night)
        assertFalse(LifeHud.clockFace(5.5, 1, ClockSpeed.X3, false).night)
        assertFalse(LifeHud.clockFace(19.99, 1, ClockSpeed.X3, false).night)
        assertTrue("paused" in LifeHud.clockFace(12.0, 1, ClockSpeed.PAUSED, false).description)
        assertEquals("Day 1", LifeHud.clockFace(12.0, 0, ClockSpeed.X1, false).day, "never day 0")
        assertEquals("11:59 PM", LifeHud.clockFace(23.9999, 1, ClockSpeed.X1, false).time)
    }

    @Test
    fun clockKeyChangesOncePerMinuteOrSpeed() {
        val a = LifeHud.clockKey(8.0 + 1.0 / 120.0, ClockSpeed.X1)   // 8:00:30
        assertEquals(a, LifeHud.clockKey(8.0 + 1.0 / 70.0, ClockSpeed.X1))
        assertNotEquals(a, LifeHud.clockKey(8.0 + 1.0 / 60.0, ClockSpeed.X1))
        assertNotEquals(a, LifeHud.clockKey(8.0 + 1.0 / 120.0, ClockSpeed.X3))
        assertNotEquals(LifeHud.clockKey(8.0, ClockSpeed.X1), LifeHud.clockKey(32.0, ClockSpeed.X1), "the next day")
        assertEquals("Day 2, 02:30", LifeHud.eventTime(26.5))
        assertEquals("Day 1, 00:00", LifeHud.eventTime(Double.NaN))
    }

    // ---- the radial menu's items

    @Test
    fun radialItemsGreyWhatCantBeHadAndSayWhy() {
        val all = LifeHud.items(InteractionId.SOLO_MENU, null)
        assertEquals(InteractionId.SOLO_MENU, all.map { it.id })
        assertTrue(all.all { it.enabled && it.reason == null }, "nothing heard yet: all offered")
        val heard = LifeHud.items(InteractionId.SOLO_MENU, InteractionId.SOLO_MENU.map { it to (it != InteractionId.WASH) })
        val wash = heard.first { it.id == InteractionId.WASH }
        assertFalse(wash.enabled)
        assertEquals(LifeLines.missing(InteractionId.WASH), wash.reason)
        assertTrue(heard.filter { it.id != InteractionId.WASH }.all { it.enabled })
        assertEquals(listOf(InteractionId.EAT, InteractionId.DRINK, InteractionId.READ), LifeHud.forObject(SceneObject.TABLE))
        assertEquals(listOf(InteractionId.REST), LifeHud.forObject(SceneObject.BED))
        val atTable = LifeHud.items(LifeHud.forObject(SceneObject.TABLE), null, animations = true)
        assertTrue(atTable.last().animations)
        assertNull(atTable.last().id)
        // icons never blank: every item's key is in the icon table
        for (i in atTable + all) assertTrue(i.icon in LifeIcons.TABLE, i.icon)
    }

    @Test
    fun socialItemsWarnWhenTheStandingIsntThereYet() {
        val strangers = LifeHud.items(InteractionId.SOCIAL_MENU, null, rel("b", 0, 0))
        assertEquals(InteractionId.SOCIAL_MENU, strangers.map { it.id })
        assertTrue(strangers.all { it.enabled }, "the player may always ask")
        assertTrue(strangers.first { it.id == InteractionId.KISS }.hint != null)
        assertTrue(strangers.first { it.id == InteractionId.HUG }.hint != null)
        assertNull(strangers.first { it.id == InteractionId.CHAT }.hint)
        val lovers = LifeHud.items(InteractionId.SOCIAL_MENU, null, rel("b", 60, 70))
        assertTrue(lovers.all { it.hint == null })
    }

    // ---- the radial menu's geometry

    @Test
    fun radialStartsAtTheTopAndGoesClockwise() {
        assertEquals(-PI / 2, Radial.angle(0, 7), 1e-9)
        val (x0, y0) = Radial.position(0, 4, 100f)
        assertEquals(0f, x0, 0.01f); assertEquals(-100f, y0, 0.01f)
        val (x1, y1) = Radial.position(1, 4, 100f)
        assertEquals(100f, x1, 0.01f); assertEquals(0f, y1, 0.01f)   // right of the centre: clockwise on a screen (y down)
        val (x2, y2) = Radial.position(2, 4, 100f)
        assertEquals(0f, x2, 0.01f); assertEquals(100f, y2, 0.01f)
    }

    @Test
    fun radialItemsNeverOverlapTheirTouchBoxes() {
        for (n in 1..12) {
            val r = Radial.radius(n)
            assertTrue(r in 88f..150f, "radius for $n")
            if (n in 2..10) {
                val chord = 2 * r * sin(PI / n).toFloat()
                assertTrue(chord >= Radial.TOUCH_DP + 16f - 0.01f, "items of $n are ${chord}dp apart")
            }
        }
    }

    @Test
    fun radialHitTestFindsEachItemAndNotTheHub() {
        for (n in listOf(3, 7, 10)) {
            val r = Radial.radius(n)
            for (i in 0 until n) {
                val (x, y) = Radial.position(i, n, r)
                assertEquals(i, Radial.hit(x, y, n, r), "item $i of $n")
                assertEquals(i, Radial.hit(x * 1.1f, y * 1.1f, n, r), "just outside item $i of $n")
            }
            assertEquals(-1, Radial.hit(0f, 0f, n, r), "the hub")
            assertEquals(-1, Radial.hit(5f, 5f, n, r), "the hub")
            assertEquals(-1, Radial.hit(r + Radial.TOUCH_DP + 5f, 0f, n, r), "outside")
        }
        // between two items it goes by the slice: slightly after item 0 (clockwise) is still item 0
        val n = 4
        val r = Radial.radius(n)
        val a = Radial.angle(0, n) + 0.3
        assertEquals(0, Radial.hit((r * 0.6 * kotlin.math.cos(a)).toFloat(), (r * 0.6 * kotlin.math.sin(a)).toFloat(), n, r))
        assertEquals(-1, Radial.hit(1f, 1f, 0, r), "no items")
    }

    @Test
    fun radialCentreIsKeptOnScreen() {
        val r = Radial.radius(10)
        val (x, y) = Radial.clampCenter(5f, 5f, 400f, 800f, r)
        val half = r + Radial.TOUCH_DP / 2 + 8f
        assertEquals(half, x, 0.01f); assertEquals(half, y, 0.01f)
        val (bx, by) = Radial.clampCenter(395f, 795f, 400f, 800f, r)
        assertEquals(400f - half, bx, 0.01f); assertEquals(800f - half - Radial.LABEL_DP, by, 0.01f)
        assertEquals(200f to 300f, Radial.clampCenter(200f, 300f, 400f, 800f, r), "where it fits it stays")
        assertEquals(100f to 100f, Radial.clampCenter(0f, 0f, 200f, 200f, r), "too small: the middle")
        // the cover screen standing (about 360 dp wide): the ten social items' ring fits across it
        assertTrue(2 * (r + Radial.TOUCH_DP / 2) <= 360f, "ring ${2 * r} dp")
    }

    // ---- the thought bubbles

    private fun t(text: String) = LifeThought("need_energy", text)

    @Test
    fun atMostThreeThoughtsOnePerNpcAndNeverOverASpeechBubble() {
        val board = ThoughtBoard(5)
        val thoughts = List(5) { t("thought $it") }
        assertTrue(board.update(0L, thoughts, { it == 4 }))
        val shown = board.current()
        assertEquals(3, shown.count { it != null })
        assertNull(shown[4], "the speaker's speech bubble shows instead")
        // nothing new while three are up, before they've shown long enough
        assertFalse(board.update(1000L, thoughts, { false }))
        // one starts to speak: it goes at once
        assertTrue(board.update(1500L, thoughts, { it == 0 }))
        assertNull(board.current()[0])
        // off screen: not shown
        val board2 = ThoughtBoard(3)
        board2.update(0L, List(3) { t("x$it") }, { false }, { it != 1 })
        assertNull(board2.current()[1])
    }

    @Test
    fun thoughtsStayLongEnoughThenRestBeforeTheNextAndNeverRepeat() {
        val board = ThoughtBoard(1, minShowMs = 3000, maxShowMs = 6000, gapMs = 5000)
        board.update(0L, listOf(t("a")), { false })
        assertEquals("a", board.current()[0]?.thought?.text)
        // the thought changed but it hasn't shown its minimum yet: kept
        board.update(1000L, listOf(t("b")), { false })
        assertEquals("a", board.current()[0]?.thought?.text)
        // now it goes (changed after its minimum)
        board.update(3000L, listOf(t("b")), { false })
        assertNull(board.current()[0])
        // the gap after it: nothing new yet
        board.update(6000L, listOf(t("b")), { false })
        assertNull(board.current()[0])
        board.update(8000L, listOf(t("b")), { false })
        assertEquals("b", board.current()[0]?.thought?.text)
        // the most a thought shows, then the same words again are not shown
        board.update(14_000L, listOf(t("b")), { false })
        assertNull(board.current()[0])
        board.update(30_000L, listOf(t("b")), { false })
        assertNull(board.current()[0], "the same words twice running are not shown")
        board.update(31_000L, listOf(t("c")), { false })
        assertEquals("c", board.current()[0]?.thought?.text)
    }

    @Test
    fun theControlledNpcsThoughtGoesFirstAndOthersTakeTurns() {
        val board = ThoughtBoard(6, maxVisible = 2)
        board.update(0L, List(6) { t("t$it") }, { false }, preferred = 5)
        val first = board.current().mapIndexedNotNull { i, s -> if (s != null) i else null }
        assertTrue(5 in first, "the controlled one")
        assertEquals(2, first.size)
        // after those go and their gap passes, others get their turn before the first ones again
        board.update(8000L, List(6) { t("u$it") }, { false })
        board.update(20_000L, List(6) { t("v$it") }, { false })
        val second = board.current().mapIndexedNotNull { i, s -> if (s != null) i else null }
        assertTrue(second.isNotEmpty())
        assertTrue(second.any { it !in first }, "turns: $first then $second")
    }

    // ---- the feed's dot

    @Test
    fun feedCountsWhatsNewSinceItWasOpened() {
        val seen = FeedSeen()
        val events = listOf(2.0 to "b", 1.0 to "a")
        assertEquals(2, seen.unseen(events))
        seen.seeAll(events)
        assertEquals(0, seen.unseen(events))
        val more = listOf(4.0 to "d", 3.0 to "c") + events
        assertEquals(2, seen.unseen(more))
        seen.seeAll(emptyList())
        assertEquals(2, seen.unseen(more), "an empty feed seen changes nothing")
        assertEquals(LifeHud.FEED, FeedSeen().unseen(List(50) { it.toDouble() to "e$it" }))
    }

    // ---- the icons

    @Test
    fun everyLifeIconKeyHasTheGamesIconAndAGlyph() {
        for (n in Need.entries) assertTrue(n.icon in LifeIcons.TABLE, n.icon)
        for (m in Mood.entries) assertTrue(m.icon in LifeIcons.TABLE, m.icon)
        for (tr in Trait.entries) assertTrue(tr.icon in LifeIcons.TABLE, tr.icon)
        for (id in InteractionId.entries) assertTrue("thought_" + id.name.lowercase() in LifeIcons.TABLE, id.name)
        assertTrue("thought_missing" in LifeIcons.TABLE)
        for ((key, icon) in LifeIcons.TABLE) {
            assertTrue(icon.fileId > 0, key)
            assertTrue(icon.name.matches(Regex("[a-z0-9_]+")), key)
        }
        assertEquals(LifeGlyph.QUESTION, LifeIcons.of(null).glyph)
        assertEquals(LifeGlyph.QUESTION, LifeIcons.of("nothing_like_it").glyph)
        assertEquals(LifeIcons.of("mood_happy"), LifeIcons.of("mood_unheard_of"), "a new mood still gets a mood's icon")
    }

    @Test
    fun everyIconKeyTheLifeSimulationWritesIsInTheTable() {
        val dir = File(System.getProperty("wifecraft.ui"), "../simulation")
        val keys = HashSet<String>()
        for (f in dir.listFiles().orEmpty().filter { it.name.startsWith("Life") }) {
            Regex("\"((event|thought|need|mood|trait)_[a-z_]+)\"").findAll(f.readText()).forEach { keys += it.groupValues[1] }
        }
        assertTrue(keys.any { it.startsWith("event_") }, "found the feed's keys")
        for (k in keys) assertTrue(k in LifeIcons.TABLE || k == "thought_", "$k has no icon")
    }

    // ---- the HUD around it

    @Test
    fun actionsComeSecondWithTheLifeSimulation() {
        val on = SceneHud.orders(walking = false, partner = false, others = 1, repeating = false, queued = 0, life = true)
        assertEquals(listOf(HudOrder.WALK, HudOrder.LIFE, HudOrder.PLAY), on.take(3).map { it.order })
        assertEquals("Actions", on[1].label)
        val off = SceneHud.orders(walking = false, partner = false, others = 1, repeating = false, queued = 0)
        assertFalse(off.any { it.order == HudOrder.LIFE })
    }

    @Test
    fun theRadialMovesWithinTheMenusTimes() {
        assertTrue(MenuMotion.RADIAL_MS in 90..160)
        assertEquals(0, MenuMotion.duration(MenuMotion.RADIAL_MS, reduced = true))
        assertTrue(LifeHud.UI_MS >= 250L, "no more than 4 a second")
    }
}
