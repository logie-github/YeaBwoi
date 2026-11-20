package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the desktop scene film found wrong with the live scene's life and fixed (tools/film): needs that hit empty took turns
 * cutting each other off (a death spiral), meals at mealtimes on a full stomach, nights broken into naps, conversations that
 * cost hours of game time, people standing in each other, the inn's chair stood up in, the nap's text, greetings that
 * ignored the hour, rooms without their props, the player's invitation refused while the other was busy, a far room view.
 * Everything seeded; nothing here has run on a phone.
 */
class LifeFlowTest {
    private val step = 0.1f

    private fun scene(ids: List<String>, objects: Set<SceneObject>, seed: Int = 7, chatter: Boolean = true,
        route: ((Vec3, Vec3) -> List<Vec3>?)? = null, pairs: List<PartnerPair> = emptyList(), partner: Float = PartnerFrequency.SOMETIMES.chance,
    ): Pair<SceneSimulation, LifeSim> {
        val words = if (chatter) Chatter(ids.associateWith { Speaker(it) }, "the Stormwind Embassy") else null
        val s = SceneSimulation(ids, objects, Random(seed), wanderOnly = true, route = route, partnerChance = partner, pairs = pairs,
            chatterChance = if (chatter) ChatterFrequency.SOMETIMES.chance else 0f, chatter = words)
        val life = LifeSim(ids, emptyMap(), seed.toLong())
        s.life = life
        life.setSpeed(LifeSpeed.FASTEST)
        return s to life
    }

    /** What happened to each person over a run: the longest stretch of any need at empty, activity changes an hour, meals. */
    private class Log(n: Int) {
        val emptySince = Array(n) { DoubleArray(Need.COUNT) { -1.0 } }
        val longestEmpty = Array(n) { DoubleArray(Need.COUNT) }
        val changes = Array(n) { HashMap<Int, Int>() }
        val last = arrayOfNulls<InteractionId>(n)
        var fullMeals = 0
        var meals = 0
        val sleepStarts = Array(n) { ArrayList<Double>() }
        val sleepEnds = Array(n) { ArrayList<Double>() }
    }

    private fun run(s: SceneSimulation, life: LifeSim, hours: Double, log: Log = Log(life.size)): Log {
        val end = life.clock.simHours + hours
        while (life.clock.simHours < end) {
            s.tick(step)
            val now = life.clock.simHours
            for (i in 0 until life.size) {
                val id = life.ids[i]
                val needs = life.needs(id)!!
                for (k in 0 until Need.COUNT) {
                    if (needs[k] < 0.01f) {
                        if (log.emptySince[i][k] < 0) log.emptySince[i][k] = now
                        log.longestEmpty[i][k] = maxOf(log.longestEmpty[i][k], now - log.emptySince[i][k])
                    } else log.emptySince[i][k] = -1.0
                }
                val doing = life.doing(id)
                if (doing != log.last[i]) {
                    if (doing != null) log.changes[i].merge(now.toInt(), 1, Int::plus)
                    if (doing == InteractionId.EAT) { log.meals++; if (needs[Need.HUNGER.ordinal] > LifeSim.EAT_BELOW + 0.05f) log.fullMeals++ }
                    if (doing == InteractionId.SLEEP || doing == InteractionId.NAP) log.sleepStarts[i] += now
                    val was = log.last[i]
                    if (was == InteractionId.SLEEP || was == InteractionId.NAP) log.sleepEnds[i] += now
                    log.last[i] = doing
                }
            }
        }
        return log
    }

    // ---- 1. the needs' death spiral; 2. meals on a full stomach

    @Test fun threeDaysNoNeedSitsEmptyAndNobodyFlaps() {
        // the Embassy's cast in a room with a table only (no bed: they doze on the floor), then an inn room with a bed
        for ((objects, ids) in listOf(setOf(SceneObject.TABLE) to listOf("elena", "brannoc", "sylvara", "fizzwick"),
            setOf(SceneObject.BED, SceneObject.CHAIR, SceneObject.TABLE) to listOf("aldric", "nerana"))) {
            val (s, life) = scene(ids, objects)
            // the film's start: one starving, one worn out
            life.setNeed(ids[0], Need.HUNGER, 0.0f)
            life.setNeed(ids[1], Need.ENERGY, 0.02f)
            val log = run(s, life, 72.0)
            for (i in ids.indices) {
                for (k in 0 until Need.COUNT) assertTrue(log.longestEmpty[i][k] <= 3.0,
                    "$objects ${ids[i]}: ${Need.entries[k]} sat empty ${"%.1f".format(log.longestEmpty[i][k])} game hours")
                val worst = log.changes[i].values.maxOrNull() ?: 0
                assertTrue(worst <= 8, "$objects ${ids[i]}: $worst activity changes in one game hour")
                assertTrue(log.sleepStarts[i].isNotEmpty(), "$objects ${ids[i]} never slept")
            }
            assertTrue(log.meals > 0)
            assertEquals(0, log.fullMeals, "$objects: ${log.fullMeals} of ${log.meals} meals begun on a full stomach")
        }
    }

