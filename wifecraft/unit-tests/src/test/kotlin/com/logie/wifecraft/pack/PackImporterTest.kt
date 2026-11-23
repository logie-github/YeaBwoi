package com.logie.wifecraft.pack

import com.logie.wifecraft.anim.AnimLibrary
import com.logie.wifecraft.props.PropStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.logie.wifecraft.pack.PackFixtures.PackFile
import com.logie.wifecraft.pack.PackFixtures.StageSpec

class PackImporterTest {
    private val bed = 15392049467256577701UL.toLong()
    private val toy = 12512225676731280366UL.toLong()
    private val cum = 17078270281272275333UL.toLong()

    private val anim = PackFixtures.animationPackage(
        StageSpec("Cowgirl", "VAGINAL", "DOUBLE_BED", listOf("T:a_x", "T:a_y")),
        StageSpec("Toy Time", "TEASING", "FLOOR", listOf("T:b_x", "T:b_y"), props = listOf(Triple(0, listOf(toy), "T:b_prop"))),
        StageSpec("Cuddle Pile (3P)", "TEASING", "SOFA", listOf("T:c_x", "T:c_y", "T:c_z")),
        StageSpec("Layered", "TEASING", "DOUBLE_BED", listOf("T:d_x"), props = listOf(Triple(0, listOf(cum), "T:d_prop"))),
    )
    private val toyPkg = PackFixtures.objectPackage(toy, 0x1000L, withTuning = StageSpec("Toy Solo", "VAGINAL", "FLOOR", listOf("T:t_x"), props = listOf(Triple(0, listOf(toy), "T:t_prop"))))
    private val layerPkg = PackFixtures.objectPackage(cum, 0x2000L)
    private val bedPkg = PackFixtures.objectPackage(bed, 0x3000L)

    private class Recorder(val result: HandlerResult = HandlerResult(EntryStatus.IMPORTED, "ok")) : PackHandler {
        val seen = ArrayList<String>()
        override fun handle(entry: PackEntry, file: File, content: PackContent?): HandlerResult {
            assertTrue(file.isFile, "the handler gets a real file")
            seen += entry.file
            return result
        }
    }

    private fun root(): File = Files.createTempDirectory("packroot").toFile()

    private fun fullPack(extra: Map<String, ByteArray> = emptyMap()) = PackFixtures.pack(listOf(
        PackFile("animations", "anim.package", anim),
        PackFile("props", "toy.package", toyPkg),
        PackFile("body-layer", "layer.package", layerPkg),
        PackFile("furniture", "bed.package", bedPkg),
        PackFile("script-info", "note.txt", "A script note\nline 2".toByteArray()),
        PackFile("unused", "menu.package", PackFixtures.dbpf(PackFixtures.Res(0x220557DA, 1, byteArrayOf(1)))),
    ), extra)

    private fun importer(root: File, rec: Map<PackKind, PackHandler>) = PackImporter(root, rec)

    @Test fun classifiesByContentNotByName() {
        val dir = root()
        fun content(b: ByteArray) = PackClassifier.read(File(dir, "x.package").also { it.writeBytes(b) })
        val a = content(anim); val t = content(toyPkg); val l = content(layerPkg); val b = content(bedPkg)
        val refs = a.propRefs + t.propRefs
        assertEquals(PackKind.ANIMATIONS, PackClassifier.kindOf(a, refs))
        assertEquals(PackKind.PROPS, PackClassifier.kindOf(t, refs))            // its own stage names its object
        assertEquals(PackKind.BODY_LAYER, PackClassifier.kindOf(l, refs))       // only another package's stage names it
        assertEquals(PackKind.FURNITURE, PackClassifier.kindOf(b, refs))        // no stage names it
        assertEquals(PackKind.UNUSED, PackClassifier.kindOf(content(PackFixtures.dbpf(PackFixtures.Res(0x220557DA, 1, byteArrayOf(1)))), refs))
        assertEquals(1, l.objects.size)
        assertEquals(4, a.stages.size)
    }

