package com.logie.wifecraft.bodyparts

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelPartsTest {
    private val manifest = """{
      "regions": {"backpiece": {"attach": "Chest", "roots": ["Chest"]}},
      "sets": [
        {"set": "a_partial", "character": "sylvanas", "look": "shadowlands", "display": 95032, "model": 3221727,
         "standIn": 1733758, "standInLook": "bare", "regions": ["head", "shoulder_l", "backpiece"], "skinMatch": false},
        {"set": "b_partial", "character": "sylvanas", "look": "shadowlands3", "model": 4214530, "regions": ["head"], "cut": []}
      ]
    }"""

    @Test fun readsTheManifest() {
        val m = ModelParts.parse(manifest)
        assertEquals(2, m.sets.size)
        val a = m.forLook("sylvanas", "shadowlands")
        assertNotNull(a)
        assertEquals(3221727, a.model)
        assertEquals(1733758, a.standIn)
        assertEquals(listOf("head", "shoulder_l", "backpiece"), a.regions)
        assertEquals(listOf("Head"), a.cut)   // the default
        assertEquals(false, a.skinMatch)
        val b = m.forLook("sylvanas", "shadowlands3")!!
        assertTrue(b.cut.isEmpty() && b.skinMatch && b.standInLook == "bare")
        assertEquals(2, m.forCharacter("sylvanas").size)
        assertNull(m.forLook("jaina", "bfa"))
    }

    @Test fun refusesWhatTheEngineWould() {
        assertFailsWith<IllegalArgumentException> { ModelParts.parse("""{"sets": [{"set": "../x", "model": 1, "regions": ["head"]}]}""") }
        assertFailsWith<IllegalArgumentException> { ModelParts.parse("""{"sets": [{"set": "x", "model": 1, "regions": ["wings"]}]}""") }
        assertFailsWith<IllegalArgumentException> { ModelParts.parse("""{"sets": [{"set": "x", "regions": ["head"]}]}""") }
        assertFailsWith<IllegalArgumentException> { ModelParts.parse("""{"sets": [{"set": "x", "model": 3}]}""") }
    }

    @Test fun readsASetIndex() {
        val text = """{
          "set": "a_partial", "model": 3221727, "standIn": 1733758, "standInLook": "bare", "skinColour": "96B6C0", "cut": ["Head"],
          "parts": [
            {"file": "head_0.wcbp", "region": "head", "bone": "Head", "texture": 3221738, "blend": 0, "triangles": 10, "offset": [0.0, 0.01, -0.02], "scale": 1.05},
            {"file": "shoulder_l_0.wcbp", "region": "shoulder_l", "bone": "L_Clavicle", "texture": 3221741, "blend": 1, "triangles": 5, "offset": [0, 0, 0], "scale": 1}
          ]
        }"""
        val s = ModelParts.parseIndex(text)
        assertEquals(0x96B6C0, s.skinColour)
        assertEquals(listOf("Head"), s.cut)
        assertEquals(2, s.parts.size)
        assertEquals("L_Clavicle", s.parts[1].bone)
        assertEquals(1, s.parts[1].blend)
        assertEquals(0.01f, s.parts[0].dy)
        assertEquals(1.05f, s.parts[0].scale)
        assertEquals(-1, ModelParts.parseIndex("""{"skinColour": "", "parts": []}""").skinColour)
    }

    @Test fun tintsTheStandInToTheSource() {
        val t = ModelParts.tint(0xB1B5C4, 0x96B6C0)
        assertEquals(0x96 / 0xB1.toFloat(), t[0], 1e-4f)
        assertEquals(0xB6 / 0xB5.toFloat(), t[1], 1e-4f)
        assertTrue(ModelParts.tint(-1, 0x112233).all { it == 1f })
        // clamped: a glowing blue face doesn't paint the body black
        assertTrue(ModelParts.tint(0xEDB898, 0x050510).all { it >= 0.25f })
        assertTrue(ModelParts.tint(0x101010, 0xFFFFFF).all { it <= 2f })
    }

    /** The shipped manifest reads, and names only the bespoke-model characters (gnome women excluded). */
    @Test fun theAssetReads() {
        val dir = System.getProperty("wifecraft.assets") ?: return
        val m = ModelParts.parse(File(dir, ModelParts.ASSET).readText())
        assertTrue(m.sets.size >= 10)
        assertTrue(m.sets.all { it.standIn > 0 && it.display > 0 && it.set.endsWith("_partial") })
        assertTrue(m.sets.none { it.character == "chromie" })
        assertEquals(m.sets.size, m.sets.map { it.set }.toSet().size)
    }
}
