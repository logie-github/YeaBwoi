package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's two bodies (README "Bodies"): a Liard HD figure where a set is imported for the race and sex, else the game's own
 * model; the Eve body is scrapped (old saves and settings load without it, its files are deleted once). Nothing here has run
 * on a phone.
 */
class BodiesTest {
    private val hd = HdModelsTest()
    private fun tmp(): File = Files.createTempDirectory("bodies").toFile()

    /** A context with sets for the human woman and man and the night elf woman (the three Liard sets built so far). */
    private fun ctx(on: Boolean = true, races: List<Pair<Int, Int>> = listOf(1 to 1, 1 to 0, 4 to 1)): HdContext {
        val d = tmp()
        val sets = races.mapIndexed { i, (r, s) -> HdSetInfo.read(File(d, "$i.whd").apply { writeBytes(hd.whd(r, s)) }) }
        return HdContext(on, sets.associateBy { it.key }, "sig")
    }

    private val races = Bodies.RACE_NAMES.keys.toList()

    @Test fun everyRaceAndSexResolvesToLiardWhereASetIsImportedElseTheGameModel() {
        val c = ctx()
        val liard = setOf(1 to Sex.FEMALE, 1 to Sex.MALE, 4 to Sex.FEMALE)
        for (r in races) for (sex in Sex.entries) {
            val a = Appearance(r, sex)
            val res = Bodies.resolve(a, a, c)
            assertEquals(if (r to sex in liard) BodyKind.LIARD else BodyKind.GAME, res.kind, "$r $sex")
            assertEquals(res.kind == BodyKind.LIARD, res.why == null, "$r $sex: the reason is given for the game's model only")
            assertTrue(res.note.startsWith(if (res.kind == BodyKind.LIARD) "Body: HD (Liard)" else "Body: Game model"))
        }
    }

    @Test fun aPandarenSetServesTheThreePandarenRaces() {
        val c = ctx(races = listOf(24 to 1))
        for (r in listOf(24, 25, 26)) {
            val a = Appearance(r, Sex.FEMALE)
            assertEquals(BodyKind.LIARD, Bodies.resolve(a, a, c).kind, "race $r")
            assertEquals(BodyKind.GAME, Bodies.resolve(a.copy(sex = Sex.MALE), a.copy(sex = Sex.MALE), c).kind)
        }
        assertEquals(241, Bodies.key(25, Sex.FEMALE)); assertEquals(240, Bodies.key(26, Sex.MALE))
    }

    @Test fun thereIsNoThirdBody() {
        // whatever the clothing, Gear, class or sculpt, the answer is one of the two (no Eve rule: "Gear off only" is gone)
        val c = ctx()
        for (gear in listOf(true, false)) for (cls in listOf(0, 1, 4, 8)) {
            val a = Appearance(4, Sex.FEMALE, gear = gear, classId = cls)
            assertEquals(BodyKind.LIARD, Bodies.resolve(a, a, c).kind)
            val orc = Appearance(2, Sex.FEMALE, gear = gear, classId = cls)
            assertEquals(BodyKind.GAME, Bodies.resolve(orc, orc, c).kind)
        }
    }

    @Test fun hdIsTheDefaultAndOptionsSwitchIsAnOverride() {
        val a = Appearance(1, Sex.FEMALE)
        assertEquals(BodyKind.LIARD, Bodies.resolve(a, a, ctx(on = true)).kind)        // on whenever a set is there
        assertEquals(BodyKind.GAME, Bodies.resolve(a, a, ctx(on = false)).kind)        // Options off: the game's model for everyone
        assertEquals(BodyKind.GAME, Bodies.resolve(a, a, null).kind)                   // nothing loaded
        assertEquals(BodyKind.GAME, Bodies.resolve(a, a, ctx(races = emptyList())).kind)   // no set imported at all
    }

    @Test fun aCharacterCanChooseTheGameModelInsteadAndNothingElseChanges() {
        val c = ctx()
        val a = Appearance(1, Sex.FEMALE)
        val game = a.copy(hd = HdOptions(useGame = true))
        assertEquals(BodyKind.GAME, Bodies.resolve(game, game, c).kind)
        assertEquals("", HdDress.cacheMark(c, game))                                   // the picture is the game model's own
        assertNotEquals("", HdDress.cacheMark(c, a))
        // the choice is saved with the look and read back; a look without it is not "game"
        assertEquals(true, Appearance.fromJson(game.toJson()).hd?.useGame)
        assertFalse(HdOptions().toJson().has("useGame"))
        assertFalse(HdOptions.fromJson(HdOptions(hair = 2).toJson()).useGame)
        // a display drawn as the game has it (drawn == null) is the game's model
        assertEquals(BodyKind.GAME, Bodies.resolve(a, null, c).kind)
    }

