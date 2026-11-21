package com.logie.wifecraft.creator

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Create-a-Character shell's pure parts: tabs, body regions, history, Randomize with locks, sculpt, personality, presets, saves. */
class CasTest {
    private val tables = CsvTables(File(javaClass.getResource("/creator/ChrModel.csv")!!.toURI()).parentFile)
    private val catalog = Catalog.build(tables)
    private fun human() = CreatorModel(catalog, Appearance(1, Sex.FEMALE)).also { it.bodyAvailable = false }

    // ---- tabs

    @Test fun optionsGoToTheirTabsByName() {
        fun tab(name: String, cat: String? = null) = CasTabs.tabOf(Option(1, name, 0, false, emptyList()), cat)
        assertEquals(CasTab.HAIR, tab("Hair Style")); assertEquals(CasTab.HAIR, tab("Hair Color")); assertEquals(CasTab.HAIR, tab("Beard"))
        assertEquals(CasTab.HAIR, tab("Mustache")); assertEquals(CasTab.HAIR, tab("Sideburns"))
        assertEquals(CasTab.FACE, tab("Face")); assertEquals(CasTab.FACE, tab("Eye Color")); assertEquals(CasTab.FACE, tab("Eyebrows"))
        assertEquals(CasTab.FACE, tab("Tusks")); assertEquals(CasTab.FACE, tab("Nose"))
        assertEquals(CasTab.MAKEUP, tab("Skin Color")); assertEquals(CasTab.MAKEUP, tab("Tattoo")); assertEquals(CasTab.MAKEUP, tab("Tattoo Color"))
        assertEquals(CasTab.MAKEUP, tab("War Paint")); assertEquals(CasTab.MAKEUP, tab("Scars")); assertEquals(CasTab.MAKEUP, tab("Makeup"))
        assertEquals(CasTab.ACCESSORIES, tab("Ears")); assertEquals(CasTab.ACCESSORIES, tab("Earrings")); assertEquals(CasTab.ACCESSORIES, tab("Horns"))
        assertEquals(CasTab.ACCESSORIES, tab("Necklace")); assertEquals(CasTab.ACCESSORIES, tab("Jewelry Color")); assertEquals(CasTab.ACCESSORIES, tab("Nose Ring"))
        assertEquals(CasTab.BODY, tab("Upright")); assertEquals(CasTab.BODY, tab("Body Shape"))
        // by the category when the name says nothing
        assertEquals(CasTab.ACCESSORIES, tab("Variant 2", "Accessories")); assertEquals(CasTab.MAKEUP, tab("Variant", "Markings"))
        assertEquals(CasTab.OUTFIT, CasTabs.tabOf(Option(CreatorModel.GEAR, "Gear", 0, true, emptyList())))
        assertEquals(CasTab.BODY, CasTabs.tabOf(BodyOptions.option(BodyOptions.SHAPE, 1)))
    }

    @Test fun everyRowIsOnATabOnce() {
        val m = human()
        val all = m.categories().flatMap { c -> m.rows(c.id).map { it.option.id } }.toSet()
        val shown = CasTab.entries.flatMap { t -> CasTabs.rows(m, t).map { it.option.id } }
        assertEquals(all, shown.toSet())                                   // nothing of the creator lost
        // each once, but the skin's colour (Makeup and the Body tab's first section)
        val skin = m.model.options.first { it.name == "Skin Color" }.id
        assertEquals(shown.filter { it != skin }.size, shown.filter { it != skin }.toSet().size)
        assertTrue(CasTabs.rows(m, CasTab.BODY).any { it.option.id == skin } && CasTabs.rows(m, CasTab.MAKEUP).any { it.option.id == skin })
        assertEquals(listOf(CreatorModel.GEAR), CasTabs.rows(m, CasTab.OUTFIT).map { it.option.id })
        assertEquals(listOf("Hair Style"), CasTabs.rows(m, CasTab.HAIR).map { it.option.name })
        assertTrue(CasTabs.rows(m, CasTab.PERSONALITY).isEmpty())
    }

