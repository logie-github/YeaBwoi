package com.logie.wifecraft.creator

import com.logie.wifecraft.characters.ImportedLooks
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The class outfits (assets/class_outfits.json) on the game's own tables, when they are here (WIFECRAFT_TABLES): every
 * piece is an item display ItemDisplayInfo has and that draws a model or a texture, and every class of every race resolves
 * dressed. With WIFECRAFT_GEAR_LOOKS set, a human woman and man in four classes with Gear on, and with Gear off, are
 * written there as engine-test looks (gear_<sex>_<class|off>.look).
 */
class GearRealTest {
    @Test fun everyOutfitDrawsOnTheGamesTables() {
        val dir = System.getenv("WIFECRAFT_TABLES")?.let(::File)?.takeIf { File(it, "ItemDisplayInfo.csv").isFile }
        assumeTrue(dir != null, "WIFECRAFT_TABLES isn't set: the game's tables aren't here")
        val t = CsvTables(dir!!)
        val c = Catalog.build(t)
        val outfits = ClassOutfits.parse(File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets", "class_outfits.json").readText())
        val items = t.rows("ItemDisplayInfo").associateBy { it["ID"]!!.trim().toInt() }
        val materials = t.rows("ItemDisplayInfoMaterialRes").groupBy { it["ItemDisplayInfoID"]!!.trim().toInt() }
        for (d in outfits.displays()) {
            val row = assertNotNull(items[d], "item display $d in ItemDisplayInfo")
            val draws = (0..1).any { (row["ModelResourcesID_$it"]?.trim()?.toIntOrNull() ?: 0) > 0 || (row["ModelMaterialResourcesID_$it"]?.trim()?.toIntOrNull() ?: 0) > 0 } ||
                !materials[d].isNullOrEmpty()
            assertTrue(draws, "item display $d draws a model or a texture")
        }
        for (race in c.races) for (sex in race.sexes) {
            if (c.model(race.id, sex) == null) continue
            for (cls in outfits.classesFor(race.id)) {
                val a = outfits.dressed(Appearance(race.id, sex, classId = cls.id))
                val look = ImportedLooks.of(c, t, a, null, Overrides.None).look
                assertTrue(look.model > 0 && look.layers.isNotEmpty(), "race ${race.id} $sex ${cls.name}")
            }
        }

        val out = System.getenv("WIFECRAFT_GEAR_LOOKS")?.let(::File)?.also { it.mkdirs() } ?: return
        for (sex in listOf(Sex.FEMALE, Sex.MALE)) {
            val shots = listOf(1, 4, 8, 6).map { it to outfits.dressed(Appearance(1, sex, classId = it)) } +
                listOf(0 to outfits.dressed(Appearance(1, sex, gear = false)))
            for ((cls, a) in shots) {
                val plan = ImportedLooks.of(c, t, a, null, Overrides.None)
                val call = plan.call(t)
                val name = "gear_${sex.name.lowercase()}_${if (a.gear) outfits.name(cls).lowercase().replace(' ', '_') else "off"}"
                File(out, "$name.look").writeText(buildString {
                    appendLine("# human ${sex.name.lowercase()} ${if (a.gear) outfits.name(cls) + ", Gear on: " + a.clothing else "Gear off"}")
                    plan.look.diagnostics.forEach { appendLine("# $it") }
                    appendLine("model ${call.model}")
                    appendLine("scale ${call.scale}")
                    appendLine("character 1")
                    appendLine("geo " + call.geosetKeys.indices.joinToString(",") { "${call.geosetKeys[it]}=${call.geosetValues[it]}" })
                    for (i in call.layerFiles.indices) {
                        val meta = call.layerMeta.copyOfRange(i * Dressing.META, i * Dressing.META + Dressing.META)
                        appendLine("layer ${meta.joinToString(" ")} ${call.layerFiles[i]} 0x%08x 0x%08x".format(call.tintColours[2 * i], call.tintColours[2 * i + 1]))
                    }
                    for (i in 0 until call.extraModels.size / Dressing.EXTRA)
                        appendLine("extra " + call.extraModels.copyOfRange(i * Dressing.EXTRA, i * Dressing.EXTRA + Dressing.EXTRA).joinToString(" "))
                })
            }
        }
    }
}
