package com.logie.wifecraft.tools

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.simulation.CameraClearance
import com.logie.wifecraft.simulation.CinematicDirector
import com.logie.wifecraft.simulation.CinematicSettings
import com.logie.wifecraft.simulation.CinematicStage
import com.logie.wifecraft.simulation.ShotAction
import com.logie.wifecraft.simulation.ShotClass
import com.logie.wifecraft.simulation.ShotLibrary
import com.logie.wifecraft.simulation.WalkRoute
import com.logie.wifecraft.story.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/**
 * The shot library's preview stills (tools/film: engine-test/film draws the tracks this writes): for each position class, the
 * pair posed by anim/PoseFixtures' synthetic bodies on the inn room's floor (dressed in the film's default looks), the app's
 * director with the library picking that class's shots against the engine's own walk test, and one track per class whose
 * frames hold each distinct shot's camera (caught once its move is over). Not a test: does nothing unless SHOT_PREVIEW_OUT
 * names a folder (SHOT_PREVIEW_HEADER: a film track whose header is used (the room, the cast, the furniture), FILM_ROUTER the
 * built engine-test/build/film, FILM_FILES the game files). Desktop only. The poses stand in for real clips: framing only.
 */
class ShotPreviewRecorder {
    private class Router(binary: String, locations: String, id: String, files: String) {
        private val process = ProcessBuilder(binary, "--serve", locations, id, files).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        private val out = BufferedWriter(OutputStreamWriter(process.outputStream))
        private val inp = BufferedReader(InputStreamReader(process.inputStream))
        init { check(inp.readLine() == "ready") { "the walk test server didn't start" } }
        private fun ask(q: String): String { out.write(q); out.newLine(); out.flush(); return inp.readLine() ?: "" }
        fun clear(a: Vec3, b: Vec3) = ask("seg ${a.x} ${a.y} ${a.z} ${b.x} ${b.y} ${b.z}") == "1"
        fun ground(x: Float, y: Float, z: Float): Float = ask("ground $x $y $z").toFloatOrNull() ?: Float.NaN
        fun close() { runCatching { out.write("quit\n"); out.flush() }; process.waitFor() }
        fun route(from: Vec3, to: Vec3): List<Vec3>? = WalkRoute.route(from, to, ::clear, ::ground)
    }

    /** One person of a layout: their clip, where they are along (u) and across (v) the line, their facing from it, hip and head. */
    private class Body(val clip: String, val u: Float, val v: Float, val facing: Float, val hip: Float, val head: Float,
                       val headU: Float = 0f)

    private val pi = PI.toFloat()

    /** The classes' layouts: A then B, on the line (A to B along +u). */
    private fun layouts(): Map<ShotClass, List<Body>> = mapOf(
        ShotClass.KISS to listOf(Body("stand", 0f, 0f, 0f, 1.0f, 1.75f), Body("stand", 0.45f, 0f, pi, 1.0f, 1.75f)),
        ShotClass.STANDING to listOf(Body("stand", 0f, 0f, 0f, 1.0f, 1.75f), Body("stand", 0.4f, 0f, pi, 1.0f, 1.75f)),
        ShotClass.PARTNER to listOf(Body("stand", 0f, 0f, 0f, 1.0f, 1.75f), Body("stand", 0.8f, 0f, pi, 1.0f, 1.75f)),
        ShotClass.FACING_LYING to listOf(Body("kneel", -0.1f, 0f, 0f, 0.55f, 1.3f), Body("lieback", 0.4f, 0f, pi, 0.2f, 0.25f, 0.8f)),
        ShotClass.REAR to listOf(Body("kneel", 0f, 0f, 0f, 0.55f, 1.3f), Body("allfours", 0.45f, 0f, 0f, 0.5f, 0.6f, 0.75f)),
        ShotClass.RIDING to listOf(Body("lieback", 0f, 0f, pi, 0.2f, 0.25f, 0.8f), Body("kneel", 0.05f, 0f, 0f, 0.55f, 1.3f)),
        ShotClass.SEATED to listOf(Body("sit", 0f, 0f, pi / 2, 0.2f, 1.0f), Body("sit", 0.55f, 0f, pi / 2, 0.2f, 1.0f)),
        ShotClass.MANUAL to listOf(Body("sit", 0f, 0f, pi / 2, 0.2f, 1.0f), Body("sit", 0.45f, 0f, pi / 2, 0.2f, 1.0f)),
        ShotClass.SIDE_LYING to listOf(Body("lieback", 0f, 0f, pi / 2, 0.2f, 0.25f, 0.8f), Body("lieback", 0.5f, 0f, pi / 2, 0.2f, 0.25f, 0.8f)),
        ShotClass.ORAL to listOf(Body("stand", 0f, 0f, 0f, 1.0f, 1.75f), Body("kneel", 0.45f, 0f, pi, 0.55f, 1.3f)),
    )