    @Test fun aTappedPartOpensItsTabAndView() {
        assertEquals(CasTab.FACE, CasTabs.tabFor(BodyRegion.HEAD)); assertEquals(CasCamera.FACE, CasTabs.cameraFor(BodyRegion.HEAD))
        assertEquals(CasTab.HAIR, CasTabs.tabFor(BodyRegion.HAIR))
        for (r in listOf(BodyRegion.CHEST, BodyRegion.WAIST, BodyRegion.HIPS, BodyRegion.LEGS, BodyRegion.ARMS, BodyRegion.NECK))
            assertEquals(CasTab.BODY, CasTabs.tabFor(r), r.name)
        assertEquals(CasCamera.UPPER, CasTabs.cameraFor(BodyRegion.CHEST)); assertEquals(CasCamera.UPPER, CasTabs.cameraFor(BodyRegion.WAIST))
        assertEquals(CasCamera.LEGS, CasTabs.cameraFor(BodyRegion.HIPS)); assertEquals(CasCamera.LEGS, CasTabs.cameraFor(BodyRegion.LEGS))
        assertNull(CasTabs.tabFor(BodyRegion.NONE))
        assertEquals(listOf(SculptSlider.HIPS, SculptSlider.BUTT, SculptSlider.BUTT_LIFT), CasTabs.slidersFor(BodyRegion.HIPS))
        assertEquals(SculptSlider.BUST_SIZE, CasTabs.slidersFor(BodyRegion.CHEST).first())
        // the engine's codes (bodypick.h) both ways
        for (r in BodyRegion.entries) assertEquals(r, BodyRegion.of(r.code))
        assertEquals(BodyRegion.NONE, BodyRegion.of(99))
    }

    // ---- history

    @Test fun undoAndRedo() {
        val h = CasHistory("a", limit = 4)
        assertFalse(h.canUndo); assertNull(h.undo())
        assertTrue(h.push("b")); assertFalse(h.push("b"))           // no change, nothing kept
        h.push("c")
        assertEquals("b", h.undo()); assertEquals("a", h.undo()); assertNull(h.undo())
        assertEquals("b", h.redo()); assertTrue(h.canRedo)
        h.push("x")                                                // a change after undoing drops what was ahead
        assertFalse(h.canRedo); assertEquals("x", h.current)
        h.push("y"); h.push("z"); h.push("w")                      // at most four
        assertEquals(4, h.size)
        assertEquals("z", h.undo()); assertEquals("y", h.undo()); assertEquals("x", h.undo()); assertNull(h.undo())
    }

    @Test fun undoPutsTheLookBack() {
        val m = human()
        val h = CasHistory(m.appearance)
        val hair = m.model.options.first { it.name == "Hair Style" }
        m.step(hair.id, 1); h.push(m.appearance)
        m.setSculpt(BodySculpt().with(SculptSlider.HIPS, 0.4f)); h.push(m.appearance)
        m.restore(h.undo()!!)
        assertNull(m.appearance.sculpt)
        m.restore(h.undo()!!)
        assertEquals(h.current, m.appearance)
        m.restore(h.redo()!!); m.restore(h.redo()!!)
        assertEquals(0.4f, m.appearance.sculpt!![SculptSlider.HIPS])
    }

    // ---- Randomize with locks