    @Test fun allEmptyAtNightTheyMendTheQuickNeedsThenSleepLong() {
        val (s, life) = scene(listOf("a", "b"), setOf(SceneObject.BED, SceneObject.CHAIR, SceneObject.TABLE), chatter = false, partner = 0f)
        life.clock.restore(21.0)
        for (id in listOf("a", "b")) for (n in listOf(Need.ENERGY, Need.HUNGER, Need.THIRST)) life.setNeed(id, n, 0f)
        val log = run(s, life, 12.0)
        for (i in 0..1) {
            for (k in 0 until Need.COUNT) assertTrue(log.longestEmpty[i][k] <= 3.0,
                "${life.ids[i]}: ${Need.entries[k]} sat empty ${"%.1f".format(log.longestEmpty[i][k])} h")
            val worst = log.changes[i].values.maxOrNull() ?: 0
            assertTrue(worst <= 8, "${life.ids[i]}: $worst changes in an hour")
        }
    }

    @Test fun noMealOnAFullStomachAtMealtime() {
        val (s, life) = scene(listOf("a"), setOf(SceneObject.TABLE), chatter = false, partner = 0f)
        life.clock.restore(12.2)
        for (n in Need.entries) life.setNeed("a", n, 0.95f)
        life.freeze("a", true)
        val log = run(s, life, 1.5)
        assertEquals(0, log.meals, "ate ${log.meals} times full at noon")
    }

    // ---- 3. one night, one sleep

    @Test fun aTiredOneWithAFreeBedSleepsTheNightThrough() {
        val (s, life) = scene(listOf("a"), setOf(SceneObject.BED, SceneObject.TABLE), chatter = false, partner = 0f)
        life.setTraits("a", listOf(Trait.CALM))
        life.clock.restore(22.5)
        for (n in Need.entries) life.setNeed("a", n, 0.8f)
        life.setNeed("a", Need.ENERGY, 0.3f)
        val log = run(s, life, 10.0)
        val starts = log.sleepStarts[0]
        assertEquals(1, starts.count { it < 22.5 + 9.0 }, "sleeps begun in the night: $starts, ended ${log.sleepEnds[0]}")
        val end = log.sleepEnds[0].firstOrNull() ?: Double.MAX_VALUE
        assertTrue(end - starts[0] >= 6.5, "slept ${"%.1f".format(end - starts[0])} h (from ${starts[0]} to $end)")
        assertTrue(end % 24.0 >= 5.5, "woke at ${end % 24.0}")
    }

    // ---- 4. talking costs at most a little game time, and a pressing need ends a chat politely

