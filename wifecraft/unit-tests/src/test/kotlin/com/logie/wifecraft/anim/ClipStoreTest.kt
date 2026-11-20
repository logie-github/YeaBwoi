package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsFixtures
import com.logie.wifecraft.sims.SimsTrack
import com.logie.wifecraft.story.Vec3
import com.logie.wifecraft.ui.AnimStore
import com.logie.wifecraft.ui.ReviewDraft
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.*

/** The clip file (ClipCodec), keyframe reduction, the lazy library (index, LRU), the old files' migration, streaming import and free space. */
class ClipStoreTest {
    private fun tmp() = Files.createTempDirectory("clipstore").toFile()

    /** A moving clip: [bones] rotation tracks turning smoothly (a few degrees to tens), a Root position track, 30 fps. */
    private fun moving(name: String, source: String, frames: Int = 120, bones: Int = 12, seed: Int = 1, loop: Boolean = false): Anim {
        val rnd = Random(seed)
        val times = FloatArray(frames) { it / 30f }
        val tracks = ArrayList<BoneTrack>()
        for (b in 0 until bones) {
            val ax = doubleArrayOf(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0))
            val len = sqrt(ax.sumOf { it * it }); for (i in 0..2) ax[i] /= len
            val amp = rnd.nextDouble(0.05, 0.8); val freq = rnd.nextDouble(0.3, 2.0)
            val v = FloatArray(frames * 4)
            for (k in 0 until frames) {
                val phase = if (loop) 2 * PI * k / (frames - 1) else times[k] * freq * 2 * PI
                val a = amp * sin(phase)
                val s = sin(a / 2)
                v[k * 4] = (ax[0] * s).toFloat(); v[k * 4 + 1] = (ax[1] * s).toFloat(); v[k * 4 + 2] = (ax[2] * s).toFloat(); v[k * 4 + 3] = cos(a / 2).toFloat()
            }
            tracks += BoneTrack(BodyGeometry.JOINTS[(b + 1) % BodyGeometry.JOINTS.size], Channel.ROTATION, times.copyOf(), v)
        }
        val p = FloatArray(frames * 3)
        for (k in 0 until frames) {
            val phase = if (loop) 2 * PI * k / (frames - 1) else times[k] * 1.3
            p[k * 3] = (0.4 * sin(phase)).toFloat(); p[k * 3 + 1] = (0.1 * cos(phase)).toFloat(); p[k * 3 + 2] = (0.05 * sin(2 * phase)).toFloat()
        }
        tracks += BoneTrack("Root", Channel.POSITION, times.copyOf(), p)
        return Anim("", name, "Tests", (frames - 1) / 30f, loop, tracks, source, 5L, conversionRevision = Retargeter.CONVERSION_REVISION)
    }

    private fun angleDeg(a: FloatArray, i: Int, b: FloatArray, j: Int): Double {
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (k in 0 until 4) { d += a[i + k].toDouble() * b[j + k]; na += a[i + k].toDouble() * a[i + k]; nb += b[j + k].toDouble() * b[j + k] }
        return Math.toDegrees(2 * acos(min(1.0, abs(d) / sqrt(na * nb))))
    }

    /** The decoded track's value at [t], as the engine plays it (slerp, lerp). */
    private fun sample(t: BoneTrack, time: Float): FloatArray {
        val per = if (t.channel == Channel.ROTATION) 4 else 3
        var a = 0
        while (a + 1 < t.times.size && t.times[a + 1] <= time) a++
        val b = min(a + 1, t.times.size - 1)
        val span = t.times[b] - t.times[a]
        val f = if (span > 0) ((time - t.times[a]) / span).coerceIn(0f, 1f).toDouble() else 0.0
        if (per == 3) return FloatArray(3) { (t.values[a * 3 + it] + (t.values[b * 3 + it] - t.values[a * 3 + it]) * f).toFloat() }
        val qa = DoubleArray(4) { t.values[a * 4 + it].toDouble() }; val qb = DoubleArray(4) { t.values[b * 4 + it].toDouble() }
        var d = (0 until 4).sumOf { qa[it] * qb[it] }
        if (d < 0) { for (i in 0 until 4) qb[i] = -qb[i]; d = -d }
        val out = if (d > 0.9995) DoubleArray(4) { qa[it] * (1 - f) + qb[it] * f } else {
            val th = acos(min(1.0, d)); val s = sin(th)
            DoubleArray(4) { qa[it] * sin((1 - f) * th) / s + qb[it] * sin(f * th) / s }
        }
        val len = sqrt(out.sumOf { it * it })
        return FloatArray(4) { (out[it] / len).toFloat() }
    }

    /** Worst rotation (degrees) and position (model units) error of [after] against every key of [before]. */
    private fun worst(before: List<BoneTrack>, after: List<BoneTrack>): Pair<Double, Double> {
        var rot = 0.0; var pos = 0.0
        for ((x, y) in before.zip(after)) {
            assertEquals(x.bone, y.bone); assertEquals(x.channel, y.channel)
            assertEquals(x.times.first(), y.times.first(), 1e-6f); assertEquals(x.times.last(), y.times.last(), 1e-6f)
            for ((k, time) in x.times.withIndex()) {
                val v = sample(y, time)
                if (x.channel == Channel.ROTATION) rot = maxOf(rot, angleDeg(x.values, k * 4, v, 0))
                else pos = maxOf(pos, sqrt((0 until 3).sumOf { i -> (x.values[k * 3 + i] - v[i]).toDouble().let { it * it } }))
            }
        }
        return rot to pos
    }

    @Test fun codecRoundTripStaysWithinTheBoundsAndIsMuchSmallerThanJson() {
        for (seed in 1..5) {
            val a = moving("Wave", "h:$seed", frames = 300, seed = seed)
            val enc = ClipCodec.encode(JSONObject().put("anim", ClipStore.meta(a)), a.tracks)
            val back = ClipCodec.decode(enc.bytes)
            val (rot, pos) = worst(a.tracks, back)
            assertTrue(rot <= ClipCodec.ROTATION_TOLERANCE_DEG + 0.01, "rotation error $rot")
            assertTrue(pos <= ClipCodec.POSITION_TOLERANCE * 1.05, "position error $pos")
            val json = AnimJson.toJson(a).toString().length
            assertTrue(enc.bytes.size * 10 < json, "${enc.bytes.size} bytes against $json of JSON")
            // decoded keys sit exactly on the 1/30 s grid they came from
            assertTrue(back.all { t -> t.times.all { abs(it * 30 - Math.round(it * 30)) < 1e-4 } })
        }
    }

    @Test fun reductionKeepsTheEndsAndLoopsCloseAndAStillTrackIsTwoKeys() {
        val loop = moving("Loop", "h:l", frames = 91, loop = true)
        val reduced = loop.tracks.map { KeyReduction.reduce(it) }
        for ((x, y) in loop.tracks.zip(reduced)) {
            assertEquals(x.times.last(), y.times.last())
            val per = if (x.channel == Channel.ROTATION) 4 else 3
            for (i in 0 until per) {
                assertEquals(x.values[i], y.values[i])
                assertEquals(x.values[x.values.size - per + i], y.values[y.values.size - per + i])
            }
        }
        val still = BoneTrack("Head", Channel.ROTATION, FloatArray(200) { it / 30f }, FloatArray(800) { if (it % 4 == 3) 1f else 0f })
        assertEquals(2, KeyReduction.reduce(still).times.size)
        assertTrue(reduced.sumOf { it.times.size } < loop.tracks.sumOf { it.times.size })
    }

    @Test fun oddValuesAreKeptRawAndDamageIsRefused() {
        val odd = Anim("x", "Odd", "", 1f, false, listOf(
            BoneTrack("Head", Channel.ROTATION, floatArrayOf(0f, 0.0123f), floatArrayOf(0f, 0f, 0f, 2f, 0.1f, 0.2f, 0.3f, 0.9f)),   // not unit length
            BoneTrack("Root", Channel.POSITION, floatArrayOf(0f), floatArrayOf(1e7f, -3f, 0.5f)),
        ), "", 0L)
        val enc = ClipCodec.encode(JSONObject(), odd.tracks)
        val back = ClipCodec.decode(enc.bytes)
        assertContentEquals(odd.tracks[0].values, back[0].values)
        assertContentEquals(odd.tracks[0].times, back[0].times)
        assertContentEquals(odd.tracks[1].values, back[1].values)
        val bad = enc.bytes.copyOf(); bad[bad.size - 3] = (bad[bad.size - 3] + 1).toByte()
        assertFailsWith<IOException> { ClipCodec.decode(bad) }
        val newer = enc.bytes.copyOf(); newer[4] = 9
        assertFailsWith<IOException> { ClipCodec.decode(newer) }
    }

    @Test fun theLibraryReadsTracksOnlyWhenPlayedAndKeepsEightDecoded() {
        val d = tmp()
        val lib = AnimLibrary(d)
        lib.batch { repeat(20) { lib.add(moving("Clip $it", "h:$it", seed = it)) } }
        assertTrue(File(d, "clips").listFiles()!!.count { it.name.endsWith(".wclip") } == 20)
        AnimLibrary.forgetCaches()
        val again = AnimLibrary(d)
        val reads = ClipCache.reads
        val all = again.list()
        assertEquals(20, all.size)
        // listing, sorting, the inspection, sizes and the stale count need no clip
        val checked = all.map { CheckedAnimation(it) }
        assertTrue(checked.all { it.inspection.type == ClipType.ONE_SHOT })
        assertEquals(13, all.first().tracks.size)
        assertEquals(0, again.stale())
        assertEquals(reads, ClipCache.reads)
        // played: read once, then from the cache; at most eight held
        val first = all.first().tracks.first()
        assertEquals(reads + 1, ClipCache.reads)
        assertEquals(first, all.first().tracks.first()); assertEquals(reads + 1, ClipCache.reads)
        for (a in all) a.tracks.forEach { it.times.size }
        assertTrue(ClipCache.held() <= ClipCache.SIZE)
        // index edits touch no clip file
        val f = (all[3].tracks as StoredTracks).file
        val stamp = f.lastModified() to f.length()
        again.rename(all[3].id, "Renamed")
        again.setCategory(all[3].id, "Elsewhere")
        assertEquals(stamp, f.lastModified() to f.length())
        AnimLibrary.forgetCaches()
        val third = AnimLibrary(d)
        assertEquals("Renamed", third.get(all[3].id)!!.name)
        assertEquals("Elsewhere", third.get(all[3].id)!!.category)
        third.delete(all[3].id)
        assertFalse(f.exists())
    }

    @Test fun aLostIndexIsRebuiltFromTheClipFiles() {
        val d = tmp()
        val lib = AnimLibrary(d)
        val a = lib.add(moving("One", "h:1"))
        val p = lib.addPair(PairAnim("", "Two", "Partner", moving("pa", "h:a"), moving("pb", "h:b", seed = 3),
            PairOffset(0.5f, 0f, 0f, 180f), PairOffset.ZERO, true, setOf("BED"), emptyList(), source = "h:a+h:b", importedAt = 2L))
        File(d, AnimLibrary.INDEX).delete()
        AnimLibrary.forgetCaches()
        val again = AnimLibrary(d)
        assertEquals("One", again.get(a.id)!!.name)
        val back = again.getPair(p.id)!!
        assertEquals("Two", back.name); assertEquals(180f, back.offsetA.yawDeg); assertEquals(setOf("BED"), back.locations)
        assertEquals(p.a.tracks.size, back.a.tracks.size)
        assertTrue(again.damaged.isEmpty() && again.damagedPairs.isEmpty())
    }

    @Test fun oldJsonFilesKeepLoadingAndAreMigratedWhenReadOrCompacted() {
        val d = tmp(); d.mkdirs()
        val old = moving("Old", "h:old").copy(id = "old1", conversionRevision = 3)
        File(d, "old1.json").writeText(AnimJson.toJson(old).toString())
        val oldPair = PairAnim("p1", "Old pair", "Partner", moving("a", "h:pa").copy(conversionRevision = 3), moving("b", "h:pb", seed = 2).copy(conversionRevision = 3),
            PairOffset.ZERO, PairOffset(1f, 0f, 0f, 180f), false, emptySet(), listOf(SoundEvent(0.5f, "kiss", 1)), source = "h:pa+h:pb", importedAt = 1L)
        File(d, "pair_p1.json").writeText(PairJson.toJson(oldPair).toString())
        File(d, "index.json").writeText("{\"v\":1,\"anims\":[{\"id\":\"old1\",\"name\":\"Old\",\"category\":\"Tests\"}]}")   // the old index form
        AnimLibrary.forgetCaches()
        val lib = AnimLibrary(d)
        assertEquals(listOf("Old"), lib.list().map { it.name })
        assertEquals(2, lib.legacyCount())
        assertEquals(2, lib.stale())   // the stale-conversion flag still counts them
        // read when played: converted there and then
        val tracks = lib.get("old1")!!.tracks
        assertEquals(old.tracks.size, tracks.size)
        tracks.first()
        assertFalse(File(d, "old1.json").exists())
        assertTrue(File(d, "clips/old1.wclip").isFile)
        assertEquals(1, lib.legacyCount())
        // the rest by Compact library, with its progress
        val seen = ArrayList<Pair<Int, Int>>()
        assertEquals(1, lib.compact { done, total -> seen += done to total })
        assertEquals(listOf(1 to 1), seen)
        assertEquals(0, lib.legacyCount())
        assertFalse(File(d, "pair_p1.json").exists())
        AnimLibrary.forgetCaches()
        val again = AnimLibrary(d)
        val p = again.getPair("p1")!!
        assertEquals(listOf(SoundEvent(0.5f, "kiss", 1)), p.sounds)
        assertEquals(3, p.a.conversionRevision)
        val (rot, pos) = worst(oldPair.b.tracks, p.b.tracks.toList())
        assertTrue(rot < 0.3 && pos < 0.001)
        assertEquals(2, again.stale())
        // the re-import replaces them as before (names kept)
        val up = again.addResult(old.copy(id = "", name = "New name", conversionRevision = Retargeter.CONVERSION_REVISION))
        assertTrue(up.updated); assertEquals("Old", up.anim.name)
    }

    @Test fun reviewFilterKeepsTwoPersonEntriesWithTheirPartnersAndALimit() {
        fun light(name: String, source: String, perf: List<ImportedPerformance> = emptyList()) =
            Anim("", name, "", 1f, false, emptyList(), source, 0L, performances = perf)
        val duo = ImportedPerformance("e1", "Duo", false, listOf(PerformanceRole("s:a", Vec3(0f, 0f, 0f), 0f), PerformanceRole("s:b", Vec3(1f, 0f, 0f), 0f)))
        val d = ReviewDraft(listOf(light("Solo", "s:solo"), light("A", "s:a", listOf(duo)), light("Other", "s:o"), light("B", "s:b", listOf(duo))))
        assertEquals(2, d.twoPersonCount)
        d.filter(twoPersonOnly = true, limit = null)
        assertEquals(listOf("A", "B"), d.items.filter { it.include }.map { it.anim.name })
        d.filter(twoPersonOnly = false, limit = 2)
        assertEquals(listOf("Solo", "A", "B"), d.items.filter { it.include }.map { it.anim.name })   // A's partner never left behind
        d.filter(twoPersonOnly = false, limit = null)
        assertEquals(4, d.chosen)
    }

    @Test fun freeSpaceStopsAnImportAndKeepsWhatCameBefore() {
        assertEquals(ImportSpace.Verdict.REFUSE, ImportSpace.verdict(0, ImportSpace.RESERVE))
        assertNotNull(ImportSpace.refusal(ImportSpace.RESERVE + 1))
        assertNull(ImportSpace.refusal(ImportSpace.RESERVE + ImportSpace.MIN_ROOM + 1))
        assertEquals(ImportSpace.Verdict.SUBSET, ImportSpace.verdict(1L shl 30, ImportSpace.RESERVE + (100L shl 20)))
        assertEquals(ImportSpace.Verdict.OK, ImportSpace.verdict(1L shl 20, ImportSpace.RESERVE + (100L shl 20)))
        assertTrue(ImportSpace.preview(500L shl 20, 4L shl 30).startsWith("Installs about"))
        assertEquals(283L shl 20, ImportSpace.parseSize("283 MB"))
        assertEquals((1.5 * (1L shl 30)).toLong(), ImportSpace.parseSize("1.5 GB"))

        val d = tmp()
        var free = ImportSpace.RESERVE + 20_000L
        val lib = AnimLibrary(d) { free }
        val staging = lib.newStaging()
        val conv = object : Retarget {
            override fun convert(clip: SimsClip): Anim = moving(clip.name, "", frames = 60, seed = clip.name.hashCode()).copy(source = clip.sourceKey)
        }
        val pkg = SimsFixtures.dbpf(*Array(100) { i -> SimsFixtures.Res(com.logie.wifecraft.sims.ClipDecoder.TYPE, i + 1L,
            SimsFixtures.actorClip("t:clip_$i", "x", floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0.1f), floatArrayOf(0f, 0f, 0f, 1f))) })
        val events = ArrayList<ImportEvent>()
        val res = ImportPipeline(conv, File(d, "tmp"), staging = staging).runAll(listOf(ImportInput("big.package") { pkg.inputStream() })) {
            events += it
            if (it is ImportEvent.Done) free -= 6_000L   // each clip takes room
        }
        assertTrue(res.anims.size in 1..5, "${res.anims.size} kept")
        val stop = events.filterIsInstance<ImportEvent.Skipped>().last()
        assertTrue(stop.why.startsWith("Stopped early") && stop.why.contains("more clips were not converted"), stop.why)
        assertTrue(res.anims.all { it.tracks is StoredTracks })
        // what came before is saved, the staging folder goes
        val saved = ReviewDraft(res.anims).save(object : AnimStore {
            override fun add(a: Anim) = lib.addResult(a)
            override fun categories() = lib.categories()
            override fun <T> batch(block: () -> T): T = lib.batch(block)
        })
        assertEquals(res.anims.size, saved.count { it is com.logie.wifecraft.ui.SaveOutcome.Added })
        assertTrue(staging.dir.listFiles()!!.none { it.name.endsWith(".wclip") })
        staging.delete()
        assertFalse(staging.dir.exists())
        AnimLibrary.forgetCaches()
        assertEquals(res.anims.size, AnimLibrary(d).list().size)
    }

    /**
     * A 2,000-clip package imported through the staging folder in a separate JVM with a 96 MB heap: held in memory, the
     * converted clips would take about 330 MB (each 30 tracks of 300 keys), so it only completes if each clip goes to disk
     * as it is converted.
     */
    @Test fun aTwoThousandClipImportStreamsInASmallHeap() {
        val d = tmp()
        val java = File(System.getProperty("java.home"), "bin/java").path
        val proc = ProcessBuilder(java, "-Xmx96m", "-cp", System.getProperty("java.class.path"), StreamingImportMain::class.java.name, d.path)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(600, TimeUnit.SECONDS))
        assertEquals(0, proc.exitValue(), out)
        assertTrue(out.contains("converted 2000"), out)
        println(out.trim())
    }
}

