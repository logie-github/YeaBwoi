package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Eve body's app side (bodyparts/EveStore.kt): the import runner on SYNTHETIC packages (EveConvertTest's), its progress,
 * errors and Remove; Appearance's `eve` and older saves; the dress decision every drawing path takes; the pictures' cache
 * mark; the creator's editor; the real fit table (assets/eve_fit.json).
 */
class EveStoreTest {
    private val profile = File("../android/app/src/main/assets/sims_profile.json").readText()
    private val fitJson = File("../android/app/src/main/assets/eve_fit.json").readText()
    private val fit = EveFitTable.parse(fitJson)
    private fun tmp(): File = Files.createTempDirectory("evestore").toFile()

    @Test
    fun importsReportsProgressAndRemoves() {
        val dir = tmp()
        val store = EveStore(File(dir, "bodyparts"))
        assertEquals("Not imported", store.summary())
        val runner = EveImportRunner(store, { profile })
        val seen = ArrayList<EveImportRunner.State>()
        runner.listen { seen += it }
        val picked = EveConvertTest().packages(File(dir, "picked").apply { mkdirs() })
        var result: EveConvert.Result? = null
        runner.run(File(dir, "work"), { work -> picked.map { f -> File(work, f.name).also { f.copyTo(it) } } }) { result = it }
        val done = assertIs<EveImportRunner.State.Done>(runner.state, "${(runner.state as? EveImportRunner.State.Failed)?.message}")
        assertNotNull(result)
        val lines = seen.filterIsInstance<EveImportRunner.State.Running>().map { it.line }
        assertEquals("Copying your files…", lines.first())
        assertTrue("Reading the packages…" in lines && "Reading the body…" in lines && "Writing the set…" in lines, "$lines")
        assertTrue(lines.any { it.startsWith("Reading skins and overlays 1/") }, "$lines")
        assertEquals("Eve body: 1 mesh, 1 skin tone, 1 overlay", store.summary())
        assertTrue(done.message.startsWith("Imported. Eve body: 1 mesh"))
        assertTrue(File(store.dir, "set.json").isFile && File(store.dir, "eve_animated.weve").isFile)
        assertFalse(File(store.root, "eve.new").exists(), "the staging folder is gone")
        assertFalse(File(dir, "work").exists(), "the copies of what was picked are deleted")
        assertTrue(store.signature().isNotEmpty())
        // memory: the converter streams; this tiny set is far under the 100 MB the user's set stays under
        assertTrue(result!!.peakHeapBytes < 512L * 1024 * 1024)
        assertTrue(runner.remove())
        assertEquals("Not imported", store.summary())
        assertFalse(store.dir.exists())
        assertEquals("", store.signature())
        dir.deleteRecursively()
    }

    @Test
    fun failuresAreSaidAndKeepTheOldSet() {
        val dir = tmp()
        val store = EveStore(File(dir, "bodyparts"))
        val runner = EveImportRunner(store, { profile })
        val picked = EveConvertTest().packages(File(dir, "picked").apply { mkdirs() })
        runner.run(File(dir, "w1"), { picked })
        assertIs<EveImportRunner.State.Done>(runner.state)
        val before = store.signature()
        // the top alone: no lower body
        runner.run(File(dir, "w2"), { picked.filter { "TOP" in it.name } })
        val f1 = assertIs<EveImportRunner.State.Failed>(runner.state)
        assertTrue("lower body" in f1.message, f1.message)
        // the body without a skin
        runner.run(File(dir, "w3"), { picked.filter { "TOP" in it.name || "BOTTOM" in it.name } })
        val f2 = assertIs<EveImportRunner.State.Failed>(runner.state)
        assertTrue("No skin" in f2.message, f2.message)
        // nothing usable at all
        runner.run(File(dir, "w4"), { w -> listOf(File(w, "notes.txt").apply { writeText("hello") }) })
        assertIs<EveImportRunner.State.Failed>(runner.state)
        runner.run(File(dir, "w5"), { emptyList() })
        assertEquals("No files were picked.", assertIs<EveImportRunner.State.Failed>(runner.state).message)
        // a staging step that throws (a document that can't be opened)
        runner.run(File(dir, "w6"), { throw java.io.IOException("x.zip can't be opened.") })
        assertTrue("can't be opened" in assertIs<EveImportRunner.State.Failed>(runner.state).message)
        assertEquals(before, store.signature(), "the set imported first is kept")
        assertEquals("Eve body: 1 mesh, 1 skin tone, 1 overlay", store.summary())
        dir.deleteRecursively()
    }

