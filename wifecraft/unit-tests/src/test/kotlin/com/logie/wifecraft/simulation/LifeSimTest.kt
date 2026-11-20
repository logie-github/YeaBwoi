package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.story.Vec3
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The life simulation (LifeSim) on its own and laid over the scene's runtime (SceneSimulation): the clock, needs falling
 * and filling, traits, each need sending an NPC to what fills it, moods and thoughts, relationships rising with talk and
 * gating what NPCs pick by themselves, refusals, the player's interactions, saving, and three game days of six NPCs with
 * nobody stuck. Everything seeded.
 */
class LifeSimTest {
    private val step = 0.1f
    private val all = setOf(SceneObject.BED, SceneObject.COUCH, SceneObject.CHAIR, SceneObject.TABLE)

    /** A room with nothing in it and everyone standing at the origin. */
    private class Bare(override val chatterOn: Boolean = true, override val pairsOn: Boolean = true) : LifeWorld {
        val finished = ArrayList<Int>()
        override fun x(index: Int) = index.toFloat()
        override fun y(index: Int) = 0f
        override fun freeSpot(item: SceneObject, index: Int): Vec3? = null
        override fun approachable(index: Int) = true
        override fun finish(index: Int) { finished += index }
    }

    private fun scene(ids: List<String>, objects: Set<SceneObject> = all, seed: Int = 11, chatter: Boolean = true,
        pairs: List<PartnerPair> = emptyList(), partner: Float = PartnerFrequency.SOMETIMES.chance,
        profiles: Map<String, LifeProfile> = emptyMap()): Pair<SceneSimulation, LifeSim> {
        val words = if (chatter) Chatter(ids.associateWith { Speaker(profiles[it]?.name ?: it, profiles[it]?.race ?: 0, profiles[it]?.female) }, "Dalaran") else null
        val s = SceneSimulation(ids, objects, Random(seed), partnerChance = partner, pairs = pairs,
            chatterChance = if (chatter) ChatterFrequency.SOMETIMES.chance else 0f, chatter = words)
        val life = LifeSim(ids, profiles, seed.toLong())
        s.life = life
        return s to life
    }

    private fun anim(id: String) = Anim(id, id, "Test", 2f, false, emptyList(), "test:$id", 0L)
    private fun pair(id: String, name: String) =
        PartnerPair(id, name, anim("$id-a"), anim("$id-b"), PartnerSlot(-0.4f, 0f, 0f, 0f), PartnerSlot(0.4f, 0f, 0f, 180f), false)

    /** Sets every need of [id] to [rest], then [need] to [low]. */
    private fun needs(life: LifeSim, id: String, need: Need?, low: Float, rest: Float = 1f) {
        for (n in Need.entries) life.setNeed(id, n, rest)
        if (need != null) life.setNeed(id, need, low)
    }

    private fun runUntil(s: SceneSimulation, seconds: Float, done: () -> Boolean): Boolean {
        var t = 0f
        while (t < seconds) { s.tick(step); t += step; if (done()) return true }
        return false
    }

    // ---- the clock

    @Test fun theClockRunsAGameMinuteARealSecondAtX1AndFasterOrNotAtAll() {
        val c = LifeClock()
        assertEquals(8.0, c.simHours, 1e-9)
        repeat(600) { c.advance(0.1f) }
        assertEquals(9.0, c.simHours, 1e-6)
        c.speed = LifeSpeed.FASTEST
        repeat(100) { c.advance(0.1f) }
        assertEquals(10.0, c.simHours, 1e-6)
        c.speed = LifeSpeed.PAUSE
        repeat(100) { c.advance(0.1f) }
        assertEquals(10.0, c.simHours, 1e-6)
        assertEquals(1, c.day)
        assertEquals(10.0, c.hourOfDay, 1e-6)
    }

