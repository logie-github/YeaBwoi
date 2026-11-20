package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.PartCut
import com.logie.wifecraft.creator.PartOptions
import com.logie.wifecraft.creator.PartPlacement
import com.logie.wifecraft.creator.PartSize
import com.logie.wifecraft.creator.Sex
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which imported parts an NPC wears (PartChoice) and the scene's switch (PartStates). Synthetic parts.jsonl lines. */
class PartChoiceTest {
    private fun line(id: String, state: String, variant: String, v: Int, t: Int, name: String = "Part $variant") =
        JSONObject().put("id", id).put("name", name).put("state", state).put("variant", variant).put("file", "$id.wcbp")
            .put("texture", "$id.png").put("vertices", v).put("triangles", t).put("bone", "Pelvis").toString()

    // as the user's set: a second DEFAULT "Medium Uncut High" that is the "Medium Cut High" mesh under the wrong name
    private val jsonl = listOf(
        line("D_MCH", "DEFAULT", "Medium Cut High", 986, 1722),
        line("D_MUH", "DEFAULT", "Medium Uncut High", 1169, 2057),
        line("D_DUP", "DEFAULT", "Medium Uncut High", 986, 1722, name = "x_Default_Medium_High_Unc"),
        line("D_MUL", "DEFAULT", "Medium Uncut Low", 1253, 2201),
        line("D_SCL", "DEFAULT", "Small Cut Low", 967, 1670),
        line("D_LRH", "DEFAULT", "Large Retracted High", 1026, 1786),
        line("O_MUH", "ON", "Medium Uncut High", 1144, 1942),
        line("O_SCL", "ON", "Small Cut Low", 1071, 1860),
    ).joinToString("\n", postfix = "\n")

    private val entries = PartChoice.parse(jsonl)

    @Test fun theDefaultsPickTheRightMediumUncutHigh() {
        val (def, on) = PartChoice.select(entries, PartOptions())
        assertEquals("D_MUH", def?.id)
        assertEquals("O_MUH", on?.id)
        assertEquals("D_MUH.png", def?.texture)
        // the order in the file doesn't matter
        val swapped = PartChoice.parse(jsonl.lines().let { it.filter { l -> "D_DUP" in l } + it.filter { l -> "D_DUP" !in l } }.joinToString("\n"))
        assertEquals("D_MUH", PartChoice.pick(swapped, "DEFAULT", PartOptions())?.id)
    }

    @Test fun eachSettingPicksItsParts() {
        val (def, on) = PartChoice.select(entries, PartOptions(PartSize.SMALL, PartCut.CUT, PartPlacement.LOW))
        assertEquals("D_SCL", def?.id)
        assertEquals("O_SCL", on?.id)
        assertEquals("D_LRH", PartChoice.pick(entries, "DEFAULT", PartOptions(PartSize.LARGE, PartCut.RETRACTED, PartPlacement.HIGH))?.id)
        assertEquals("D_MUL", PartChoice.pick(entries, "DEFAULT", PartOptions(placement = PartPlacement.LOW))?.id)
        // missing: the nearest, size first ("Large Uncut Low" -> the only Large)
        assertEquals("D_LRH", PartChoice.pick(entries, "DEFAULT", PartOptions(PartSize.LARGE, PartCut.UNCUT, PartPlacement.LOW))?.id)
        assertNull(PartChoice.pick(entries, "OTHER", PartOptions()))
        assertEquals("Medium Uncut High", PartOptions().label)
    }

    @Test fun onlyAnUnclothedLowerBodyShowsThem() {
        val man = Appearance(1, Sex.MALE)
        assertTrue(PartChoice.applies(man))
        assertFalse(PartChoice.applies(Appearance(1, Sex.FEMALE)))
        assertFalse(PartChoice.lowerBodyBare(man))                                   // recruit clothing still to be put on
        assertTrue(PartChoice.lowerBodyBare(man.copy(gear = false)))                // No Gear: underwear or bare
        assertFalse(PartChoice.lowerBodyBare(man.copy(clothing = mapOf(7 to 100), starterClothing = false)))
        assertFalse(PartChoice.lowerBodyBare(man.copy(clothing = mapOf(5 to 100), starterClothing = false)))
        assertTrue(PartChoice.lowerBodyBare(man.copy(clothing = mapOf(1 to 100, 10 to 7), starterClothing = false)))
        assertEquals(PartDraw.NONE, PartDraw.visible(PartDraw.ON, false))
        assertEquals(PartDraw.ON, PartDraw.visible(PartDraw.ON, true))
    }

    @Test fun theSwitchPerNpc() {
        var s = PartStates()
        assertFalse(s.has("a"))
        assertEquals(PartDraw.NONE, s.state("a"))
        assertEquals(s, s.setOn("a", true))                                        // no parts: nothing to switch
        s = s.register("a").register("b")
        assertEquals(PartDraw.DEFAULT, s.state("a"))
        s = s.setOn("a", true)
        assertTrue(s.isOn("a"))
        assertEquals(PartDraw.ON, s.state("a"))
        assertEquals(PartDraw.DEFAULT, s.state("b"))
        assertEquals(PartDraw.ON, s.register("a").state("a"))                      // registering again keeps the state
        s = s.toggle("a")
        assertEquals(PartDraw.DEFAULT, s.state("a"))
        s = s.remove("b")
        assertFalse(s.has("b"))
    }
}
