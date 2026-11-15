package com.logie.wifecraft.creator

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The creator on the game's own tables, when they are here: WIFECRAFT_TABLES names a folder of wago.tools' CSVs
 * (ChrRaces, ChrRaceXChrModel, ChrModel, ChrCustomization*, ChrModelTextureLayer, ChrModelMaterial,
 * CreatureDisplayInfo, CreatureModelData, TextureFileData; tools/creator_probe.py fetch). Skipped without them: nothing
 * of the game is in the repository. Every choice of every option is picked in turn and must draw.
 */
class RealTablesTest {
    private fun tables(): CsvTables {
        assumeTrue(shared != null, "WIFECRAFT_TABLES isn't set: the game's tables aren't here")
        return shared!!
    }

    companion object {
        /** One reading of the tables for both tests. */
        private val shared: CsvTables? by lazy {
            System.getenv("WIFECRAFT_TABLES")?.let(::File)?.takeIf { File(it, "ChrCustomizationChoice.csv").isFile }?.let(::CsvTables)
        }
    }

    /** Each choice of each option picked on a fresh look; what draws nothing, as "race sex: diagnostic". */
    private fun sweep(catalog: Catalog, t: Tables, raceId: Int, sex: Sex): Pair<Int, List<String>> {
        val entry = catalog.model(raceId, sex)!!
        val out = ArrayList<String>()
        var picked = 0
        for (o in entry.options) {
            for (c in o.choices) {
                val m = CreatorModel(catalog, Appearance(raceId, sex))
                catalog.master(o.id)?.let { master ->
                    // a colour is shown once a pattern it paints is picked
                    val pattern = catalog.elements[c.id].orEmpty().map { it.related }.firstOrNull { catalog.optionOf(it) == master }
                    if (pattern != null) m.choose(master, pattern)
                }
                m.choose(o.id, c.id)
                if (m.appearance.picks[o.id] != c.id) out += "${entry.race.name} $sex: ${o.name} ${c.id} didn't stay picked"
                picked++
                m.resolved(t).diagnostics.forEach { out += "${entry.race.name} $sex: $it (after ${o.name} ${c.id})" }
            }
        }
        return picked to out
    }

    @Test fun theFiveNamedResolveWithEveryOption() {
        val t = tables()
        val catalog = Catalog.build(t)
        val wanted = listOf(4 to Sex.FEMALE, 10 to Sex.MALE, 25 to Sex.MALE, 25 to Sex.FEMALE, 29 to Sex.MALE, 29 to Sex.FEMALE,
            31 to Sex.MALE, 31 to Sex.FEMALE)
        val problems = ArrayList<String>()
        for ((race, sex) in wanted) {
            val entry = catalog.model(race, sex)
            assertTrue(entry != null, "race $race $sex isn't in the catalog")
            assertTrue(entry.options.size >= 4, "${entry.race.name} $sex has ${entry.options.size} options")
            val (n, p) = sweep(catalog, t, race, sex)
            println("${entry.race.name} $sex: ${entry.categories.size} categories, ${entry.options.size} options, $n choices picked, ${p.size} not drawn")
            problems += p
        }
        problems.forEach { println("NOT DRAWN $it") }
        assertEquals(emptyList(), problems)
    }

    @Test fun everyRaceAndSex() {
        val t = tables()
        val catalog = Catalog.build(t)
        var models = 0
        var choices = 0
        val problems = ArrayList<String>()
        for (race in catalog.races) for (sex in race.sexes) {
            val entry = catalog.model(race.id, sex) ?: continue
            models++
            val look = LookResolver.resolve(catalog, t, Appearance(race.id, sex))
            assertTrue(look.model != 0 && look.layers.isNotEmpty(), "${race.name} $sex has no model or skin")
            val (n, p) = sweep(catalog, t, race.id, sex)
            choices += n
            problems += p
        }
        println("${catalog.races.size} races, $models models, $choices choices picked, ${problems.size} not drawn")
        problems.forEach { println("NOT DRAWN $it") }
        assertTrue(catalog.races.size >= 23, "only ${catalog.races.size} races")
        assertEquals(emptyList(), problems)
    }
}
