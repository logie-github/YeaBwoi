package com.logie.wifecraft.tools

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.catalog.EmotePlayability
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.simulation.Activity
import com.logie.wifecraft.simulation.Chatter
import com.logie.wifecraft.simulation.ChatterFrequency
import com.logie.wifecraft.simulation.ClipPlayback
import com.logie.wifecraft.simulation.ClockSpeed
import com.logie.wifecraft.simulation.Furniture
import com.logie.wifecraft.simulation.FurnitureAnimation
import com.logie.wifecraft.simulation.InteractionId
import com.logie.wifecraft.simulation.InteractionSpot
import com.logie.wifecraft.simulation.LifeProfile
import com.logie.wifecraft.simulation.LifeSim
import com.logie.wifecraft.simulation.LifeSpeed
import com.logie.wifecraft.simulation.LiveScene
import com.logie.wifecraft.simulation.ModelEmotes
import com.logie.wifecraft.simulation.Need
import com.logie.wifecraft.simulation.PartnerEmotes
import com.logie.wifecraft.simulation.PartnerFrequency
import com.logie.wifecraft.simulation.PartnerPair
import com.logie.wifecraft.simulation.PartnerSlot
import com.logie.wifecraft.simulation.PlaybackKeys
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.SceneSnapshot
import com.logie.wifecraft.simulation.Speaker
import com.logie.wifecraft.simulation.SpeechLayout
import com.logie.wifecraft.simulation.WorldClock
import com.logie.wifecraft.simulation.WalkRoute
import com.logie.wifecraft.simulation.LifeProps
import com.logie.wifecraft.simulation.CameraClearance
import com.logie.wifecraft.story.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test

/**
 * The desktop scene film's recorder (tools/film/README.md): the app's own live scene ([LiveScene] with its [LifeSim], the
 * NPCs' chatter, the location's furniture and spots, a [WorldClock] at x6) built the way MainActivity and
 * SceneSessionScreen build it, driven at 60 frames a second with a fake clock, with a few forced moments (someone hungry,
 * someone tired, the player's orders), and written as a track: a header, a frame record every [SAMPLE_MS] real
 * milliseconds (each person as the screen's sink placed and animated them, their words, thought, mood and needs, the camera,
 * the hour) and every sink call as an event. engine-test/film draws the track. The walks take the app's routes: the
 * engine's own walk test (film --serve) answers SceneView.glWalkRoute's questions.
 *
 * Not a test: it does nothing unless FILM_OUT names a folder (FILM_ROUTER the built engine-test/build/film, FILM_FILES the
 * game files, FILM_SCENES "embassy,goldshire,bedpair", FILM_HOURS game hours, default 24; the unrouted bedpair at most 4). Desktop only.
 */
class SceneFilmRecorder {
    companion object {
        const val SAMPLE_MS = 250L
        const val FPS = 60
    }

    private class CastMember(val id: String, val name: String, val race: Int, val female: Boolean, val look: String)

    /**
     * A forced moment: at [seconds] (real), [what] is done on the scene (inside a frame's commands); it answers whether it
     * started (false: tried again 5 s later, at most 20 times) and passes the scene's answers to [Ctx.log].
     */
    private class Cue(val seconds: Double, val label: String, val what: (Ctx) -> Boolean)
    private class Ctx(val scene: LiveScene, val life: LifeSim, val sim: com.logie.wifecraft.simulation.SceneSimulation, val log: (String) -> Unit) {
        fun interact(a: String, b: String?, id: InteractionId): Boolean {
            val r = sim.lifeInteract(a, b, id)
            log("${id.label}: ${if (r.accepted) "accepted" else "refused"} (${r.message})")
            return true
        }
    }

    private class Plan(val name: String, val location: String, val cast: List<CastMember>, val pairs: List<PartnerPair>,
                       val cues: List<Cue>, val seed: Int, val routed: Boolean = true)