    @Test fun aLongChatCostsLittleAndHungerEndsItPolitely() {
        val (s, life) = scene(listOf("a", "b"), emptySet(), partner = 0f)
        for (id in listOf("a", "b")) for (n in Need.entries) life.setNeed(id, n, 0.9f)
        // a player's chat: however long it is read, the needs fall at most TALK_COST_HOURS' worth
        assertTrue(s.lifeInteract("a", "b", InteractionId.CHAT).accepted)
        var t = 0f
        while (life.doing("a") != InteractionId.CHAT && t < 30f) { s.tick(step); t += step }
        val before = life.need("a", Need.HUNGER)
        val start = life.clock.simHours
        while (s.talking("a") && t < 300f) { s.tick(step); t += step }
        val hours = life.clock.simHours - start
        val fell = before - life.need("a", Need.HUNGER)
        assertTrue(hours > LifeSim.TALK_COST_HOURS, "the chat ran ${hours} h")
        assertTrue(fell <= LifeSim.TALK_COST_HOURS / Need.HUNGER.hoursToEmpty * 1.6f + 0.01f, "hunger fell $fell over a $hours h chat")

        // their own chat, hunger urgent: they excuse themselves
        val (s2, life2) = scene(listOf("a", "b"), emptySet(), partner = 0f)
        for (id in listOf("a", "b")) { for (n in Need.entries) life2.setNeed(id, n, 0.9f); life2.setNeed(id, Need.SOCIAL, 0.05f) }
        var t2 = 0f
        while (!(s2.talking("a") && life2.doing("a") == InteractionId.CHAT) && t2 < 200f) { s2.tick(step); t2 += step }
        assertTrue(s2.talking("a"), "they never talked")
        val talker = if (s2.talkingWith("a") != null) "a" else "b"
        life2.setNeed(talker, Need.HUNGER, 0.02f)
        life2.freeze(talker, true)
        var said: String? = null
        val lines = listOf("I should get something to eat.", "Excuse me, my stomach's calling.")
        while (t2 < 260f && said == null) {
            s2.tick(step); t2 += step
            said = listOf("a", "b").mapNotNull { s2.speech(it) }.firstOrNull { it in lines }
        }
        assertNotNull(said, "no excuse said")
        assertFalse(s2.talking("a"))
    }

    // ---- 6. nobody stands in another

