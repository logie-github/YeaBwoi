package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.atan2
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
}

enum class SceneObject(val label: String, val model: Int, val position: Vec3, val approach: Vec3, val action: Activity) {
    CHAIR("Chair", 7151868, Vec3(-2.7f, 1.7f, 0f), Vec3(-2.7f, 1.7f, 0.05f), Activity.SIT),
    BED("Bed", 961704, Vec3(2.6f, 2.2f, 0f), Vec3(2.6f, 2.2f, 0.55f), Activity.REST),
    TABLE("Table and book", 198604, Vec3(1.9f, -1.3f, 0f), Vec3(1.9f, -2.25f, 0f), Activity.READ),
    COUCH("Couch", 6390430, Vec3(-0.2f, 2.9f, 0f), Vec3(-0.2f, 2.65f, 0.08f), Activity.SIT);

    companion object { const val BOOK_MODEL = 198029 }
}

class SceneSimulation(
    actorIds: List<String>,
    val objects: Set<SceneObject>,
    private val random: Random = Random.Default,
    private val durations: Map<Activity, Float> = emptyMap(),
) {
    data class Person(val id: String, val position: Vec3, val facing: Float, val activity: Activity, val directed: Boolean)

    private enum class Phase { WAITING, MOVING, ACTING }

    private class Actor(val id: String, var position: Vec3, var facing: Float, var remaining: Float) {
        var phase = Phase.WAITING
        var destination = position
        var activity = Activity.IDLE
        var target: SceneObject? = null
        var partner: Actor? = null
        var directed = false
    }

    private val actors = actorIds.distinct().mapIndexed { i, id ->
        Actor(id, Vec3((i % 3 - 1) * 1.2f, -0.6f + (i / 3) * 1.45f, 0f), -1.4f, 0.6f + i * 0.45f)
    }.associateBy { it.id }

    fun people(): List<Person> = actors.values.map { actor ->
        Person(actor.id, actor.position, actor.facing, if (actor.phase == Phase.MOVING) Activity.WALK else actor.activity, actor.directed)
    }

    fun available(target: SceneObject, id: String): Boolean =
        target in objects && actors.values.none { it.id != id && it.target == target }

    fun use(id: String, target: SceneObject): Boolean {
        val actor = actors[id] ?: return false
        if (!available(target, id)) return false
        begin(actor, target.action, target.approach, directed = true, target = target)
        return true
    }

    fun chat(id: String, otherId: String): Boolean {
        val actor = actors[id] ?: return false
        val other = actors[otherId] ?: return false
        if (actor === other) return false
        startChat(actor, other, directed = true)
        return true
    }

    fun freeWill(id: String) {
        actors[id]?.let { release(it) }
    }

    fun tick(seconds: Float) {
        val dt = seconds.coerceIn(0f, 0.1f)
        for (actor in actors.values) {
            when (actor.phase) {
                Phase.WAITING -> {
                    actor.remaining -= dt
                    if (actor.remaining <= 0f) choose(actor)
                }
                Phase.MOVING -> {
                    val delta = actor.destination - actor.position
                    val flat = Vec3(delta.x, delta.y, 0f)
                    val step = dt * 1.25f
                    val distance = hypot(flat.x, flat.y)
                    if (distance <= step) arrive(actor)
                    else actor.position += flat * (step / distance)
                }
                Phase.ACTING -> {
                    if (actor.activity == Activity.LISTEN) continue
                    actor.remaining -= dt
                    if (actor.remaining <= 0f) release(actor)
                }
            }
        }
    }

    private fun choose(actor: Actor) {
        val options = objects.filter { available(it, actor.id) }
        val people = actors.values.filter { it !== actor && it.phase == Phase.WAITING }
        val choice = random.nextInt(options.size + people.size + 1)
        when {
            choice < options.size -> options[choice].let { begin(actor, it.action, it.approach, false, it) }
            choice < options.size + people.size -> startChat(actor, people[choice - options.size], directed = false)
            else -> begin(actor, Activity.IDLE, Vec3(random.nextFloat() * 3 - 1.5f, random.nextFloat() * 2 - 1.4f, 0f), false)
        }
    }

    private fun startChat(actor: Actor, other: Actor, directed: Boolean) {
        release(actor)
        release(other)
        val side = if (other.position.x > 0f) -1f else 1f
        begin(actor, Activity.CHAT, Vec3(other.position.x + side * 0.85f, other.position.y, 0f), directed)
        actor.partner = other
        other.partner = actor
        other.activity = Activity.LISTEN
        other.phase = Phase.ACTING
    }

    private fun begin(actor: Actor, activity: Activity, destination: Vec3, directed: Boolean, target: SceneObject? = null) {
        release(actor)
        actor.activity = activity
        actor.target = target
        actor.directed = directed
        actor.destination = destination
        val delta = destination - actor.position
        if (delta.x * delta.x + delta.y * delta.y > 0.001f) actor.facing = atan2(delta.y, delta.x)
        actor.phase = Phase.MOVING
    }

    private fun arrive(actor: Actor) {
        actor.position = actor.destination
        actor.phase = Phase.ACTING
        actor.remaining = durations[actor.activity]?.takeIf { it.isFinite() && it > 0f } ?: actor.activity.seconds
        actor.facing = when (actor.target) {
            SceneObject.TABLE -> 1.57f
            SceneObject.CHAIR, SceneObject.COUCH, SceneObject.BED -> -1.57f
            null -> actor.facing
        }
        actor.partner?.let { other ->
            actor.facing = atan2(other.position.y - actor.position.y, other.position.x - actor.position.x)
            other.facing = atan2(actor.position.y - other.position.y, actor.position.x - other.position.x)
        }
    }

    private fun release(actor: Actor) {
        val partner = actor.partner
        actor.partner = null
        actor.target = null
        actor.position = actor.position.copy(z = 0f)
        actor.activity = Activity.IDLE
        actor.phase = Phase.WAITING
        actor.remaining = 1.25f + random.nextFloat() * 2f
        actor.directed = false
        if (partner != null) {
            partner.partner = null
            release(partner)
        }
    }
}
