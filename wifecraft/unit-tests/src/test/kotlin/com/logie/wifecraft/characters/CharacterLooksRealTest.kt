package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CreatureLooks
import com.logie.wifecraft.creator.CsvTables
import com.logie.wifecraft.creator.DressCall
import com.logie.wifecraft.creator.Dressing
import com.logie.wifecraft.creator.ResolvedLook
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every variant of assets/characters.json on the game's own tables, when they are here (WIFECRAFT_TABLES: wago.tools'
 * CSVs, tools/creature_probe.py tables). Skipped without them. With WIFECRAFT_CHARACTER_LOOKS set, each variant's look is
 * written there as an engine-test look file (<character>_<variant>.look; a humanoid outfit also as the game's own NPC
 * display, <character>_<variant>_npc.look) for tools/creature_probe.py files/render.
 */
class CharacterLooksRealTest {
    @Test fun everyVariantResolves() {
        val dir = System.getenv("WIFECRAFT_TABLES")?.let(::File)?.takeIf { File(it, "Creature.csv").isFile }
        assumeTrue(dir != null, "WIFECRAFT_TABLES isn't set: the game's tables aren't here")
        val t = CsvTables(dir!!)
        val c = Catalog.build(t)
        val roster = CharacterRoster.parse(File(System.getProperty("wifecraft.assets"), "characters.json").readText())
        val out = System.getenv("WIFECRAFT_CHARACTER_LOOKS")?.let(::File)?.also { it.mkdirs() }
        val failed = ArrayList<String>()
        for (ch in roster) for (v in ch.variants) {
            val look = CharacterLooks.resolve(c, t, v)
            if (look == null) { failed += "${ch.id}/${v.key}: ${CharacterLooks.why(c, t, v)}"; continue }
            val call = CharacterLooks.call(c, t, v, look)
            println("${ch.id}/${v.key}: model ${look.model}, ${look.layers.size} layers, ${look.extraModels.size} extra; " + look.diagnostics.joinToString("; "))
            out?.let { File(it, "${ch.id}_${v.key}.look").writeText(lookFile("${ch.name} ${v.label}", look, call, !v.isDisplay)) }
            if (out != null && v.kind == VariantKind.OUTFIT && !v.isDisplay && !v.era.startsWith("assembled")) {
                val npc = CreatureLooks.resolve(t, v.sourceDisplay) ?: continue
                File(out, "${ch.id}_${v.key}_npc.look").writeText(lookFile("display ${v.sourceDisplay}", npc, CreatureLooks.call(t, npc), true))
            }
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
