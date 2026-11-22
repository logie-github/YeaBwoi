package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The NPCs' small talk: the lines (Chatter), a conversation's clock, the simulation's conversations, the bubbles' layout. */
class ChatterTest {
    private val speakers = mapOf(
        "a" to Speaker("Nyrelle Moonquill", 4, true),
        "b" to Speaker("Bram Ironstock", 3, false),
        "c" to Speaker("Someone"),
    )

    private fun chatter() = Chatter(speakers, "Stormwind Embassy")

    @Test fun theSameSeedSaysTheSameThings() {
        val one = chatter(); val two = chatter()
        val r1 = Random(42); val r2 = Random(42)
        repeat(10) { assertEquals(one.conversation("a", "b", r1), two.conversation("a", "b", r2)) }
    }

    @Test fun aConversationGreetsTalksAndSaysGoodbye() {
        val c = chatter()
        val random = Random(3)
        repeat(40) {
            val lines = c.conversation("a", "b", random)
            assertEquals(0, lines[0].speaker)
            assertEquals(1, lines[1].speaker)
            assertTrue(lines.size in 8..22, "${lines.size} lines")
            assertTrue(lines[lines.size - 1].speaker != lines[lines.size - 2].speaker)
            for (l in lines) {
                assertFalse('{' in l.text || "null" in l.text, l.text)
                assertTrue(l.text.isNotBlank())
                if (l.nod) assertEquals(Gestures.TALK, l.gesture)
            }
        }
    }

    @Test fun nothingIsSaidAgainInTheNextConversation() {
        val c = chatter()
        val random = Random(11)
        var last = emptySet<String>()
        repeat(30) {
            // short prompts ("And?", "Why?") may come again; every real line is new
            val lines = c.conversation("a", "b", random).map { it.text }.filter { it.length > 10 }
            assertEquals(lines.size, lines.toSet().size, "a line repeated within one conversation: $lines")
            assertTrue(lines.none { it in last }, "repeated from the last conversation: ${lines.filter { it in last }}")
            last = lines.toSet()
        }
    }

    @Test fun itIsVariedAndUsesTheirNamesAndPlace() {
        val c = chatter()
        val random = Random(5)
        val all = (0 until 25).flatMap { c.conversation(if (it % 2 == 0) "a" else "b", if (it % 2 == 0) "b" else "a", random) }.map { it.text }
        assertTrue(all.toSet().size > 120, "only ${all.toSet().size} different lines")
        assertTrue(all.any { "Nyrelle" in it } && all.any { "Bram" in it })
        assertTrue(all.none { "Moonquill" in it }, "the first name only")
        val gestures = (0 until 25).flatMap { c.conversation("a", "b", random) }.map { it.gesture }.toSet()
        assertTrue(setOf(Gestures.TALK, Gestures.QUESTION, Gestures.LAUGH, Gestures.WAVE).all { it in gestures })
    }

    @Test fun slotsThatCantBeFilledAreNotUsed() {
        val c = chatter()
        val nobody = Speaker("Someone")
        assertNull(c.fill("Do you miss {yourhome}?", speakers.getValue("a"), nobody))
        assertNull(c.fill("Hail, {kin}.", speakers.getValue("a"), nobody))
        assertEquals("Do you miss Ironforge?", c.fill("Do you miss {yourhome}?", speakers.getValue("a"), speakers.getValue("b")))
        assertEquals("Hail, sister.", c.fill("Hail, {kin}.", speakers.getValue("b"), speakers.getValue("a")))
        val random = Random(9)
        repeat(20) { for (l in c.conversation("c", "c", random)) assertFalse('{' in l.text) }
        assertEquals("the Stormwind Embassy", Chatter.placeRef("Stormwind Embassy"))
        assertEquals("Dalaran's streets", Chatter.placeRef("Dalaran's streets"))
        assertEquals("the Violet Citadel's terrace", Chatter.placeRef("The Violet Citadel's terrace"))
        assertTrue(c.sayings("a").contains("Busy day at the Stormwind Embassy."))
    }

