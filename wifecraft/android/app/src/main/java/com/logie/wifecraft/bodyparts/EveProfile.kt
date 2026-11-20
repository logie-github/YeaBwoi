package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.sims.SimsHash
import org.json.JSONObject

/**
 * How the Eve body's Sims rig maps onto Warcraft's skeleton (pure; [EveConvert] uses it):
 *
 * - [JOINTS]: the Sims adult woman's joints in Warcraft axes (x forward, y left, z up), Sims metres from her feet, left side
 *   (the right is the mirror). ESTIMATES, not a rig: made once from Eve's own skin weights (where two bones' weights blend
 *   most: the neck between Spine2 and Neck, the shoulder between the clavicle and the upper arm, and so on; the hip from
 *   the upper thigh's cross-section; the ankle carried down the shin to 0.09 m since Eve has no feet; the crotch the
 *   pelvis's lowest point on the midline; the fingers' knuckles (K), middle (P) and last (D) joints and tips (T)). The
 *   engine fits each Warcraft model by mapping these onto its own bones (engine/evebody.cpp).
 * - [wowBone]: each Sims bone's Warcraft name for the skin weights, from assets/sims_profile.json's bones (Spine0 to Spine1,
 *   Spine2 to Chest, Mid0 to Middle1, ...) with what the profile leaves out (its animations need no twist bones) added
 *   here: the upper-arm and forearm twists and the elbow helper to the Warcraft model's own twist and helper bones
 *   (L_UpperArmTwist, L_ForearmTwist, L_ElbowHelper: the engine finds them by key bone, else uses the limb bone), the
 *   thigh twist to the thigh, the breasts to Warcraft's breast bones, the fingers' last joints to the tips; the root, the
 *   buttocks, Anus and any bone this table doesn't know (WickedWhims' rig additions in the animated bottom) are folded
 *   into the Pelvis.
 */
object EveProfile {
    val JOINTS: Map<String, FloatArray> = linkedMapOf(
        "neck" to floatArrayOf(-0.0206f, 0.0000f, 1.6043f),
        "shoulder" to floatArrayOf(-0.0361f, 0.1997f, 1.4732f),
        "elbow" to floatArrayOf(-0.0430f, 0.3958f, 1.2928f),
        "wrist" to floatArrayOf(-0.0190f, 0.5836f, 1.0985f),
        "hip" to floatArrayOf(0.0018f, 0.0842f, 0.9492f),
        "knee" to floatArrayOf(0.0054f, 0.0870f, 0.5335f),
        "ankle" to floatArrayOf(-0.0302f, 0.1000f, 0.0900f),
        "calfend" to floatArrayOf(-0.0231f, 0.0974f, 0.1783f),
        "crotch" to floatArrayOf(0.0201f, 0.0030f, 0.9253f),
        "ThumbK" to floatArrayOf(0.0141f, 0.5966f, 1.0673f), "ThumbP" to floatArrayOf(0.0328f, 0.6038f, 1.0485f),
        "ThumbD" to floatArrayOf(0.0557f, 0.6091f, 1.0278f), "ThumbT" to floatArrayOf(0.0731f, 0.6159f, 1.0094f),
        "IndexK" to floatArrayOf(0.0207f, 0.6435f, 1.0372f), "IndexP" to floatArrayOf(0.0231f, 0.6712f, 1.0123f),
        "IndexD" to floatArrayOf(0.0261f, 0.6881f, 0.9956f), "IndexT" to floatArrayOf(0.0290f, 0.7090f, 0.9789f),
        "MidK" to floatArrayOf(-0.0022f, 0.6525f, 1.0304f), "MidP" to floatArrayOf(-0.0006f, 0.6726f, 1.0105f),
        "MidD" to floatArrayOf(0.0018f, 0.6958f, 0.9878f), "MidT" to floatArrayOf(0.0039f, 0.7178f, 0.9719f),
        "RingK" to floatArrayOf(-0.0247f, 0.6529f, 1.0307f), "RingP" to floatArrayOf(-0.0229f, 0.6722f, 1.0107f),
        "RingD" to floatArrayOf(-0.0210f, 0.6926f, 0.9914f), "RingT" to floatArrayOf(-0.0198f, 0.7131f, 0.9760f),
        "PinkyK" to floatArrayOf(-0.0428f, 0.6466f, 1.0342f), "PinkyP" to floatArrayOf(-0.0413f, 0.6678f, 1.0154f),
        "PinkyD" to floatArrayOf(-0.0405f, 0.6845f, 1.0010f), "PinkyT" to floatArrayOf(-0.0411f, 0.7019f, 0.9872f),
    )