    @Test fun randomizeKeepsTheLockedTabs() {
        val m = human()
        val owners = CasTabs.owners(m)
        val hairIds = owners.filterValues { it == CasTab.HAIR }.keys
        assertTrue(hairIds.isNotEmpty())
        val sculpt = BodySculpt().with(SculptSlider.BUST_SIZE, 0.3f)
        m.setSculpt(sculpt)
        m.setPersonality(CasPersonality(listOf("CALM")))
        m.setGear(false); m.setClass(8)
        val locks = setOf(CasTab.HAIR, CasTab.BODY, CasTab.OUTFIT, CasTab.PERSONALITY)
        val hairBefore = hairIds.associateWith { m.appearance.picks[it] }
        val seen = HashSet<Map<Int, Int>>()
        for (seed in 1..20) {
            CasRandomizer.randomize(m, locks, Random(seed), sculptable = true, traits = listOf("A", "B", "C", "D"))
            assertEquals(hairBefore, hairIds.associateWith { m.appearance.picks[it] }, "seed $seed")
            assertEquals(sculpt, m.appearance.sculpt)
            assertEquals(listOf("CALM"), m.appearance.personality!!.traits)
            assertFalse(m.appearance.gear); assertEquals(8, m.appearance.classId)
            seen += m.appearance.picks.filterKeys { it !in hairIds }
        }
        assertTrue(seen.size > 1, "the unlocked tabs changed")
    }

    @Test fun randomizeUnlockedGivesPlausibleLooks() {
        val m = human()
        for (seed in 1..15) {
            CasRandomizer.randomize(m, emptySet(), Random(seed), sculptable = true, traits = listOf("A", "B", "C", "D"), opposed = { a, b -> setOf(a, b) == setOf("A", "B") })
            val a = m.appearance
            // every pick one of its option's choices
            for ((o, c) in a.picks) assertTrue(m.model.options.first { it.id == o }.choices.any { it.id == c })
            val s = a.sculpt ?: BodySculpt()
            for (k in SculptSlider.entries) assertTrue(kotlin.math.abs(s[k]) <= CasRandomizer.SCULPT_REACH + 1e-3f, "$k ${s[k]}")
            val t = a.personality!!.traits
            assertEquals(3, t.size); assertFalse("A" in t && "B" in t)
        }
        // the dice alone on a Warcraft body: the height only
        CasRandomizer.randomize(m, emptySet(), Random(3), sculptable = false)
        assertTrue((m.appearance.sculpt ?: BodySculpt()).values.keys.all { it == SculptSlider.HEIGHT })
    }

    // ---- sculpt

    @Test fun sculptValues() {
        val s = BodySculpt().with(SculptSlider.HIPS, 2f).with(SculptSlider.WAIST, -0.256f).with(SculptSlider.BELLY, Float.NaN)
        assertEquals(1f, s[SculptSlider.HIPS]); assertEquals(-0.26f, s[SculptSlider.WAIST]); assertEquals(0f, s[SculptSlider.BELLY])
        assertFalse(SculptSlider.BELLY in s.values)                 // zeros aren't kept
        assertTrue(BodySculpt().with(SculptSlider.ARMS, 0.3f).with(SculptSlider.ARMS, 0f).isNeutral)
        assertEquals(2 * SculptSlider.entries.size, s.toArray().size)   // (the sliders, then each one's side balance)
        assertEquals(1f, s.toArray()[SculptSlider.HIPS.ordinal])
        assertEquals(1.06f, BodySculpt().with(SculptSlider.HEIGHT, 1f).heightScale, 1e-6f)
        assertEquals(0.94f, BodySculpt().with(SculptSlider.HEIGHT, -1f).heightScale, 1e-6f)
        assertEquals(s, BodySculpt.fromJson(s.toJson()))
        assertNull(BodySculpt.fromJson(JSONObject()))
    }

    @Test fun everyBodyTakesEverySlider() {
        // the game's own bodies take the whole sculpt now (cpp/engine/sculpt.h, SculptOptions::gameBody), as the Eve body does
        for (k in SculptSlider.entries) for (eve in listOf(false, true)) {
            assertTrue(SculptSupport.supports(k, eveBody = eve), "$k"); assertNull(SculptSupport.why(k, eveBody = eve), "$k")
        }
        // leg length is the skeleton's (engine/bodyrig.h): live on every body, never greyed, never randomized
        for (eve in listOf(false, true)) {
            assertTrue(SculptSupport.supports(SculptSlider.LEG_LENGTH, eveBody = eve))
            assertNull(SculptSupport.why(SculptSlider.LEG_LENGTH, eveBody = eve))
        }
        assertTrue(SculptSlider.entries.all { it.drawn })
        for (seed in 1..10) assertEquals(0f, BodySculpt.random(Random(seed), 0.5f)[SculptSlider.LEG_LENGTH])
        val s = BodySculpt().with(SculptSlider.HEIGHT, 0.2f).with(SculptSlider.HIPS, 0.5f)
        val wow = RecordingSculptApplier(false); wow.apply("a", s)
        assertEquals(s, wow.applied.single().second)
        val eve = RecordingSculptApplier(true); eve.apply("a", s)
        assertEquals(s, eve.applied.single().second)
    }