    @Test fun aWorldClockDrivesTheHoursAcrossMidnight() {
        var world = 23.5
        val c = LifeClock()
        c.source = object : LifeTime { override val simHours get() = world }
        assertEquals(0.0, c.advance(0.1f), 1e-9)
        assertEquals(23.5, c.hourOfDay, 1e-9)
        world = 23.9; assertEquals(0.4, c.advance(0.1f), 1e-9)
        world = 0.2; assertEquals(0.3, c.advance(0.1f), 1e-9)
        world = 10.0; assertEquals(LifeClock.MAX_STEP_HOURS, c.advance(0.1f), 1e-9)
        // the world's own clock drives it the same way
        val wc = WorldClock(21.0)
        val lc = LifeClock().apply { source = wc }
        lc.advance(0.1f)
        assertEquals(21.0, lc.hourOfDay, 1e-9)
    }

    // ---- needs and traits

    @Test fun needsFallOverAGameDayAndTraitsChangeHowFast() {
        val world = Bare()
        val l2 = LifeSim(listOf("a", "b"), seed = 3)
        l2.setTraits("a", listOf(Trait.GLUTTON)); l2.setTraits("b", listOf(Trait.ASCETIC))
        for (id in listOf("a", "b")) needs(l2, id, null, 1f)
        repeat(1800) { l2.tick(step, world) }   // three game hours
        val glutton = 1f - l2.need("a", Need.HUNGER)
        val ascetic = 1f - l2.need("b", Need.HUNGER)
        assertEquals(3f * Need.HUNGER.decayPerHour * 1.3f, glutton, 0.01f)
        assertEquals(3f * Need.HUNGER.decayPerHour * 0.8f, ascetic, 0.01f)
        // energy runs out in about 19 waking hours: a day's cycle with a night's sleep
        assertEquals(3f * Need.ENERGY.decayPerHour, 1f - l2.need("a", Need.ENERGY), 0.01f)
    }

    @Test fun frozenNeedsStandStillAndAPausedClockStopsThemAll() {
        val life = LifeSim(listOf("a", "b"), seed = 3)
        for (id in listOf("a", "b")) needs(life, id, null, 0.8f, 0.8f)
        life.freeze("a", true)
        val world = Bare()
        repeat(600) { life.tick(step, world) }
        assertEquals(0.8f, life.need("a", Need.HUNGER))
        assertTrue(life.need("b", Need.HUNGER) < 0.8f)
        life.setSpeed(LifeSpeed.PAUSE)
        val b = life.need("b", Need.HUNGER)
        repeat(600) { life.tick(step, world) }
        assertEquals(b, life.need("b", Need.HUNGER))
    }

    @Test fun traitsAreTheSameEachTimeNeverOpposedAndLeanByRace() {
        assertEquals(Traits.assign("nyrelle", 4, true), Traits.assign("nyrelle", 4, true))
        var dwarfGlutton = 0
        var humanGlutton = 0
        for (k in 0 until 400) {
            val d = Traits.assign("npc$k", 3, k % 2 == 0)
            val h = Traits.assign("npc$k", 1, k % 2 == 0)
            for (t in listOf(d, h)) {
                assertEquals(Traits.PER_NPC, t.size)
                for (x in t) for (y in t) assertFalse(Traits.opposed(x, y), "$t")
            }
            if (Trait.GLUTTON in d) dwarfGlutton++
            if (Trait.GLUTTON in h) humanGlutton++
        }
        assertTrue(dwarfGlutton > 2 * humanGlutton, "dwarves $dwarfGlutton, humans $humanGlutton")
        assertTrue(Traits.compatibility(listOf(Trait.GREGARIOUS, Trait.PLAYFUL), listOf(Trait.LONER, Trait.CALM)) < 0f)
        assertTrue(Traits.compatibility(listOf(Trait.BOOKISH, Trait.CALM), listOf(Trait.BOOKISH, Trait.CALM)) > 0.2f)
    }

    // ---- moods and thoughts

    @Test fun aLowNeedMakesTheMoodItsOwnAndAThoughtAboutIt() {
        val life = LifeSim(listOf("a"), seed = 5)
        needs(life, "a", Need.THIRST, 0.05f)
        life.freeze("a", true)
        val world = Bare()
        repeat(20) { life.tick(step, world) }
        assertEquals(Mood.THIRSTY, life.mood("a"))
        val thought = assertNotNull(life.thought("a"))
        assertEquals(Need.THIRST.icon, thought.iconKey)
        assertTrue(thought.text in LifeLines.needThoughts(Need.THIRST))
        assertEquals("wants a cold drink", life.wish("a"))
        needs(life, "a", null, 1f)
        repeat(30) { life.tick(step, world) }
        assertTrue(life.mood("a")!!.good, "${life.mood("a")}")
        assertTrue(life.moodScore("a") > 0.4f)
    }

