package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.ActorRequirement
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.dialogue.DialogueDirector
import com.logie.wifecraft.dialogue.NpcProfile
import com.logie.wifecraft.dialogue.PersonView
import com.logie.wifecraft.dialogue.PlaceView
import com.logie.wifecraft.dialogue.Relation
import com.logie.wifecraft.dialogue.SceneView
import com.logie.wifecraft.dialogue.SpeechSink
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * The room's view brought to the cast: a room whose view stands far from where its people are (the Embassy's, from across
 * the hall to the doorway they come in by) shows them small and crowded. [frame] moves the eye in along the line from the
 * room's eye to the cast's middle, to a distance that fits the cast's circle in the picture, at the room's eye height
 * (within 1.8 to 3.2 yards over the floor), looking at their middle. With [CameraClearance] it stands only where the floor
 * under it reaches the room's own eye in a straight walk (the same open room, no wall between): farther back along that
 * line where it doesn't, never nearer (the cast stays in the picture). A room whose view is near its people already is
 * kept as it is. Pure JVM.
 */
object CastFraming {
    /** The room's eye farther than this from the cast's middle (yards), and farther than the framing needs, is moved in. */
    const val FAR = 6.5f
    const val MIN_DISTANCE = 3.2f
    /** Yards round the cast's circle (a body's width and some air). */
    const val MARGIN = 1.2f
    /** The least width over height of the picture (a phone held sideways is wider). */
    const val ASPECT = 1.6f
    const val EYE_MIN = 1.8f
    const val EYE_MAX = 3.2f
    /** The point looked at, over the floor (yards): about the chest. */
    const val AT_HEIGHT = 1.0f
    private const val DEG = 0.017453292f