    // the arms down at the sides (the fixtures leave them out straight: their framing would be judged by a T)
    private val armsDown = mapOf("L_UpperArm" to PoseFixtures.rotX(-75.0), "R_UpperArm" to PoseFixtures.rotX(75.0))

    private fun clipOf(name: String): Anim = when (name) {
        "stand" -> PoseFixtures.pose("stand", armsDown, 1.0)
        "kneel" -> PoseFixtures.pose("kneel", armsDown + mapOf("L_Calf" to PoseFixtures.rotY(90.0), "R_Calf" to PoseFixtures.rotY(90.0)), 0.567)
        "lieback" -> PoseFixtures.lyingBack(0.0, name = "lieback")
        "allfours" -> PoseFixtures.allFours(name = "allfours")
        "sit" -> PoseFixtures.sitting(0.0, name = "sit")
        else -> error(name)
    }.copy(loop = true)

    private fun animText(a: Anim): String = buildString {
        append("# ${a.name}\nduration ${a.duration}\n")
        if (a.loop) append("loop\n")
        for (t in a.tracks) {
            val per = if (t.channel == Channel.ROTATION) 4 else 3
            for (k in t.times.indices) {
                append(if (per == 4) "rot " else "pos ").append(t.bone).append(' ').append(t.times[k])
                for (c in 0 until per) append(' ').append(t.values[k * per + c])
                append('\n')
            }
        }
    }