    @Test fun routesEveryEntryToItsKindsHandlerAndReportsEachLine() {
        val recs = PackKind.values().associateWith { Recorder() }
        val r = importer(root(), recs).import({ fullPack().inputStream() })
        assertNull(r.problem)
        assertEquals(6, r.results.size)
        assertEquals(listOf("anim.package"), recs.getValue(PackKind.ANIMATIONS).seen)
        assertEquals(listOf("toy.package"), recs.getValue(PackKind.PROPS).seen)
        assertEquals(listOf("layer.package"), recs.getValue(PackKind.BODY_LAYER).seen)
        assertEquals(listOf("bed.package"), recs.getValue(PackKind.FURNITURE).seen)
        assertEquals(listOf("note.txt"), recs.getValue(PackKind.SCRIPT_INFO).seen)
        assertTrue(recs.getValue(PackKind.UNUSED).seen.isEmpty())
        assertEquals(EntryStatus.SKIPPED, r.results.last().status)
        assertTrue("kept in the pack" in r.results.last().detail)
        assertTrue(r.ok)
    }

    @Test fun theContentDecidesWhenTheManifestIsWrong() {
        // the bed filed as props, the toy filed as furniture: routed by what they hold
        val z = PackFixtures.pack(listOf(PackFile("props", "bed.package", bedPkg), PackFile("furniture", "toy.package", toyPkg), PackFile("animations", "anim.package", anim)))
        val recs = PackKind.values().associateWith { Recorder() }
        val r = importer(root(), recs).import({ z.inputStream() })
        assertEquals(listOf("bed.package"), recs.getValue(PackKind.FURNITURE).seen)
        assertEquals(listOf("toy.package"), recs.getValue(PackKind.PROPS).seen)
        val bedLine = r.results.first { it.entry.file == "bed.package" }
        assertEquals(PackKind.FURNITURE, bedLine.routedAs)
        assertTrue("the manifest says props" in bedLine.note, bedLine.note)
    }

    @Test fun keepsObjectPackagesInTheStoreAndNotTheAnimationOnes() {
        val d = root()
        val imp = importer(d, PackKind.values().associateWith { Recorder() })
        imp.import({ fullPack().inputStream() })
        assertTrue(File(imp.storeDir(PackKind.PROPS), "toy.package").isFile)
        assertTrue(File(imp.storeDir(PackKind.FURNITURE), "bed.package").isFile)
        assertTrue(File(imp.storeDir(PackKind.BODY_LAYER), "layer.package").isFile)
        assertFalse(File(imp.storeDir(PackKind.ANIMATIONS), "anim.package").exists())
        assertFalse(File(d, "staging").exists(), "staging is emptied")
    }

    @Test fun aDamagedEntryFailsAloneAndTheRestCarriesOn() {
        // the manifest's digest for the toy is wrong
        val z = PackFixtures.pack(listOf(PackFile("animations", "anim.package", anim), PackFile("props", "toy.package", toyPkg)),
            tamper = { it.replace(PackFixtures.sha256(toyPkg), "0".repeat(64)) })
        val recs = PackKind.values().associateWith { Recorder() }
        val r = importer(root(), recs).import({ z.inputStream() })
        assertEquals(EntryStatus.IMPORTED, r.results[0].status)
        assertEquals(EntryStatus.FAILED, r.results[1].status)
        assertTrue("checksum" in r.results[1].detail, r.results[1].detail)
        assertTrue(recs.getValue(PackKind.PROPS).seen.isEmpty())
        assertFalse(r.ok)
    }

    @Test fun aHandlerThatThrowsFailsOnlyItsEntry() {
        val boom = PackHandler { _, _, _ -> throw IllegalStateException("kaput") }
        val recs = PackKind.values().associateWith<PackKind, PackHandler> { Recorder() } + (PackKind.PROPS to boom)
        val r = importer(root(), recs).import({ fullPack().inputStream() })
        assertEquals(EntryStatus.FAILED, r.results.first { it.routedAs == PackKind.PROPS }.status)
        assertEquals(EntryStatus.IMPORTED, r.results.first { it.routedAs == PackKind.FURNITURE }.status)
    }

    @Test fun refusesAZipThatIsNotAPackOrIsUnsafe() {
        val d = root()
        assertTrue("no pack/manifest.json" in importer(d, emptyMap()).import({ PackFixtures.zip("pack/a.package" to byteArrayOf(1)).inputStream() }).problem!!)
        val slip = importer(d, emptyMap()).import({ PackFixtures.zip("../evil" to byteArrayOf(1)).inputStream() })
        assertTrue("refused" in slip.problem!!)
        val bad = importer(d, emptyMap()).import({ PackFixtures.zip("pack/manifest.json" to "{}".toByteArray()).inputStream() })
        assertNotNull(bad.problem)
        assertTrue(bad.results.isEmpty())
    }

