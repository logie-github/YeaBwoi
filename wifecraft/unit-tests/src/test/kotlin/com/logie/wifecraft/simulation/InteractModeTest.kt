package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.Approach
import com.logie.wifecraft.dialogue.DirtyLevel
import com.logie.wifecraft.dialogue.Disposition
import com.logie.wifecraft.story.Vec3
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Interact mode, step one: the region map, touch consent and reactions, the intents, the state machine and the camera. */
class InteractModeTest {
    private val shy = InteractPersona(Disposition(caution = 0.6f, warmth = 0.1f), affinity = 0.1f)
    private val bold = InteractPersona(Disposition(boldness = 0.6f, warmth = 0.3f), affinity = 0.6f)
    private fun detail(code: Int, h: Float, across: Float = 0f, front: Boolean = true, geoset: Int = 0, side: Int = 0) =
        floatArrayOf(code.toFloat(), side.toFloat(), h, 0f, across, 0f, if (front) 1f else 0f, geoset.toFloat())
    private fun hit(r: TouchRegion) = TouchHit(r, GarmentSlot.BARE, 0, true, 0.5f)

    // ---------------------------------------------------------------- region mapping
    @Test fun theRegionsAreNamedFromTheEnginesCodeAndTheHeightOnHer() {
        fun r(code: Int, h: Float, across: Float = 0f, front: Boolean = true) = RegionMap.classify(detail(code, h, across, front))?.region
        assertEquals(TouchRegion.FACE, r(RegionMap.C_HEAD, 0.95f))
        assertEquals(TouchRegion.HEAD, r(RegionMap.C_HEAD, 0.95f, front = false))
        assertEquals(TouchRegion.HAIR, r(RegionMap.C_HAIR, 1.0f))
        assertEquals(TouchRegion.NECK, r(RegionMap.C_NECK, 0.87f))
        assertEquals(TouchRegion.CHEST, r(RegionMap.C_CHEST, 0.75f))
        assertEquals(TouchRegion.BACK, r(RegionMap.C_CHEST, 0.75f, front = false))
        assertEquals(TouchRegion.SHOULDER, r(RegionMap.C_CHEST, 0.88f, across = 0.2f))
        assertEquals(TouchRegion.BELLY, r(RegionMap.C_WAIST, 0.6f))
        assertEquals(TouchRegion.BACK, r(RegionMap.C_WAIST, 0.6f, front = false))
        assertEquals(TouchRegion.HIP, r(RegionMap.C_HIPS, 0.5f))
        assertEquals(TouchRegion.BUTT, r(RegionMap.C_HIPS, 0.5f, front = false))
        assertEquals(TouchRegion.THIGH, r(RegionMap.C_LEGS, 0.4f))
        assertEquals(TouchRegion.BUTT, r(RegionMap.C_LEGS, 0.48f, front = false))
        assertEquals(TouchRegion.KNEE, r(RegionMap.C_LEGS, 0.28f))
        assertEquals(TouchRegion.CALF, r(RegionMap.C_LEGS, 0.15f))
        assertEquals(TouchRegion.FOOT, r(RegionMap.C_LEGS, 0.04f))
        assertEquals(TouchRegion.FOOT, r(RegionMap.C_FEET, 0.02f))
        assertEquals(TouchRegion.HAND, r(RegionMap.C_HANDS, 0.45f))
        assertEquals(TouchRegion.ARM, r(RegionMap.C_ARMS, 0.65f, across = 0.3f))
        assertEquals(TouchRegion.SHOULDER, r(RegionMap.C_ARMS, 0.86f, across = 0.1f))
    }

    @Test fun everyTouchableRegionIsReachableFromSomeHit() {
        val seen = HashSet<TouchRegion>()
        for (code in 1..10) for (h in listOf(0.02f, 0.1f, 0.15f, 0.28f, 0.4f, 0.5f, 0.6f, 0.75f, 0.88f, 0.95f)) for (front in listOf(true, false)) for (across in listOf(0f, 0.2f))
            RegionMap.regionOf(code, h, across, front)?.let { seen += it }
        assertEquals(TouchRegion.TOUCHABLE.toSet(), seen)
    }

