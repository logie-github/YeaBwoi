package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.anim.PairJson
import com.logie.wifecraft.sims.AnimTuning
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Automatic arousal (bodyparts/Arousal.kt, assets/arousal_map.json): the stage table, the tracker's rise, hold and fall, the
 * override and the Options mode, the stage keys, and the pack category kept on import and in the pair files.
 */
class ArousalTest {
    private val map: ArousalMap by lazy {
        val dir = System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets"
        ArousalMap.parse(File(dir, "arousal_map.json").readText())
    }
    private val dt = 1f / 30f

    private fun cls(vararg names: String, category: String = "", actors: Int = 2) = map.classify(StageHints(names.toList(), category, actors))

    /** Every animation_category value read from the real packs' tuning (WickedWhims' own, Anarcis', Azmodan22's: their typos too). */
    private val packCategories = listOf("TEASING", "HANDJOB", "FOOTJOB", "ORALJOB", "VAGINAL", "ANAL", "CLIMAX", "Climax", "ORAL", "ORAJOB",
        "VAGNAL", "VAGINA", "FACE", "CHEST, FACE")

    @Test
    fun everyPackCategoryHasALevel() {
        for (c in packCategories) {
            val k = map.canonical(c)
            assertNotNull(k, "pack category $c")
            assertTrue(map.categories.getValue(k).level in 0f..1f)
        }
        // the penetrative ones go in where they should; teasing is half-way and goes nowhere
        assertEquals(PartEntryKind.VAGINAL, map.categories.getValue("VAGINAL").entry)
        assertEquals(PartEntryKind.ANAL, map.categories.getValue("ANAL").entry)
        assertEquals(PartEntryKind.ORAL, map.categories.getValue("ORALJOB").entry)
        assertEquals(PartEntryKind.MANUAL, map.categories.getValue("HANDJOB").entry)
        assertEquals(PartEntryKind.KEEP, map.categories.getValue("CLIMAX").entry)
        assertEquals(PartEntryKind.NONE, map.categories.getValue("TEASING").entry)
        assertTrue(map.categories.getValue("TEASING").level in 0.5f..0.6f)
        assertEquals(0f, map.categories.getValue("CALM").level)
    }

    @Test
    fun wickedWhimsOwnStagesByName() {
        // WickedWhims' own default stages, as named in the app (no category: imported before it was kept)
        assertEquals(0f, cls("Stage 1 · Intimate Kissing").level)
        assertEquals(0f, cls("Sensual Dance").level)
        assertEquals(0f, cls("Love Embrace").level)
        assertEquals(0f, cls("Hold Me").level)
        assertEquals(PartEntryKind.VAGINAL, cls("Stage 4 · Doggy Pleasure").entry)
        assertEquals(PartEntryKind.VAGINAL, cls("Stage 5 · Cowgirl").entry)
        assertEquals(PartEntryKind.VAGINAL, cls("Stage 9 · Passionate Missionary").entry)
        assertEquals(PartEntryKind.ORAL, cls("Stage 7 · Passionate Blowjob").entry)
        assertEquals(PartEntryKind.ORAL, cls("Stage 3 · Sixty-Nine").entry)
        // he gives: aroused, nothing goes in
        val cunni = cls("Stage 2 · Passionate Cunnilingus")
        assertTrue(cunni.level > 0.5f); assertEquals(PartEntryKind.NONE, cunni.entry)
        assertEquals(PartEntryKind.MANUAL, cls("Handy Missionary").entry)
        assertEquals(PartEntryKind.ANAL, cls("Aggro Assfuck").entry)
        assertEquals(PartEntryKind.VAGINAL, cls("Spooning Penetration").entry)
        assertEquals("TEASING", cls("Butt Rubbing").category)
    }

    @Test
    fun categoryAgainstCalmNames() {
        // a pack's TEASING kiss stays flaccid; a strong category wins over a calm word in the name
        assertEquals(0f, cls("Intimate Kissing", category = "TEASING").level)
        assertEquals(PartEntryKind.VAGINAL, cls("Kissing Lotus", category = "VAGINAL").entry)
        assertEquals(PartEntryKind.ORAL, cls("PosePack_2017_set_1", category = "ORAJOB").entry)
        assertEquals("TEASING", cls("PosePack_2017_set_1", category = "TEASING").category)
        // unknown: a two-person stage with a sexual hint (the clip's pack in its name) is 0.6, no entry; anything else 0
        val unknown = cls("Anarcis:PosePack_201705231238150629_set_1")
        assertEquals(map.unknownSexual, unknown.level); assertEquals(PartEntryKind.NONE, unknown.entry)
        assertEquals(0.6f, map.unknownSexual)
        assertEquals(0f, cls("Anarcis:PosePack_201705231238150629_set_1", actors = 1).level)
        assertEquals(0f, cls("Wave Hello").level)
        assertEquals(0f, cls("Chat").level)
    }

