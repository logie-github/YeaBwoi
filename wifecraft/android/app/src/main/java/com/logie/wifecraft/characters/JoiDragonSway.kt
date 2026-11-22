package com.logie.wifecraft.characters

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.ClipLayers
import com.logie.wifecraft.anim.ClipTuning
import com.logie.wifecraft.anim.PoseKey
import com.logie.wifecraft.anim.PoseTrack
import kotlin.random.Random

/**
 * JOI mode's procedural layer for a creature NPC (Sindragosa): her own stand animation keeps playing (no person's clip is
 * ever put on her) and this lays slow sways of her neck and head over it, as the engine's bone layer takes them (rotation
 * tracks named "#N", the model's bone N; no held bones, so her breathing, wings and tail go on under it). Degrees. Pure JVM.
 */
object JoiDragonSway {
    /** Sindragosa's neck and head bones by index (the table of [DragonStages.PET_HEAD]: frostwurmnorthrend has no names in the file). */
    val BONES = mapOf("neck1" to 21, "neck2" to 24, "neck3" to 31, "head" to 42)
    /** One loop, seconds, and the keys' spacing. */
    const val LOOP = 24f
    const val STEP = 4f
    /** The largest angle any key turns a bone (degrees), at the lively end. */
    const val MAX_DEG = 11f

    /** The layer for [energy] (0..1: livelier sways with it) from [seed]; loops (its last key is its first). */
    fun layer(energy: Float, seed: Long): Anim {
        val rnd = Random(seed)
        val e = energy.coerceIn(0f, 1f)
        val amp = 0.55f + 0.45f * e
        // (pitch, yaw, roll) each bone's own share: the neck bends along its length, the head turns most
        val share = mapOf("neck1" to 0.45f, "neck2" to 0.6f, "neck3" to 0.75f, "head" to 1f)
        val tracks = BONES.keys.map { name ->
            val s = share.getValue(name)
            val n = (LOOP / STEP).toInt()
            val first = floatArrayOf(0f, 0f, 0f)
            val keys = ArrayList<PoseKey>()
            for (i in 0..n) {
                val t = i * STEP
                val v = if (i == 0 || i == n) first else floatArrayOf(
                    (rnd.nextFloat() * 2f - 1f) * 3.2f * amp * s,
                    (rnd.nextFloat() * 2f - 1f) * MAX_DEG * 0.7f * amp * s,
                    (rnd.nextFloat() * 2f - 1f) * 2.4f * amp * s)
                keys += PoseKey(t, v[0], v[1], v[2])
            }
            PoseTrack(name, keys)
        }
        return ClipLayers.bake("joi:sway", "JOI sway", tracks, LOOP, true, ClipTuning(1f, 1f, 15)) { "#" + BONES.getValue(it) }
    }
}
