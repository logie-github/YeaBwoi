package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.random.Random

enum class Activity(val label: String, val description: String, val animation: Int, val seconds: Float) {
    IDLE("Wait", "Idle", 0, 1.5f),
    WALK("Walk", "Walking", 4, 0f),
    SIT("Sit", "Sitting", 103, 8f),
    REST("Rest", "Resting", 100, 10f),
    READ("Read a book", "Reading", 520, 9f),
    CHAT("Chat", "Chatting", 60, 7f),
    LISTEN("Listen", "Listening", 0, 7f),
    PERFORM("Play animation", "Performing", 0, 0f),
}

enum class SceneObject(val label: String, val model: Int, val position: Vec3, val approach: Vec3, val action: Activity) {
    CHAIR("Chair", 7151868, Vec3(-2.7f, 1.7f, 0f), Vec3(-2.7f, 1.7f, 0.05f), Activity.SIT),
    BED("Bed", 961704, Vec3(2.6f, 2.2f, 0f), Vec3(2.6f, 2.2f, 0.55f), Activity.REST),
    TABLE("Table and book", 198604, Vec3(1.9f, -1.3f, 0f), Vec3(1.9f, -2.25f, 0f), Activity.READ),
    COUCH("Couch", 6390430, Vec3(-0.2f, 2.9f, 0f), Vec3(-0.2f, 2.65f, 0.08f), Activity.SIT);

    fun interaction(surface: Vec3, facing: Float, halfDepth: Float): InteractionSpot {
        if (this == TABLE) {
            val entry = surface.copy(z = 0f) + (approach - position)
            return InteractionSpot(entry, entry, 1.5707964f)
        }
        val approachFacing = if (this == BED) facing + 1.5707964f else facing
        val direction = Vec3(cos(approachFacing), sin(approachFacing), 0f)
        val towardCenter = direction.x * surface.x + direction.y * surface.y < 0f
        val side = if (this == BED && !towardCenter) -1f else 1f
        val entry = surface.copy(z = 0f) + direction * ((halfDepth + 0.35f) * side)
        return InteractionSpot(entry, surface, facing)
    }

    companion object { const val BOOK_MODEL = 198029 }
}

/** One actor's part of a performance: the clip to play (a key the screen resolves: [PlaybackKeys]), where it stands from
 *  the performance's anchor (x forward, y left; z is a lift the clip carries) and its facing (radians) from the heading. */
data class PlaybackRole(val clip: String, val offset: Vec3, val facing: Float)
data class ClipPlayback(val clip: String, val command: Long)
/**
 * An animation for one or two NPCs: [roles] in order (the lead's, then [partnerId]'s), one cycle of [seconds], played
 * [repetitions] times (or until stopped when [repeat]). [name] is shown while it plays; [sounds] are its sound events on
 * the cycle's clock ([PartnerSound.actor]: the role's index).
 */
data class PerformanceOrder(
    val partnerId: String?, val roles: List<PlaybackRole>, val seconds: Float,
    val repeat: Boolean = false, val repetitions: Int = 1,
    val name: String = "", val sounds: List<PartnerSound> = emptyList(),
)

/** The clip keys of [PlaybackRole.clip] that are not imported single clips: a partner pair's role, a Warcraft emote. */
object PlaybackKeys {
    fun pair(id: String, role: Int) = "pair:$id:$role"
    fun emote(anim: Int) = "emote:$anim"
    /** The game animation of an emote key, else null. */
    fun emoteOf(key: String): Int? = if (key.startsWith("emote:")) key.removePrefix("emote:").toIntOrNull() else null
}

data class InteractionSpot(val entry: Vec3, val pose: Vec3, val facing: Float)

/**
 * One person as the renderer wants them this frame ([SceneSimulation.pose] fills it; reused, never one a frame): where they
 * are and face, what they do, the furniture they are on (null: the floor) and the floor spot beside it ([ex], [ey], [ez]).
 */
class Pose {
    var x = 0f; var y = 0f; var z = 0f
    var facing = 0f
    var activity = Activity.IDLE
    var furniture: SceneObject? = null
    var playback: ClipPlayback? = null
    var ex = 0f; var ey = 0f; var ez = 0f
    /** The line said now (a speech bubble), or null; [speechId] is new for every line said. */
    var speech: String? = null
    var speechId = 0L
    /** A game animation played over the activity's while talking (a gesture, a nod, standing between lines), or -1. */
    var gesture = -1
}

/** What a person's partner animation is, for the menus and the tests ([SceneSimulation.partnerOf]). [stage] is APPROACH
 *  (walking to their places) or PLAY; [role] 0 is the one who went to the other (or the lead of an ordered one). */
data class PartnerView(
    val stage: String, val partner: String, val name: String, val role: Int, val loops: Int, val clock: Float,
    val startStep: Long, val ox: Float, val oy: Float, val yaw: Float, val furniture: SceneObject?, val emote: Int,
)