    @Test
    fun theRealPackNamesAreClassified() {
        // the stages of the real packs the alignment audit read (names only: the hardest case); when the packs' audit tables
        // are on this machine, how many are told by name alone, printed
        val dir = System.getProperty("wifecraft.alignDir") ?: return
        for (pack in listOf("final_ww", "final_anarcis", "final_azgn")) {
            val f = File(dir, "$pack/stages.csv")
            if (!f.isFile) continue
            val names = f.readLines().drop(1).mapNotNull { Regex("^\"[^\"]*\",\"[^\"]*\",[^,]*,\"([^\"]*)\"").find(it)?.groupValues?.get(1) }.toSet()
            val by = names.groupingBy { cls(it).category }.eachCount()
            println("$pack: ${names.size} stage names: $by")
        }
    }

    @Test
    fun idleStaysFlaccidAndAStageRaisesIt() {
        val t = ArousalTracker()
        repeat(90) { t.step(StageClass.NONE, 0.2f, ArousalMode.AUTOMATIC, PartOverride.AUTO, dt) }
        assertEquals(0f, t.level)
        val doggy = cls("Stage 4 · Doggy Pleasure")
        // at the stage's first intensity (0.4) it goes to FOLLOW_LOW, past the swap; at full intensity to 1
        var s = 0f
        var steps = 0
        while (t.level < 0.69f && steps < 1000) { t.step(doggy, 0.4f, ArousalMode.AUTOMATIC, PartOverride.AUTO, dt); s += dt; steps++ }
        assertTrue(s in 1.2f..1.6f, "rose to 0.7 in $s s")
        repeat(300) { t.step(doggy, 0.9f, ArousalMode.AUTOMATIC, PartOverride.AUTO, dt) }
        assertEquals(1f, t.level)
        // never more than 1 / RISE_S a second
        val u = ArousalTracker()
        var last = 0f
        repeat(120) {
            val l = u.step(doggy, 1f, ArousalMode.AUTOMATIC, PartOverride.AUTO, dt)
            assertTrue(l - last <= dt / ArousalTracker.RISE_S + 1e-5f)
            last = l
        }
    }

    @Test
    fun itHoldsThroughAGapAndThenFalls() {
        val t = ArousalTracker()
        val doggy = cls("Stage 4 · Doggy Pleasure")
        repeat(300) { t.step(doggy, 1f, ArousalMode.AUTOMATIC, PartOverride.AUTO, dt) }
        assertEquals(1f, t.level)
        // a second between two stages: held
        repeat(30) { t.step(StageClass.NONE, 1f, ArousalMode.AUTOMATIC, PartOverride.AUTO, dt) }
        assertEquals(1f, t.level)
        repeat(30) { t.step(doggy, 1f, ArousalMode.AUTOMATIC, PartOverride.AUTO, dt) }
        assertEquals(1f, t.level)
        // the stage over: held HOLD_S, then down at 1 / FALL_S a second, flaccid after FALL_S more
        var s = 0f
        while (t.level >= 1f && s < 10f) { t.step(StageClass.NONE, 0.2f, ArousalMode.AUTOMATIC, PartOverride.AUTO, dt); s += dt }
        assertTrue(s in ArousalTracker.HOLD_S..ArousalTracker.HOLD_S + 0.1f, "held $s s")
        var f = 0f
        while (t.level > 0f && f < 10f) { t.step(StageClass.NONE, 0.2f, ArousalMode.AUTOMATIC, PartOverride.AUTO, dt); f += dt }
        assertTrue(f in ArousalTracker.FALL_S - 0.1f..ArousalTracker.FALL_S + 0.1f, "fell in $f s")
        assertEquals(PartEntryKind.NONE, t.lastEntry)
    }

    @Test
    fun kissAndDanceStayFlaccid() {
        val t = ArousalTracker()
        for (name in listOf("Stage 1 · Intimate Kissing", "Sensual Dance", "Cuddle and Nuzzle", "Flirty Slow Dancing")) {
            repeat(200) { t.step(cls(name), 0.9f, ArousalMode.AUTOMATIC, PartOverride.AUTO, dt) }
            assertEquals(0f, t.level, name)
        }
    }

