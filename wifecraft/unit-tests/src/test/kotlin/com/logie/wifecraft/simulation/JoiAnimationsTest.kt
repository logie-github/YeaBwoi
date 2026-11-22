package com.logie.wifecraft.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JoiAnimationsTest {
    private fun c(label: String, group: String = "") = JoiClip("k:$label", label, group)
    private val library = listOf(
        c("Sexy dance 1"), c("Hip sway"), c("Come hither gesture"), c("Hair flip"), c("Blow kiss"), c("Look back over shoulder"),
        c("Hands on hips pose"), c("Flirty wink"), c("Teasing strip"), c("Sit on chair"), c("Sleep"), c("Fight stance"),
        c("Partner stage 2", "Partner"), c("Walk cycle"), c("Penetration loop"),
    )

    @Test fun clipsAreClassifiedByTheirNamesAndTheWrongOnesLeftOut() {
        val cat = JoiAnimations.catalogue(library)
        fun moves(l: String) = cat.entries.firstOrNull { it.key.label == l }?.value
        assertTrue(JoiMove.DANCE in moves("Sexy dance 1")!!)
        assertTrue(JoiMove.DANCE in moves("Hip sway")!!)
        assertTrue(JoiMove.GESTURE in moves("Come hither gesture")!!)
        assertTrue(JoiMove.HAIR in moves("Hair flip")!!)
        assertTrue(JoiMove.KISS in moves("Blow kiss")!!)
        assertTrue(JoiMove.LOOK_BACK in moves("Look back over shoulder")!!)
        assertTrue(JoiMove.POSE in moves("Hands on hips pose")!!)
        assertTrue(JoiMove.FLIRT in moves("Flirty wink")!!)
        assertTrue(JoiMove.TEASE in moves("Teasing strip")!!)
        for (bad in listOf("Sit on chair", "Sleep", "Fight stance", "Partner stage 2", "Walk cycle", "Penetration loop")) assertNull(moves(bad), bad)
    }

    @Test fun anEmoteGivesAClipWithItsAnimationKey() {
        val e = com.logie.wifecraft.catalog.Emote(1, "Dance", 69, 0, emptyList(), loop = true, label = "Dance")
        val clip = JoiAnimations.fromEmotes(listOf(e)).single()
        assertEquals("emote:69", clip.key)
        assertTrue(clip.emote && clip.loop)
        assertTrue(JoiMove.DANCE in JoiAnimations.classify(clip))
    }

    @Test fun aGentleStagePicksGentleMovesAndAnIntenseOneLivelyOnes() {
        val cat = JoiAnimations.catalogue(library)
        var gentle = 0f; var lively = 0f
        repeat(200) { i ->
            val p = JoiAnimationPicker(i.toLong())
            gentle += JoiAnimations.classify(p.pick(cat, 0.15f, 0f)!!).maxOf { it.energy }
            val q = JoiAnimationPicker(i.toLong() + 1000)
            lively += JoiAnimations.classify(q.pick(cat, 0.9f, 0f)!!).maxOf { it.energy }
        }
        assertTrue(lively > gentle + 20f, "gentle ${gentle / 200} lively ${lively / 200}")
    }

    @Test fun aClipIsNotRepeatedStraightAwayNorWithinItsCooldown() {
        val cat = JoiAnimations.catalogue(library)
        val p = JoiAnimationPicker(1L)
        var now = 0f
        val seen = HashMap<String, Float>()
        var prev: String? = null
        repeat(20) {
            val got = p.pick(cat, 0.6f, now) ?: return@repeat
            assertTrue(got.key != prev, "same twice running")
            seen[got.key]?.let { at -> assertTrue(now - at >= JoiAnimationPicker.COOLDOWN, "${got.label} again after ${now - at}") }
            seen[got.key] = now; prev = got.key
            now += 15f
        }
    }

    @Test fun twoOfTheSameKindDoNotFollowEachOther() {
        val cat = JoiAnimations.catalogue(library)
        val p = JoiAnimationPicker(3L)
        var last: Set<JoiMove>? = null
        var now = 0f
        repeat(8) {
            val got = p.pick(cat, 0.5f, now) ?: return@repeat
            val m = cat[got]!!
            assertTrue(m != last, "same kind twice")
            last = m; now += 130f
        }
    }

    @Test fun nothingEligibleGivesNull() {
        assertNull(JoiAnimationPicker(1L).pick(emptyMap(), 0.5f, 0f))
        val one = JoiAnimations.catalogue(listOf(c("Dance")))
        val p = JoiAnimationPicker(1L)
        assertNotNull(p.pick(one, 0.5f, 0f))
        assertNull(p.pick(one, 0.5f, 10f), "the only clip is on cooldown")
    }

    @Test fun theSameSeedPicksTheSame() {
        val cat = JoiAnimations.catalogue(library)
        fun seq(s: Long): List<String?> { val p = JoiAnimationPicker(s); return (0 until 6).map { p.pick(cat, 0.5f, it * 130f)?.key } }
        assertEquals(seq(5L), seq(5L))
    }

    @Test fun pacingEnergyRisesFromWarmupToTheCountdown() {
        assertTrue(JoiPacing.energy("warmup") < JoiPacing.energy("edge"))
        assertTrue(JoiPacing.energy("edge") < JoiPacing.energy("countdown"))
        assertTrue(JoiPacing.energy("afterglow") < JoiPacing.energy("build"))
    }
}