    @Test
    fun progressLines() {
        assertEquals("Reading the body…", EveStore.progressLine(0, 40, "top"))
        assertEquals("Reading the body…", EveStore.progressLine(1, 40, "bottom"))
        assertEquals("Reading skins and overlays 1/38…", EveStore.progressLine(2, 40, "skin"))
        assertEquals("Reading skins and overlays 38/38…", EveStore.progressLine(39, 40, "skin"))
        assertEquals("Writing the set…", EveStore.progressLine(40, 40, "writing the set"))
    }

    @Test
    fun appearanceKeepsEveAndOlderSavesAreUnchanged() {
        val old = """{"race":4,"sex":"FEMALE","picks":{"12":34},"body":{"shape":1,"style":0,"density":0},"gear":false}"""
        val a = Appearance.fromJson(JSONObject(old))
        assertNull(a.eve)
        assertEquals(JSONObject(old).toString(), a.toJson().toString(), "an older save is written back the same")
        val o = EveOptions(on = true, bottom = "fairy", tone = 3, overlaySlot = 1, overlayShade = -1, opacity = 0.45f)
        val b = Appearance.fromJson(JSONObject(a.copy(eve = o).toJson().toString()))
        assertEquals(o, b.eve)
        val off = Appearance.fromJson(JSONObject(a.copy(eve = EveOptions()).toJson().toString()))
        assertEquals(EveOptions(), off.eve, "a chosen Off is kept (it is not the default)")
        assertEquals(a, Appearance.fromJson(a.toJson()))
    }

    private val ready = EveContext(true, fit, defaultOn = false, signature = "s1")
    private val woman = Appearance(1, Sex.FEMALE, gear = false, eve = EveOptions(on = true))
    private fun model(race: Int) = fit.forRace(race)!!.models.first()

