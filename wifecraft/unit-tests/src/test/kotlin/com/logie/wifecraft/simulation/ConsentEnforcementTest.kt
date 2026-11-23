package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.Hints
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.RequirementClassifier
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.dialogue.ConsentGate
import com.logie.wifecraft.dialogue.DialogueHook
import com.logie.wifecraft.story.Vec3
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The limits where an act can start: NPCs' own picks, the player's orders and invitations, Switch positions, auto-play, a running act. */
class ConsentEnforcementTest {
    private val step = 1f / 120f
    private val zero = Vec3(0f, 0f, 0f)
    private fun anim(id: String) = Anim(id, id, "Test", 2f, false, emptyList(), "test:$id", 0L)
    private fun pair(id: String, name: String, kind: String = "") = PartnerPair(id, name, anim("$id-a"), anim("$id-b"),
        PartnerSlot(-0.4f, 0f, 0f, 0f), PartnerSlot(0.4f, 0f, 0f, 180f), false, kind = kind)
    private val noEmotes = ModelEmotes { _, _ -> false }

    private fun sim(policy: ConsentPolicy?, vararg pairs: PartnerPair, emotes: ModelEmotes = noEmotes, hook: DialogueHook? = null) =
        SceneSimulation(listOf("a", "b", "c"), emptySet(), Random(7), pairs = pairs.toList(), emotes = emotes).also { it.consent = policy; it.dialogue = hook }

    private fun oralWont(who: String = "b") = ConsentPolicy(mapOf(who to NpcLimits().withCategory(ActCategories.ORAL, Stance.WONT)))

    @Test fun anActOneWontDoIsNeverPickedForThemWhateverTheRole() {
        val blowjob = pair("bj", "Standing Blowjob")
        for (who in listOf("a", "b")) repeat(30) { seed ->
            val s = SceneSimulation(listOf("a", "b"), emptySet(), Random(seed.toLong()), pairs = listOf(blowjob), emotes = noEmotes).also { it.consent = oralWont(who) }
            assertFalse(s.partner("a", "b"), "nothing else to play: nothing starts ($who, seed $seed)")
            assertNull(s.partnerOf("a")); assertNull(s.partnerOf("b"))
        }
        // without the limit it does start
        assertTrue(sim(null, blowjob).partner("a", "c"))
    }

    @Test fun otherActsStillPlayAndEmotesFillInWhereNothingElseIsAllowed() {
        val kiss = pair("k", "Slow Kiss")
        val s = sim(oralWont(), pair("bj", "Standing Blowjob"), kiss)
        repeat(20) { assertTrue(s.partner("a", "b")); assertEquals("Slow Kiss", s.partnerOf("a")?.name); s.freeWill("a"); s.freeWill("b") }
        val e = sim(oralWont(), pair("bj", "Standing Blowjob"), emotes = ModelEmotes.STANDARD)
        assertTrue(e.partner("a", "b")); assertTrue(e.partnerOf("a")!!.emote >= 0, "an emote, not the act")
    }

    @Test fun thePlayersOrderForAnActIsRefusedInCharacterAndNothingStarts() {
        val s = sim(oralWont(), pair("bj", "Standing Blowjob"))
        val roles = listOf(PlaybackRole(PlaybackKeys.pair("bj", 0), zero, 0f), PlaybackRole(PlaybackKeys.pair("bj", 1), zero, 0f))
        assertFalse(s.perform("a", PerformanceOrder("b", roles, 2f, pairId = "bj")))
        assertEquals("They declines that.", s.takeRefusal())
        val pose = Pose(); s.pose(1, pose)
        assertTrue(pose.speech?.contains("declines") == true, "the neutral bubble: ${pose.speech}")
        repeat(40) { s.tick(0.1f) }
        assertNull(s.partnerOf("a")); s.pose(0, pose); assertNull(pose.playback)
        // an unlimited NPC can still do it with the third
        assertTrue(s.perform("a", PerformanceOrder("c", roles, 2f, pairId = "bj")))
    }

    @Test fun aRoleLimitRefusesOnlyThatRoleAndAQueuedStageIsHeldToIt() {
        val limits = ConsentPolicy(mapOf("b" to NpcLimits().withRole("bj", 1, Stance.WONT)))
        val s = sim(limits, pair("bj", "Standing Blowjob"))
        fun roles(k: Int) = listOf(PlaybackRole(PlaybackKeys.pair("bj", k), zero, 0f), PlaybackRole(PlaybackKeys.pair("bj", 1 - k), zero, 0f))
        assertFalse(s.perform("a", PerformanceOrder("b", roles(0), 2f, pairId = "bj")), "a role 0, b role 1: refused")
        assertTrue(s.perform("b", PerformanceOrder("a", roles(0), 2f, pairId = "bj")), "b role 0, a role 1: allowed")
        val q = sim(limits, pair("bj", "Standing Blowjob"))
        assertTrue(q.enqueue("a", listOf(PerformanceOrder("b", roles(0), 2f, pairId = "bj"))))
        repeat(30) { q.tick(0.1f) }
        val pose = Pose(); q.pose(0, pose); assertNull(pose.playback, "the queued stage never starts")
    }