    @Test fun peopleDoNotStackOnOneSpot() {
        val ids = listOf("a", "b", "c", "d")
        val (s, life) = scene(ids, setOf(SceneObject.TABLE, SceneObject.CHAIR))
        var close = 0
        var samples = 0
        val pose = Pose()
        val at = Array(ids.size) { FloatArray(2) }
        val end = life.clock.simHours + 24.0
        var k = 0
        while (life.clock.simHours < end) {
            s.tick(step)
            if (++k % 3 != 0) continue
            for (i in ids.indices) { s.pose(i, pose); at[i][0] = pose.x; at[i][1] = pose.y }
            val people = s.people()
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                // a two-person animation stands them together on purpose; passing each other walking is no stack
                if (people[i].partner == ids[j]) continue
                if (people[i].activity == Activity.WALK || people[j].activity == Activity.WALK) continue
                samples++
                if (hypot(at[i][0] - at[j][0], at[i][1] - at[j][1]) < 0.35f) close++
            }
        }
        assertTrue(close <= samples / 200, "$close of $samples pair samples closer than 0.35 yd")
    }

    @Test fun twoGettingUpFromTheSameSpotStandApart() {
        // the table's floor spot taken by one standing there: the one getting up from the table stands beside
        val (s, life) = scene(listOf("a", "b"), setOf(SceneObject.TABLE), chatter = false, partner = 0f)
        life.enabled = false
        assertTrue(s.use("a", SceneObject.TABLE))
        var m = 0
        while (s.people().first { it.id == "a" }.furniture != SceneObject.TABLE && m++ < 400) s.tick(step)
        val entry = s.people().first { it.id == "a" }.entry
        assertTrue(s.walk("b", entry))
        var n = 0
        while (s.position("b")!!.let { hypot(it.x - entry.x, it.y - entry.y) } > 0.05f && n++ < 400) s.tick(step)
        assertEquals(Activity.READ, s.people().first { it.id == "a" }.activity)
        s.freeWill("a")
        val a = s.position("a")!!; val b = s.position("b")!!
        assertTrue(hypot(a.x - b.x, a.y - b.y) >= SceneSimulation.MIN_SEPARATION - 1e-3f, "a $a, b $b")
    }

    // ---- 8. the nap's words; 10. a nap with no bed

    @Test fun aNapSaysWhereItWasHad() {
        val (s, life) = scene(listOf("a"), setOf(SceneObject.BED), chatter = false, partner = 0f)
        life.clock.restore(15.0)
        for (n in Need.entries) life.setNeed("a", n, 0.9f)
        life.setNeed("a", Need.ENERGY, 0.1f)
        life.freeze("a", true)
        var t = 0f
        while (life.doing("a") != InteractionId.NAP && life.doing("a") != InteractionId.SLEEP && t < 120f) { s.tick(step); t += step }
        val text = life.events().last { it.icon == "event_sleep" }.text
        assertFalse("couch" in text, text)
        assertTrue("bed" in text, text)
    }

    @Test fun tiredWithNoBedOrSeatTheyDozeOnTheFloor() {
        val (s, life) = scene(listOf("a"), setOf(SceneObject.TABLE), chatter = false, partner = 0f)
        life.clock.restore(23.0)
        for (n in Need.entries) life.setNeed("a", n, 0.9f)
        life.setNeed("a", Need.ENERGY, 0.05f)
        life.freeze("a", true)
        var t = 0f
        while (life.doing("a") != InteractionId.NAP && t < 120f) { s.tick(step); t += step }
        assertEquals(InteractionId.NAP, life.doing("a"))
        assertTrue(life.events().any { it.text.endsWith("dozed off on the floor.") }, life.events().map { it.text }.toString())
        repeat(20) { s.tick(step) }
        val pose = Pose(); s.pose(0, pose)
        assertEquals(PlaybackKeys.emote(LifeAnims.SIT_GROUND), pose.playback?.clip)
    }

    // ---- 9. greetings by the hour

    @Test fun greetingsNameTheHour() {
        assertEquals("Morning", Chatter.dayPart(10.15))
        assertEquals("Afternoon", Chatter.dayPart(13.0))
        assertEquals("Evening", Chatter.dayPart(19.5))
        assertNull(Chatter.dayPart(2.0))
        assertNull(Chatter.dayPart(Double.NaN))
        val c = Chatter(mapOf("a" to Speaker("Ann"), "b" to Speaker("Bob")), "Dalaran")
        val r = Random(3)
        for ((hour, wrong) in listOf(10.15 to listOf("Evening,", "Afternoon,", "Still up"), 19.0 to listOf("Morning,", "Afternoon,", "Still up"),
            2.0 to listOf("Morning,", "Afternoon,", "Evening,"), Double.NaN to listOf("Morning,", "Afternoon,", "Evening,", "Still up"))) {
            c.hour = hour
            repeat(60) {
                val first = c.conversation("a", "b", r).first().text
                for (w in wrong) assertFalse(first.startsWith(w), "at $hour: $first")
            }
        }
    }

    // ---- 7. the locations' chairs; 10. their props

    private val locations by lazy { File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets", "locations.json").readText() }

    @Test fun nobodyStandsUpInsideAChair() {
        for (loc in LocationInfo.parseAll(locations)) for (i in loc.interactions) {
            if (i.item != "CHAIR" && i.item != "COUCH" && i.item != "BED") continue
            val d = hypot(i.entry.x - i.pose.x, i.entry.y - i.pose.y)
            assertTrue(d >= 0.5f, "${loc.id} ${i.item}: its entry is ${"%.2f".format(d)} yd from its pose")
        }
    }

    @Test fun propsAreTheRoomsOwnBookshelves() {
        val gold = LifeProps.of(locations, "goldshire_inn")
        assertEquals(2, gold.size)
        assertTrue(gold.all { it.kind == LifePropKind.BOOKSHELF })
        assertEquals(gold, LifeProps.of(locations, "Lion's Pride Inn, Goldshire"))
        assertTrue(LifeProps.of(locations, "embassy").isEmpty())   // no fireplace, basin or shelf of the game's in its hall
        assertTrue(LifeProps.of(locations, "nowhere").isEmpty())
        // turned into the scene's frame as the furniture is
        val turned = LifeProps.of(locations, "goldshire_inn", { Vec3(it.x - 1f, it.y, it.z) }, 0.5f)
        assertEquals(gold[0].entry.x - 1f, turned[0].entry.x, 1e-5f)
        assertEquals(gold[0].facing + 0.5f, turned[0].facing, 1e-5f)
        // a bookshelf is somewhere to read
        val (s, life) = scene(listOf("a"), emptySet(), chatter = false, partner = 0f)
        life.props = listOf(LifeProp(LifePropKind.BOOKSHELF, Vec3(2f, 0f, 0f), 0f))
        life.setTraits("a", listOf(Trait.BOOKISH))
        for (n in Need.entries) life.setNeed("a", n, 1f)
        life.setNeed("a", Need.FUN, 0.1f)
        life.freeze("a", true)
        var t = 0f
        while (life.doing("a") != InteractionId.READ && t < 120f) { s.tick(step); t += step }
        assertEquals(InteractionId.READ, life.doing("a"))
    }

    // ---- 11. the player's invitation waits for a busy other

    @Test fun anInvitationToABusyOneWaitsAndThenAsks() {
        val (s, life) = scene(listOf("a", "b", "c"), emptySet(), partner = 0f)
        for (id in life.ids) for (n in Need.entries) life.setNeed(id, n, 0.9f)
        life.relationship("a", "b")!!.friendship = 60f
        // b goes to chat with c at the player's order: busy with it
        assertTrue(s.lifeInteract("b", "c", InteractionId.CHAT).accepted)
        repeat(5) { s.tick(step) }
        val busy = s.lifeInteract("a", "b", InteractionId.DANCE_TOGETHER)
        assertFalse(busy.accepted)
        assertTrue("busy" in busy.message && "in a moment" in busy.message, busy.message)
        var t = 0f
        var notice: String? = null
        while (notice == null && t < 120f) { s.tick(step); t += step; notice = s.takeQueueNotice() }
        assertNotNull(notice)
        assertTrue("Dance together" in notice || "refused" in notice || "still busy" in notice, notice)
    }

    // ---- 5. a pair on a bed bigger than the usual one

    @Test fun aBedPairReachesABigBedFromFartherOutOrSaysWhy() {
        val F = PoseFixtures
        val p = PartnerPair("lie2", "Pair lie2", F.lyingBack(0.55), F.lyingBack(0.55), PartnerSlot.human(0f, 0f, 0f, 0f),
            PartnerSlot.human(0f, (0.6 * BodyGeometry.REF_UNIT).toFloat(), 0f, 0f), true)
        val bed = SceneObject.BED
        // the Lion's Pride's canopy bed: its frame 2.3 yards each side of its middle, nothing inside it can be walked to
        fun inside(v: Vec3) = abs(v.x - bed.approach.x) < 2.3f && abs(v.y - bed.approach.y) < 2.3f
        val walls: (Vec3, Vec3) -> List<Vec3>? = { _, to -> if (inside(to)) null else listOf(to) }
        val sim = SceneSimulation(listOf("a", "b"), setOf(bed), Random(1), pairs = listOf(p), route = walls)
        sim.setSpot(bed, InteractionSpot(Vec3(bed.approach.x - 3.2f, bed.approach.y, 0f), bed.approach, -1.5707964f))
        assertTrue(sim.partnerOn(bed, p.id, "a", "b"), sim.takeRefusal() ?: "")
        var n = 0
        while (sim.partnerOf("a")?.stage != "PLAY" && n < 20_000) { sim.tick(1f / 60f); n++ }
        assertEquals("PLAY", sim.partnerOf("a")?.stage)
        // nowhere to stand: refused with a reason for the player
        val shut = SceneSimulation(listOf("a", "b"), setOf(bed), Random(1), pairs = listOf(p), route = { _, _ -> null })
        assertFalse(shut.partnerOn(bed, p.id, "a", "b"))
        assertTrue(shut.takeRefusal()!!.contains("reach"))
    }

    // ---- 12. the room's view brought to the cast

    @Test fun aFarRoomViewIsBroughtToTheCast() {
        val far = CameraPose(Vec3(-11f, 0f, 3.6f), Vec3(0f, 0f, 2.9f), 50f)
        val framed = CastFraming.frame(far, 0f, 0f, 1.7f, 1.5f)
        val d = hypot(framed.eye.x, framed.eye.y)
        assertTrue(d < 7f && d >= CastFraming.MIN_DISTANCE, "eye ${framed.eye}")
        assertTrue(framed.eye.x < 0f && abs(framed.eye.y) < 1e-4f, "the room's side: ${framed.eye}")
        assertEquals(1.7f + CastFraming.AT_HEIGHT, framed.at.z, 1e-4f)
        // the floor nearer than 5 yards cut off from the room's eye (a counter): it stands farther back, never nearer
        val walled = CastFraming.frame(far, 0f, 0f, 1.7f, 1.5f, CameraClearance { eye, _ -> eye.x <= -5f })
        assertTrue(walled.eye.x <= -5f && walled.eye.x > -11f, "${walled.eye}")
        // nowhere clear: the room's own view
        assertTrue(CastFraming.frame(far, 0f, 0f, 1.7f, 1.5f, CameraClearance { _, _ -> false }) === far)
        // near already: kept
        val near = CameraPose(Vec3(-4f, 0f, 3f), Vec3(0f, 0f, 2f), 60f)
        assertTrue(CastFraming.frame(near, 0f, 0f, 1f, 1f) === near)
    }
}