    /** The engine's walk test over a pipe (engine-test/film --serve). */
    private class Router(binary: String, locations: String, id: String, files: String) {
        private val process = ProcessBuilder(binary, "--serve", locations, id, files).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        private val out = BufferedWriter(OutputStreamWriter(process.outputStream))
        private val inp = BufferedReader(InputStreamReader(process.inputStream))
        var asked = 0
        init { check(inp.readLine() == "ready") { "the walk test server didn't start" } }
        private fun ask(q: String): String { asked++; out.write(q); out.newLine(); out.flush(); return inp.readLine() ?: "" }
        fun clear(a: Vec3, b: Vec3) = ask("seg ${a.x} ${a.y} ${a.z} ${b.x} ${b.y} ${b.z}") == "1"
        fun ground(x: Float, y: Float, z: Float): Float = ask("ground $x $y $z").toFloatOrNull() ?: Float.NaN
        fun close() { runCatching { out.write("quit\n"); out.flush() }; process.waitFor() }

        /** SceneView.glWalkRoute: the same WalkRoute, the engine's walk test over the pipe (stage coordinates). */
        fun route(from: Vec3, to: Vec3): List<Vec3>? = WalkRoute.route(from, to, ::clear, ::ground)
    }

    private fun plans(): List<Plan> {
        // two synthetic two-role clips (anim/PoseFixtures' bodies) as library pairs, as FurnitureSceneTest makes them
        fun pair(id: String, name: String, a: Anim, b: Anim, dy: Double) = PartnerPair(id, name, a, b, PartnerSlot.human(0f, 0f, 0f, 0f),
            PartnerSlot.human(0f, (dy * BodyGeometry.REF_UNIT).toFloat(), 0f, 0f), true)
        val cuddle = pair("cuddle", "Cuddle", PoseFixtures.lyingBack(0.55, name = "cuddle_a"), PoseFixtures.lyingBack(0.55, name = "cuddle_b"), 0.6)
        val sitTogether = pair("sit2", "Sit together", PoseFixtures.sitting(0.45, name = "sit_a"), PoseFixtures.sitting(0.45, name = "sit_b"), 0.55)
        return listOf(
            Plan("embassy", "embassy", listOf(
                CastMember("elena", "Elena Ashford", 1, true, "human_f"),
                CastMember("brannoc", "Brannoc Stonebrew", 3, false, "dwarf_m"),
                CastMember("sylvara", "Sylvara Moonbrook", 4, true, "nightelf_f"),
                CastMember("fizzwick", "Fizzwick Sprocket", 7, false, "gnome_m"),
            ), emptyList(), listOf(
                Cue(4.0, "Brannoc gets hungry") { c -> c.life.setNeed("brannoc", Need.HUNGER, 0.08f); true },
                Cue(12.0, "player: Elena chats with Sylvara") { c -> c.interact("elena", "sylvara", InteractionId.CHAT) },
                Cue(45.0, "player: Fizzwick dances with Elena") { c -> c.interact("fizzwick", "elena", InteractionId.DANCE_TOGETHER) },
                Cue(80.0, "player: Sylvara tells Brannoc a joke") { c -> c.interact("sylvara", "brannoc", InteractionId.JOKE) },
                Cue(120.0, "Elena is exhausted (no bed here)") { c -> c.life.setNeed("elena", Need.ENERGY, 0.05f); true },
            ), 7),
            Plan("goldshire", "goldshire_inn", listOf(
                CastMember("aldric", "Aldric Vane", 1, false, "human_m"),
                CastMember("nerana", "Nerana", 11, true, "draenei_f"),
            ), listOf(cuddle, sitTogether), listOf(
                Cue(3.0, "Aldric gets hungry") { c -> c.life.setNeed("aldric", Need.HUNGER, 0.08f); true },
                Cue(6.0, "Nerana is tired") { c -> c.life.setNeed("nerana", Need.ENERGY, 0.06f); true },
                Cue(40.0, "player: Aldric chats with Nerana") { c -> c.interact("aldric", "nerana", InteractionId.CHAT) },
                Cue(150.0, "player: Cuddle on the bed (partner animation)") { c -> c.sim.partnerOn(SceneObject.BED, "cuddle", "nerana", "aldric").also { ok ->
                    val pair = c.sim.pairs.first { it.id == "cuddle" }
                    val r = c.sim.requirementOf(pair)
                    val bed = c.sim.furniture(SceneObject.BED)
                    val fit = if (r != null && bed != null) com.logie.wifecraft.simulation.FurnitureFit.fit(r, bed, com.logie.wifecraft.simulation.SceneSimulation.HUMAN_BODY_UNIT) else null
                    c.log(if (ok) "started" else "not now: needs ${r?.surface} ${r?.basis}, bed ${bed?.kind} surface ${bed?.spec?.surface}, fit $fit, " +
                        "bed held by ${c.sim.holder(SceneObject.BED)}, people ${c.sim.people().map { "${it.id}:${it.activity}:${it.directed}" }}")
                } },
                Cue(110.0, "player: Nerana dances with Aldric") { c -> c.interact("nerana", "aldric", InteractionId.DANCE_TOGETHER) },
                Cue(130.0, "player: Aldric invites Nerana to sit") { c -> c.interact("aldric", "nerana", InteractionId.INVITE_SIT) },
            ), 11),
            // the same inn without the engine's walk test (straight walks, as the JVM tests walk): the bed's pair, which the
            // walk test never lets them reach (see the goldshire track's cue answers)
            Plan("bedpair", "goldshire_inn", listOf(
                CastMember("aldric", "Aldric Vane", 1, false, "human_m"),
                CastMember("nerana", "Nerana", 11, true, "draenei_f"),
            ), listOf(cuddle, sitTogether), listOf(
                Cue(3.0, "player: Cuddle on the bed (partner animation)") { c -> c.sim.partnerOn(SceneObject.BED, "cuddle", "nerana", "aldric").also { ok ->
                    val pair = c.sim.pairs.first { it.id == "cuddle" }
                    val r = c.sim.requirementOf(pair)
                    val bed = c.sim.furniture(SceneObject.BED)
                    val fit = if (r != null && bed != null) com.logie.wifecraft.simulation.FurnitureFit.fit(r, bed, com.logie.wifecraft.simulation.SceneSimulation.HUMAN_BODY_UNIT) else null
                    c.log(if (ok) "started" else "not now: needs ${r?.surface} ${r?.basis}, bed ${bed?.kind} surface ${bed?.spec?.surface}, fit $fit, " +
                        "bed held by ${c.sim.holder(SceneObject.BED)}, people ${c.sim.people().map { "${it.id}:${it.activity}:${it.directed}" }}")
                } }
            ), 11, routed = false),
        )
    }

