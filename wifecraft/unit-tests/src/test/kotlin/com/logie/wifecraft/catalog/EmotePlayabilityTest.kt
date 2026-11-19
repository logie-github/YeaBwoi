package com.logie.wifecraft.catalog

import com.logie.wifecraft.creator.Sex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmotePlayabilityTest {
    private val small = """
        {"text":[[60,"Laugh",70,0],[85,"Sigh",-1,0]],
         "npc":[],
         "anims":{"0":"Stand","69":"EmoteDance","70":"EmoteLaugh","91":"Mount","808":"FacePose","15":"HandsClosed",
                  "42":"Swim","135":"Fly","1066":"FlyingKick","97":"SitGround","1":"Death","114":"KneelStart"},
         "models":{"1:1":{"m":2,"f":1000764,"a":[0,1,69,1,70,1,91,1,808,1,15,1,42,1,135,1,1066,1,97,1,1,1,114,1],"v":{"85":[13]}}}}
    """.trimIndent()

    @Test fun problemsAreSaidPlainly() {
        assertNotNull(EmotePlayability.problem(91, "Mount"))
        assertNotNull(EmotePlayability.problem(5000, "MountSelfIdle"))
        assertNotNull(EmotePlayability.problem(5001, "ReclinedMountPassenger"))
        assertNotNull(EmotePlayability.problem(135, "Fly"))
        assertNotNull(EmotePlayability.problem(42, "Swim"))
        assertNotNull(EmotePlayability.problem(808, "FacePose"))
        assertNotNull(EmotePlayability.problem(15, null))               // a layer by id, whatever its name
        assertNotNull(EmotePlayability.problem(-1, null))
        assertNull(EmotePlayability.problem(1066, "FlyingKick"))         // a monk's kick on the ground, not flight
        assertNull(EmotePlayability.problem(69, "EmoteDance"))
        assertNull(EmotePlayability.problem(9999, null))                 // unnamed: kept
    }

    @Test fun thePickerListsOnlyWhatPlaysAlone() {
        val c = EmoteCatalog.parse(small)
        val all = c.forModel(1, Sex.FEMALE)
        val ok = c.playableForModel(1, Sex.FEMALE)
        val anims = ok.map { it.anim }.toSet()
        assertTrue(91 !in anims && 42 !in anims && 135 !in anims && 15 !in anims && 808 !in anims, "$anims")
        assertTrue(69 in anims && 70 in anims && 1066 in anims && 0 in anims)
        assertTrue(ok.any { it.id == 85 && it.anim == -1 }, "voice-only kept by default")
        assertFalse(c.playableForModel(1, Sex.FEMALE, includeVoiceOnly = false).any { it.anim < 0 })
        assertEquals(all.filter { it in ok }, ok, "the order is kept")
        assertEquals(emptyList(), c.playableForModel(99, Sex.MALE))
    }

    @Test fun loopsAndStatesAreHeldByDefault() {
        val e = EmoteCatalog.parse(small).forModel(1, Sex.FEMALE).associateBy { it.anim }
        assertTrue(e.getValue(0).loop, "Stand")
        assertTrue(e.getValue(69).loop, "a dance played once froze on its last frame")
        assertTrue(e.getValue(97).loop, "SitGround")
        assertFalse(e.getValue(1).loop, "Death holds its last frame")
        assertFalse(e.getValue(114).loop, "KneelStart")
        assertFalse(e.getValue(70).loop, "the text emote's own (one-shot) is kept")
        for (n in listOf("ReadyUnarmed", "HoldBow", "WAGuardStand01", "EmoteReadLoop", "ChannelCastOmni", "StealthWalk", "Monk2HLIdle"))
            assertTrue(EmotePlayability.heldByName(n), n)
        for (n in listOf("Attack1H", "EmoteWave", "SitGroundDown", "SleepUp", "WADead01", "UseStandingStart", "Knockdown", "SpellCastOmni"))
            assertFalse(EmotePlayability.heldByName(n), n)
        assertEquals("Combat", EmoteCatalog.categoryOf("FlyingKick"))
        assertEquals("Flying", EmoteCatalog.categoryOf("FlyStand"))
    }

    /** The app's emotes.json: every creator model's list loses its mounts, flight, swimming and layers, keeps its dances. */
    @Test fun theRealCatalog() {
        val dir = System.getProperty("wifecraft.assets") ?: return
        val c = EmoteCatalog.parse(File(dir, "emotes.json").readText())
        assertTrue(c.keys.size >= 40)
        for (k in c.keys) {
            val (race, sex) = k.split(':').map { it.toInt() }
            val s = if (sex == 0) Sex.MALE else Sex.FEMALE
            val ok = c.playableForModel(race, s)
            val names = ok.mapNotNull { if (it.anim >= 0) c.animName(it.anim) else null }
            assertTrue(names.none { "Mount" in it || it.startsWith("Swim") || it == "Fly" || it == "FacePose" || it == "HandsClosed" }, "$k: $names")
            assertTrue(ok.any { it.anim == 69 }, "$k keeps its dance")
            assertTrue(ok.size >= c.forModel(race, s).size - 80, "$k: too much removed")
        }
    }
}