    @Test
    fun dressDecisionTable() {
        fun on(own: Appearance?, drawn: Appearance?, race: Int = drawn?.raceId ?: 1, ctx: EveContext? = ready) =
            EveDress.decide(own, drawn, model(race), ctx).options != null
        // a human woman with nothing on: Eve
        assertTrue(on(woman, woman))
        // clothed: chest, legs, gloves, a tabard; boots and a helm don't count
        for (slot in listOf(5, 7, 10, 19, 4)) assertFalse(on(woman, woman.copy(gear = true, clothing = mapOf(slot to 1), starterClothing = false)), "slot $slot")
        for (slot in listOf(8, 1)) assertTrue(on(woman, woman.copy(gear = true, clothing = mapOf(slot to 1), starterClothing = false)), "slot $slot")
        // the recruit clothing not yet put on covers her
        assertFalse(on(woman, woman.copy(gear = true, clothing = emptyMap(), starterClothing = true)))
        // races: night elf, blood elf, troll yes; gnome, dwarf, draenei, tauren no (even if her options say on)
        for (r in listOf(4, 10, 8, 27, 29)) assertTrue(on(woman.copy(raceId = r), woman.copy(raceId = r)), "race $r")
        for (r in listOf(7, 3, 11, 6, 9, 22)) assertFalse(on(woman.copy(raceId = r), woman.copy(raceId = r)), "race $r")
        assertEquals(EveDress.NEVER_REASON, EveDress.decide(woman.copy(raceId = 7), woman.copy(raceId = 7), 0, ready).why)
        assertTrue("draenei" in EveDress.decide(woman.copy(raceId = 11), woman.copy(raceId = 11), model(11), ready).why!!.lowercase())
        // a man never (his parts are another rule)
        val man = woman.copy(sex = Sex.MALE)
        assertFalse(on(man, man))
        // a display drawn as the game has it, no set, the set not loaded, her own Off
        assertFalse(on(woman, null))
        assertFalse(on(woman, woman, ctx = null))
        assertFalse(on(woman, woman, ctx = EveContext(false, fit, false, "")))
        assertFalse(on(woman.copy(eve = EveOptions(on = false)), woman, ctx = EveContext(true, fit, defaultOn = true, signature = "s")))
        // the default: a woman with no choice of her own (a named character, a starter, an older NPC) follows Options
        val plain = woman.copy(eve = null)
        assertFalse(on(plain, plain))
        val withDefault = EveContext(true, fit, defaultOn = true, signature = "s")
        assertTrue(on(plain, plain, ctx = withDefault))
        // a named character: her roster appearance names the variant; what is drawn is the variant's body
        val roster = Appearance(10, Sex.FEMALE, gear = false, named = com.logie.wifecraft.creator.NamedLook("valeera", "underwear"))
        val underwear = Appearance(10, Sex.FEMALE, gear = false)
        val outfit = Appearance(10, Sex.FEMALE, gear = true, clothing = mapOf(5 to 1, 7 to 2), starterClothing = false)
        val nude = Appearance(10, Sex.FEMALE, gear = true, clothing = mapOf(1 to 3, 8 to 4), starterClothing = false)
        assertTrue(on(roster, underwear, ctx = withDefault))
        assertTrue(on(roster, nude, ctx = withDefault))
        assertFalse(on(roster, outfit, ctx = withDefault))
        assertFalse(on(roster, underwear), "the default off: her own body")
        // the model decides before the race (Mag'har's own race id on the orc model is still offered)
        assertNull(EveDress.decide(woman.copy(raceId = 36), woman.copy(raceId = 36), model(2), ready).why)
    }

    @Test
    fun cacheMarkFollowsTheSetAndTheOptions() {
        assertEquals("", EveDress.cacheMark(null, woman))
        assertEquals("", EveDress.cacheMark(EveContext(false, fit, true, "s1"), woman))
        assertEquals("", EveDress.cacheMark(ready, woman.copy(eve = null)), "no choice and the default off: her own body's picture")
        assertEquals("", EveDress.cacheMark(ready, woman.copy(eve = EveOptions(on = false))))
        val m = EveDress.cacheMark(ready, woman)
        assertTrue(m.matches(Regex("_e[0-9a-f]{8}")), m)
        assertEquals(m, EveDress.cacheMark(ready, woman), "stable")
        assertNotEquals(m, EveDress.cacheMark(EveContext(true, fit, false, "s2"), woman), "a new set draws anew")
        assertNotEquals(m, EveDress.cacheMark(ready, woman.copy(eve = EveOptions(on = true, tone = 2))), "other options draw anew")
        assertNotEquals("", EveDress.cacheMark(EveContext(true, fit, true, "s1"), woman.copy(eve = null)), "the default on")
    }