    @Test fun scriptsInThePackAreIgnoredNotRun() {
        val recs = PackKind.values().associateWith { Recorder() }
        val r = importer(root(), recs).import({ fullPack(mapOf("pack/info/Mod.ts4script" to byteArrayOf(1, 2, 3), "pack/evil.py" to byteArrayOf(4))).inputStream() })
        assertTrue(r.ignored.any { it.first == "pack/info/Mod.ts4script" })
        assertTrue(r.ignored.any { it.first == "pack/evil.py" })
        assertEquals(6, r.results.size)
    }

    @Test fun resumesAndNeverDoesAnEntryTwice() {
        val d = root()
        val first = PackKind.values().associateWith { Recorder() }
        val pack = fullPack()
        importer(d, first).import({ pack.inputStream() })
        val second = PackKind.values().associateWith { Recorder() }
        val r = importer(d, second).import({ pack.inputStream() })
        assertTrue(second.values.all { it.seen.isEmpty() }, "nothing is imported twice")
        assertEquals(6, r.count(EntryStatus.ALREADY))
        // force does them all again
        val third = PackKind.values().associateWith { Recorder() }
        importer(d, third).import({ pack.inputStream() }, force = true)
        assertEquals(1, third.getValue(PackKind.PROPS).seen.size)
    }

    @Test fun anInterruptedImportGoesOnFromTheNextEntry() {
        val d = root()
        val pack = fullPack()
        var calls = 0
        val dying = PackHandler { _, _, _ -> if (++calls == 1) throw java.lang.InterruptedException("power") else HandlerResult(EntryStatus.IMPORTED, "ok") }
        runCatching { importer(d, mapOf(PackKind.ANIMATIONS to Recorder(), PackKind.PROPS to dying, PackKind.FURNITURE to Recorder(), PackKind.BODY_LAYER to Recorder(), PackKind.SCRIPT_INFO to Recorder())).import({ pack.inputStream() }) }
        val rec = PackKind.values().associateWith { Recorder() }
        importer(d, rec).import({ pack.inputStream() })
        assertTrue(rec.getValue(PackKind.ANIMATIONS).seen.isEmpty(), "the animation entry finished before the stop")
        assertEquals(listOf("toy.package"), rec.getValue(PackKind.PROPS).seen, "the stopped entry is done again")
    }

    @Test fun theObjectHandlerLoadsPropsIntoTheStoreAndReportsThem() {
        val d = root()
        val store = PropStore(File(d, "props"))
        val handlers = mapOf(PackKind.PROPS to ObjectHandler(store, PackKind.PROPS), PackKind.BODY_LAYER to ObjectHandler(store, PackKind.BODY_LAYER),
            PackKind.FURNITURE to ObjectHandler(store, PackKind.FURNITURE))
        val r = importer(File(d, "pack"), handlers).import({ fullPack().inputStream() })
        val props = r.results.first { it.routedAs == PackKind.PROPS }
        assertEquals(EntryStatus.IMPORTED, props.status, props.detail)
        assertTrue("1 props loaded" in props.detail, props.detail)
        assertTrue(store.has(toy) && store.has(cum) && store.has(bed))
        val m = store.model(toy)!!
        assertEquals(4, m.vertexCount)
        assertEquals(2, m.triangleCount)
        assertEquals(1, m.textures.size)
        assertEquals(listOf(PackKind.PROPS, PackKind.BODY_LAYER, PackKind.FURNITURE).toSet(), store.index().map { it.kind }.toSet())
        // the stage's prop use is linked by its actors' clips
        val uses = store.usesFor(listOf("t:t_x"))
        assertEquals(1, uses.size)
        assertEquals(listOf(toy), uses[0].guids)
    }

    @Test fun theAnimationHandlerSaysWhatToDoWithoutASkeleton() {
        val d = root()
        val lib = AnimLibrary(File(d, "anims"))
        val store = PropStore(File(d, "props"))
        val h = AnimationHandler(lib, { null }, { error("no rig") }, File(d, "tmp"), store)
        val r = importer(File(d, "pack"), mapOf(PackKind.ANIMATIONS to h)).import({ PackFixtures.pack(listOf(PackFile("animations", "anim.package", anim))).inputStream() })
        val line = r.results.single()
        assertEquals(EntryStatus.SKIPPED, line.status)
        assertTrue("Sims 4 skeleton" in line.detail && "import the pack again" in line.detail, line.detail)
        // its props' links were read all the same
        assertEquals(1, store.usesFor(listOf("t:b_x", "t:b_y")).size)
    }
}