    // ---- each need sends them to what fills it

    private fun goesTo(need: Need, expect: InteractionId, objects: Set<SceneObject> = all, hour: Double = 13.0,
        traits: List<Trait> = listOf(Trait.CALM), props: List<LifeProp> = emptyList()) {
        val (s, life) = scene(listOf("a", "b"), objects, chatter = false, partner = 0f)
        life.clock.restore(hour)
        life.props = props
        life.setTraits("a", traits)
        needs(life, "a", need, 0.1f)
        needs(life, "b", null, 1f)
        life.freeze("a", true); life.freeze("b", true)
        assertTrue(runUntil(s, 120f) { s.lifeDoing("a") == expect }, "$need: never ${expect}; doing ${s.lifeDoing("a")}")
    }

    @Test fun tiredAtNightTheyGoToBed() = goesTo(Need.ENERGY, InteractionId.SLEEP, hour = 23.0)
    @Test fun tiredByDayWithNoBedTheyNapOnTheCouch() = goesTo(Need.ENERGY, InteractionId.NAP, objects = setOf(SceneObject.COUCH), hour = 15.0)
    @Test fun hungryTheyEatAtTheTable() = goesTo(Need.HUNGER, InteractionId.EAT)
    @Test fun hungryWithNoTableTheyEatFromThePack() = goesTo(Need.HUNGER, InteractionId.EAT, objects = emptySet())
    @Test fun thirstyTheyDrink() = goesTo(Need.THIRST, InteractionId.DRINK)
    @Test fun boredABookishOneReads() = goesTo(Need.FUN, InteractionId.READ, traits = listOf(Trait.BOOKISH))
    @Test fun boredAPlayfulOneDances() = goesTo(Need.FUN, InteractionId.DANCE, objects = setOf(SceneObject.BED), traits = listOf(Trait.PLAYFUL))
    @Test fun achingTheySit() = goesTo(Need.COMFORT, InteractionId.RELAX)
    @Test fun grubbyWithABasinTheyWash() = goesTo(Need.HYGIENE, InteractionId.WASH,
        props = listOf(LifeProp(LifePropKind.BASIN, Vec3(3f, 0f, 0f), 0f)))
    @Test fun grubbyWithoutOneTheyFreshenUp() = goesTo(Need.HYGIENE, InteractionId.TIDY)
    @Test fun coldTheyKneelByTheFire() = goesTo(Need.COMFORT, InteractionId.WARM, objects = emptySet(),
        props = listOf(LifeProp(LifePropKind.FIREPLACE, Vec3(-2f, 1f, 0f), 1.5f)))

    @Test fun lonelyTheyGoAndTalk() {
        val (s, life) = scene(listOf("a", "b"), partner = 0f)
        needs(life, "a", Need.SOCIAL, 0.05f)
        needs(life, "b", Need.SOCIAL, 0.3f)
        assertTrue(runUntil(s, 120f) { s.lifeDoing("a") == InteractionId.CHAT })
        val before = life.need("a", Need.SOCIAL)
        runUntil(s, 200f) { !s.talking("a") }
        assertTrue(life.need("a", Need.SOCIAL) > before + 0.1f, "${life.need("a", Need.SOCIAL)}")
    }

    @Test fun eatingFillsHungerAndStopsWhenFull() {
        val (s, life) = scene(listOf("a"), chatter = false, partner = 0f)
        life.clock.restore(12.5)
        needs(life, "a", Need.HUNGER, 0.2f, 0.9f)
        assertTrue(runUntil(s, 60f) { s.lifeDoing("a") == InteractionId.EAT })
        assertTrue(runUntil(s, 300f) { s.lifeDoing("a") != InteractionId.EAT })
        assertTrue(life.need("a", Need.HUNGER) > 0.9f, "${life.need("a", Need.HUNGER)}")
        assertTrue(life.events().any { it.text.contains("eat") })
    }