    @Test fun aMissAndJunkAreNoTouch() {
        assertNull(RegionMap.classify(null)); assertNull(RegionMap.classify(floatArrayOf(1f)))
        assertNull(RegionMap.classify(detail(0, 0.5f)))
        assertNull(RegionMap.classify(detail(99, 0.5f)))
    }

    @Test fun theSideBitsOfTheCodeDoNotChangeTheRegionAndTheGeosetGivesTheSlot() {
        val h = RegionMap.classify(detail(RegionMap.C_LEGS or (1 shl 8), 0.4f, geoset = 902))
        assertEquals(TouchRegion.THIGH, h?.region); assertEquals(GarmentSlot.LEGS, h?.slot)
        assertEquals(GarmentSlot.BARE, RegionMap.slotOf(0)); assertEquals(GarmentSlot.HANDS, RegionMap.slotOf(401)); assertEquals(GarmentSlot.FEET, RegionMap.slotOf(502))
        assertEquals(GarmentSlot.SLEEVES, RegionMap.slotOf(801)); assertEquals(GarmentSlot.BACK, RegionMap.slotOf(1502)); assertEquals(GarmentSlot.HAIR, RegionMap.slotOf(5))
        assertEquals(GarmentSlot.HAIR, RegionMap.classify(detail(RegionMap.C_HAIR, 1f))?.slot)
    }

