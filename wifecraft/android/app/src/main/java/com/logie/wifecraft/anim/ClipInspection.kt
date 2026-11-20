package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import kotlin.math.abs

enum class ClipType(val label: String) {
    POSE("Poses"),
    LOOP("Loops"),
    ONE_SHOT("One-shot animations"),
    INVALID("Needs review"),
}

data class ClipInspection(val type: ClipType, val reason: String? = null) {
    companion object {
        private const val MOVING_ROTATION = 0.9998477 // cos(2° / 2)
        private const val LOOP_ROTATION = 0.9963897 // cos(9.74° / 2)

        fun inspect(anim: Anim): ClipInspection {
            fun invalid(reason: String) = ClipInspection(ClipType.INVALID, reason)
            // a stored clip's verdict is kept in the library's index (anim/ClipStore): its tracks are not read for it
            (anim.tracks as? StoredTracks)?.inspection?.let { return ClipStore.forDuration(it, anim.duration) }
            if (!anim.duration.isFinite() || anim.duration < 0f) return invalid("The duration is invalid.")
            if (anim.tracks.isEmpty()) return invalid("There are no bone tracks.")

            var moving = false
            var closes = true
            for (track in anim.tracks) {
                val rotation = track.channel == Channel.ROTATION
                val size = if (rotation) 4 else 3
                val frames = track.times.size
                if (frames == 0 || track.values.size.toLong() != frames.toLong() * size) {
                    return invalid("A bone track has missing keyframes.")
                }
                var last = -1f
                for (time in track.times) {
                    if (!time.isFinite() || time < 0f || time < last) return invalid("Keyframe times are invalid.")
                    last = time
                }
                val values = track.values
                for (offset in values.indices step size) {
                    var comparison = 0.0
                    var norm = 0.0
                    for (i in 0 until size) {
                        val value = values[offset + i].toDouble()
                        if (!value.isFinite()) return invalid("A bone keyframe is not finite.")
                        if (rotation) {
                            norm += value * value
                            comparison += value * values[i]
                        } else {
                            val delta = value - values[i]
                            comparison += delta * delta
                        }
                    }
                    if (rotation && abs(norm - 1.0) > 0.02) return invalid("A rotation keyframe is not normalized.")
                    val changed = if (rotation) abs(comparison) < MOVING_ROTATION else comparison > 0.015 * 0.015
                    moving = moving || changed
                    if (offset + size == values.size) {
                        val joins = if (rotation) abs(comparison) >= LOOP_ROTATION else comparison <= 0.08 * 0.08
                        closes = closes && joins
                    }
                }
            }
            return ClipInspection(when {
                !moving -> ClipType.POSE
                anim.duration <= 0f -> return invalid("The moving clip has no duration.")
                closes -> ClipType.LOOP
                else -> ClipType.ONE_SHOT
            })
        }
    }
}

data class CheckedAnimation(val clip: Anim, val inspection: ClipInspection = ClipInspection.inspect(clip)) {
    fun forPlayback(): Anim? = when (inspection.type) {
        ClipType.INVALID -> null
        ClipType.POSE -> clip.copy(duration = 0f, loop = false)
        ClipType.LOOP -> clip.copy(loop = true)
        ClipType.ONE_SHOT -> clip.copy(loop = false)
    }
}
