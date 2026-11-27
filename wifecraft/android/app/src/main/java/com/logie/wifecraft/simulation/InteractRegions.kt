package com.logie.wifecraft.simulation

// Pure Kotlin (JVM-tested: InteractModeTest): Interact mode's touch, step one. What a ray pick against her skinned body (the engine's
// Scene::pickBodyDetail: the body's triangles as posed, each vertex skinned) says is turned here into the part of the body that was
// touched and the garment slot it landed on. The engine's coarse region (bodypick.h: head, hair, neck, chest, waist, hips, legs, arms,
// hands, feet) is made finer by where on her the point is (its height over the crown, which side she is touched from, how far out from
// her middle line): the same numbers on every race and body, since they are fractions of her own height.

/** The parts of a body a touch can land on. */
enum class TouchRegion(val label: String) {
    NONE(""), HEAD("head"), FACE("face"), HAIR("hair"), NECK("neck"), SHOULDER("shoulder"), CHEST("chest"), BELLY("belly"), HIP("hip"),
    BUTT("butt"), THIGH("thigh"), KNEE("knee"), CALF("calf"), FOOT("foot"), HAND("hand"), BACK("back"), ARM("arm");

    companion object {
        /** The regions a player can name by touching (every one but NONE). */
        val TOUCHABLE: List<TouchRegion> = entries.filter { it != NONE }
    }
}

/** The garment slot a touch landed on: [BARE] her skin, else what the geoset hit belongs to (a gear piece's group). */
enum class GarmentSlot(val label: String) { BARE("bare skin"), HANDS("gloves"), FEET("boots"), SLEEVES("sleeves"), LEGS("legs' cover"), BACK("cloak"), TABARD("tabard"), HAIR("hair") }

/** What a touch landed on: the part, the slot, the side (-1 her right, 0 the middle, 1 her left) and whether it was from in front. */
data class TouchHit(val region: TouchRegion, val slot: GarmentSlot, val side: Int, val front: Boolean, val height: Float)

object RegionMap {
    /** The engine's coarse codes ([com.logie.wifecraft.creator.BodyRegion] and bodypick.h's BodyRegion). */
    const val C_HEAD = 1; const val C_HAIR = 2; const val C_NECK = 3; const val C_CHEST = 4; const val C_WAIST = 5; const val C_HIPS = 6
    const val C_LEGS = 7; const val C_ARMS = 8; const val C_HANDS = 9; const val C_FEET = 10

    // the heights of her body over her crown, 0 the floor, 1 the crown (Scene::pickBodyDetail's third number)
    const val SHOULDER_TOP = 0.86f
    const val CHEST_LOW = 0.69f
    const val BELLY_LOW = 0.56f
    const val HIP_LOW = 0.47f
    const val THIGH_LOW = 0.33f
    const val KNEE_LOW = 0.24f
    const val CALF_LOW = 0.08f
    /** How far a hit is round the side of her, in her own heights, before it counts as her arm's or hip's rather than front or back. */
    const val SIDE_WIDTH = 0.11f

    /** The garment slot of a hit geoset id (group = id / 100: 4 gloves, 5 boots, 8 sleeves, 9 and 13 the legs' cover, 11 and 12 the tabard, 15 the cloak). */
    fun slotOf(geoset: Int): GarmentSlot = when (geoset / 100) {
        0 -> if (geoset in 1..99) GarmentSlot.HAIR else GarmentSlot.BARE
        4 -> GarmentSlot.HANDS
        5 -> GarmentSlot.FEET
        8 -> GarmentSlot.SLEEVES
        9, 13 -> GarmentSlot.LEGS
        11, 12 -> GarmentSlot.TABARD
        15 -> GarmentSlot.BACK
        else -> GarmentSlot.BARE
    }

    /**
     * The touch [detail] (Native.pickBodyDetail: code with the side bits, side, height over the crown, forward, sideways, z, from the
     * front 1/0, geoset id) says; null when it missed her (no detail, or a code that is no body part).
     */
    fun classify(detail: FloatArray?): TouchHit? {
        if (detail == null || detail.size < 8) return null
        val code = detail[0].toInt() and 0xFF
        val side = detail[1].toInt().coerceIn(-1, 1)
        val h = detail[2]
        val across = detail[4]
        val front = detail[6] >= 0.5f
        val slot = if (code == C_HAIR) GarmentSlot.HAIR else slotOf(detail[7].toInt())
        val region = regionOf(code, h, across, front) ?: return null
        return TouchHit(region, slot, side, front, h)
    }

    /** [classify]'s rule on the numbers: the coarse [code], the height [h] over the crown, the sideways offset [across] (in her own heights) and the side touched. */
    fun regionOf(code: Int, h: Float, across: Float, front: Boolean): TouchRegion? = when (code) {
        C_HAIR -> TouchRegion.HAIR
        C_HEAD -> if (front) TouchRegion.FACE else TouchRegion.HEAD
        C_NECK -> TouchRegion.NECK
        C_HANDS -> TouchRegion.HAND
        C_FEET -> TouchRegion.FOOT
        C_ARMS -> if (h >= SHOULDER_TOP - 0.05f && kotlin.math.abs(across) < SIDE_WIDTH * 2.6f) TouchRegion.SHOULDER else TouchRegion.ARM
        C_CHEST -> when {
            h >= SHOULDER_TOP && kotlin.math.abs(across) > SIDE_WIDTH -> TouchRegion.SHOULDER
            !front -> TouchRegion.BACK
            else -> TouchRegion.CHEST
        }
        C_WAIST -> if (front) TouchRegion.BELLY else TouchRegion.BACK
        C_HIPS -> when {
            !front -> TouchRegion.BUTT
            h < BELLY_LOW && h >= HIP_LOW - 0.04f -> TouchRegion.HIP
            h >= BELLY_LOW -> TouchRegion.BELLY
            else -> TouchRegion.HIP
        }
        C_LEGS -> when {
            !front && h >= HIP_LOW - 0.04f -> TouchRegion.BUTT
            h >= THIGH_LOW -> TouchRegion.THIGH
            h >= KNEE_LOW -> TouchRegion.KNEE
            h >= CALF_LOW -> TouchRegion.CALF
            else -> TouchRegion.FOOT
        }
        else -> null
    }
}
