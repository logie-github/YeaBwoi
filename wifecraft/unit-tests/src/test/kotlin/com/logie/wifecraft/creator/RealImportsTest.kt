package com.logie.wifecraft.creator

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The user's own packages, imported as the app imports them (never in the repository: given by path, skipped without):
 *  - WIFECRAFT_BODY_ZIP: Wrath++'s body options (pack2.py's wrathpp-body.zip);
 *  - WIFECRAFT_UNDEAD_ZIP: Wrath++'s undead skins (undead_pack.py's wrathpp-undead-skins.zip);
 *  - WIFECRAFT_TABLES (optional): the game's tables (CSV) for the creator's rows and the resolver;
 *  - WIFECRAFT_IMPORT_OUT (optional): where to write look files for engine-test (`harness --look`) and the files they name.
 * What each finds is printed (the counts the report gives).
 */
class RealImportsTest {
    private val names = MapNames(emptyMap())
    private fun tmp(): File = Files.createTempDirectory("real").toFile()
    private fun tables(): CsvTables? = System.getenv("WIFECRAFT_TABLES")?.let(::File)?.takeIf { File(it, "ChrModel.csv").isFile }?.let(::CsvTables)
    private val out: File? get() = System.getenv("WIFECRAFT_IMPORT_OUT")?.let { File(it).apply { mkdirs() } }

    /** A look file as engine-test's harness reads it (`--look`), from the arrays the app hands Native.dress. */
    private fun lookFile(call: DressCall, body: BodyCall?, morphFile: File?): String {
        val sb = StringBuilder("model ${call.model}\nscale ${call.scale}\n")
        sb.append("geo " + call.geosetKeys.indices.joinToString(",") { "${call.geosetKeys[it]}=${call.geosetValues[it]}" } + "\n")
        for (i in call.layerFiles.indices) sb.append("layer ${call.layerMeta.copyOfRange(i * Dressing.META, i * Dressing.META + Dressing.META).joinToString(" ")} ${call.layerFiles[i]}\n")
        for (i in 0 until call.extraModels.size / 4) sb.append("extra ${call.extraModels.copyOfRange(i * 4, i * 4 + 4).joinToString(" ")}\n")
        if (body != null && (body.k != 0f || body.morph != null)) sb.append("body ${body.k} ${body.fold}" + (morphFile?.let { " ${it.path}" } ?: "") + "\n")
        call.skipped.forEach { sb.append("# skipped: $it\n") }
        return sb.toString()
    }

    private fun floats(m: FloatArray, f: File) {
        val b = ByteBuffer.allocate(m.size * 4).order(ByteOrder.LITTLE_ENDIAN); m.forEach { b.putFloat(it) }; f.writeBytes(b.array())
    }

