package com.logie.wifecraft.dialogue

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KinkPackTest {
    private val sample = mapOf(
        "concepts.json" to """{"entries":[
          {"id":"a-feather","cat":"sensation","i":1,"role":"dom","band":"acquaintances","tags":["feather"],"concept":"Light touch.","voice":{"order":"Still."},"tw":{"gnome":"Tiny and bold."}},
          {"id":"b-rope","cat":"bondage","i":3,"role":"dom","band":"friendly","tags":["rope"],"concept":"Rope restraint.","voice":{"order":"Wrists together.","beg":"Tighter."},"tw":{"rogue":"Neat knots."}},
          {"id":"c-ruin","cat":"orgasm control","i":5,"role":"dom","band":"uninhibited","tags":["ruin"],"concept":"A negotiated stop at the brink.","voice":{"order":"Hands off."},"tw":{}},
          {"id":"d-service","cat":"service","i":2,"role":"sub","band":"acquaintances","tags":["service"],"concept":"Serving as a gift.","voice":{"beg":"May I attend you?"},"tw":{}}]}""",
        "care.json" to """{"entries":[
          {"id":"c1","kind":"checkin","i":1,"band":"strangers","text":"Check in often.","examples":["Still with me?"]},
          {"id":"c2","kind":"aftercare","i":2,"band":"acquaintances","text":"Water and a blanket.","examples":["Come here."]},
          {"id":"c3","kind":"safeword","i":1,"band":"strangers","text":"Red stops all.","examples":[]}]}""",
        "examples.json" to """{"examples":[
          {"id":"x1","band":"friendly","pers":["brat"],"races":["gnome"],"stage":"scene","role":"sub","tags":["brat"],"lines":[{"who":"A","text":"Make me."},{"who":"B","text":"Gladly."}]},
          {"id":"x2","band":"uninhibited","pers":["dominant"],"races":["orc"],"stage":"joi","role":"dom","tags":["joi"],"lines":[{"who":"A","text":"Slower."}]}]}""",
        "joi.json" to """{"stages":[{"id":"setup","hint":"Agree first.","care":"ask","min_band":"intimate","pace":"slow"},{"id":"build","hint":"One instruction at a time.","min_band":"intimate","pace":"steady"}],
          "variants":{"strict":"Short orders."},"twists":{"orc":"Deep and plain."},"by_band":{}}""",
        "twists.json" to """{"twists":{"orc":"Direct."}}""",
    )
    private val pack = KinkPack.load { sample[it] }

    @Test fun bandCapsIntensity() {
        val q = KinkQuery(Band.FRIENDLY, tags = setOf("ruin", "rope", "feather"), limit = 4)
        val ids = pack.retrieve(q).map { it.id }
        assertTrue("c-ruin" !in ids, "intensity 5 must not appear at friendly")
        assertEquals("b-rope", ids.first())
        assertTrue(pack.retrieve(KinkQuery(Band.STRANGERS, limit = 4)).isEmpty(), "nothing before acquaintances")
    }

    @Test fun roleFilters() {
        val q = KinkQuery(Band.ACQUAINTANCES, role = "sub", limit = 4)
        assertEquals(listOf("d-service"), pack.retrieve(q).map { it.id })
    }

    @Test fun raceTwistRaisesAndShows() {
        val q = KinkQuery(Band.FRIENDLY, races = listOf("rogue"), stage = "scene", limit = 2)
        val first = pack.retrieve(q).first()
        assertEquals("b-rope", first.id)
        assertTrue(pack.render(first, q).contains("Neat knots."))
    }

    @Test fun deterministic() {
        val q = KinkQuery(Band.UNINHIBITED, personalities = setOf("dominant"), limit = 4)
        assertEquals(pack.snippets(q), pack.snippets(q))
        assertEquals(pack.retrieve(q).map { it.id }, pack.retrieve(q).map { it.id })
    }

    @Test fun limitIsCappedAtFour() {
        assertTrue(pack.retrieve(KinkQuery(Band.UNINHIBITED, limit = 99)).size <= 4)
    }

    @Test fun intenseSnippetsCarryCare() {
        val s = pack.snippets(KinkQuery(Band.FRIENDLY, tags = setOf("rope"), stage = "scene", limit = 2))
        assertTrue(s.size <= 2)
        assertTrue(s.any { it.contains("Check in often") || it.contains("Red stops") }, s.toString())
    }

    @Test fun careByStage() {
        assertEquals("Water and a blanket.", pack.care(KinkQuery(Band.FRIENDLY, stage = "aftercare")))
        assertEquals("Red stops all.", pack.care(KinkQuery(Band.FRIENDLY, stage = "negotiate")))
    }

    @Test fun examplesMatchBandAndStage() {
        val ex = pack.examples(KinkQuery(Band.FRIENDLY, personalities = setOf("brat"), races = listOf("gnome"), stage = "scene"))
        assertEquals(listOf("x1"), ex.map { it.id })  // x2 is for uninhibited
        assertEquals("A: Make me.\nB: Gladly.", ex.first().render())
    }

    @Test fun joiNeedsIntimacyAndFlavours() {
        assertNull(pack.joiHint("setup", KinkQuery(Band.FRIENDLY)))
        val h = pack.joiHint("setup", KinkQuery(Band.INTIMATE, personalities = setOf("strict"), races = listOf("orc")))
        assertNotNull(h)
        assertTrue(h.contains("Short orders.") && h.contains("Deep and plain."))
    }

    @Test fun joiPlanShape() {
        assertTrue(pack.joiPlan(Band.FRIENDLY, 1).isEmpty())
        for (seed in 0L..20L) {
            val p = pack.joiPlan(Band.UNINHIBITED, seed)
            assertEquals("setup", p.first()); assertEquals("afterglow", p.last())
            assertEquals("permit", p[p.size - 2]); assertEquals("countdown", p[p.size - 3])
            assertTrue(p.count { it == "edge" } in 2..4)
            assertEquals(p, pack.joiPlan(Band.UNINHIBITED, seed))
        }
        val (start, beat) = pack.countdown(Band.UNINHIBITED, 7)
        assertTrue(start in listOf(5, 10) && beat in 1500..3000)
    }

    // The shipped pack, checked against the real files.
    private fun real(): KinkPack {
        val dir = File(System.getProperty("wifecraft.assets"), "dialogue/kink")
        return KinkPack.load { n -> File(dir, n).takeIf { it.exists() }?.readText() }
    }

    @Test fun shippedPackIsComplete() {
        val p = real()
        assertTrue(p.entries.size >= 40, "entries ${p.entries.size}")
        assertTrue(p.examples.size >= 30, "examples ${p.examples.size}")
        assertTrue(p.care.size >= 8)
        assertEquals(8, p.joiStages.size)
        assertEquals(p.entries.map { it.id }.toSet().size, p.entries.size, "duplicate ids")
        for (e in p.entries) assertTrue(e.intensity in 1..5, e.id)
    }

    @Test fun shippedSnippetsFitTheBudget() {
        val p = real()
        for (band in Band.entries) for (stage in listOf("flirt", "scene", "joi", "negotiate", "aftercare")) for (race in listOf("undead", "orc", "gnome", "worgen")) {
            val s = p.snippets(KinkQuery(band, personalities = setOf("dominant"), races = listOf(race), classes = listOf("paladin"), stage = stage, limit = 4))
            assertTrue(s.size <= 4)
            for (t in s) assertTrue(t.length <= KinkPack.MAX_CHARS, "${t.length}: $t")
        }
    }

    @Test fun shippedPackNeverShowsIntenseThingsToStrangers() {
        val p = real()
        for (e in p.retrieve(KinkQuery(Band.ACQUAINTANCES, limit = 4))) assertTrue(e.intensity <= 2 && e.band.ordinal <= 1, e.id)
        assertTrue(p.retrieve(KinkQuery(Band.INTIMATE, tags = setOf("ruin"), limit = 4)).none { it.intensity == 5 })
    }

    @Test fun shippedExamplesAreAdultAndCarry() {
        val p = real()
        val care = p.examples(KinkQuery(Band.INTIMATE, stage = "care"), 2)
        assertTrue(care.isNotEmpty())
        assertTrue(p.examples.any { it.tags.contains("safeword") })
        assertTrue(p.joiHint("countdown", KinkQuery(Band.UNINHIBITED, personalities = setOf("dominant"))) != null)
    }
}
