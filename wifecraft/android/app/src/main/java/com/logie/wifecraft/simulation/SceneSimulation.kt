package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.ActorRequirement
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.FleshLift
import com.logie.wifecraft.anim.FleshProfile
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.story.Vec3
import kotlin.math.abs
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
 * the cycle's clock ([PartnerSound.actor]: the role's index). [pairId]: the partner pair ([SceneSimulation.pairs]) a two-person
 * one is, whose bodies read as needing furniture make it a placed order (played on that furniture, as [SceneSimulation.partnerOn]).
 */
data class PerformanceOrder(
    val partnerId: String?, val roles: List<PlaybackRole>, val seconds: Float,
    val repeat: Boolean = false, val repetitions: Int = 1,
    val name: String = "", val sounds: List<PartnerSound> = emptyList(),
    val pairId: String? = null,
    /** A staged animation's parts in order ([PlaybackSegment]: START once, LOOPs [repetitions] passes or until stopped when
     *  [repeat], END once or when stopped); empty: [roles] and [seconds] are its one loop, as always. */
    val segments: List<PlaybackSegment> = emptyList(),
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
    /**
     * Playing a clip: the floor under them ([floor]) and how far over it the clip's origin is ([lift] = z - floor): onto a
     * bed or seat ([FurnitureFit]), or off a floor its joints would reach under. The engine stands an actor on the floor under
     * it (Scene::placeActor), so whoever draws them raises the clip by [lift] (its Root track) or places them without that.
     * Otherwise the floor they stand on and 0.
     */
    var floor = 0f
    var lift = 0f
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
        /** Their queue plays round again when it ends ([queueOptions]). */
        val queueRepeat: Boolean = false,
        /** Playing an animation now (its clips started): Stop plays its END. */
        val performing: Boolean = false,
        /** The HUD's line for what plays ([StageLine]: its name, part and loop counter), else null. */
        val playing: String? = null,
    )

    /**
     * Each person's size over a human's for the pairs' positioning (anim/HumanScale.pairScale: a gnome's own, everyone
     * else 1; absent: 1): their pair slots, an ordered performance's places and the distance they stand talking are taken
     * by it ([PairFit]).
     */
    var sizes: Map<String, Float> = emptyMap()

    private fun size(actor: Actor?): Float = actor?.let { sizes[it.id] } ?: 1f

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
        if (item in objects) { spots[item] = spot; furnitureMade.remove(item); version++ }
    }

    // ---- furniture: the scene's objects as furniture (Furniture), what each partner animation needs (anim/Requirements)

    /** Each person's body over a human's (HumanScale.bodyScale: a dwarf's or gnome's own; absent: 1), for the furniture fit. */
    var bodyScales: Map<String, Float> = emptyMap()

    /**
     * Each person's own flesh ([FleshProfile], measured by the engine on their dressed model; absent: the generic body's,
     * the rules as before): how far a clip is raised off the floor, a seat or a bed for them ([FleshLift]), and how far apart
     * a pair's two bodies are kept ([PairSpacing]). The screen fills it as each person is dressed, and again when redressed.
     */
    var flesh: Map<String, FleshProfile> = emptyMap()

    /** The clip a playback key names (the screen's: an imported single clip), for a single clip's lift on furniture; null: none. */
    var clips: ((String) -> Anim?)? = null

    private fun fleshOf(actor: Actor?): FleshProfile = actor?.let { flesh[it.id] } ?: FleshProfile.GENERIC

    /** One role's lift worked out for one body, by the clip, its slot height and the body's profile (each kept as itself). */
    private class LiftKey(val anim: Anim, val z: Float, val body: FleshProfile, val need: ActorRequirement) {
        override fun equals(other: Any?) = other is LiftKey && other.anim === anim && other.z == z && other.body === body && other.need == need
        override fun hashCode() = (System.identityHashCode(anim) * 31 + z.hashCode()) * 31 + System.identityHashCode(body)
    }
    private val lifts = HashMap<LiftKey, ActorRequirement>()

    /** [need] (a role of [anim], its slot [z] high) with the lift its flesh needs on a body with [body] ([FleshLift]). */
    private fun liftedFor(need: ActorRequirement, anim: Anim, z: Float, body: FleshProfile): ActorRequirement {
        if (!body.measured) return need
        if (lifts.size > 512) lifts.clear()
        return lifts.getOrPut(LiftKey(anim, z, body, need)) {
            FleshLift.adjusted(need, BodyGeometry.frames(anim, z = z.toDouble(), flesh = body), body)
        }
    }

    /** [r] (what [pair] needs) with each role's lift for the body playing it: [first] the pair's actor 0, [second] its actor 1. */
    private fun forBodies(r: Requirement, pair: PartnerPair, first: FleshProfile, second: FleshProfile): Requirement {
        if (!first.measured && !second.measured) return r
        val out = r.actors.mapIndexed { i, need ->
            when (i) {
                0 -> liftedFor(need, pair.a, pair.slotA.z, first)
                1 -> liftedFor(need, pair.b, pair.slotB.z, second)
                else -> need
            }
        }
        return if (out.indices.all { out[it] === r.actors[it] }) r else r.copy(actors = out)
    }

    /**
     * How far each role of [pair] (needing [r]) is raised for the bodies playing it ([first] its actor 0, [second] its actor
     * 1), world units in the pair's actor order ([first]'s, then [second]'s); null: neither. On the floor ([onFurniture]
     * false) the whole lift its own flesh needs ([FleshLift]); on furniture, where the fit already put the clip's support on
     * the seat or bed for the generic body, the difference its own flesh makes (a bulky body up, a slim one down onto it).
     */
    private fun roleLifts(r: Requirement, pair: PartnerPair, first: Actor, second: Actor, onFurniture: Boolean): FloatArray? {
        val own = forBodies(r, pair, fleshOf(first), fleshOf(second)).actors
        fun raise(i: Int, body: Actor): Float {
            val mine = own.getOrNull(i)?.liftUnits ?: 0f
            val base = if (onFurniture) r.actors.getOrNull(i)?.liftUnits ?: 0f else 0f
            return (mine - base) * bodyUnit(body)
        }
        return floatArrayOf(raise(0, first), raise(1, second)).takeIf { l -> l.any { abs(it) > 1e-4f } }
    }

    /**
     * Where [pair] (needing [r]) fits on [f] for [first] (its actor 0) and [second] (its actor 1): the fit is made for the
     * generic body (the same places as before bodies were measured); each body's own flesh then raises its role by
     * [roleLifts] (onFurniture), which the performance's Setup carries.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun fitFor(r: Requirement, f: Furniture, unit: Float, pair: PartnerPair, first: Actor?, second: Actor?): FurnitureFit? =
        FurnitureFit.fit(r, f, unit)

    /**
     * What each partner animation needs, by its id ([PartnerPair.id]; the screen classifies them off the GL thread). A pair
     * with none here is classified once on first use when [classifyPairs], else it plays as before by its location names
     * until its entry comes.
     */
    var requirements: Map<String, Requirement> = emptyMap()
    var classifyPairs = true

    private val pieces = HashMap<SceneObject, Furniture>()
    private val furnitureMade = HashMap<SceneObject, Furniture>()
    private val classified = HashMap<String, Requirement>()

    /** [item] is [piece] (the location's own measure of it: locations.json "furniture"); null: made from its spot. */
    fun setFurniture(item: SceneObject, piece: Furniture?) {
        if (item !in objects) return
        if (piece == null) pieces.remove(item) else pieces[item] = piece
        furnitureMade.remove(item)
        version++
    }

    /** [item] as furniture (its spot measured), or null. */
    fun furniture(item: SceneObject): Furniture? {
        val spot = spots[item] ?: return null
        return furnitureMade.getOrPut(item) { pieces[item] ?: Furniture.of(item, spot) }
    }

    /**
     * What [pair] needs, or null (not classified yet: [classifyPairs] off). A pair whose roles had to be moved apart
     * ([spaced]) is read again at its new slots (the screen's reading is of the old ones).
     */
    fun requirementOf(pair: PartnerPair): Requirement? {
        val s = spaced(pair)
        if (s !== pair && classifyPairs) return classified.getOrPut(pair.id + SPACED) { FurnitureAnimation.of(s).requirement }
        return requirements[pair.id] ?: if (classifyPairs) classified.getOrPut(pair.id) { FurnitureAnimation.of(pair).requirement } else null
    }

    private val spacedPairs = java.util.IdentityHashMap<PartnerPair, PartnerPair>()

    /** [pair] with its two bodies kept apart ([PairSpacing]: no two trunks in each other, never both on one axis); cached. */
    fun spaced(pair: PartnerPair): PartnerPair = spacedPairs.getOrPut(pair) { PairSpacing.spaced(pair) }

    /** A pair spaced for two bodies (each kept as itself). */
    private class SpacedKey(val pair: PartnerPair, val a: FleshProfile, val b: FleshProfile) {
        override fun equals(other: Any?) = other is SpacedKey && other.pair === pair && other.a === a && other.b === b
        override fun hashCode() = (System.identityHashCode(pair) * 31 + System.identityHashCode(a)) * 31 + System.identityHashCode(b)
    }
    private val spacedForBodies = HashMap<SpacedKey, PartnerPair>()

    /** [pair] spaced for the bodies playing it ([first] its actor 0, [second] its actor 1: their own flesh); cached. */
    private fun spaced(pair: PartnerPair, first: Actor, second: Actor): PartnerPair {
        val a = fleshOf(first); val b = fleshOf(second)
        if (!a.measured && !b.measured) return spaced(pair)
        if (spacedForBodies.size > 256) spacedForBodies.clear()
        return spacedForBodies.getOrPut(SpacedKey(pair, a, b)) { PairSpacing.spaced(pair, a, b) }
    }

    /** World units of one body unit for these people (the human body's standing pelvis, times their mean size). */
    private fun bodyUnit(vararg people: Actor): Float =
        HUMAN_BODY_UNIT * people.map { bodyScales[it.id] ?: 1f }.average().toFloat()

    /** Whether nobody but [a] and [b] holds [item]. */
    private fun freeFor(item: SceneObject, a: Actor, b: Actor?): Boolean = order.none { it !== a && it !== b && it.target == item }

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
        val lead: Actor, val other: Actor?, var roles: List<PlaybackRole>, var seconds: Float,
        val anchor: Vec3, val heading: Float, var repeat: Boolean, cycles: Int,
        val directed: Boolean = true, val name: String = "", val sounds: List<PartnerSound> = emptyList(),
        val partnerActivity: Boolean = false, val initiator: Actor? = null, val furniture: SceneObject? = null,
        val emote: Int = -1,
        /** The floor the places are walked to on (NaN: the anchor's own height): played on furniture, the clip is raised. */
        val floorZ: Float = Float.NaN,
        val fit: FurnitureFit? = null,
        /** How far each role's clip is raised over [anchor] (world units, role order): a floor clip whose joints would reach
         *  under the floor ([com.logie.wifecraft.anim.ActorRequirement.liftUnits]); null: none. */
        val lifts: FloatArray? = null,
        /** Its parts ([PerformanceOrder.segments], fitted as [roles] are); empty: [roles] and [seconds] are its one loop. */
        segments: List<PlaybackSegment> = emptyList(),
    ) {
        val segments: List<PlaybackSegment> = segments.ifEmpty { listOf(PlaybackSegment(SegmentKind.LOOP, roles, seconds)) }
        /** Which part plays and how many loops are left (one plain loop: the cycles, or until stopped). */
        val cursor = StageCursor(this.segments.map { it.kind }, cycles, repeat)
        val staged: Boolean get() = segments.size > 1
        init { enter(cursor.index) }
        /** Part [index] is the one played now: its roles and length. */
        fun enter(index: Int) {
            val s = segments.getOrNull(index) ?: return
            roles = s.roles
            seconds = s.seconds.coerceIn(0.25f, 3600f)
        }
        var started = false
        /** The life simulation's interaction this is (an [InteractionId] ordinal: a hug, a dance), or -1. */
        var lifeKind = -1
        /** Seconds played since the first cycle started, and cycles finished: one clock for both. */
        var clock = 0f
        var loops = 0
        var startStep = 0L
    }

    /**
     * How a placed performance is set up: a partner animation's ([partner]; see [startPartner]) or one played on furniture
     * ([performOn]); [floorZ] the floor the places are walked to on, [fit] where it lands on the furniture.
     */
    private class Setup(val anchor: Vec3, val heading: Float, val furniture: SceneObject?, val emote: Int,
        val initiator: Actor, val directed: Boolean, val partner: Boolean = true, val floorZ: Float = Float.NaN,
        val fit: FurnitureFit? = null, val lifts: FloatArray? = null)

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
        /** Seconds to stay on furniture once there (the life simulation's; 0: the activity's own time). */
        var hold = 0f
        /** The life simulation's interaction this person is doing (an [InteractionId] ordinal), or -1. */
        var lifeTag = -1
        /** The queue plays round again when it ends; its next is picked at random ([queueOptions]). */
        var queueRepeat = false
        var queueShuffle = false
        /** A join's ease ([JoinEase]): the place drawn is this much off the real one, let out over [easeLength] seconds. */
        var easeX = 0f; var easeY = 0f; var easeZ = 0f; var easeYaw = 0f
        var easeTime = 0f; var easeLength = 0f
        val easeWeight: Float get() = JoinEase.weight(easeTime, easeLength)
        /** Eases from where this person was drawn ([x0]..[facing0], the ease then running included) to where they are now. */
        fun easeFrom(x0: Float, y0: Float, z0: Float, facing0: Float, length: Float) {
            val dx = x0 - x; val dy = y0 - y; val dz = z0 - z
            val dyaw = JoinEase.wrap(facing0 - facing)
            if (abs(dx) < JoinEase.EPSILON && abs(dy) < JoinEase.EPSILON && abs(dz) < JoinEase.EPSILON && abs(dyaw) < JoinEase.EPSILON) {
                easeLength = 0f; easeTime = 0f; easeX = 0f; easeY = 0f; easeZ = 0f; easeYaw = 0f
                return
            }
            easeX = dx; easeY = dy; easeZ = dz; easeYaw = dyaw
            easeTime = 0f; easeLength = length
        }
        val drawnX: Float get() = x + easeX * easeWeight
        val drawnY: Float get() = y + easeY * easeWeight
        val drawnZ: Float get() = z + easeZ * easeWeight
        val drawnFacing: Float get() = facing + easeYaw * easeWeight
    }

    /**
     * Two people talking: [a] went to [b]; [conversation] is its lines' clock (started when [a] arrives). For the life
     * simulation: what it was ([kind]), whether [b] said no ([refused]) and the small talk's [topic].
     */
    private class Talk(val a: Actor, val b: Actor, val conversation: Conversation, val directed: Boolean,
        val kind: InteractionId = InteractionId.CHAT, val refused: Boolean = false, val topic: String? = null)

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
    private val emoteScratch = ArrayList<PartnerEmote>(PartnerEmotes.ALL.size)

    /** How many people are on stage (the indices [pose] takes, in the cast's order). */
    val count: Int get() = order.size

    fun id(index: Int): String = order[index].id

    /** What the renderer needs of person [index] this tick, written into [out] (nothing allocated: once a frame). */
    fun pose(index: Int, out: Pose) {
        val a = order[index]
        // drawn where a join's ease has them (JoinEase): never a jump from one part's place to the next
        val w = a.easeWeight
        out.x = a.x + a.easeX * w; out.y = a.y + a.easeY * w; out.z = a.z + a.easeZ * w
        out.facing = a.facing + a.easeYaw * w
        out.playback = a.playback
        out.activity = if (a.phase == Phase.MOVING) Activity.WALK else a.activity
        val item = a.target.takeIf { a.phase == Phase.ACTING }
        out.furniture = item
        val entry = item?.let { spots[it]?.entry }
        if (entry != null) { out.ex = entry.x; out.ey = entry.y; out.ez = entry.z } else { out.ex = a.x; out.ey = a.y; out.ez = a.z }
        val playing = a.performance?.takeIf { a.phase == Phase.ACTING && a.playback != null }
        out.floor = playing?.let { p -> if (p.floorZ.isNaN()) p.anchor.z else p.floorZ } ?: a.z
        out.lift = (out.z - out.floor).coerceAtLeast(0f)
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
            actor.talk?.let { if (it.a === actor) it.b.id else it.a.id }, actor.queueRepeat,
            actor.performance?.started == true, actor.performance?.takeIf { it.started }?.let(::lineOf))
    }

    /**
     * Where [id]'s moment is, for its intensity ([IntensityModel]): what plays, with whom, the author's stage, the part, the
     * pass and how long it has played; [MomentProgress.IDLE] when nothing does. Allocates (a few times a second).
     */
    fun momentOf(id: String): MomentProgress {
        val a = actors[id] ?: return MomentProgress.IDLE
        val p = a.performance?.takeIf { it.started } ?: return MomentProgress.IDLE
        val c = p.cursor
        return MomentProgress(true, partnered = p.other != null, stage = MomentProgress.stageOf(p.name), kind = c.kind,
            pass = c.pass, wanted = c.wanted, seconds = p.clock)
    }

    /** Who [id] is with now (the other of a two-person animation, else of a conversation), or null: whom they look at. */
    fun companionOf(id: String): String? = actors[id]?.let { a -> partnerOf(a)?.id ?: talkingWith(id) }

    /** What [p] plays now for the HUD ([StageLine]). */
    private fun lineOf(p: Performance): String {
        val c = p.cursor
        val repeat = p.repeat && !c.stopping && p.lead.queue.isEmpty()
        return StageLine.of(p.name, c.kind, c.pass, c.wanted, repeat, p.staged)
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
        refusal = null
        val actor = actors[id] ?: return false
        return valid(actor, request) && startOrdered(actor, request, null)
    }

    /** An ordered [request]: a placed one on its furniture ([startPlacedOrder]), any other as before. */
    private fun startOrdered(lead: Actor, request: PerformanceOrder, previous: Performance?): Boolean =
        // (after a stage on furniture, a floor one starts afresh from the floor: never at the bed's height)
        startPlacedOrder(lead, request, previous) ?: startPerformance(lead, request, previous?.takeIf { it.furniture == null })

    /**
     * An ordered two-person [request] whose pair ([PerformanceOrder.pairId]) was read from its bodies as needing furniture (a bed,
     * a seat, a top: not the floor): played as [partnerOn] plays it, the slots kept apart and fitted to the two bodies, the origin
     * where [FurnitureFit] puts it on the piece, both walking to their places beside it. Its lead plays the pair's actor 0. Right
     * after a stage on furniture with the same two (a queue, an author's sequence) it stays on that furniture from the same origin,
     * never re-seated between stages; otherwise the nearest free piece in reach it fits. Null: not such an order (it plays as
     * before: its own places, on the floor). False: no free furniture in reach fits it ([refusal] says so).
     */
    private fun startPlacedOrder(lead: Actor, request: PerformanceOrder, previous: Performance?): Boolean? {
        val pairId = request.pairId ?: return null
        val other = request.partnerId?.let(actors::get) ?: return null
        if (request.roles.size != 2 || other === lead) return null
        val pair = pairs.firstOrNull { it.id == pairId } ?: return null
        val r = requirementOf(pair) ?: return null
        if (!placed(r) || r.surface in Requirement.FLOOR_LEVEL) return null
        val unit = bodyUnit(lead, other)
        val before = previous?.takeIf { it.other === other && it.furniture != null && it.fit != null }
        var item: SceneObject? = null
        var fit: FurnitureFit? = null
        if (before != null) {
            item = before.furniture
            val f = item?.let { furniture(it) }
            fit = f?.let { fitFor(r, it, unit, pair, lead, other) } ?: before.fit
        } else {
            var best = Float.MAX_VALUE
            for (candidate in SceneObject.entries) {
                if (candidate !in objects || !freeFor(candidate, lead, other)) continue
                val f = furniture(candidate) ?: continue
                val d = hypot(f.x - lead.x, f.y - lead.y)
                if (d > FURNITURE_RANGE || d >= best) continue
                val fits = fitFor(r, f, unit, pair, lead, other) ?: continue
                best = d; item = candidate; fit = fits
            }
        }
        if (item == null || fit == null) return refuse("Nothing free here fits ${request.name.ifBlank { "that" }}: it needs ${placeName(r.surface)}.")
        val floorZ = furniture(item)?.z ?: spots[item]?.entry?.z ?: fit.z
        // the pair's own slots, kept apart for these two bodies and fitted to their sizes (as startPartner's)
        val spacedPair = spaced(pair, lead, other)
        val (slotA, slotB) = PairFit.slots(spacedPair.slotA, spacedPair.slotB, size(lead), size(other))
        // (the pair's own two clips: a placed order plays its loop alone)
        val placedRequest = request.copy(roles = listOf(role(PlaybackKeys.pair(pair.id, 0), slotA), role(PlaybackKeys.pair(pair.id, 1), slotB)),
            segments = emptyList())
        val setup = Setup(before?.anchor ?: Vec3(fit.x, fit.y, fit.z), before?.heading ?: fit.yaw, item, -1, lead, true,
            partner = false, floorZ = floorZ, fit = fit, lifts = roleLifts(r, pair, lead, other, onFurniture = true))
        return startPerformance(lead, placedRequest, before, setup) || refuse("They can't reach the ${item.label.lowercase()} from where they stand.")
    }

    /** "a bed", "a seat"... for a placed order's refusal. */
    private fun placeName(surface: Surface): String = when (surface) {
        Surface.BED -> "a bed"
        Surface.COUCH -> "a couch"
        Surface.CHAIR -> "a chair"
        Surface.TABLE_OR_COUNTER -> "a table or counter"
        else -> "a seat"
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
            // this pass is the last: then its END (if any), as an ordered number of loops ends
            performance.cursor.finishPass()
            version++
        }
    }

    /**
     * Stops what [id] plays (as a player's Stop or Skip): its END at once (crossfaded as any join; [JoinEase]), and only
     * then are they let go; one with no END is let go now. Their queue then goes on ([stopAll] clears it first). False:
     * nothing plays.
     */
    fun stop(id: String): Boolean {
        val actor = actors[id] ?: return false
        val p = actor.performance ?: return false
        val lead = p.lead
        if (!p.started) {
            // still walking to their places: nothing played yet, nothing to end
            release(lead)
            return true
        }
        p.repeat = false
        when (val next = p.cursor.stop()) {
            StageCursor.KEEP -> Unit
            StageCursor.DONE -> finish(lead, p)
            else -> {
                p.enter(next)
                play(p)
            }
        }
        version++
        return true
    }

    /** Stops [id] and everything queued for them: the END plays, then they stand. */
    fun stopAll(id: String): Boolean {
        val actor = actors[id] ?: return false
        val lead = actor.performance?.lead ?: actor
        for (a in listOf(actor, lead)) { a.queue.clear(); a.queueRepeat = false }
        return stop(id)
    }

    /** [id]'s queue: played round again when it ends ([repeat]: each entry whole, start to end), its next picked at random ([shuffle]). */
    fun queueOptions(id: String, repeat: Boolean, shuffle: Boolean) {
        val actor = actors[id] ?: return
        if (actor.queueRepeat == repeat && actor.queueShuffle == shuffle) return
        actor.queueRepeat = repeat
        actor.queueShuffle = shuffle
        version++
    }

    /** A performance that ended (its last part played, or stopped with no END): its lead let go, their queue goes on. */
    private fun finish(actor: Actor, performance: Performance) {
        release(actor)
        actor.completedPerformance = performance.takeIf { actor.queue.isNotEmpty() }
        startQueuedPerformance(actor)
        if (performance.partnerActivity) part(performance)
    }

    private fun valid(lead: Actor, request: PerformanceOrder): Boolean =
        request.seconds.isFinite() && request.seconds > 0f && request.repetitions in 1..100 &&
            request.segments.all { it.roles.size == request.roles.size && it.seconds.isFinite() && it.seconds > 0f } &&
            if (request.partnerId == null) request.roles.size == 1
            else request.partnerId != lead.id && request.partnerId in actors && request.roles.size == 2

    private fun startPerformance(lead: Actor, request: PerformanceOrder, continuation: Performance? = null, setup: Setup? = null): Boolean {
        val other = request.partnerId?.let(actors::get)
        val previous = continuation?.takeIf { it.other === other }
        val anchor = previous?.anchor ?: setup?.anchor ?: movementOrigin(lead)
        val heading = previous?.heading ?: setup?.heading ?: lead.facing
        val directed = setup?.directed ?: true
        val furniture = setup?.furniture
        // an ordered one's places for the two bodies' sizes (a partner animation's slots were fitted already: startPartner)
        val sizes = floatArrayOf(size(lead), size(other))
        val roles = if (setup == null) PairFit.roles(request.roles, sizes) else request.roles
        // a staged one's parts fitted as its roles are (the same places for these two bodies)
        val segments = request.segments.map { s -> if (setup == null) s.copy(roles = PairFit.roles(s.roles, sizes)) else s }
        val performance = Performance(lead, other, roles, request.seconds.coerceIn(0.25f, 3600f),
            anchor, heading, request.repeat, request.repetitions, directed, request.name, request.sounds,
            setup?.partner == true, setup?.initiator, furniture, setup?.emote ?: -1, setup?.floorZ ?: Float.NaN, setup?.fit,
            setup?.lifts, segments)
        if (other != null && previous == null &&
            !beginAny(other, Activity.PERFORM, approaches(performance, roles[1]), directed, furniture)) return false

        // the lead steps to its own place too when it is not there already (a two-person one, one placed on furniture), so
        // nobody jumps when the clips start
        val leadSpots = if (previous == null && (other != null || setup != null)) approaches(performance, roles[0]) else null
        val leadWalks = leadSpots != null && hypot(leadSpots[0].x - lead.x, leadSpots[0].y - lead.y) > LEAD_STEP &&
            beginAny(lead, Activity.PERFORM, leadSpots, directed, furniture)
        if (!leadWalks) release(lead)
        if (other == null) {
            if (leadWalks) {
                // alone, walking to a place on furniture: the clip starts on arrival
                lead.performance = performance
                lead.directed = directed
                return true
            }
            lead.target = furniture
        }
        if (other != null) {
            if (previous != null) {
                release(other)
                // a stage after a stage on the same furniture: still on it
                other.target = furniture
            }
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
        if (actor.queue.isEmpty()) return
        // shuffled: any of the queue next; a repeated queue plays each again after the rest (whole: start, loops, end)
        val pick = if (actor.queueShuffle && actor.queue.size > 1) random.nextInt(actor.queue.size) else 0
        val next = actor.queue[pick]
        val partner = next.partnerId?.let(actors::get)
        if (partner?.let { it.directed || it.performance != null } == true) return
        actor.queue.removeAt(pick)
        if (actor.queueRepeat) actor.queue.addLast(next)
        val previous = actor.completedPerformance
        actor.completedPerformance = null
        refusal = null
        if (!startOrdered(actor, next, previous)) queueNotice = takeRefusal() ?: "The NPCs couldn’t reach their starting positions."
        version++
    }

    var queueNotice: String? = null
        private set

    fun takeQueueNotice(): String? = queueNotice.also { queueNotice = null }

    /**
     * Where [role]'s person walks to, best first: its place, on the floor the performance is reached from; a place on
     * furniture (inside its footprint) from the floor beside its nearest side (the clip then starts on the furniture: [play]
     * puts them there), stepping farther out from that side ([STEP_OUT]: a bed bigger than the usual one, its frame in the
     * way), then the other sides, then the furniture's own floor spot (where its user gets on) as the last resort.
     */
    private fun approaches(performance: Performance, role: PlaybackRole): List<Vec3> {
        val at = rolePosition(performance, role)
        if (performance.floorZ.isNaN()) return listOf(at)
        val floor = at.copy(z = performance.floorZ)
        val item = performance.furniture ?: return listOf(floor)
        if (performance.fit == null) return listOf(floor)
        val f = furniture(item) ?: return listOf(floor)
        val (lx, ly) = f.toLocal(at.x, at.y)
        val hx = f.spec.halfX + STEP_OFF
        val hy = f.spec.halfY + STEP_OFF
        if (abs(lx) >= hx || abs(ly) >= hy) return listOf(floor)
        val out = ArrayList<Vec3>()
        val sx = if (lx < 0f) -1f else 1f
        val sy = if (ly < 0f) -1f else 1f
        val nearX = hx - abs(lx) < hy - abs(ly)
        // the nearest side first, then the opposite long side, then the foot or head
        val sides = if (nearX) listOf(0 to sx, 1 to sy, 1 to -sy, 0 to -sx) else listOf(1 to sy, 0 to sx, 1 to -sy, 0 to -sx)
        for ((axis, sign) in sides) for (extra in STEP_OUT) {
            val p = if (axis == 0) f.toWorld(sign * (hx + extra), ly) else f.toWorld(lx, sign * (hy + extra))
            out += p.copy(z = performance.floorZ)
        }
        spots[item]?.entry?.let { out += it }
        return out
    }

    /** [begin] to the first of [destinations] that can be walked to. */
    private fun beginAny(actor: Actor, activity: Activity, destinations: List<Vec3>, directed: Boolean, target: SceneObject?): Boolean {
        for (d in destinations) if (begin(actor, activity, d, directed, target)) return true
        return false
    }

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
            // from where they were drawn: a join (another part, another stage) or onto their place from where they walked
            val x0 = actor.drawnX; val y0 = actor.drawnY; val z0 = actor.drawnZ; val f0 = actor.drawnFacing
            val joining = actor.playback != null
            actor.position = rolePosition(performance, role) + Vec3(0f, 0f, performance.lifts?.getOrNull(index) ?: 0f)
            actor.facing = performance.heading + role.facing
            actor.easeFrom(x0, y0, z0, f0, if (joining) JoinEase.JOIN else JoinEase.SETTLE)
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
        // the sound events are the loop's (a staged one's start and end have none of their own)
        if (performance.cursor.kind != SegmentKind.LOOP) return
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
            if (actor.easeTime < actor.easeLength) actor.easeTime += dt
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
                        // the next part (StageCursor): the start's loop, the next loop or pass, the end; none: it is over
                        val next = performance?.let { it.cursor.repeat = repeat; it.cursor.next() } ?: StageCursor.DONE
                        if (performance != null && next >= 0) {
                            // the next cycle keeps the step's overshoot: cycles stay on the one clock, never drifting
                            val over = actor.remaining
                            performance.enter(next)
                            play(performance)
                            actor.remaining += over
                            fire(performance, 0f, -over)
                        } else if (performance != null) {
                            finish(actor, performance)
                        } else {
                            release(actor)
                            startQueuedPerformance(actor)
                        }
                    }
                }
            }
        }
        pendingInvite?.let { p -> retryInvite(p) }
        val l = life
        if (l != null && l.enabled) {
            for (a in order) l.observe(a.index, observed(a), a.phase == Phase.MOVING, a.directed, a.target.takeIf { a.phase == Phase.ACTING })
            l.tick(dt, lifeWorld)
        }
    }

    private fun wander(actor: Actor) {
        val center = actor.wanderCenter
        // a few tries for a spot nobody stands on (or is walking to)
        var target = center + Vec3(random.nextFloat() * 3 - 1.5f, random.nextFloat() * 2 - 1f, 0f)
        for (k in 0 until 3) {
            if (clearOfOthers(target.x, target.y, actor, null) && !onAnEntry(target.x, target.y)) break
            target = center + Vec3(random.nextFloat() * 3 - 1.5f, random.nextFloat() * 2 - 1f, 0f)
        }
        if (!begin(actor, Activity.IDLE, target, false)) actor.remaining = 0.8f
    }

    /** Whether nobody but [actor] and [ignore] stands within [MIN_SEPARATION] of x, y, or is walking to there. */
    private fun clearOfOthers(x: Float, y: Float, actor: Actor, ignore: Actor?): Boolean {
        for (o in order) {
            if (o === actor || o === ignore) continue
            if (hypot(o.x - x, o.y - y) < MIN_SEPARATION) return false
            if (o.phase == Phase.MOVING) {
                val end = o.waypoints.lastOrNull() ?: continue
                if (hypot(end.x - x, end.y - y) < MIN_SEPARATION) return false
            }
        }
        return true
    }

    /** Whether x, y is on a piece of furniture's floor spot (where its next user comes to get on). */
    private fun onAnEntry(x: Float, y: Float): Boolean = spots.values.any { hypot(it.entry.x - x, it.entry.y - y) < MIN_SEPARATION }

    /**
     * A place to stand at or near [at] that nobody else is on: [at] itself when free (unless [aside]: then never on it, as
     * a furniture's floor spot is for its next user), else the first free point on rings round it ([STAND_RINGS] yards out,
     * eight ways) that is reached from [at] in one straight walk and is no other furniture's spot. [at] when none is.
     */
    private fun freeStand(at: Vec3, actor: Actor, ignore: Actor? = null, aside: Boolean = false): Vec3 {
        if (!aside && clearOfOthers(at.x, at.y, actor, ignore)) return at
        for ((ring, r) in STAND_RINGS.withIndex()) {
            for (k in 0 until 8) {
                val a = (k + ring * 0.5f) * (PI_F / 4f)
                val p = Vec3(at.x + cos(a) * r, at.y + sin(a) * r, at.z)
                if (!clearOfOthers(p.x, p.y, actor, ignore) || onAnEntry(p.x, p.y)) continue
                if (route != null && route.invoke(at, p)?.size != 1) continue
                return p
            }
        }
        return at
    }

    private fun choose(actor: Actor) {
        val l = life
        if (l != null && l.enabled && actor.queue.isEmpty() && !actor.directed) {
            val plan = l.decide(actor.index, lifeWorld)
            if (plan == null || !runLife(actor, plan, directed = false)) wander(actor)
            return
        }
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

    private fun startChat(actor: Actor, other: Actor, directed: Boolean, lines: List<ChatLine>? = null,
        kind: InteractionId = InteractionId.CHAT, refused: Boolean = false, topic: String? = null): Boolean {
        release(actor)
        release(other)
        val side = if (other.position.x > 0f) -1f else 1f
        val gap = PairFit.gap(CHAT_GAP, size(actor), size(other))
        // the side toward the room's middle first, then round the other: a place nobody else stands on
        var started = false
        for (pass in 0..1) {
            for (turn in CHAT_TURNS) {
                val a = (if (side > 0f) 0f else PI_F) + turn
                val x = other.x + cos(a) * gap
                val y = other.y + sin(a) * gap
                // first only where nobody else stands; then anywhere it can be walked to
                if (pass == 0 && !clearOfOthers(x, y, actor, other)) continue
                if (begin(actor, Activity.CHAT, Vec3(x, y, other.z), directed)) { started = true; break }
            }
            if (started) break
        }
        if (!started) {
            actor.remaining = 0.8f
            return false
        }
        actor.partner = other
        other.partner = actor
        other.activity = Activity.LISTEN
        other.phase = Phase.ACTING
        val words = chatter
        // the greetings by the hour ("Morning, ..."): the life clock's (the world's when it drives it)
        words?.hour = life?.clock?.hourOfDay ?: Double.NaN
        val said: List<ChatLine>? = lines ?: words?.conversation(actor.id, other.id, random, topic)
            ?: life?.takeIf { it.enabled }?.smallTalk(actor.index, other.index)
        if (said != null) {
            val talk = Talk(actor, other, Conversation(said), directed, kind, refused, if (lines == null) words?.lastTopicName else null)
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
    private fun endTalk(talk: Talk, cut: Boolean = false) {
        val a = talk.a
        val b = talk.b
        release(a)
        if (b.talk === talk) release(b)
        a.chatCooldown = MIN_CHAT_COOLDOWN + random.nextFloat() * (MAX_CHAT_COOLDOWN - MIN_CHAT_COOLDOWN)
        b.chatCooldown = MIN_CHAT_COOLDOWN + random.nextFloat() * (MAX_CHAT_COOLDOWN - MIN_CHAT_COOLDOWN)
        val l = life
        if (l != null && l.enabled) {
            l.talkEnded(a.index, b.index, talk.kind, talk.refused, talk.topic)
            when {
                talk.refused || cut -> {}
                talk.kind == InteractionId.INVITE_SIT -> seatTogether(a, b, talk.directed)
                !talk.directed && partnerChance > 0f && free(a) && free(b) && l.followUp(a.index, b.index) ->
                    pairUp(a, b, InteractionId.PLAY_TOGETHER, l.allowed(a.index, b.index), null, directed = false)
            }
            return
        }
        if (!cut && !talk.directed && partnerChance > 0f && random.nextFloat() < FRIENDLY_CHANCE && free(a) && free(b) && pick(a, b)) {
            startPartner(a, b, directed = false)
        }
    }

    /**
     * [actor] leaves the conversation they are in by themselves, saying [line] (life: a need that can't wait); one the
     * player ordered runs on. It counts as a conversation had.
     */
    private fun excuseTalk(actor: Actor, line: String) {
        val talk = actor.talk ?: return
        if (talk.directed || !talk.conversation.started) return
        endTalk(talk, cut = true)
        say(actor.id, line)
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
        actor.remaining = actor.hold.takeIf { it > 0f } ?: durations[actor.activity]?.takeIf { it.isFinite() && it > 0f } ?: actor.activity.seconds
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
        // a clip ending: eased back from where it was drawn to where they stand now (JoinEase), no jump off the furniture
        val wasPlaying = actor.playback != null
        val x0 = actor.drawnX; val y0 = actor.drawnY; val z0 = actor.drawnZ; val f0 = actor.drawnFacing
        actor.performance = null
        actor.completedPerformance = null
        actor.playback = null
        // off the furniture onto its floor spot, or beside it when someone stands there already
        if (actor.phase == Phase.ACTING) actor.target?.let { actor.position = freeStand(spots.getValue(it).entry, actor, aside = true) }
        if (wasPlaying) actor.easeFrom(x0, y0, z0, f0, JoinEase.SETTLE)
        actor.target = null
        actor.waypoints = emptyList()
        actor.activity = Activity.IDLE
        actor.phase = Phase.WAITING
        actor.remaining = 1.25f + random.nextFloat() * 2f
        actor.directed = false
        actor.hold = 0f
        actor.lifeTag = -1
        if (partner != null) {
            partner.partner = null
            release(partner)
        }
    }

    // ---- the life simulation (LifeSim): needs, moods, relationships; it decides, this runtime carries it out

    /**
     * The life simulation laid over this scene (null: none; NPCs choose as before). Its cast must be this one's, in order.
     * While it is on, an NPC free to choose asks it ([LifeSim.decide]) instead of wandering at random, and every step it is
     * told what each one is doing ([observed]).
     */
    var life: LifeSim? = null
        set(v) {
            require(v == null || v.ids == order.map { it.id }) { "the life simulation's cast is not the scene's" }
            field = v
            version++
        }

    private val lifeWorld = object : LifeWorld {
        override fun x(index: Int) = order[index].x
        override fun y(index: Int) = order[index].y
        override fun freeSpot(item: SceneObject, index: Int): Vec3? =
            if (item in objects && available(item, order[index].id)) spots[item]?.entry else null
        override fun approachable(index: Int) = canApproach(order[index])
        override fun finish(index: Int) = finishLife(order[index])
        override fun excuse(index: Int, line: String) = excuseTalk(order[index], line)
        override val chatterOn: Boolean get() = chatterChance > 0f
        override val pairsOn: Boolean get() = partnerChance > 0f
    }

    /**
     * Whether an NPC may go to [o] for something social by themselves: [o] under no order and in no conversation or pair,
     * not lately in one, and standing, wandering, sitting or reading (they stop for it, as anyone would); never one asleep
     * or at a meal.
     */
    private fun canApproach(o: Actor): Boolean {
        if (o.directed || o.talk != null || o.performance != null || o.queue.isNotEmpty() || o.chatCooldown > 0f) return false
        if (o.lifeTag == InteractionId.SLEEP.ordinal || o.lifeTag == InteractionId.NAP.ordinal) return false
        return when (o.phase) {
            Phase.WAITING -> true
            Phase.MOVING -> o.activity == Activity.IDLE && o.target == null
            Phase.ACTING -> o.activity == Activity.SIT || o.activity == Activity.READ || o.activity == Activity.IDLE
        }
    }

    /** What life counts [a] as doing now, or null (standing, waiting, walking, a two-person animation not begun). */
    private fun observed(a: Actor): InteractionId? {
        if (a.phase != Phase.ACTING) return null
        val p = a.performance
        if (p != null && !p.started) return null
        val talk = a.talk
        if (talk != null && !talk.conversation.started) return null
        if (a.lifeTag >= 0) return InteractionId.entries[a.lifeTag]
        return when (a.activity) {
            Activity.REST -> if (a.target == SceneObject.BED) InteractionId.SLEEP else InteractionId.NAP
            Activity.SIT -> InteractionId.RELAX
            Activity.READ -> InteractionId.READ
            Activity.CHAT, Activity.LISTEN -> InteractionId.CHAT
            Activity.PERFORM -> if (p?.other != null) InteractionId.PLAY_TOGETHER else null
            else -> null
        }
    }

    /** Ends what [actor] does by itself now (life: its need is met); a conversation, a pair or a player's order runs on. */
    private fun finishLife(actor: Actor) {
        if (actor.directed || actor.talk != null || actor.phase != Phase.ACTING) return
        val p = actor.performance
        if (p != null) {
            if (p.other != null || p.lead !== actor) return
            p.repeat = false
            p.cursor.finishPass()
        }
        actor.remaining = 0f
    }

    /** Carries out [plan] for [actor] with this runtime's own machinery. False: it couldn't start (held, no way there). */
    private fun runLife(actor: Actor, plan: LifePlan, directed: Boolean): Boolean {
        val id = plan.id
        return when (plan.provider) {
            LifeProvider.BED, LifeProvider.COUCH, LifeProvider.CHAIR -> {
                val item = plan.item ?: return false
                sitOrLie(actor, item, id, plan.holdSeconds, directed)
            }
            LifeProvider.TABLE -> {
                val item = plan.item ?: return false
                if (id == InteractionId.READ) sitOrLie(actor, item, id, plan.holdSeconds, directed)
                else {
                    if (!available(item, actor.id)) return false
                    val spot = spots.getValue(item)
                    emoteAt(actor, plan, spot.entry, spot.facing, item, directed)
                }
            }
            LifeProvider.FLOOR -> when (id) {
                InteractionId.WANDER -> { wander(actor); true }
                // a nap with nowhere to lie or sit: sat on the floor, nodding off
                InteractionId.NAP -> emoteAt(actor, plan, actor.position, actor.facing, null, directed, LifeAnims.SIT_GROUND)
                else -> emoteAt(actor, plan, actor.position, actor.facing, null, directed)
            }
            LifeProvider.FIREPLACE, LifeProvider.BASIN, LifeProvider.BOOKSHELF -> {
                val prop = plan.prop ?: return false
                emoteAt(actor, plan, prop.entry, prop.facing, null, directed)
            }
            LifeProvider.PERSON -> social(actor, plan, directed)
        }
    }

    /** [actor] goes to [item] and sits, lies or reads there (its own pose), for [hold] seconds. */
    private fun sitOrLie(actor: Actor, item: SceneObject, id: InteractionId, hold: Float, directed: Boolean): Boolean {
        if (!available(item, actor.id)) return false
        val activity = when {
            item == SceneObject.TABLE -> Activity.READ
            item == SceneObject.BED && id != InteractionId.RELAX && id != InteractionId.INVITE_SIT -> Activity.REST
            else -> Activity.SIT
        }
        if (!begin(actor, activity, spots.getValue(item).entry, directed, item)) return false
        actor.hold = hold
        actor.lifeTag = id.ordinal
        return true
    }

    /** [actor] walks to [anchor] (holding [furniture], if any) and plays [plan]'s emote there, facing [heading], its cycles over. */
    private fun emoteAt(actor: Actor, plan: LifePlan, anchor: Vec3, heading: Float, furniture: SceneObject?, directed: Boolean,
        anim: Int = plan.id.anim): Boolean {
        val id = plan.id
        if (anim < 0) return false
        // its own emote cycles (a meal's bites); another's (a nap sat on the floor) is one long hold
        val own = anim == id.anim && id.cycle > 0f
        val request = PerformanceOrder(null, listOf(PlaybackRole(PlaybackKeys.emote(anim), Vec3(0f, 0f, 0f), 0f)),
            if (own) id.cycle.coerceAtLeast(0.5f) else plan.holdSeconds.coerceAtLeast(1f),
            repetitions = if (own) plan.cycles.coerceIn(1, 100) else 1, name = id.label)
        if (!startPerformance(actor, request, null, Setup(anchor, heading, furniture, -1, actor, directed, partner = false))) return false
        actor.lifeTag = id.ordinal
        return true
    }

    /** A social [plan]: a conversation, a spoken exchange, or a two-person animation (with words when none fits). */
    private fun social(actor: Actor, plan: LifePlan, directed: Boolean): Boolean {
        val other = order.getOrNull(plan.other) ?: return false
        if (other === actor) return false
        if (!directed && !canApproach(other)) return false
        val id = plan.id
        fun spoken(refused: Boolean) = startChat(actor, other, directed, listOf(
            ChatLine(0, plan.ask ?: "", Gestures.of(plan.ask ?: "")),
            ChatLine(1, plan.answer ?: "", if (refused) Gestures.STAND else Gestures.of(plan.answer ?: ""))), id, refused)
        if (!plan.accepted) return spoken(true)
        return when (id) {
            InteractionId.CHAT -> startChat(actor, other, directed, kind = id)
            InteractionId.JOKE -> startChat(actor, other, directed, kind = id, topic = "jokes")
            InteractionId.HUG, InteractionId.KISS, InteractionId.DANCE_TOGETHER, InteractionId.PLAY_TOGETHER -> {
                val words = when (id) {
                    InteractionId.HUG -> PairKinds.HUG_WORDS
                    InteractionId.KISS -> PairKinds.KISS_WORDS
                    InteractionId.DANCE_TOGETHER -> PairKinds.DANCE_WORDS
                    else -> null
                }
                // the player's choice is any that fits the words; by themselves, only what their standing allows
                val max = if (directed) Intimacy.INTIMATE else life?.allowed(actor.index, other.index) ?: Intimacy.CASUAL
                pairUp(actor, other, id, max, words, directed) || spoken(false)
            }
            else -> spoken(false)
        }
    }

    /** [a] and [b] play a two-person animation of [id] no more intimate than [max] (and named by [words]). False: none fits. */
    private fun pairUp(a: Actor, b: Actor, id: InteractionId, max: Intimacy, words: List<String>?, directed: Boolean): Boolean {
        val gate = { name: String, emote: Int ->
            val level = if (emote >= 0) PairKinds.ofEmote(emote) else PairKinds.of(name)
            level.ordinal <= max.ordinal && (words == null || PairKinds.matches(name, words))
        }
        if (!pick(a, b, gate)) return false
        release(a)
        release(b)
        if (!startPartner(a, b, directed)) return false
        a.performance?.lifeKind = id.ordinal
        a.lifeTag = id.ordinal
        b.lifeTag = id.ordinal
        return true
    }

    /** After "Sit with me?": [a] and [b] each take a seat, the couch first, and sit a while together. */
    private fun seatTogether(a: Actor, b: Actor, directed: Boolean) {
        val seats = listOf(SceneObject.COUCH, SceneObject.CHAIR, SceneObject.BED).filter { it in objects && it in spots }
        val hold = life?.clock?.realSeconds(InteractionId.INVITE_SIT.hours.toDouble())?.coerceIn(LifeSim.MIN_HOLD, LifeSim.MAX_HOLD) ?: 30f
        var taken: SceneObject? = null
        for (item in seats) if (available(item, a.id) && sitOrLie(a, item, InteractionId.INVITE_SIT, hold, directed)) { taken = item; break }
        for (item in seats) if (item != taken && available(item, b.id) && sitOrLie(b, item, InteractionId.INVITE_SIT, hold, directed)) break
    }

    /**
     * The player's life interaction ([LifeControl.interact]): [id] by [aId] (with [bId]), as the player's order; the other
     * may refuse. The answer is the scene's notice.
     */
    fun lifeInteract(aId: String, bId: String?, id: InteractionId): LifeResult {
        val l = life?.takeIf { it.enabled } ?: return LifeResult(false, "Life simulation is off.")
        val a = actors[aId] ?: return LifeResult(false, "Nobody to do that.")
        val b = if (bId == null) null else actors[bId] ?: return LifeResult(false, "Nobody to do that with.")
        if (id.social && b == null) return LifeResult(false, "Choose someone for ${id.label}.")
        if (b != null && b.directed && b.performance?.partnerActivity != true) {
            // busy with another of the player's orders: asked again as soon as they are free (for a while)
            pendingInvite = PendingInvite(aId, b.id, id, time + INVITE_WAIT)
            return LifeResult(false, "${l.firstName(b.index)} is busy. ${l.firstName(a.index)} will ask again in a moment.")
        }
        val (plan, result) = l.request(a.index, b?.index ?: -1, id, lifeWorld)
        if (plan == null) return result
        if (!runLife(a, plan, directed = true)) return LifeResult(false, "Couldn't get there.")
        return result
    }

    /** The player's interaction waiting for its other to be free ([lifeInteract]), at most until [until] (seconds). */
    private class PendingInvite(val a: String, val b: String, val id: InteractionId, val until: Float)
    private var pendingInvite: PendingInvite? = null

    private fun retryInvite(p: PendingInvite) {
        val b = actors[p.b] ?: run { pendingInvite = null; return }
        val l = life?.takeIf { it.enabled } ?: run { pendingInvite = null; return }
        if (b.directed && b.performance?.partnerActivity != true) {
            if (time > p.until) {
                pendingInvite = null
                queueNotice = "${l.firstName(b.index)} is still busy. Try again later."
            }
            return
        }
        pendingInvite = null
        queueNotice = lifeInteract(p.a, p.b, p.id).message
    }

    /** Which of the player's menu interactions [aId] (with [bId]) can have here now. */
    fun lifeMenu(aId: String, bId: String?): List<Pair<InteractionId, Boolean>> {
        val l = life ?: return emptyList()
        val a = actors[aId] ?: return emptyList()
        return l.menu(a.index, bId?.let { actors[it]?.index } ?: -1, lifeWorld)
    }

    /** What [id] is doing as life counts it (tests). */
    fun lifeDoing(id: String): InteractionId? = actors[id]?.let { observed(it) }

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
    private var pickedFit: FurnitureFit? = null

    /** One way to play a pair: on the free floor ([item] null), on [item] where [fit] puts it, or on [item] by its names ([fit] null). */
    private class Option(val pair: PartnerPair, val item: SceneObject?, val fit: FurnitureFit?)

    /**
     * Chooses what [a] and [b] will play: a random way to play one of the pairs, else a random emote both models have. A pair
     * whose bodies were read ([placed]) plays where its every actor lands on something that takes their posture at their
     * height ([FurnitureFit.fit], as [FurnitureSelection.pickFor] chooses): on the floor when it needs none (a floor or
     * standing one), on any free furniture in reach that fits it (a floor one on a bed too); one that needs a surface with
     * no fitting furniture in reach is never chosen. A pair read from its names alone goes by its location names, as before
     * ([furnitureFor]). False: nothing.
     */
    private fun pick(a: Actor, b: Actor, gate: ((name: String, emote: Int) -> Boolean)? = null): Boolean {
        val options = ArrayList<Option>()
        val unit = bodyUnit(a, b)
        for (p in pairs) {
            if (gate != null && !gate(p.name, -1)) continue
            val r = requirementOf(p)
            if (r == null || !placed(r)) {
                val place = furnitureFor(p, a, b)
                if (place !== NO_PLACE) options += Option(p, place as SceneObject?, null)
                continue
            }
            if (r.surface in Requirement.FLOOR_LEVEL) options += Option(p, null, null)
            for (item in SceneObject.entries) {
                if (item !in objects || !freeFor(item, a, b)) continue
                val f = furniture(item) ?: continue
                if (hypot(f.x - a.x, f.y - a.y) > FURNITURE_RANGE && hypot(f.x - b.x, f.y - b.y) > FURNITURE_RANGE) continue
                FurnitureFit.fit(r, f, unit)?.let { options += Option(p, item, it) }
            }
        }
        picked = null; pickedEmote = null; pickedFurniture = null; pickedFit = null
        if (options.isNotEmpty()) {
            val o = options[random.nextInt(options.size)]
            picked = o.pair; pickedFurniture = o.item; pickedFit = o.fit
            return true
        }
        val mutual = PartnerEmotes.mutual(a.id, b.id, emotes, emoteScratch)
        if (gate != null) mutual.removeAll { !gate(it.name, it.anim) }
        if (mutual.isEmpty()) return false
        pickedEmote = mutual[random.nextInt(mutual.size)]
        return true
    }

    /** Whether [r] was read from the bodies (where each actor goes is known), not from names alone or not at all. */
    private fun placed(r: Requirement) = r.surface != Surface.UNKNOWN && "geometry" in r.basis

    /** The furniture [p] would play on by its names (null: a free spot), or [NO_PLACE] when the scene has none of it free. */
    private fun furnitureFor(p: PartnerPair, a: Actor, b: Actor): Any? {
        for (location in p.locations) {
            val item = PartnerPlaces.furniture(location) ?: continue
            if (item in objects && item in spots && freeFor(item, a, b)) return item
        }
        return if (PartnerPlaces.free(p)) null else NO_PLACE
    }

    /**
     * The player's Play animation on furniture: [id] (and [otherId], else the nearest other free to join) play [pairId] on
     * [item], where it fits, each on the role whose place is nearer. False: no such pair or person, the furniture is held
     * by someone else, the other is busy with an order, or it doesn't fit there.
     */
    fun partnerOn(item: SceneObject, pairId: String, id: String, otherId: String?): Boolean {
        refusal = null
        val actor = actors[id] ?: return false
        val pair = pairs.firstOrNull { it.id == pairId } ?: return false
        fun joinable(o: Actor) = o !== actor && !(o.directed && o.performance?.partnerActivity != true)
        val other = (if (otherId != null) actors[otherId]?.takeIf(::joinable)
            else order.filter(::joinable).minByOrNull { hypot(it.x - actor.x, it.y - actor.y) })
            ?: return refuse("Nobody is free to join now. Try again in a moment.")
        val what = item.label.lowercase()
        if (item !in objects || !freeFor(item, actor, other)) return refuse("The $what is in use.")
        val f = furniture(item) ?: return refuse("The $what isn't ready yet.")
        val r = requirementOf(pair) ?: return refuse("That animation is still being read.")
        val fit = FurnitureFit.fit(r, f, bodyUnit(actor, other)) ?: return refuse("That doesn't fit on the $what.")
        release(actor)
        release(other)
        picked = pair; pickedEmote = null; pickedFurniture = item; pickedFit = fit
        return startPartner(actor, other, directed = true) || refuse("They can't reach the $what from where they stand.")
    }

    /** Why the last [partnerOn] or [performOn] was refused (for the player's notice), or null; taken once. */
    var refusal: String? = null
        private set

    fun takeRefusal(): String? = refusal.also { refusal = null }

    private fun refuse(why: String): Boolean { refusal = why; return false }

    /**
     * The player's Play animation on furniture for one: [id] walks to where [requirement] fits on [item] and plays [request]
     * (one role; its offset across the floor is the fit's) there. False: no such person, a bad order, the furniture is held,
     * or it doesn't fit.
     */
    fun performOn(id: String, item: SceneObject, requirement: Requirement, request: PerformanceOrder): Boolean {
        refusal = null
        val actor = actors[id] ?: return false
        val what = item.label.lowercase()
        if (request.partnerId != null || !valid(actor, request) || item !in objects) return false
        if (!freeFor(item, actor, null)) return refuse("The $what is in use.")
        val f = furniture(item) ?: return refuse("The $what isn't ready yet.")
        val fit = FurnitureFit.fit(requirement, f, bodyUnit(actor)) ?: return refuse("That doesn't fit on the $what.")
        // raised (or let down) by what this body's own flesh changes of the lift (its single clip, when the screen gives it;
        // the screen's clip carries its role's lift in its Root track already)
        val body = fleshOf(actor)
        val clip = if (body.measured) request.roles.firstOrNull()?.clip?.let { key -> clips?.invoke(key) } else null
        val raise = if (clip != null && requirement.actors.isNotEmpty())
            (liftedFor(requirement.actors[0], clip, 0f, body).liftUnits - requirement.actors[0].liftUnits) * bodyUnit(actor) else 0f
        val role = request.roles[0].let { it.copy(offset = Vec3(0f, 0f, it.offset.z)) }
        // a staged one's parts on the fit's place too (each keeps its own lift)
        val segments = request.segments.map { s -> s.copy(roles = s.roles.map { it.copy(offset = Vec3(0f, 0f, it.offset.z)) }) }
        return startPerformance(actor, request.copy(roles = listOf(role), segments = segments), null,
            Setup(Vec3(fit.x, fit.y, fit.z), fit.yaw, item, -1, actor, true, partner = false, floorZ = f.z, fit = fit,
                lifts = if (abs(raise) > 1e-4f) floatArrayOf(raise) else null)) ||
            refuse("They can't reach the $what from where they stand.")
    }

    /**
     * The furniture [id] is on now (using it, or in an animation played on it) and, for the cameras, a point at the hips and
     * one at the head of each person on it (a rough height by what they do); null when they are on none.
     */
    fun onFurniture(id: String): Pair<Furniture, List<Vec3>>? {
        val actor = actors[id] ?: return null
        val item = actor.target?.takeIf { actor.phase == Phase.ACTING } ?: return null
        val f = furniture(item) ?: return null
        val points = ArrayList<Vec3>()
        for (o in order) {
            if (o.target != item || o.phase != Phase.ACTING) continue
            val raised = o.z - f.z > 0.25f
            val head = when {
                o.activity == Activity.REST -> 0.45f
                o.activity == Activity.SIT -> 1.05f
                o.performance?.fit != null && raised -> 0.9f
                else -> 1.6f
            }
            points += Vec3(o.x, o.y, o.z + 0.3f)
            points += Vec3(o.x, o.y, o.z + head)
        }
        return f to points
    }

    /**
     * [initiator] goes to [target] and they play what [pick] chose, as a [Performance]. On the floor: the shared origin is
     * put so that the target's slot is where it stands, turned so the slots line up with the two of them (the initiator
     * walks to its slot, the target stays). On furniture where the pair's bodies were read: the origin is where
     * [FurnitureFit] put it (raised onto the bed or top), each plays the role whose place is nearer
     * ([FurnitureSelection.cast]) and both walk to their places on the floor beside it. On furniture by its names: the
     * origin is the seat or bed, facing as it does. The clips start on one step and play on one clock.
     */
    private fun startPartner(initiator: Actor, target: Actor, directed: Boolean): Boolean {
        val pair = picked
        val emote = pickedEmote
        val furniture = pickedFurniture
        val fit = pickedFit
        if (pair == null && emote == null) return false
        // who plays the pair's actor 0 ([playA], the one who goes) and actor 1 ([playB], the performance's lead)
        var playA = initiator
        var playB = target
        if (fit != null) {
            val cast = FurnitureSelection.cast(fit, listOf(NearbyNpc(initiator.id, initiator.x, initiator.y), NearbyNpc(target.id, target.x, target.y)))
            if (cast != null && cast[0] == target.id) { playA = target; playB = initiator }
        }
        // the slots for the two bodies' sizes (a gnome comes nearer the contact point: PairFit), the bodies kept apart (PairSpacing,
        // by each one's own flesh when measured)
        val placed = pair?.let { spaced(it, playA, playB) }
        val (slotA, slotB) = PairFit.slots(placed?.slotA ?: PartnerEmotes.SLOT_A, placed?.slotB ?: PartnerEmotes.SLOT_B,
            size(playA), size(playB))
        val keyA = if (pair != null) PlaybackKeys.pair(pair.id, 0) else PlaybackKeys.emote(emote!!.anim)
        val keyB = if (pair != null) PlaybackKeys.pair(pair.id, 1) else PlaybackKeys.emote(emote!!.anim)
        val spot = furniture?.let { spots[it] }
        val anchor: Vec3
        val heading: Float
        var floorZ = Float.NaN
        if (fit != null && furniture != null) {
            anchor = Vec3(fit.x, fit.y, fit.z)
            heading = fit.yaw
            floorZ = this.furniture(furniture)?.z ?: spot?.entry?.z ?: fit.z
        } else if (spot != null) {
            anchor = Vec3(spot.pose.x, spot.pose.y, spot.entry.z)
            heading = spot.facing
        } else {
            heading = PartnerMath.yaw(playA.x, playA.y, playB.x, playB.y, slotA, slotB, playA.facing)
            val c = cos(heading); val s = sin(heading)
            anchor = Vec3(playB.x - (slotB.x * c - slotB.y * s), playB.y - (slotB.x * s + slotB.y * c), playB.z)
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
        // the pair's actor 0 is [playA], the performance's role 0 [playB] (its lead)
        val sounds = pair?.sounds.orEmpty().map { it.copy(actor = if (it.actor == 0) 1 else 0) }
        val request = PerformanceOrder(playA.id, listOf(role(keyB, slotB), role(keyA, slotA)), seconds, false,
            cycles.coerceIn(1, 100), pair?.name ?: emote!!.name, sounds)
        // on the floor, each role raised where its clip's flesh would reach under the floor (role order: playB's, playA's); on
        // furniture, by what each body's own flesh changes of the fit's lift (roleLifts)
        val lifts = if (pair != null) requirementOf(pair)?.let { roleLifts(it, pair, playA, playB, onFurniture = fit != null) }
            ?.let { floatArrayOf(it[1], it[0]) } else null
        return startPerformance(playB, request, null,
            Setup(anchor, heading, furniture, emote?.anim ?: -1, playA, directed, floorZ = floorZ, fit = fit, lifts = lifts))
    }

    private fun role(key: String, slot: PartnerSlot) = PlaybackRole(key, Vec3(slot.x, slot.y, slot.z), slot.yawDeg * DEG)

    /** The end of a finished partner animation (both already released): they step a little apart, then carry on. */
    private fun part(performance: Performance) {
        val other = performance.other
        if (other != null) life?.takeIf { it.enabled }?.together(performance.lead.index, other.index,
            InteractionId.entries.getOrNull(performance.lifeKind) ?: InteractionId.PLAY_TOGETHER)
        val ox = performance.anchor.x
        val oy = performance.anchor.y
        for (actor in listOfNotNull(performance.lead, performance.other)) {
            if (actor.performance != null || actor.phase != Phase.WAITING) continue
            var dx = actor.x - ox; var dy = actor.y - oy
            var d = hypot(dx, dy)
            if (d < 0.05f) { dx = -cos(actor.facing); dy = -sin(actor.facing); d = 1f }
            val away = 1f + random.nextFloat() * 0.6f
            // back down onto the floor the clip was raised from
            val floor = if (performance.floorZ.isNaN()) performance.anchor.z else performance.floorZ
            begin(actor, Activity.IDLE, Vec3(actor.x + dx / d * away, actor.y + dy / d * away, floor), directed = false)
        }
    }

    companion object {
        /** Yards a second a person walks. */
        const val WALK_SPEED = 1.25f
        private const val MAX_QUEUED = 64
        /** A performance's lead farther than this from its place walks there first. */
        private const val LEAD_STEP = 0.02f
        /** How far apart two humans stand talking (yards; a gnome stands nearer: [PairFit.gap]). */
        const val CHAT_GAP = 0.85f
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
        /** How far beside furniture someone stands before stepping onto it (yards). */
        const val STEP_OFF = 0.35f
        /** How much farther out from that side the next tries stand (yards: a bed bigger than the usual one). */
        private val STEP_OUT = floatArrayOf(0f, 0.6f, 1.2f, 1.9f, 2.6f)
        /** Nobody stands nearer another than this when it can be helped (yards). */
        const val MIN_SEPARATION = 0.5f
        /** The rings round a taken spot where the next one stands instead (yards). */
        private val STAND_RINGS = floatArrayOf(0.6f, 1.0f, 1.5f)
        /** Where round the other a talker stands (radians from the side toward the room's middle). */
        private val CHAT_TURNS = floatArrayOf(0f, (PI_F / 4f), -(PI_F / 4f), (PI_F / 2f), -(PI_F / 2f), PI_F, (3f * PI_F / 4f), -(3f * PI_F / 4f))
        /** Seconds the player's interaction waits for a busy other before it gives up. */
        const val INVITE_WAIT = 20f
        private const val PI_F = 3.1415927f
        /** Furniture farther than this from both (yards) is out of an idle pair's reach. */
        const val FURNITURE_RANGE = 10f
        /**
         * One body unit (a human's standing pelvis) in yards as the live scene draws a clip: the reference body's pelvis
         * ([BodyGeometry.REF_UNIT], 1.441) across the floor at [HumanScale.PAIR] (the humans' mean pelvis over it), which is
         * also how high a human's pelvis is drawn (the man's 1.130, the woman's 1.216; their mean 1.173).
         */
        val HUMAN_BODY_UNIT = (BodyGeometry.REF_UNIT * HumanScale.PAIR).toFloat()
        private const val DEG = 0.017453292f
        private val NO_PLACE = Any()
        private const val SPACED = ":spaced"
    }
}
