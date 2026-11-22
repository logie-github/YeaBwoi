package com.logie.wifecraft.characters

import com.logie.wifecraft.dialogue.JoiPrompt
import com.logie.wifecraft.sims.Channel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JoiDragonSwayTest {
    @Test fun theLayerSwaysHerNeckAndHeadBonesOnly() {
        val a = JoiDragonSway.layer(0.5f, 3L)
        assertEquals(JoiDragonSway.BONES.values.map { "#$it" }.toSet(), a.tracks.map { it.bone }.toSet())
        assertTrue(a.tracks.all { it.channel == Channel.ROTATION })
        assertTrue(a.loop)
        assertEquals(JoiDragonSway.LOOP, a.duration, 0.01f)
    }

    @Test fun theAnglesStayWithinTheBoundAndTheLoopClosesWithoutASeam() {
        for (e in listOf(0f, 0.5f, 1f)) {
            val a = JoiDragonSway.layer(e, 9L)
            for (t in a.tracks) {
                var worst = 0f
                for (i in 0 until t.times.size) {
                    val w = t.values[4 * i + 3].coerceIn(-1f, 1f)
                    val deg = Math.toDegrees(2.0 * Math.acos(abs(w).toDouble())).toFloat()
                    worst = maxOf(worst, deg)
                }
                assertTrue(worst <= JoiDragonSway.MAX_DEG * 1.6f, "energy $e: ${t.bone} turns $worst degrees")
                // first key is the rest pose
                assertEquals(1f, abs(t.values[3]), 1e-4f)
            }
        }
    }

    @Test fun livelierEnergySwaysMoreAndTheSameSeedTheSame() {
        fun total(e: Float): Float = JoiDragonSway.layer(e, 5L).tracks.sumOf { t -> t.values.indices.filter { it % 4 != 3 }.sumOf { abs(t.values[it]).toDouble() } }.toFloat()
        assertTrue(total(1f) > total(0f))
        assertEquals(total(0.4f), total(0.4f))
        assertEquals(JoiDragonSway.layer(0.5f, 1L).tracks.first().values.toList(), JoiDragonSway.layer(0.5f, 1L).tracks.first().values.toList())
    }

    @Test fun creatorTraitsBecomeTheJoiMannerAndTheDragonIsDominant() {
        assertEquals(setOf("teasing"), JoiPrompt.personalitiesFrom(emptyList()))
        assertEquals(setOf("tender", "bold"), JoiPrompt.personalitiesFrom(listOf("Gentle", "Confident")))
        assertEquals(setOf("dominant", "strict"), JoiPrompt.personalitiesFrom(listOf("shy"), dragon = true))
        assertEquals("bloodelf", JoiPrompt.raceKey(10))
    }

    @Test fun sindragosaHasASkullPlaceForTheOrbit() {
        val s = CreatureNpcs.SINDRAGOSA
        assertTrue(s.skullForward > 1f && s.skullLift > 1f)
        assertEquals(0.25f, s.scale)
    }
}