    @Test
    fun theOverrideAndTheModeWin() {
        val doggy = cls("Stage 4 · Doggy Pleasure")
        val on = ArousalTracker()
        repeat(200) { on.step(StageClass.NONE, 0.2f, ArousalMode.AUTOMATIC, PartOverride.ON, dt) }
        assertEquals(1f, on.level)
        val off = ArousalTracker()
        repeat(200) { off.step(doggy, 1f, ArousalMode.AUTOMATIC, PartOverride.OFF, dt) }
        assertEquals(0f, off.level)
        val flaccid = ArousalTracker()
        repeat(200) { flaccid.step(doggy, 1f, ArousalMode.ALWAYS_FLACCID, PartOverride.AUTO, dt) }
        assertEquals(0f, flaccid.level)
        val erect = ArousalTracker()
        repeat(200) { erect.step(StageClass.NONE, 0.2f, ArousalMode.ALWAYS_ERECT, PartOverride.AUTO, dt) }
        assertEquals(1f, erect.level)
        // the override over the mode
        val both = ArousalTracker()
        repeat(200) { both.step(doggy, 1f, ArousalMode.ALWAYS_ERECT, PartOverride.OFF, dt) }
        assertEquals(0f, both.level)
        // the switch goes round Auto, On, Off; the stored names read back, anything else Auto
        assertEquals(PartOverride.ON, PartOverride.AUTO.next())
        assertEquals(PartOverride.OFF, PartOverride.ON.next())
        assertEquals(PartOverride.AUTO, PartOverride.OFF.next())
        assertEquals(PartOverride.OFF, PartOverride.of("OFF"))
        assertEquals(PartOverride.AUTO, PartOverride.of(null))
        assertEquals(ArousalMode.AUTOMATIC, ArousalMode.of("nonsense"))
        assertEquals(ArousalMode.ALWAYS_ERECT, ArousalMode.of("ALWAYS_ERECT"))
        ArousalControl.setOverride("npc1", PartOverride.ON)
        assertEquals(PartOverride.ON, ArousalControl.override("npc1"))
        ArousalControl.setOverride("npc1", PartOverride.AUTO)
        assertEquals(PartOverride.AUTO, ArousalControl.override("npc1"))
    }

    @Test
    fun aClimaxKeepsTheStageBeforesEntry() {
        val t = ArousalTracker()
        val anal = cls("Aggro Assfuck")
        repeat(10) { t.step(anal, 1f, ArousalMode.AUTOMATIC, PartOverride.AUTO, dt) }
        val climax = cls("Big Finish", category = "CLIMAX")
        assertEquals(PartEntryKind.KEEP, climax.entry)
        t.step(climax, 1f, ArousalMode.AUTOMATIC, PartOverride.AUTO, dt)
        assertEquals(PartEntryKind.ANAL, t.entryFor(climax))
        assertEquals(PartEntryKind.ORAL, t.entryFor(cls("Stage 7 · Passionate Blowjob")))
    }

    @Test
    fun stageKeysFindThePartner() {
        assertEquals("pair:abc", StageKeys.group("pair:abc:1"))
        assertEquals(1, StageKeys.role("pair:abc:1"))
        assertNull(StageKeys.group("clip:single"))
        val p = StageKeys.partners(arrayOf("pair:abc:0", null, "pair:abc:1", "choice:x:0"))
        assertEquals(listOf(2, -1, 0, -1), p.toList())
    }

    @Test
    fun theCategoryIsKeptOnImportAndInThePairFile() {
        val xml = """<I c="WickedWhimsAnimationPackage" n="X:Y" s="1"><L n="animations_list"><T>
            <T n="animation_display_name">0x0CBDA31C</T><T n="animation_category">VAGINAL</T>
            <T n="animation_actors_list"><T><T n="actor_id">0</T><T n="animation_name">A_Male</T></T>
            <T><T n="actor_id">1</T><T n="animation_name">A_Female</T></T></T></T></L></I>"""
        val defs = AnimTuning.parse(xml)
        assertEquals(1, defs.size)
        assertEquals("VAGINAL", defs[0].kind)
        // a pair file without the key (saved before) reads with none; one with it keeps it
        val p = com.logie.wifecraft.anim.PairAnim(id = "p1", name = "Doggy", category = "Partner",
            a = com.logie.wifecraft.anim.Anim("a", "a", "c", 1f, true, emptyList(), "s", 0L),
            b = com.logie.wifecraft.anim.Anim("b", "b", "c", 1f, true, emptyList(), "s", 0L),
            offsetA = com.logie.wifecraft.anim.PairOffset.ZERO, offsetB = com.logie.wifecraft.anim.PairOffset.ZERO,
            loops = true, locations = emptySet(), sounds = emptyList(), source = "s", importedAt = 0L, kind = "VAGINAL")
        val json = PairJson.toJson(p)
        assertEquals("pair", json.getString("kind"))
        assertEquals("VAGINAL", PairJson.fromJson(json).kind)
        json.remove("stageKind")
        assertEquals("", PairJson.fromJson(json).kind)
    }
}
