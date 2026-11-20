package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LibraryTest {
    private fun tmp() = Files.createTempDirectory("animlib").toFile()
    private fun anim(name: String, source: String = "", category: String = "Poses", id: String = "") = Anim(
        id, name, category, 0.5f, false,
        listOf(BoneTrack("Head", Channel.ROTATION, floatArrayOf(0f, 0.5f), floatArrayOf(0f, 0f, 0f, 1f, 0.1f, 0.2f, 0.3f, 0.9f)),
            BoneTrack("Hips", Channel.POSITION, floatArrayOf(0f), floatArrayOf(0f, 1f, 0f))),
        source, 1234L)

    @Test fun jsonRoundTripAndVersionGuard() {
        val a = anim("Sit", "h:c", id = "abc")
        val back = AnimJson.parse(AnimJson.toJson(a).toString())
        assertEquals(a, back)
        assertFailsWith<IllegalArgumentException> { AnimJson.parse(AnimJson.toJson(a).put("v", 99).toString()) }
        val bad = AnimJson.toJson(a); bad.getJSONArray("tracks").getJSONObject(0).put("values", org.json.JSONArray(listOf(1.0)))
        assertFailsWith<IllegalArgumentException> { AnimJson.parse(bad.toString()) }
        assertFailsWith<IllegalArgumentException> { AnimJson.toJson(a.copy(duration = Float.NaN)) }
    }

    @Test fun persistsAcrossInstances() {
        val d = tmp()
        val lib = AnimLibrary(d)
        val saved = lib.add(anim("Sit", "h:1"))
        lib.add(anim("Wave", "h:2", category = "Gestures"))
        AnimLibrary.forgetCaches()
        val again = AnimLibrary(d)
        assertEquals(listOf("Wave", "Sit"), again.list().map { it.name })   // Gestures before Poses
        assertEquals(saved, again.get(saved.id))
        assertEquals(listOf("Gestures", "Poses"), again.categories())
        assertTrue(File(d, "index.json").isFile)
        assertTrue(d.listFiles()!!.none { it.name.endsWith(".tmp") })
        AnimLibrary.forgetCaches()
        assertEquals(setOf("Sit", "Wave"), AnimLibrary(d).index().map { it.name }.toSet())
    }

    @Test fun duplicatePolicy() {
        val lib = AnimLibrary(tmp())
        val first = lib.addResult(anim("Sit", "h:1"))
        assertFalse(first.duplicate)
        val same = lib.addResult(anim("Another name", "h:1"))
        assertTrue(same.duplicate); assertEquals(first.anim.id, same.anim.id)
        assertTrue(same.message.contains("already"))
        assertEquals(1, lib.list().size)
        assertEquals("Sit (2)", lib.add(anim("Sit", "h:2")).name)
        assertEquals("sit (3)", lib.add(anim("sit", "h:3")).name)
        assertEquals(3, lib.list().size)
        // blank source is never a duplicate
        lib.add(anim("X")); lib.add(anim("Y"))
        assertEquals(5, lib.list().size)
    }

    @Test fun anOlderConversionIsCountedAndReplacedByTheReimport() {
        val d = tmp()
        val lib = AnimLibrary(d)
        val old = lib.add(anim("Lie", "h:1").copy(conversionRevision = 3))
        lib.rename(old.id, "My lie")
        lib.add(anim("Hand made"))   // no source: not a converted clip
        // a file written before the revision was stamped still loads, and counts as older
        val legacy = AnimJson.toJson(anim("Kneel", "h:2", id = "legacy1")).apply { remove("conversionRevision") }
        File(d, "legacy1.json").writeText(legacy.toString())
        AnimLibrary.forgetCaches()
        val again = AnimLibrary(d)
        assertEquals(3, again.list().size)
        assertEquals(2, again.stale())
        // the same clip converted again: replaces it (not skipped as already imported), its name kept
        val r = again.addResult(anim("Lie", "h:1").copy(conversionRevision = Retargeter.CONVERSION_REVISION))
        assertTrue(r.updated); assertFalse(r.duplicate)
        assertEquals(old.id, r.anim.id); assertEquals("My lie", r.anim.name)
        assertEquals(1, again.stale())
        // the same conversion again: a duplicate, skipped
        assertTrue(again.addResult(anim("Lie", "h:1").copy(conversionRevision = Retargeter.CONVERSION_REVISION)).duplicate)
        // partner animations too: an older pair is replaced, its name and switch kept
        fun pair(rev: Int) = PairAnim("", "Pair", "Partner", anim("pa", "h:a").copy(conversionRevision = rev),
            anim("pb", "h:b").copy(conversionRevision = rev), PairOffset.ZERO, PairOffset.ZERO, true, emptySet(), emptyList(),
            source = "h:a+h:b", importedAt = 1L)
        val p = again.addPair(pair(3))
        again.setPairEnabled(p.id, false)
        assertEquals(2, again.stale())
        val up = again.addPairResult(pair(Retargeter.CONVERSION_REVISION))
        assertTrue(up.updated); assertEquals(p.id, up.pair.id); assertFalse(up.pair.enabled)
        assertEquals(1, again.stale())
        assertTrue(again.addPairResult(pair(Retargeter.CONVERSION_REVISION)).duplicate)
    }

    @Test fun renameCategoryDeleteFind() {
        val d = tmp()
        val lib = AnimLibrary(d)
        val a = lib.add(anim("Sit", "h:1")); val b = lib.add(anim("Stand", "h:2"))
        lib.rename(a.id, "Stand")                       // taken: gets a suffix
        assertEquals("Stand (2)", lib.get(a.id)!!.name)
        lib.rename(b.id, "Stand")                       // its own name is not a clash
        assertEquals("Stand", lib.get(b.id)!!.name)
        assertFailsWith<IllegalArgumentException> { lib.rename(a.id, "  ") }
        assertFailsWith<NoSuchElementException> { lib.rename("nope", "x") }
        lib.setCategory(a.id, "poses")                  // existing category keeps its spelling
        assertEquals("Poses", lib.get(a.id)!!.category)
        lib.setCategory(a.id, "Dances")
        assertEquals(listOf("Dances", "Poses"), lib.categories())
        assertEquals(listOf(a.id), lib.find("DANCE").map { it.id })
        assertEquals(listOf(b.id), lib.find("sTaNd").filter { it.id == b.id }.map { it.id })
        assertEquals(2, lib.find("").size)
        lib.delete(a.id); lib.delete(a.id)
        assertNull(lib.get(a.id)); assertFalse(File(d, "${a.id}.json").exists())
        AnimLibrary.forgetCaches()
        assertEquals(1, AnimLibrary(d).list().size)
    }

    @Test fun damagedFileIsSkippedNotFatal() {
        val d = tmp()
        AnimLibrary(d).add(anim("Ok", "h:1"))
        File(d, "broken.json").writeText("{not json")
        AnimLibrary.forgetCaches()
        val lib = AnimLibrary(d)
        assertEquals(listOf("Ok"), lib.list().map { it.name })
        assertEquals(listOf("broken.json"), lib.damaged)
    }

    @Test fun hostileIdsAreReplaced() {
        val lib = AnimLibrary(tmp())
        val a = lib.add(anim("A", id = "../../evil"))
        assertTrue(Regex("[A-Za-z0-9_-]+").matches(a.id))
        val b = lib.add(anim("B", id = a.id))
        assertNotEquals(a.id, b.id)
    }

    @Test fun concurrentAddsAllLand() {
        val d = tmp()
        val lib = AnimLibrary(d)
        val pool = Executors.newFixedThreadPool(8)
        repeat(40) { i -> pool.execute { AnimLibrary(d).add(anim("Same", "h:$i")) } }
        pool.shutdown(); assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(40, lib.list().size)
        assertEquals(40, lib.list().map { it.name }.toSet().size)
        AnimLibrary.forgetCaches()
        assertEquals(40, AnimLibrary(d).list().size)
    }
}
