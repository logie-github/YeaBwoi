package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.ClipType
import com.logie.wifecraft.sims.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretModelTest {
    private fun clip(name: String, type: ClipType = ClipType.LOOP, participants: Int = 1, group: String = "", seconds: Float = 5f, id: String = name) =
        SecretClip(id, name, group, participants, type, seconds)

    // ---- the kneeling pose's choice

    @Test fun anImportedKneelingClipComesFirst() {
        val c = SecretKneel.choose(listOf(clip("Dance"), clip("Kneel idle", ClipType.POSE), clip("Sit")), emoteHolds = true, modelHasEmote = true)
        assertEquals(KneelSource.IMPORTED, c.source)
        assertEquals("Kneel idle", c.clip?.name)
    }

    @Test fun theBestKneelingClipWins() {
        val c = SecretKneel.choose(listOf(clip("Kneeling motion", ClipType.ONE_SHOT), clip("Kneeling idle loop", ClipType.LOOP), clip("Kneel pose", ClipType.POSE)))
        assertEquals("Kneel pose", c.clip?.name)   // a pose with no idle word loses to... the pose type bonus and the idle word
    }

    @Test fun twoPersonClipsStandUpsAndAllFoursAreNotKneelingPoses() {
        for (n in listOf("Kneel get up", "Kneel to stand", "Kneel all fours", "Kneeling pair: oral", "Dance")) {
            assertEquals(0, SecretKneel.score(clip(n)), n)
        }
        assertEquals(0, SecretKneel.score(clip("Kneel idle", participants = 2)))
        assertEquals(0, SecretKneel.score(clip("Kneel idle", ClipType.INVALID)))
        assertTrue(SecretKneel.score(clip("Kneel")) > 0)
        assertEquals(0, SecretKneel.score(clip("Kneeler")))   // a word, not a part of one
    }

    @Test fun theGameEmoteOnlyWhereItIsKnownToHoldAndTheModelHasIt() {
        assertEquals(KneelSource.GAME_EMOTE, SecretKneel.choose(emptyList(), emoteHolds = true, modelHasEmote = true).source)
        assertEquals(KneelSource.PROCEDURAL, SecretKneel.choose(emptyList(), emoteHolds = true, modelHasEmote = false).source)
        assertEquals(KneelSource.PROCEDURAL, SecretKneel.choose(emptyList(), emoteHolds = false, modelHasEmote = true).source)
        assertEquals(KneelSource.PROCEDURAL, SecretKneel.choose(listOf(clip("Dance"))).source)
    }

    @Test fun theChoiceIsStable() {
        val lib = listOf(clip("Kneel a", id = "b"), clip("Kneel a", id = "a"))
        assertEquals(SecretKneel.choose(lib).clip?.id, SecretKneel.choose(lib.reversed()).clip?.id)
    }

    // ---- holding a clip

    private fun shot(): Anim {
        val rot = BoneTrack("L_Calf", Channel.ROTATION, floatArrayOf(0f, 1f, 2f), floatArrayOf(0f, 0f, 0f, 1f, 0.3f, 0f, 0f, 0.95f, 0.7f, 0f, 0f, 0.71f))
        return Anim("a", "A", "", 2f, false, listOf(rot), "s", 0L)
    }

    @Test fun aOneShotIsHeldAtItsLastMoment() {
        val held = SecretKneel.held(shot(), ClipType.ONE_SHOT)
        assertEquals(0f, held.duration)
        assertEquals(false, held.loop)
        val t = held.tracks.single()
        assertEquals(1, t.times.size)
        assertEquals(listOf(0.7f, 0f, 0f, 0.71f), t.values.toList())
    }

    @Test fun aLoopKeepsLoopingAndAPoseStays() {
        assertTrue(SecretKneel.held(shot(), ClipType.LOOP).loop)
        val p = SecretKneel.held(shot().copy(duration = 3f, loop = true), ClipType.POSE)
        assertEquals(0f, p.duration); assertEquals(false, p.loop)
    }

    @Test fun lastFrameDropsBrokenTracks() {
        val bad = BoneTrack("Head", Channel.ROTATION, floatArrayOf(0f, 1f), floatArrayOf(0f, 0f, 0f, 1f))
        assertTrue(SecretKneel.lastFrame(shot().copy(tracks = listOf(bad))).tracks.isEmpty())
    }

    // ---- the procedural kneel, read by the app's own forward kinematics

    @Test fun theProceduralKneelHasTheKneesOnTheFloorAndHerBodyUpright() {
        val anim = SecretKneel.procedural()
        val f = BodyGeometry.frames(anim).single()
        fun p(j: String) = assertNotNull(f[j], j)
        // the hips are over the knees: the thighs hang straight down
        assertTrue(kotlin.math.abs(p("L_Calf")[0] - p("L_Thigh")[0]) < 0.05, "the knee is under the hip")
        assertTrue(p("L_Thigh")[2] - p("L_Calf")[2] > 0.3, "the thigh hangs down")
        // the shins lie along the floor (the rest shin leans back 9 degrees, so 81 turns it level): the feet are level with the knees and behind them
        assertTrue(kotlin.math.abs(p("L_Foot")[2] - p("L_Calf")[2]) < 0.06, "the shin is flat")
        assertTrue(p("L_Foot")[0] < p("L_Calf")[0] - 0.3, "the feet are behind the knees")
        // upright: the head is far above the hips, in line
        assertTrue(p("Head")[2] - p("Pelvis")[2] > 0.35)   // (body units: a standing pelvis is 1)
        assertTrue(kotlin.math.abs(p("Head")[0] - p("Pelvis")[0]) < 0.2)
        // the hands rest at the thighs: below the shoulders, near the thighs' height and in front of the hip's line
        val hand = p("L_Hand")
        assertTrue(hand[2] < p("Chest")[2] - 0.15, "the hand hangs low")
        assertTrue(hand[2] > p("L_Calf")[2] + 0.2 && kotlin.math.abs(hand[2] - p("L_Thigh")[2]) < 0.15, "the hand is at the top of the thigh")
        assertTrue(hand[1] > p("L_Thigh")[1] - 0.05 && hand[1] < p("L_Thigh")[1] + 0.25, "the hand is beside the thigh, not across her: ${hand[1]}")
        assertTrue(hand[0] > p("L_Thigh")[0] - 0.05, "the hand is not behind the hip line: ${hand[0]}")
        // the right side mirrors the left
        assertTrue(kotlin.math.abs(p("R_Calf")[2] - p("L_Calf")[2]) < 1e-6)
        assertTrue(kotlin.math.abs(p("R_Hand")[1] + p("L_Hand")[1]) < 0.02)
    }

    @Test fun theFloorDropPutsTheLowestFleshOnTheFloor() {
        val d = SecretKneel.floorDrop(SecretKneel.procedural())
        // standing the pelvis is 1 unit up; kneeling the hips are about half that over the floor: a drop of 0.3 to 0.6
        assertTrue(d in 0.3f..0.6f, "drop $d")
        assertEquals(0f, SecretKneel.floorDrop(Anim("e", "E", "", 0f, false, emptyList(), "", 0L)))
    }

    // ---- who is shown

    @Test fun theRememberedCharacterIsShownWhileSheStaysInTheCast() {
        val roster = listOf(SecretCast.Entry("starter:F", true, true), SecretCast.Entry("a", false, true), SecretCast.Entry("b", false, false))
        assertEquals("b", SecretCast.pick(roster, "b"))
        assertEquals("a", SecretCast.pick(roster, "gone"))      // the user's own first NPC
        assertEquals("a", SecretCast.pick(roster, null))
        assertEquals("starter:F", SecretCast.pick(roster.take(1) + SecretCast.Entry("starter:M", true, false), null))
        assertEquals("starter:M", SecretCast.pick(listOf(SecretCast.Entry("starter:M", true, false)), null))
        assertNull(SecretCast.pick(emptyList(), "x"))
    }

    // ---- settings and the void

    @Test fun settingsKeepAndReadBack() {
        val store = MemorySecretStore()
        assertEquals(SecretSettings(), SecretSettings.parse(store.read()))
        val s = SecretSettings(floorShadow = false, breathing = false, depthOfField = false, light = SecretLight.RIM, character = "npc-9")
        store.write(s.toJson())
        assertEquals(s, SecretSettings.parse(store.read()))
        assertEquals(SecretSettings(), SecretSettings.parse("not json {"))
        assertEquals(SecretLight.STUDIO, SecretSettings.parse("{\"light\":\"nope\"}").light)
    }

    @Test fun theDefaultsAreFloorShadowBreathingAndFocusOn() {
        val s = SecretSettings()
        assertTrue(s.floorShadow && s.breathing && s.depthOfField)
        assertEquals(SecretLight.STUDIO, s.light)
        assertNull(s.character)
    }

    @Test fun everyLightIsTheEnginesArrayWithADarkNeutralVoid() {
        for (l in SecretLight.entries) {
            val v = l.values()
            assertEquals(23, v.size)
            assertTrue(v.all { it.isFinite() })
            // the sky, the horizon and what is below are dark and grey (no colour cast)
            for (i in 0 until 9) assertTrue(v[i] < 0.2f, "${l.name} $i")
            for (k in 0 until 3) assertTrue(kotlin.math.abs(v[k * 3] - v[k * 3 + 2]) < 0.03f)
            // the sun points down, from the front or behind, never up
            assertTrue(v[11] < 0f)
            assertTrue(v[22] > v[21])
        }
        assertEquals(SecretLight.RIM, SecretLight.STUDIO.next().next())
        assertEquals(SecretLight.STUDIO, SecretLight.RIM.next())
    }

    @Test fun theVoidHasNothingButHer() {
        assertEquals("", SecretVoid.LOCATION)
        assertEquals(0, SecretVoid.OTHERS)
        assertTrue(SecretVoid.SOUND_SILENT)
        assertEquals(InteractView.FULL, SecretVoid.START_VIEW)
        val off = SecretVoid.focusBand(false, 2.3f, 1.7f)
        assertTrue(off.second > 1000f && off.third == 0f)   // sharp everywhere
        val (near, far, strength) = SecretVoid.focusBand(true, 2.3f, 1.7f)
        assertTrue(near > 0f && far > near && strength > 0f)
        assertEquals(0f, SecretVoid.breathing(false))
        assertTrue(SecretVoid.breathing(true) in 0.1f..1f)
        assertTrue(SecretVoid.distanceRange().start >= 0.2f && SecretVoid.distanceRange().endInclusive <= 5f)
    }

    // ---- the mode is hidden

    @Test fun noMenuTipOrTitleTextNamesTheHiddenMode() {
        val dir = java.io.File(System.getProperty("wifecraft.ui") ?: return)
        for (name in listOf("TitleScreen.kt", "MenuModel.kt", "DownloadOptions.kt", "SceneHud.kt")) {
            val f = java.io.File(dir, name)
            if (!f.exists()) continue
            val literals = Regex("\"[^\"\\n]*\"").findAll(f.readText()).map { it.value.lowercase() }
            assertTrue(literals.none { "secret" in it || "hidden mode" in it || "kneel" in it }, "$name names the hidden mode")
        }
    }

    @Test fun theKeyIsOneConstantThatCanBeChanged() {
        val other = SecretSpec(sequence = listOf(SwipeDirection.LEFT, SwipeDirection.RIGHT), taps = 2)
        val g = SecretGesture(other)
        fun sw(d: SwipeDirection, t: Long) = SecretTouch(t, t + 200, if (d == SwipeDirection.LEFT) -100f else 100f, 0f, 100f)
        g.feed(sw(SwipeDirection.LEFT, 0)); g.feed(sw(SwipeDirection.RIGHT, 400))
        g.feed(SecretTouch(800, 880, 0f, 0f, 1f))
        assertEquals(SecretOutcome.OPEN, g.feed(SecretTouch(1100, 1180, 0f, 0f, 1f)))
    }
}
