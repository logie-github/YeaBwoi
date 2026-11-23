package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.CharacterSize
import com.logie.wifecraft.anim.Hints
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.RequirementClassifier
import com.logie.wifecraft.sims.Channel
import kotlin.test.*

/**
 * Character size and the furniture and the pair frame: a lying or sitting body rests on its surface at any factor, a pair's contact
 * (in body units) and its slots (in body units across the floor) are what they were, and the fit's answer does not depend on the
 * factor (the bodies and the pair frame grow together; the furniture does not).
 */
class CharacterSizeFitTest {
    private val F = PoseFixtures
    private val factors = listOf(0.8f, 1f, 1.15f, 1.3f, 1.5f, 1.8f)

    private fun moved(a: Anim, dx: Double, dy: Double): Anim = a.copy(tracks = a.tracks.map { t ->
        if (t.bone == "Root" && t.channel == Channel.POSITION) {
            val v = t.values.copyOf()
            for (k in t.times.indices) { v[k * 3] += (dx * BodyGeometry.REF_UNIT).toFloat(); v[k * 3 + 1] += (dy * BodyGeometry.REF_UNIT).toFloat() }
            BoneTrack(t.bone, t.channel, t.times, v)
        } else t
    })

    private fun req(vararg actors: Anim, hints: Hints = Hints()): Requirement =
        RequirementClassifier.classify(actors.map { BodyGeometry.frames(it) }, hints)

    /** A bed whose mattress is [surface] yards (the game's beds 0.66 to 1.2), a chair of seat [seat]. */
    private fun bed(surface: Float) = Furniture("bed", FurnitureSpec.standard(FurnitureKind.BED_DOUBLE, FurnitureSpec.YARDS_PER_METRE, surface), 0f, 0f, 0f, 0f)
    private fun chair(seat: Float) = Furniture("chair", FurnitureSpec.standard(FurnitureKind.CHAIR, FurnitureSpec.YARDS_PER_METRE, seat), 0f, 0f, 0f, 0f)

    @Test fun aLyingBodyRestsOnTheMattressAtAnyFactorOnAnyBed() {
        val lying = req(F.lyingBack(0.55))
        for (surface in listOf(0.66f, 0.744f, 0.84f, 1.08f)) for (f in factors) CharacterSize.with(f) {
            val fit = FurnitureFit.fitAny(lying, bed(surface), SceneSimulation.HUMAN_BODY_UNIT)
            assertNotNull(fit, "no fit at factor $f on a $surface bed")
            // its pelvis lands on the surface itself: not sunk into the mattress, not floating over it
            assertEquals(surface, fit.positions[0].z, 1e-3f, "factor $f, mattress $surface")
        }
    }

    @Test fun aSittingBodyRestsOnTheSeatUpToMatchTheRoomAndNeverSinksPastItAbove() {
        val sit = req(F.sitting(0.47))
        for (f in factors) CharacterSize.with(f) {
            val fit = FurnitureFit.fitAny(sit, chair(0.48f), SceneSimulation.HUMAN_BODY_UNIT)
            // up to Match the room (1.25) the Goldshire chair (0.48 yards) takes the sitter; a still bigger body would sink its feet
            // through the floor on so low a seat, so it is refused (the stage plays on the floor instead), never placed wrong
            if (f <= 1.25f) assertNotNull(fit, "factor $f")
            if (fit != null) assertEquals(0.48f, fit.positions[0].z, 1e-3f, "factor $f")
        }
    }

    @Test fun aPairOnTheBedLiesOnItAtAnyFactorWithItsSlotsInBodyUnits() {
        val side = req(F.lyingBack(0.55), moved(F.lyingBack(0.55), 0.0, 0.6))
        var slots: List<String>? = null
        for (f in factors) CharacterSize.with(f) {
            val unit = SceneSimulation.HUMAN_BODY_UNIT
            val fit = FurnitureFit.fitAny(side, bed(0.744f), unit)
            assertNotNull(fit, "factor $f")
            slots = slots ?: fit.slots   // (the bed's slots are furniture: which one a body takes may change with its size)
            // the two bodies stay 0.6 body units apart across the bed, whatever the size of the body unit
            val across = kotlin.math.hypot(fit.positions[1].x - fit.positions[0].x, fit.positions[1].y - fit.positions[0].y)
            assertEquals(0.6f * unit, across, 0.02f * unit, "factor $f")
            assertEquals(0.744f, fit.positions[0].z, 1e-3f)
        }
    }

    @Test fun aPairsContactIsTheSameInBodyUnitsAtEveryFactor() {
        // two standing bodies 0.1 body units apart: too near, so the second is moved clear; how far, in body units, never depends on the factor
        fun pair() = PartnerPair("p", "Standing", F.standing, moved(F.standing, 0.1, 0.0), PartnerSlot.human(0f, 0f, 0f, 0f),
            PartnerSlot.human(0.1f * BodyGeometry.REF_UNIT.toFloat(), 0f, 0f, 0f), true)
        var wanted: Double? = null
        for (f in factors) CharacterSize.with(f) {
            val p = pair()
            // the slots, from reference units, scale with the pair frame
            assertEquals(0.1f * BodyGeometry.REF_UNIT.toFloat() * HumanScale.PAIR, p.slotB.x, 1e-4f)
            val spaced = PairSpacing.spaced(p)
            val unit = SceneSimulation.HUMAN_BODY_UNIT.toDouble()
            val apart = (spaced.slotB.x - spaced.slotA.x) / unit
            wanted = wanted ?: apart
            assertEquals(wanted!!, apart, 1e-4, "factor $f")
        }
    }

    @Test fun theGroundsRoomForABodyDoesNotDependOnTheFactorInBodyUnits() {
        // a floor stage lowered to the floor drops by the same body units whatever the factor; its world drop grows with the body
        val unit1 = SceneSimulation.HUMAN_BODY_UNIT
        val lying = req(F.lyingBack(0.0), F.lyingFront(0.0).let { moved(it, -0.5, 0.0) })
        val at1 = FurnitureFit.fitAny(lying, bed(0.744f), unit1)!!.lift
        for (f in factors) CharacterSize.with(f) {
            // (a head-to-feet pair of much bigger bodies is longer than the bed and is refused there: it plays on the floor)
            val fit = FurnitureFit.fitAny(lying, bed(0.744f), SceneSimulation.HUMAN_BODY_UNIT)
            if (f <= 1.25f) assertNotNull(fit, "factor $f")
            val lift = fit?.lift ?: return@with
            // a floor-level clip played on a bed is raised by the bed's height: the furniture, not the body, so the same at every factor
            assertEquals(at1, lift, 0.02f, "factor $f")
        }
    }
}
