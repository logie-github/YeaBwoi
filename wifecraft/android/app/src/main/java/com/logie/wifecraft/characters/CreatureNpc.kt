package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Appearance
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A named character that is a creature, not a person (Sindragosa in her Wrath dragon form): drawn by the game's own display
 * ([display], the model, skeleton and textures as CreatureDisplayInfo has them), at [scale] (the model's own units are
 * far too big beside a person: her model is 33 yards nose to tail at 1) and kept to the game's own animations: no Sims clip
 * is ever retargeted onto her ([standAnimation] plays in every partner stage and when nothing else does). She can stand in
 * for either role of any two-person stage: the other person plays the stage as authored and she is put at her role's marker
 * ([delta]): her hindquarters on it ([shiftX], yards along her facing) and [clearance] yards farther from the other person
 * than the marker, so the two bodies don't pass through each other. Pure JVM.
 *
 * No measured flesh, no body parts and no insertion anchor of her own: a creature has none, so the simulation treats her as
 * the generic body (no measured profile) and the male-part aim finds no entry on her (it lets go: the shaft rests).
 */
data class CreatureSpec(
    val character: String,
    /** The game's CreatureDisplayInfo she is drawn as (Wrath's Sindragosa, creature 36853). */
    val display: Int,
    /** Her size: the display's CreatureModelScale times the model's own scale, replaced (the model is 33 yards long at 1). */
    val scale: Float,
    /** Yards her hindquarters stand ahead of her origin: the role's marker is put there. */
    val shiftX: Float,
    val shiftY: Float,
    /** Extra yards between her origin's marker and the other person, along the line between the two roles. */
    val clearance: Float,
    /** Where a male part's aim would meet her (yards over the floor): the lower anchor of a body with no legs apart. */
    val entryHeight: Float,
    /** Her stand animation (AnimationData 0), played in every partner stage (a loop: she breathes). */
    val standAnimation: Int = 0,
    /** Her speech bubble hangs this high (yards). */
    val head: Float = 2.6f,
)

object CreatureNpcs {
    val SINDRAGOSA = CreatureSpec(
        character = "sindragosa", display = 30362, scale = 0.25f,
        shiftX = 1.2f, shiftY = 0f, clearance = 0.9f, entryHeight = 0.9f,
    )

    val ALL: List<CreatureSpec> = listOf(SINDRAGOSA)

    fun of(character: String?): CreatureSpec? = ALL.firstOrNull { it.character == character }

    fun of(a: Appearance): CreatureSpec? = of(a.named?.character)

    /** The spec of the NPC with roster id [actorId] ("character:sindragosa"), or null. */
    fun ofActor(actorId: String): CreatureSpec? =
        if (NamedCast.isNamed(actorId)) of(actorId.removePrefix(NamedCast.PREFIX)) else null

    /**
     * The move that puts [spec]'s creature at her role's marker, in the pair's frame (x its front, y its left): her
     * hindquarters on it (along her own facing [facing], radians) and [CreatureSpec.clearance] away from the other role's
     * marker ([mx], [my]: hers; [ox], [oy]: the other's). Finite for every input (two roles on one spot: no clearance).
     */
    fun delta(spec: CreatureSpec, mx: Float, my: Float, ox: Float, oy: Float, facing: Float): FloatArray {
        val c = cos(facing); val s = sin(facing)
        var dx = spec.shiftX * c - spec.shiftY * s
        var dy = spec.shiftX * s + spec.shiftY * c
        val ux = mx - ox; val uy = my - oy
        val d = hypot(ux, uy)
        if (d > 1e-4f) { dx += spec.clearance * ux / d; dy += spec.clearance * uy / d }
        return floatArrayOf(dx, dy)
    }
}
