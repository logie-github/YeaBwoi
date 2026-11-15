package com.logie.wifecraft.creator

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CreatureLooks on the game's own tables, when they are here: WIFECRAFT_TABLES names a folder of wago.tools' CSVs
 * (tools/creature_probe.py tables). Skipped without them: nothing of the game is in the repository. Every named creature's
 * first display is resolved; with WIFECRAFT_LOOKS set, the looks of the displays in its displays.txt (or the demo's
 * defaults) are written there as engine-test look files, with counts.txt, for tools/creature_probe.py to fetch and draw.
 */
class CreatureLooksRealTest {
    private fun tables(): CsvTables {
        val dir = System.getenv("WIFECRAFT_TABLES")?.let(::File)?.takeIf { File(it, "Creature.csv").isFile }
        assumeTrue(dir != null, "WIFECRAFT_TABLES isn't set (or has no Creature table): the game's tables aren't here")
        return CsvTables(dir!!)
    }

    @Test fun everyNamedCreatureResolves() {
        val t = tables()
        val creatures = t.rows("Creature").filter { it["Name_lang"].orEmpty().isNotBlank() }
        val files = t.rows("ModelFileData").mapTo(HashSet()) { it.int("FileDataID") }
        val why = sortedMapOf<String, Int>()
        val notes = sortedMapOf<String, Int>()
        var drawable = 0
        for (c in creatures) {
            val d = (0..3).map { c.int("DisplayID_$it") }.firstOrNull { it != 0 }
            if (d == null) { why.merge("no display id", 1, Int::plus); continue }
            val r = CreatureLooks.check(t, d)
            val look = r.look
            if (look == null) { why.merge(r.why!!.replace(Regex("\\d+"), "N"), 1, Int::plus); continue }
            if (look.model !in files) { why.merge("model file not in ModelFileData (not shipped)", 1, Int::plus); continue }
            drawable++
            for (n in look.diagnostics) notes.merge(n.replace(Regex("\\d+"), "N").substringBefore(" ("), 1, Int::plus)
        }
        val report = buildString {
            appendLine("named creatures: ${creatures.size}")
            appendLine("drawable look: $drawable")
            appendLine("no look: ${creatures.size - drawable}")
            for ((k, v) in why) appendLine("  $v  $k")
            appendLine("diagnostics on drawable looks (a look can have several):")
            for ((k, v) in notes.entries.sortedByDescending { it.value }.take(25)) appendLine("  $v  $k")
        }
        println(report)
        System.getenv("WIFECRAFT_LOOKS")?.let(::File)?.let { out ->
            out.mkdirs()
            File(out, "counts.txt").writeText(report)
            val list = File(out, "displays.txt")
            if (!list.isFile) list.writeText(DEFAULTS.joinToString("\n") + "\n")
            for (line in list.readLines()) {
                val id = line.substringBefore('#').trim().toIntOrNull() ?: continue
                val look = assertNotNull(CreatureLooks.resolve(t, id), "display $id: ${CreatureLooks.check(t, id).why}")
                File(out, "$id.look").writeText(lookFile(id, look, CreatureLooks.call(t, look)))
            }
        }
        assertTrue(drawable > creatures.size * 9 / 10, report)
    }

    /** engine-test's look file (harness.cpp) for a call. */
    private fun lookFile(id: Int, look: ResolvedLook, c: DressCall) = buildString {
        appendLine("# display $id")
        look.diagnostics.forEach { appendLine("# $it") }
        appendLine("model ${c.model}")
        appendLine("scale ${c.scale}")
        appendLine("geo " + c.geosetKeys.indices.joinToString(",") { "${c.geosetKeys[it]}=${c.geosetValues[it]}" })
        for (i in c.layerFiles.indices) {
            val m = c.layerMeta.copyOfRange(i * Dressing.META, i * Dressing.META + Dressing.META)
            appendLine("layer ${m.joinToString(" ")} ${c.layerFiles[i]} 0x%08x 0x%08x".format(c.tintColours[2 * i], c.tintColours[2 * i + 1]))
        }
        for (i in 0 until c.extraModels.size / Dressing.EXTRA)
            appendLine("extra " + c.extraModels.copyOfRange(i * Dressing.EXTRA, i * Dressing.EXTRA + Dressing.EXTRA).joinToString(" "))
    }

    companion object {
        /**
         * A boar (Giant Boar), a wolf (Wolf), a murloc (Murloc Spearhunter), the old demo's Kaldorei Sentinel, a Stormwind
         * City Guard, a gnome (Gnomish Engineer), a Forsaken (Forsaken Deathguard) and a Dalaran guard (Kirin Tor Guardian).
         */
        val DEFAULTS = listOf(52696, 81061, 3619, 89902, 99391, 66546, 88839, 68480)
    }
}
