package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** What the live screen's menus show, made only when it changed (and at most [LiveScene.SNAPSHOT_MS] apart). */
data class SceneSnapshot(
    val people: List<PersonView>,
    /** Who holds each object that someone holds (walking to it or on it). */
    val holders: Map<SceneObject, String>,
    /** The objects whose spot has been measured (usable). */
    val usable: Set<SceneObject>,
    /** The last command's refusal ("Chair is in use."), or null. */
    val notice: String?,
    /** How many partner animations (the library's enabled pairs) the scene picks from (0: the Warcraft emotes). */
    val partnerPairs: Int = 0,
    /** The furniture views of each person on furniture (by id; [FurnitureCameras]: the bed's foot, head...), in order. */
    val furnitureViews: Map<String, List<FurnitureView>> = emptyMap(),
    /** The life simulation's view (needs, moods, thoughts, relationships, the feed: [LifeApi]), or null without one. */
    val life: LifeView? = null,
) {
    /** [partner]: the other NPC of a two-person animation, else null; [queued] orders waiting; [repeating] one repeating. */
    data class PersonView(val id: String, val activity: Activity, val directed: Boolean,
        val queued: Int = 0, val repeating: Boolean = false, val partner: String? = null,
        /** The other NPC of a conversation, else null. */
        val talkingWith: String? = null)

    /** Whether [id] may use [item] now. */
    fun available(item: SceneObject, id: String): Boolean = item in usable && holders[item].let { it == null || it == id }
}

/**
 * The live scene, run frame by frame on the GL thread: the simulation on its fixed step ([FixedStep]: the same at any
 * screen rate), the people drawn between its last two steps (smooth at 60, 90 or 120 Hz), their turning eased, the camera
 * ([SceneCamera]) following the selected one, and each frame written straight to a [Sink] (no state, no allocation per
 * frame). The screen's commands ([command]) wait in a queue the frame runs first (the simulation is only ever touched by
 * one thread); what the menus show comes back as a [SceneSnapshot] only when it changed. Pure: the JVM tests drive it with a
 * fake clock. The stage: a scene point p is drawn at [origin] + p turned [rotation] radians about Z.
 */