    @Test fun aConversationsClockRunsItsLinesWithGesturesAndNods() {
        val lines = listOf(
            ChatLine(0, "Hello there, friend.", Gestures.WAVE),
            ChatLine(1, "A fine day for it, wouldn't you say so?", Gestures.QUESTION),
            ChatLine(0, "It is.", Gestures.TALK, nod = true),
        )
        val c = Conversation(lines)
        assertEquals(-1, c.gesture(0))
        assertEquals(0, c.start())
        assertEquals(Gestures.WAVE, c.gesture(0))
        assertEquals(Gestures.STAND, c.gesture(1))
        val begun = ArrayList<Int>()
        var t = 0f
        val dt = 1f / 60f
        var nodded = false
        var talkedAfterWave = false
        while (!c.done) {
            val i = c.advance(dt)
            if (i >= 0) begun += i
            if (c.line?.speaker == 0 && c.index == 0 && c.gesture(0) == Gestures.TALK) talkedAfterWave = true
            if (c.index == 2 && c.line?.nod == true && c.gesture(1) == Gestures.STAND) nodded = true   // (the nod is a Yes the pose check turns away: PoseSanity)
            t += dt
            check(t < 60f)
        }
        assertEquals(listOf(1, 2), begun)
        assertTrue(talkedAfterWave)
        assertTrue(nodded)
        val expected = lines.sumOf { Reading.seconds(it.text).toDouble() } + 3 * Conversation.PAUSE
        assertEquals(expected, t.toDouble(), 0.1)
        assertEquals(-1, c.gesture(0))
        assertNull(c.line)
    }

    @Test fun readingTimeGrowsWithTheLine() {
        assertEquals(Reading.MIN_READ, Reading.seconds("Hi."))
        assertTrue(Reading.seconds("x".repeat(60)) > Reading.seconds("x".repeat(30)))
        assertEquals(Reading.MAX_READ, Reading.seconds("x".repeat(400)))
        assertEquals(Gestures.QUESTION, Gestures.of("Anyone for a drink?"))
        assertEquals(Gestures.LAUGH, Gestures.of("Ha! That's a good one."))
        assertEquals(Gestures.EXCLAIM, Gestures.of("Hello there!"))
        assertEquals(Gestures.TALK, Gestures.of("I could use a rest."))
    }

    // ---- the simulation

    private val step = 1f / 60f

    private fun sim(ids: List<String>, chance: Float = 1f, partner: Float = 0f, seed: Int = 7) =
        SceneSimulation(ids, emptySet(), Random(seed), wanderOnly = true, partnerChance = partner,
            chatterChance = chance, chatter = chatter())

    @Test fun idleNpcsGoAndTalkInTurnsThenParting() {
        val sim = sim(listOf("a", "b"))
        var n = 0
        while (!sim.talking("a")) { sim.tick(step); check(++n < 20_000) }
        assertEquals("b", sim.talkingWith("a"))
        assertEquals("a", sim.talkingWith("b"))
        val said = ArrayList<Pair<String, String>>()
        var speaker: String? = null
        while (sim.talking("a")) {
            sim.tick(step)
            for (id in listOf("a", "b")) sim.speech(id)?.let { text ->
                if (said.lastOrNull()?.second != text) { said += id to text; speaker = id }
            }
            assertFalse(sim.speech("a") != null && sim.speech("b") != null, "both talk at once")
            check(++n < 200_000)
        }
        assertNotNull(speaker)
        assertTrue(said.size >= 8, "$said")
        // they take turns (an exchange's three parts alternate too), and the bubbles are down at the end
        for (i in 1 until said.size) assertTrue(said[i].first != said[i - 1].first, "$said")
        sim.tick(step)
        assertNull(sim.speech("a")); assertNull(sim.speech("b"))
        assertFalse(sim.talking("b"))
        // they don't start again at once
        repeat((SceneSimulation.MIN_CHAT_COOLDOWN / step).toInt() - 60) { sim.tick(step); assertFalse(sim.talking("a")) }
    }

