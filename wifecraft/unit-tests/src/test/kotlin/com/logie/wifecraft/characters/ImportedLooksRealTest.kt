package com.logie.wifecraft.characters

import com.logie.wifecraft.bodyparts.ModelParts
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CsvTables
import com.logie.wifecraft.creator.DressCall
import com.logie.wifecraft.creator.Dressing
import com.logie.wifecraft.creator.ImportStore
import com.logie.wifecraft.creator.Overrides
import com.logie.wifecraft.creator.ResolvedLook
import com.logie.wifecraft.creator.Sex
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whitemane and the other named characters on the game's own tables (WIFECRAFT_TABLES; skipped without them) with a
 * SYNTHETIC import (the user's Ralia package is never here): every file the human woman's Skin Color option puts on stands
 * for an imported one (ImportStore.FIRST_ID + n). Each of her choices (scarlet, deathknight, their nude ones, underwear,
 * bare) and her Scarlet display 2043 is checked to draw the imported files; with WIFECRAFT_IMPORT_LOOKS set, each is written
 * there as an engine-test look file twice (<choice>_off.look, <choice>_on.look) with `map.txt` (retail id, imported id) for
 * the tinted stand-ins a desktop render draws (the scratch script recolours the retail BLPs).
 */
class ImportedLooksRealTest {
    @Test fun whitemanesEveryLookWearsTheImports() {
        val dir = System.getenv("WIFECRAFT_TABLES")?.let(::File)?.takeIf { File(it, "Creature.csv").isFile }
        assumeTrue(dir != null, "WIFECRAFT_TABLES isn't set: the game's tables aren't here")
        val t = CsvTables(dir!!)
        val c = Catalog.build(t)
        val assets = File(System.getProperty("wifecraft.assets"))
        val cast = NamedCast.parse(File(assets, "characters.json").readText(), File(assets, ModelParts.ASSET).readText())
        val w = cast.character("whitemane")!!
        val human = CharacterLooks.appearance(w.variant("underwear")!!)!!
        val retail = CharacterLooks.skinColorFiles(c, t, human).sorted()
        val map = retail.withIndex().associate { (i, id) -> id to ImportStore.FIRST_ID + 1000 + i }
        val synthetic = object : Overrides {
            override fun texture(retailFileId: Int, female: Boolean) = if (female) map[retailFileId] ?: retailFileId else retailFileId
            override fun bodyModel(raceId: Int, shape: Int): Int? = null
            override fun bodyLayer(raceId: Int, style: Int, density: Int): Int? = null
            override fun hasRalia() = true
            override fun hasBody() = false
        }
        val out = System.getenv("WIFECRAFT_IMPORT_LOOKS")?.let(::File)?.also { it.mkdirs() }
        out?.let { File(it, "map.txt").writeText(map.entries.joinToString("") { (r, s) -> "$r $s\n" }) }
        val failed = ArrayList<String>()
        fun check(name: String, on: ImportedLook, off: ImportedLook, wanted: Boolean) {
            val used = on.look.layers.map { it.fileId }.toSet() + on.look.textures.values
            val imported = used.any { it >= ImportStore.FIRST_ID }
            println("$name: ${if (imported) "imported " + used.filter { it >= ImportStore.FIRST_ID } else "the game's own"}" +
                (on.drawn?.let { " (drawn as race ${it.raceId} ${it.sex}, ${it.clothing.size} items)" } ?: " (as the game has it)"))
            if (imported != wanted) failed += "$name: imported=$imported, wanted $wanted"
            if (out != null) {
                File(out, "${name}_off.look").writeText(lookFile("$name, nothing imported", off.look, off.call(t), off.character))
                File(out, "${name}_on.look").writeText(lookFile("$name, synthetic import", on.look, on.call(t), on.character))
            }
        }
        for (choice in cast.choices(w)) {
            val a = cast.appearance(w, choice)
            check("whitemane_${choice.key}", ImportedLooks.of(c, t, a, cast, synthetic), ImportedLooks.of(c, t, a, cast, Overrides.None), true)
        }
        // her source displays, as a placed NPC or a portrait draws them: the classic Scarlet one is a human woman (drawn as her
        // body wearing its items while the import changes it); the death knight's is too
        for (d in listOf(2043, 71257)) {
            val on = ImportedLooks.ofDisplay(c, t, d, synthetic)
            check("display_$d", on, ImportedLooks.ofDisplay(c, t, d, Overrides.None), on.drawn != null)
        }
        // every named character's bare look (a playable body) takes it; Sylvanas' own model doesn't (not a player body)
        for (ch in cast.characters) for (choice in cast.choices(ch)) {
            val a = cast.appearance(ch, choice)
            val on = ImportedLooks.of(c, t, a, cast, synthetic)
            if (choice.display > 0) assertNull(on.drawn?.takeIf { CharacterLooks.parts(t, choice.display) == null }, "${ch.id}/${choice.key}")
            if (a.raceId == 1 && a.sex == Sex.FEMALE && choice.display == 0)
                check("${ch.id}_${choice.key}", on, ImportedLooks.of(c, t, a, cast, Overrides.None), true)
        }
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
    }

    private fun lookFile(what: String, look: ResolvedLook, c: DressCall, character: Boolean) = buildString {
        appendLine("# $what")
        look.diagnostics.forEach { appendLine("# $it") }
        appendLine("model ${c.model}")
        appendLine("scale ${c.scale}")
        appendLine("character ${if (character) 1 else 0}")
        appendLine("geo " + c.geosetKeys.indices.joinToString(",") { "${c.geosetKeys[it]}=${c.geosetValues[it]}" })
        for (i in c.layerFiles.indices) {
            val m = c.layerMeta.copyOfRange(i * Dressing.META, i * Dressing.META + Dressing.META)
            appendLine("layer ${m.joinToString(" ")} ${c.layerFiles[i]} 0x%08x 0x%08x".format(c.tintColours[2 * i], c.tintColours[2 * i + 1]))
        }
        for (i in 0 until c.extraModels.size / Dressing.EXTRA)
            appendLine("extra " + c.extraModels.copyOfRange(i * Dressing.EXTRA, i * Dressing.EXTRA + Dressing.EXTRA).joinToString(" "))
    }
}