/** The child JVM of [ClipStoreTest.aTwoThousandClipImportStreamsInASmallHeap]: a synthetic package of 2,000 clips, staged, saved. */
object StreamingImportMain {
    @JvmStatic fun main(args: Array<String>) {
        val d = File(args[0])
        val n = 2000
        val pkg = File(d, "big.package")
        pkg.writeBytes(SimsFixtures.dbpf(*Array(n) { i -> SimsFixtures.Res(com.logie.wifecraft.sims.ClipDecoder.TYPE, i + 1L,
            SimsFixtures.actorClip("t:clip_$i", "x", floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0.1f), floatArrayOf(0f, 0f, 0f, 1f))) }))
        val conv = object : Retarget {
            override fun convert(clip: SimsClip): Anim {
                val frames = 300
                val times = FloatArray(frames) { it / 30f }
                val tracks = List(30) { b ->
                    BoneTrack(BodyGeometry.JOINTS[b % BodyGeometry.JOINTS.size] + if (b >= BodyGeometry.JOINTS.size) "_$b" else "", Channel.ROTATION, times.copyOf(),
                        FloatArray(frames * 4) { k -> val f = k / 4; val a = 0.3 * sin(f * 0.07 + b + clip.name.length); when (k % 4) { 0 -> sin(a / 2).toFloat(); 3 -> cos(a / 2).toFloat(); else -> 0f } })
                }
                return Anim("", clip.name, "", (frames - 1) / 30f, false, tracks, clip.sourceKey, 0L)
            }
        }
        val lib = AnimLibrary(File(d, "lib"))
        val staging = lib.newStaging()
        val rt = Runtime.getRuntime()
        var peak = 0L
        var count = 0
        val res = ImportPipeline(conv, File(d, "tmp"), staging = staging).runAll(listOf(ImportInput("big.package") { pkg.inputStream() })) { e ->
            if (e is ImportEvent.Done && ++count % 200 == 0) { System.gc(); peak = maxOf(peak, rt.totalMemory() - rt.freeMemory()) }
        }
        ReviewDraft(res.anims).save(object : AnimStore {
            override fun add(a: Anim) = lib.addResult(a)
            override fun categories() = lib.categories()
            override fun <T> batch(block: () -> T): T = lib.batch(block)
        })
        staging.delete()
        println("converted ${res.anims.size}, staged ${staging.bytes shr 10} KB, heap after GC at most ${peak shr 20} MB, library ${lib.list().size}")
        if (res.anims.size != n || lib.list().size != n) System.exit(2)
    }
}