    @Test fun withNoBedFreeTheyWantOneAloudAndDoNotFakeIt() {
        val (s, life) = scene(listOf("a", "b"), objects = setOf(SceneObject.BED), chatter = false, partner = 0f)
        life.clock.restore(23.5)
        needs(life, "a", Need.ENERGY, 0.05f)
        needs(life, "b", Need.ENERGY, 0.05f)
        life.freeze("a", true); life.freeze("b", true)
        // one of them takes the bed; the other wants it and says so
        assertTrue(runUntil(s, 120f) { s.lifeDoing("a") == InteractionId.SLEEP || s.lifeDoing("b") == InteractionId.SLEEP })
        val other = if (s.lifeDoing("a") == InteractionId.SLEEP) "b" else "a"
        assertTrue(runUntil(s, 60f) { life.thought(other)?.text == LifeLines.missing(InteractionId.SLEEP) })
        assertTrue(s.lifeDoing(other) != InteractionId.SLEEP)
    }

    // ---- relationships

    @Test fun chattingMakesFriendsAndFriendsHugButOnlySweetheartsKiss() {
        val pairs = listOf(pair("hug", "Warm Hug"), pair("kiss", "Kiss"), pair("x", "Pair 7"))
        val (s, life) = scene(listOf("a", "b"), objects = emptySet(), pairs = pairs, partner = PartnerFrequency.OFTEN.chance)
        life.setTraits("a", listOf(Trait.GREGARIOUS, Trait.PLAYFUL)); life.setTraits("b", listOf(Trait.GREGARIOUS, Trait.PLAYFUL))
        val r = life.relationship("a", "b")!!
        var seenPairs = 0
        var lastStarts = 0
        val feed = HashSet<String>()
        var t = 0f
        while (t < 3000f) {
            needs(life, "a", Need.SOCIAL, 0.2f, 0.9f); needs(life, "b", Need.SOCIAL, 0.2f, 0.9f)
            s.tick(step); t += step
            life.events().lastOrNull()?.let { feed += it.text }
            if (s.pairStarts != lastStarts) {
                lastStarts = s.pairStarts
                seenPairs++
                val name = s.lastPairName ?: ""
                val level = PairKinds.of(name)
                assertTrue(level.ordinal <= r.allows.ordinal, "$name at ${r.friendship}/${r.romance}")
            }
        }
        assertTrue(r.friendship >= FriendTier.FRIENDLY.min, "friendship ${r.friendship}")
        assertTrue(seenPairs > 0, "no pair played")
        assertTrue("a and b became friends." in feed, "$feed")
    }

    @Test fun theStandingsAreTheGamesReputationNames() {
        assertEquals(FriendTier.NEUTRAL, FriendTier.of(0f))
        assertEquals(FriendTier.FRIENDLY, FriendTier.of(10f))
        assertEquals(FriendTier.EXALTED, FriendTier.of(100f))
        assertEquals(FriendTier.HATED, FriendTier.of(-100f))
        assertEquals(RomanceTier.STRANGER, RomanceTier.of(0f))
        assertEquals(RomanceTier.SOULBOUND, RomanceTier.of(90f))
        val r = Relationship(0, 1)
        assertEquals(Intimacy.CASUAL, r.allows)
        r.friendship = 20f; assertEquals(Intimacy.FRIENDLY, r.allows)
        r.romance = 45f; assertEquals(Intimacy.ROMANTIC, r.allows)
        r.romance = 70f; assertEquals(Intimacy.INTIMATE, r.allows)
        assertEquals(Intimacy.ROMANTIC, PairKinds.of("Slow Dance"))
        assertEquals(Intimacy.FRIENDLY, PairKinds.of("Friendly Hug"))
        assertEquals(Intimacy.INTIMATE, PairKinds.of("Pair 7"))
        assertEquals(Intimacy.CASUAL, PairKinds.ofEmote(67))
    }

    @Test fun aColdShoulderRefusesAFlirtAndItCosts() {
        val (s, life) = scene(listOf("a", "b"), objects = emptySet(), partner = 0f)
        val r = life.relationship("a", "b")!!
        r.friendship = -50f
        assertTrue(life.acceptance(1, 0, InteractionId.FLIRT) < 0.1f)
        val result = s.lifeInteract("a", "b", InteractionId.FLIRT)
        assertFalse(result.accepted, result.message)
        assertTrue(result.message.contains("refused"))
        val before = r.romance
        assertTrue(runUntil(s, 120f) { !s.talking("a") && !s.talking("b") })
        assertTrue(r.romance < before)
        assertTrue(life.events().any { it.text.contains("turned down") })
    }

