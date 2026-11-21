package com.logie.wifecraft.characters

import com.logie.wifecraft.bodyparts.ModelParts
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.NamedLook
import com.logie.wifecraft.creator.PartCut
import com.logie.wifecraft.creator.PartOptions
import com.logie.wifecraft.creator.PartPlacement
import com.logie.wifecraft.creator.PartSize
import com.logie.wifecraft.creator.Sex
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The named characters in the roster (NamedCast): their variant lists from assets/characters.json and model_parts.json. */
class NamedCastTest {
    private val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
    private val cast = NamedCast.parse(File(assets, "characters.json").readText(), File(assets, ModelParts.ASSET).readText())

    private fun keys(id: String) = cast.choices(assertNotNull(cast.character(id))).map { it.key }

    @Test fun theNamedCharactersAreRosterNpcs() {
        val actors = cast.actors()
        assertEquals(listOf("sylvanas", "calia", "lilian", "jaina", "alexstrasza", "chromie", "valeera", "alleria", "whitemane", "xalatath", "tess", "lyssabel", "sindragosa"),
            actors.map { it.id.removePrefix(NamedCast.PREFIX) })
        assertTrue(actors.all { NamedCast.isNamed(it.id) && it.appearance.named != null && it.appearance.sex == Sex.FEMALE })
        // each in her first look until one is chosen
        assertEquals("shadowlands", actors.first().appearance.named?.variant)
        val chosen = cast.actors(mapOf(NamedCast.actorId("sylvanas") to "bare"))
        assertEquals(NamedLook("sylvanas", "bare"), chosen.first().appearance.named)
        // a choice that isn't hers falls back to her first
        assertEquals("shadowlands", cast.actors(mapOf(NamedCast.actorId("sylvanas") to "nope")).first().appearance.named?.variant)
    }

    @Test fun variantListsAreWhatCanBeDrawn() {
        assertEquals(listOf("timewalker"), keys("chromie"))
        assertEquals(listOf("shadowlands", "shadowlands2", "shadowlands3", "underwear", "bare",
            "shadowlands_partial", "shadowlands2_partial", "shadowlands3_partial"), keys("sylvanas"))
        // Alexstrasza's Dragonflight model has no set: no partial for it
        assertTrue("visage_partial" in keys("alexstrasza"))
        assertFalse("dragonflight_partial" in keys("alexstrasza"))
        // the listed nude and tabard variants are offered once
        assertEquals(listOf("scarlet", "deathknight", "scarlet_nude", "deathknight_nude", "scarlet_tabard", "scarlet_tabard_crusade", "underwear", "bare"), keys("whitemane"))
        // the eleven partial looks of model_parts.json
        val partials = cast.characters.flatMap { c -> cast.choices(c).filter { it.kind == NamedCast.Kind.PARTIAL } }
        assertEquals(11, partials.size)
        assertEquals(cast.manifest!!.sets.map { it.set }.toSet(), partials.map { it.set!!.set }.toSet())
    }

    @Test fun eachChoiceDrawsWhatItSays() {
        val sylvanas = assertNotNull(cast.character("sylvanas"))
        val outfit = assertNotNull(cast.choice(sylvanas, "shadowlands"))
        assertEquals(95032, outfit.display)
        assertFalse(outfit.bare)
        val partial = assertNotNull(cast.choice(sylvanas, "shadowlands_partial"))
        assertEquals(0, partial.display)
        assertEquals(VariantKind.BARE, partial.variant.kind)
        assertEquals("sylvanas_shadowlands_partial", partial.set?.set)
        assertTrue(partial.bare)
        assertTrue(assertNotNull(cast.choice(sylvanas, "bare")).bare)
        assertFalse(assertNotNull(cast.choice(sylvanas, "underwear")).bare)
        val lilian = assertNotNull(cast.character("lilian"))
        assertTrue(assertNotNull(cast.choice(lilian, "bfa_nude")).bare)
        val tabard = assertNotNull(cast.choice(assertNotNull(cast.character("whitemane")), "scarlet_tabard"))
        assertEquals(NamedCast.Kind.TABARD, tabard.kind)
        assertTrue(tabard.bare)
        assertEquals("Scarlet Crusade (High Inquisitor, Scarlet Monastery), tabard only", tabard.label)
        // a display variant stands in the scene as her stand-in's race and sex, no gear
        val a = cast.appearance(sylvanas, outfit)
        assertEquals(29, a.raceId)
        assertFalse(a.gear)
        assertEquals(NamedLook("sylvanas", "shadowlands"), a.named)
        assertEquals(outfit, cast.choiceOf(a))
        // a body outfit keeps its own items
        val scarlet = cast.appearance(assertNotNull(cast.character("whitemane")), assertNotNull(cast.choice(cast.character("whitemane")!!, "scarlet")))
        assertTrue(scarlet.gear)
        assertTrue(scarlet.clothing.isNotEmpty())
        // Chromie has no body variant: her preset's race stands for her
        val chromie = assertNotNull(cast.character("chromie"))
        assertEquals(7, cast.appearance(chromie, cast.choice(chromie, null)!!).raceId)
        // what Options downloads for them
        assertTrue(95032 in cast.displays() && 28227 in cast.displays())
    }

