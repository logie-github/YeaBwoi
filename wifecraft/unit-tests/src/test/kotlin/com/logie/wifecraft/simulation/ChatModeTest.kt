package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.ChatCharacter
import com.logie.wifecraft.dialogue.ChatClean
import com.logie.wifecraft.dialogue.ChatLength
import com.logie.wifecraft.dialogue.ChatNames
import com.logie.wifecraft.dialogue.ChatPrompt
import com.logie.wifecraft.dialogue.ChatSession
import com.logie.wifecraft.dialogue.ChatSetup
import com.logie.wifecraft.dialogue.ChatSetupPhase
import com.logie.wifecraft.dialogue.ChatTopics
import com.logie.wifecraft.dialogue.ComfortBand
import com.logie.wifecraft.dialogue.DialogueEngine
import com.logie.wifecraft.dialogue.DialogueRequest
import com.logie.wifecraft.dialogue.DialoguePriority
import com.logie.wifecraft.dialogue.FakeDialogueEngine
import com.logie.wifecraft.dialogue.FOURTH_WALL
import com.logie.wifecraft.dialogue.Faction
import com.logie.wifecraft.dialogue.Kit
import com.logie.wifecraft.dialogue.MemoryChatStore
import com.logie.wifecraft.dialogue.PlaceView
import com.logie.wifecraft.story.Vec3
import com.logie.wifecraft.ui.HudOrder
import com.logie.wifecraft.ui.SceneHud
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatModeTest {
    private val lore = Kit.lore
    private val names = ChatNames(File(System.getProperty("wifecraft.assets"), "dialogue/chat_names.json").readText())
    private val place = PlaceView("A House in Brill, Tirisfal", true, 21.0, null, listOf("a hearth"))
    private val prior = JoiPrior(cinematic = true, cameraMode = "ORBIT", cameraView = 2)

    /** Runs a suspend block to completion (the fake engine never really suspends). */
    private fun <T> run(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    private val jaina = Kit.npc("jaina", Kit.HUMAN, true, 2, listOf("CALM"), "A mage of Dalaran.", "Jaina Wren")
    private val you = ChatCharacter("Brannok", Kit.UNDEAD, false, 9)   // an undead warlock

    private fun session(npc: com.logie.wifecraft.dialogue.NpcProfile = jaina, who: ChatCharacter = you, dragon: Boolean = false, seed: Int = 1) =
        ChatSession(npc, who, lore, dragon, place, null, Random(seed))

    private fun setup(store: MemoryChatStore? = MemoryChatStore()) = ChatSetup(lore, names, store, Random(3))

    // ---- setup ----

    @Test fun theSetupAsksForSexAndStartsOnlyWhenEverythingIsChosen() {
        val st = MemoryChatStore()
        val s = setup(st)
        assertEquals(ChatSetupPhase.CLOSED, s.phase)
        s.open()
        assertEquals(ChatSetupPhase.OPEN, s.phase)
        assertNull(s.female, "sex has no default: it is asked")
        assertEquals(listOf("Race", "Sex", "Class", "Name"), s.missing())
        assertFalse(s.canStart); assertNull(s.start())
        s.pickRace(Kit.NIGHTELF); assertEquals(Faction.ALLIANCE, s.faction)
        s.pickClass(2); s.typeName("  Thalara   Moon "); assertNull(s.start(), "sex is still missing")
        assertEquals(listOf("Sex"), s.missing())
        s.pickSex(true)
        val c = s.start()!!
        assertEquals(ChatCharacter("Thalara Moon", Kit.NIGHTELF, true, 2), c)
        assertEquals(ChatSetupPhase.STARTED, s.phase)
        assertNotNull(st.text)
    }

    @Test fun theLastChoiceIsRememberedAndCancelSavesNothing() {
        val st = MemoryChatStore()
        setup(st).apply { open(); pickRace(Kit.ORC); pickSex(false); pickClass(1); typeName("Grosh"); start() }
        val again = setup(st); again.open()
        assertTrue(again.remembered)
        assertEquals(Kit.ORC, again.raceId); assertEquals(false, again.female); assertEquals(1, again.classId); assertEquals("Grosh", again.name)
        assertTrue(again.canStart)
        // a cancelled sheet changes nothing
        val third = setup(st); third.open(); third.pickRace(Kit.GNOME); third.cancel()
        assertEquals(ChatSetupPhase.CANCELLED, third.phase)
        val fourth = setup(st); fourth.open(); assertEquals(Kit.ORC, fourth.raceId)
        // a broken or unknown store is ignored
        val bad = setup(MemoryChatStore("{nonsense")); bad.open(); assertFalse(bad.remembered); assertNull(bad.raceId)
        val gone = setup(MemoryChatStore(ChatSetup.encode(ChatCharacter("X", 999, true, 1)))); gone.open(); assertFalse(gone.remembered)
    }

    @Test fun everyRaceAndClassIsOffered() {
        val s = setup()
        for (r in listOf("human", "orc", "dwarf", "nightelf", "undead", "tauren", "gnome", "troll", "bloodelf", "draenei", "worgen", "goblin", "pandaren"))
            assertTrue(s.races.any { lore.raceKey(it.id) == r }, "missing $r in ${s.races.map { it.name }}")
        for (allied in listOf("voidelf", "nightborne", "lightforged", "zandalari", "highmountain", "kultiran", "darkiron", "vulpera", "magharorc", "mechagnome"))
            assertTrue(s.races.any { lore.raceKey(it.id) == allied }, "missing allied $allied")
        assertEquals(13, s.classes.size)
        assertTrue(s.races.first().id == Kit.HUMAN)
        // a pandaren's side is shown; every race has a side
        assertTrue(s.races.filter { lore.raceKey(it.id) == "pandaren" }.all { "(" in it.name })
    }

    @Test fun theRandomNameIsTheRacesAndChangesEachPress() {
        val s = setup(); s.open()
        assertNull(s.randomName(), "needs a race")
        for (race in s.races) {
            s.pickRace(race.id)
            for (female in listOf(true, false)) {
                s.pickSex(female)
                val a = s.randomName()!!; val b = s.randomName()!!
                assertTrue(a.isNotBlank() && b.isNotBlank()); assertNotEquals(a, b, "another name each press for ${race.name}")
            }
        }
        // names are the race's own data; a file with nothing in it still gives names
        assertTrue(ChatNames(null).random("human", true).isNotBlank())
        assertTrue(ChatNames("not json").random(null, null).isNotBlank())
    }

    @Test fun theNameGeneratorDataCoversEveryRaceOfTheLore() {
        val root = org.json.JSONObject(File(System.getProperty("wifecraft.assets"), "dialogue/chat_names.json").readText())
        for (id in lore.raceIdList()) {
            val key = lore.raceKey(id)!!
            val o = root.optJSONObject(key)
            assertNotNull(o, "no names for $key")
            assertTrue(o.getJSONArray("f").length() >= 5 && o.getJSONArray("m").length() >= 5, key)
        }
    }

    // ---- the prompt ----

    private val wall = Regex("\\b(" + (FOURTH_WALL + listOf("typing", "keyboard", "type", "typed", "message", "chat", "click", "tap", "cursor", "user")).joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    @Test fun herPromptTellsHerWhoStandsBeforeHerAndHowHerPeopleLookOnThem() {
        val s = session()
        val user = ChatPrompt.user(lore, ChatPrompt.Turn(jaina, you, place, ComfortBand.STRANGERS, emptyList(), "Well met.", false, ChatLength.NORMAL))
        assertTrue("A Forsaken warlock called Brannok stands before you, a man of the Horde." in user, user)
        assertTrue("Forsaken" in user && "Alliance" in user, "the lore stance Alliance toward the Forsaken is there:\n$user")
        assertTrue("wary of Brannok" in user, "an Alliance woman is wary of a Forsaken stranger:\n$user")
        assertTrue("Brannok says to you: \"Well met.\"" in user)
        val sys = ChatPrompt.system(jaina, lore, false)
        assertTrue("Jaina Wren" in sys && "mage" in sys)
        assertFalse(wall.containsMatchIn(sys), "fourth wall in the system prompt: ${wall.find(sys)?.value}")
        assertFalse(wall.containsMatchIn(user), "fourth wall in the user prompt: ${wall.find(user)?.value}")
        // an Alliance paladin facing a Forsaken warlock, and a friend of her own people: only the stranger worries her
        val kin = ChatCharacter("Aldrin", Kit.HUMAN, false, 2)
        val friendly = ChatPrompt.user(lore, ChatPrompt.Turn(jaina, kin, place, ComfortBand.STRANGERS, emptyList(), "Hello.", false, ChatLength.NORMAL))
        assertFalse("wary of Aldrin" in friendly)
        assertTrue("A human paladin called Aldrin stands before you, a man of the Alliance." in friendly)
    }

    @Test fun herPersonaKeepsItsVoiceAndTheChatRulesReplaceTheOneOrTwoSentenceLine() {
        val sys = ChatPrompt.system(jaina, lore, false)
        assertFalse("one or two sentences" in sys)
        assertTrue("You are a real person living in this world" in sys)
        assertTrue("ordinary person of this world" in sys)
    }

    @Test fun theDragonSpeaksInHerOwnVoice() {
        val sind = Kit.npc("sindragosa", 0, true, 0, emptyList(), "", "Sindragosa")
        val sys = ChatPrompt.system(sind, lore, true)
        assertTrue("frost dragon" in sys && "imperious" in sys && "ancient" in sys)
        assertFalse(wall.containsMatchIn(sys))
        val s = ChatSession(sind, you, lore, true, place, null, Random(2))
        val engine = FakeDialogueEngine("You are small, and warm, and you are welcome to stand there.")
        assertEquals("You are small, and warm, and you are welcome to stand there.", run { s.say("Greetings, great one.", engine) })
        assertTrue("frost dragon" in engine.requests.single().system)
    }

    @Test fun desireTalkOpensTheBodyAndTheKinkGuideOnlyWhenTheEaseAllowsIt() {
        val strangers = ChatPrompt.user(lore, ChatPrompt.Turn(jaina, you, place, ComfortBand.STRANGERS, emptyList(), "I want you.", true, ChatLength.NORMAL))
        assertFalse("Their body" in strangers, "no bodies between strangers")
        val comfy = ChatPrompt.user(lore, ChatPrompt.Turn(jaina, you, place, ComfortBand.COMFORTABLE, emptyList(), "I want you.", true, ChatLength.NORMAL))
        assertTrue("Their body" in comfy)
        assertTrue("that is talk only" in comfy && "Do not make the sounds of lovemaking" in comfy, "chat is no sexual stage")
        val none = ChatPrompt.user(lore, ChatPrompt.Turn(jaina, you, place, ComfortBand.COMFORTABLE, emptyList(), "Nice day.", false, ChatLength.NORMAL))
        assertFalse("Their body" in none)
        assertTrue(ChatPrompt.raisesDesire("I would kiss you") && !ChatPrompt.raisesDesire("Fine weather"))
    }

    // ---- the talk ----

    @Test fun repliesAreCutToTheChosenLength() {
        val long = "First, a thought of mine. Second, another that follows it. Third, one more to close. Fourth, which is too many."
        assertEquals("First, a thought of mine.", ChatClean.clean(long, null, ChatLength.SHORT))
        assertEquals("First, a thought of mine. Second, another that follows it.", ChatClean.clean(long, null, ChatLength.NORMAL))
        assertEquals("First, a thought of mine. Second, another that follows it. Third, one more to close.", ChatClean.clean(long, null, ChatLength.LONG))
        assertEquals("Aye.", ChatClean.clean("*smiles* Jaina Wren: \"Aye.\" (nods)", "Jaina Wren", ChatLength.NORMAL))
        assertNull(ChatClean.clean("*smiles*", null, ChatLength.NORMAL))
        val ramble = "word ".repeat(200)
        for (l in ChatLength.entries) assertTrue(ChatClean.clean(ramble, null, l)!!.length <= l.chars + 3, "$l")
        assertTrue(ChatLength.SHORT.tokens < ChatLength.NORMAL.tokens && ChatLength.NORMAL.tokens < ChatLength.LONG.tokens)
        // through a session: the request asks for the length and the reply is cut to it
        for (l in ChatLength.entries) {
            val s = session(); s.length = l
            val engine = FakeDialogueEngine(long)
            val reply = run { s.say("Tell me more.", engine) }!!
            assertEquals(l.sentences, reply.split(Regex("(?<=[.!?])\\s+")).size)
            val req = engine.requests.single()
            assertEquals(l.tokens, req.maxTokens)
            assertTrue("at most ${l.words} words" in req.user)
        }
    }

    @Test fun herRepliesStreamInAndTheTypingIndicatorShows() {
        val s = session()
        val seen = ArrayList<String>(); var typingDuring = false
        val engine = object : DialogueEngine {
            override suspend fun complete(req: DialogueRequest): String? {
                typingDuring = s.typing
                "Fair weather, and fairer company.".split(" ").runningReduce { a, b -> "$a $b" }.forEach { req.onPartial?.invoke(it); seen += s.partial }
                return "Fair weather, and fairer company."
            }
        }
        assertFalse(s.typing)
        val reply = run { s.say("A fine day.", engine) }
        assertTrue(typingDuring); assertFalse(s.typing); assertEquals("", s.partial)
        assertEquals("Fair weather, and fairer company.", reply)
        assertEquals(listOf("Fair", "Fair weather,", "Fair weather, and", "Fair weather, and fairer", "Fair weather, and fairer company."), seen, "the partial text grows word by word")
    }

    @Test fun anActionOrNameTagStillBeingWrittenIsNotShownInThePreview() {
        assertEquals("Aye, I", ChatClean.preview("Jaina Wren: Aye, I *sm", "Jaina Wren", ChatLength.NORMAL))
        assertEquals("", ChatClean.preview("*leans in", null, ChatLength.NORMAL))
    }

    @Test fun theRequestsAreChatsOwnSelectedAndCarryTheRollingHistory() {
        val s = session()
        val engine = FakeDialogueEngine("Aye.", "Not often.", "Ask the innkeeper.")
        run { s.say("Do you come here often?", engine) }
        run { s.say("Often?", engine) }
        run { s.say("Where do I stay?", engine) }
        val reqs = engine.requests
        assertTrue(reqs.all { it.npcId == "chat:jaina" && it.priority == DialoguePriority.SELECTED })
        assertEquals(0, reqs[0].history.size); assertEquals(2, reqs[1].history.size); assertEquals(4, reqs[2].history.size)
        assertEquals("Do you come here often?", reqs[1].history[0].text)
        assertEquals(6, s.log.size); assertEquals(true, s.log[0].fromYou); assertEquals(false, s.log[1].fromYou)
        assertEquals(ChatPrompt.CHAT_PREFIX + "jaina", s.engineId)
        // the history is rolling
        repeat(12) { run { s.say("again $it", engine) } }
        assertEquals(ChatSession.HISTORY, s.history().size)
    }

    @Test fun theHistoryIsClearedOnLeavingAndOnAReset() {
        val s = session()
        val engine = FakeDialogueEngine("Aye.")
        run { s.say("Hello", engine) }
        s.toggleTopic("their past")
        assertEquals(2, s.log.size); assertTrue(s.history().isNotEmpty()); assertTrue(s.band >= ComfortBand.STRANGERS)
        s.reset()
        assertTrue(s.log.isEmpty()); assertTrue(s.history().isEmpty()); assertTrue(s.steers.isEmpty)
        assertEquals(0f, s.comfort.score(jaina.id, ChatCharacter.ID))
        // leaving the mode resets its session
        val m = ChatMode(1)
        m.open("jaina", false, prior, setup()); fill(m)
        assertTrue(m.start({ session(who = it) }, true))
        val sess = m.session!!
        run { sess.say("Hello", engine) }
        assertEquals(2, sess.log.size)
        m.leave()
        assertTrue(sess.log.isEmpty()); assertNull(m.session)
        // and a fresh session knows nothing (nothing is saved)
        assertTrue(session().log.isEmpty())
    }

    @Test fun aLongFriendlyChatLoosensHerUp() {
        val npc = Kit.npc("lia", Kit.HUMAN, true, 5, listOf("GREGARIOUS", "ROMANTIC"), "Kind and open.", "Lia")
        val s = session(npc, ChatCharacter("Aldrin", Kit.HUMAN, false, 2))
        val engine = FakeDialogueEngine("It is good to talk with you.")
        assertEquals(ComfortBand.STRANGERS, s.band)
        val bands = ArrayList<ComfortBand>()
        repeat(14) { run { s.say("Thank you, friend, it is a pleasure.", engine) }; bands += s.band }
        assertTrue(bands.zipWithNext().all { (a, b) -> b >= a }, "never falls on kind talk: $bands")
        assertTrue(s.band >= ComfortBand.COMFORTABLE, "a long friendly chat reaches comfortable: ${s.band} $bands")
        assertTrue(s.band < ComfortBand.UNINHIBITED, "talk alone never reaches uninhibited")
        // the ledger uses the user's character as a pseudo NPC id; the prompt then names the band
        assertTrue(s.comfort.score("lia", ChatCharacter.ID) > 2f)
        assertTrue("comfortable with each other" in engine.requests.last().user || "very comfortable" in engine.requests.last().user)
        // a shy one loosens slower than a bold one, on the same talk
        fun after(traits: List<String>): Float {
            val x = session(Kit.npc("x", Kit.HUMAN, true, 5, traits), ChatCharacter("Aldrin", Kit.HUMAN, false, 2))
            repeat(8) { run { x.say("Well met, friend.", engine) } }
            return x.comfort.band(x.npc.disposition, "x", ChatCharacter.ID).ordinal.toFloat() + x.comfort.score("x", ChatCharacter.ID) * 0.001f
        }
        assertTrue(after(listOf("BRAVE", "MISCHIEVOUS")) >= after(listOf("LONER", "ASCETIC")))
        // an insult costs ease
        val h = session(npc, ChatCharacter("Aldrin", Kit.HUMAN, false, 2))
        repeat(6) { run { h.say("Hello there.", engine) } }
        val before = h.comfort.score("lia", ChatCharacter.ID)
        run { h.say("You are a stupid fool.", engine) }
        assertTrue(h.comfort.score("lia", ChatCharacter.ID) < before)
    }

    @Test fun aChipIsASteerAndSheSpeaksFirstWithoutAHardCodedLine() {
        val s = session()
        val engine = FakeDialogueEngine { r -> if ("stands quietly, waiting" in r.user) "The market was loud today." else "Aye." }
        val topic = ChatTopics.CHIPS[0].topic
        assertTrue(s.toggleTopic(topic)); assertTrue(s.topicOn(topic))
        val reply = run { s.say(null, engine) }
        assertEquals("The market was loud today.", reply)
        val req = engine.requests.single()
        assertTrue(topic in req.user, "the steer reaches the prompt")
        assertTrue("Brannok stands quietly, waiting for you to speak." in req.user)
        assertEquals(1, s.log.size, "only her line is in the log: no invented line of the user's")
        assertEquals(1, s.history().size)
        assertFalse(s.toggleTopic(topic)); assertFalse(s.topicOn(topic))
        // the chips are labels and topics: none of them is a sentence anyone says
        assertTrue(ChatTopics.CHIPS.size >= 4 && ChatTopics.CHIPS.map { it.label }.containsAll(listOf("Her day", "The war", "Flirt", "Her past")))
    }

    @Test fun refusedRepliesAreAskedAgainAndLiveVocalisationIsKeptOutOfChat() {
        // the fourth wall: refused, asked again with a note, the second try used
        var n = 0
        val wallEngine = FakeDialogueEngine { _ -> if (n++ == 0) "I saw it on the screen, player." else "I have not heard of such a thing." }
        val s = session()
        assertEquals("I have not heard of such a thing.", run { s.say("Do you know about the game?", wallEngine) })
        assertEquals(2, wallEngine.requests.size); assertTrue("beyond this world" in wallEngine.requests[1].user)
        // an anachronism
        var m = 0
        val anachron = FakeDialogueEngine { _ -> if (m++ == 0) "Okay, whatever you say." else "As you wish." }
        assertEquals("As you wish.", run { session().say("Hmm.", anachron) })
        // both tries bad: she says nothing
        val bad = FakeDialogueEngine("Look at the screen.")
        val q = session(); assertNull(run { q.say("Hi", bad) }); assertTrue(q.quiet)
        // live vocalisation: rewritten where something is left, else asked again; never delivered as it came
        val vocal = FakeDialogueEngine("Oh yes, oh yes, harder. I would keep you by the fire all night if you asked.")
        val r = run { session().say("Tell me what you want.", vocal) }!!
        assertFalse(com.logie.wifecraft.dialogue.LiveFilter.isLive(r), r)
        assertTrue("fire" in r)
        // the log never holds a refused line
        assertTrue(q.log.none { !it.fromYou })
    }

    @Test fun withoutAModelThereIsNoAnswerAndTheModeSaysSo() {
        val s = session()
        assertNull(run { s.say("Hello", null) }); assertTrue(s.quiet)
        val off = FakeDialogueEngine("Aye.").also { it.ready = false }
        assertNull(run { s.say("Hello", off) })
        assertTrue(off.requests.isEmpty(), "an engine that is not ready is not asked")
        val silent = FakeDialogueEngine { _ -> null }
        assertNull(run { s.say("Hello", silent) }); assertFalse(s.typing)
        // the mode: no model, the note; a model later, the talk; the model lost, the note again
        val m = ChatMode(1)
        m.open("jaina", false, prior, setup()); fill(m)
        assertEquals(ChatPhase.SETUP, m.phase); assertFalse(m.cameraOn)
        assertTrue(m.start({ session(who = it) }, modelReady = false))
        assertEquals(ChatPhase.NEEDS_MODEL, m.phase); assertTrue(m.cameraOn)
        m.modelReady(); assertEquals(ChatPhase.CHATTING, m.phase)
        m.modelLost(); assertEquals(ChatPhase.NEEDS_MODEL, m.phase)
        m.modelReady(); assertEquals(ChatPhase.CHATTING, m.phase)
    }

    // ---- the mode and the camera state ----

    /** The sheet of an opened mode filled in (the mode opens the sheet itself: nothing is chosen before). */
    private fun fill(m: ChatMode) { m.setup!!.apply { pickRace(1); pickSex(true); pickClass(2); typeName("Lia") } }

    @Test fun leavingPutsBackWhatWasOnAndCancelChangesNothing() {
        val m = ChatMode(2)
        val set = setup()
        assertFalse(m.on)
        m.open("jaina", false, prior, set)
        assertEquals(ChatPhase.SETUP, m.phase)
        assertFalse(m.start({ session(who = it) }, true), "an unfinished sheet doesn't start")
        val back = m.cancel()
        assertEquals(prior, back); assertEquals(ChatPhase.OFF, m.phase); assertNull(m.actor)
        // a talk, and out: the cinematic state, camera mode and furniture view come back as they were
        m.open("jaina", false, JoiPrior(false, "FOLLOW", -1), setup()); fill(m)
        assertTrue(m.start({ session(who = it) }, true))
        assertEquals("jaina", m.actor); assertEquals(ChatPhase.CHATTING, m.phase)
        assertEquals(JoiPrior(false, "FOLLOW", -1), m.leave())
        assertEquals(ChatPhase.OFF, m.phase); assertNull(m.actor); assertNull(m.setup)
        // and again, with different prior state
        m.open("jaina", true, prior, setup()); fill(m)
        assertTrue(m.creature); assertEquals(prior, m.leave())
    }

    private fun body(facing: Float = 0f, x: Float = 0f, y: Float = 0f, h: Float = 1.7f) = JoiBody().also {
        it.set(Vec3(x, y, 1.62f * h / 1.7f), Vec3(x, y, 1.25f * h / 1.7f), Vec3(x, y, 0.95f * h / 1.7f), facing, h, 0f, true)
    }

    private fun runCam(cam: ChatCamera, b: JoiBody, seconds: Float, aspect: Float = 16f / 9f, each: (Float) -> Unit = {}) {
        var t = 0f
        while (t < seconds) { cam.update(1 / 30f, b, aspect); t += 1 / 30f; each(t) }
    }

    @Test fun theCameraStandsInFrontOfHerAtEyeLevelAndDriftsGently() {
        for (facing in listOf(0f, 1.2f, -2.5f)) {
            val b = body(facing); val cam = ChatCamera(5L)
            var minA = 9f; var maxA = -9f; var minD = 99f; var maxD = 0f
            runCam(cam, b, 90f) {
                val dx = cam.eyeX - b.head.x; val dy = cam.eyeY - b.head.y
                val a = JoiOrbit.wrap(atan2(dy, dx) - facing)
                minA = minOf(minA, a); maxA = maxOf(maxA, a)
                val d = hypot(dx, dy); minD = minOf(minD, d); maxD = maxOf(maxD, d)
                assertTrue(abs(cam.eyeZ - ChatFraming.eyeZ(b)) < 0.12f, "eye level: ${cam.eyeZ} against her eyes ${ChatFraming.eyeZ(b)}")
            }
            assertTrue(abs(minA) <= 0.2f && abs(maxA) <= 0.2f, "in front of her (facing $facing): $minA..$maxA")
            assertTrue(maxA - minA > 0.02f, "it drifts: $minA..$maxA")
            assertTrue(maxD / minD < 1.12f && minD > 1.3f && maxD < 2.4f, "a conversation's distance: $minD..$maxD")
            val eye = Vec3(cam.eyeX, cam.eyeY, cam.eyeZ); val at = Vec3(cam.atX, cam.atY, cam.atZ)
            assertTrue(CinematicFraming.inFrame(eye, at, cam.fov, 16f / 9f, ChatFraming.mustShow(b), 0f), "her head and chest are in the picture")
        }
    }

    @Test fun theCameraKeepsHerInFrameInAPhoneAndATabletShapeAndFollowsHerWhereSheMoves() {
        for (aspect in listOf(9f / 16f, 4f / 3f, 20f / 9f)) {
            val b = body(); val cam = ChatCamera(8L)
            runCam(cam, b, 10f, aspect)
            val eye = Vec3(cam.eyeX, cam.eyeY, cam.eyeZ); val at = Vec3(cam.atX, cam.atY, cam.atZ)
            assertTrue(CinematicFraming.inFrame(eye, at, cam.fov, aspect, ChatFraming.mustShow(b), 0f), "aspect $aspect")
            assertTrue(cam.fov <= ChatFraming.FOV + 16.1f)
        }
        val cam = ChatCamera(8L)
        runCam(cam, body(0f, 0f, 0f), 2f)
        runCam(cam, body(0f, 5f, 3f), 4f)
        assertTrue(hypot(cam.atX - 5f, cam.atY - 3f) < 0.4f, "follows her")
        // she is turned away: the camera goes round to her front, smoothly
        val c2 = ChatCamera(9L); runCam(c2, body(0f), 1f)
        runCam(c2, body(3.0f), 6f)
        assertTrue(abs(JoiOrbit.wrap(atan2(c2.eyeY - c2.atY, c2.eyeX - c2.atX) - 3.0f)) < 0.25f)
        // restart snaps to the new body at once
        c2.restart(); c2.update(1 / 30f, body(0f, 9f, 9f), 16f / 9f)
        assertTrue(hypot(c2.atX - 9f, c2.atY - 9f) < 0.6f)
        // an unusable body writes nothing
        assertFalse(ChatCamera(1L).update(0.1f, JoiBody().also { it.set(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f), Vec3(0f, 0f, 0f), 0f, 0f, 0f, false) }, 1.5f))
    }

    @Test fun aWallDrawsTheCameraIn() {
        val b = body(); val open = ChatCamera(5L); runCam(open, b, 1f)
        val dOpen = hypot(open.eyeX - b.head.x, open.eyeY - b.head.y)
        val walled = ChatCamera(5L)
        // everything beyond 1.6 yards from her is behind a wall
        walled.visible = { eye, _ -> hypot(eye.x - b.head.x, eye.y - b.head.y) < 1.6f }
        runCam(walled, b, 2f)
        val dWalled = hypot(walled.eyeX - b.head.x, walled.eyeY - b.head.y)
        assertTrue(dWalled < dOpen && dWalled < 1.9f, "$dWalled against $dOpen")
    }

    @Test fun sindragosaIsSeenFromInFrontOfHerHead() {
        val spec = com.logie.wifecraft.characters.CreatureNpcs.SINDRAGOSA
        val head = Vec3(spec.skullForward, spec.skullSide, spec.skullLift)
        val facing = 0.7f
        val b = JoiBody().also { it.set(head, Vec3(1f, 0f, 1.3f), Vec3(-0.5f, 0f, 1.0f), facing, 3f, 0f, false) }
        val cam = ChatCamera(2L); cam.creature = true; cam.creatureScale = spec.scale
        val k = spec.scale / 0.25f
        runCam(cam, b, 60f) {
            val dx = cam.eyeX - head.x; val dy = cam.eyeY - head.y
            assertTrue(abs(JoiOrbit.wrap(atan2(dy, dx) - facing)) < 0.2f, "in front of her head")
            assertTrue(cam.eyeZ > head.z && cam.eyeZ < head.z + 1.5f * k, "a little above her head: ${cam.eyeZ - head.z}")
            val d = hypot(hypot(dx, dy), cam.eyeZ - head.z)
            assertTrue(d > 1.8f * k && d < 3.3f * k, "distance $d")
        }
        assertTrue(abs(cam.atX - head.x) < 0.1f && abs(cam.atZ - (head.z - ChatFraming.CREATURE_DROP * k)) < 0.1f, "looks a little under her head, so it sits high in the picture")
    }

    // ---- her gestures ----

    @Test fun herGesturesAreSmallOnesAndNeverAnotherKind() {
        val g = ChatGestures(4L)
        val clips = listOf(
            JoiClip("a", "Wave clip"), JoiClip("b", "Hair twirl"), JoiClip("c", "Standing pose"), JoiClip("d", "Slow dance"), JoiClip("e", "Seductive tease"),
            JoiClip("f", "Blow kiss"), JoiClip("g", "Sit on chair"), JoiClip("h", "Explaining gesture"), JoiClip("emote:70", "Laugh", emote = true), JoiClip("emote:67", "Wave", emote = true), JoiClip("emote:999", "Unmeasured", emote = true),
            JoiClip.stroke(), JoiClip("j", "Look over the shoulder"), JoiClip("k", "Dance with partner", kind = "HANDJOB", giver = 0),
        )
        val cat = g.catalogue(clips)
        val keys = cat.keys.map { it.key }.toSet()
        assertEquals(setOf("a", "b", "c", "h", "emote:67", "j"), keys, "$keys (Laugh folds the body: not played; an unmeasured emote is not played either)")
        // picks: never the last, none within the cooldown, and after it again
        val seen = ArrayList<String>()
        var now = 0f
        repeat(6) { g.pick(cat, now)?.let { seen += it.key }; now += 10f }
        assertEquals(seen.size, seen.zipWithNext().count { it.first != it.second } + 1)
        assertTrue(seen.size <= 6)
        val g2 = ChatGestures(1L); val one = g2.catalogue(listOf(JoiClip("a", "Wave")))
        assertNotNull(g2.pick(one, 0f)); assertNull(g2.pick(one, 10f), "cooldown"); assertNull(g2.pick(one, ChatGestures.COOLDOWN + 1f), "not the one just played")
        assertTrue(g.gap(true) < g.gap(false) + 20f && g.gap(true) in 4f..9f && g.gap(false) in 14f..30f)
    }

    @Test fun gesturesAreDueWhileSheSpeaksAndListensAndNotForTheDragon() {
        fun mode(creature: Boolean): ChatMode {
            val m = ChatMode(5L)
            m.open("jaina", creature, prior, setup()); fill(m)
            assertTrue(m.start({ session(who = it) }, true)); return m
        }
        val m = mode(false)
        var speakingDue = 0; var listeningDue = 0
        var t = 0f
        while (t < 120f) { m.update(0.1f, speaking = (t % 30f) < 15f); for (e in m.drain()) if ((e as ChatEvent.GestureDue).speaking) speakingDue++ else listeningDue++; t += 0.1f }
        assertTrue(speakingDue >= 6 && listeningDue >= 2 && speakingDue > listeningDue, "$speakingDue / $listeningDue")
        m.holdGestures(1000f)
        t = 0f; while (t < 60f) { m.update(0.1f, true); t += 0.1f }
        assertTrue(m.drain().isEmpty(), "held while one plays")
        val d = mode(true); t = 0f
        while (t < 120f) { d.update(0.1f, true); t += 0.1f }
        assertTrue(d.drain().isEmpty(), "a creature keeps her own animation")
        // nothing runs before the talk
        val idle = ChatMode(6L); idle.update(5f, true); assertTrue(idle.drain().isEmpty())
    }

    // ---- the command menu ----

    @Test fun theChatOrderSitsBesideJoiAndIsOffByDefault() {
        val on = SceneHud.orders(false, false, 2, false, 0, joi = true, chatMode = true).map { it.order }
        assertTrue(on.indexOf(HudOrder.CHAT_MODE) == on.indexOf(HudOrder.JOI) + 1)
        assertTrue(HudOrder.CHAT in on || SceneHud.orders(false, false, 0, false, 0, joi = true, chatMode = true).any { it.order == HudOrder.CHAT_MODE })
        assertFalse(SceneHud.orders(false, false, 2, false, 0, joi = true).any { it.order == HudOrder.CHAT_MODE })
        assertEquals("Chat", SceneHud.orders(false, false, 0, false, 0, chatMode = true).first { it.order == HudOrder.CHAT_MODE }.label)
    }
}