    /** The view of [room] framed on a cast whose middle is [cx], [cy] on the floor at [floorZ], within [radius] yards of it. */
    fun frame(room: CameraPose, cx: Float, cy: Float, floorZ: Float, radius: Float, clear: CameraClearance? = null): CameraPose {
        val dx = room.eye.x - cx
        val dy = room.eye.y - cy
        val far = hypot(dx, dy)
        val fov = if (room.fov.isFinite() && room.fov > 1f && room.fov < 170f) room.fov else 50f
        val halfWide = atan(tan(fov * DEG / 2f) * ASPECT)
        val need = maxOf(MIN_DISTANCE, (radius + MARGIN) / tan(halfWide))
        if (!far.isFinite() || far < FAR || far <= need + 1f) return room
        val ux = dx / far
        val uy = dy / far
        val h = (room.eye.z - floorZ).coerceIn(EYE_MIN, EYE_MAX)
        var d = need
        if (clear != null) {
            val eyeFloor = Vec3(room.eye.x, room.eye.y, floorZ)
            while (d < far - 0.5f && !clear.clear(Vec3(cx + ux * d, cy + uy * d, floorZ), eyeFloor)) d += 0.5f
            if (d >= far - 0.5f) return room
        }
        return CameraPose(Vec3(cx + ux * d, cy + uy * d, floorZ + h), Vec3(cx, cy, floorZ + AT_HEIGHT), room.fov)
    }
}

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
        val talkingWith: String? = null,
        /** Playing an animation (Stop plays its end), and the HUD's line for it ([StageLine]: part and loop counter). */
        val performing: Boolean = false, val playing: String? = null)

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
        /**
         * Person [index] at x, y, z facing [facing] (stage), on [furniture] (null: the floor) whose surface is sx, sy, sz. On
         * no furniture, sz under z means a clip raised that far over the floor at sz (Pose.lift: onto a bed or seat, or off a
         * floor its joints would reach under): drawn at z, not stood on the floor.
         */
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
        /**
         * Person [index]'s head, chest and pelvis bones as last drawn (world x, y, z each, into [out]: 9 floats), true; false when
         * not known (JOI mode's camera then estimates them). GL thread.
         */
        fun bonePoints(index: Int, out: FloatArray): Boolean = false
    }

    companion object {
        /** The menus' snapshots at most this often (10 a second), and only when something they show changed. */
        const val SNAPSHOT_MS = 100L
        /** The dialogue layer looks at the scene this often (seconds). */
        const val DIALOGUE_STEP = 0.2f
        /** When only the life simulation's view changed (a need moved 5%, a thought), a snapshot at most this often. */
        const val LIFE_SNAPSHOT_MS = 500L
        /** How fast a person turns to their new heading (1/s): a quick, eased turn rather than a snap. */
        const val TURN_RATE = 14f
        /** How far round the furniture its views' eyes may go (yards): about a room's reach, its walls not known. */
        const val ROOM_OUT = 3.5f
        /** The step the furniture views' clear floor is found by (yards). */
        const val REACH_STEP = 0.25f
        /** How often the cast framing is looked at again (ms), how far it must move to be redone (yards), how fast it glides. */
        const val FRAMING_MS = 1000L
        const val REFRAME_YARDS = 1.5f
        const val FRAMING_RATE = 1.5f
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
    /** Whether the room's view is brought in to the cast when it stands far from them ([CastFraming]). Any thread. */
    @Volatile var frameCast = true
    /**
     * Cinematic mode's camera director ([CinematicDirector]; null: none) and whether it runs ([directing]): the camera eases
     * to its shots and, when it stops, back to the player's own camera ([SceneCamera.setDirecting]). [aspect]: the picture's
     * width over its height (the framing). Any thread.
     */
    @Volatile var director: CinematicDirector? = null
    @Volatile var directing = false
    /** Build mode is on: no animation ends ([SceneSimulation.holdAnimations]). Any thread; passed to the simulation each frame. */
    @Volatile var holdAnimations = false
    @Volatile var aspect = 16f / 9f
    /**
     * JOI mode ([JoiCamera]; null: off): the camera frames person [joiActor] (a cast index; -1 none) with her bones, or, for a
     * creature ([joiCreature]), circles her head; [director] and [directing] still say whether the film camera is on, and the
     * director waits while this one runs. While it is on she is kept where she stands, facing the camera as she was turned
     * ([joiPlace]), and goes back to idle after each solo animation. Any thread.
     */
    @Volatile var joiCamera: JoiCamera? = null
    @Volatile var joiActor = -1
    @Volatile var joiCreature: com.logie.wifecraft.characters.CreatureSpec? = null
    /**
     * Chat mode ([ChatCamera]; null: off): the camera stands in front of person [chatActor] (a cast index; -1 none), at her head's height, with a
     * slow drift; a creature ([chatCreature]) is seen from in front of her head. She is turned to face it by [joiPlace] and put back to idle
     * after each gesture ([joiKeep], as in JOI mode). Any thread.
     */
    @Volatile var chatCamera: ChatCamera? = null
    @Volatile var chatActor = -1
    @Volatile var chatCreature: com.logie.wifecraft.characters.CreatureSpec? = null
    private var joiWas = false
    private var joiHoldFacing = Float.NaN
    private var joiSink: Sink? = null
    private val joiPoints = FloatArray(9)
    private val joiBody = JoiBody()
    // the director's view of the stage (GL thread): filled each frame; its furniture and obstacles made again on a change
    private var cineStage: CinematicStage? = null
    private var cineVersion = -1L
    private var cineWas = false
    // what each person played when the director's action was last read (a new clip: read again; ShotLibrary)
    private var cinePlay = arrayOfNulls<ClipPlayback>(0)
    // the cast framing: the room view it was made for, where it is going, what is shown, when it is looked at again
    private var framedFor: CameraPose? = null
    private var framedGoal: CameraPose? = null
    private var framedShown: CameraPose? = null
    private var nextFraming = Long.MIN_VALUE
    // the furniture view's pose now: for whom, which, the simulation's version it was made at
    private var viewPose: CameraPose? = null
    private var viewFor = -1
    private var viewOf: FurnitureView? = null
    private var viewVersion = -1L

    /** Whether the simulation runs (the scene is loaded and not being ended). Any thread. */
    @Volatile var running = false
    /**
     * The preview before a two-person animation plays ([StagePreview]; null: none): the simulation held, the two people drawn
     * where the order would start them, in their roles' first frames ([PreviewKeys.hold]), the camera framing them
     * ([PreviewFraming]; the director paused). Nothing is started. Any thread.
     */
    @Volatile var preview: StagePreview? = null
    // the preview shown (GL thread): what it was made for, its places by cast index, its camera, the command its clips carry
    private var previewFor: StagePreview? = null
    private var held: List<PreviewPlace> = emptyList()
    private var heldCamera: CameraPose? = null
    private var holdCommand = 0L
    /** The selected person's index, -1 none. Any thread. */
    @Volatile var selected = -1

    /**
     * What the dialogue layer reads of this scene: its [director], who the NPCs are ([cast]), where they are ([place]) and
     * whether cinematic mode is on. Set by the screen (any thread); read each frame on the GL thread: the director's lines go to
     * the speech bubbles through [speechSink], on the same thread. Null: no dialogue layer, nothing changes.
     */
    class DialogueFeed(val director: DialogueDirector, val cast: Map<String, NpcProfile>, val place: PlaceView, val cinematic: () -> Boolean)

    @Volatile var dialogue: DialogueFeed? = null
        set(v) { field = v; simulation.dialogue = v?.director }
    private var dialogueClock = 0f

    /** Speech bubbles for the dialogue layer's lines (GL thread). */
    fun speechSink() = SpeechSink { id, text -> simulation.say(id, text) }

    /** The scene as the dialogue layer's director reads it. */
    private fun dialogueView(f: DialogueFeed): SceneView {
        val people = simulation.people()
        val byId = people.associateBy { it.id }
        val life = simulation.life?.takeIf { it.enabled }
        val views = ArrayList<PersonView>(people.size)
        for ((i, p) in people.withIndex()) {
            val profile = f.cast[p.id] ?: continue
            val stage = simulation.partnerOf(p.id)
            val inStage = stage != null && stage.stage == "PLAY"
            val other = (if (inStage) stage?.partner else p.talkingWith)?.let { byId[it] }
            val near = other != null && hypot(other.position.x - p.position.x, other.position.y - p.position.y) < 2.2f
            val inConversation = !inStage && p.talkingWith != null && near
            val otherName = other?.let { f.cast[it.id]?.first }
            val doing = when {
                inStage -> "in ${stage?.name?.lowercase()} with ${otherName ?: "another"}"
                inConversation -> "talking with ${otherName ?: "another"}"
                p.activity == Activity.WALK -> "walking about"
                p.furniture != null -> "${p.activity.label.lowercase()} on the ${p.furniture.label.lowercase()}"
                else -> p.activity.label.lowercase()
            }
            views += PersonView(profile, doing, other?.id, inConversation, simulation.leadsTalk(p.id), inStage,
                inStage && simulation.sexualStageOf(p.id), stage?.name.orEmpty(), p.activity == Activity.WALK,
                if (i == selected) 0f else 8f, i == selected)
        }
        val place = f.place.copy(hour = life?.clock?.hourOfDay ?: Double.NaN, props = simulation.objects.map { "a ${it.label.lowercase()}" })
        return SceneView(place, views, f.cinematic()) { a, b ->
            life?.relationship(a, b)?.let { Relation(it.friendship, it.romance) } ?: Relation()
        }
    }

    private val commands = ConcurrentLinkedQueue<(SceneSimulation) -> String?>()

    /** Runs [change] on the simulation at the start of the next frame (the notice stays as it is). Any thread. */
    fun command(change: (SceneSimulation) -> Unit) { commands.add { change(it); KEEP } }

    /** A player's order: run as [command], its answer becomes the notice (null: none, a done order clears an old refusal). */
    fun order(change: (SceneSimulation) -> String?) { commands.add(change) }

    /** Person [index]'s moment for the intensity ([SceneSimulation.momentOf]). GL thread (inside [frame] or a [command]). */
    fun moment(index: Int): MomentProgress = simulation.momentOf(simulation.id(index))

    /** Whom person [index] is with now (their partner or the one they talk to; [SceneSimulation.companionOf]), or null. GL thread. */
    fun companion(index: Int): String? = simulation.companionOf(simulation.id(index))

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
        val people = simulation.people().map { SceneSnapshot.PersonView(it.id, it.activity, it.directed, it.queued, it.repeating, it.partner, it.talkingWith,
            it.performing, it.playing) }
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

    /** A piece of furniture's box (its [Furniture.bounds]) on the stage: the box round its four corners turned there. */
    private fun stageBox(f: Furniture): Pair<Vec3, Vec3> {
        val (lo, hi) = f.bounds()
        val corners = listOf(stage(Vec3(lo.x, lo.y, lo.z)), stage(Vec3(lo.x, hi.y, lo.z)), stage(Vec3(hi.x, lo.y, lo.z)), stage(Vec3(hi.x, hi.y, hi.z)))
        return Vec3(corners.minOf { it.x }, corners.minOf { it.y }, oz + lo.z) to Vec3(corners.maxOf { it.x }, corners.maxOf { it.y }, oz + hi.z)
    }

    /**
     * Cinematic mode, before the camera's frame (GL thread): the stage as the director sees it (everyone's hips and heads,
     * the furniture in use and its views, the furniture's boxes, the floor, the walls' test, the camera as last drawn), the
     * director's shot for this frame laid over the camera. A start (from nothing shown) starts the director afresh from the
     * camera where it is; while it fades out the director keeps going, so the way back is as smooth as the way in.
     */
    private fun directFrame(dt: Float) {
        val dir = director
        val on = directing && dir != null && previewFor == null
        camera.setDirecting(on)
        if (dir == null || (!on && camera.directAmount <= 0f)) { cineWas = false; return }
        if (on && !cineWas && camera.directAmount <= 0f) dir.restart()
        cineWas = on
        val jc = joiCamera
        val cc = chatCamera
        if (on && jc != null && joiActor in 0 until n) {
            joiWas = true
            if (joiFrame(jc, joiActor, dt)) return
        } else if (on && cc != null && chatActor in 0 until n) {
            joiWas = true
            if (chatFrame(cc, chatActor, dt)) return
        } else if (joiWas) { joiWas = false; dir.restart() }
        val st = cineStage ?: CinematicStage(n).also { cineStage = it }
        st.count = n
        if (cinePlay.size != n) { cinePlay = arrayOfNulls(n); cineReads = arrayOfNulls(n); cineFrames = FloatArray(4 * n); cineActionRead = false }
        for (i in 0 until n) {
            simulation.pose(i, pose)
            if (cinePlay[i] !== pose.playback) { cinePlay[i] = pose.playback; cineActionRead = false; cineRead(i) }
            val sx = stageX(curX[i], curY[i]); val sy = stageY(curX[i], curY[i]); val z = oz + curZ[i]
            st.facing[i] = pose.facing + rotation
            // the hips and head: from the playing clip's skeleton as it was read (anim/Requirements: its pelvis, its head over
            // the ground, its posture), else by what they do (a body resting on a bed laid out toward its head)
            val read = cineReads[i]
            val scale = (simulation.bodyScales[simulation.id(i)] ?: 1f) * com.logie.wifecraft.anim.CharacterSize.active
            if (read != null && pose.playback != null && CinematicAnchors.performer(read, cineFrames[4 * i], cineFrames[4 * i + 1],
                    cineFrames[4 * i + 2], cineFrames[4 * i + 3], scale, pose.z, pose.floor, cinePoint)) {
                val p = cinePoint
                st.person(i, stageX(p[0], p[1]), stageY(p[0], p[1]), oz + p[2], stageX(p[3], p[4]), stageY(p[3], p[4]), oz + p[5])
                continue
            }
            if (pose.activity == Activity.REST) {
                CinematicAnchors.estimate(Posture.LIE_BACK, sx, sy, z + 0.23f, pose.facing + rotation, scale, cinePoint)
                val p = cinePoint
                st.person(i, p[0], p[1], p[2], p[3], p[4], p[5])
                continue
            }
            val (hip, head) = when {
                pose.activity == Activity.SIT -> 0.5f to 1.15f
                pose.playback != null -> 0.6f to 1.25f
                else -> 1.0f to 1.65f
            }
            st.person(i, sx, sy, z + hip, sx, sy, z + head)
        }
        if (!cineActionRead) { cineActionRead = true; cineAction(st, dir.library) }
        val version = simulation.version
        if (version != cineVersion) {
            cineVersion = version
            // the furniture in use: the piece the most people are on (its views as the furniture views make them)
            var best: Pair<Furniture, List<Vec3>>? = null
            var bestId: String? = null
            for (i in 0 until n) {
                val id = simulation.id(i)
                val use = simulation.onFurniture(id) ?: continue
                if (best == null || use.second.size > best.second.size) { best = use; bestId = id }
            }
            st.furniture = if (best == null || bestId == null) null else {
                val (f, pts) = best
                val views = presets(bestId, clearance).map { p ->
                    p.copy(eye = stage(p.eye), at = stage(p.at), yaw = p.yaw + rotation)
                }
                val c = if (pts.isNotEmpty()) stage(Vec3(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat(),
                    pts.map { it.z }.average().toFloat())) else stage(f.toWorld(0f, 0f, f.spec.surface))
                // the surface its people rest on (a bed's mattress, a seat; FurnitureFit's), for the shots set by it
                val surface = if (f.kind != FurnitureKind.FLOOR && f.spec.surface > 0.05f) oz + f.z + f.spec.surface else Float.NaN
                FurnitureFocus(f.id, c, views, hypot(f.spec.halfX, f.spec.halfY), stageBox(f), surface)
            }
            st.obstacles = simulation.objects.mapNotNull { item -> simulation.furniture(item)?.let(::stageBox) }
            // what the eye can't see through: each piece's real solid parts and a canopy's roof and drapes (PropSolids)
            st.solids = simulation.objects.flatMap { item -> simulation.furniture(item)?.let { f -> PropSolids.world(f).map(::stageBoxOf) } ?: emptyList() }
            st.canopies = simulation.objects.mapNotNull { item -> simulation.furniture(item)?.let(PropSolids::canopyBox)?.let(::stageBoxOf) }
        }
        st.floorZ = camera.floorZ
        st.ceilingZ = Float.NaN
        st.clearance = clearance
        st.aspect = aspect
        st.camera(camera.eyeX, camera.eyeY, camera.eyeZ, camera.atX, camera.atY, camera.atZ, camera.fov)
        if (dir.update(dt, st)) camera.directPose(dir.eyeX, dir.eyeY, dir.eyeZ, dir.atX, dir.atY, dir.atZ, dir.fov)
    }

    /**
     * JOI mode's frame (GL thread): her bones (the skeleton as drawn, else estimates by what she stands on), and [jc]'s pose laid
     * over the camera. A creature's skull is found by [com.logie.wifecraft.characters.CreatureSpec.skullForward] and its kin.
     */
    private fun joiFrame(jc: JoiCamera, i: Int, dt: Float): Boolean {
        val spec = joiCreature
        fillBody(i, spec)
        jc.creature = spec != null
        if (spec != null) jc.creatureScale = spec.scale
        jc.visible = clearance?.let { c -> { eye: Vec3, at: Vec3 -> c.clear(eye, at) } }
        if (!jc.update(dt, joiBody, aspect)) return false
        camera.directPose(jc.eyeX, jc.eyeY, jc.eyeZ, jc.atX, jc.atY, jc.atZ, jc.fov)
        return true
    }

    /** Chat mode's frame (GL thread): her body as JOI mode reads it, and [cc]'s conversation framing laid over the camera. */
    private fun chatFrame(cc: ChatCamera, i: Int, dt: Float): Boolean {
        val spec = chatCreature
        fillBody(i, spec)
        cc.creature = spec != null
        if (spec != null) cc.creatureScale = spec.scale
        cc.visible = clearance?.let { c -> { eye: Vec3, at: Vec3 -> c.clear(eye, at) } }
        if (!cc.update(dt, joiBody, aspect)) return false
        camera.directPose(cc.eyeX, cc.eyeY, cc.eyeZ, cc.atX, cc.atY, cc.atZ, cc.fov)
        return true
    }

    /** Person [i]'s body for a conversation or JOI camera ([joiBody]): her bones as drawn, else estimates; a creature's skull by its spec. */
    private fun fillBody(i: Int, spec: com.logie.wifecraft.characters.CreatureSpec?) {
        simulation.pose(i, pose)
        val scale = (simulation.bodyScales[simulation.id(i)] ?: 1f) * com.logie.wifecraft.anim.CharacterSize.active
        val sx = stageX(curX[i], curY[i]); val sy = stageY(curX[i], curY[i]); val z = oz + curZ[i]
        val facing = pose.facing + rotation
        if (spec != null) {
            val k = spec.scale / 0.25f
            val cf = cos(facing); val sf = sin(facing)
            val hx = sx + (cf * spec.skullForward - sf * spec.skullSide) * k
            val hy = sy + (sf * spec.skullForward + cf * spec.skullSide) * k
            val hz = z + spec.skullLift * k
            joiBody.set(Vec3(hx, hy, hz), Vec3(sx, sy, z + 1.3f * k), Vec3(sx, sy, z + 1.0f * k), facing, 3f * k, z, false)
        } else {
            val tracked = joiSink?.bonePoints(i, joiPoints) == true && joiPoints.all { it.isFinite() }
            val p = joiPoints
            if (tracked) joiBody.set(Vec3(p[0], p[1], p[2]), Vec3(p[3], p[4], p[5]), Vec3(p[6], p[7], p[8]), facing, 1.7f * scale, z, true)
            else joiBody.set(Vec3(sx, sy, z + 1.62f * scale), Vec3(sx, sy, z + 1.25f * scale), Vec3(sx, sy, z + 0.95f * scale), facing, 1.7f * scale, z, false)
        }
    }

    /**
     * JOI mode starts: person [id] stays where they stand, turned to face the camera (a creature keeps her heading), held there
     * ([SceneSimulation.holdAt]). Any thread (runs at the start of the next frame).
     */
    fun joiPlace(id: String, creature: Boolean) = command { sim ->
        val at = sim.position(id) ?: return@command
        val ex = camera.eyeX - ox; val ey = camera.eyeY - oy
        val lx = ex * cosR + ey * sinR; val ly = -ex * sinR + ey * cosR
        val now = sim.people().firstOrNull { it.id == id }?.facing ?: 0f
        val yaw = if (creature) now else atan2(ly - at.y, lx - at.x)
        joiHoldFacing = yaw
        sim.holdAt(id, null, at, yaw, Activity.IDLE)
    }

    /** JOI mode ends: [id] is let go back to their day. Any thread. */
    fun joiRelease(id: String) = command { sim -> joiHoldFacing = Float.NaN; sim.endHold(id) }

    /** While JOI mode runs, the speaker is put back where she stands after each solo animation (GL thread, each frame). */
    private fun joiKeep() {
        val i = if (chatCamera != null) chatActor else joiActor
        if ((joiCamera == null && chatCamera == null) || i !in 0 until n || joiHoldFacing.isNaN()) return
        val id = simulation.id(i)
        if (simulation.busy(id)) return
        val at = simulation.position(id) ?: return
        simulation.holdAt(id, null, at, joiHoldFacing, Activity.IDLE)
    }

    private var cineActionRead = false
    // each person's clip as it was read (anim/Requirements; null: not known) and its frame (origin x, y, turn, world units a
    // body unit), read again when their clip changes; a point scratch
    private var cineReads = arrayOfNulls<ActorRequirement>(0)
    private var cineFrames = FloatArray(0)
    private val cinePoint = FloatArray(6)

    /**
     * Person [i]'s clip as anim/Requirements read its skeleton (only when their clip changed: allocates): a pair's role
     * ("pair:<id>:<role>") in its shared frame, placed where the performance put that frame (its anchor and heading).
     */
    private fun cineRead(i: Int) {
        cineReads[i] = null
        val clip = cinePlay[i]?.clip ?: return
        if (!clip.startsWith("pair:")) return
        val pairId = clip.removePrefix("pair:").substringBeforeLast(':')
        val role = clip.substringAfterLast(':').toIntOrNull() ?: return
        val id = simulation.id(i)
        val pv = simulation.partnerOf(id) ?: return
        val pair = simulation.pairs.firstOrNull { it.id == pairId } ?: return
        val read = runCatching { simulation.requirementOf(pair) }.getOrNull()?.actors?.getOrNull(role) ?: return
        val scales = simulation.bodyScales
        val unit = SceneSimulation.HUMAN_BODY_UNIT * ((scales[id] ?: 1f) + (scales[pv.partner] ?: 1f)) / 2f
        cineReads[i] = read
        cineFrames[4 * i] = pv.ox; cineFrames[4 * i + 1] = pv.oy; cineFrames[4 * i + 2] = pv.yaw; cineFrames[4 * i + 3] = unit
    }

    /** A box of the scene's frame on the stage: the box round its four corners turned there. */
    private fun stageBoxOf(b: Pair<Vec3, Vec3>): Pair<Vec3, Vec3> {
        val (lo, hi) = b
        val c = listOf(stage(Vec3(lo.x, lo.y, lo.z)), stage(Vec3(lo.x, hi.y, lo.z)), stage(Vec3(hi.x, lo.y, lo.z)), stage(Vec3(hi.x, hi.y, hi.z)))
        return Vec3(c.minOf { it.x }, c.minOf { it.y }, oz + lo.z) to Vec3(c.maxOf { it.x }, c.maxOf { it.y }, oz + hi.z)
    }

    /**
     * What the cast plays, for the director's shot library (only when someone's clip changed: allocates): the class of the
     * animation from its names (the clips' keys, the partner animation's name), who leads it (role A) and who is with them
     * (role B), the line of action (the performance's heading on the stage), its stage part where a clip's name says it.
     */
    private fun cineAction(st: CinematicStage, lib: ShotLibrary?) {
        val playing = (0 until n).filter { cinePlay[it] != null }
        if (lib == null || playing.isEmpty()) { st.action = null; st.lineYaw = Float.NaN; return }
        val names = ArrayList<String>()
        var lead = playing[0]
        var other = -1
        var heading = Float.NaN
        for (i in playing) {
            val id = simulation.id(i)
            names += cinePlay[i]?.clip ?: ""
            val pv = simulation.partnerOf(id) ?: continue
            names += pv.name
            if (pv.role == 0) {
                lead = i
                heading = pv.yaw + rotation
                other = (0 until n).firstOrNull { simulation.id(it) == pv.partner } ?: -1
            }
        }
        val cls = lib.classify(names, emptyList(), if (other >= 0) 2 else playing.size)
        val part = names.firstNotNullOfOrNull { com.logie.wifecraft.anim.StageRules.parse(it.substringAfterLast('/').substringAfterLast(':'))?.part }
            ?.let { p -> SegmentKind.entries.firstOrNull { it.name == p.name } }
        st.roleA = lead
        st.roleB = if (other >= 0) other else playing.firstOrNull { it != lead } ?: lead
        st.lineYaw = heading
        st.action = ShotAction(cls, part, cls.name + "|" + names.sorted().joinToString("|"))
    }

    /** The room's view this frame: [roomView], or brought in to the cast ([CastFraming]: looked at once a second, glided). */
    private fun castRoom(nowNanos: Long, dt: Float): CameraPose {
        val room = roomView
        if (!frameCast || n == 0) { framedShown = null; return room }
        if (room !== framedFor) { framedFor = room; framedGoal = null; framedShown = null; nextFraming = Long.MIN_VALUE }
        if (nextFraming == Long.MIN_VALUE || nowNanos >= nextFraming) {
            nextFraming = nowNanos + FRAMING_MS * 1_000_000L
            var cx = 0f; var cy = 0f; var cz = 0f
            for (i in 0 until n) { cx += stageX(curX[i], curY[i]); cy += stageY(curX[i], curY[i]); cz += oz + curZ[i] }
            cx /= n; cy /= n; cz /= n
            var r = 0f
            for (i in 0 until n) r = maxOf(r, hypot(stageX(curX[i], curY[i]) - cx, stageY(curX[i], curY[i]) - cy))
            val goal = CastFraming.frame(room, cx, cy, cz, r, clearance)
            val old = framedGoal
            // a new framing only when it moved by more than a step (a walk across the room, not a shuffle)
            if (old == null || hypot(old.eye.x - goal.eye.x, old.eye.y - goal.eye.y) > REFRAME_YARDS ||
                hypot(old.at.x - goal.at.x, old.at.y - goal.at.y) > REFRAME_YARDS) framedGoal = goal
        }
        val goal = framedGoal ?: return room
        val shown = framedShown
        val next = if (shown == null) goal else if (shown == goal) shown else {
            val k = 1f - exp(-FRAMING_RATE * dt)
            val moved = shown.lerp(goal, k)
            if (hypot(moved.eye.x - goal.eye.x, moved.eye.y - goal.eye.y) < 0.01f && hypot(moved.at.x - goal.at.x, moved.at.y - goal.at.y) < 0.01f) goal else moved
        }
        framedShown = next
        return next
    }

    /** The camera's room this frame: the furniture view asked for when [sel] is on furniture that has it, else the room's. */
    private fun cameraRoom(sel: Int, room: CameraPose): CameraPose {
        val want = furnitureView
        if (want == null || sel < 0) { viewPose = null; viewOf = null; return room }
        val version = simulation.version
        if (sel != viewFor || want != viewOf || version != viewVersion) {
            viewFor = sel; viewOf = want; viewVersion = version
            viewPose = presets(simulation.id(sel), clearance).firstOrNull { it.view == want }?.let { p ->
                CameraPose(stage(p.eye), stage(p.at), p.fov)
            }
        }
        return viewPose ?: room
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
        joiSink = sink
        joiKeep()
        holdPreview(sink)
        val ticking = running && previewFor == null
        simulation.holdAnimations = holdAnimations
        if (ticking) {
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
        if (ticking) dialogue?.let { f ->
            dialogueClock += dt
            if (dialogueClock >= DIALOGUE_STEP) { f.director.update(dialogueClock, dialogueView(f)); dialogueClock = 0f }
        }
        val a = if (ticking) clock.alpha else 1f
        val turn = 1f - exp(-TURN_RATE * dt)
        val sel = selected
        var hasFocus = false
        var fx = 0f; var fy = 0f; var fz = 0f
        for (i in 0 until n) {
            val h = held.firstOrNull { it.index == i }
            if (h != null) {
                // the preview: at the order's start place, its clip held at the first frame
                shownFacing[i] = h.facing
                val sx = stageX(h.x, h.y); val sy = stageY(h.x, h.y)
                sink.place(i, sx, sy, oz + h.z, h.facing + rotation, null, sx, sy, oz + h.floor)
                continue
            }
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
                // a clip raised over its floor (Pose.lift) keeps that height: the floor goes as the surface
                val lift = if (pose.playback != null) pose.lift else 0f
                sink.place(i, sx, sy, oz + z, facing, null, sx, sy, oz + z - lift)
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
        val wantRoom = heldCamera ?: cameraRoom(sel, castRoom(nowNanos, dt))
        if (heldCamera != null) hasFocus = false
        if (camera.room !== wantRoom) camera.room = wantRoom
        directFrame(dt)
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

    /**
     * The preview (GL thread, at the frame's start): a new one placed ([SceneSimulation.previewPlaces]) and its held clips sent;
     * one ended: the people's own clips sent again (whatever they played before it, from the simulation as it is).
     */
    private fun holdPreview(sink: Sink) {
        val want = preview
        if (want === previewFor) return
        val was = held
        previewFor = want
        held = want?.let { simulation.previewPlaces(it.lead, it.order, it.item) }.orEmpty()
        heldCamera = null
        if (want != null && held.isEmpty()) { notice = "Nothing near them fits that to preview."; noticeChanged = true }
        if (held.size == 2) {
            val (pa, pb) = held
            heldCamera = PreviewFraming.frame(stageX(pa.x, pa.y), stageY(pa.x, pa.y), stageX(pb.x, pb.y), stageY(pb.x, pb.y),
                oz + minOf(pa.floor, pb.floor), pa.facing + rotation, camera.room.fov, clearance)
            holdCommand++
            for (h in held) {
                val clip = ClipPlayback(PreviewKeys.hold(h.clip), -holdCommand)
                lastPlayback[h.index] = clip
                sink.playback(h.index, clip)
            }
        }
        // the people no longer held: back to what the simulation has them play (nothing: their activity's animation)
        for (h in was) if (held.none { it.index == h.index }) {
            simulation.pose(h.index, pose)
            lastPlayback[h.index] = pose.playback
            sink.playback(h.index, pose.playback)
            sink.activity(h.index, pose.activity)
            lastActivity[h.index] = pose.activity
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