    @Test fun anOldSaveWithAnEveBlockLoadsAndIsWrittenBackWithoutIt() {
        val old = """{"race":4,"sex":"FEMALE","picks":{"1":2},"body":{"shape":0,"style":0,"density":0},"gear":false,"class":8,
            |"eve":{"on":true,"bottom":"animated","tone":2,"overlaySlot":1,"overlayShade":3,"opacity":0.5,"bones":false}}""".trimMargin()
        val a = Appearance.fromJson(JSONObject(old))
        assertEquals(4, a.raceId); assertEquals(Sex.FEMALE, a.sex); assertEquals(8, a.classId); assertFalse(a.gear)
        assertEquals(mapOf(1 to 2), a.picks)
        assertNull(a.hd)
        val back = a.toJson()
        assertFalse(back.has("eve"), "the Eve block is dropped")
        assertEquals(a, Appearance.fromJson(back))
        // the same decision as a woman who never had it: Liard where a set is, else the game's model
        assertEquals(BodyKind.LIARD, Bodies.resolve(a, a, ctx()).kind)
        val orc = Appearance.fromJson(JSONObject(old.replace("\"race\":4", "\"race\":2")))
        assertEquals(BodyKind.GAME, Bodies.resolve(orc, orc, ctx()).kind)
    }

    @Test fun theEveFilesAreDeletedOnceAndNothingElseIsTouched() {
        val files = tmp(); val cache = tmp()
        for (p in listOf("bodyparts/eve/set.json", "bodyparts/eve/eve_plain.weve", "bodyparts/eve.new/x", "eve/old.bin", "bodyparts/hd/1_1.whd",
            "bodyparts/males/parts.jsonl", "npc-looks.json")) File(files, p).apply { parentFile.mkdirs(); writeText("x") }
        for (n in listOf("npc_3_ab12_e0123abcd_160.png", "npc_3_ab12_e0123abcd_h89abcdef_160.png", "npc_3_ab12_h89abcdef_160.png", "prop_77_160.png"))
            File(files, "thumbs/$n").apply { parentFile.mkdirs(); writeText("x") }
        File(cache, "eve-import/a.zip").apply { parentFile.mkdirs(); writeText("x") }
        File(cache, "hd-import/b.zip").apply { parentFile.mkdirs(); writeText("x") }
        val log = ArrayList<String>()
        assertTrue(LegacyEve.cleanup(files, cache, alreadyDone = true) { log += it }, "already done: nothing to do")
        assertTrue(File(files, "bodyparts/eve/set.json").exists() && log.isEmpty())
        assertTrue(LegacyEve.cleanup(files, cache, alreadyDone = false) { log += it })
        for (p in listOf("bodyparts/eve", "bodyparts/eve.new", "eve")) assertFalse(File(files, p).exists(), p)
        assertFalse(File(cache, "eve-import").exists())
        // what is not Eve's stays: the HD sets, the male parts, the saved looks, the other import's cache
        for (p in listOf("bodyparts/hd/1_1.whd", "bodyparts/males/parts.jsonl", "npc-looks.json")) assertTrue(File(files, p).exists(), p)
        assertTrue(File(cache, "hd-import/b.zip").exists())
        assertFalse(File(files, "thumbs/npc_3_ab12_e0123abcd_160.png").exists()); assertFalse(File(files, "thumbs/npc_3_ab12_e0123abcd_h89abcdef_160.png").exists())
        assertTrue(File(files, "thumbs/npc_3_ab12_h89abcdef_160.png").exists() && File(files, "thumbs/prop_77_160.png").exists())
        assertEquals(5, log.size); assertTrue(log.all { it.startsWith("Eve body scrapped: deleted ") })
        // a second launch with the flag unset again finds nothing and logs nothing
        log.clear()
        assertTrue(LegacyEve.cleanup(files, cache, alreadyDone = false) { log += it }); assertTrue(log.isEmpty())
    }

    @Test fun theCoverageLineNamesTheSetsAndTheFallback() {
        val sets = ctx().sets.values
        val line = Bodies.coverageLine(sets)
        assertTrue("Human woman" in line && "Human man" in line && "Night Elf woman" in line, line)
        assertTrue("game's own model" in line)
        assertTrue("every race and sex is drawn as the game's own model" in Bodies.coverageLine(emptyList()))
        assertEquals("race 99", Bodies.raceName(99))
        assertEquals(41, Bodies.key(4, Sex.FEMALE)); assertEquals(40, Bodies.key(4, Sex.MALE))
    }
}
