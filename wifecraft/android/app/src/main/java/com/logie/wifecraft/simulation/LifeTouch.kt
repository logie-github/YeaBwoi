package com.logie.wifecraft.simulation

// Pure Kotlin (JVM-tested: LifeTouchTest): how a touch on her reaches the engine's life layer (cpp/engine/life.h): what kind of start it is, where it
// was (the WoW-side bone near that part, so her head and eyes turn toward it), and how hard.
object LifeTouch {
    /** life.h's Event ids. */
    const val FLINCH = 1; const val LEAN_IN = 2; const val STEP_BACK = 3; const val GIGGLE = 4; const val ATTEND = 5

    fun event(k: ReactionKind): Int = when (k) {
        ReactionKind.FLINCH, ReactionKind.SHIVER, ReactionKind.SWAT -> FLINCH
        ReactionKind.LEAN_IN -> LEAN_IN
        ReactionKind.STEP_BACK -> STEP_BACK
        ReactionKind.GIGGLE, ReactionKind.SMILE, ReactionKind.BLUSH -> GIGGLE
        ReactionKind.TURN_HEAD, ReactionKind.LOOK_AWAY -> ATTEND
    }

    /** The bone whose place stands for [r] (WoW-side names, as the bone layers use; empty: none). */
    fun bone(r: TouchRegion): String = when (r) {
        TouchRegion.NONE -> ""
        TouchRegion.HEAD, TouchRegion.HAIR, TouchRegion.FACE -> "Head"
        TouchRegion.NECK -> "Neck1"
        TouchRegion.SHOULDER -> "L_Clavicle"
        TouchRegion.CHEST, TouchRegion.BACK -> "Chest"
        TouchRegion.BELLY -> "Spine1"
        TouchRegion.HIP, TouchRegion.BUTT, TouchRegion.GROIN -> "Pelvis"
        TouchRegion.THIGH -> "L_Thigh"
        TouchRegion.KNEE -> "L_Calf"
        TouchRegion.CALF, TouchRegion.FOOT -> "L_Foot"
        TouchRegion.HAND -> "L_Hand"
        TouchRegion.ARM -> "L_UpperArm"
    }

    /** How hard the start is 0..1: the tenderer the part the bigger, a pinch more than a tap, a stroke less. */
    fun strength(region: TouchRegion, kind: TouchKind): Float {
        val base = 0.35f + 0.55f * TouchConsent.sensitivity(region)
        return (when (kind) { TouchKind.PINCH -> base * 1.25f; TouchKind.STROKE -> base * 0.5f; TouchKind.PULL -> base * 1.1f; TouchKind.TAP -> base }).coerceIn(0.1f, 1f)
    }
}