    /** The Sims rig's body bones a CAS body mesh may name (short names: "Pelvis" for b__Pelvis__), for reading its hashes. */
    val SIMS_BONES: List<String> = buildList {
        addAll(listOf("ROOT", "ROOT_bind", "Pelvis", "Spine0", "Spine1", "Spine2", "Neck", "Head", "Jaw", "CAS_L_Breast", "CAS_R_Breast", "Anus"))
        for (s in listOf("Clavicle", "UpperArm", "ShoulderTwist", "Forearm", "ForearmTwist", "Elbow", "Hand", "Thigh", "ThighTwist", "Calf", "Foot", "Toe",
            "Thumb0", "Thumb1", "Thumb2", "Index0", "Index1", "Index2", "Mid0", "Mid1", "Mid2", "Ring0", "Ring1", "Ring2", "Pinky0", "Pinky1", "Pinky2", "Butt", "Eye"))
            for (side in listOf("L_", "R_")) add(side + s)
    }

    /** What sims_profile.json leaves out (or maps elsewhere for animation), for the skin weights. */
    private val EXTRA: Map<String, String> = buildMap {
        for (n in listOf("ROOT", "ROOT_bind", "Pelvis", "Anus", "L_Butt", "R_Butt")) put(n, "Pelvis")
        put("CAS_L_Breast", "L_Breast"); put("CAS_R_Breast", "R_Breast")
        for (s in listOf("L", "R")) {
            put("${s}_ShoulderTwist", "${s}_UpperArmTwist")
            put("${s}_Elbow", "${s}_ElbowHelper")
            put("${s}_ForearmTwist", "${s}_ForearmTwist")
            put("${s}_ThighTwist", "${s}_Thigh")
            put("${s}_Thumb2", "${s}_Thumb2"); put("${s}_Index2", "${s}_Index2"); put("${s}_Mid2", "${s}_Middle2")
            put("${s}_Ring2", "${s}_Ring2"); put("${s}_Pinky2", "${s}_Pinky2")
        }
    }

    /** sims_profile.json's bones as short Sims name -> Warcraft name ("b__Spine0__" -> "Spine0" -> "Spine1"). */
    fun profileBones(profileJson: String): Map<String, String> {
        val out = HashMap<String, String>()
        val bones = JSONObject(profileJson).optJSONArray("bones") ?: return out
        for (i in 0 until bones.length()) {
            val b = bones.getJSONObject(i)
            val sims = shortName(b.optString("sims")); val wow = b.optString("wow")
            if (sims.isNotEmpty() && wow.isNotEmpty()) out[sims] = wow
        }
        return out
    }

    /** "b__L_Calf__" -> "L_Calf". */
    fun shortName(sims: String): String = sims.removePrefix("b__").removeSuffix("__")

    /** The Warcraft name for the Sims bone [short] ([EXTRA] first, then the profile; anything else the Pelvis). */
    fun wowBone(short: String, profile: Map<String, String>): String = EXTRA[short] ?: profile[short] ?: "Pelvis"

    /** A GEOM's bone hashes as short names (an unknown hash: null, folded into the Pelvis). */
    fun namesOf(hashes: IntArray): List<String?> {
        val byHash = HashMap<Int, String>()
        for (n in SIMS_BONES) { byHash[SimsHash.fnv32("b__${n}__")] = n; byHash[SimsHash.fnv32("b__$n")] = n }
        return hashes.map { byHash[it] }
    }

    /** Sims model space (y up, the sim faces +z, her left +x) to Warcraft's axes (x forward, y left, z up). */
    fun toWow(x: Float, y: Float, z: Float, out: FloatArray, at: Int) { out[at] = z; out[at + 1] = x; out[at + 2] = y }
}
