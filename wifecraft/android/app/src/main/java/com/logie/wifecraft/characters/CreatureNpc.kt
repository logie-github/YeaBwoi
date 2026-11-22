package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Appearance
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A named character that is a creature, not a person (Sindragosa in her Wrath dragon form): drawn by the game's own display
 * ([display], the model, skeleton and textures as CreatureDisplayInfo has them), at [scale] (the model is 33 yards nose to
 * tail at scale 1, the display's 2.5 would be 84), and kept to the game's own animations: no Sims clip is ever retargeted
 * onto her ([standAnimation] plays in every partner stage and when nothing else does). She can stand in for either role of
 * any two-person stage: the other person plays the stage as authored; she is turned side-on to them with her flank at the
 * role's marker, her hips ([hipX], yards from her origin along her body) over it, and [clearance] yards farther from them,
 * so the two bodies don't pass through each other ([place]; her tail would run through a person standing at her rump).
 * Pure JVM.
 *
 * No measured flesh, no body parts and no insertion anchor of her own: a creature has none, so the simulation treats her as
 * the generic body (no measured profile) and the male-part aim finds no entry on her (it lets go: the shaft rests).
 */
data class CreatureSpec(
    val character: String,
    /** The game's CreatureDisplayInfo she is drawn as (Wrath's Sindragosa, creature 36853). */
    val display: Int,
    /** Her size: replaces the display's CreatureModelScale times the model's own scale. */
    val scale: Float,
    /** Where her hips are along her body from her origin (yards at [scale]; negative: behind it). */
    val hipX: Float,
    /** Her flank: half the body's width at the hips (yards). */
    val flank: Float,
    /** Extra yards between her flank and the other person. */
    val clearance: Float,
    /** Where a male part's aim would meet her (yards over the floor): the lower anchor of a body with no legs apart. */
    val entryHeight: Float,
    /** Her stand animation (AnimationData 0), played in every partner stage (a loop: she breathes). */
    val standAnimation: Int = 0,
    /** Her speech bubble hangs this high (yards). */
    val head: Float = 2.6f,
    /**
     * Where her skull is from her origin at her [scale] (yards): [skullForward] along her body, [skullSide] to her left, [skullLift]
     * up. JOI mode's orbit circles it when the skeleton's head bone is not found. Placed by eye on a desktop render of her
     * stand animation (not measured from the bone).
     */
    val skullForward: Float = 2.15f,
    val skullSide: Float = -0.35f,
    val skullLift: Float = 1.75f,
)

object CreatureNpcs {
    val SINDRAGOSA = CreatureSpec(
        character = "sindragosa", display = 30362, scale = 0.25f,
        hipX = -0.8f, flank = 0.5f, clearance = 0.1f, entryHeight = 0.9f,
    )

    val ALL: List<CreatureSpec> = listOf(SINDRAGOSA)

    fun of(character: String?): CreatureSpec? = ALL.firstOrNull { it.character == character }

    fun of(a: Appearance): CreatureSpec? = of(a.named?.character)

    /** The spec of the NPC with roster id [actorId] ("character:sindragosa"), or null. */
    fun ofActor(actorId: String): CreatureSpec? =
        if (NamedCast.isNamed(actorId)) of(actorId.removePrefix(NamedCast.PREFIX)) else null

    /**
     * Where [spec]'s creature stands for the role whose marker is ([mx], [my]) against the other role's ([ox], [oy]), in
     * the pair's frame, and how she turns: (dx, dy, turn degrees), the move of her origin from the marker and her turn from
     * the role's [facing] (radians). She turns side-on: her left flank towards the other person, the hips' flank point on the
     * marker pushed [CreatureSpec.clearance] away. Finite for every input (two roles on one spot: faces along the role's
     * facing, no clearance).
     */
    fun place(spec: CreatureSpec, mx: Float, my: Float, ox: Float, oy: Float, facing: Float): FloatArray {
        var gx = ox - mx; var gy = oy - my
        val d = hypot(gx, gy)
        val yaw: Float
        if (d > 1e-4f) { gx /= d; gy /= d; yaw = atan2(gy, gx) - HALF_PI } else { gx = 0f; gy = 0f; yaw = facing }
        val c = cos(yaw); val s = sin(yaw)
        // marker = origin + hipX * forward + flank * left (left = forward turned 90 degrees)
        val fx = c; val fy = s; val lx = -s; val ly = c
        val dx = -spec.hipX * fx - spec.flank * lx - spec.clearance * gx
        val dy = -spec.hipX * fy - spec.flank * ly - spec.clearance * gy
        var turn = Math.toDegrees((yaw - facing).toDouble())
        while (turn > 180) turn -= 360
        while (turn < -180) turn += 360
        return floatArrayOf(dx, dy, turn.toFloat())
    }

    private const val HALF_PI = 1.5707964f
}