    @Test
    fun editorOffersTheSetsLists() {
        val ix = EveSetIndex(linkedMapOf("plain" to "eve_plain.weve", "fairy" to "eve_fairy.weve"), 1024, 1288, 0.28f, 0.9f,
            EveSetIndex.Texture("n", "neutral", "skin_neutral.png", 0, 0x999999, 0x999999, 0x999999, 0f),
            listOf(EveSetIndex.Texture("a", "x", "tone_00.png", 0xC7B1A8, 0xC0A495, 0, 0, 0f), EveSetIndex.Texture("b", "y", "tone_01.png", 0x5A3A2A, 0x553322, 0, 0, 0f)),
            listOf(EveSetIndex.OverlaySlot("freckles", listOf(EveSetIndex.Shade("1", "ov_freckles_00.png", 0xF0D0C0, 0, intArrayOf(0, 0, 4, 4)),
                EveSetIndex.Shade("2", "ov_freckles_01.png", 0x402010, 0, intArrayOf(0, 0, 4, 4))))), emptyList(), emptyList())
        val e = EveOptionsEditor(ix, fit)
        assertEquals(listOf("plain", "fairy"), e.bottoms)
        assertEquals(listOf("Tone 1", "Tone 2"), e.tones.map { it.label })
        assertEquals(0xC0A495, e.tones[0].colour)
        assertEquals("Freckles", e.slots.single().label)
        assertEquals(listOf(0xF0D0C0, 0x402010), e.slots[0].shades.map { it.swatch })
        assertEquals(0, e.nearestShade(0xE8C8B8, 0), "a pale skin: the pale shade")
        assertEquals(1, e.nearestShade(0x3A2010, 0), "a dark skin: the dark shade")
        assertEquals(-1, e.nearestShade(0x3A2010, 5))
        // normalised to the set: a lower body it lacks, a tone or slot past the end
        val n = e.normalise(EveOptions(on = true, bottom = "animated", tone = 9, overlaySlot = 3, overlayShade = 7, opacity = 2f))
        assertEquals(EveOptions(on = true, bottom = "plain", tone = -1, overlaySlot = -1, overlayShade = -1, opacity = 1f), n)
        assertEquals(EveOptions(on = true), e.read(Appearance(1, Sex.FEMALE), defaultOn = true))
        assertEquals(EveOptions(on = false), e.read(Appearance(1, Sex.MALE), defaultOn = true))
        assertEquals(EveOptions(on = true, tone = 1), e.write(Appearance(1, Sex.FEMALE), EveOptions(on = true, tone = 1)).eve)
        assertNull(e.unavailable(1, Sex.FEMALE))
        assertEquals("for women only", e.unavailable(1, Sex.MALE))
        assertEquals(EveDress.NEVER_REASON, e.unavailable(7, Sex.FEMALE))
        assertTrue(e.unavailable(11, Sex.FEMALE)!!.startsWith("not offered for Draenei"))
        assertEquals("fairy, tone 2, freckles (nearest shade) at 60%", e.describe(EveOptions(on = true, bottom = "fairy", tone = 1, overlaySlot = 0)))
        assertEquals("Off", e.describe(EveOptions()))
    }

    @Test
    fun realFitTable() {
        assertTrue(fit.rows.size >= 20)
        assertTrue(fit.forRace(1)!!.enabled && fit.forRace(4)!!.enabled && fit.forRace(10)!!.enabled)
        assertFalse(fit.forRace(7)!!.enabled, "gnome women: never")
        assertEquals(fit.forRace(1), fit.forModel(1000764))
        assertNull(fit.whyNot(1, 1000764))
        assertNotNull(fit.whyNot(99))
    }

    /** The table's defaults (engine/evebody.cpp reads them): she keeps the model's height, the seams stitched shut, the skin's
     *  colour taken from her own body; the races left off say why in plain words. */
    @Test
    fun realFitTableDefaults() {
        val root = JSONObject(fitJson)
        val d = root.getJSONObject("defaults").getJSONObject("fit")
        assertEquals(0.0, d.optDouble("heads", 0.0), "no head-count target: she stands as tall as the body she replaces")
        assertTrue(d.getDouble("transfer") > 0.0, "the colour transfer is on")
        assertEquals(1.0, d.getDouble("stitch"), "the seams are stitched")
        val hip = d.getDouble("hipRatio")
        assertTrue(hip in 0.5..0.6, "hip joints at $hip of her height")
        val races = root.getJSONArray("races")
        for (i in 0 until races.length()) {
            val r = races.getJSONObject(i)
            val f = r.optJSONObject("fit") ?: JSONObject()
            if (r.getBoolean("enabled")) {
                assertEquals(0.0, f.optDouble("heads", 0.0), "${r.getString("name")}: no row shortens her")
                assertEquals(0.0, f.optDouble("split", 0.0), "${r.getString("name")}: no split fit is offered")
            } else {
                assertTrue(r.getString("reason").isNotBlank(), "${r.getString("name")}: off, with a reason")
            }
        }
    }
}