    // ---------------------------------------------------------------- consent gating
    @Test fun everyTouchIsAnActInACategoryAndAWontOnItDeclines() {
        assertEquals(ActCategories.FRIENDLY, TouchConsent.category(TouchRegion.HEAD))
        assertEquals(ActCategories.HANDS, TouchConsent.category(TouchRegion.THIGH))
        assertEquals(ActCategories.HANDS, TouchConsent.category(TouchRegion.BUTT))
        assertEquals(ActCategories.FOOT, TouchConsent.category(TouchRegion.FOOT))
        assertEquals(ActCategories.KISSING, TouchConsent.category(TouchRegion.FACE))
        val policy = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.HANDS, Stance.WONT)))
        for (r in listOf(TouchRegion.CHEST, TouchRegion.BUTT, TouchRegion.HIP, TouchRegion.THIGH))
            assertEquals(Verdict.DECLINE, TouchConsent.verdict(policy, "a", r, TouchKind.TAP, bold), "$r")
        assertEquals(Verdict.ALLOW, TouchConsent.verdict(policy, "a", TouchRegion.HEAD, TouchKind.TAP, bold))
        // a pull at the stockings is the same act: the same Won't
        assertEquals(Verdict.DECLINE, TouchConsent.verdict(policy, "a", TouchRegion.THIGH, TouchKind.PULL, bold))
        // another NPC is not bound by "a"'s limits
        assertNotEquals(Verdict.DECLINE, TouchConsent.verdict(policy, "b", TouchRegion.HIP, TouchKind.TAP, bold))
    }

    private fun assertNotEquals(a: Any?, b: Any?, msg: String? = null) = assertTrue(a != b, msg ?: "$a == $b")

    @Test fun aWillTheyPlayerSetAllowsAndTheDefaultLeavesItToHerWillingness() {
        val will = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.HANDS, Stance.WILL)))
        assertEquals(Verdict.ALLOW, TouchConsent.verdict(will, "a", TouchRegion.BUTT, TouchKind.TAP, shy))
        val none = ConsentPolicy()
        // no limits set: a stranger shy girl does not allow a hand on her chest; a hand on her head is fine
        assertNotEquals(Verdict.ALLOW, TouchConsent.verdict(none, "a", TouchRegion.CHEST, TouchKind.TAP, shy))
        assertEquals(Verdict.ALLOW, TouchConsent.verdict(none, "a", TouchRegion.HEAD, TouchKind.TAP, shy))
    }

    @Test fun herNatureAndHowWellSheKnowsYouMoveTheLine() {
        val tender = TouchRegion.THIGH
        val rShy = TouchConsent.resistance(tender, TouchKind.TAP, shy)
        val rBold = TouchConsent.resistance(tender, TouchKind.TAP, bold)
        assertTrue(rShy > rBold, "$rShy $rBold")
        val strangers = shy.copy(affinity = 0f); val friends = shy.copy(affinity = 0.9f)
        assertTrue(TouchConsent.resistance(tender, TouchKind.TAP, strangers) > TouchConsent.resistance(tender, TouchKind.TAP, friends))
        // the more private, the more resistance; a pull adds a little
        assertTrue(TouchConsent.sensitivity(TouchRegion.CHEST) > TouchConsent.sensitivity(TouchRegion.THIGH))
        assertTrue(TouchConsent.sensitivity(TouchRegion.THIGH) > TouchConsent.sensitivity(TouchRegion.HEAD))
        assertTrue(TouchConsent.resistance(tender, TouchKind.PULL, bold) > TouchConsent.resistance(tender, TouchKind.TAP, bold))
        for (r in TouchRegion.TOUCHABLE) assertTrue(TouchConsent.resistance(r, TouchKind.TAP, bold) in 0f..1f)
    }

    // ---------------------------------------------------------------- reactions
    @Test fun aReactionIsPickedByTheVerdictAndIsDeterministicForASeed() {
        for (r in TouchRegion.TOUCHABLE) for (v in Verdict.entries) {
            val a = InteractReactions.choose(hit(r), TouchKind.TAP, v, bold, Random(7))
            val b = InteractReactions.choose(hit(r), TouchKind.TAP, v, bold, Random(7))
            assertEquals(a, b, "$r $v")
            assertEquals(v != Verdict.ALLOW, a.declined)
            when (v) {
                Verdict.DECLINE -> { assertTrue(a.kind in InteractReactions.DECLINING); assertNotNull(a.line, "a refusal is always said") }
                Verdict.HESITATE -> { assertTrue(a.kind in InteractReactions.HESITATING); assertNotNull(a.line) }
                Verdict.ALLOW -> assertTrue(a.kind in InteractReactions.allowedKinds(r).map { it.first })
            }
        }
    }

    @Test fun herNatureLeansTheReactions() {
        fun count(p: InteractPersona, k: ReactionKind): Int { val rnd = Random(3); return (0 until 400).count { InteractReactions.choose(hit(TouchRegion.NECK), TouchKind.TAP, Verdict.ALLOW, p, rnd).kind == k } }
        assertTrue(count(bold, ReactionKind.LEAN_IN) > count(shy, ReactionKind.LEAN_IN))
        assertTrue(count(shy, ReactionKind.BLUSH) > count(bold, ReactionKind.BLUSH))
    }

    @Test fun sheDoesNotRepeatTheLastReactionWhenThereIsAnother() {
        val rnd = Random(1)
        var last = emptyList<ReactionKind>()
        repeat(60) {
            val r = InteractReactions.choose(hit(TouchRegion.HEAD), TouchKind.TAP, Verdict.ALLOW, bold, rnd, last)
            if (last.isNotEmpty()) assertNotEquals(last.last(), r.kind)
            last = last + r.kind
        }
    }

    @Test fun hapticsFollowTheTenderness() {
        val soft = InteractReactions.haptic(TouchRegion.HEAD, Verdict.ALLOW)
        val tender = InteractReactions.haptic(TouchRegion.BUTT, Verdict.ALLOW)
        assertTrue(tender.amplitudes[0] > soft.amplitudes[0])
        assertEquals(3, InteractReactions.haptic(TouchRegion.HEAD, Verdict.DECLINE).timings.size)
        assertTrue(InteractReactions.haptic(TouchRegion.THIGH, Verdict.ALLOW, TouchKind.STROKE).timings[0] < soft.timings[0] + 1)
        for (r in TouchRegion.TOUCHABLE) for (v in Verdict.entries) for (k in TouchKind.entries) {
            val h = InteractReactions.haptic(r, v, k)
            assertEquals(h.timings.size, h.amplitudes.size); assertTrue(h.amplitudes.all { it in 0..255 })
        }
    }

    @Test fun linesFollowTheStyleAndTheLevelButARefusalIsThePlainSameAtEveryLevel() {
        val rnd = Random(5)
        val declines = DirtyLevel.entries.map { lvl -> InteractLines.line(Verdict.DECLINE, ReactionKind.SWAT, hit(TouchRegion.CHEST), bold.copy(dirty = lvl), Random(5)) }
        assertEquals(1, declines.toSet().size)
        val soft = InteractLines.line(Verdict.ALLOW, ReactionKind.SMILE, hit(TouchRegion.THIGH), bold.copy(dirty = DirtyLevel.CUTE), Random(5))
        val warm = InteractLines.line(Verdict.ALLOW, ReactionKind.SMILE, hit(TouchRegion.THIGH), bold.copy(dirty = DirtyLevel.HOT), Random(5))
        assertNotEquals(soft, warm)
        assertNotNull(InteractLines.line(Verdict.HESITATE, ReactionKind.LOOK_AWAY, hit(TouchRegion.HIP), shy, rnd))
    }

    // ---------------------------------------------------------------- strokes
    @Test fun aStrokeGoesAsFarAsSheLetsItAndStopsOnceWhereSheDoesNot() {
        val policy = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.HANDS, Stance.WONT)))
        val gate = StrokeGate(policy, "a") { bold }
        val rnd = Random(2)
        assertEquals(StrokeGate.Step.Kind.GO, gate.sample(hit(TouchRegion.SHOULDER), rnd).kind)
        assertEquals(StrokeGate.Step.Kind.NEW_PART, gate.sample(hit(TouchRegion.ARM), rnd).kind)
        assertEquals(StrokeGate.Step.Kind.GO, gate.sample(hit(TouchRegion.ARM), rnd).kind)
        val stop = gate.sample(hit(TouchRegion.THIGH), rnd)
        assertEquals(StrokeGate.Step.Kind.STOP, stop.kind)
        assertTrue(stop.reaction!!.declined)
        assertEquals(TouchRegion.THIGH, gate.stoppedAt)
        // after the stop nothing more is felt until the player lifts and touches again
        assertEquals(StrokeGate.Step.Kind.NONE, gate.sample(hit(TouchRegion.ARM), rnd).kind)
        assertEquals(StrokeGate.Step.Kind.NONE, gate.sample(null, rnd).kind)
    }

    @Test fun aStrokeOffHerIsNothing() {
        val gate = StrokeGate(null, "a") { bold }
        assertEquals(StrokeGate.Step.Kind.NONE, gate.sample(null, Random(1)).kind)
        assertNull(gate.stoppedAt)
    }

    // ---------------------------------------------------------------- intents
    @Test fun keywordIntentsReadWhatThePlayerTypes() {
        fun p(s: String) = InteractIntents.parse(s)
        assertEquals(InteractAction.Pose(PoseKind.DANCE), p("dance for me"))
        assertEquals(InteractAction.Pose(PoseKind.TURN_AROUND), p("turn around"))
        assertEquals(InteractAction.Pose(PoseKind.BEND_OVER), p("Bend over, please"))
        assertEquals(InteractAction.Pose(PoseKind.SIT), p("sit down"))
        assertEquals(InteractAction.Pose(PoseKind.KNEEL), p("kneel"))
        assertEquals(InteractAction.Pose(PoseKind.LIE_DOWN), p("lie down"))
        assertEquals(InteractAction.Pose(PoseKind.STRETCH), p("stretch"))
        assertEquals(InteractAction.Pose(PoseKind.HAIR_PLAY), p("play with your hair"))
        assertEquals(InteractAction.Look(LookTarget.CAMERA), p("look at me"))
        assertEquals(InteractAction.Look(LookTarget.AWAY), p("look away"))
        assertEquals(InteractAction.Look(LookTarget.DOWN), p("look down"))
        assertEquals(InteractAction.Express(Expression.SMILE), p("smile"))
        assertEquals(InteractAction.Express(Expression.BLUSH), p("blush for me"))
        assertEquals(InteractAction.Express(Expression.TEASE), p("tease me"))
        assertEquals(InteractAction.Express(Expression.SHY), p("act shy"))
        assertEquals(InteractAction.Follow, p("follow me"))
        assertEquals(InteractAction.Stay, p("stay here"))
        assertEquals(InteractAction.ComeHere, p("come here"))
        assertEquals(InteractAction.MoveToSpot, p("walk to that spot"))
        assertEquals(OutfitAct.STOCKINGS_ON, (p("put on thigh-high stockings") as InteractAction.Outfit).act)
        assertEquals(OutfitAct.STOCKINGS_OFF, (p("take off your stockings") as InteractAction.Outfit).act)
        assertEquals(OutfitAct.GEAR_ON, (p("get dressed") as InteractAction.Outfit).act)
        assertEquals(OutfitAct.GEAR_OFF, (p("take off your gear") as InteractAction.Outfit).act)
        assertNull(p("what is the weather")); assertNull(p("")); assertNull(p("   "))
    }

    @Test fun theModelMayOnlyPickAnIdOfTheAllowedList() {
        val allowed = InteractAction.catalogue()
        assertEquals(InteractAction.Pose(PoseKind.DANCE), InteractIntents.parseChoice("""{"action":"pose:dance"}""", allowed))
        assertEquals(InteractAction.Follow, InteractIntents.parseChoice("Sure! {\"action\": \"follow\"}", allowed))
        assertNull(InteractIntents.parseChoice("""{"action":"none"}""", allowed))
        assertNull(InteractIntents.parseChoice("""{"action":"pose:fly"}""", allowed), "an invented id is nothing")
        assertNull(InteractIntents.parseChoice("""{"action":"pose:dance"}""", allowed.filter { it != InteractAction.Pose(PoseKind.DANCE) }), "a removed one too")
        assertNull(InteractIntents.parseChoice("dance please", allowed)); assertNull(InteractIntents.parseChoice(null, allowed)); assertNull(InteractIntents.parseChoice("{broken", allowed))
        assertTrue(InteractIntents.modelPrompt("dance", allowed).contains("pose:dance"))
        assertEquals(allowed.size, allowed.map { it.id }.toSet().size, "ids are unique")
        for (a in allowed) assertEquals(a, InteractAction.byId(a.id))
    }

    // ---------------------------------------------------------------- commands and the consent gate
    private fun mode(): InteractMode = InteractMode(5).also { it.enter("a", InteractPrior(false, "ROOM", -1)) }

    @Test fun anActSheWontIsNeverPerformedAndSheDeclinesInCharacter() {
        val policy = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.DANCING, Stance.WONT)))
        val m = mode()
        for (a in listOf(InteractAction.Pose(PoseKind.DANCE), InteractAction.Pose(PoseKind.BEND_OVER), InteractAction.Express(Expression.TEASE),
            InteractAction.Outfit(OutfitAct.GEAR_OFF), InteractAction.Outfit(OutfitAct.STOCKINGS_OFF))) {
            val r = m.command(a, policy, bold)
            assertTrue(r is CommandResult.Declined, "$a gave $r")
            assertFalse((r as CommandResult.Declined).line.isBlank())
        }
        assertFalse(m.persuading, "a Won't is not a hesitation")
        // the same words by "Tell her..." take the same road
        assertTrue(m.tell("dance for me", policy, bold) is CommandResult.Declined)
        // what is not in a Won't category still goes
        assertTrue(m.command(InteractAction.Pose(PoseKind.SIT), policy, bold) is CommandResult.Do)
    }

    @Test fun theModelsChoiceStillPassesTheGate() {
        val policy = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.DANCING, Stance.WONT)))
        val m = mode()
        val r = m.tell("show me something nice", policy, bold) { _, allowed -> allowed.first { it.id == "pose:dance" } }
        assertTrue(r is CommandResult.Declined)
        assertTrue(m.tell("nonsense words", null, bold) { _, _ -> null } is CommandResult.Unknown)
    }

    @Test fun aHesitationWaitsForThePersuasionAndAgreesOrGivesUp() {
        val m = mode()
        val policy = ConsentPolicy()
        val action = InteractAction.Pose(PoseKind.LIE_DOWN)
        val first = m.command(action, policy, shy)
        assertTrue(first is CommandResult.Hesitates, "$first")
        assertTrue(m.persuading); assertEquals(action, m.pending)
        var res: CommandResult? = first
        var n = 0
        while (res is CommandResult.Hesitates && n++ < 8) res = m.persuade(Approach.REASSURE, shy)
        assertTrue(res is CommandResult.Do || res is CommandResult.Declined, "$res")
        assertFalse(m.persuading)
        // Later lets it go
        m.command(action, policy, shy); m.later(); assertFalse(m.persuading)
        assertNull(m.persuade(Approach.KINDNESS, shy))
    }

    @Test fun followStayAndMoveAreStandingOrders() {
        val m = mode()
        assertTrue(m.command(InteractAction.Follow, null, bold) is CommandResult.Do); assertTrue(m.following); assertFalse(m.staying)
        assertTrue(m.command(InteractAction.Stay, null, bold) is CommandResult.Do); assertFalse(m.following); assertTrue(m.staying)
        assertTrue(m.command(InteractAction.MoveToSpot, null, bold) is CommandResult.AwaitSpot); assertTrue(m.awaitingSpot)
        assertTrue(m.spotTapped()); assertFalse(m.awaitingSpot); assertFalse(m.spotTapped())
        m.command(InteractAction.Follow, null, bold); m.command(InteractAction.MoveToSpot, null, bold); assertFalse(m.following, "a move ends the following")
    }

    // ---------------------------------------------------------------- the state machine
    @Test fun enteringAndLeavingTheMode() {
        val m = InteractMode(1)
        assertFalse(m.on); assertNull(m.leave())
        val prior = InteractPrior(true, "FOLLOW", 2)
        assertTrue(m.enter("a", prior)); assertTrue(m.on); assertEquals("a", m.actor); assertEquals(InteractTool.TOUCH, m.tool)
        assertFalse(m.enter("b", prior), "one NPC at the centre"); assertEquals("a", m.actor)
        m.openTab(InteractTab.POSE); m.setTool(InteractTool.LOOK); m.command(InteractAction.Follow, null, bold)
        assertEquals(prior, m.leave())
        assertFalse(m.on); assertNull(m.tab); assertFalse(m.following)
        assertTrue(m.enter("b", prior)); assertEquals(InteractTool.TOUCH, m.tool, "a fresh session")
    }

    @Test fun theToolTheTabsAndTheTellRowAreOneThingAtATime() {
        val m = mode()
        m.toggleTool(); assertEquals(InteractTool.LOOK, m.tool); m.toggleTool(); assertEquals(InteractTool.TOUCH, m.tool)
        m.openTab(InteractTab.COMMANDS); assertEquals(InteractTab.COMMANDS, m.tab); assertTrue(m.sheetOpen)
        m.openTab(InteractTab.OUTFIT); assertEquals(InteractTab.OUTFIT, m.tab)
        m.openTab(InteractTab.OUTFIT); assertNull(m.tab, "again closes it")
        m.openTab(InteractTab.POSE); m.toggleTell(); assertTrue(m.tell); assertNull(m.tab, "the tell row replaces the sheet")
        m.openTab(InteractTab.POSE); assertFalse(m.tell, "and the sheet the row")
    }

    @Test fun touchIsOnlyFeltWithTheTouchToolAndMovesHerTrust() {
        val m = mode()
        val policy = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.HANDS, Stance.WONT)))
        assertNotNull(m.touch(hit(TouchRegion.HEAD), TouchKind.TAP, policy, bold))
        val t0 = m.trust
        assertTrue(t0 > 0f)
        val no = m.touch(hit(TouchRegion.BUTT), TouchKind.TAP, policy, bold)!!
        assertEquals(Verdict.DECLINE, no.verdict); assertTrue(m.trust < t0); assertEquals(1, m.refused)
        m.setTool(InteractTool.LOOK)
        assertNull(m.touch(hit(TouchRegion.HEAD), TouchKind.TAP, policy, bold), "the Look tool touches nothing")
        assertNull(m.stroke(policy, bold))
        m.setTool(InteractTool.TOUCH)
        assertNotNull(m.stroke(policy, bold))
        repeat(40) { m.touch(hit(TouchRegion.HEAD), TouchKind.TAP, null, bold) }
        assertTrue(m.trust <= 0.25f)
        // trust makes her more willing at the margin
        assertTrue(m.persona(shy).affinity > shy.affinity)
    }

    @Test fun noOneBeforeEnteringIsTouchedOrTold() {
        val m = InteractMode(1)
        assertNull(m.touch(hit(TouchRegion.HEAD), TouchKind.TAP, null, bold))
        assertTrue(m.command(InteractAction.Follow, null, bold) is CommandResult.Unknown)
        assertNull(m.stroke(null, bold))
    }

    // ---------------------------------------------------------------- the camera
    private fun body() = JoiBody().also { it.set(Vec3(0f, 0f, 1.62f), Vec3(0f, 0f, 1.25f), Vec3(0f, 0f, 0.95f), 0f, 1.7f, 0f, true) }

    @Test fun theCameraCentresOnHerAndTheViewsAreAimedAlongHerBody() {
        val c = InteractCamera()
        val b = body()
        c.restart()
        assertTrue(c.update(0.016f, b, 16f / 9f))
        // the pivot: chest in the Torso view, the head in Face, between pelvis and floor in Legs
        c.goTo(InteractView.TORSO); assertEquals(1.62f - 0.22f / 0.22f * (1.62f - 1.25f), c.pivot(b).z, 0.01f)
        assertEquals(1.62f, c.pivot(b, 0f).z, 0.001f)
        assertTrue(c.pivot(b, InteractView.LEGS.aim).z < 1.25f && c.pivot(b, InteractView.LEGS.aim).z > 0f)
        // drive it to Face: close, at the head, in front of her
        c.goTo(InteractView.FACE); c.yaw = 0f
        repeat(200) { c.update(0.016f, b, 16f / 9f) }
        assertTrue(c.eyeX > 0.2f && c.eyeX < 1.2f, "in front of her at ${c.eyeX}")
        assertEquals(1.62f, c.atZ, 0.05f)
        assertTrue(c.eyeX.isFinite() && c.eyeY.isFinite() && c.eyeZ.isFinite() && c.fov in 20f..60f)
    }

    @Test fun dragOrbitsWithoutLimitAndPitchIsClampedAndPinchZooms() {
        val c = InteractCamera()
        c.restart()
        c.orbit(-400f, 0f, 800f, 400f)
        assertTrue(c.yaw > 0f)
        repeat(20) { c.orbit(-400f, 0f, 800f, 400f) }
        assertTrue(kotlin.math.abs(c.yaw) <= Math.PI.toFloat() + 1e-3f, "the yaw wraps")
        repeat(40) { c.orbit(0f, 400f, 800f, 400f) }
        assertEquals(InteractZoom.MAX_PITCH, c.pitch, 1e-4f)
        repeat(80) { c.orbit(0f, -400f, 800f, 400f) }
        assertEquals(InteractZoom.MIN_PITCH, c.pitch, 1e-4f)
        c.overview(); assertEquals(0f, c.yaw); assertEquals(InteractView.FULL, c.current())
        c.pinch(2f); assertTrue(c.distance < InteractView.FULL.distance); assertNull(c.current())
        repeat(30) { c.pinch(3f) }; assertEquals(InteractZoom.MIN_DISTANCE, c.distance, 1e-4f)
        repeat(30) { c.pinch(0.3f) }; assertEquals(InteractZoom.MAX_DISTANCE, c.distance, 1e-4f)
        c.pinch(Float.NaN); c.pinch(-1f); assertTrue(c.distance.isFinite())
    }

    @Test fun thePresetsAreOrderedFaceTorsoLegsFullAndNearestFindsThem() {
        assertTrue(InteractView.FACE.distance < InteractView.TORSO.distance && InteractView.TORSO.distance < InteractView.LEGS.distance && InteractView.LEGS.distance < InteractView.FULL.distance)
        for (v in InteractView.entries) assertEquals(v, InteractZoom.nearest(v.distance))
        val c = InteractCamera(); c.goTo(InteractView.LEGS); assertEquals(InteractView.LEGS, c.current())
    }

    @Test fun aWallInTheWayDrawsTheCameraIn() {
        val c = InteractCamera(); val b = body()
        c.visible = { eye, _ -> kotlin.math.hypot(eye.x, eye.y) < 2.2f }
        c.restart(); c.goTo(InteractView.FULL)
        repeat(400) { c.update(0.016f, b, 16f / 9f) }
        assertTrue(kotlin.math.hypot(c.eyeX, c.eyeY) < 2.3f, "drew in to ${kotlin.math.hypot(c.eyeX, c.eyeY)}")
    }

    // ---------------------------------------------------------------- the reaction layers and the pose library
    @Test fun everyReactionHasALayerOfBonesTheEngineKnows() {
        for (k in ReactionKind.entries) {
            val r = InteractReactions.choose(hit(TouchRegion.HEAD), TouchKind.TAP, Verdict.ALLOW, bold, Random(1)).copy(kind = k, turnHead = 0.4f)
            val a = InteractLayers.layer(r)!!
            assertTrue(a.tracks.isNotEmpty() && a.duration > 0.5f && !a.loop, "$k")
            assertTrue(a.tracks.all { it.bone in setOf("Head", "Spine2", "Neck1") && it.times.isNotEmpty() && it.values.all { v -> v.isFinite() } })
        }
    }

    @Test fun looksAndExpressionsAreHeldPostures() {
        for (t in LookTarget.entries) { val a = InteractLayers.look(t); assertTrue(a.tracks.isNotEmpty() && a.duration > 3f && a.tracks.all { it.values.all { v -> v.isFinite() } }, "$t") }
        for (e in Expression.entries) { val a = InteractLayers.expression(e); if (e == Expression.NEUTRAL) assertNull(a) else assertNotNull(a, "$e") }
    }

    @Test fun thePoseLibraryFindsClipsByTheirWordsAndOffersWhatItCanPlay() {
        val lib = listOf(
            JoiClip("a", "Sexy Dance 2", "Dance"), JoiClip("b", "Sitting Chair", "Idle"), JoiClip("c", "Kneel Down", "Poses"), JoiClip("d", "Lie Down Bed", "Poses"),
            JoiClip("e", "Stretch Arms", "Idle"), JoiClip("f", "Hair Flip", "Flirt"), JoiClip("g", "Bend Over Table", "Poses"),
            JoiClip("h", "Dance Partner Waltz", "Pair", kind = "PAIR"), JoiClip("i", "Sit Dance Funny", "Fun"))
        assertEquals("a", PoseLibrary.clipsFor(PoseKind.DANCE, lib).first().key)
        assertTrue(PoseLibrary.clipsFor(PoseKind.DANCE, lib).none { it.key == "h" }, "a partner stage is no solo pose")
        assertEquals("c", PoseLibrary.clipsFor(PoseKind.KNEEL, lib).single().key)
        assertEquals("d", PoseLibrary.clipsFor(PoseKind.LIE_DOWN, lib).single().key)
        assertEquals("f", PoseLibrary.clipsFor(PoseKind.HAIR_PLAY, lib).single().key)
        assertEquals("g", PoseLibrary.clipsFor(PoseKind.BEND_OVER, lib).single().key)
        val offered = PoseLibrary.available(lib).map { it.first }
        assertTrue(PoseKind.TURN_AROUND in offered, "a turn needs no clip")
        assertTrue(PoseKind.DANCE in offered)
        assertTrue(PoseKind.KNEEL in PoseLibrary.available(lib).map { it.first })
        assertFalse(PoseKind.KNEEL in PoseLibrary.available(emptyList()).map { it.first })
        assertNotNull(PoseLibrary.turnOf(PoseKind.TURN_AROUND)); assertNull(PoseLibrary.turnOf(PoseKind.DANCE))
    }
}