    // ---- personality

    @Test fun personalityTraitsAndJson() {
        var p = CasPersonality()
        assertTrue(p.isDefault)
        val opposed = { a: String, b: String -> setOf(a, b) == setOf("LONER", "GREGARIOUS") }
        p = p.toggle("LONER", opposed).toggle("GREGARIOUS", opposed)       // opposites don't go together
        assertEquals(listOf("LONER"), p.traits)
        p = p.toggle("CALM").toggle("BOOKISH").toggle("ROMANTIC")            // three at most
        assertEquals(listOf("LONER", "CALM", "BOOKISH"), p.traits)
        p = p.toggle("CALM")
        assertEquals(listOf("LONER", "BOOKISH"), p.traits)
        p = p.copy(preferences = Preferences(Initiation.ASK, Pace.SLOW, setOf(StageKind.KISS, StageKind.EMBRACE)), bio = "A scribe.", favouriteColour = 0x5AA0D8)
        val back = CasPersonality.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(p, back)
        assertNull(CasPersonality.fromJson(CasPersonality().toJson()))
        assertEquals(Preferences(), Preferences.fromJson(JSONObject("{}")))
    }

    // ---- saves

    @Test fun oldSavesLoadIdentical() {
        val old = listOf(
            """{"race":4,"sex":"FEMALE","picks":{"123":456,"7":8},"body":{"shape":0,"style":0,"density":0},"gear":true}""",
            """{"race":1,"sex":"MALE","picks":{},"body":{"shape":2,"style":3,"density":1},"gear":false,"clothing":{"5":10},"undead":true,"undeadSkin":2}""",
            """{"race":7,"sex":"FEMALE","picks":{"1":2},"body":{"shape":0,"style":0,"density":0},"gear":true,"named":{"character":"chromie","variant":"underwear"},"parts":{"size":"LARGE","cut":"CUT","placement":"LOW"}}""",
        )
        for (text in old) {
            val a = Appearance.fromJson(JSONObject(text))
            assertNull(a.sculpt); assertNull(a.personality)
            // written back exactly as it was, less an older save's Transmog outfit ("clothing": the Gear switch's now)
            assertEquals(JSONObject(text).toMap() - "clothing", a.toJson().toMap(), text)
        }
    }

    @Test fun newFieldsRoundTrip() {
        val a = Appearance(1, Sex.FEMALE, mapOf(10 to 100), sculpt = BodySculpt().with(SculptSlider.BUST_SIZE, 0.35f).with(SculptSlider.HEIGHT, -0.1f),
            personality = CasPersonality(listOf("CALM", "ROMANTIC"), Preferences(Initiation.NO), "Bio", 0x112233))
        val back = Appearance.fromJson(JSONObject(a.toJson().toString()))
        assertEquals(a, back)
        // neutral sculpt and default personality are not written
        val plain = a.copy(sculpt = BodySculpt(), personality = CasPersonality())
        assertFalse(plain.toJson().has("sculpt")); assertFalse(plain.toJson().has("personality"))
    }

    @Test fun raceChangeKeepsSculptAndPersonality() {
        val m = human()
        m.setSculpt(BodySculpt().with(SculptSlider.WAIST, -0.3f))
        m.setPersonality(CasPersonality(bio = "x"))
        m.setRace(2)
        assertEquals(-0.3f, m.appearance.sculpt!![SculptSlider.WAIST])
        assertEquals("x", m.appearance.personality!!.bio)
    }

    // ---- presets

