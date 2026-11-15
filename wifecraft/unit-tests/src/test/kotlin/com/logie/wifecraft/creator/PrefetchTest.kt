package com.logie.wifecraft.creator

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every file the creator can draw, for the first launch (Prefetch), on the small tables of our own. */
class PrefetchTest {
    private val tables = CsvTables(File(javaClass.getResource("/creator/ChrModel.csv")!!.toURI()).parentFile)
    private val catalog = Catalog.build(tables)
    private val plan = Prefetch.plan(catalog, tables)

    @Test fun everyChoiceOfEveryRaceIsInThePlan() {
        // the human woman's every choice: skins, faces on each skin, the face's bones, the tattoo's colours on their
        // patterns, the eyes, the horns (a skinned model), Upright's other model
        val woman = listOf(4001, 3000, 3001, 3010, 3011, 3012, 6001, 3041, 3042, 3043, 3080, 5001, 4002)
        assertTrue(plan.files.containsAll(woman), "missing ${woman - plan.files}")
        // the man's model and his Dark skin
        assertTrue(plan.files.containsAll(listOf(4000, 3001)))
        // each file once; no zero
        assertEquals(plan.files.size, plan.files.distinct().size)
        assertTrue(0 !in plan.files)
        // the body models and the skinned extras apart (their deps differ)
        assertTrue(plan.bodyModels.containsAll(listOf(4000, 4001, 4002)))
        assertEquals(setOf(5001), plan.extraModels)
        assertTrue(plan.looks > catalog.races.size)
    }

    @Test fun eachLookIsInThePlan() {
        // whatever the resolver gives for a look the creator can make is fetched already
        val m = CreatorModel(catalog, Appearance(1, Sex.FEMALE))
        for (o in m.model.options) for (c in o.choices) {
            m.choose(o.id, c.id)
            val look = m.resolved(tables)
            assertTrue(plan.files.containsAll(look.needsFiles), "${o.name} ${c.name}: ${look.needsFiles - plan.files}")
        }
    }

    @Test fun raceByRaceSharedFirst() {
        // the first race's (the Human's) man first, his model before anything he wears
        val ids = plan.files.toList()
        assertEquals(4000, ids.first())
        assertTrue(ids.indexOf(4001) < ids.indexOf(3010))
        // what one race's look shares with another's is counted for the first only
        assertEquals(plan.files.size, plan.byRace.values.sum())
        assertEquals(catalog.races.first().id, plan.byRace.keys.first())
    }

    @Test fun orderPutsEachModelsDepsAfterIt() {
        val deps = mapOf(4001 to listOf(7001, 7002, 3000), 5001 to listOf(7100))
        val asked = HashMap<Int, Boolean>()
        val order = plan.order { model, body -> asked[model] = body; deps[model] }
        val ids = order.toList()
        assertEquals(ids.indexOf(4001) + 1, ids.indexOf(7001))
        assertEquals(ids.indexOf(7001) + 1, ids.indexOf(7002))
        assertEquals(ids.indexOf(5001) + 1, ids.indexOf(7100))
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(true, asked[4001])
        assertEquals(false, asked[5001])
        // a model not here yet: nothing after it, the rest as planned
        assertEquals(plan.files, plan.order { _, _ -> null })
    }

    @Test fun keptAsText() {
        val text = plan.write("k1")
        val back = Prefetch.Plan.read(text, "k1")!!
        assertEquals(plan.files.toList(), back.files.toList())
        assertEquals(plan.bodyModels, back.bodyModels)
        assertEquals(plan.extraModels, back.extraModels)
        // other tables: a new plan
        assertNull(Prefetch.Plan.read(text, "k2"))
        assertNull(Prefetch.Plan.read("rubbish", "k1"))
    }

    /** On the game's own tables when WIFECRAFT_TABLES names them: the plan's size (WIFECRAFT_PLAN_OUT keeps its ids). */
    @Test fun realTables() {
        val dir = System.getenv("WIFECRAFT_TABLES")?.let(::File)?.takeIf { File(it, "ChrCustomizationChoice.csv").isFile }
        assumeTrue(dir != null, "WIFECRAFT_TABLES isn't set: the game's tables aren't here")
        val t = CsvTables(dir!!)
        val c = Catalog.build(t)
        val start = System.nanoTime()
        val p = Prefetch.plan(c, t)
        val ms = (System.nanoTime() - start) / 1_000_000
        val models = c.races.sumOf { r -> r.sexes.count { c.model(r.id, it) != null } }
        println("Prefetch: ${c.races.size} races, $models models, ${p.looks} looks, ${p.files.size} files " +
            "(${p.bodyModels.size} body models, ${p.extraModels.size} extra models, ${p.swept} only by the sweep) in $ms ms")
        System.getenv("WIFECRAFT_PLAN_OUT")?.let { File(it).writeText(p.write("real")) }
        assertTrue(p.files.size > 1000)
        // every look the creator makes, a choice at a time with its choices kept right (CreatorModel), is in the plan
        val missing = LinkedHashSet<String>()
        for (r in c.races) for (sex in r.sexes) {
            val m = CreatorModel(c, Appearance(r.id, sex))
            for (o in m.model.options) for (ch in o.choices) {
                m.choose(o.id, ch.id)
                val gone = m.resolved(t).needsFiles - p.files
                if (gone.isNotEmpty()) missing += "${r.name} $sex ${o.name} ${ch.name}: $gone"
            }
        }
        assertTrue(missing.isEmpty(), "${missing.size} looks need files the plan hasn't: ${missing.take(10)}")
    }
}