    @Test fun theBodyOptionsPackage() {
        val zip = System.getenv("WIFECRAFT_BODY_ZIP") ?: return
        val root = tmp()
        val store = ImportStore(root, names)
        val st = store.importZip(ImportKind.BODY, File(zip).inputStream(), File(zip).name)
        val r = StringBuilder("body: ${ZipFile(zip).size()} entries, ${st.files} files kept\n")
        val summary = assertNotNull(store.bodySummary())
        r.append("races with shapes: ${summary.shapes}; with designs: ${summary.layers}\n")
        assertEquals(setOf(1), summary.shapes)
        assertEquals(BodyOptions.RACES, summary.layers)
        // every race: Style 1..16 x Density 1..4, one design each (the alliance's or the horde's 16th)
        for (race in BodyOptions.RACES) for (s in 1..16) for (d in 1..4) assertNotNull(store.bodyLayer(race, s, d), "$race $s $d")
        val designs = BodyOptions.RACES.flatMap { race -> (1..16).flatMap { s -> (1..4).map { d -> store.bodyLayer(race, s, d)!! } } }.toSet()
        r.append("designs: ${designs.size} files for ${BodyOptions.RACES.size * 64} race/style/density\n")
        assertEquals(17 * 4, designs.size)                                      // 15 shared and each faction's 16th: 17 designs x 4 densities
        // As drawn's model of every race reads; the Human's three shapes move her
        for (race in BodyOptions.RACES) {
            val m0 = store.bodyModel(race, 0); assertNull(m0)                  // shape 0 is the race's own: no model asked for
            for (shape in 1..3) if (race != 1) assertNull(store.bodyModel(race, shape))
        }
        val meta = org.json.JSONObject(File(root, "imports/wrathpp-body/meta.json").readText())
        val models = meta.getJSONArray("models")
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val mesh = BodyShapes.readPatch(File(root, "game/${m.getInt("model")}").readBytes())
            val own = BodyShapes.bodyCount(File(root, "game/${m.getInt("skin")}").readBytes(), mesh.count)
            r.append("race ${m.getInt("race_id")} shape ${m.getInt("shape")}: ${mesh.count} vertices, ${own} before the layer's\n")
            assertTrue(own in 1..mesh.count)
        }
        for (shape in 1..3) {
            val morph = assertNotNull(BodyShapes.load(root, 1, shape))
            r.append("Human shape $shape: ${morph.size / BodyShapes.STRIDE} records, ${BodyShapes.moved(morph)} moved\n")
            assertTrue(BodyShapes.moved(morph) > 1000)
        }
        // the creator's rows and what reaches the renderer, with the game's tables
        val t = tables()
        if (t != null) {
            val catalog = Catalog.build(t)
            val m = CreatorModel(catalog, Appearance(1, Sex.FEMALE)).also { it.bodySource = store }
            val tab = m.categories().last { it.name == "Body" }
            val rows = m.rows(tab.id).filter { it.option.id in setOf(BodyOptions.SHAPE, BodyOptions.STYLE, BodyOptions.DENSITY) }
            assertEquals(listOf(true, true, true), rows.map { it.enabled })
            m.choose(BodyOptions.SHAPE, 2); m.choose(BodyOptions.STYLE, 4); m.choose(BodyOptions.DENSITY, 4)
            val look = m.resolved(t, store)
            val design = store.bodyLayer(1, 4, 4)!!
            assertTrue(look.layers.any { it.fileId == design && it.layer == LookResolver.BODY_LAYER })
            val call = Dressing.call(t, Dressing.layout(catalog, t, m.appearance), look)
            assertTrue(design in call.layerFiles.toList(), "${call.skipped}")
            val body = Dressing.body(root, m.appearance, store.hasBody())
            assertNotNull(body.morph); assertEquals(BodyShapes.frame(1)!!.k, body.k)
            r.append("Human woman Curvy, Style 4 (heart), 100%: design $design at layer ${LookResolver.BODY_LAYER}, morph ${body.morph!!.size / BodyShapes.STRIDE} records, k ${body.k}\n")
            val orc = CreatorModel(catalog, Appearance(2, Sex.FEMALE)).also { it.bodySource = store }
            val orcRows = orc.rows(orc.categories().last { it.name == "Body" }.id).filter { it.option.id < 0 && it.option.id != CreatorModel.GEAR }
            r.append("Orc woman's body rows: ${orcRows.map { "${it.option.name}=${it.enabled}/${it.why}" }}\n")
            assertEquals(listOf(false, true, true), orcRows.map { it.enabled })
            out?.let { o ->
                val mf = File(o, "human_curvy.morph"); floats(body.morph!!, mf)
                File(o, "human_curvy_heart.look").writeText(lookFile(call, body, mf))
                val plain = Dressing.call(t, Dressing.layout(catalog, t, Appearance(1, Sex.FEMALE)), LookResolver.resolve(catalog, t, Appearance(1, Sex.FEMALE), store))
                File(o, "human_plain.look").writeText(lookFile(plain, null, null))
                File(root, "game").listFiles()!!.forEach { it.copyTo(File(o, "files/${it.name}"), overwrite = true) }
                File(o, "needs_body.txt").writeText((look.needsFiles + Dressing.models(look)).joinToString("\n") + "\n")
            }
        }
        print(r)
    }

    @Test fun theUndeadSkinsPackage() {
        val zip = System.getenv("WIFECRAFT_UNDEAD_ZIP") ?: return
        val root = tmp()
        val store = ImportStore(root, names)
        val st = store.importZip(ImportKind.UNDEAD, File(zip).inputStream(), File(zip).name)
        val sum = assertNotNull(store.undeadSummary())
        val r = StringBuilder("undead: ${ZipFile(zip).size()} entries, ${st.files} files kept, ${sum.skins} skins for ${sum.models.size} races and sexes: ${sum.models}\n")
        for (key in sum.models) {
            val (race, sex) = key.split('_').let { it[0].toInt() to if (it[1] == "1") Sex.FEMALE else Sex.MALE }
            val skins = store.undeadSkins(race, sex)
            val head = assertNotNull(UndeadSkins.head(race, sex), key)
            for (k in skins) {
                val body = UndeadSkins.size(File(root, "game/${k.skin}").readBytes())
                val face = UndeadSkins.size(File(root, "game/${k.face}").readBytes())
                assertEquals(512 to 512, body)
                assertEquals(if (head.rotate) 128 to 256 else 256 to 128, face, key)
            }
            r.append("$key ${skins.map { it.name }} underwear ${store.undeadUnderwear(race, sex)}, head ${if (head.rotate) "turned" else "as is"}\n")
        }
        val t = tables()
        if (t != null) {
            val catalog = Catalog.build(t)
            for (key in sum.models) {
                val (race, sex) = key.split('_').let { it[0].toInt() to if (it[1] == "1") Sex.FEMALE else Sex.MALE }
                val m = CreatorModel(catalog, Appearance(race, sex)).also { it.bodySource = store }
                val cat = m.categories().first { it.id == m.undeadCategory() }
                val row = m.rows(cat.id).single { it.option.id == CreatorModel.UNDEAD }
                assertTrue(row.enabled)
                m.choose(CreatorModel.UNDEAD, 1)
                val skinRow = m.categories().flatMap { m.rows(it.id) }.single { it.option.id == CreatorModel.UNDEAD_SKIN }
                assertEquals(store.undeadSkins(race, sex).map { it.name }, skinRow.option.choices.map { it.name })
                assertTrue(m.categories().flatMap { m.rows(it.id) }.none { it.option.name == CreatorModel.FACE })
                val look = m.resolved(t, store)
                assertEquals(emptyList(), look.diagnostics.filter { it.startsWith("Undead") }, key)
                val call = Dressing.call(t, Dressing.layout(catalog, t, m.appearance), look)
                val k = store.undeadSkins(race, sex)[0]
                assertTrue(k.skin in call.layerFiles.toList() && k.face in call.layerFiles.toList(), "$key ${call.skipped}")
                r.append("$key: Undead under ${cat.name}; on: ${call.layerFiles.size} layers of the skin\n")
            }
            out?.let { o ->
                val be = Appearance(10, Sex.FEMALE)
                for ((name, a) in listOf("be_living" to be, "be_undead0" to be.copy(undead = true, undeadSkin = 0), "be_undead1" to be.copy(undead = true, undeadSkin = 1))) {
                    val look = LookResolver.resolve(catalog, t, a, store)
                    File(o, "$name.look").writeText(lookFile(Dressing.call(t, Dressing.layout(catalog, t, a), look), null, null))
                    File(o, "needs_$name.txt").writeText((look.needsFiles + Dressing.models(look)).joinToString("\n") + "\n")
                    r.append("$name: ${look.layers.map { "${it.textureType}/${it.layer}:${it.fileId}${it.rect?.let { r -> "@$r" } ?: ""}" }}\n")
                }
                for ((name, a) in listOf("orcf_living" to Appearance(2, Sex.FEMALE), "orcf_undead" to Appearance(2, Sex.FEMALE, undead = true))) {
                    val look = LookResolver.resolve(catalog, t, a, store)
                    File(o, "$name.look").writeText(lookFile(Dressing.call(t, Dressing.layout(catalog, t, a), look), null, null))
                    File(o, "needs_$name.txt").writeText((look.needsFiles + Dressing.models(look)).joinToString("\n") + "\n")
                }
                File(root, "game").listFiles()!!.forEach { it.copyTo(File(o, "files/${it.name}"), overwrite = true) }
            }
        }
        print(r)
    }
}