class SceneSimulation(
    actorIds: List<String>,
    val objects: Set<SceneObject>,
    private val random: Random = Random.Default,
    private val durations: Map<Activity, Float> = emptyMap(),
    initiallyReady: Boolean = true,
    private val wanderOnly: Boolean = false,
    private val route: ((Vec3, Vec3) -> List<Vec3>?)? = null,
    /** The chance an idle NPC's decision is to start a partner animation with another (0: never; [PartnerFrequency]). */
    var partnerChance: Float = 0f,
    /** The partner animations an NPC picks from by itself (empty: the Warcraft emotes, [PartnerEmotes]). */
    var pairs: List<PartnerPair> = emptyList(),
    /** Which game animations each NPC's model has (the fallback's emotes are picked from both models'). */
    var emotes: ModelEmotes = ModelEmotes.STANDARD,
    /** The chance an idle NPC's decision is to go and talk with another (0: never; [ChatterFrequency]). */
    var chatterChance: Float = 0f,
    /** What NPCs say to each other (null: they chat without words, as before). */
    var chatter: Chatter? = null,
) {
    data class Person(
        val id: String, val position: Vec3, val facing: Float, val activity: Activity, val directed: Boolean,
        val furniture: SceneObject? = null, val entry: Vec3 = position,
        val queued: Int = 0, val repeating: Boolean = false,
        /** The other NPC of a two-person animation (walking to their places or playing), else null. */
        val partner: String? = null,
        /** The other NPC of a conversation (walking to it or talking), else null. */
        val talkingWith: String? = null,
    )

    /** A performance's sound event is due: actor [index] (the cast's order), sound [name]. Called inside [tick]. */
    var sounds: ((index: Int, name: String) -> Unit)? = null

    /** Steps run ([tick]s): both people of a performance start on the same one. */
    var steps = 0L
        private set

    /** How many partner animations (by themselves or sent with Send to partner) have started, and the last one's name. */
    var pairStarts = 0
        private set
    var lastPairName: String? = null
        private set

    private val spots = if (initiallyReady) objects.associateWith { item ->
        InteractionSpot(item.approach.copy(z = 0f), item.approach, if (item == SceneObject.TABLE) 1.5707964f else -1.5707964f)
    }.toMutableMap() else mutableMapOf()

    fun setSpot(item: SceneObject, spot: InteractionSpot) {
        if (item in objects) { spots[item] = spot; version++ }
    }

    /**
     * Goes up whenever what a menu shows changes (someone's activity, whether they were directed, who holds which object,
     * the spots measured); positions alone never move it. The live screen redraws its menus only when this moved.
     */
    var version = 0L
        private set

    /**
     * One performance: [roles] for [lead] and [other], placed from [anchor] turned by [heading]. A partner animation
     * ([partnerActivity]: started by an NPC itself or by Send to partner) was begun by [initiator], may hold [furniture],
     * plays an [emote] when no imported pair fits, and ends with both stepping apart and a cooldown.
     */
    private class Performance(
        val lead: Actor, val other: Actor?, val roles: List<PlaybackRole>, val seconds: Float,
        val anchor: Vec3, val heading: Float, var repeat: Boolean, var remainingCycles: Int,
        val directed: Boolean = true, val name: String = "", val sounds: List<PartnerSound> = emptyList(),
        val partnerActivity: Boolean = false, val initiator: Actor? = null, val furniture: SceneObject? = null,
        val emote: Int = -1,
    ) {
        var started = false
        /** Seconds played since the first cycle started, and cycles finished: one clock for both. */
        var clock = 0f
        var loops = 0
        var startStep = 0L
    }

    /** How a partner animation is set up (the partner activity's placement): see [startPartner]. */
    private class Setup(val anchor: Vec3, val heading: Float, val furniture: SceneObject?, val emote: Int,
        val initiator: Actor, val directed: Boolean)

    private enum class Phase { WAITING, MOVING, ACTING }

    private class Actor(val id: String, val index: Int, start: Vec3, var facing: Float, var remaining: Float) {
        // the position as plain numbers: walking moves them every tick without making a Vec3
        var x = start.x
        var y = start.y
        var z = start.z
        var position: Vec3
            get() = Vec3(x, y, z)
            set(v) { x = v.x; y = v.y; z = v.z }
        var phase = Phase.WAITING
        var destination = position
        var waypoints = emptyList<Vec3>()
        var waypoint = 0
        var wanderCenter = start
        var activity = Activity.IDLE
        var target: SceneObject? = null
        var partner: Actor? = null
        var performance: Performance? = null
        var playback: ClipPlayback? = null
        val queue = ArrayDeque<PerformanceOrder>()
        var completedPerformance: Performance? = null
        var directed = false
        /** Seconds before this person may start another partner animation by themselves. */
        var cooldown = 0f
        /** The conversation this person is in (walking to it, or talking), else null. */
        var talk: Talk? = null
        /** Seconds before this person may start or be drawn into another conversation by themselves. */
        var chatCooldown = 0f
        /** The last line this person said, its serial, until when it is up, and until when its talking gesture plays. */
        var spoken: String? = null
        var spokenId = 0L
        var spokenUntil = 0f
        var sayGestureUntil = 0f
    }

    /** Two people talking: [a] went to [b]; [conversation] is its lines' clock (started when [a] arrives). */
    private class Talk(val a: Actor, val b: Actor, val conversation: Conversation, val directed: Boolean)

    /** Seconds the simulation has run (the speech bubbles' clock). */
    private var time = 0f
    private var speechSerial = 0L

    private val actors = actorIds.distinct().mapIndexed { i, id ->
        Actor(id, i, Vec3((i % 3 - 1) * 1.2f, -0.6f + (i / 3) * 1.45f, 0f), -1.4f, 0.6f + i * 0.45f)
    }.associateBy { it.id }
    /** The actors in the cast's order, for the per-frame reads ([count], [pose]) that index them. */
    private val order = actors.values.toList()
    // reused by [choose] (a decision makes no new lists)
    private val optionScratch = ArrayList<SceneObject>(objects.size)
    private val peopleScratch = ArrayList<Actor>(order.size)
    private val pairScratch = ArrayList<PartnerPair>()
    private val emoteScratch = ArrayList<PartnerEmote>(PartnerEmotes.ALL.size)

    /** How many people are on stage (the indices [pose] takes, in the cast's order). */
    val count: Int get() = order.size

    fun id(index: Int): String = order[index].id

    /** What the renderer needs of person [index] this tick, written into [out] (nothing allocated: once a frame). */
    fun pose(index: Int, out: Pose) {
        val a = order[index]
        out.x = a.x; out.y = a.y; out.z = a.z
        out.facing = a.facing
        out.playback = a.playback
        out.activity = if (a.phase == Phase.MOVING) Activity.WALK else a.activity
        val item = a.target.takeIf { a.phase == Phase.ACTING }
        out.furniture = item
        val entry = item?.let { spots[it]?.entry }
        if (entry != null) { out.ex = entry.x; out.ey = entry.y; out.ez = entry.z } else { out.ex = a.x; out.ey = a.y; out.ez = a.z }
        out.speech = a.spoken?.takeIf { time < a.spokenUntil }
        out.speechId = a.spokenId
        out.gesture = gestureOf(a)
    }

    private fun gestureOf(a: Actor): Int {
        val talk = a.talk
        if (talk != null) return if (a.phase == Phase.ACTING) talk.conversation.gesture(if (talk.a === a) 0 else 1) else -1
        val free = a.phase != Phase.MOVING && a.performance == null && a.target == null
        return if (a.spoken != null && time < a.sayGestureUntil && free) Gestures.TALK else -1
    }

    /** What [id] is saying now (tests), or null. */
    fun speech(id: String): String? = actors[id]?.let { a -> a.spoken?.takeIf { time < a.spokenUntil } }

    /** Whether [id] is in a conversation (walking to it or talking). */
    fun talking(id: String): Boolean = actors[id]?.talk != null

    /** The other of [id]'s conversation, or null. */
    fun talkingWith(id: String): String? = actors[id]?.talk?.let { if (it.a.id == id) it.b.id else it.a.id }

    /**
     * The player's Say: [id] says [text] in a speech bubble (up for its reading time), talking as it does when standing
     * free. False: no such person or nothing to say.
     */
    fun say(id: String, text: String): Boolean {
        val actor = actors[id] ?: return false
        val line = text.trim()
        if (line.isEmpty()) return false
        val seconds = Reading.seconds(line)
        actor.spoken = line
        actor.spokenId = ++speechSerial
        actor.spokenUntil = time + seconds
        actor.sayGestureUntil = time + minOf(seconds, Conversation.GESTURE_SECONDS + 0.8f)
        return true
    }

    /** Whether [item]'s spot has been measured (it can be used). */
    fun measured(item: SceneObject): Boolean = item in spots

    /** Who holds [item] (walking to it or using it), or null. */
    fun holder(item: SceneObject): String? = order.firstOrNull { it.target == item }?.id

    fun people(): List<Person> = actors.values.map { actor ->
        val item = actor.target.takeIf { actor.phase == Phase.ACTING }
        Person(actor.id, actor.position, actor.facing, if (actor.phase == Phase.MOVING) Activity.WALK else actor.activity,
            actor.directed, item, item?.let { spots[it]?.entry } ?: actor.position,
            actor.queue.size, actor.performance?.repeat == true, partnerOf(actor)?.id,
            actor.talk?.let { if (it.a === actor) it.b.id else it.a.id })
    }

    private fun partnerOf(actor: Actor): Actor? = actor.performance?.let { if (it.lead === actor) it.other else it.lead }

    /** [id]'s two-person animation now, or null (menus and tests; allocates). */
    fun partnerOf(id: String): PartnerView? {
        val actor = actors[id] ?: return null
        val p = actor.performance ?: return null
        val other = partnerOf(actor) ?: return null
        val role = if (p.initiator != null) (if (actor === p.initiator) 0 else 1) else (if (actor === p.lead) 0 else 1)
        return PartnerView(if (p.started) "PLAY" else "APPROACH", other.id, p.name, role, p.loops, p.clock, p.startStep,
            p.anchor.x, p.anchor.y, p.heading, p.furniture, p.emote)
    }

    /** Where [id] stands (tests). */
    fun position(id: String): Vec3? = actors[id]?.position

    /**
     * The player's Send to partner: [id] goes to [otherId] and they play a random partner animation (an imported pair that
     * fits, else a Warcraft emote both models have). Whatever either was doing stops, but someone carrying out another of
     * the player's orders is not taken. False: no such person, the other is busy with an order, nothing fits, or no path.
     */
    fun partner(id: String, otherId: String): Boolean {
        val actor = actors[id] ?: return false
        val other = actors[otherId] ?: return false
        if (actor === other || (other.directed && other.performance?.partnerActivity != true)) return false
        if (!pick(actor, other)) return false
        release(actor)
        release(other)
        return startPartner(actor, other, directed = true)
    }

    /** Ends every partner animation at once (the scene ends): both of each back to idle where they stand. */
    fun endPartners() {
        for (actor in order) if (actor.performance?.partnerActivity == true) release(actor)
    }

    fun available(target: SceneObject, id: String): Boolean =
        target in spots && actors.values.none { it.id != id && it.target == target }

    fun use(id: String, target: SceneObject): Boolean {
        val actor = actors[id] ?: return false
        if (!available(target, id)) return false
        return begin(actor, target.action, spots.getValue(target).entry, directed = true, target = target)
    }

    fun walk(id: String, destination: Vec3): Boolean {
        val actor = actors[id] ?: return false
        if (!destination.x.isFinite() || !destination.y.isFinite() || !destination.z.isFinite()) return false
        return begin(actor, Activity.IDLE, destination, directed = true)
    }

    fun chat(id: String, otherId: String): Boolean {
        val actor = actors[id] ?: return false
        val other = actors[otherId] ?: return false
        if (actor === other) return false
        return startChat(actor, other, directed = true)
    }

    fun perform(id: String, otherId: String?, roles: List<PlaybackRole>, seconds: Float): Boolean =
        perform(id, PerformanceOrder(otherId, roles, seconds))

    fun perform(id: String, request: PerformanceOrder): Boolean {
        val actor = actors[id] ?: return false
        return valid(actor, request) && startPerformance(actor, request)
    }

    fun enqueue(id: String, requests: List<PerformanceOrder>): Boolean {
        val actor = actors[id] ?: return false
        if (requests.isEmpty() || requests.size > MAX_QUEUED - actor.queue.size || requests.any { !valid(actor, it) }) return false
        actor.queue.addAll(requests)
        version++
        return true
    }

    fun clearQueue(id: String) {
        val actor = actors[id] ?: return
        actor.queue.clear()
        actor.completedPerformance = null
        version++
    }

    fun stopRepeat(id: String) {
        val performance = actors[id]?.performance ?: return
        if (performance.repeat) {
            performance.repeat = false
            performance.remainingCycles = 1
            version++
        }
    }

    private fun valid(lead: Actor, request: PerformanceOrder): Boolean =
        request.seconds.isFinite() && request.seconds > 0f && request.repetitions in 1..100 &&
            if (request.partnerId == null) request.roles.size == 1
            else request.partnerId != lead.id && request.partnerId in actors && request.roles.size == 2

    private fun startPerformance(lead: Actor, request: PerformanceOrder, continuation: Performance? = null, setup: Setup? = null): Boolean {
        val other = request.partnerId?.let(actors::get)
        val previous = continuation?.takeIf { it.other === other }
        val anchor = previous?.anchor ?: setup?.anchor ?: movementOrigin(lead)
        val heading = previous?.heading ?: setup?.heading ?: lead.facing
        val directed = setup?.directed ?: true
        val furniture = setup?.furniture
        val performance = Performance(lead, other, request.roles, request.seconds.coerceIn(0.25f, 3600f),
            anchor, heading, request.repeat, request.repetitions, directed, request.name, request.sounds,
            setup != null, setup?.initiator, furniture, setup?.emote ?: -1)
        if (other != null && previous == null &&
            !begin(other, Activity.PERFORM, rolePosition(performance, request.roles[1]), directed, furniture)) return false

        // the lead steps to its own place too when it is not there already, so nobody jumps when the clips start
        val leadSpot = if (other != null && previous == null) rolePosition(performance, request.roles[0]) else null
        val leadWalks = leadSpot != null && hypot(leadSpot.x - lead.x, leadSpot.y - lead.y) > LEAD_STEP &&
            begin(lead, Activity.PERFORM, leadSpot, directed, furniture)
        if (!leadWalks) release(lead)
        if (other != null) {
            if (previous != null) release(other)
            lead.partner = other
            other.partner = lead
            lead.performance = performance
            other.performance = performance
            if (!leadWalks) {
                lead.phase = Phase.ACTING
                lead.target = furniture
            }
            lead.directed = directed
        }
        if (other == null || previous != null) play(performance)
        return true
    }

    private fun startQueuedPerformance(actor: Actor) {
        if (actor.performance != null || actor.directed) return
        val next = actor.queue.firstOrNull() ?: return
        val partner = next.partnerId?.let(actors::get)
        if (partner?.let { it.directed || it.performance != null } == true) return
        actor.queue.removeFirst()
        val previous = actor.completedPerformance
        actor.completedPerformance = null
        if (!startPerformance(actor, next, previous)) queueNotice = "The NPCs couldn’t reach their starting positions."
        version++
    }

    var queueNotice: String? = null
        private set

    fun takeQueueNotice(): String? = queueNotice.also { queueNotice = null }

    private fun rolePosition(performance: Performance, role: PlaybackRole): Vec3 {
        val x = role.offset.x
        val y = role.offset.y
        val c = cos(performance.heading)
        val s = sin(performance.heading)
        return performance.anchor + Vec3(x * c - y * s, x * s + y * c, 0f)
    }

    private fun play(performance: Performance) {
        val first = !performance.started
        performance.started = true
        val command = ++version
        for ((index, actor) in listOfNotNull(performance.lead, performance.other).withIndex()) {
            val role = performance.roles[index]
            actor.position = rolePosition(performance, role)
            actor.facing = performance.heading + role.facing
            actor.performance = performance
            actor.playback = ClipPlayback(role.clip, command)
            actor.activity = Activity.PERFORM
            actor.phase = Phase.ACTING
            actor.remaining = performance.seconds
            actor.directed = performance.directed
        }
        if (first) {
            performance.startStep = steps
            if (performance.partnerActivity) {
                pairStarts++
                lastPairName = performance.name
            }
        }
        fire(performance, -1f, 0f)
        version++
    }

    /** [performance]'s sound events whose time is in (from, to] of the cycle. */
    private fun fire(performance: Performance, from: Float, to: Float) {
        val listener = sounds ?: return
        if (to <= from) return
        for (sound in performance.sounds) {
            if (sound.timeSec > from && sound.timeSec <= to) {
                val who = if (sound.actor == 1) performance.other else performance.lead
                if (who != null) listener(who.index, sound.name)
            }
        }
    }

    fun freeWill(id: String) {
        actors[id]?.let { actor ->
            clearQueue(actor.id)
            actor.performance?.lead?.takeUnless { it === actor }?.let { clearQueue(it.id) }
            release(actor)
        }
    }

    fun tick(seconds: Float) {
        val dt = if (seconds.isNaN()) 0f else seconds.coerceIn(0f, 0.1f)
        steps++
        time += dt
        for (actor in order) startQueuedPerformance(actor)
        for (actor in order) {
            if (actor.cooldown > 0f) actor.cooldown -= dt
            if (actor.chatCooldown > 0f) actor.chatCooldown -= dt
            when (actor.phase) {
                Phase.WAITING -> {
                    actor.remaining -= dt
                    if (actor.remaining <= 0f) choose(actor)
                }
                Phase.MOVING -> {
                    val dx = actor.destination.x - actor.x
                    val dy = actor.destination.y - actor.y
                    val step = dt * WALK_SPEED
                    val distance = hypot(dx, dy)
                    if (distance <= step) {
                        if (actor.waypoint + 1 == actor.waypoints.size) arrive(actor)
                        else {
                            actor.position = actor.destination
                            nextWaypoint(actor)
                        }
                    } else {
                        val fraction = step / distance
                        actor.x += dx * fraction
                        actor.y += dy * fraction
                        actor.z += (actor.destination.z - actor.z) * fraction
                    }
                }
                Phase.ACTING -> {
                    val performance = actor.performance
                    if (performance != null && !performance.started) {
                        // waiting for the other to reach their place: face them as they come
                        val coming = partnerOf(actor)
                        if (coming != null && coming.phase == Phase.MOVING && hypot(coming.x - actor.x, coming.y - actor.y) <= FACE_RANGE) {
                            actor.facing = atan2(coming.y - actor.y, coming.x - actor.x)
                        }
                        continue
                    }
                    val talk = actor.talk
                    if (talk != null && performance == null) {
                        if (talk.a === actor && talk.conversation.started) {
                            val begun = talk.conversation.advance(dt)
                            if (begun >= 0) speak(talk, begun)
                            if (talk.conversation.done) endTalk(talk)
                        } else if (talk.b === actor && !talk.conversation.started) {
                            // waiting for the other to come over: face them as they come
                            val coming = talk.a
                            if (coming.phase == Phase.MOVING && hypot(coming.x - actor.x, coming.y - actor.y) <= FACE_RANGE) {
                                actor.facing = atan2(coming.y - actor.y, coming.x - actor.x)
                            }
                        }
                        if (talk.a === actor || talk.b === actor) continue
                    }
                    if (actor.activity == Activity.LISTEN || (performance != null && performance.lead !== actor)) continue
                    val before = if (performance != null) performance.seconds - actor.remaining else 0f
                    actor.remaining -= dt
                    if (performance != null) {
                        performance.clock += dt
                        fire(performance, before, minOf(performance.seconds - actor.remaining, performance.seconds))
                    }
                    if (actor.remaining <= 0f) {
                        if (performance != null) performance.loops++
                        val repeat = performance?.repeat == true && actor.queue.isEmpty() &&
                            performance.other?.queue?.isNotEmpty() != true
                        if (performance != null && (--performance.remainingCycles > 0 || repeat)) {
                            // the next cycle keeps the step's overshoot: cycles stay on the one clock, never drifting
                            val over = actor.remaining
                            play(performance)
                            actor.remaining += over
                            fire(performance, 0f, -over)
                        } else {
                            release(actor)
                            actor.completedPerformance = performance?.takeIf { actor.queue.isNotEmpty() }
                            startQueuedPerformance(actor)
                            if (performance?.partnerActivity == true) part(performance)
                        }
                    }
                }
            }
        }
    }

    private fun wander(actor: Actor) {
        val center = actor.wanderCenter
        val target = center + Vec3(random.nextFloat() * 3 - 1.5f, random.nextFloat() * 2 - 1f, 0f)
        if (!begin(actor, Activity.IDLE, target, false)) actor.remaining = 0.8f
    }

    private fun choose(actor: Actor) {
        if (partnerChance > 0f && actor.cooldown <= 0f && !actor.directed && actor.queue.isEmpty()) {
            val other = nearestFree(actor)
            if (other != null && random.nextFloat() < partnerChance && pick(actor, other) && startPartner(actor, other, directed = false)) return
        }
        if (chatterChance > 0f && actor.chatCooldown <= 0f && !actor.directed && actor.queue.isEmpty()) {
            val other = nearestFree(actor, chat = true)
            if (other != null && random.nextFloat() < chatterChance && startChat(actor, other, directed = false)) return
        }
        if (wanderOnly) {
            wander(actor)
            return
        }
        val options = optionScratch.apply { clear(); for (o in objects) if (available(o, actor.id)) add(o) }
        val people = peopleScratch.apply { clear(); for (o in order) if (o !== actor && o.phase == Phase.WAITING) add(o) }
        val choice = random.nextInt(options.size + people.size + 1)
        when {
            choice < options.size -> options[choice].let { begin(actor, it.action, spots.getValue(it).entry, false, it) }
            choice < options.size + people.size -> startChat(actor, people[choice - options.size], directed = false)
            else -> wander(actor)
        }
    }

    private fun startChat(actor: Actor, other: Actor, directed: Boolean): Boolean {
        release(actor)
        release(other)
        val side = if (other.position.x > 0f) -1f else 1f
        if (!begin(actor, Activity.CHAT, Vec3(other.x + side * 0.85f, other.y, other.z), directed)) {
            actor.remaining = 0.8f
            return false
        }
        actor.partner = other
        other.partner = actor
        other.activity = Activity.LISTEN
        other.phase = Phase.ACTING
        chatter?.let { words ->
            val talk = Talk(actor, other, Conversation(words.conversation(actor.id, other.id, random)), directed)
            actor.talk = talk
            other.talk = talk
        }
        version++
        return true
    }

    /** Line [index] of [talk] begins: its speaker's bubble (the other's is taken down). */
    private fun speak(talk: Talk, index: Int) {
        val line = talk.conversation.lines[index]
        val who = if (line.speaker == 0) talk.a else talk.b
        val other = if (who === talk.a) talk.b else talk.a
        who.spoken = line.text
        who.spokenId = ++speechSerial
        who.spokenUntil = time + Reading.seconds(line.text)
        who.sayGestureUntil = 0f
        if (other.spokenUntil > time) other.spokenUntil = time
    }

    /** [talk] ran to its end: both carry on (and now and then, being friends, go into a partner animation together). */
    private fun endTalk(talk: Talk) {
        val a = talk.a
        val b = talk.b
        release(a)
        if (b.talk === talk) release(b)
        a.chatCooldown = MIN_CHAT_COOLDOWN + random.nextFloat() * (MAX_CHAT_COOLDOWN - MIN_CHAT_COOLDOWN)
        b.chatCooldown = MIN_CHAT_COOLDOWN + random.nextFloat() * (MAX_CHAT_COOLDOWN - MIN_CHAT_COOLDOWN)
        if (!talk.directed && partnerChance > 0f && random.nextFloat() < FRIENDLY_CHANCE && free(a) && free(b) && pick(a, b)) {
            startPartner(a, b, directed = false)
        }
    }

    private fun movementOrigin(actor: Actor): Vec3 =
        spots[actor.target]?.entry?.takeIf { actor.phase == Phase.ACTING } ?: actor.position

    private fun begin(actor: Actor, activity: Activity, destination: Vec3, directed: Boolean, target: SceneObject? = null): Boolean {
        val from = movementOrigin(actor)
        val path = if (route == null) listOf(destination) else route.invoke(from, destination).orEmpty()
        if (path.isEmpty()) return false
        release(actor)
        version++
        actor.activity = activity
        actor.target = target
        actor.directed = directed
        actor.waypoints = path
        actor.waypoint = -1
        nextWaypoint(actor)
        actor.phase = Phase.MOVING
        return true
    }

    private fun nextWaypoint(actor: Actor) {
        actor.destination = actor.waypoints[++actor.waypoint]
        val delta = actor.destination - actor.position
        if (delta.x * delta.x + delta.y * delta.y > 0.001f) actor.facing = atan2(delta.y, delta.x)
    }

    private fun arrive(actor: Actor) {
        actor.performance?.let { performance ->
            // at their place: the clips start once both are there
            version++
            actor.position = actor.destination
            actor.phase = Phase.ACTING
            if (performance.lead.phase != Phase.MOVING && performance.other?.phase != Phase.MOVING) play(performance)
            return
        }
        version++
        actor.position = actor.destination
        if (actor.activity == Activity.IDLE && actor.directed) actor.wanderCenter = actor.position
        actor.phase = Phase.ACTING
        actor.remaining = durations[actor.activity]?.takeIf { it.isFinite() && it > 0f } ?: actor.activity.seconds
        actor.target?.let { target ->
            val spot = spots.getValue(target)
            actor.position = spot.pose
            actor.facing = spot.facing
        }
        actor.partner?.let { other ->
            actor.facing = atan2(other.position.y - actor.position.y, other.position.x - actor.position.x)
            other.facing = atan2(actor.position.y - other.position.y, actor.position.x - other.position.x)
        }
        actor.talk?.let { talk ->
            if (talk.a === actor && !talk.conversation.started && talk.conversation.start() == 0) speak(talk, 0)
        }
    }

    private fun release(actor: Actor) {
        version++
        if (actor.performance?.partnerActivity == true) actor.cooldown = MIN_COOLDOWN + random.nextFloat() * (MAX_COOLDOWN - MIN_COOLDOWN)
        val partner = actor.partner
        actor.partner = null
        actor.talk?.let { talk ->
            // a conversation cut short: its bubbles come down with it
            for (who in arrayOf(talk.a, talk.b)) {
                if (who.talk === talk) { who.talk = null; if (who.spokenUntil > time) who.spokenUntil = time }
            }
        }
        actor.performance = null
        actor.completedPerformance = null
        actor.playback = null
        if (actor.phase == Phase.ACTING) actor.target?.let { actor.position = spots.getValue(it).entry }
        actor.target = null
        actor.waypoints = emptyList()
        actor.activity = Activity.IDLE
        actor.phase = Phase.WAITING
        actor.remaining = 1.25f + random.nextFloat() * 2f
        actor.directed = false
        if (partner != null) {
            partner.partner = null
            release(partner)
        }
    }

    // ---- the partner activity: an idle NPC goes to another and they play a two-person animation (a Performance)

    private fun free(o: Actor) = o.phase == Phase.WAITING && o.performance == null && !o.directed && o.cooldown <= 0f && o.queue.isEmpty()

    /** Free to be talked to: idle, in nothing else, not lately in a conversation. */
    private fun freeToChat(o: Actor) = o.phase == Phase.WAITING && o.performance == null && o.talk == null && !o.directed &&
        o.chatCooldown <= 0f && o.queue.isEmpty()

    /** The free person nearest [actor] within [PARTNER_RANGE] (ties within [TIE_YARDS]: a random one), or null. */
    private fun nearestFree(actor: Actor, chat: Boolean = false): Actor? {
        var best = Float.MAX_VALUE
        for (o in order) {
            if (o === actor || !(if (chat) freeToChat(o) else free(o))) continue
            val d = hypot(o.x - actor.x, o.y - actor.y)
            if (d <= PARTNER_RANGE && d < best) best = d
        }
        if (best == Float.MAX_VALUE) return null
        val near = peopleScratch.apply { clear() }
        for (o in order) if (o !== actor && (if (chat) freeToChat(o) else free(o)) && hypot(o.x - actor.x, o.y - actor.y) <= best + TIE_YARDS) near.add(o)
        return if (near.size == 1) near[0] else near[random.nextInt(near.size)]
    }

    // what [pick] chose, read by [startPartner]
    private var picked: PartnerPair? = null
    private var pickedEmote: PartnerEmote? = null
    private var pickedFurniture: SceneObject? = null

    /** Chooses what [a] and [b] will play: a random usable pair, else a random emote both models have. False: nothing. */
    private fun pick(a: Actor, b: Actor): Boolean {
        val usable = pairScratch.apply { clear() }
        for (p in pairs) if (furnitureFor(p, a, b) != NO_PLACE) usable.add(p)
        picked = null; pickedEmote = null; pickedFurniture = null
        if (usable.isNotEmpty()) {
            val p = usable[random.nextInt(usable.size)]
            picked = p
            pickedFurniture = furnitureFor(p, a, b) as SceneObject?
            return true
        }
        val mutual = PartnerEmotes.mutual(a.id, b.id, emotes, emoteScratch)
        if (mutual.isEmpty()) return false
        pickedEmote = mutual[random.nextInt(mutual.size)]
        return true
    }

    /** The furniture [p] would play on (null: a free spot), or [NO_PLACE] when the scene has none of what it needs free. */
    private fun furnitureFor(p: PartnerPair, a: Actor, b: Actor): Any? {
        for (location in p.locations) {
            val item = PartnerPlaces.furniture(location) ?: continue
            if (item in objects && item in spots && order.none { it !== a && it !== b && it.target == item }) return item
        }
        return if (PartnerPlaces.free(p)) null else NO_PLACE
    }

    /**
     * [initiator] goes to [target] and they play what [pick] chose, as a [Performance] led by [target]: on the floor, the
     * shared origin is put so that the target's slot is where it stands, turned so the slots line up with the two of them
     * (the initiator walks to its slot, the target stays); on furniture, the origin is the seat or bed, facing as it does,
     * and both walk to their slots. The clips start on one step and play on one clock.
     */
    private fun startPartner(initiator: Actor, target: Actor, directed: Boolean): Boolean {
        val pair = picked
        val emote = pickedEmote
        val furniture = pickedFurniture
        if (pair == null && emote == null) return false
        val slotI = pair?.slotA ?: PartnerEmotes.SLOT_A
        val slotT = pair?.slotB ?: PartnerEmotes.SLOT_B
        val keyI = if (pair != null) PlaybackKeys.pair(pair.id, 0) else PlaybackKeys.emote(emote!!.anim)
        val keyT = if (pair != null) PlaybackKeys.pair(pair.id, 1) else PlaybackKeys.emote(emote!!.anim)
        val spot = furniture?.let { spots[it] }
        val anchor: Vec3
        val heading: Float
        if (spot != null) {
            anchor = Vec3(spot.pose.x, spot.pose.y, spot.entry.z)
            heading = spot.facing
        } else {
            heading = PartnerMath.yaw(initiator.x, initiator.y, target.x, target.y, slotI, slotT, initiator.facing)
            val c = cos(heading); val s = sin(heading)
            anchor = Vec3(target.x - (slotT.x * c - slotT.y * s), target.y - (slotT.x * s + slotT.y * c), target.z)
        }
        val (seconds, cycles) = when {
            pair == null -> (if (emote!!.loop) 6f + random.nextFloat() * 6f else 3f + random.nextFloat() * 2f) to 1
            !pair.loops -> maxOf(pair.duration, MIN_HOLD) to 1
            pair.duration <= 0f -> (MIN_LOOP_TIME + random.nextFloat() * (MAX_LOOP_TIME - MIN_LOOP_TIME)) to 1
            else -> {
                val loops = 1 + random.nextInt(4)
                if (loops * pair.duration >= MIN_LOOP_TIME) pair.duration to loops
                else {
                    val time = MIN_LOOP_TIME + random.nextFloat() * (MAX_LOOP_TIME - MIN_LOOP_TIME)
                    pair.duration to kotlin.math.ceil(time / pair.duration).toInt()
                }
            }
        }
        // the pair's actor 0 is the initiator, the performance's role 0 the target (its lead)
        val sounds = pair?.sounds.orEmpty().map { it.copy(actor = if (it.actor == 0) 1 else 0) }
        val request = PerformanceOrder(initiator.id, listOf(role(keyT, slotT), role(keyI, slotI)), seconds, false,
            cycles.coerceIn(1, 100), pair?.name ?: emote!!.name, sounds)
        return startPerformance(target, request, null, Setup(anchor, heading, furniture, emote?.anim ?: -1, initiator, directed))
    }

    private fun role(key: String, slot: PartnerSlot) = PlaybackRole(key, Vec3(slot.x, slot.y, slot.z), slot.yawDeg * DEG)

    /** The end of a finished partner animation (both already released): they step a little apart, then carry on. */
    private fun part(performance: Performance) {
        val ox = performance.anchor.x
        val oy = performance.anchor.y
        for (actor in listOfNotNull(performance.lead, performance.other)) {
            if (actor.performance != null || actor.phase != Phase.WAITING) continue
            var dx = actor.x - ox; var dy = actor.y - oy
            var d = hypot(dx, dy)
            if (d < 0.05f) { dx = -cos(actor.facing); dy = -sin(actor.facing); d = 1f }
            val away = 1f + random.nextFloat() * 0.6f
            begin(actor, Activity.IDLE, Vec3(actor.x + dx / d * away, actor.y + dy / d * away, actor.z), directed = false)
        }
    }

    companion object {
        /** Yards a second a person walks. */
        const val WALK_SPEED = 1.25f
        private const val MAX_QUEUED = 64
        /** A performance's lead farther than this from its place walks there first. */
        private const val LEAD_STEP = 0.02f
        /** How far an idle NPC looks for a partner (yards). */
        const val PARTNER_RANGE = 6f
        /** Partners nearer than the nearest by this much are as near (a random one is taken). */
        const val TIE_YARDS = 0.25f
        /** The one waiting turns to the one coming within this distance. */
        const val FACE_RANGE = 3f
        /** A one-shot pair holds its last pose at least this long; a looping one plays this long at least, at most [MAX_LOOP_TIME]. */
        const val MIN_HOLD = 3f
        const val MIN_LOOP_TIME = 6f
        const val MAX_LOOP_TIME = 25f
        /** Seconds before either may start another partner animation by themselves. */
        const val MIN_COOLDOWN = 20f
        const val MAX_COOLDOWN = 60f
        /** Seconds before either may start another conversation by themselves. */
        const val MIN_CHAT_COOLDOWN = 25f
        const val MAX_CHAT_COOLDOWN = 60f
        /** The chance a conversation that ran its course leads the two into a partner animation (when those are on). */
        const val FRIENDLY_CHANCE = 0.2f
        private const val DEG = 0.017453292f
        private val NO_PLACE = Any()
    }
}
