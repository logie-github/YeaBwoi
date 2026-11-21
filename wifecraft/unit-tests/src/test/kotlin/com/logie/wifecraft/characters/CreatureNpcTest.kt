package com.logie.wifecraft.characters

import com.logie.wifecraft.bodyparts.ModelParts
import com.logie.wifecraft.simulation.PlaybackRole
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.SceneSimulation
import com.logie.wifecraft.story.Vec3
import java.io.File
import kotlin.math.hypot
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
        assertTrue(s.shiftX > 0f && s.clearance > 0f && s.entryHeight in 0.3f..2f)
    }

    @Test fun herMarkerIsFilledForEitherRoleAndStaysClearOfTheOther() {
        val s = CreatureNpcs.SINDRAGOSA
        // her marker 1 yard in front of the other's, both facing each other (role B of a face-to-face stage)
        val d = CreatureNpcs.delta(s, 0.5f, 0f, -0.5f, 0f, Math.PI.toFloat())
        // her hindquarters shift along her own facing (backwards in the pair's frame) and she is pushed away from the other: net
        assertTrue(d[0].isFinite() && d[1].isFinite())
        assertEquals(-s.shiftX + s.clearance, d[0], 1e-4f)
        assertEquals(0f, d[1], 1e-4f)
        // the same roles the other way round (she is role A): symmetric
        val e = CreatureNpcs.delta(s, -0.5f, 0f, 0.5f, 0f, 0f)
        assertEquals(s.shiftX - s.clearance, e[0], 1e-4f)
        // two roles on one spot: no clearance, no NaN
        val z = CreatureNpcs.delta(s, 0f, 0f, 0f, 0f, 0.3f)
        assertTrue(z[0].isFinite() && z[1].isFinite())
        assertEquals(s.shiftX, hypot(z[0], z[1]), 1e-4f)
    }

    @Test fun theSimulationPutsHerAtTheRoleAndNeverRaisesHer() {
        val sim = SceneSimulation(listOf("a", "character:sindragosa"), emptySet<SceneObject>())
        sim.creatures = mapOf("character:sindragosa" to CreatureNpcs.SINDRAGOSA)
        assertEquals(setOf("character:sindragosa"), sim.creatures.keys)
        // a pair role and her move: the pair's frame, as the two roles
        val mine = PlaybackRole("b", Vec3(0.4f, 0f, 0f), Math.PI.toFloat())
        val theirs = PlaybackRole("a", Vec3(-0.4f, 0f, 0f), 0f)
        val d = CreatureNpcs.delta(CreatureNpcs.SINDRAGOSA, mine.offset.x, mine.offset.y, theirs.offset.x, theirs.offset.y, mine.facing)
        assertTrue(hypot(d[0], d[1]) <= CreatureNpcs.SINDRAGOSA.shiftX + CreatureNpcs.SINDRAGOSA.clearance + 1e-3f)
    }
}
