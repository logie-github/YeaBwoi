package com.logie.wifecraft.tools

import com.logie.wifecraft.simulation.HandStroke
import com.logie.wifecraft.simulation.StrokeHands
import java.io.File
import kotlin.test.Test

/**
 * Writes the hand-stroke layer as the engine-test harness reads a bone layer (`layer N FILE`: "rot BONE t x y z w" lines) for the
 * pictures of README, "JOI mode": one file per tempo (JOI_HJ_TEMPOS, Hz, default "0.6,1.8") and hands (JOI_HJ_HANDS, default BOTH),
 * each already at full weight and constant tempo, 8 seconds. Only when JOI_HJ_OUT names the directory; otherwise nothing.
 */
class JoiHandStrokeRecorder {
    @Test fun record() {
        val out = System.getenv("JOI_HJ_OUT") ?: return
        File(out).mkdirs()
        val tempos = (System.getenv("JOI_HJ_TEMPOS") ?: "0.6,1.8").split(',').map { it.trim().toFloat() }
        val hands = StrokeHands.valueOf(System.getenv("JOI_HJ_HANDS") ?: "BOTH")
        for (tempo in tempos) {
            val a = HandStroke.bake("pic", 8f, 0.0, tempo, 1f, tempo, 1f, hands)
            val sb = StringBuilder("duration ${a.duration}\n")
            for (t in a.tracks) for (i in t.times.indices)
                sb.append("rot ${t.bone} %.4f %.6f %.6f %.6f %.6f\n".format(t.times[i], t.values[i * 4], t.values[i * 4 + 1], t.values[i * 4 + 2], t.values[i * 4 + 3]))
            File(out, "stroke_${hands.name.lowercase()}_%.1f.anim".format(tempo)).writeText(sb.toString())
        }
    }
}