    @Test fun presetsRoundTripAndStore() {
        val dir = Files.createTempDirectory("cas-presets").toFile()
        try {
            val store = CasPresetStore(dir)
            assertTrue(store.list().isEmpty())
            val a = Appearance(1, Sex.FEMALE, mapOf(10 to 100), classId = 9,
                sculpt = BodySculpt().with(SculptSlider.HIPS, 0.4f), personality = CasPersonality(listOf("PLAYFUL")))
            val p = CasPreset("Evening / Look", a, "Elaria")
            store.save(p)
            assertEquals(listOf(p), store.list())
            assertTrue(store.fileFor(p.name).name.matches(Regex("[A-Za-z0-9 _-]+\\.json")))
            store.save(p.copy(appearance = a.copy(gear = false)))             // the same name replaces it
            assertEquals(1, store.list().size); assertFalse(store.list().single().appearance.gear)
            // shared and imported again: a new name, the same look
            val shared = store.export(store.list().single())
            val imported = store.import(shared)!!
            assertEquals("Evening / Look (2)", imported.name)
            assertEquals(store.list().first().appearance, imported.appearance)
            assertEquals(2, store.list().size)
            assertNull(store.import("{\"not\":\"a preset\"}")); assertNull(store.import("garbage"))
            assertNull(CasPreset.parse("""{"wifecraftPreset":9,"name":"x","appearance":{"race":1,"sex":"FEMALE"}}"""))   // a newer format
            assertTrue(store.delete("Evening / Look")); assertEquals(1, store.list().size)
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun builtInPresetsFromTheNamedLooks() {
        val chromie = ClothingSet(-1, "Chromie", mapOf(5 to 42), look = LookPreset(7, Sex.FEMALE, mapOf(1 to CustomChoices.CHROMIE_HAIR)))
        val plain = ClothingSet(-2, "Robes", mapOf(5 to 1))
        val b = CasPreset.builtIns(listOf(chromie, plain))
        assertEquals(listOf("Chromie"), b.map { it.name })
        assertTrue(b.single().builtIn)
        assertEquals(7, b.single().appearance.raceId)
        assertEquals(CustomChoices.CHROMIE_HAIR, b.single().appearance.picks[1])
        assertTrue(b.single().appearance.gear, "a named preset loads with Gear on")
        assertTrue(b.single().appearance.clothing.isEmpty(), "its clothing is the class's, put on as it is drawn")
    }

    @Test fun legLengthMapsAsTheEngineDoes() {
        // bodyrig.h legLengthFactor: 0.85 at -1, 1 at 0, 1.2 at +1, linear on each side; out of range clamped, NaN neutral
        assertEquals(0.85f, BodySculpt.legFactor(-1f), 1e-6f)
        assertEquals(0.925f, BodySculpt.legFactor(-0.5f), 1e-6f)
        assertEquals(1f, BodySculpt.legFactor(0f), 1e-6f)
        assertEquals(1.1f, BodySculpt.legFactor(0.5f), 1e-6f)
        assertEquals(1.2f, BodySculpt.legFactor(1f), 1e-6f)
        assertEquals(1.2f, BodySculpt.legFactor(3f), 1e-6f)
        assertEquals(1f, BodySculpt.legFactor(Float.NaN), 1e-6f)
        assertEquals(1.2f, BodySculpt().with(SculptSlider.LEG_LENGTH, 1f).legScale, 1e-6f)
        assertEquals(1f, BodySculpt().legScale, 1e-6f)
        // the engine reads it at Leg Length's place in the array (sculpt.h kSculptLegLength = 10)
        assertEquals(10, SculptSlider.LEG_LENGTH.ordinal)
        assertEquals(-0.3f, BodySculpt().with(SculptSlider.LEG_LENGTH, -0.3f).toArray()[10])
        // saved and read back like the others
        val s = BodySculpt().with(SculptSlider.LEG_LENGTH, 0.75f)
        assertEquals(s, BodySculpt.fromJson(s.toJson()))
    }
}
