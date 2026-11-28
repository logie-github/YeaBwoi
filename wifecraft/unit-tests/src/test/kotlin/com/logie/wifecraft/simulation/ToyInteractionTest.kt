package com.logie.wifecraft.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToyInteractionTest {
    private val geo = ToyGeometry(entryX = 500f, entryY = 700f, axisX = 0f, axisY = -1f, pxPerMetre = 2000f, insertLength = 0.2f)
    private val dildo = ToyItem(1, ToyKind.DILDO, "Dildo 01")
    private val vibe = ToyItem(2, ToyKind.VIBRATOR, "Clit Vibrator")

    @Test fun classifiesByPackageNameAndSize() {
        assertEquals(ToyKind.DILDO, ToyKind.classify("0nizu_Dildo_01.package", listOf(0.08f, 0.08f, 0.36f)))
        assertEquals(ToyKind.DOUBLE, ToyKind.classify("0nizu_Dildo_02_-_Double_Ended.package", listOf(0.04f, 0.44f, 0.04f)))
        assertEquals(ToyKind.VIBRATOR, ToyKind.classify("0nizu_Clit_Vibrator.package", listOf(0.12f, 0.09f, 0.04f)))
        assertEquals(ToyKind.MASTURBATOR, ToyKind.classify("0nizu_Electric_penis_masturbator.package", listOf(0.13f, 0.13f, 0.33f)))
        assertEquals(ToyKind.DOLL, ToyKind.classify("0nizu_Thick_Sex_Doll_v2.package", listOf(0.5f, 0.3f, 1.6f)))
        assertEquals(ToyKind.CUFFS, ToyKind.classify("0nizu_Realistic_Handcuffs.package", listOf(0.1f, 0.1f, 0.02f)))
        assertEquals(ToyKind.DILDO, ToyKind.classify("mystery.package", listOf(0.04f, 0.04f, 0.3f)))   // long and thin: by geometry
        assertEquals(ToyKind.OTHER, ToyKind.classify("mystery.package", listOf(0.4f, 0.4f, 0.3f)))
        assertTrue(ToyKind.DILDO.insertable && ToyKind.DOUBLE.insertable && ToyKind.DOUBLE.doubleEnded)
        assertFalse(ToyKind.DOLL.insertable || ToyKind.CUFFS.insertable || ToyKind.DOLL.usable)
        assertTrue(ToyKind.VIBRATOR.vibrates)
    }

    @Test fun trayListsToysInsertableFirstAndNumbersVariants() {
        val items = ToyTray.items(listOf(
            Triple(10L, "0nizu_Thick_Sex_Doll.package", listOf(0.5f, 0.3f, 1.6f)),
            Triple(11L, "0nizu_Dildo_01.package", listOf(0.08f, 0.08f, 0.36f)),
            Triple(12L, "0nizu_Dildo_01.package", listOf(0.08f, 0.08f, 0.36f)),
            Triple(13L, "random_chair.package", listOf(0.5f, 0.5f, 0.9f)),
        ))
        assertEquals(listOf("Dildo 01", "Dildo 01 · 2", "Thick Sex Doll"), items.map { it.label })
        assertTrue(items.none { it.guid == 13L }, "a chair is not a toy")
    }

    @Test fun pickHoldsTheToyAtTheFinger() {
        val c = ToyController()
        assertEquals(ToyEvent.PICKED, c.pick(dildo, 100f, 200f))
        assertEquals(ToyController.Phase.HELD, c.phase)
        val cmd = assertNotNull(c.command(1000, 1000))
        assertEquals(1, cmd.mode); assertEquals(100f, cmd.fx)
        assertNull(ToyController().command(1000, 1000))
    }

    @Test fun snapsNearTheEntryOnlyForInsertableToys() {
        val c = ToyController()
        c.pick(dildo, 100f, 100f)
        assertEquals(ToyEvent.NONE, c.move(500f, 500f, geo))                 // 200 px = 10 cm away
        assertEquals(ToyEvent.SNAPPED, c.move(520f, 740f, geo))              // 45 px = 2.2 cm
        assertEquals(ToyController.Phase.ENGAGED, c.phase)
        assertEquals(-ToyController.CLEARANCE, c.depth)
        val v = ToyController()
        v.pick(vibe, 100f, 100f)
        assertEquals(ToyEvent.NONE, v.move(500f, 700f, geo), "a vibrator is held against her, it doesn't go in")
        assertEquals(ToyController.Phase.HELD, v.phase)
    }

    @Test fun dragAlongTheAxisPushesAndPulls() {
        val c = ToyController()
        c.pick(dildo, 0f, 0f); c.move(500f, 700f, geo)
        // the axis on screen is up (-y): 100 px up = 5 cm
        c.drag(0f, -100f, 0.1f, geo)
        assertEquals(-0.03f + 0.05f, c.depth, 1e-4f)
        assertEquals(0.5f, c.speed, 1e-3f)
        c.drag(300f, 0f, 0.1f, geo)                                          // across the axis: nothing
        assertEquals(0.02f, c.depth, 1e-4f)
        c.drag(0f, 60f, 0.1f, geo)                                           // back: 3 cm out
        assertEquals(-0.01f, c.depth, 1e-4f)
        assertTrue(c.speed < 0)
        c.drag(0f, 5000f, 0.1f, geo)
        assertEquals(-ToyController.CLEARANCE, c.depth, "never behind the clearance")
    }

    @Test fun depthStopsAt85PercentOfTheLength() {
        val c = ToyController()
        c.pick(dildo, 0f, 0f); c.move(500f, 700f, geo)
        c.drag(0f, -100000f, 0.1f, geo)
        assertEquals(0.2f * 0.85f, c.depth, 1e-5f)
        assertTrue(c.inserted)
    }

    @Test fun releaseLeavesItWhereItIs() {
        val c = ToyController()
        c.pick(dildo, 0f, 0f); c.move(500f, 700f, geo)
        c.drag(0f, -100f, 0.1f, geo)
        val d = c.depth
        c.release()
        for (i in 0 until 60) c.step(1f / 60)
        assertEquals(d, c.depth, 1e-6f, "held by the body")
        assertEquals(ToyController.Phase.ENGAGED, c.phase)
        assertEquals(0f, c.speed, 1e-2f)
        // released before it went in: back to being held free
        val e = ToyController()
        e.pick(dildo, 0f, 0f); e.move(500f, 700f, geo); e.release()
        assertEquals(ToyController.Phase.HELD, e.phase)
    }

    @Test fun removePullsItOutThenHoldsItFree() {
        val c = ToyController()
        c.pick(dildo, 0f, 0f); c.move(500f, 700f, geo)
        c.drag(0f, -400f, 0.1f, geo)
        assertTrue(c.remove())
        var out = false
        var t = 0f
        while (t < 5f && !out) { out = c.step(1f / 60) == ToyEvent.REMOVED; t += 1f / 60 }
        assertTrue(out)
        assertTrue(t < 2f)
        assertEquals(ToyController.Phase.HELD, c.phase)
        assertFalse(ToyController().remove(), "nothing to remove")
    }

    @Test fun dropPutsItAway() {
        val c = ToyController()
        c.pick(vibe, 0f, 0f)
        c.setVibration(true, 0.8f)
        assertEquals(ToyEvent.DROPPED, c.drop())
        assertFalse(c.held); assertNull(c.item); assertEquals(0f, c.vibration)
    }

    @Test fun vibratorIntensityAndHaptics() {
        val c = ToyController()
        c.pick(vibe, 0f, 0f)
        assertEquals(0f, c.vibration)
        c.setVibration(true, 0.3f)
        assertEquals(0.3f, c.vibration)
        c.setVibration(true, 7f)
        assertEquals(1f, c.vibration)
        c.setVibration(false)
        assertEquals(0f, c.vibration)
        assertNull(ToyHaptics.buzz(0f))
        val lo = assertNotNull(ToyHaptics.buzz(0.2f)).amplitudes[0]
        val hi = assertNotNull(ToyHaptics.buzz(1f)).amplitudes[0]
        assertTrue(hi > lo && hi <= 255 && lo >= 1, "proportional: $lo < $hi")
    }

    @Test fun ticksEveryCentimetreOfTravel() {
        val c = ToyController()
        c.pick(dildo, 0f, 0f); c.move(500f, 700f, geo)
        var ticks = 0
        repeat(10) { if (c.drag(0f, -10f, 0.02f, geo) == ToyEvent.TICK) ticks++ }   // 10 x 0.5 cm
        assertEquals(5, ticks)
    }

    @Test fun anUnmeasuredEntryDoesNothing() {
        val c = ToyController()
        c.pick(dildo, 0f, 0f)
        assertEquals(ToyEvent.NONE, c.move(500f, 700f, null))
        assertEquals(ToyEvent.NONE, c.move(500f, 700f, geo.copy(pxPerMetre = 0f)))
        assertEquals(ToyController.Phase.HELD, c.phase)
    }
}
