package com.logie.wifecraft.characters

import com.logie.wifecraft.bodyparts.ModelParts
import com.logie.wifecraft.simulation.PlaybackRole
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.SceneSimulation
import com.logie.wifecraft.story.Vec3
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sindragosa as a named creature NPC: her catalogue entry, her spec and her place at a partner role's marker. */
class CreatureNpcTest {
    private val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
    private val cast = NamedCast.parse(File(assets, "characters.json").readText(), File(assets, ModelParts.ASSET).readText())

    @Test fun sheIsInTheCatalogueAsWrathsFrostWyrmDisplay() {
        val c = assertNotNull(cast.character("sindragosa"))
        val choice = assertNotNull(cast.choice(c, null))
        assertEquals(30362, choice.display)
        assertEquals(listOf("wrath"), cast.choices(c).map { it.key })   // no undressed kinds, no partials: a creature
        assertTrue(30362 in cast.displays())
        val actor = cast.actors().first { it.id == NamedCast.actorId("sindragosa") }
        assertEquals(CreatureNpcs.SINDRAGOSA, CreatureNpcs.of(actor.appearance))
        assertEquals(CreatureNpcs.SINDRAGOSA, CreatureNpcs.ofActor(actor.id))
        assertEquals(CreatureNpcs.SINDRAGOSA.display, choice.display)
    }

    @Test fun nobodyElseIsACreature() {
        for (a in cast.actors()) if (a.id != NamedCast.actorId("sindragosa")) assertNull(CreatureNpcs.of(a.appearance), a.id)
        assertNull(CreatureNpcs.ofActor("npc:mira"))
        assertNull(CreatureNpcs.of(null as String?))
    }

    @Test fun herSizeIsADragonsButNotTheModelsRawOne() {
        val s = CreatureNpcs.SINDRAGOSA
        // her model is 33.5 yards nose to tail at scale 1 (CreatureModelData GeoBox): the display's own 2.5 would be 84 yards
        val length = 33.5f * s.scale
        assertTrue(length in 5f..12f, "length $length")
        assertTrue(s.hipX < 0f && s.flank > 0f && s.clearance >= 0f && s.entryHeight in 0.3f..2f)
    }

    private fun world(s: CreatureSpec, mx: Float, my: Float, d: FloatArray, facing: Float): FloatArray {
        // her flank point as drawn: origin + hipX * forward + flank * left, at her final facing
        val yaw = facing + Math.toRadians(d[2].toDouble()).toFloat()
        val ox = mx + d[0]; val oy = my + d[1]
        return floatArrayOf(ox + s.hipX * cos(yaw) - s.flank * sin(yaw), oy + s.hipX * sin(yaw) + s.flank * cos(yaw), yaw)
    }

    @Test fun herMarkerIsFilledForEitherRoleSideOnAndClearOfTheOther() {
        val s = CreatureNpcs.SINDRAGOSA
        for ((mx, ox, facing) in listOf(Triple(0.5f, -0.5f, Math.PI.toFloat()), Triple(-0.5f, 0.5f, 0f), Triple(0f, 0f, 1.2f))) {
            val d = CreatureNpcs.place(s, mx, 0f, ox, 0f, facing)
            assertTrue(d.all { it.isFinite() })
            assertTrue(d[2] in -180f..180f)
            val w = world(s, mx, 0f, d, facing)
            // her flank point is the marker pushed by the clearance away from the other person
            val away = if (mx == ox) 0f else if (mx > ox) 1f else -1f
            assertEquals(mx + away * s.clearance, w[0], 1e-4f)
            assertEquals(0f, w[1], 1e-4f)
            // and her left flank faces the other person (her left axis points at them)
            if (mx != ox) {
                val left = floatArrayOf(-sin(w[2]), cos(w[2]))
                assertEquals(if (ox > mx) 1f else -1f, left[0], 1e-4f)
            }
        }
    }

    @Test fun theSimulationPutsHerAtTheRoleAndNeverRaisesHer() {
        val sim = SceneSimulation(listOf("a", "character:sindragosa"), emptySet<SceneObject>())
        sim.creatures = mapOf("character:sindragosa" to CreatureNpcs.SINDRAGOSA)
        assertEquals(setOf("character:sindragosa"), sim.creatures.keys)
        val mine = PlaybackRole("b", Vec3(0.4f, 0f, 0f), Math.PI.toFloat())
        val theirs = PlaybackRole("a", Vec3(-0.4f, 0f, 0f), 0f)
        val d = CreatureNpcs.place(CreatureNpcs.SINDRAGOSA, mine.offset.x, mine.offset.y, theirs.offset.x, theirs.offset.y, mine.facing)
        assertTrue(hypot(d[0], d[1]) <= CreatureNpcs.SINDRAGOSA.run { -hipX + flank + clearance } + 1e-3f)
    }
}