    @Test fun friendsAcceptAHugAndThePlayersChoiceIsAnyHugThatFits() {
        val (s, life) = scene(listOf("a", "b"), objects = emptySet(), pairs = listOf(pair("h", "Bear Hug")), partner = 0f)
        life.setTraits("a", listOf(Trait.CALM)); life.setTraits("b", listOf(Trait.CALM))
        for (id in listOf("a", "b")) needs(life, id, null, 0.9f)
        val r = life.relationship("a", "b")!!
        r.friendship = 60f
        assertTrue(life.acceptance(1, 0, InteractionId.HUG) > 0.9f)
        val result = s.lifeInteract("a", "b", InteractionId.HUG)
        assertTrue(result.accepted, result.message)
        assertTrue(runUntil(s, 60f) { s.lastPairName == "Bear Hug" })
        val before = r.friendship
        runUntil(s, 60f) { s.partnerOf("a") == null }
        assertTrue(r.friendship > before)
    }

    @Test fun thePlayersSendToPartnerIsNeverHeldBackByStanding() {
        val (s, life) = scene(listOf("a", "b"), objects = emptySet(), pairs = listOf(pair("x", "Pair 7")), partner = 0f)
        life.relationship("a", "b")!!.friendship = -90f
        assertTrue(s.partner("a", "b"))
    }

    @Test fun thePlayersSoloInteractionsUseTheRoomOrSayWhy() {
        val (s, life) = scene(listOf("a"), objects = setOf(SceneObject.BED), chatter = false, partner = 0f)
        assertTrue(s.lifeInteract("a", null, InteractionId.REST).accepted)
        assertTrue(runUntil(s, 60f) { s.lifeDoing("a") == InteractionId.SLEEP })
        val wash = s.lifeInteract("a", null, InteractionId.WASH)
        assertFalse(wash.accepted)
        assertTrue(wash.message.contains("basin"), wash.message)
        val menu = s.lifeMenu("a", null).toMap()
        assertFalse(menu.getValue(InteractionId.WASH))
        assertTrue(menu.getValue(InteractionId.DANCE))
        assertTrue(s.lifeInteract("a", null, InteractionId.DANCE).accepted)
        assertTrue(runUntil(s, 30f) { s.lifeDoing("a") == InteractionId.DANCE })
        assertNotNull(life.view().lastResult)
    }

    @Test fun aJokeIsTheJokesTopic() {
        val c = Chatter(mapOf("a" to Speaker("Ann"), "b" to Speaker("Bob")), "Dalaran")
        c.conversation("a", "b", Random(1), "jokes")
        assertEquals("jokes", c.lastTopicName)
    }

    // ---- saving

    @Test fun theWholeLifeSavesAndComesBack() {
        val (s, life) = scene(listOf("a", "b", "c"))
        repeat(3000) { s.tick(step) }
        life.relationship("a", "c")!!.romance = 33f
        life.freeze("b", true)
        val json = life.toJson()
        val back = LifeSim(listOf("c", "a", "b", "d"), seed = 99)
        assertTrue(back.load(json))
        for (id in listOf("a", "b", "c")) {
            assertEquals(life.traits(id), back.traits(id))
            for (n in Need.entries) assertEquals(life.need(id, n), back.need(id, n), 1e-5f)
        }
        assertEquals(33f, back.relationship("a", "c")!!.romance, 1e-4f)
        assertTrue(back.frozen("b"))
        assertEquals(life.clock.simHours, back.clock.simHours, 1e-9)
        assertEquals(life.events().size, back.events().size)
        assertFalse(back.load("not json"))
        assertEquals(LifeSim.fileName(listOf("a", "b")), LifeSim.fileName(listOf("b", "a")))
    }

    @Test fun theSameSeedLivesTheSameLife() {
        fun run(): String {
            val (s, life) = scene(listOf("a", "b", "c"), seed = 21)
            repeat(6000) { s.tick(step) }
            return life.toJson()
        }
        assertEquals(run(), run())
    }