class LiveScene(
    ids: List<String>,
    objects: Set<SceneObject>,
    durations: Map<Activity, Float>,
    room: CameraPose,
    private val ox: Float, private val oy: Float, private val oz: Float,
    private val rotation: Float,
    random: Random = Random.Default,
    wanderOnly: Boolean = false,
    route: ((Vec3, Vec3) -> List<Vec3>?)? = null,
    /** The partner activity: the two-person animations an NPC picks from, the models' emotes, and how often. */
    pairs: List<PartnerPair> = emptyList(),
    emotes: ModelEmotes = ModelEmotes.STANDARD,
    partnerChance: Float = PartnerFrequency.SOMETIMES.chance,
    /** The NPCs' small talk: what they say ([Chatter]; null: no words) and how often they go and talk (0: never). */
    chatter: Chatter? = null,
    chatterChance: Float = 0f,
    /** The life simulation over the scene (its cast this one's, in order: [LifeApi]); null: none, NPCs choose as before. */
    lifeSim: LifeSim? = null,
) {
    /** Where the frame goes (the 3D view, or a test's recorder). GL thread. */
    interface Sink {
        fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float)
        /** Person [index] at x, y, z facing [facing] (stage), on [furniture] (null: the floor) whose surface is sx, sy, sz. */
        fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float)
        /** Person [index] began [activity] this frame (their animation changes now, with the placement). */
        fun activity(index: Int, activity: Activity)
        /**
         * Person [index]'s clip changes this frame ([clip] null: none; a new [ClipPlayback.command] starts it again). Both
         * people of a two-person animation get theirs in the same frame (the same simulation step): one start time.
         */
        fun playback(index: Int, clip: ClipPlayback?) {}
        /** A performance's sound event [name] for person [index] is due (its time on the performance's clock passed). */
        fun sound(index: Int, name: String) {}
        /** Person [index] says [text] now (a speech bubble; [id] new for every line), or stops ([text] null, [id] 0). */
        fun speech(index: Int, text: String?, id: Long) {}
        /**
         * Person [index]'s game animation while talking changes this frame: a gesture, a nod or standing between lines, or
         * (the talk over) back to their activity's own [animation]. Called after [activity] in the same frame.
         */
        fun gesture(index: Int, animation: Int) {}
    }

    companion object {
        /** The menus' snapshots at most this often (10 a second), and only when something they show changed. */
        const val SNAPSHOT_MS = 100L
        /** When only the life simulation's view changed (a need moved 5%, a thought), a snapshot at most this often. */
        const val LIFE_SNAPSHOT_MS = 500L
        /** How fast a person turns to their new heading (1/s): a quick, eased turn rather than a snap. */
        const val TURN_RATE = 14f
        /** How far round the furniture its views' eyes may go (yards): about a room's reach, its walls not known. */
        const val ROOM_OUT = 3.5f
        /** The step the furniture views' clear floor is found by (yards). */
        const val REACH_STEP = 0.25f
        /** A [command]'s answer: the notice unchanged. */
        private const val KEEP = "\u0000keep"
    }

    private val simulation = SceneSimulation(ids, objects, random, durations, initiallyReady = false, wanderOnly = wanderOnly,
        route = route, partnerChance = partnerChance, pairs = pairs, emotes = emotes,
        chatterChance = chatterChance, chatter = chatter).also {
        // the screen classifies the pairs off this thread; until it has, a pair goes by its location names
        it.classifyPairs = false
        it.life = lifeSim
    }
    /** The life simulation's commands (any thread: [LifeApi]). */
    val life = LifeControl(this)
    /** The frame's sink while [frame] runs (the simulation's sound events go to it). */
    private var frameSink: Sink? = null
    val clock = FixedStep()
    val camera = SceneCamera(room)

    /** The room's own view (the screen's): the camera's room, unless a furniture view is on. Any thread. */
    @Volatile var roomView: CameraPose = room
    /**
     * The furniture view the camera shows of the selected person's furniture ([SceneSnapshot.furnitureViews]; null: none),
     * as the camera's room: the button's Room mode shows it, an orbit turns about it. Any thread.
     */
    @Volatile var furnitureView: FurnitureView? = null
    /** Whether the camera may stand at an eye seeing a point (stage coordinates, GL thread: no wall between); null: always. */
    @Volatile var clearance: CameraClearance? = null
    // the furniture view's pose now: for whom, which, the simulation's version it was made at
    private var viewPose: CameraPose? = null
    private var viewFor = -1
    private var viewOf: FurnitureView? = null
    private var viewVersion = -1L

    /** Whether the simulation runs (the scene is loaded and not being ended). Any thread. */
    @Volatile var running = false
    /** The selected person's index, -1 none. Any thread. */
    @Volatile var selected = -1

    private val commands = ConcurrentLinkedQueue<(SceneSimulation) -> String?>()

    /** Runs [change] on the simulation at the start of the next frame (the notice stays as it is). Any thread. */
    fun command(change: (SceneSimulation) -> Unit) { commands.add { change(it); KEEP } }

    /** A player's order: run as [command], its answer becomes the notice (null: none, a done order clears an old refusal). */
    fun order(change: (SceneSimulation) -> String?) { commands.add(change) }

    /** The cast's id of person [index] (the order of [Sink]'s indices). */
    fun id(index: Int): String = simulation.id(index)

    /** How many people are on stage. */
    val count: Int get() = simulation.count

    private val n = simulation.count
    private val pose = Pose()
    private val prevX = FloatArray(n); private val prevY = FloatArray(n); private val prevZ = FloatArray(n)
    private val curX = FloatArray(n); private val curY = FloatArray(n); private val curZ = FloatArray(n)
    private val shownFacing = FloatArray(n)
    private val lastActivity = arrayOfNulls<Activity>(n)
    private val lastPlayback = arrayOfNulls<ClipPlayback>(n)
    private val lastSpeech = LongArray(n)
    private val lastGesture = IntArray(n) { -1 }
    private var lastPairStarts = 0
    private val cosR = cos(rotation)
    private val sinR = sin(rotation)

    private var notice: String? = null
    private var noticeChanged = false
    private var emittedVersion = -1L
    private var emittedLife = -1L
    private var lastEmitNanos = Long.MIN_VALUE

    init {
        simulation.sounds = { index, name -> frameSink?.sound(index, name) }
        for (i in 0 until n) {
            simulation.pose(i, pose)
            curX[i] = pose.x; curY[i] = pose.y; curZ[i] = pose.z
            prevX[i] = pose.x; prevY[i] = pose.y; prevZ[i] = pose.z
            shownFacing[i] = pose.facing
        }
    }

    /** The menus' view now (allocates: on change only). */
    fun snapshot(): SceneSnapshot {
        val people = simulation.people().map { SceneSnapshot.PersonView(it.id, it.activity, it.directed, it.queued, it.repeating, it.partner, it.talkingWith) }
        val holders = HashMap<SceneObject, String>()
        val usable = HashSet<SceneObject>()
        for (item in simulation.objects) {
            simulation.holder(item)?.let { holders[item] = it }
            if (simulation.measured(item)) usable += item
        }
        val views = HashMap<String, List<FurnitureView>>()
        for (i in 0 until n) {
            val id = simulation.id(i)
            val list = presets(id, null).map { it.view }
            if (list.isNotEmpty()) views[id] = list
        }
        val lifeView = simulation.life?.let { l -> emittedLife = l.version; l.view() }
        return SceneSnapshot(people, holders, usable, notice, simulation.pairs.size, views, lifeView)
    }

    /**
     * The furniture views of what [id] is on ([FurnitureCameras.presets], scene frame): kept within a box round the furniture
     * (3.5 yards out, up to [SceneCamera.MAX_TARGET_H] over its floor: the room's walls are not known here) and clear by
     * [clear], asked along the floor: from just beside the furniture, toward the eye, to under the eye (the scene's test is a
     * walk on its floor; from the furniture's middle the furniture itself would stand in the way). A view no eye can be had
     * for is left out; with none left, they are made without the walls.
     */
    private fun presets(id: String, clear: CameraClearance?): List<FurniturePreset> {
        val (f, people) = simulation.onFurniture(id) ?: return emptyList()
        val (lo, hi) = f.bounds()
        val room = RoomBounds(Vec3(lo.x - ROOM_OUT, lo.y - ROOM_OUT, f.z), Vec3(hi.x + ROOM_OUT, hi.y + ROOM_OUT, f.z + SceneCamera.MAX_TARGET_H))
        val beside = hypot(f.spec.halfX, f.spec.halfY) + 0.3f
        // how far the floor is clear each way (by the degree), found once by stepping out: a few tests a view, not one an eye
        val reach = HashMap<Int, Float>()
        val local = clear?.let { c ->
            CameraClearance { eye, _ ->
                val dx = eye.x - f.x; val dy = eye.y - f.y
                val d = hypot(dx, dy)
                if (d <= beside) true else {
                    val ux = dx / d; val uy = dy / d
                    val far = reach.getOrPut(Math.round(Math.toDegrees(atan2(dy, dx).toDouble())).toInt()) {
                        val from = stage(Vec3(f.x + ux * beside, f.y + uy * beside, f.z))
                        var r = beside
                        while (r < beside + 2 * ROOM_OUT + SceneCamera.MAX_DISTANCE) {
                            val next = r + REACH_STEP
                            if (!c.clear(stage(Vec3(f.x + ux * next, f.y + uy * next, f.z)), from)) break
                            r = next
                        }
                        r
                    }
                    d <= far
                }
            }
        }
        val list = FurnitureCameras.presets(f, people, room, local)
        return if (list.isEmpty() && local != null) FurnitureCameras.presets(f, people, room, null) else list
    }

    private fun stage(p: Vec3) = Vec3(stageX(p.x, p.y), stageY(p.x, p.y), oz + p.z)

    /** The camera's room this frame: the furniture view asked for when [sel] is on furniture that has it, else the room's. */
    private fun cameraRoom(sel: Int): CameraPose {
        val want = furnitureView
        if (want == null || sel < 0) { viewPose = null; viewOf = null; return roomView }
        val version = simulation.version
        if (sel != viewFor || want != viewOf || version != viewVersion) {
            viewFor = sel; viewOf = want; viewVersion = version
            viewPose = presets(simulation.id(sel), clearance).firstOrNull { it.view == want }?.let { p ->
                CameraPose(stage(p.eye), stage(p.at), p.fov)
            }
        }
        return viewPose ?: roomView
    }

    /**
     * One frame at [nowNanos] (GL thread): the commands, the steps due, the camera, everyone placed; [emit] gets a new
     * snapshot when what the menus show changed and the last one is [SNAPSHOT_MS] old.
     */
    fun frame(nowNanos: Long, sink: Sink, emit: (SceneSnapshot) -> Unit) {
        while (true) {
            val c = commands.poll() ?: break
            val answer = c(simulation)
            if (answer !== KEEP && answer != notice) { notice = answer; noticeChanged = true }
        }
        val due = clock.begin(nowNanos)
        val dt = clock.frameSeconds
        frameSink = sink
        if (running) {
            for (s in 0 until due) {
                for (i in 0 until n) { prevX[i] = curX[i]; prevY[i] = curY[i]; prevZ[i] = curZ[i] }
                simulation.tick(clock.step)
                simulation.takeQueueNotice()?.let { notice = it; noticeChanged = true }
                for (i in 0 until n) {
                    simulation.pose(i, pose)
                    curX[i] = pose.x; curY[i] = pose.y; curZ[i] = pose.z
                }
            }
        }
        val a = if (running) clock.alpha else 1f
        val turn = 1f - exp(-TURN_RATE * dt)
        val sel = selected
        var hasFocus = false
        var fx = 0f; var fy = 0f; var fz = 0f
        for (i in 0 until n) {
            simulation.pose(i, pose)
            val x = if (pose.playback != null) pose.x else prevX[i] + (curX[i] - prevX[i]) * a
            val y = if (pose.playback != null) pose.y else prevY[i] + (curY[i] - prevY[i]) * a
            val z = if (pose.playback != null) pose.z else prevZ[i] + (curZ[i] - prevZ[i]) * a
            val onFurniture = pose.furniture != null && (pose.activity == Activity.SIT || pose.activity == Activity.REST)
            shownFacing[i] = if (onFurniture || pose.playback != null) pose.facing else turnToward(shownFacing[i], pose.facing, turn)
            var activityChanged = false
            if (running && lastActivity[i] != pose.activity) {
                lastActivity[i] = pose.activity
                activityChanged = true
                sink.activity(i, pose.activity)
            }
            if (running && lastPlayback[i] != pose.playback) {
                lastPlayback[i] = pose.playback
                sink.playback(i, pose.playback)
            }
            if (running) {
                // the talking gesture over the activity's animation; when it ends, the activity's own again
                val g = pose.gesture
                if (g != lastGesture[i] || (activityChanged && g >= 0)) {
                    val was = lastGesture[i]
                    lastGesture[i] = g
                    if (g >= 0) sink.gesture(i, g) else if (was >= 0 && !activityChanged) sink.gesture(i, pose.activity.animation)
                }
                val said = if (pose.speech != null) pose.speechId else 0L
                if (said != lastSpeech[i]) {
                    lastSpeech[i] = said
                    sink.speech(i, pose.speech, said)
                }
            }
            val facing = shownFacing[i] + rotation
            if (onFurniture) {
                sink.place(i, stageX(pose.ex, pose.ey), stageY(pose.ex, pose.ey), oz + pose.ez, facing, pose.furniture,
                    stageX(x, y), stageY(x, y), oz + z)
            } else {
                val sx = stageX(x, y); val sy = stageY(x, y)
                sink.place(i, sx, sy, oz + z, facing, null, sx, sy, oz + z)
            }
            if (i == sel) {
                hasFocus = true
                val height = when (pose.activity) {
                    Activity.REST -> 0.35f
                    Activity.SIT -> 0.7f
                    else -> 1.05f
                }
                fx = stageX(x, y); fy = stageY(x, y); fz = oz + z + height
            }
        }
        frameSink = null
        val starts = simulation.pairStarts
        if (starts != lastPairStarts) {
            lastPairStarts = starts
            simulation.lastPairName?.let { name -> notice = "Partner animation: $name"; noticeChanged = true }
        }
        val wantRoom = cameraRoom(sel)
        if (camera.room !== wantRoom) camera.room = wantRoom
        camera.update(dt, hasFocus, fx, fy, fz)
        sink.camera(camera.eyeX, camera.eyeY, camera.eyeZ, camera.atX, camera.atY, camera.atZ, camera.fov)

        val version = simulation.version
        val since = nowNanos - lastEmitNanos
        val lifeDue = simulation.life.let { it != null && it.version != emittedLife } && since >= LIFE_SNAPSHOT_MS * 1_000_000L
        if ((version != emittedVersion || noticeChanged || lifeDue) &&
            (lastEmitNanos == Long.MIN_VALUE || since >= SNAPSHOT_MS * 1_000_000L)) {
            emittedVersion = version
            noticeChanged = false
            lastEmitNanos = nowNanos
            emit(snapshot())
        }
    }

    private fun stageX(x: Float, y: Float) = ox + x * cosR - y * sinR
    private fun stageY(x: Float, y: Float) = oy + x * sinR + y * cosR

    private fun turnToward(from: Float, to: Float, k: Float): Float {
        val tau = 2f * PI.toFloat()
        var d = (to - from) % tau
        if (d > PI) d -= tau
        if (d < -PI) d += tau
        return from + d * k
    }
}
