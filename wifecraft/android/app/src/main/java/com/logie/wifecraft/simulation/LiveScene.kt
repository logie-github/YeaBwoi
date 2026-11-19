package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
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
) {
    data class PersonView(val id: String, val activity: Activity, val directed: Boolean)

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
) {
    /** Where the frame goes (the 3D view, or a test's recorder). GL thread. */
    interface Sink {
        fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float)
        /** Person [index] at x, y, z facing [facing] (stage), on [furniture] (null: the floor) whose surface is sx, sy, sz. */
        fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float)
        /** Person [index] began [activity] this frame (their animation changes now, with the placement). */
        fun activity(index: Int, activity: Activity)
        fun playback(index: Int, clip: ClipPlayback?) {}
    }

    companion object {
        /** The menus' snapshots at most this often (10 a second), and only when something they show changed. */
        const val SNAPSHOT_MS = 100L
        /** How fast a person turns to their new heading (1/s): a quick, eased turn rather than a snap. */
        const val TURN_RATE = 14f
        /** A [command]'s answer: the notice unchanged. */
        private const val KEEP = "\u0000keep"
    }

    private val simulation = SceneSimulation(ids, objects, random, durations, initiallyReady = false, wanderOnly = wanderOnly, route = route)
    val clock = FixedStep()
    val camera = SceneCamera(room)

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
    private val cosR = cos(rotation)
    private val sinR = sin(rotation)

    private var notice: String? = null
    private var noticeChanged = false
    private var emittedVersion = -1L
    private var lastEmitNanos = Long.MIN_VALUE

    init {
        for (i in 0 until n) {
            simulation.pose(i, pose)
            curX[i] = pose.x; curY[i] = pose.y; curZ[i] = pose.z
            prevX[i] = pose.x; prevY[i] = pose.y; prevZ[i] = pose.z
            shownFacing[i] = pose.facing
        }
    }

    /** The menus' view now (allocates: on change only). */
    fun snapshot(): SceneSnapshot {
        val people = simulation.people().map { SceneSnapshot.PersonView(it.id, it.activity, it.directed) }
        val holders = HashMap<SceneObject, String>()
        val usable = HashSet<SceneObject>()
        for (item in simulation.objects) {
            simulation.holder(item)?.let { holders[item] = it }
            if (simulation.measured(item)) usable += item
        }
        return SceneSnapshot(people, holders, usable, notice)
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
        if (running) {
            for (s in 0 until due) {
                for (i in 0 until n) { prevX[i] = curX[i]; prevY[i] = curY[i]; prevZ[i] = curZ[i] }
                simulation.tick(clock.step)
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
            if (running && lastActivity[i] != pose.activity) {
                lastActivity[i] = pose.activity
                sink.activity(i, pose.activity)
            }
            if (running && lastPlayback[i] != pose.playback) {
                lastPlayback[i] = pose.playback
                sink.playback(i, pose.playback)
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
        camera.update(dt, hasFocus, fx, fy, fz)
        sink.camera(camera.eyeX, camera.eyeY, camera.eyeZ, camera.atX, camera.atY, camera.atZ, camera.fov)

        val version = simulation.version
        if ((version != emittedVersion || noticeChanged) &&
            (lastEmitNanos == Long.MIN_VALUE || nowNanos - lastEmitNanos >= SNAPSHOT_MS * 1_000_000L)) {
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
