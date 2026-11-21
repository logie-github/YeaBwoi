package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CreatorModel
import com.logie.wifecraft.creator.CreatureLooks
import com.logie.wifecraft.creator.CsvTables
import com.logie.wifecraft.creator.CustomChoices
import com.logie.wifecraft.creator.Dressing
import com.logie.wifecraft.creator.Sex
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The creator presets of assets/characters.json (Chromie's gnome look) on the game's own tables, when they are here
 * (WIFECRAFT_TABLES). Each preset's picks are choices the creator lists for its race and sex, survive the creator's own
 * checks when put on, and the look resolves with its items, which ItemDisplayInfo has and which draw something. With
 * WIFECRAFT_CHARACTER_LOOKS set, each preset's look as the creator draws it is written there (preset_<character>_<key>.look).
 */
class CreatorPresetsRealTest {
    @Test fun everyPresetIsTheCreatorsOwnChoices() {
        val dir = System.getenv("WIFECRAFT_TABLES")?.let(::File)?.takeIf { File(it, "Creature.csv").isFile }
        assumeTrue(dir != null, "WIFECRAFT_TABLES isn't set: the game's tables aren't here")
        val t = CsvTables(dir!!)
        val c = Catalog.build(t)
        val roster = CharacterRoster.parse(File(System.getProperty("wifecraft.assets"), "characters.json").readText())
        val chromie = roster.first { it.id == "chromie" }.presets.single()
        assertEquals(7, chromie.raceId)
        assertEquals(Sex.FEMALE, chromie.sex)
        // her display's own parts are what the preset holds
        val parts = assertNotNull(CharacterLooks.parts(t, chromie.sourceDisplay))
        // but her hair colour: her own (CustomChoices.CHROMIE_HAIR) in place of the display's pale ash
        assertEquals(parts.picks + (88 to CustomChoices.CHROMIE_HAIR), chromie.picks)
        val hair = c.model(7, Sex.FEMALE)!!.options.first { it.id == 88 }
        assertEquals(CustomChoices.CHROMIE_HAIR, hair.choices.last().id)
        assertEquals("Chromie", hair.choices.last().name)
        assertEquals(0xAA8035, hair.choices.last().swatch1)
        assertEquals(parts.equipment, chromie.equipment)

        val out = System.getenv("WIFECRAFT_CHARACTER_LOOKS")?.let(::File)?.also { it.mkdirs() }
        val items = t.rows("ItemDisplayInfo").associateBy { it["ID"]!!.trim() }
        val materials = t.rows("ItemDisplayInfoMaterialRes").groupBy { it["ItemDisplayInfoID"]!!.trim() }
        val outfits = CreatorPresets.outfits(roster)
        for (ch in roster) for (p in ch.presets) {
            val entry = assertNotNull(c.model(p.raceId, p.sex), "${ch.id}/${p.key}: race ${p.raceId} in the creator")
            for ((o, choice) in p.picks) {
                val option = assertNotNull(entry.options.firstOrNull { it.id == o }, "${ch.id}/${p.key}: option $o is a row of the creator")
                assertTrue(option.choices.any { it.id == choice }, "${ch.id}/${p.key}: choice $choice is listed in ${option.name}")
            }
            for ((slot, display) in p.equipment) {
                val row = assertNotNull(items[display.toString()], "${ch.id}/${p.key}: item display $display (slot $slot) in ItemDisplayInfo")
                val draws = (0..1).any { (row["ModelResourcesID_$it"]?.trim()?.toIntOrNull() ?: 0) > 0 || (row["ModelMaterialResourcesID_$it"]?.trim()?.toIntOrNull() ?: 0) > 0 } ||
                    !materials[display.toString()].isNullOrEmpty()
                assertTrue(draws, "${ch.id}/${p.key}: item display $display draws a model or a texture")
            }
            // the preset's look as the creator puts it on, wearing the preset's own items as drawn
            val set = outfits.first { it.name == p.label }
            val m = CreatorModel(c, Appearance(p.raceId, p.sex))
            assertTrue(m.applyLook(assertNotNull(set.look)))
            m.edit { it.copy(clothing = set.displays, starterClothing = false) }
            for ((o, choice) in p.picks) assertEquals(choice, m.appearance.picks[o], "${ch.id}/${p.key}: option $o kept after the creator's checks")
            assertEquals(p.equipment, m.appearance.clothing)
            val look = m.resolved(t)
            assertTrue(look.layers.isNotEmpty())
            if (p.picks[88] == CustomChoices.CHROMIE_HAIR) {
                // her hair: the gold's texture (3448769) through her tints, and the hair style's scalp that hangs on the gold
                val tinted = look.layers.filter { it.tint1 == 0xFFFFE7 && it.tint2 == 0x392A1E }
                assertTrue(tinted.any { it.fileId == 3448769 && it.textureType == 6 }, "hair texture tinted: $tinted")
                assertTrue(tinted.any { it.textureType == 1 }, "the scalp tinted: $tinted")
                assertTrue(look.layers.filter { it.tint1 != null }.all { it in tinted })
            }
            println("${ch.id}/${p.key}: model ${look.model}, ${look.layers.size} layers, ${look.extraModels.size} extra; " + look.diagnostics.joinToString("; "))
            if (out != null) {
                val call = CreatureLooks.call(t, look, Dressing.layout(c, t, m.appearance))
                File(out, "preset_${ch.id}_${p.key}.look").writeText(buildString {
                    appendLine("# ${ch.name} preset ${p.label}")
                    look.diagnostics.forEach { appendLine("# $it") }
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
        // the Mechagnome's options are its own (no choice of hers is one of them)
        val mecha = c.model(37, Sex.FEMALE)
        if (mecha != null) assertTrue(mecha.options.flatMap { o -> o.choices.map { it.id } }.none { it in chromie.picks.values })
    }
}
