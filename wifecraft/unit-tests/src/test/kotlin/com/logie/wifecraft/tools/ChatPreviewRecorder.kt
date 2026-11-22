package com.logie.wifecraft.tools

import com.logie.wifecraft.simulation.ChatCamera
import com.logie.wifecraft.simulation.JoiBody
import com.logie.wifecraft.story.Vec3
import java.io.File
import kotlin.test.Test

/**
 * Writes the engine-test harness's camera lines for Chat mode's real camera ([ChatCamera]): a human woman from her bone points
 * (CHAT_BONES: "hx hy hz cx cy cz px py pz" as the harness's `bonepoints` prints them; default a standing woman) at three moments of the
 * drift and in two picture shapes (16:9 and a phone held upright 9:19.5, where the picture is as wide as it is for the chat), and
 * Sindragosa seen from in front of her head. Only when CHAT_OUT names the file; otherwise nothing. Used for the pictures in README, "Chat mode".
 */
class ChatPreviewRecorder {
    @Test fun record() {
        val out = System.getenv("CHAT_OUT") ?: return
        val v = (System.getenv("CHAT_BONES") ?: "0 0 1.62 0 0 1.25 0 0 0.95").trim().split(Regex("\\s+")).map { it.toFloat() }
        val body = JoiBody().also { it.set(Vec3(v[0], v[1], v[2]), Vec3(v[3], v[4], v[5]), Vec3(v[6], v[7], v[8]), 0f, 1.71f, 0f, true) }
        val sb = StringBuilder()
        for ((name, aspect) in listOf("wide" to 16f / 9f, "phone" to 9f / 19.5f)) {
            val cam = ChatCamera(7L)
            var t = 0f
            for (at in listOf(0f, 9f, 17f)) {
                while (t < at + 0.01f) { cam.update(1f / 60f, body, aspect); t += 1f / 60f }
                sb.append("human $name t=%.0f\n".format(at))
                sb.append("camera %.3f %.3f %.3f %.3f %.3f %.3f %.1f\n".format(cam.eyeX, cam.eyeY, cam.eyeZ, cam.atX, cam.atY, cam.atZ, cam.fov))
            }
        }
        run {
            val spec = com.logie.wifecraft.characters.CreatureNpcs.SINDRAGOSA
            val head = Vec3(spec.skullForward, spec.skullSide, spec.skullLift)
            val b = JoiBody().also { it.set(head, Vec3(1f, 0f, 1.3f), Vec3(-0.5f, 0f, 1.0f), 0f, 3f, 0f, false) }
            val cam = ChatCamera(2L); cam.creature = true; cam.creatureScale = spec.scale
            var t = 0f
            for (at in listOf(0f, 9f, 17f)) {
                while (t < at + 0.01f) { cam.update(1f / 60f, b, 16f / 9f); t += 1f / 60f }
                sb.append("sindragosa t=%.0f\n".format(at))
                sb.append("camera %.3f %.3f %.3f %.3f %.3f %.3f %.1f\n".format(cam.eyeX, cam.eyeY, cam.eyeZ, cam.atX, cam.atY, cam.atZ, cam.fov))
            }
        }
        File(out).writeText(sb.toString())
    }
}
