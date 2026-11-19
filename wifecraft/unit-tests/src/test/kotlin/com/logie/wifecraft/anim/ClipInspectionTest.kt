package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipInspectionTest {
    private fun clip(vararg x: Float): Anim {
        val track = BoneTrack("Root", Channel.POSITION, FloatArray(x.size) { it.toFloat() },
            FloatArray(x.size * 3) { if (it % 3 == 0) x[it / 3] else 0f })
        return Anim("clip", "Sample", "Imported", 2f, true, listOf(track), "sample", 0L)
    }

    @Test
    fun identifiesPosesLoopsTransitionsAndInvalidTracks() {
        val pose = CheckedAnimation(clip(0.5f, 0.5f, 0.5f))
        assertEquals(ClipType.POSE, pose.inspection.type)
        assertEquals(0f, pose.forPlayback()?.duration)
        val loop = CheckedAnimation(clip(0f, 1f, 0f))
        assertEquals(ClipType.LOOP, loop.inspection.type)
        assertTrue(loop.forPlayback()?.loop == true)
        val transition = CheckedAnimation(clip(0f, 1f, 2f))
        assertEquals(ClipType.ONE_SHOT, transition.inspection.type)
        assertFalse(checkNotNull(transition.forPlayback()).loop)
        assertEquals(ClipType.INVALID, CheckedAnimation(clip(Float.NaN)).inspection.type)
    }

    @Test
    fun reimportUpdatesMotionInPlaceAndPreservesNameAndCategory() {
        val dir = Files.createTempDirectory("wifecraft-clip-revision").toFile()
        try {
            val library = AnimLibrary(dir)
            val original = library.add(clip(0f, 1f, 0f))
            library.rename(original.id, "My clip")
            library.setCategory(original.id, "My pack")
            val refreshed = library.addResult(clip(0f, 1f, 2f).copy(conversionRevision = 1, omittedChannels = true))
            assertTrue(refreshed.updated)
            assertEquals(original.id, refreshed.anim.id)
            assertEquals("My clip", refreshed.anim.name)
            assertEquals("My pack", refreshed.anim.category)
            assertTrue(AnimJson.parse(AnimJson.toJson(refreshed.anim).toString()).omittedChannels)
            assertTrue(library.addResult(refreshed.anim).duplicate)
            assertEquals(1, library.list().size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
