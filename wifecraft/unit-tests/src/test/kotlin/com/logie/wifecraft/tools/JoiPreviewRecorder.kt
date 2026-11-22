package com.logie.wifecraft.tools

import com.logie.wifecraft.simulation.JoiBody
import com.logie.wifecraft.simulation.JoiCamera
import com.logie.wifecraft.simulation.JoiFraming
import com.logie.wifecraft.story.Vec3
import java.io.File
import kotlin.test.Test

/**
 * Writes the engine-test harness's camera lines for JOI mode's real camera ([JoiCamera]): the three framings of a human woman
 * from her bone points (JOI_BONES: "hx hy hz cx cy cz px py pz" as the harness's `bonepoints` prints them) and Sindragosa's
 * orbit sampled along time. Only when JOI_OUT names the file; otherwise nothing. Used to make the framing sheets (README, "JOI mode").
 */
class JoiPreviewRecorder {
    @Test fun record() {
        val out = System.getenv("JOI_OUT") ?: return
        val v = (System.getenv("JOI_BONES") ?: "0 0 1.62 0 0 1.25 0 0 0.95").trim().split(Regex("\\s+")).map { it.toFloat() }
        val body = JoiBody().also { it.set(Vec3(v[0], v[1], v[2]), Vec3(v[3], v[4], v[5]), Vec3(v[6], v[7], v[8]), 0f, 1.71f, 0f, true) }
        val sb = StringBuilder()
        for (f in listOf(JoiFraming.FACE, JoiFraming.TORSO, JoiFraming.LEGS)) for (seed in listOf(11L, 12L)) {
            val cam = JoiCamera(seed); cam.framing = f
            cam.update(0.016f, body, 4f / 3f)
            // (a few frames in: the first cut's own angle)
            repeat(30) { cam.update(1f / 60f, body, 4f / 3f) }
            sb.append("framing ${f.label} seed $seed\n")
            sb.append("camera %.3f %.3f %.3f %.3f %.3f %.3f %.1f\n".format(cam.eyeX, cam.eyeY, cam.eyeZ, cam.atX, cam.atY, cam.atZ, cam.fov))
        }
        // auto: the cuts over 40 s
        run {
            val cam = JoiCamera(21L); cam.framing = JoiFraming.AUTO
            var t = 0f; var next = 0f
            while (t < 60f) {
                cam.update(1f / 60f, body, 4f / 3f)
                if (t >= next) {
                    sb.append("auto t=%.0f %s\n".format(t, cam.shot!!.framing.label))
                    sb.append("camera %.3f %.3f %.3f %.3f %.3f %.3f %.1f\n".format(cam.eyeX, cam.eyeY, cam.eyeZ, cam.atX, cam.atY, cam.atZ, cam.fov))
                    next += 7.5f
                }
                t += 1f / 60f
            }
        }
        // Sindragosa: the head's place by her spec, her orbit every 8 s
        run {
            val spec = com.logie.wifecraft.characters.CreatureNpcs.SINDRAGOSA
            val head = Vec3(spec.skullForward, spec.skullSide, spec.skullLift)
            val b = JoiBody().also { it.set(head, Vec3(1f, 0f, 1.3f), Vec3(-0.5f, 0f, 1.0f), 0f, 3f, 0f, false) }
            val cam = JoiCamera(2L); cam.creature = true; cam.creatureScale = spec.scale
            var t = 0f; var next = 0f
            while (t < 70f) {
                cam.update(1f / 60f, b, 4f / 3f)
                if (t >= next) {
                    val p = cam.orbitPose!!
                    sb.append("orbit t=%.0f az=%.0f r=%.2f h=%.2f\n".format(t, Math.toDegrees(p.az.toDouble()), p.radius, p.height))
                    sb.append("camera %.3f %.3f %.3f %.3f %.3f %.3f %.1f\n".format(cam.eyeX, cam.eyeY, cam.eyeZ, cam.atX, cam.atY, cam.atZ, cam.fov))
                    next += 8f
                }
                t += 1f / 60f
            }
        }
        File(out).writeText(sb.toString())
    }
}
