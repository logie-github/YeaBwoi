package com.logie.wifecraft.catalog

import com.logie.wifecraft.creator.Sex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmoteCatalogTest {
    private val small = """
        {"build":"x",
         "text":[[60,"Laugh",70,0],[86,"Sit",97,1],[1,"Agree",-1,0],[85,"Sigh",-1,0],[132,"Sniff",506,0]],
         "npc":[[10094,"Dance",69,0],[10173,"Work",136,2],[10392,"Laugh",70,2],[10500,"Laugh again",70,0]],
         "anims":{"0":"Stand","4":"Walk","69":"EmoteDance","70":"EmoteLaugh","97":"SitGround","136":"EmoteWorkNoSheathe","229":"FlyStand","17":"Attack1H"},
         "models":{"1:1":{"m":2,"f":1000764,"a":[0,3,4,1,17,1,69,2,70,1,97,1,136,1,229,1],"v":{"60":[11,12],"85":[13]}}}}
    """.trimIndent()

    @Test fun textEmotesNeedAnAnimationOrAVoice() {
        val c = EmoteCatalog.parse(small)
        val e = c.forModel(1, Sex.FEMALE)
        val text = e.filter { it.kind == EmoteKind.TEXT }.map { it.name }
        assertEquals(listOf("Laugh", "Sigh", "Sit"), text)              // Agree: nothing to show; Sniff: no anim, no voice
        val laugh = e.first { it.id == 60 }
        assertEquals(70, laugh.anim); assertEquals(listOf(11, 12), laugh.soundFiles); assertTrue(!laugh.loop)
        val sigh = e.first { it.id == 85 }
        assertEquals(-1, sigh.anim); assertEquals(listOf(13), sigh.soundFiles)
        assertTrue(e.first { it.id == 86 }.loop)
    }

    @Test fun npcEmotesAndAnimationsFillTheRest() {
        val e = EmoteCatalog.parse(small).forModel(1, Sex.FEMALE)
        val npc = e.filter { it.kind == EmoteKind.NPC }
        // the one-shot laugh is the text emote's; the looping laugh is new
        assertEquals(setOf(10094, 10173, 10392), npc.map { it.id }.toSet())
        assertEquals(2, npc.first { it.id == 10094 }.variations)
        val anims = e.filter { it.kind == EmoteKind.ANIM }
        assertEquals(setOf(0, 4, 17, 229), anims.map { it.anim }.toSet())
        assertEquals(3, anims.first { it.anim == 0 }.variations)
        assertEquals(EmoteCatalog.ANIM_BASE + 17, anims.first { it.anim == 17 }.id)
        assertEquals("Flying", anims.first { it.anim == 229 }.category)
        assertEquals("Combat", anims.first { it.anim == 17 }.category)
        assertEquals(e.size, EmoteCatalog.parse(small).count(1, Sex.FEMALE))
        assertEquals(emptyList(), EmoteCatalog.parse(small).forModel(1, Sex.MALE))
    }

    @Test fun aFaceLayerIsNotOfferedAlone() {
        // FacePose keys the face only: alone it left the body in the bind pose, arms out
        val c = EmoteCatalog.parse(small.replace("\"229\":\"FlyStand\"", "\"229\":\"FlyStand\",\"808\":\"FacePose\"").replace("229,1]", "229,1,808,1]"))
        val anims = c.forModel(1, Sex.FEMALE).map { it.anim }
        assertTrue(229 in anims)
        assertTrue(808 !in anims)
    }

    @Test fun categories() {
        assertEquals("Dance", EmoteCatalog.categoryOf("EmoteDance"))
        assertEquals("Sit, sleep and kneel", EmoteCatalog.categoryOf("SitChairMed"))
        assertEquals("Sit, sleep and kneel", EmoteCatalog.categoryOf("WALean01"))
        assertEquals("Work and life", EmoteCatalog.categoryOf("WASmithLoop"))
        assertEquals("Work and life", EmoteCatalog.categoryOf("FishingLoop"))
        assertEquals("Emotes", EmoteCatalog.categoryOf("EmoteWave"))
        assertEquals("Combat", EmoteCatalog.categoryOf("ReadyUnarmed"))
        assertEquals("Spells", EmoteCatalog.categoryOf("SpellCastDirected"))
        assertEquals("Movement", EmoteCatalog.categoryOf("Walk"))
        assertEquals("Death and injury", EmoteCatalog.categoryOf("Death"))
    }

    /** The app's own emotes.json (tools/emotes.py): every creator model, each with the game's emotes and voices. */
    @Test fun theAppsFile() {
        val f = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets", "emotes.json")
        val c = EmoteCatalog.parse(f.readText())
        assertEquals(52, c.keys.size)
        for (k in c.keys) {
            val (race, sex) = k.split(":").map { it.toInt() }
            val s = if (sex == 0) Sex.MALE else Sex.FEMALE
            val e = c.forModel(race, s)
            val text = e.filter { it.kind == EmoteKind.TEXT }
            assertTrue(text.size >= 100, "$k: ${text.size} text emotes")
            assertTrue(text.count { it.soundFiles.isNotEmpty() } >= 40, "$k: voices")
            for (name in listOf("Laugh", "Dance", "Sit", "Kneel", "Sleep", "Wave", "Bow", "Cheer", "Cry", "Kiss"))
                assertTrue(text.any { it.name == name && it.anim >= 0 }, "$k: $name")
            assertTrue(e.size >= 300, "$k: ${e.size}")
            assertEquals(e.size, e.map { it.id }.toSet().size, "$k: ids repeat")
        }
        // a voice of the human woman's laugh
        assertTrue(c.forModel(1, Sex.FEMALE).first { it.name == "Laugh" }.soundFiles.isNotEmpty())
        assertEquals(1000764, c.modelFile(1, Sex.FEMALE))
        assertEquals("EmoteLaugh", c.animName(70))
    }
}
