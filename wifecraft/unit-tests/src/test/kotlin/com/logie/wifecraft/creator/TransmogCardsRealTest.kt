package com.logie.wifecraft.creator

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Transmog window's cards on the game's own tables (WIFECRAFT_TABLES: wago.tools' CSVs with the item tables, ItemSparse,
 * ItemAppearance, ItemModifiedAppearance, ItemSet). Skipped without them. Each first page (Head, Shoulders, Chest, Legs, Feet,
 * Back, Hands, Waist, Wrist, Shirt, Tabard and the first Sets) for a human woman, a human man and an orc woman resolves as the
 * creator dresses her (LookResolver with the card's piece, Dressing/CreatureLooks.call); every piece's geosets, layers and
 * hung models are checked. With WIFECRAFT_TRANSMOG_CARDS set, each card is written there as an engine-test look file with a
 * harness script (cards.txt: `card LOOK W H LO HI AZ EL FIT CLEAR OUT.png`), for tools/creature_probe.py files and the harness.
 */
class TransmogCardsRealTest {
    private fun lookFile(c: DressCall) = buildString {
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

    @Test fun firstPagesResolveAndAreWritten() {
        val dir = System.getenv("WIFECRAFT_TABLES")?.let(::File)?.takeIf { File(it, "ItemSparse.csv").isFile && File(it, "ChrModel.csv").isFile }
        assumeTrue(dir != null, "WIFECRAFT_TABLES with the item tables isn't set")
        val t = CsvTables(dir!!)
        val c = Catalog.build(t)
        val tm = TransmogCatalog.load(t)
        val out = System.getenv("WIFECRAFT_TRANSMOG_CARDS")?.let(::File)?.also { it.mkdirs() }
        val perPage = System.getenv("WIFECRAFT_TRANSMOG_PAGE")?.toIntOrNull() ?: TransmogFrame.ITEMS.perPage
        val script = StringBuilder()
        val problems = ArrayList<String>()
        val who = listOf("human_f" to Appearance(1, Sex.FEMALE), "human_m" to Appearance(1, Sex.MALE), "orc_f" to Appearance(2, Sex.FEMALE))
        val slots = listOf(ClothingSlot.HEAD, ClothingSlot.SHOULDERS, ClothingSlot.TORSO, ClothingSlot.LEGS, ClothingSlot.FEET,
            ClothingSlot.BACK, ClothingSlot.GLOVES, ClothingSlot.WAIST, ClothingSlot.WRISTS, ClothingSlot.SHIRT, ClothingSlot.TABARD)
        // the first page as the window lists it, after the names that start with punctuation or digits (test items)
        fun page(slot: ClothingSlot) = TransmogLists.items(tm, slot, "", emptySet()).filter { it.name.first().isLetter() }.take(perPage)
        val sets = TransmogLists.sets(tm, "", emptySet()).filter { it.name.first().isLetter() }.take(TransmogFrame.SETS.perPage)
        for ((name, start) in who) {
            val base = CreatorModel(c, start).appearance
            val orders = ArrayList<Pair<String, CardOrder>>()
            for (slot in slots) page(slot).forEachIndexed { i, p ->
                orders += "${name}_${slot.name.lowercase()}_${"%02d".format(i)}" to TransmogCards.itemOrder(base, slot, p.display)
            }
            sets.forEachIndexed { i, s -> orders += "${name}_set_${"%02d".format(i)}" to TransmogCards.setOrder(base, s) }
            for ((label, o) in orders) {
                val look = LookResolver.resolve(c, t, o.look)
                val layout = Dressing.layout(c, t, o.look)
                val call = CreatureLooks.call(t, look, layout)
                // every worn piece must have drawn something: a geoset group, a texture layer or a model
                val pieces = o.look.clothing
                val bare = LookResolver.resolve(c, t, o.look.copy(clothing = emptyMap(), gear = false))
                val drew = look.geosetGroups != bare.geosetGroups || look.layers.size > bare.layers.size || look.extraModels.size > bare.extraModels.size
                if (!drew) problems += "$label ${pieces}: nothing drawn"
                look.diagnostics.filter { "item" in it }.forEach { problems += "$label: $it" }
                call.skipped.forEach { problems += "$label: skipped $it" }
                if (out != null) {
                    File(out, "$label.look").writeText(lookFile(call))
                    val s = o.shot
                    script.appendLine("card $out/$label.look ${o.width} ${o.height} ${s.lo} ${s.hi} ${s.az} ${s.el} ${if (s.fitWidth) 1 else 0} 1 $out/$label.png")
                }
            }
        }
        out?.let {
            File(it, "cards.txt").writeText("files ${System.getenv("WIFECRAFT_FILES") ?: "FILES"}\n" + script)
            File(it, "names.txt").writeText(slots.joinToString("\n") { s -> s.name + ": " + page(s).joinToString(" | ") { p -> "${p.name} (${p.display})" } } +
                "\nSETS: " + sets.joinToString(" | ") { s -> "${s.name} ${s.displays}" } + "\n")
            File(it, "problems.txt").writeText(problems.joinToString("\n"))
        }
        println(problems.take(60).joinToString("\n"))
        val nothing = problems.count { "nothing drawn" in it }
        assertTrue(nothing * 10 < who.size * (slots.size * perPage + sets.size), "too many cards draw nothing: $nothing\n" + problems.take(40).joinToString("\n"))
    }
}
