package com.logie.wifecraft.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PortraitSettingsTest {
    @Test fun defaultsAreNaturalLightNaturalLifeCameraGaze() {
        val d = PortraitSettings()
        assertEquals(PortraitLighting.NATURAL, d.lighting)
        assertEquals(LifeLevel.NATURAL, d.life)
        assertEquals(GazeMode.CAMERA, d.gaze)
        assertEquals(70, d.lookAngle)
    }

    @Test fun roundTripsThroughJson() {
        val s = PortraitSettings(PortraitLighting.DRAMATIC, LifeLevel.LIVELY, GazeMode.WANDER, 45)
        assertEquals(s, PortraitSettings.parse(s.toJson().toString()))
        for (l in PortraitLighting.entries) for (v in LifeLevel.entries) for (g in GazeMode.entries) {
            val t = PortraitSettings(l, v, g, 33)
            assertEquals(t, PortraitSettings.parse(t.toJson().toString()))
        }
    }

    @Test fun brokenOrMissingTextIsTheDefaults() {
        assertEquals(PortraitSettings(), PortraitSettings.parse(null))
        assertEquals(PortraitSettings(), PortraitSettings.parse(""))
        assertEquals(PortraitSettings(), PortraitSettings.parse("not json {"))
        assertEquals(PortraitSettings(), PortraitSettings.parse("{\"lighting\":\"NOPE\",\"life\":7,\"gaze\":null}"))
    }

    @Test fun theLookAngleIsKeptBetween20And90() {
        assertEquals(20, PortraitSettings().withAngle(5).lookAngle)
        assertEquals(90, PortraitSettings().withAngle(400).lookAngle)
        assertEquals(55, PortraitSettings().withAngle(55).lookAngle)
        assertEquals(90, PortraitSettings.parse("{\"angle\":1000}").lookAngle)
        assertEquals(20, PortraitSettings.parse("{\"angle\":-3}").lookAngle)
    }

    @Test fun engineIdsAreDistinct() {
        assertEquals(PortraitLighting.entries.size, PortraitLighting.entries.map { it.engineId }.toSet().size)
        assertEquals(LifeLevel.entries.size, LifeLevel.entries.map { it.engineId }.toSet().size)
        assertEquals(GazeMode.entries.size, GazeMode.entries.map { it.engineId }.toSet().size)
        assertFalse(PortraitLighting.entries.any { it.engineId == 0 }, "0 is the engine's 'no rig'")
    }

    @Test fun interactDoesNotJudgeTouchesOrCommands() {
        assertFalse(InteractConsent.ENABLED)
    }
}