    @Test fun record() {
        val outDir = System.getenv("FILM_OUT") ?: return
        val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
        val router = System.getenv("FILM_ROUTER")
        val files = System.getenv("FILM_FILES")
        val hours = System.getenv("FILM_HOURS")?.toDoubleOrNull() ?: 24.0
        val wanted = (System.getenv("FILM_SCENES") ?: "embassy,goldshire,bedpair").split(',').map { it.trim() }
        File(outDir).mkdirs()
        for (plan in plans().filter { it.name in wanted }) record(plan, assets, File(outDir), router, files, if (plan.routed) hours else minOf(hours, 4.0))
    }

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

    /** SceneSessionScreen's lifted(): a pair role's slot height carried in its Root track. */
    private fun lifted(a: Anim, lift: Float): Anim = if (lift == 0f) a else a.copy(tracks = a.tracks.map { t ->
        if (t.bone == "Root" && t.channel == Channel.POSITION) BoneTrack(t.bone, t.channel, t.times, t.values.copyOf().also { v ->
            for (i in 2 until v.size step 3) v[i] += lift
        }) else t
    })

    private fun emoteHeld(anim: Int): Boolean =
        PartnerEmotes.ALL.firstOrNull { it.anim == anim }?.let { it.loop || EmotePlayability.heldByName(it.name) } ?: false