    @Test fun theyFaceEachOtherAndGestureWhileTalking() {
        val sim = sim(listOf("a", "b"))
        var n = 0
        while (sim.speech("a") == null && sim.speech("b") == null) { sim.tick(step); check(++n < 20_000) }
        val pa = Pose().also { sim.pose(0, it) }
        val pb = Pose().also { sim.pose(1, it) }
        assertTrue(pa.gesture >= 0 && pb.gesture >= 0)
        val toB = kotlin.math.atan2(pb.y - pa.y, pb.x - pa.x)
        assertEquals(0f, kotlin.math.abs(kotlin.math.sin((pa.facing - toB) / 2)), 0.05f)
        assertEquals(0f, kotlin.math.abs(kotlin.math.cos((pb.facing - toB) / 2)), 0.05f)
    }

    @Test fun anNpcIsNeverInAConversationAndAPartnerAnimationAtOnce() {
        val ids = listOf("a", "b", "c", "d")
        val sim = sim(ids, chance = 0.6f, partner = 0.6f, seed = 3)
        var talks = 0; var pairs = 0
        repeat(60 * 400) {
            sim.tick(step)
            for (id in ids) {
                val talking = sim.talking(id)
                val pairing = sim.partnerOf(id) != null
                assertFalse(talking && pairing, "$id in both")
                if (talking) talks++
                if (pairing) pairs++
            }
        }
        assertTrue(talks > 0 && pairs > 0)
    }

    @Test fun anOrderCutsAConversationShortAndItsBubblesComeDown() {
        val sim = sim(listOf("a", "b"))
        var n = 0
        while (sim.speech("a") == null && sim.speech("b") == null) { sim.tick(step); check(++n < 20_000) }
        assertTrue(sim.walk("a", Vec3(3f, 3f, 0f)))
        assertFalse(sim.talking("a")); assertFalse(sim.talking("b"))
        assertNull(sim.speech("a")); assertNull(sim.speech("b"))
    }

    @Test fun thePlayersChatHasWordsAndSayShowsABubble() {
        val sim = sim(listOf("a", "b"), chance = 0f)
        assertTrue(sim.chat("a", "b"))
        assertTrue(sim.talking("a"))
        var n = 0
        while (sim.speech("a") == null) { sim.tick(step); check(++n < 20_000) }
        assertTrue(sim.say("b", "Hello there!"))
        assertEquals("Hello there!", sim.speech("b"))
        assertFalse(sim.say("b", "  "))
        val off = sim(listOf("x", "y"), chance = 0f)
        assertTrue(off.say("x", "Anyone for a drink?"))
        assertEquals(Gestures.TALK, Pose().also { off.pose(0, it) }.gesture)
        repeat(((Reading.seconds("Anyone for a drink?") + 0.1f) / step).toInt()) { off.tick(step) }
        assertNull(off.speech("x"))
        assertEquals(-1, Pose().also { off.pose(0, it) }.gesture)
    }

    @Test fun noChatterNoTalk() {
        val sim = SceneSimulation(listOf("a", "b"), emptySet(), Random(1), wanderOnly = true)
        repeat(60 * 120) { sim.tick(step); assertFalse(sim.talking("a")) }
    }