    @Test fun record() {
        val outDir = System.getenv("SHOT_PREVIEW_OUT") ?: return
        val headerTrack = File(System.getenv("SHOT_PREVIEW_HEADER") ?: return)
        val routerBin = System.getenv("FILM_ROUTER") ?: return
        val files = System.getenv("FILM_FILES") ?: return
        val assets = File(System.getProperty("wifecraft.assets"))
        val lib = ShotLibrary.parse(File(assets, "shot_library.json").readText())
        val out = File(outDir).apply { mkdirs() }
        val header = JSONObject(headerTrack.bufferedReader().use { it.readLine() })
        val floor = header.getJSONArray("origin").getDouble(2).toFloat()
        val centre = Vec3(System.getenv("SHOT_PREVIEW_X")?.toFloat() ?: 5.9f, System.getenv("SHOT_PREVIEW_Y")?.toFloat() ?: 0.4f, floor)
        val lineYaw = System.getenv("SHOT_PREVIEW_YAW")?.toFloat() ?: 0f
        val router = Router(routerBin, File(assets, "locations.json").path, header.optString("location", "goldshire_inn"), files)
        val clearance = CameraClearance { eye, at -> router.route(at, eye)?.size == 1 }
        val boxes = ArrayList<Pair<Vec3, Vec3>>()
        val furn = header.optJSONArray("furniture") ?: JSONArray()
        for (i in 0 until furn.length()) {
            val f = furn.getJSONObject(i)
            val yaw = f.getDouble("yaw")
            val hx = f.getDouble("halfX"); val hy = f.getDouble("halfY")
            val ex = (kotlin.math.abs(cos(yaw)) * hx + kotlin.math.abs(sin(yaw)) * hy).toFloat()
            val ey = (kotlin.math.abs(sin(yaw)) * hx + kotlin.math.abs(cos(yaw)) * hy).toFloat()
            val x = f.getDouble("x").toFloat(); val y = f.getDouble("y").toFloat(); val z = f.getDouble("z").toFloat()
            boxes += Vec3(x - ex, y - ey, z) to Vec3(x + ex, y + ey, z + f.getDouble("top").toFloat())
        }
        val clipDir = File(out, "clips").apply { mkdirs() }
        val clips = JSONObject()
        for (name in listOf("stand", "kneel", "lieback", "allfours", "sit")) {
            val f = File(clipDir, "$name.anim"); f.writeText(animText(clipOf(name))); clips.put("prev:$name", f.absolutePath)
        }
        val ux = cos(lineYaw); val uy = sin(lineYaw)
        val summary = JSONObject()
        for ((cls, bodies) in layouts()) {
            // the bodies on the stage, the director's view of them
            val st = CinematicStage(2)
            st.count = 2
            val placed = bodies.map { b ->
                val x = centre.x + ux * b.u - uy * b.v; val y = centre.y + uy * b.u + ux * b.v
                val face = lineYaw + b.facing
                // the head along the body's own axis for one lying or on all fours (headU: forward for all fours, back for lying)
                val dir = if (b.clip == "lieback") -1f else 1f
                val hx = x + cos(face) * b.headU * dir; val hy = y + sin(face) * b.headU * dir
                floatArrayOf(x, y, face, hx, hy, floor + b.hip, floor + b.head)
            }
            for ((i, p) in placed.withIndex()) { st.person(i, p[0], p[1], p[5], p[3], p[4], p[6]); st.facing[i] = p[2] }
            st.floorZ = floor
            st.clearance = clearance
            st.obstacles = boxes
            st.aspect = 16f / 9f
            st.bodyHeight = 1.9f
            st.lineYaw = lineYaw
            st.action = ShotAction(cls)
            st.camera(centre.x - 3f, centre.y - 2f, floor + 2f, centre.x, centre.y, floor + 1f, 55f)
            val d = CinematicDirector(CinematicSettings(randomness = 30), seed = 17L + cls.ordinal).also { it.library = lib }
            val want = lib.set(cls).map { it.first.id }.toSet()
            val caught = LinkedHashMap<String, FloatArray>()
            val dt = 1f / 60f
            var t = 0f
            while (t < 400f && caught.keys != want) {
                d.update(dt, st); t += dt
                val s = d.shot ?: continue
                val id = d.spec?.id ?: continue
                if (id in caught) continue
                val at = if (s.cut) 1.2f else s.travel + minOf(1.5f, (s.seconds - s.travel) / 2f)
                if (d.shotTime >= at) caught[id] = floatArrayOf(d.eyeX, d.eyeY, d.eyeZ, d.atX, d.atY, d.atZ, d.fov)
            }
            if (caught.isEmpty()) { println("shots: $cls: none; history ${d.history.map { it.preset }.take(8)}; rejections ${d.rejections}"); continue }
            // the track: 3 s to settle on the first shot, then 12 records (3 s) a shot; film --every 12 draws each shot's first
            val track = File(out, "${cls.name}.jsonl").bufferedWriter()
            val h = JSONObject(header.toString()).put("clips", clips).put("cues", JSONArray()).put("plan", "shots_${cls.name}")
            track.write(h.toString()); track.newLine()
            val shots = caught.entries.toList()
            var rec = 0
            for ((k, e) in (listOf(shots.first()) + shots).withIndex()) {
                repeat(12) {
                    val people = JSONArray()
                    for ((i, p) in placed.withIndex()) {
                        people.put(JSONObject().put("x", p[0]).put("y", p[1]).put("z", floor).put("f", p[2]).put("pose", 0)
                            .put("sx", p[0]).put("sy", p[1]).put("sz", floor).put("hx", p[3]).put("hy", p[4]).put("hz", p[6])
                            .put("anim", 0).put("loop", true).put("animStart", 0).put("clip", "prev:${bodies[i].clip}").put("clipStart", 0)
                            .put("act", "PERFORM"))
                    }
                    track.write(JSONObject().put("type", "frame").put("t", rec * 250L).put("hour", 14.0).put("light", 14.0).put("day", 1)
                        .put("camera", JSONArray(e.value.map { it.toDouble() })).put("people", people)
                        .put("shot", if (k == 0) "settle" else e.key)
                        .put("cine", JSONArray(listOf(0.7, 0.7, 0.0, 0.0))).toString())
                    track.newLine()
                    rec++
                }
            }
            track.close()
            summary.put(cls.name, JSONArray(shots.map { it.key }))
            println("shots: $cls: ${shots.map { it.key }} (missing ${want - caught.keys}) in ${"%.0f".format(t)} s; rejections ${d.rejections}")
        }
        File(out, "shots.json").writeText(summary.toString(2))
        router.close()
    }
}
