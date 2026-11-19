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

data class PlaybackRole(val clip: String, val offset: Vec3, val facing: Float)
data class ClipPlayback(val clip: String, val command: Long)
data class PerformanceOrder(
    val partnerId: String?, val roles: List<PlaybackRole>, val seconds: Float,
    val repeat: Boolean = false, val repetitions: Int = 1,
)

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
}

class SceneSimulation(
    actorIds: List<String>,
    val objects: Set<SceneObject>,
    private val random: Random = Random.Default,
    private val durations: Map<Activity, Float> = emptyMap(),
    initiallyReady: Boolean = true,
    private val wanderOnly: Boolean = false,
    private val route: ((Vec3, Vec3) -> List<Vec3>?)? = null,
) {
    data class Person(
        val id: String, val position: Vec3, val facing: Float, val activity: Activity, val directed: Boolean,
        val furniture: SceneObject? = null, val entry: Vec3 = position,
        val queued: Int = 0, val repeating: Boolean = false,
    )

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

    private class Performance(
        val lead: Actor, val other: Actor?, val roles: List<PlaybackRole>, val seconds: Float,
        val anchor: Vec3, val heading: Float, var repeat: Boolean, var remainingCycles: Int,
    ) {
        var started = false
    }

    private enum class Phase { WAITING, MOVING, ACTING }

    private class Actor(val id: String, start: Vec3, var facing: Float, var remaining: Float) {
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
    }

    private val actors = actorIds.distinct().mapIndexed { i, id ->
        Actor(id, Vec3((i % 3 - 1) * 1.2f, -0.6f + (i / 3) * 1.45f, 0f), -1.4f, 0.6f + i * 0.45f)
    }.associateBy { it.id }
    /** The actors in the cast's order, for the per-frame reads ([count], [pose]) that index them. */
    private val order = actors.values.toList()
    // reused by [choose] (a decision makes no new lists)
    private val optionScratch = ArrayList<SceneObject>(objects.size)
    private val peopleScratch = ArrayList<Actor>(order.size)

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
    }

    /** Whether [item]'s spot has been measured (it can be used). */
    fun measured(item: SceneObject): Boolean = item in spots

    /** Who holds [item] (walking to it or using it), or null. */
    fun holder(item: SceneObject): String? = order.firstOrNull { it.target == item }?.id

    fun people(): List<Person> = actors.values.map { actor ->
        val item = actor.target.takeIf { actor.phase == Phase.ACTING }
        Person(actor.id, actor.position, actor.facing, if (actor.phase == Phase.MOVING) Activity.WALK else actor.activity,
            actor.directed, item, item?.let { spots[it]?.entry } ?: actor.position,
            actor.queue.size, actor.performance?.repeat == true)
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

    private fun startPerformance(lead: Actor, request: PerformanceOrder, continuation: Performance? = null): Boolean {
        val other = request.partnerId?.let(actors::get)
        val previous = continuation?.takeIf { it.other === other }
        val anchor = previous?.anchor ?: movementOrigin(lead)
        val heading = previous?.heading ?: lead.facing
        val performance = Performance(lead, other, request.roles, request.seconds.coerceIn(0.25f, 3600f),
            anchor, heading, request.repeat, request.repetitions)
        if (other != null && previous == null &&
            !begin(other, Activity.PERFORM, rolePosition(performance, request.roles[1]), directed = true)) return false

        release(lead)
        if (other != null) {
            if (previous != null) release(other)
            lead.partner = other
            other.partner = lead
            lead.performance = performance
            other.performance = performance
            lead.phase = Phase.ACTING
            lead.directed = true
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
            actor.directed = true
        }
        version++
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
        for (actor in order) startQueuedPerformance(actor)
        for (actor in order) {
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
                    if (actor.activity == Activity.LISTEN ||
                        (performance != null && (!performance.started || performance.lead !== actor))) continue
                    actor.remaining -= dt
                    if (actor.remaining <= 0f) {
                        val repeat = performance?.repeat == true && actor.queue.isEmpty() &&
                            performance.other?.queue?.isNotEmpty() != true
                        if (performance != null && (--performance.remainingCycles > 0 || repeat)) {
                            play(performance)
                        } else {
                            release(actor)
                            actor.completedPerformance = performance?.takeIf { actor.queue.isNotEmpty() }
                            startQueuedPerformance(actor)
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
        version++
        return true
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
        actor.performance?.let {
            play(it)
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
    }

    private fun release(actor: Actor) {
        version++
        val partner = actor.partner
        actor.partner = null
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

    companion object {
        /** Yards a second a person walks. */
        const val WALK_SPEED = 1.25f
        private const val MAX_QUEUED = 64
    }
}