    // ---- three days, six NPCs

    @Test fun threeGameDaysOfSixNpcsNobodyStuckEveryoneSleeps() {
        val ids = listOf("sylvanas", "jaina", "thrall", "valeera", "tyrande", "muradin")
        val profiles = mapOf("sylvanas" to LifeProfile("Sylvanas", 5, true), "jaina" to LifeProfile("Jaina", 1, true),
            "thrall" to LifeProfile("Thrall", 2, false), "valeera" to LifeProfile("Valeera", 10, true),
            "tyrande" to LifeProfile("Tyrande", 4, true), "muradin" to LifeProfile("Muradin", 3, false))
        val pairs = listOf(pair("h", "Hug"), pair("d", "Dance"), pair("k", "Kiss"), pair("x", "Pair 7"))
        val (s, life) = scene(ids, pairs = pairs, profiles = profiles, seed = 5)
        life.setSpeed(LifeSpeed.FAST)
        val n = ids.size
        val lastState = arrayOfNulls<String>(n)
        val since = FloatArray(n)
        val longest = FloatArray(n)
        val asleep = FloatArray(n)
        val lastDoing = arrayOfNulls<InteractionId>(n)
        val counts = java.util.TreeMap<InteractionId, Int>()
        val pose = Pose()
        var t = 0f
        var hungerSum = 0.0
        var samples = 0
        val start = System.nanoTime()
        var ticks = 0
        while (life.clock.simHours < 8.0 + 72.0) {
            s.tick(step); t += step; ticks++
            for (i in 0 until n) {
                s.pose(i, pose)
                val doing = s.lifeDoing(ids[i])
                val state = "${pose.activity}:${pose.furniture}:${doing}"
                if (state != lastState[i]) { lastState[i] = state; since[i] = 0f } else since[i] += step
                // sleeping a night through is no deadlock
                if (doing != null && doing != lastDoing[i]) counts.merge(doing, 1, Int::plus)
                lastDoing[i] = doing
                if (doing != InteractionId.SLEEP && doing != InteractionId.NAP) longest[i] = maxOf(longest[i], since[i])
                else asleep[i] += step
            }
            if (ticks % 100 == 0) { for (id in ids) hungerSum += life.need(id, Need.HUNGER); samples += ids.size }
        }
        val ms = (System.nanoTime() - start) / 1e6
        println("life: $ticks steps of ${n} NPCs over 3 game days in %.0f ms (%.2f us a step)".format(ms, ms * 1000 / ticks))
        for (i in 0 until n) {
            // nobody stays in one waking state over two real minutes (the longest are a long conversation, a meal, a book)
            assertTrue(longest[i] < 120f, "${ids[i]} stuck ${longest[i]} s")
            assertTrue(life.sleeps(ids[i]) >= 3, "${ids[i]} slept ${life.sleeps(ids[i])} times")
        }
        // the decision tick is throttled: an NPC chooses only when free, a second or more apart
        val perMinute = life.decisions / (t / 60f) / n
        println("life: %.1f decisions a real minute per NPC".format(perMinute))
        assertTrue(perMinute < 60f / 1.25f, "$perMinute")
        val meanHunger = hungerSum / samples
        assertTrue(meanHunger > 0.35, "mean hunger $meanHunger")
        val v = life.view()
        assertEquals(n, v.people.size)
        assertTrue(v.events.isNotEmpty())
        println("life: mean hunger %.2f; ".format(meanHunger) + v.people.joinToString { p ->
            "${p.id} ${p.mood} ${p.traits.map { it.label }} slept ${life.sleeps(p.id)} times, %.1f h".format(
                life.clock.realSeconds(1.0).let { h -> asleep[ids.indexOf(p.id)] / h }) } )
        println("life: interactions begun: $counts")
        println("life: longest awake states (s): " + longest.joinToString { "%.0f".format(it) })
        println("life: " + ids.flatMap { a -> ids.filter { it > a }.map { b -> life.relationship(a, b)!! } }
            .joinToString { "${ids[it.a]}-${ids[it.b]} ${it.friendship.toInt()}/${it.romance.toInt()}" })
    }
}