    private fun record(plan: Plan, assets: File, out: File, routerBin: String?, files: String?, hours: Double) {
        val locationsJson = File(assets, "locations.json").readText()
        val preset = LocationInfo.parseAll(locationsJson).first { it.id == plan.location }
        val indoor = preset.views.getValue("inside")
        val room = checkNotNull(indoor.camera)
        // MainActivity's stage: the scene's origin at the inside view's spawn, the Embassy turned a quarter
        val origin = indoor.spawn ?: Vec3(0f, 0f, 0f)
        val embassy = preset.id == "embassy"
        val rotation = if (embassy) (-Math.PI / 2).toFloat() else 0f
        val toStage: (Vec3) -> Vec3 = { p -> origin + if (embassy) Vec3(p.y, -p.x, p.z) else p }
        val toLocal: (Vec3) -> Vec3 = { p -> val d = p - origin; if (embassy) Vec3(-d.y, d.x, d.z) else d }
        val objects = preset.interactions.mapNotNull { i -> SceneObject.entries.firstOrNull { it.name == i.item } }.toSet()
        val ids = plan.cast.map { it.id }
        val router = if (plan.routed && routerBin != null && files != null) Router(routerBin, File(assets, "locations.json").path, plan.location, files) else null

        // MainActivity: the life and the world clock (start 08:00; Options' x6); SceneSessionScreen: the rest
        val life = LifeSim(ids, plan.cast.associate { it.id to LifeProfile(it.name, it.race, it.female) }, seed = plan.seed.toLong())
        val clock = WorldClock().also { it.start(8.0); it.speed = ClockSpeed.X6; it.cycle = true }
        val words = Chatter(plan.cast.associate { it.id to Speaker(it.name, it.race, it.female) }, preset.name)
        val o = toStage(Vec3(0f, 0f, 0f))
        val live = LiveScene(ids, objects, emptyMap(), room, o.x, o.y, o.z, rotation, Random(plan.seed),
            wanderOnly = true, route = router?.let { r -> { from: Vec3, to: Vec3 -> r.route(toStage(from), toStage(to))?.map(toLocal) } },
            pairs = plan.pairs, emotes = ModelEmotes.STANDARD, partnerChance = PartnerFrequency.SOMETIMES.chance,
            chatter = words, chatterChance = ChatterFrequency.SOMETIMES.chance, lifeSim = life)
        life.onSpeed = { s -> clock.speed = if (s == LifeSpeed.PAUSE) ClockSpeed.PAUSED else ClockSpeed.X6 }
        live.life.setTimeSource(clock)
        live.life.setEnabled(true)
        live.command { sim -> sim.life?.setSpeed(LifeSpeed.FASTEST) }
        val heads = FloatArray(plan.cast.size) { HumanScale.head(plan.cast[it].race, plan.cast[it].female) }
        val pairSizes = plan.cast.associate { it.id to HumanScale.pairScale(it.race, it.female) }
        val bodyScales = plan.cast.associate { it.id to HumanScale.bodyScale(it.race, it.female) }
        val needs = plan.pairs.associate { it.id to FurnitureAnimation.of(it).requirement }
        live.command { sim -> sim.sizes = pairSizes; sim.bodyScales = bodyScales; sim.requirements = needs }
        val furniture = HashMap<SceneObject, Furniture>()
        for (item in objects) preset.furniture.firstOrNull { it.item == item.name }?.let { piece ->
            Furniture.of(piece, toLocal, -rotation)?.let { furniture[item] = it }
        }
        live.command { sim -> for ((item, f) in furniture) sim.setFurniture(item, f) }
        // SceneSessionScreen: the room's props (locations.json "props") and the camera's walls (the walk test)
        val props = LifeProps.of(locationsJson, preset.name, toLocal, -rotation)
        live.life.setProps(props)
        if (router != null) live.clearance = CameraClearance { eye, at -> router.route(at, eye)?.size == 1 }
        for (interaction in preset.interactions) {
            val item = objects.firstOrNull { it.name == interaction.item } ?: continue
            val spot = InteractionSpot(toLocal(interaction.entry), toLocal(interaction.pose), interaction.facing - rotation)
            live.command { it.setSpot(item, spot) }
        }

        // the pair clips as the engine gets them (lifted to their slots), written beside the track
        val clipDir = File(out, "${plan.name}_clips").apply { mkdirs() }
        val clips = JSONObject()
        for (p in plan.pairs) {
            for ((role, anim, slot) in listOf(Triple(0, p.a, p.slotA), Triple(1, p.b, p.slotB))) {
                val f = File(clipDir, "${p.id}_$role.anim")
                f.writeText(animText(lifted(anim.copy(loop = false), slot.z)))
                clips.put(PlaybackKeys.pair(p.id, role), f.absolutePath)
            }
        }
        val pairClips = plan.pairs.flatMap { p -> listOf(PlaybackKeys.pair(p.id, 0), PlaybackKeys.pair(p.id, 1)) }.toSet()

        // ---- the sink: what SceneSessionScreen's sink would tell the engine, kept per person
        val n = plan.cast.size
        val requested = IntArray(n) { -1 }            // SceneView.requestedAnimations (glAnimate's dedup)
        val anim = IntArray(n) { 0 }; val loop = BooleanArray(n) { true }; val animStart = LongArray(n)
        val clipKey = arrayOfNulls<String>(n); val clipStart = LongArray(n) { -1 }
        val emoting = BooleanArray(n)
        val place = Array(n) { FloatArray(8) }; val poseKind = IntArray(n)
        val said = arrayOfNulls<String>(n)
        val activity = arrayOfNulls<Activity>(n)
        val camera = FloatArray(7)
        var frameMs = 0L
        val track = File(out, "${plan.name}.jsonl").bufferedWriter()
        fun event(i: Int, kind: String, value: Any?) {
            track.write(JSONObject().put("type", "event").put("t", frameMs).put("i", i).put("who", ids[i]).put("kind", kind).put("value", value ?: JSONObject.NULL).toString())
            track.newLine()
        }
        fun engineAnim(i: Int, a: Int, l: Boolean) { anim[i] = a; loop[i] = l; animStart[i] = frameMs }
        fun glAnimate(i: Int, a: Int) { if (requested[i] != a) { requested[i] = a; engineAnim(i, a, true) } }
        val sink = object : LiveScene.Sink {
            override fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float) {
                camera[0] = eyeX; camera[1] = eyeY; camera[2] = eyeZ; camera[3] = atX; camera[4] = atY; camera[5] = atZ; camera[6] = fov
            }
            override fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float) {
                poseKind[index] = when (furniture?.action) { Activity.SIT -> 1; Activity.REST -> 2; else -> 0 }
                val p = place[index]
                p[0] = x; p[1] = y; p[2] = z; p[3] = facing; p[4] = sx; p[5] = sy; p[6] = sz
                p[7] = sz + SpeechLayout.head(heads[index], poseKind[index] == 1, poseKind[index] == 2)
            }
            override fun activity(index: Int, activity: Activity) {
                glAnimate(index, activity.animation)
                event(index, "activity", activity.name)
            }
            override fun playback(index: Int, clip: ClipPlayback?) {
                val emote = clip?.let { PlaybackKeys.emoteOf(it.clip) }
                val wasEmote = emoting[index]
                emoting[index] = emote != null
                if (emote != null) {
                    clipKey[index] = null; clipStart[index] = -1
                    engineAnim(index, emote, emoteHeld(emote))   // the app posts it to the main thread and back: a frame or two later
                } else {
                    val key = clip?.clip?.takeIf { it in pairClips }
                    if (clip != null && key == null) event(index, "missing-clip", clip.clip)
                    clipKey[index] = key; clipStart[index] = if (key != null) frameMs else -1
                    if (wasEmote) engineAnim(index, 0, true)   // back to standing (view.anim: requestedAnimations untouched)
                }
                event(index, "playback", clip?.let { "${it.clip}#${it.command}" })
            }
            override fun speech(index: Int, text: String?, id: Long) { said[index] = text; if (text != null) event(index, "say", text) }
            override fun gesture(index: Int, animation: Int) { glAnimate(index, animation); event(index, "gesture", animation) }
            override fun sound(index: Int, name: String) { event(index, "sound", name) }
        }

        // the header: the cast as the engine dresses and sizes them, the clips, the furniture (stage), an orbit round the room
        val furn = JSONArray()
        for ((item, f) in furniture) {
            val c = toStage(Vec3(f.x, f.y, f.z))
            furn.put(JSONObject().put("item", item.name).put("kind", f.kind.name).put("x", c.x).put("y", c.y).put("z", c.z)
                .put("yaw", f.yaw + rotation).put("halfX", f.spec.halfX).put("halfY", f.spec.halfY).put("top", f.spec.top))
        }
        val pts = furniture.values.map { toStage(Vec3(it.x, it.y, it.z)) } + o
        val cx = pts.map { it.x }.average(); val cy = pts.map { it.y }.average()
        val radius = (pts.maxOf { hypot(it.x - cx, it.y - cy) } + 3.0).coerceIn(4.0, 9.0)
        val header = JSONObject().put("type", "header").put("plan", plan.name).put("location", plan.location).put("view", "inside")
            .put("name", preset.name).put("fps", FPS).put("sampleMs", SAMPLE_MS).put("speed", "X6").put("startHour", 8.0)
            .put("origin", JSONArray(listOf(o.x, o.y, o.z))).put("rotation", rotation)
            .put("cast", JSONArray(plan.cast.mapIndexed { i, c ->
                JSONObject().put("id", c.id).put("name", c.name).put("race", c.race).put("female", c.female).put("look", c.look)
                    .put("ownSize", HumanScale.keepsOwnSize(c.race)).put("height", HumanScale.target(c.female))
                    .put("rootXY", HumanScale.PAIR * (if (HumanScale.keepsOwnSize(c.race)) HumanScale.pairScale(c.race, c.female) else 1f))
                    .put("head", heads[i]).put("traits", JSONArray(life.traits(c.id).map { it.label }))
            }))
            .put("clips", clips).put("furniture", furn).put("routed", router != null)
            .put("props", JSONArray(props.map { p -> val c = toStage(p.entry); JSONObject().put("kind", p.kind.name).put("x", c.x).put("y", c.y).put("z", c.z) }))
            .put("orbit", JSONArray(listOf(cx, cy, o.z.toDouble(), radius, 2.6, 90.0)))
            .put("cues", JSONArray(plan.cues.map { JSONObject().put("t", (it.seconds * 1000).toLong()).put("label", it.label) }))
        track.write(header.toString()); track.newLine()

        live.running = true
        val realSeconds = hours * clock.realSecondsPerHour
        val frameNanos = 1_000_000_000L / FPS
        val cues = plan.cues.sortedBy { it.seconds }.toMutableList()
        val tries = HashMap<String, Int>()
        var nextSample = 0L
        var emitted: SceneSnapshot? = null
        val totalFrames = (realSeconds * FPS).toLong()
        for (k in 0..totalFrames) {
            val now = k * frameNanos
            frameMs = now / 1_000_000L
            while (cues.isNotEmpty() && cues[0].seconds * 1000 <= frameMs) {
                val c = cues.removeAt(0)
                live.command { sim ->
                    val ok = c.what(Ctx(live, life, sim) { answer ->
                        track.write(JSONObject().put("type", "cue").put("t", frameMs).put("label", c.label).put("answer", answer).toString()); track.newLine()
                    })
                    if (!ok && (tries[c.label] ?: 0) < 20) {
                        tries[c.label] = (tries[c.label] ?: 0) + 1
                        cues.add(Cue(frameMs / 1000.0 + 5.0, c.label, c.what)); cues.sortBy { it.seconds }
                    }
                }
                track.write(JSONObject().put("type", "cue").put("t", frameMs).put("label", c.label).toString()); track.newLine()
            }
            if (k > 0) clock.advance(1.0 / FPS)
            live.frame(now, sink) { snap ->
                if (snap.notice != null && snap.notice != emitted?.notice) {
                    track.write(JSONObject().put("type", "notice").put("t", frameMs).put("text", snap.notice).toString()); track.newLine()
                }
                emitted = snap
            }
            if (frameMs >= nextSample) {
                nextSample += SAMPLE_MS
                val people = JSONArray()
                val snap = emitted
                for (i in 0 until n) {
                    val id = ids[i]; val p = place[i]
                    val view = snap?.people?.firstOrNull { it.id == id }
                    // walking or standing on the floor inside a piece of furniture's footprint (the router doesn't know furniture)
                    var inside: String? = null
                    if (poseKind[i] == 0 && clipKey[i] == null) for ((item, f) in furniture) {
                        val local = toLocal(Vec3(p[0], p[1], p[2]))
                        val (lx, ly) = f.toLocal(local.x, local.y)
                        if (abs(lx) < f.spec.halfX * 0.85f && abs(ly) < f.spec.halfY * 0.85f) inside = item.name
                    }
                    people.put(JSONObject().put("x", p[0]).put("y", p[1]).put("z", p[2]).put("f", p[3]).put("pose", poseKind[i])
                        .put("sx", p[4]).put("sy", p[5]).put("sz", p[6]).put("hx", p[4]).put("hy", p[5]).put("hz", p[7])
                        .put("act", view?.activity?.name ?: "").put("anim", anim[i]).put("loop", loop[i]).put("animStart", animStart[i])
                        .put("clip", clipKey[i] ?: "").put("clipStart", clipStart[i]).put("say", said[i] ?: JSONObject.NULL)
                        .put("thought", life.thought(id)?.text ?: JSONObject.NULL).put("mood", life.mood(id)?.label ?: "")
                        .put("needs", JSONArray(life.needs(id)?.map { (it * 100).toInt() } ?: emptyList<Int>()))
                        .put("doing", life.doing(id)?.name ?: JSONObject.NULL).put("with", view?.talkingWith ?: JSONObject.NULL)
                        .put("partner", view?.partner ?: JSONObject.NULL).put("directed", view?.directed ?: false)
                        .put("inFurniture", inside ?: JSONObject.NULL))
                }
                track.write(JSONObject().put("type", "frame").put("t", frameMs).put("hour", clock.hourOfDay).put("light", clock.lightHour)
                    .put("day", clock.day).put("camera", JSONArray(camera.toList())).put("people", people).toString())
                track.newLine()
            }
            activity.indices.forEach { activity[it] = null }
        }
        // the life feed and the relationships at the end
        val feed = JSONArray(life.events().map { JSONObject().put("hours", it.hours).put("text", it.text) })
        val rel = JSONArray()
        for (a in ids) for (b in ids) if (a < b) life.relationship(a, b)?.let { r ->
            rel.put(JSONObject().put("a", a).put("b", b).put("friendship", r.friendship).put("romance", r.romance)
                .put("friendTier", r.friendTier.name).put("romanceTier", r.romanceTier.name).put("allows", r.allows.name))
        }
        track.write(JSONObject().put("type", "end").put("t", frameMs).put("feed", feed).put("relationships", rel)
            .put("walkTests", router?.asked ?: 0).put("decisions", life.decisions).toString())
        track.newLine()
        track.close()
        router?.close()
        println("film: ${plan.name}: ${totalFrames} frames, ${File(out, "${plan.name}.jsonl").length() / 1024} KB")
    }
}