    /**
     * Tess Greymane (display 116131, a human woman) and Lyssabel Dawnpetal (an assembled night elf woman: her display isn't
     * in the client's tables): each an outfit on her race's body, its nude look, underwear and bare; no partial (no model of
     * her own); Gear on wears the outfit, Gear off her bare look.
     */
    @Test fun tessAndLyssabelAreBodyCharactersWithTheUsualLooks() {
        assertEquals(listOf("gilneas", "gilneas_nude", "underwear", "bare"), keys("tess"))
        assertEquals(listOf("steward", "steward_nude", "underwear", "bare"), keys("lyssabel"))
        val tess = assertNotNull(cast.character("tess"))
        assertEquals("Tess Greymane", tess.name)
        val gilneas = assertNotNull(tess.variant("gilneas"))
        assertEquals(116131, gilneas.sourceDisplay)
        assertEquals(1, gilneas.raceId)
        assertEquals(Sex.FEMALE, gilneas.sex)
        assertTrue(gilneas.equipment.isNotEmpty() && !gilneas.isDisplay)
        assertEquals(1, tess.presets.size)
        val lyssabel = assertNotNull(cast.character("lyssabel"))
        assertEquals("Lyssabel Dawnpetal", lyssabel.name)
        val steward = assertNotNull(lyssabel.variant("steward"))
        assertEquals(4, steward.raceId)
        assertEquals(Sex.FEMALE, steward.sex)
        assertTrue(steward.era.startsWith("assembled"))
        // her spectacles (head) stay on in her nude look; the robe, belt and boots come off
        val nude = assertNotNull(lyssabel.variant("steward_nude"))
        assertEquals(steward.equipment[1], nude.equipment[1])
        assertTrue(nude.equipment.keys.none { it in CharacterLooks.NUDE_REMOVED })
        assertTrue(lyssabel.presets.isEmpty())
        for (c in listOf(tess, lyssabel)) {
            val outfit = cast.choices(c).first()
            assertEquals(NamedCast.Kind.OUTFIT, outfit.kind)
            assertEquals(0, outfit.display, "${c.id}: drawn as her race's body, not a display")
            assertTrue(cast.choices(c).none { it.kind == NamedCast.Kind.PARTIAL || it.kind == NamedCast.Kind.TABARD })
            assertEquals("bare", GearSwitch.choiceFor(cast, c, false, outfit.key)?.key)
            assertEquals(outfit.key, GearSwitch.choiceFor(cast, c, true, "bare")?.key)
            assertTrue(GearSwitch.isOn(cast.appearance(c, outfit), cast))
            assertFalse(GearSwitch.isOn(cast.appearance(c, cast.choice(c, "underwear")!!), cast))
            // undressed, her own face, hair and skin
            assertEquals(outfit.variant.picks, assertNotNull(c.variant("bare")).picks)
            assertTrue(assertNotNull(cast.choice(c, "bare")).bare)
        }
    }

    @Test fun anAppearanceKeepsItsNamedLookAndPartsAndOldOnesLoad() {
        val a = Appearance(1, Sex.MALE, named = NamedLook("jaina", "bfa_partial"), parts = PartOptions(PartSize.LARGE, PartCut.CUT, PartPlacement.LOW))
        assertEquals(a, Appearance.fromJson(JSONObject(a.toJson().toString())))
        val old = JSONObject("""{"race":4,"sex":"FEMALE","picks":{"1":2},"body":{"shape":0,"style":0,"density":0},"gear":true}""")
        val loaded = Appearance.fromJson(old)
        assertNull(loaded.named)
        assertNull(loaded.parts)
        // the defaults are left out (an old save's text, and its thumbnails' names, are unchanged)
        assertEquals(Appearance(4, Sex.FEMALE).toJson().toString(), Appearance(4, Sex.FEMALE, parts = PartOptions()).toJson().toString())
        assertFalse(Appearance(4, Sex.FEMALE).toJson().has("named"))
    }

    @Test fun noManifestNoPartials() {
        val bare = NamedCast.parse(File(assets, "characters.json").readText(), null)
        assertTrue(bare.characters.flatMap { bare.choices(it) }.none { it.kind == NamedCast.Kind.PARTIAL })
        assertTrue(NamedCast.parse(null, null).actors().isEmpty())
    }
}
