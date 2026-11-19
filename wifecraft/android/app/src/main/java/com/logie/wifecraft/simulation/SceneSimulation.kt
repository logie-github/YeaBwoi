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
    /** A two-person animation with another NPC (the partner activity: an imported pair, or a game emote together). */
    PARTNER("Partner", "With a partner", 0, 0f),
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
    var ex = 0f; var ey = 0f; var ez = 0f
    /** Goes up whenever this person's partner animation starts or stops (the renderer starts or stops it then). */
    var pairSerial = 0
    /** The pair being played (null: none, or a game emote: [emote]); [role] 0 the initiator, 1 the partner. */
    var pair: PartnerPair? = null
    var role = 0
    /** The game emote played with the partner (-1 none) and whether it is held. */
    var emote = -1
    var emoteLoop = false
}

/** What a person's partner activity is, for the menus and the tests ([SceneSimulation.partnerOf]). */
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
    /** The chance an idle NPC's decision is to go to a partner (0: never; [PartnerFrequency]). */
    var partnerChance: Float = PartnerFrequency.SOMETIMES.chance,
    /** The library's enabled pairs to pick from (empty: the Warcraft emotes, [PartnerEmotes]). */
    var pairs: List<PartnerPair> = emptyList(),
    /** Which game animations each NPC's model has (the fallback's emotes are picked from both models'). */
    var emotes: ModelEmotes = ModelEmotes.STANDARD,
) {
    data class Person(
        val id: String, val position: Vec3, val facing: Float, val activity: Activity, val directed: Boolean,
        val furniture: SceneObject? = null, val entry: Vec3 = position,
        /** The partner's id while in a partner activity (walking to them, playing, parting), else null. */
        val partner: String? = null,
    )

    /** A pair's sound event is due: actor [index] (the cast's order), sound [name]. Called inside [tick]. */
    var sounds: ((index: Int, name: String) -> Unit)? = null

    /** Steps run ([tick]s): two people start a pair at the same one. */
    var steps = 0L
        private set

    /** How many partner animations have started, and the last one's name (the screen's notice). */
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

    private enum class Phase { WAITING, MOVING, ACTING, PAIRED }

    private enum class Stage { APPROACH, ALIGN, PLAY, RELEASE }

    /** Two people's partner activity, from the walk over to the parting (pooled: none is made during play). */
    private class PairRun {
        var active = false
        var a: Actor? = null
        var b: Actor? = null
        var pair: PartnerPair? = null
        var emote: PartnerEmote? = null
        var furniture: SceneObject? = null
        var stage = Stage.APPROACH
        var ox = 0f; var oy = 0f; var oz = 0f; var yaw = 0f
        /** The shared clock: seconds since both started (one value for both, moved once a step). */
        var clock = 0f
        /** Where in the current cycle the clock is, and the cycles finished. */
        var cycle = 0f
        var loops = 0
        var playFor = 0f
        var hold = 0f
        var startStep = 0L
        // the walk over: where a goes
        var tx = 0f; var ty = 0f
    }

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
        var activity = Activity.IDLE
        var target: SceneObject? = null
        var partner: Actor? = null
        var directed = false
        // the partner activity
        var run: PairRun? = null
        var role = 0
        /** Seconds before this person may start another partner activity by themselves. */
        var cooldown = 0f
        var pairSerial = 0
        var showing = false
        var pairActivity = Activity.IDLE
        var slotX = 0f; var slotY = 0f; var slotZ = 0f; var slotF = 0f
        var fromX = 0f; var fromY = 0f; var fromZ = 0f
        var alignT = 0f; var alignFor = 0f
        var legWalking = false
        var aligned = false
    }

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
    private val slotScratch = FloatArray(4)
    private val runs = Array(maxOf(1, order.size / 2)) { PairRun() }

    /** How many people are on stage (the indices [pose] takes, in the cast's order). */
    val count: Int get() = order.size

    fun id(index: Int): String = order[index].id

    /** What the renderer needs of person [index] this tick, written into [out] (nothing allocated: once a frame). */
    fun pose(index: Int, out: Pose) {
        val a = order[index]
        out.x = a.x; out.y = a.y; out.z = a.z
        out.facing = a.facing
        out.pairSerial = a.pairSerial
        val run = a.run
        if (run != null && a.showing) {
            out.pair = run.pair; out.role = a.role
            out.emote = run.emote?.anim ?: -1; out.emoteLoop = run.emote?.loop ?: false
        } else { out.pair = null; out.role = 0; out.emote = -1; out.emoteLoop = false }
        if (run != null) {
            out.activity = a.pairActivity
            out.furniture = null
            out.ex = a.x; out.ey = a.y; out.ez = a.z
            return
        }
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
        val run = actor.run
        if (run != null) {
            val other = if (actor.role == 0) run.b else run.a
            return@map Person(actor.id, actor.position, actor.facing, actor.pairActivity, actor.directed, null, actor.position, other?.id)
        }
        val item = actor.target.takeIf { actor.phase == Phase.ACTING }
        Person(actor.id, actor.position, actor.facing, if (actor.phase == Phase.MOVING) Activity.WALK else actor.activity,
            actor.directed, item, item?.let { spots[it]?.entry } ?: actor.position)
    }

    /** [id]'s partner activity now, or null (menus and tests; allocates). */
    fun partnerOf(id: String): PartnerView? {
        val actor = actors[id] ?: return null
        val run = actor.run ?: return null
        val other = (if (actor.role == 0) run.b else run.a) ?: return null
        return PartnerView(run.stage.name, other.id, run.pair?.name ?: run.emote?.name.orEmpty(), actor.role, run.loops,
            run.clock, run.startStep, run.ox, run.oy, run.yaw, run.furniture, run.emote?.anim ?: -1)
    }

    /** Where [id] stands (tests). */
    fun position(id: String): Vec3? = actors[id]?.position

    /**
     * The player's order: [id] goes to [otherId] and starts a random partner animation with them (an imported pair, else
     * a game emote both have). Whatever either was doing stops (but someone carrying out another order of the player's
     * is not taken). False: no such person, the other is busy with an order, or nothing fits them both.
     */
    fun partner(id: String, otherId: String): Boolean {
        val actor = actors[id] ?: return false
        val other = actors[otherId] ?: return false
        if (actor === other || (other.directed && other.run == null)) return false
        if (!pick(actor, other)) return false
        release(actor)
        release(other)
        start(actor, other, directed = true)
        return true
    }

    /** Ends every partner activity at once (the scene ends): both of each pair back to idle where they stand. */
    fun endPartners() {
        for (run in runs) if (run.active) breakRun(run)
    }

    fun available(target: SceneObject, id: String): Boolean =
        target in spots && actors.values.none { it.id != id && it.target == target }

    fun use(id: String, target: SceneObject): Boolean {
        val actor = actors[id] ?: return false
        if (!available(target, id)) return false
        begin(actor, target.action, spots.getValue(target).entry, directed = true, target = target)
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
        val dt = if (seconds.isNaN()) 0f else seconds.coerceIn(0f, 0.1f)
        steps++
        for (actor in order) {
            if (actor.cooldown > 0f) actor.cooldown -= dt
            when (actor.phase) {
                Phase.PAIRED -> Unit
                Phase.WAITING -> {
                    actor.remaining -= dt
                    if (actor.remaining <= 0f) choose(actor)
                }
                Phase.MOVING -> {
                    val dx = actor.destination.x - actor.x
                    val dy = actor.destination.y - actor.y
                    val step = dt * WALK_SPEED
                    val distance = hypot(dx, dy)
                    if (distance <= step) arrive(actor)
                    else { actor.x += dx * (step / distance); actor.y += dy * (step / distance) }
                }
                Phase.ACTING -> {
                    if (actor.activity == Activity.LISTEN) continue
                    actor.remaining -= dt
                    if (actor.remaining <= 0f) release(actor)
                }
            }
        }
        for (run in runs) if (run.active) tickRun(run, dt)
    }

    private fun choose(actor: Actor) {
        if (partnerChance > 0f && actor.cooldown <= 0f && !actor.directed) {
            val other = nearestFree(actor)
            if (other != null && random.nextFloat() < partnerChance && pick(actor, other)) {
                start(actor, other, directed = false)
                return
            }
        }
        val options = optionScratch.apply { clear(); for (o in objects) if (available(o, actor.id)) add(o) }
        val people = peopleScratch.apply { clear(); for (o in order) if (o !== actor && o.phase == Phase.WAITING) add(o) }
        val choice = random.nextInt(options.size + people.size + 1)
        when {
            choice < options.size -> options[choice].let { begin(actor, it.action, spots.getValue(it).entry, false, it) }
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
        version++
    }

    private fun begin(actor: Actor, activity: Activity, destination: Vec3, directed: Boolean, target: SceneObject? = null) {
        release(actor)
        version++
        actor.activity = activity
        actor.target = target
        actor.directed = directed
        actor.destination = destination
        val delta = destination - actor.position
        if (delta.x * delta.x + delta.y * delta.y > 0.001f) actor.facing = atan2(delta.y, delta.x)
        actor.phase = Phase.MOVING
    }

    private fun arrive(actor: Actor) {
        version++
        actor.position = actor.destination
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
        actor.run?.let { breakRun(it) }
        val partner = actor.partner
        actor.partner = null
        if (actor.phase == Phase.ACTING) actor.target?.let { actor.position = spots.getValue(it).entry }
        actor.target = null
        actor.z = 0f
        actor.activity = Activity.IDLE
        actor.phase = Phase.WAITING
        actor.remaining = 1.25f + random.nextFloat() * 2f
        actor.directed = false
        if (partner != null) {
            partner.partner = null
            release(partner)
        }
    }

    // ---- the partner activity

    private fun free(o: Actor) = o.phase == Phase.WAITING && o.run == null && !o.directed && o.cooldown <= 0f

    /** The free person nearest [actor] that it can see and reach (ties within a quarter yard: a random one), or null. */
    private fun nearestFree(actor: Actor): Actor? {
        var best = Float.MAX_VALUE
        for (o in order) {
            if (o === actor || !free(o) || !visible(actor.x, actor.y, o.x, o.y)) continue
            val d = hypot(o.x - actor.x, o.y - actor.y)
            if (d <= PARTNER_RANGE && d < best) best = d
        }
        if (best == Float.MAX_VALUE) return null
        val near = peopleScratch.apply { clear() }
        for (o in order) {
            if (o === actor || !free(o) || !visible(actor.x, actor.y, o.x, o.y)) continue
            if (hypot(o.x - actor.x, o.y - actor.y) <= best + TIE_YARDS) near.add(o)
        }
        return if (near.size == 1) near[0] else near[random.nextInt(near.size)]
    }

    /** Whether nothing of the scene's furniture stands on the line between two points. */
    private fun visible(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 < 1e-6f) return true
        for (o in objects) {
            val t = (((o.position.x - ax) * dx + (o.position.y - ay) * dy) / len2).coerceIn(0f, 1f)
            val px = ax + dx * t - o.position.x; val py = ay + dy * t - o.position.y
            if (px * px + py * py < BLOCK_RADIUS * BLOCK_RADIUS) return false
        }
        return true
    }

    // what [pick] chose, read by [start]
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

    private fun start(a: Actor, b: Actor, directed: Boolean) {
        val run = runs.firstOrNull { !it.active } ?: return
        version++
        run.active = true
        run.a = a; run.b = b
        run.pair = picked; run.emote = pickedEmote; run.furniture = pickedFurniture
        run.stage = Stage.APPROACH
        run.clock = 0f; run.cycle = 0f; run.loops = 0
        for (actor in arrayOf(a, b)) {
            actor.run = run
            actor.phase = Phase.PAIRED
            actor.activity = Activity.PARTNER
            actor.target = run.furniture
            actor.directed = false
            actor.showing = false
            actor.aligned = false
            actor.pairActivity = Activity.IDLE
        }
        a.role = 0; b.role = 1
        a.directed = directed
        // a walks up to b's front: a little short of b, on the side a comes from
        val dx = a.x - b.x; val dy = a.y - b.y
        val d = hypot(dx, dy)
        if (d <= APPROACH_GAP + 0.2f) { beginAlign(run); return }
        run.tx = b.x + dx / d * APPROACH_GAP
        run.ty = b.y + dy / d * APPROACH_GAP
        a.pairActivity = Activity.WALK
        a.facing = atan2(run.ty - a.y, run.tx - a.x)
    }

    private fun tickRun(run: PairRun, dt: Float) {
        val a = run.a ?: return
        val b = run.b ?: return
        when (run.stage) {
            Stage.APPROACH -> {
                val dx = run.tx - a.x; val dy = run.ty - a.y
                val distance = hypot(dx, dy)
                val step = dt * WALK_SPEED
                if (hypot(a.x - b.x, a.y - b.y) <= FACE_RANGE) b.facing = atan2(a.y - b.y, a.x - b.x)
                if (distance <= step) { a.x = run.tx; a.y = run.ty; beginAlign(run) }
                else { a.x += dx * (step / distance); a.y += dy * (step / distance) }
            }
            Stage.ALIGN -> {
                alignStep(a, dt)
                alignStep(b, dt)
                if (a.aligned && b.aligned) beginPlay(run)
            }
            Stage.PLAY -> {
                val previous = run.cycle
                run.clock += dt
                val pair = run.pair
                val length = pair?.duration ?: 0f
                if (pair != null && length > 0f) {
                    var next = previous + dt
                    if (pair.loops) {
                        var from = previous
                        while (next >= length) {
                            fire(run, from, length)
                            next -= length
                            run.loops++
                            from = -1f
                        }
                        fire(run, from, next)
                        run.cycle = next
                    } else {
                        if (previous < length) fire(run, previous, minOf(next, length))
                        if (next >= length && previous < length) run.loops = 1
                        run.cycle = next
                    }
                } else run.cycle += dt
                if (run.clock >= run.playFor - 1e-4f) beginRelease(run)
            }
            Stage.RELEASE -> {
                run.hold -= dt
                if (run.hold <= 0f) part(run)
            }
        }
    }

    /** Sounds of [run]'s pair whose time is in (from, to] of the cycle. */
    private fun fire(run: PairRun, from: Float, to: Float) {
        val pair = run.pair ?: return
        val listener = sounds ?: return
        if (to <= from) return
        for (i in pair.sounds.indices) {
            val s = pair.sounds[i]
            if (s.timeSec > from && s.timeSec <= to) {
                val who = if (s.actor == 1) run.b else run.a
                if (who != null) listener(who.index, s.name)
            }
        }
    }

    private fun beginAlign(run: PairRun) {
        val a = run.a ?: return
        val b = run.b ?: return
        version++
        run.stage = Stage.ALIGN
        val slotA = run.pair?.slotA ?: PartnerEmotes.SLOT_A
        val slotB = run.pair?.slotB ?: PartnerEmotes.SLOT_B
        val item = run.furniture
        val spot = item?.let { spots[it] }
        if (spot != null) {
            // on furniture: the origin on the floor under its seat or bed, facing as it does (the clip lifts them onto it)
            run.ox = spot.pose.x; run.oy = spot.pose.y; run.oz = 0f; run.yaw = spot.facing
        } else {
            snapFloor((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)
            run.ox = snapX; run.oy = snapY; run.oz = 0f
            run.yaw = PartnerMath.yaw(a.x, a.y, b.x, b.y, slotA, slotB, a.facing)
        }
        aim(a, run, slotA)
        aim(b, run, slotB)
    }

    /** Sets [actor]'s slot and how it gets there: a walk when far, then a short eased move. */
    private fun aim(actor: Actor, run: PairRun, slot: PartnerSlot) {
        PartnerMath.place(run.ox, run.oy, run.oz, run.yaw, slot, slotScratch)
        actor.slotX = slotScratch[0]; actor.slotY = slotScratch[1]; actor.slotZ = slotScratch[2]; actor.slotF = slotScratch[3]
        actor.aligned = false
        val d = hypot(actor.slotX - actor.x, actor.slotY - actor.y)
        actor.legWalking = d > ALIGN_REACH
        if (actor.legWalking) {
            actor.facing = atan2(actor.slotY - actor.y, actor.slotX - actor.x)
            actor.pairActivity = Activity.WALK
        } else startEase(actor)
    }

    private fun startEase(actor: Actor) {
        actor.legWalking = false
        actor.fromX = actor.x; actor.fromY = actor.y; actor.fromZ = actor.z
        val d = hypot(actor.slotX - actor.x, actor.slotY - actor.y)
        actor.alignT = 0f
        actor.alignFor = (d / WALK_SPEED).coerceIn(MIN_EASE, MAX_EASE)
        actor.facing = actor.slotF
        actor.pairActivity = if (d > 0.08f) Activity.WALK else Activity.IDLE
    }

    private fun alignStep(actor: Actor, dt: Float) {
        if (actor.aligned) return
        if (actor.legWalking) {
            val dx = actor.slotX - actor.x; val dy = actor.slotY - actor.y
            val d = hypot(dx, dy)
            val step = dt * WALK_SPEED
            val left = d - EASE_FROM
            if (left <= step) {
                actor.x += dx / d * left.coerceAtLeast(0f); actor.y += dy / d * left.coerceAtLeast(0f)
                startEase(actor)
            } else { actor.x += dx * (step / d); actor.y += dy * (step / d) }
            return
        }
        actor.alignT += dt
        val t = (actor.alignT / actor.alignFor).coerceIn(0f, 1f)
        val k = t * t * (3f - 2f * t)
        actor.x = actor.fromX + (actor.slotX - actor.fromX) * k
        actor.y = actor.fromY + (actor.slotY - actor.fromY) * k
        actor.z = actor.fromZ + (actor.slotZ - actor.fromZ) * k
        if (t >= 1f) {
            actor.x = actor.slotX; actor.y = actor.slotY; actor.z = actor.slotZ
            actor.aligned = true
            actor.pairActivity = Activity.IDLE
        }
    }

    private fun beginPlay(run: PairRun) {
        val a = run.a ?: return
        val b = run.b ?: return
        version++
        run.stage = Stage.PLAY
        run.clock = 0f; run.cycle = 0f; run.loops = 0
        run.startStep = steps
        val pair = run.pair
        run.playFor = when {
            pair == null -> if (run.emote?.loop == true) 6f + random.nextFloat() * 6f else 3f + random.nextFloat() * 2f
            !pair.loops -> maxOf(pair.duration, MIN_HOLD)
            pair.duration <= 0f -> MIN_LOOP_TIME + random.nextFloat() * (MAX_LOOP_TIME - MIN_LOOP_TIME)
            else -> {
                val loops = 1 + random.nextInt(4)
                if (loops * pair.duration >= MIN_LOOP_TIME) loops * pair.duration
                else {
                    val time = MIN_LOOP_TIME + random.nextFloat() * (MAX_LOOP_TIME - MIN_LOOP_TIME)
                    kotlin.math.ceil(time / pair.duration) * pair.duration
                }
            }
        }
        for (actor in arrayOf(a, b)) {
            actor.facing = actor.slotF
            actor.pairActivity = Activity.PARTNER
            actor.showing = true
            actor.pairSerial++
        }
        pairStarts++
        lastPairName = pair?.name ?: run.emote?.name
        fire(run, -1f, 0f)
    }

    private fun beginRelease(run: PairRun) {
        version++
        run.stage = Stage.RELEASE
        run.hold = 0.6f + random.nextFloat() * 0.6f
        for (actor in arrayOf(run.a, run.b)) {
            if (actor == null) continue
            actor.pairActivity = Activity.IDLE
            if (actor.showing) { actor.showing = false; actor.pairSerial++ }
        }
    }

    /** The end of a finished pair: both walk a few steps apart, then carry on by themselves. */
    private fun part(run: PairRun) {
        val a = run.a; val b = run.b
        val ox = run.ox; val oy = run.oy
        val item = run.furniture
        detach(run)
        for (actor in arrayOf(a, b)) {
            if (actor == null) continue
            if (item != null) spots[item]?.entry?.let { actor.x = it.x; actor.y = it.y }
            actor.z = 0f
            var dx = actor.x - ox; var dy = actor.y - oy
            var d = hypot(dx, dy)
            if (d < 0.05f) { dx = -cos(actor.facing); dy = -sin(actor.facing); d = 1f }
            val away = 1f + random.nextFloat() * 0.6f
            snapFloor(actor.x + dx / d * away, actor.y + dy / d * away, avoid = false)
            begin(actor, Activity.IDLE, Vec3(snapX, snapY, 0f), directed = false)
        }
    }

    /** Ends [run] at once (an order, the scene's end): both idle where they stand (off the furniture), nobody frozen. */
    private fun breakRun(run: PairRun) {
        val a = run.a; val b = run.b
        val item = run.furniture
        detach(run)
        for (actor in arrayOf(a, b)) {
            if (actor == null) continue
            if (item != null && actor.z > 0.01f) spots[item]?.entry?.let { actor.x = it.x; actor.y = it.y }
            actor.z = 0f
            actor.phase = Phase.WAITING
            actor.activity = Activity.IDLE
            actor.directed = false
            actor.remaining = 1.25f + random.nextFloat() * 2f
        }
    }

    /** Lets go of [run]: both leave it (stopping what they showed) with a cooldown; the pool slot is free again. */
    private fun detach(run: PairRun) {
        version++
        for (actor in arrayOf(run.a, run.b)) {
            if (actor == null) continue
            if (actor.showing) { actor.showing = false; actor.pairSerial++ }
            actor.run = null
            actor.target = null
            actor.pairActivity = Activity.IDLE
            actor.cooldown = MIN_COOLDOWN + random.nextFloat() * (MAX_COOLDOWN - MIN_COOLDOWN)
        }
        run.active = false
        run.a = null; run.b = null
        run.pair = null; run.emote = null; run.furniture = null
    }

    private var snapX = 0f
    private var snapY = 0f

    /** A floor point near ([x], [y]): inside the room's free floor and (with [avoid]) clear of the furniture. */
    private fun snapFloor(x: Float, y: Float, avoid: Boolean = true) {
        var px = x.coerceIn(FLOOR_MIN_X, FLOOR_MAX_X)
        var py = y.coerceIn(FLOOR_MIN_Y, FLOOR_MAX_Y)
        if (avoid) repeat(3) {
            for (o in objects) {
                val dx = px - o.position.x; val dy = py - o.position.y
                val d = hypot(dx, dy)
                if (d < CLEAR_RADIUS) {
                    if (d < 1e-3f) { px += CLEAR_RADIUS; continue }
                    px = o.position.x + dx / d * CLEAR_RADIUS
                    py = o.position.y + dy / d * CLEAR_RADIUS
                }
            }
            px = px.coerceIn(FLOOR_MIN_X, FLOOR_MAX_X)
            py = py.coerceIn(FLOOR_MIN_Y, FLOOR_MAX_Y)
        }
        snapX = px; snapY = py
    }

    companion object {
        /** Yards a second a person walks. */
        const val WALK_SPEED = 1.25f
        /** How far an idle NPC looks for a partner (yards). */
        const val PARTNER_RANGE = 6f
        /** Partners nearer than the nearest by this much are as near (a random one is taken). */
        const val TIE_YARDS = 0.25f
        /** The initiator stops this far from the partner's front. */
        const val APPROACH_GAP = 1.1f
        /** The partner turns to the initiator within this distance. */
        const val FACE_RANGE = 3f
        /** Slots farther than this are walked to before the eased move. */
        const val ALIGN_REACH = 1.2f
        /** The eased move starts this far from the slot after a walk. */
        const val EASE_FROM = 0.6f
        const val MIN_EASE = 0.25f
        const val MAX_EASE = 1.2f
        /** A one-shot pair holds its last pose at least this long; a looping one plays this long at least, at most [MAX_LOOP_TIME]. */
        const val MIN_HOLD = 3f
        const val MIN_LOOP_TIME = 6f
        const val MAX_LOOP_TIME = 25f
        /** Seconds before either may start another partner activity by themselves. */
        const val MIN_COOLDOWN = 20f
        const val MAX_COOLDOWN = 60f
        /** Furniture this close to the line between two people hides one from the other. */
        const val BLOCK_RADIUS = 0.45f
        /** A free pair's origin keeps this far from the furniture. */
        const val CLEAR_RADIUS = 1.1f
        const val FLOOR_MIN_X = -2.2f
        const val FLOOR_MAX_X = 2.2f
        const val FLOOR_MIN_Y = -2.0f
        const val FLOOR_MAX_Y = 1.3f
        private val NO_PLACE = Any()
    }
}