    @Test fun anActAWontInAnyCategoryOfItsKeepsItOutAndNoBuildModePlayAnywayNeedsAnyOverride() {
        val rough = pair("rd", "Rough Doggy")
        val p = ConsentPolicy(mapOf("b" to NpcLimits().withCategory(ActCategories.VAGINAL, Stance.WONT)))
        val s = sim(p, rough)
        assertFalse(s.partner("a", "b"))
        val roles = listOf(PlaybackRole(PlaybackKeys.pair("rd", 0), zero, 0f), PlaybackRole(PlaybackKeys.pair("rd", 1), zero, 0f))
        assertFalse(s.perform("c", PerformanceOrder("b", roles, 2f, pairId = "rd")))
        assertFalse(s.perform("b", PerformanceOrder("c", roles, 2f, pairId = "rd")), "in either role")
    }

    @Test fun willStartsWithoutTheQuestionAskGoesThroughTheFlowAndWontIsNeverAsked() {
        class Hook(var answer: ConsentGate) : DialogueHook {
            override val active = true; override val cinematic = false
            val asked = ArrayList<String>(); val declined = ArrayList<String>()
            override fun takesOverChat(a: String, b: String) = true
            override fun consent(initiator: String, target: String, directed: Boolean, stageName: String): ConsentGate { asked += stageName; return answer }
            override fun declined(npcId: String, otherId: String, actName: String): Boolean { declined += npcId; return true }
        }
        val bj = pair("bj", "Standing Blowjob")
        val will = Hook(ConsentGate.REFUSED); val sw = sim(ConsentPolicy(mapOf("b" to NpcLimits().withCategory(ActCategories.ORAL, Stance.WILL))), bj, hook = will)
        assertTrue(sw.partner("a", "b")); assertEquals("APPROACH", sw.partnerOf("a")?.stage, "a refusing flow is never reached"); assertTrue(will.asked.isEmpty())
        val ask = Hook(ConsentGate.REFUSED); val sa = sim(ConsentPolicy(mapOf("b" to NpcLimits().withCategory(ActCategories.ORAL, Stance.ASK))), bj, hook = ask)
        assertTrue(sa.partner("a", "b")); assertEquals(1, ask.asked.size); assertNull(sa.partnerOf("a"), "refused for now by the flow")
        val wont = Hook(ConsentGate.PROCEED); val sn = sim(oralWont(), bj, hook = wont)
        assertFalse(sn.partner("a", "b")); assertTrue(wont.asked.isEmpty(), "never asked")
        // directed through the player's Send to partner: an ordered act takes the dialogue layer's own refusal line
        val roles = listOf(PlaybackRole(PlaybackKeys.pair("bj", 0), zero, 0f), PlaybackRole(PlaybackKeys.pair("bj", 1), zero, 0f))
        assertFalse(sn.perform("a", PerformanceOrder("b", roles, 2f, pairId = "bj"))); assertEquals(listOf("b"), wont.declined.distinct())
        val pose = Pose(); sn.pose(1, pose); assertNull(pose.speech, "the layer speaks, not the neutral bubble")
    }

    @Test fun anActThatBecomesAWontWhilePlayingEndsGracefully() {
        val limits = ConsentPolicy()
        val s = sim(limits, pair("bj", "Standing Blowjob"))
        val roles = listOf(PlaybackRole(PlaybackKeys.pair("bj", 0), zero, 0f), PlaybackRole(PlaybackKeys.pair("bj", 1), zero, 0f))
        assertTrue(s.perform("a", PerformanceOrder("b", roles, 0.5f, repeat = true, pairId = "bj")))
        repeat(20) { s.tick(0.1f) }
        val pose = Pose(); s.pose(0, pose); assertNotNull(pose.playback, "playing")
        limits.set("b", NpcLimits().withCategory(ActCategories.ORAL, Stance.WONT))   // live change
        s.tick(0.1f)
        s.pose(1, pose); assertTrue(pose.speech?.contains("declines") == true, "the one who stopped says so")
        repeat(30) { s.tick(0.1f) }
        s.pose(0, pose); assertNull(pose.playback, "ended")
        assertTrue(s.takeQueueNotice()?.contains("declines") == true)
        // an act in a category the change does not touch plays on
        val limits2 = ConsentPolicy(); val s2 = sim(limits2, pair("bj", "Standing Blowjob"))
        assertTrue(s2.perform("a", PerformanceOrder("b", roles, 0.5f, repeat = true, pairId = "bj")))
        repeat(20) { s2.tick(0.1f) }
        limits2.set("b", NpcLimits().withCategory(ActCategories.ANAL, Stance.WONT)); repeat(30) { s2.tick(0.1f) }
        s2.pose(0, pose); assertNotNull(pose.playback)
    }