    @Test fun theLiveSceneSaysWhoSpeaksAndGestures() {
        val live = LiveScene(listOf("a", "b"), emptySet(), emptyMap(), CameraPose(Vec3(0f, -5f, 2f), Vec3(0f, 0f, 1f), 50f),
            0f, 0f, 0f, 0f, Random(4), wanderOnly = true, chatter = chatter(), chatterChance = 1f)
        live.running = true
        val speeches = ArrayList<Pair<Int, String?>>()
        val gestures = ArrayList<Pair<Int, Int>>()
        val sink = object : LiveScene.Sink {
            override fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float) {}
            override fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float) {}
            override fun activity(index: Int, activity: Activity) {}
            override fun speech(index: Int, text: String?, id: Long) { speeches += index to text }
            override fun gesture(index: Int, animation: Int) { gestures += index to animation }
        }
        var now = 0L
        repeat(60 * 90) { now += 16_666_667L; live.frame(now, sink) {} }
        assertTrue(speeches.count { it.second != null } >= 4, "$speeches")
        assertTrue(speeches.any { it.first == 0 && it.second != null } && speeches.any { it.first == 1 && it.second != null })
        assertTrue(gestures.any { it.second == Gestures.STAND } && gestures.any { it.second != Gestures.STAND })
    }

    // ---- the bubbles' layout

    @Test fun theTargetIsTheMiddleOfTheViewAndWhatIsBehindIsOff() {
        val out = FloatArray(4)
        SpeechLayout.project(0f, -10f, 2f, 0f, 0f, 2f, 50f, 800f, 400f, 0f, 0f, 2f, out, 0)
        assertEquals(400f, out[0], 0.5f); assertEquals(200f, out[1], 0.5f); assertEquals(10f, out[2], 0.01f)
        // higher is further up the screen, to the right (+x when looking down +y) is right
        SpeechLayout.project(0f, -10f, 2f, 0f, 0f, 2f, 50f, 800f, 400f, 1f, 0f, 3f, out, 0)
        assertTrue(out[0] > 400f && out[1] < 200f)
        SpeechLayout.project(0f, -10f, 2f, 0f, 0f, 2f, 50f, 800f, 400f, 0f, -20f, 2f, out, 0)
        assertEquals(0f, out[2])
        SpeechLayout.project(0f, -10f, 2f, 0f, 0f, 2f, 50f, 800f, 400f, 50f, 0f, 2f, out, 0)
        assertEquals(0f, out[2], "far off to the side")
    }

    @Test fun onlyTheNearestSpeakersOnScreenGetBubbles() {
        val n = 6
        val layout = FloatArray(n * SpeechLayout.STRIDE)
        val depths = floatArrayOf(5f, 3f, 0f, 9f, 4f, 7f)
        for (i in 0 until n) layout[i * SpeechLayout.STRIDE + 2] = depths[i]
        val speaking = booleanArrayOf(true, true, true, true, true, false)
        SpeechLayout.choose(n, speaking, layout, max = 3)
        val shown = (0 until n).filter { layout[it * SpeechLayout.STRIDE + 3] == 1f }
        assertEquals(listOf(0, 1, 4), shown)
        assertTrue(SpeechLayout.head(2f, sitting = true, lying = false) < 2f)
    }

    // ---- who is controlled and who is their partner

    @Test fun tappingAnotherNpcWhileControllingOneMakesItThePartner() {
        var c = SceneControl()
        c = c.tapNpc("a")
        assertEquals(SceneControl("a", null), c)
        c = c.tapNpc("b")
        assertEquals(SceneControl("a", "b"), c)
        assertEquals(c, c.tapNpc("a"), "tapping the controlled one changes nothing")
        assertEquals(SceneControl("a", "c"), c.tapNpc("c"))
        assertEquals(SceneControl("a", null), c.tapNpc("b"), "the partner again clears it")
        assertEquals(SceneControl("a", null), c.clearPartner())
        assertEquals(SceneControl("b", null), c.tapCast("b"), "the cast bar switches who is controlled")
        assertEquals(SceneControl(), c.tapCast("a"))
        assertEquals(SceneControl("a", null), c.keep(listOf("a")))
        assertEquals(SceneControl(), c.keep(listOf("b")))
    }
}
