package com.logie.wifecraft.tools

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.simulation.CameraClearance
import com.logie.wifecraft.simulation.CinematicDirector
import com.logie.wifecraft.simulation.CinematicSettings
import com.logie.wifecraft.simulation.CinematicStage
import com.logie.wifecraft.simulation.Furniture
import com.logie.wifecraft.simulation.FurnitureFocus
import com.logie.wifecraft.simulation.PropSolids
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
 * pair posed by anim/PoseFixtures' synthetic bodies on the inn room's floor (dressed in the film's default looks), and the
 * lying classes again on the room's bed ("<CLASS>_BED": the Lion's Pride's canopy bed, raised onto its mattress), the app's
 * director with the library picking that class's shots against the engine's own walk test and the room's furniture as the
 * app has it (locations.json's real extents: the boxes and PropSolids' sight blockers, the canopy's), and one track per scene
 * whose frames hold each distinct shot's camera (caught once its move is over). Not a test: does nothing unless
 * SHOT_PREVIEW_OUT names a folder (SHOT_PREVIEW_HEADER: a film track whose header is used (the room, the cast), FILM_ROUTER
 * the built engine-test/build/film, FILM_FILES the game files; SHOT_PREVIEW_CLASSES: the scenes to record, comma separated,
 * else all). Desktop only. The poses stand in for real clips: framing only.
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
        // the room's furniture as the app has it (locations.json, the measured extents; the stage is the location's frame)
        val location = header.optString("location", "goldshire_inn")
        val preset = LocationInfo.parseAll(File(assets, "locations.json").readText()).first { it.id == location }
        val pieces = preset.furniture.mapNotNull { Furniture.of(it) }
        val boxes = pieces.map { it.bounds() }
        val solids = pieces.flatMap { PropSolids.world(it) }
        val bed = pieces.firstOrNull { it.kind.bed }
        val only = System.getenv("SHOT_PREVIEW_CLASSES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val clipDir = File(out, "clips").apply { mkdirs() }
        val clips = JSONObject()
        for (name in listOf("stand", "kneel", "lieback", "allfours", "sit")) {
            val f = File(clipDir, "$name.anim"); f.writeText(animText(clipOf(name))); clips.put("prev:$name", f.absolutePath)
        }
        // the scenes: each class on the floor, and the lying ones on the bed (its mattress's middle, along its length: the
        // lying body's head toward the headboard)
        class Scene(val name: String, val cls: ShotClass, val bodies: List<Body>, val at: Vec3, val yaw: Float, val on: Furniture?)
        val scenes = ArrayList<Scene>()
        for ((cls, bodies) in layouts()) scenes += Scene(cls.name, cls, bodies, centre, lineYaw, null)
        if (bed != null) {
            val mid = bed.toWorld(0f, 0f, bed.spec.surface)
            // the bed's head is toward its own -x: the lying body's head (along the line) that way
            val headward = bed.yaw + PI.toFloat()
            for (cls in listOf(ShotClass.RIDING, ShotClass.FACING_LYING, ShotClass.SIDE_LYING))
                scenes += Scene("${cls.name}_BED", cls, layouts().getValue(cls), mid, headward, bed)
        }
        val summary = JSONObject()
        for (sc in scenes) {
            if (only != null && sc.name !in only) continue
            val cls = sc.cls; val bodies = sc.bodies
            val ux = cos(sc.yaw); val uy = sin(sc.yaw)
            val base = sc.at.z
            // the bodies on the stage, the director's view of them
            val st = CinematicStage(2)
            st.count = 2
            val placed = bodies.map { b ->
                val x = sc.at.x + ux * b.u - uy * b.v; val y = sc.at.y + uy * b.u + ux * b.v
                val face = sc.yaw + b.facing
                // the head along the body's own axis for one lying or on all fours (headU: forward for all fours, back for lying)
                val dir = if (b.clip == "lieback") -1f else 1f
                val hx = x + cos(face) * b.headU * dir; val hy = y + sin(face) * b.headU * dir
                floatArrayOf(x, y, face, hx, hy, base + b.hip, base + b.head)
            }
            for ((i, p) in placed.withIndex()) { st.person(i, p[0], p[1], p[5], p[3], p[4], p[6]); st.facing[i] = p[2] }
            st.floorZ = floor
            st.clearance = clearance
            st.obstacles = boxes
            st.solids = solids
            st.canopies = pieces.mapNotNull { PropSolids.canopyBox(it) }
            st.aspect = 16f / 9f
            st.bodyHeight = 1.9f
            st.lineYaw = sc.yaw
            st.action = ShotAction(cls)
            sc.on?.let { f ->
                val pts = placed.flatMap { listOf(Vec3(it[0], it[1], it[5]), Vec3(it[3], it[4], it[6])) }
                val c = Vec3(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat(), pts.map { it.z }.average().toFloat())
                st.furniture = FurnitureFocus(f.id, c, emptyList(), kotlin.math.hypot(f.spec.halfX, f.spec.halfY), f.bounds(), f.z + f.spec.surface)
            }
            st.camera(sc.at.x - 3f, sc.at.y - 2f, floor + 2f, sc.at.x, sc.at.y, floor + 1f, 55f)
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
            if (caught.isEmpty()) { println("shots: ${sc.name}: none; history ${d.history.map { it.preset }.take(8)}; rejections ${d.rejections}"); continue }
            // the track: 3 s to settle on the first shot, then 12 records (3 s) a shot; film --every 12 draws each shot's first
            val track = File(out, "${sc.name}.jsonl").bufferedWriter()
            val h = JSONObject(header.toString()).put("clips", clips).put("cues", JSONArray()).put("plan", "shots_${sc.name}")
            track.write(h.toString()); track.newLine()
            val shots = caught.entries.toList()
            var rec = 0
            for ((k, e) in (listOf(shots.first()) + shots).withIndex()) {
                repeat(12) {
                    val people = JSONArray()
                    for ((i, p) in placed.withIndex()) {
                        // (on the bed: Scene::kRaised, 3, drawn at its height, not stood on the floor)
                        people.put(JSONObject().put("x", p[0]).put("y", p[1]).put("z", base).put("f", p[2]).put("pose", if (sc.on != null) 3 else 0)
                            .put("sx", p[0]).put("sy", p[1]).put("sz", base).put("hx", p[3]).put("hy", p[4]).put("hz", p[6])
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
            summary.put(sc.name, JSONArray(shots.map { it.key }))
            println("shots: ${sc.name}: ${shots.map { it.key }} (missing ${want - caught.keys}) in ${"%.0f".format(t)} s; rejections ${d.rejections}")
        }
        File(out, "shots.json").writeText(summary.toString(2))
        router.close()
    }
}