    @Test fun switchPositionsIsRefusedWhenTheNewRoleIsAWont() {
        val limits = ConsentPolicy()
        val s = sim(limits, pair("bj", "Standing Blowjob"))
        val roles = listOf(PlaybackRole(PlaybackKeys.pair("bj", 0), zero, 0f), PlaybackRole(PlaybackKeys.pair("bj", 1), zero, 0f))
        assertTrue(s.perform("a", PerformanceOrder("b", roles, 0.5f, repeat = true, pairId = "bj")))
        repeat(20) { s.tick(0.1f) }
        limits.set("a", NpcLimits().withRole("bj", 1, Stance.WONT))   // a, now in role 0, would take role 1 ... wait: it is b who would take role 0
        limits.set("b", NpcLimits().withRole("bj", 0, Stance.WONT))   // b won't take role 0
        // (the limits changed while it plays: b is in role 1, allowed; so it plays on until the switch)
        repeat(5) { s.tick(0.1f) }
        assertFalse(s.switchRoles("a"), "b taking role 0 is a Won't"); assertTrue(s.takeRefusal() != null)
        limits.set("b", NpcLimits.EMPTY); limits.set("a", NpcLimits.EMPTY)
        assertTrue(s.switchRoles("a"))
    }

    @Test fun autoplayNeverChoosesAClipEitherWontDoAndExcludesRoles() {
        val F = PoseFixtures
        fun req(vararg a: Anim) = RequirementClassifier.classify(a.map { BodyGeometry.frames(it) }, Hints())
        val floor = AutoPlace("floor", null, Furniture("FLOOR", FurnitureSpec.standard(FurnitureKind.FLOOR), -1f, -1f, 0f, 0f))
        val clips = listOf(AutoClip("bj", "Standing Blowjob", req(F.standing, F.standing)), AutoClip("k", "Slow Kiss", req(F.standing, F.standing)),
            AutoClip("ex", "Exotic", req(F.standing, F.standing)))
        val acts = mapOf("bj" to ConsentAct("bj", "Standing Blowjob"), "k" to ConsentAct("k", "Slow Kiss"), "ex" to ConsentAct("ex", "Exotic", "FemDomania"))
        val limits = ConsentPolicy(mapOf("b" to NpcLimits().withCategory(ActCategories.ORAL, Stance.WONT), "a" to NpcLimits().withRole("ex", 0, Stance.WONT).withRole("ex", 1, Stance.WONT)))
        val ok: (AutoClip) -> Boolean = { limits.allowsPair("a", "b", acts.getValue(it.id)) }
        assertEquals(listOf("k"), AutoplayPlanner.options(listOf(floor), clips, allowed = ok).map { it.clip.id }.distinct(), "refused in both roles, or by the other: left out")
        // role-limited: a refuses role 0 only; b can take role 0, so the clip is still played, turned over
        limits.set("a", NpcLimits().withRole("ex", 0, Stance.WONT)); limits.set("b", NpcLimits())
        assertEquals(setOf("bj", "k", "ex"), AutoplayPlanner.options(listOf(floor), clips, allowed = ok).map { it.clip.id }.toSet())
        // live: the planner asks at every choice
        val auto = CinematicAutoplay(AutoplayPlanner(3)); auto.options = AutoplayPlanner.options(listOf(floor), clips); auto.allowed = ok
        limits.set("a", NpcLimits().withCategory(ActCategories.ORAL, Stance.WONT)); limits.set("b", NpcLimits().withAct("ex", Stance.WONT))
        repeat(40) {
            auto.stop(); auto.start()
            var picked: String? = null
            for (i in 0 until 60) { for (st in auto.tick(1f / 30f)) if (st is AutoStep.Swap && picked == null) picked = st.beat.clip?.id; if (picked != null) break }
            assertEquals("k", picked, "only the kiss")
        }
    }
}
