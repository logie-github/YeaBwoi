package com.logie.wifecraft.ui

import com.logie.wifecraft.anim.AddResult
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.ImportEvent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnimStateTest {
    private fun anim(name: String, source: String = "h:$name", category: String = "", duration: Float = 0.07f, loop: Boolean = false, id: String = "id_$name") =
        Anim(id, name, category, duration, loop, emptyList(), source, 1L)

    /** A library stand-in with AnimLibrary's duplicate policy (same source: skipped; same name: " (2)"). */
    private class FakeStore : AnimStore {
        val kept = mutableListOf<Anim>()
        var failOn: String? = null
        override fun add(a: Anim): AddResult {
            if (a.name == failOn) throw IllegalStateException("disk full")
            kept.firstOrNull { it.source == a.source }?.let { return AddResult(it, true, "already there") }
            var n = a.name; var k = 2
            while (kept.any { it.name.equals(n, true) }) n = "${a.name} (${k++})"
            val s = a.copy(name = n); kept += s
            return AddResult(s, false, "saved")
        }
        override fun categories() = kept.map { it.category }.distinct()
    }

    // ---------------------------------------------------------------- the progress list

    @Test fun eventsBecomeRowsWithTheirReasons() {
        val run = ImportRun()
        run.apply(ImportEvent.Found("a.package"))
        run.apply(ImportEvent.Found("a.package :: clip1"))
        run.apply(ImportEvent.Done(anim("Sit")))            // named by the clip, not by the label
        run.apply(ImportEvent.Found("a.package :: clip2"))
        run.apply(ImportEvent.Failed("a.package :: clip2", "no skeleton"))
        run.apply(ImportEvent.Found("b.zip/x.package"))
        run.apply(ImportEvent.Skipped("b.zip/x.package", "No animation clips in this package"))
        run.apply(ImportEvent.Failed("c.txt", "Not a Sims 4 .package file"))   // never announced: still a row
        run.finish()
        assertEquals(
            listOf("DONE:Sit", "FAILED:a.package :: clip2", "SKIPPED:b.zip/x.package", "FAILED:c.txt"),
            run.rows.map { "${it.status}:${it.name}" },
        )
        assertEquals("no skeleton", run.rows[1].note)
        assertEquals("1 converted, 1 skipped, 2 failed", run.summary())
        assertEquals(listOf("Sit"), run.converted.map { it.name })
        assertTrue(run.rows.none { it.status == ItemStatus.WAITING })
    }

    @Test fun aFileThatNeverAnswersIsFailedNotLeftReading() {
        val run = ImportRun()
        run.apply(ImportEvent.Found("x.package"))
        assertEquals(ItemStatus.WAITING, run.rows.single().status)
        run.finish()
        assertEquals(ItemStatus.FAILED, run.rows.single().status)
        assertTrue(run.finished)
    }

    @Test fun noSkeletonIsSaidPlainly() {
        assertTrue("Sims 4 skeleton" in AnimText.NO_RIG && "can't be converted" in AnimText.NO_RIG)
        assertTrue(RigStore.status(false, null).contains("needs one"))
        assertEquals("Sims 4 skeleton loaded (60 bones).", RigStore.status(true, 60))
    }

    // ---------------------------------------------------------------- the review and the save

    @Test fun reviewDefaultsToTheClipNameAndALibraryCategory() {
        val d = ReviewDraft(listOf(anim("Sit"), anim("Wave", category = "Gestures")))
        assertEquals(listOf("Sit", "Wave"), d.items.map { it.name })
        assertEquals(listOf("Imported", "Gestures"), d.items.map { it.category })
        assertEquals(listOf("Gestures", "Imported"), d.categoryChoices(emptyList()))
        assertEquals(listOf("Gestures", "Imported", "poses"), d.categoryChoices(listOf("poses", "Gestures")))   // the library's spelling wins
    }

    @Test fun batchCategoryNamesAndSaving() {
        val store = FakeStore()
        val d = ReviewDraft(listOf(anim("Sit"), anim("Stand"), anim("Skip me")))
        d.setAllCategories("  Sitting   poses ".let { AnimText.category(it) })
        d.setName(0, "  Armchair   sit ")
        d.setName(1, "   ")                   // blank: the clip's own name
        d.toggle(2)
        assertEquals(2, d.chosen)
        val r = d.save(store)
        assertEquals(listOf("Armchair sit", "Stand"), store.kept.map { it.name })
        assertEquals(listOf("Sitting poses", "Sitting poses"), store.kept.map { it.category })
        assertTrue(r.all { it is SaveOutcome.Added })
        assertEquals("2 added", ReviewDraft.summary(r))
    }

    @Test fun duplicatesAreSkippedAndSameNamesGetASuffix() {
        val store = FakeStore()
        ReviewDraft(listOf(anim("Sit", source = "h:1"))).save(store)
        val d = ReviewDraft(listOf(anim("Sit again", source = "h:1"), anim("Sit", source = "h:2")))
        val r = d.save(store)
        assertTrue(r[0] is SaveOutcome.Duplicate)
        val added = r[1] as SaveOutcome.Added
        assertEquals("Sit (2)", added.name)
        assertEquals("Sit", added.renamedFrom)
        assertEquals(2, store.kept.size)
        assertEquals("1 added, 1 already in the library (skipped)", ReviewDraft.summary(r))
    }

    @Test fun oneFailureDoesNotStopTheRest() {
        val store = FakeStore().also { it.failOn = "Bad" }
        val r = ReviewDraft(listOf(anim("Bad"), anim("Good"))).save(store)
        assertTrue(r[0] is SaveOutcome.Failed)
        assertTrue(r[1] is SaveOutcome.Added)
        assertEquals(listOf("Good"), store.kept.map { it.name })
        assertEquals("1 added, 1 failed", ReviewDraft.summary(r))
    }

    @Test fun namesAreTidied() {
        assertEquals("a b", AnimText.tidy("  a \n\t b  ", 60))
        assertEquals(40, AnimText.tidy("x".repeat(100), 40).length)
        assertEquals("Imported", AnimText.category("  "))
        assertEquals("pose 1", AnimText.baseName("folder/sub\\pose 1.package"))
        assertEquals("Animation", AnimText.name("", ""))
    }

    // ---------------------------------------------------------------- the browser and the pickers

    @Test fun searchAndCategoryFilter() {
        val all = listOf(
            anim("Sit chair", category = "Sitting", source = "h1:a"), anim("Wave", category = "Gestures", source = "pack.package:b"),
            anim("Sit floor", category = "sitting", source = "h2:c"), anim("Dance", category = "Gestures", duration = 2.4f, loop = true),
        )
        assertEquals(listOf("Dance", "Wave", "Sit chair", "Sit floor"), AnimBrowse.filter(all, "", null).map { it.name })
        assertEquals(listOf("Sit chair", "Sit floor"), AnimBrowse.filter(all, "SIT", null).map { it.name })
        assertEquals(listOf("Sit chair"), AnimBrowse.filter(all, "sit chair", null).map { it.name })
        assertEquals(listOf("Wave"), AnimBrowse.filter(all, "pack", null).map { it.name })       // the source file
        assertEquals(listOf("Dance", "Wave"), AnimBrowse.filter(all, "", "gestures").map { it.name })
        assertEquals(listOf("Gestures" to 2, "Sitting" to 2), AnimBrowse.categories(all))
        assertEquals("Pose", AnimBrowse.describe(all[0]))
        assertEquals("2.4 s, loops", AnimBrowse.describe(all[3]))
    }

    @Test fun editorPickersListTheLibraryByName() {
        val all = listOf(anim("Wave", category = "Gestures"), anim("Sit", category = "Sitting"))
        assertEquals(listOf("id_Wave" to "Gestures: Wave", "id_Sit" to "Sitting: Sit"), PoseChoices.items(all))
        assertEquals(listOf("anim:id_Wave" to "Pose: Wave", "anim:id_Sit" to "Pose: Sit"), PoseChoices.emotes(all))
    }

    // ---------------------------------------------------------------- the stored skeleton

    @Test fun rigIsStoredAtomicallyAndReadBack() {
        val dir = Files.createTempDirectory("rig").toFile()
        val s = RigStore(dir)
        assertFalse(s.has()); assertNull(s.bytes())
        s.save(byteArrayOf(1, 2, 3))
        assertTrue(s.has())
        assertEquals(listOf<Byte>(1, 2, 3), s.bytes()!!.toList())
        s.save(byteArrayOf(9))
        assertEquals(listOf<Byte>(9), RigStore(dir).bytes()!!.toList())
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".tmp") })
        assertNotNull(runCatching { s.save(ByteArray(0)) }.exceptionOrNull())
        s.clear()
        assertFalse(s.has())
    }
}
