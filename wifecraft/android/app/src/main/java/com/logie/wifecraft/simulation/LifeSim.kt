package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Who an NPC is to the life simulation: their name, race (ChrRaces id; 0 unknown) and sex (null unknown), and what the
 * creator's Personality tab chose ([personality]: traits instead of the ones their id gives, the boundaries LifeSim honours).
 */
data class LifeProfile(val name: String, val race: Int = 0, val female: Boolean? = null,
                       val personality: com.logie.wifecraft.creator.CasPersonality? = null) {
    val first: String get() = name.trim().substringBefore(' ').ifEmpty { "friend" }
}

/** What the scene tells a decision and lets the life layer do ([SceneSimulation] is one). Indices are the cast's order. */
interface LifeWorld {
    fun x(index: Int): Float
    fun y(index: Int): Float
    /** Where [index] would stand to use [item] when it is measured and nobody else holds it, else null. */
    fun freeSpot(item: SceneObject, index: Int): Vec3?
    /** Whether [index] can be gone to for a social interaction now (idle, in nothing else, under no order of the player's). */
    fun approachable(index: Int): Boolean
    /** Cuts short what [index] is doing by itself (its need is met, or another is pressing). */
    fun finish(index: Int)
    /** [index] leaves the conversation they are in by themselves, saying [line] ("I should get something to eat."). */
    fun excuse(index: Int, line: String) {}
    /** Whether NPCs talk by themselves (the scene's Chatter setting is not Off). */
    val chatterOn: Boolean
    /** Whether NPCs start two-person animations by themselves (the Partner setting is not Off). */
    val pairsOn: Boolean
}

/**
 * One thing an NPC will do ([LifeSim.decide], [LifeSim.request]): [id] at [provider] ([item] of the scene's furniture, or
 * [prop]), with [other] (index, -1 none) who [accepted] or not, the two lines said when it is a spoken one ([ask],
 * [answer]), held [holdSeconds] on furniture or played [cycles] times as an emote.
 */
class LifePlan(
    val id: InteractionId, val provider: LifeProvider,
    val item: SceneObject? = null, val prop: LifeProp? = null, val other: Int = -1,
    val accepted: Boolean = true, val ask: String? = null, val answer: String? = null,
    val holdSeconds: Float = 0f, val cycles: Int = 1,
)

/** The answer to the player's interaction: done or refused, and the words for it. */
data class LifeResult(val accepted: Boolean, val message: String)

/** One line of the life feed: at game hour [hours], [text], with an [icon] key. */
data class LifeEvent(val hours: Double, val text: String, val icon: String)

/**
 * The life simulation: The Sims' needs, moods, traits, wants and relationships, World of Warcraft flavoured, laid over the
 * live scene's one activity runtime ([SceneSimulation]: walking, sitting, resting, reading, talking, partner animations,
 * emotes). It never moves anyone itself: [SceneSimulation] tells it each step what everyone is doing ([observe]) and runs
 * the time ([tick]); when an NPC is free to choose, it asks [decide] and carries the plan out with its own machinery.
 *
 * - Needs ([Need]) fall over game time ([LifeClock]) by each one's traits and fill by what is being done
 *   ([InteractionId.gains]): sleeping in the bed, sitting, eating at the table (an inn meal) or from the pack, chatting.
 * - Autonomy: a utility AI. Every interaction that can happen now (the room's free furniture and props, the open floor,
 *   everyone free to approach) is scored: need urgency x advertised gain x trait liking x distance x variety x noise, with
 *   the hour (sleep at night: an early bird earlier, a night owl later) and the relationship (who to flirt with) folded in;
 *   the best wins. The last [RECENT] choices are remembered so nobody loops. What can't be had (no bed free) is wanted
 *   aloud in a thought rather than faked.
 * - Relationships ([Relationship]) rise and fall with what two people do together and gate what they pick by themselves:
 *   hugs and dances from Friendly, kisses from Sweethearts, the library's intimate pairs from Romantic ([Intimacy]). An
 *   invitation is accepted or refused by the other's standing, mood and traits.
 * - Thoughts, moods, wishes and a feed of what happened ([LifeEvent]) for the screen ([view]); the whole state saves as
 *   JSON ([toJson], [load]).
 *
 * Cost: [observe] and the needs' fall are O(people) a step with no allocation; the pass over pairs (time together, drift
 * apart) is O(people^2) once every quarter game hour; the thought and mood check runs once a real second for each; a
 * decision (O(objects + people x social interactions)) runs only when an NPC is free to choose, a few seconds apart.
 * Seeded ([seed]): the same steps make the same life. Pure JVM.
 */
class LifeSim(
    ids: List<String>,
    profiles: Map<String, LifeProfile> = emptyMap(),
    seed: Long = 1L,
    val clock: LifeClock = LifeClock(),
) {
    val ids: List<String> = ids.distinct()
    val size: Int = this.ids.size
    private val random = Random(seed)
    private val indexOf = HashMap<String, Int>().apply { this@LifeSim.ids.forEachIndexed { i, id -> put(id, i) } }
    private val people = Array(size) { profiles[this.ids[it]] ?: LifeProfile(this.ids[it]) }
    private val minds = Array(size) { i ->
        Mind(LifePreferences.traits(people[i].personality).ifEmpty { Traits.assign(this.ids[i], people[i].race, people[i].female) })
    }
    private val prefs = Array(size) { people[it].personality?.preferences ?: com.logie.wifecraft.creator.Preferences() }
    private val rels = arrayOfNulls<Relationship>(size * size)
    private val events = ArrayDeque<LifeEvent>()

    /** Whether life runs: off, needs stand still and the scene's NPCs choose as they did before it. */
    var enabled = true
        set(v) { if (field != v) { field = v; version++ } }
    /** The room's props (a fireplace, a basin, a bookshelf); none until a location names them. */
    var props: List<LifeProp> = emptyList()
        set(v) { field = v; propHolder = IntArray(v.size) { -1 } }
    private var propHolder = IntArray(0)
    /** Called with the speed the player chose when a world clock drives the time (it keeps its own speed). */
    var onSpeed: ((LifeSpeed) -> Unit)? = null
    /** Goes up whenever what [view] shows changed (needs in steps of 5%, moods, thoughts, relationships, the feed). */
    var version = 0L
        private set
    /** The last answer to the player's interaction. */
    var lastResult: LifeResult? = null
        private set
    /** Decisions made so far ([decide]: the throttle's measure). */
    var decisions = 0L
        private set
    private var realTime = 0f
    private var pairHours = 0.0

    private class Mind(var traits: List<Trait>) {
        val needs = FloatArray(Need.COUNT) { 0.75f }
        val decay = FloatArray(Need.COUNT)
        var frozen = false
        val moodlets = ArrayList<Moodlet>(8)
        var mood = Mood.FINE
        var score = 0f
        var thought: LifeThought? = null
        var thoughtUntil = 0f
        var nextThought = 0f
        var wish: String? = null
        /** The last choices: interaction ordinal x 1000 + other + 1. */
        val recent = ArrayDeque<Int>()
        var doing: InteractionId? = null
        var moving = false
        var directed = false
        var since = 0.0
        var nextCheck = 0f
        val shown = IntArray(Need.COUNT)
        var complaint: InteractionId? = null
        var sleeps = 0
        var lastSleepEnd = 0.0
        /** Real seconds ([realTime]) when [doing] began, and its need then (the one it fills: [primary]). */
        var startedReal = 0f
        var startNeed = 1f
        /** Game hours of the conversation now running (its needs' fall stops after [TALK_COST_HOURS]). */
        var talkHours = 0.0
        /** The furniture [doing] is on (a nap's bed, couch or chair), or null: the floor. */
        var on: SceneObject? = null
        /** Whether the last sleep ended rested (then the night's bonus waits for a lower energy before bed again). */
        var wokeRested = false

        fun retrait(t: List<Trait>) {
            traits = t
            for (k in 0 until Need.COUNT) {
                var d = Need.entries[k].decayPerHour
                for (tr in t) d *= tr.decay(Need.entries[k])
                decay[k] = d
            }
        }

        init { retrait(traits) }
    }

    init {
        for ((i, m) in minds.withIndex()) {
            for (k in 0 until Need.COUNT) m.needs[k] = 0.55f + random.nextFloat() * 0.4f
            m.nextCheck = i * 0.17f
        }
    }

    fun index(id: String): Int = indexOf[id] ?: -1
    /** Person [i]'s first name (the notices'). */
    fun firstName(i: Int): String = people.getOrNull(i)?.first ?: "They"
    fun traits(id: String): List<Trait> = indexOf[id]?.let { minds[it].traits }.orEmpty()
    fun needs(id: String): FloatArray? = indexOf[id]?.let { minds[it].needs.copyOf() }
    fun need(id: String, need: Need): Float = indexOf[id]?.let { minds[it].needs[need.ordinal] } ?: 0f
    fun mood(id: String): Mood? = indexOf[id]?.let { minds[it].mood }
    fun moodScore(id: String): Float = indexOf[id]?.let { minds[it].score } ?: 0f
    fun thought(id: String): LifeThought? = indexOf[id]?.let { thoughtOf(it) }
    fun wish(id: String): String? = indexOf[id]?.let { minds[it].wish }
    fun doing(id: String): InteractionId? = indexOf[id]?.let { minds[it].doing }
    /** How many times [id] has gone to sleep (in the bed or napping on the couch). */
    fun sleeps(id: String): Int = indexOf[id]?.let { minds[it].sleeps } ?: 0
    fun events(): List<LifeEvent> = events.toList()
    fun frozen(id: String): Boolean = indexOf[id]?.let { minds[it].frozen } == true

    /** Sets [id]'s need (tests, a debug menu). */
    fun setNeed(id: String, need: Need, value: Float) {
        val i = indexOf[id] ?: return
        minds[i].needs[need.ordinal] = value.coerceIn(0f, 1f)
        version++
    }

    /** Gives [id] these traits (tests; a saved game's). */
    fun setTraits(id: String, traits: List<Trait>) {
        val i = indexOf[id] ?: return
        minds[i].retrait(traits.distinct())
        version++
    }

    /** Freezes [id]'s needs where they are (or lets them run again). */
    fun freeze(id: String, frozen: Boolean) {
        val i = indexOf[id] ?: return
        if (minds[i].frozen != frozen) { minds[i].frozen = frozen; version++ }
    }

    fun setSpeed(speed: LifeSpeed) {
        if (clock.speed != speed) { clock.speed = speed; version++ }
        onSpeed?.invoke(speed)
    }

    /** [a] and [b]'s relationship (made at nothing on first ask). */
    fun relationship(a: Int, b: Int): Relationship {
        val lo = minOf(a, b); val hi = maxOf(a, b)
        val k = lo * size + hi
        return rels[k] ?: Relationship(lo, hi).also { rels[k] = it }
    }

    fun relationship(a: String, b: String): Relationship? {
        val i = indexOf[a] ?: return null
        val j = indexOf[b] ?: return null
        return if (i == j) null else relationship(i, j)
    }

    /** The most intimate two-person animation [a] and [b] pick by themselves. */
    fun allowed(a: Int, b: Int): Intimacy {
        val r = relationship(a, b).allows
        // what the two allow (the Personality tab's stages): the least of their standing's and their own
        val own = minOf(LifePreferences.cap(prefs[a]), LifePreferences.cap(prefs[b]))
        return if (own < r) own else r
    }

    // ---- what is happening: the scene's report each step, and time

    /**
     * Person [i] is doing [doing] now (null: nothing life counts: standing, waiting), walking when [moving], under the
     * player's order when [directed]. Called each step for everyone by [SceneSimulation]; allocates nothing unless what they
     * do changed.
     */
    fun observe(i: Int, doing: InteractionId?, moving: Boolean, directed: Boolean, on: SceneObject? = null) {
        val m = minds[i]
        m.moving = moving
        m.directed = directed
        if (doing == m.doing) return
        val was = m.doing
        m.doing = doing
        m.on = on
        m.since = clock.simHours
        m.startedReal = realTime
        m.startNeed = doing?.let { primary(it) }?.let { m.needs[it] } ?: 1f
        m.talkHours = 0.0
        changed(i, was, doing)
    }

    private fun changed(i: Int, was: InteractionId?, now: InteractionId?) {
        val m = minds[i]
        val name = people[i].first
        if (was == InteractionId.SLEEP || was == InteractionId.NAP) {
            m.lastSleepEnd = clock.simHours
            m.wokeRested = m.needs[Need.ENERGY.ordinal] >= 0.85f
            if (m.needs[Need.ENERGY.ordinal] >= 0.85f) {
                moodlet(i, "rested", Mood.HAPPY, 0.15f, 4.0, "Well Rested")
                if (was == InteractionId.SLEEP) event("$name woke up well rested.", "event_wake")
            }
        }
        if (was == InteractionId.READ && clock.simHours - m.since >= 0.0) {
            if (Trait.BOOKISH in m.traits || random.nextFloat() < 0.35f) moodlet(i, "read", Mood.INSPIRED, 0.18f, 3.0, "Inspired by a Book")
        }
        if (was == InteractionId.DANCE) moodlet(i, "dance", Mood.PLAYFUL, 0.14f, 2.0, "Light on Their Feet")
        when (now) {
            InteractionId.SLEEP -> { m.sleeps++; event("$name went to bed.", "event_sleep") }
            InteractionId.NAP -> { m.sleeps++; event("$name dozed off ${LifeLines.onWhat(m.on)}.", "event_sleep") }
            InteractionId.EAT -> event("$name sat down to eat.", "event_eat")
            InteractionId.DRINK -> event("$name had a drink.", "event_drink")
            InteractionId.READ -> event("$name opened a book.", "event_read")
            InteractionId.DANCE -> event("$name started dancing.", "event_dance")
            InteractionId.WASH -> event("$name washed up.", "event_wash")
            else -> {}
        }
        if (now != null && LifeLines.doing(now) != null) {
            m.thought = LifeThought("thought_" + now.name.lowercase(), LifeLines.doing(now)!!)
            m.thoughtUntil = realTime + THOUGHT_SHOWN
            m.nextThought = realTime + THOUGHT_SHOWN + 4f
        }
        version++
    }

    /**
     * [dt] real seconds passed: the clock moves, needs fall and fill by what each one does, once a quarter game hour the pairs
     * are passed over (together: closer; apart: drifting), and once a real second each one's mood, thought and wish are
     * made again and what they do by themselves is cut short when its need is met or another is pressing.
     */
    fun tick(dt: Float, world: LifeWorld) {
        if (!enabled) return
        val step = if (dt.isFinite() && dt > 0f) dt else 0f
        realTime += step
        val h = clock.advance(step)
        if (h > 0.0) for (i in 0 until size) needs(i, h.toFloat())
        pairHours += h
        if (pairHours >= PAIR_PASS_HOURS) { pairs(pairHours, world); pairHours = 0.0 }
        for (i in 0 until size) {
            val m = minds[i]
            if (realTime < m.nextCheck) continue
            m.nextCheck = realTime + CHECK_SECONDS
            refresh(i)
            interrupt(i, world)
        }
    }

    private fun needs(i: Int, h: Float) {
        val m = minds[i]
        if (m.frozen) return
        val doing = m.doing
        val asleep = doing == InteractionId.SLEEP || doing == InteractionId.NAP
        val gains = doing?.gains
        // a conversation's words take real reading time (minutes of game time at x6): it costs the needs at most
        // [TALK_COST_HOURS] of their fall, however long it is read
        var talk = 1f
        if (doing?.social == true) {
            m.talkHours += h
            if (m.talkHours > TALK_COST_HOURS) talk = 0f
        }
        for (k in 0 until Need.COUNT) {
            var fall = m.decay[k] * (if (asleep) SLEEP_DECAY[k] else 1f) * talk
            if (m.moving) fall += WALK_COST[k]
            val gain = gains?.get(k) ?: 0f
            val before = m.needs[k]
            var v = before + (gain - fall) * h
            // freshening up is no bath: what it fills stops at its cap
            if (gain > 0f && doing != null && doing.cap < 1f && v > doing.cap) v = maxOf(before, doing.cap)
            m.needs[k] = v.coerceIn(0f, 1f)
        }
    }

    private fun pairs(hours: Double, world: LifeWorld) {
        for (a in 0 until size) for (b in a + 1 until size) {
            val r = relationship(a, b)
            val near = hypot(world.x(a) - world.x(b), world.y(a) - world.y(b)) <= TOGETHER_YARDS
            val ma = minds[a]; val mb = minds[b]
            val seatedTogether = near && seated(ma.doing) && seated(mb.doing)
            if (seatedTogether) {
                // sitting together is company: a little social, a little closer
                if (!ma.frozen) ma.needs[Need.SOCIAL.ordinal] = (ma.needs[Need.SOCIAL.ordinal] + 0.35f * hours.toFloat()).coerceAtMost(1f)
                if (!mb.frozen) mb.needs[Need.SOCIAL.ordinal] = (mb.needs[Need.SOCIAL.ordinal] + 0.35f * hours.toFloat()).coerceAtMost(1f)
                change(a, b, (0.8f * hours).toFloat() * (1f + compatibility(a, b)), 0f)
                r.apartHours = 0.0
            } else if (near && ma.doing?.social == true && mb.doing?.social == true) {
                r.apartHours = 0.0
            } else {
                r.apartHours += hours
                if (r.apartHours > RelationshipRules.DRIFT_AFTER_HOURS) {
                    val d = (RelationshipRules.DRIFT_PER_HOUR * hours).toFloat()
                    r.friendship = toward0(r.friendship, d)
                    r.romance = toward0(r.romance, d)
                }
            }
        }
    }

    private fun seated(id: InteractionId?) = id == InteractionId.RELAX || id == InteractionId.INVITE_SIT || id == InteractionId.EAT ||
        id == InteractionId.DRINK || id == InteractionId.READ

    private fun toward0(v: Float, d: Float) = if (v > 0f) maxOf(0f, v - d) else minOf(0f, v + d)

    private fun refresh(i: Int) {
        val m = minds[i]
        val now = clock.simHours
        m.moodlets.removeAll { it.until <= now }
        m.score = Moods.score(m.needs, m.moodlets, now)
        val mood = Moods.mood(m.needs, m.moodlets, now, m.score)
        var changed = false
        if (mood != m.mood) { m.mood = mood; changed = true; m.nextThought = minOf(m.nextThought, realTime) }
        val wish = wishOf(i)
        if (wish != m.wish) { m.wish = wish; changed = true }
        for (k in 0 until Need.COUNT) {
            val q = (m.needs[k] * 20f).toInt()
            if (q != m.shown[k]) { m.shown[k] = q; changed = true }
        }
        if (realTime >= m.nextThought) { think(i); changed = true }
        if (changed) version++
    }

    private fun think(i: Int) {
        val m = minds[i]
        val pressing = Moods.pressing(m.needs)
        val complaint = m.complaint
        val doing = m.doing
        val thought: LifeThought? = when {
            complaint != null -> LifeThought("thought_missing", LifeLines.missing(complaint))
            pressing != null && !(doing != null && doing.gains[pressing.ordinal] > 0f) ->
                LifeLines.pick(LifeLines.needThoughts(pressing), random)?.let { LifeThought(pressing.icon, it) }
            doing != null && LifeLines.doing(doing) != null -> LifeThought("thought_" + doing.name.lowercase(), LifeLines.doing(doing)!!)
            random.nextFloat() < 0.6f -> LifeLines.pick(LifeLines.moodThoughts(m.mood), random)?.let { line ->
                if ("{you}" in line) crush(i)?.let { LifeThought(m.mood.icon, LifeLines.fill(line, people[i].first, people[it].first)) }
                else LifeThought(m.mood.icon, line)
            }
            else -> null
        }
        m.thought = thought
        m.thoughtUntil = realTime + if (pressing != null) THOUGHT_SHOWN * 1.6f else THOUGHT_SHOWN
        m.nextThought = realTime + THOUGHT_GAP_MIN + random.nextFloat() * (THOUGHT_GAP_MAX - THOUGHT_GAP_MIN)
    }

    private fun thoughtOf(i: Int): LifeThought? = minds[i].thought?.takeIf { realTime < minds[i].thoughtUntil }

    /** The one [i] fancies most (romance at least Acquaintance), or null. */
    private fun crush(i: Int): Int? {
        var best = -1; var v = RomanceTier.ACQUAINTANCE.min
        for (j in 0 until size) if (j != i) { val r = relationship(i, j).romance; if (r >= v) { v = r; best = j } }
        return best.takeIf { it >= 0 }
    }

    /** The friend [i] likes best (Friendly at least), or null. */
    private fun friend(i: Int): Int? {
        var best = -1; var v = FriendTier.FRIENDLY.min
        for (j in 0 until size) if (j != i) { val f = relationship(i, j).friendship; if (f >= v) { v = f; best = j } }
        return best.takeIf { it >= 0 }
    }

    private fun wishOf(i: Int): String? {
        val m = minds[i]
        var low = 0.5f
        var need: Need? = null
        for (k in 0 until Need.COUNT) if (m.needs[k] < low) { low = m.needs[k]; need = Need.entries[k] }
        val t = m.traits
        if (need != null) return when (need) {
            Need.ENERGY -> "wants a good night's sleep"
            Need.HUNGER -> if (Trait.GLUTTON in t) "wants a feast" else "wants a hot meal"
            Need.THIRST -> if (people[i].race == 3 || people[i].race == 34) "wants an ale" else "wants a cold drink"
            Need.SOCIAL -> when {
                Trait.BOOKISH in t -> "wants to hear a story"
                friend(i) == null -> "wants a friend"
                else -> "wants to catch up with ${people[friend(i)!!].first}"
            }
            Need.FUN -> when {
                Trait.PLAYFUL in t -> "wants to dance"
                Trait.BOOKISH in t -> "wants a good book"
                Trait.MISCHIEVOUS in t -> "wants to play a prank"
                else -> "wants some fun"
            }
            Need.HYGIENE -> "wants a wash"
            Need.COMFORT -> "wants a soft seat"
        }
        return when {
            Trait.ROMANTIC in t && crush(i) == null -> "wants a sweetheart"
            Trait.ROMANTIC in t -> "wants time with ${people[crush(i)!!].first}"
            Trait.GREGARIOUS in t && friend(i) == null -> "wants a friend"
            Trait.BOOKISH in t -> "wants to read"
            Trait.PLAYFUL in t -> "wants to dance"
            Trait.GLUTTON in t -> "wants a feast"
            Trait.BRAVE in t -> "wants an adventure"
            else -> "wants to take it easy"
        }
    }

    /**
     * Cuts short what [i] does by itself once its need is met, or when another need is pressing (never a player's order),
     * with hysteresis so two low needs never take turns cutting each other off: an activity runs [MIN_RUN_SECONDS] at least;
     * another need cuts it only when it is urgent ([URGENT]) and lower than the one being filled by [URGENT_MARGIN]; one
     * begun on a critical need ([CRITICAL]) runs until that need is [MOSTLY_FILLED]. A sleep runs to the morning (or full
     * energy by day) and is broken only by hunger or thirst gone urgent after half a night. A conversation by themselves is
     * left politely ([LifeWorld.excuse]) when a need other than company turns urgent.
     */
    private fun interrupt(i: Int, world: LifeWorld) {
        val m = minds[i]
        val doing = m.doing ?: return
        if (m.moving) return
        val run = realTime - m.startedReal
        if (doing.social) {
            if (!m.directed && run >= MIN_RUN_SECONDS) {
                val k = lowest(m.needs, doing)
                if (k >= 0 && m.needs[k] < URGENT) world.excuse(i, LifeLines.excuse(Need.entries[k], random))
            }
            return
        }
        if (m.directed) return
        val needs = m.needs
        val primary = primary(doing) ?: return
        val done = when (doing) {
            InteractionId.SLEEP, InteractionId.NAP -> sleepDone(m, run)
            InteractionId.WANDER -> false
            else -> {
                val cap = minOf(doing.cap, 1f)
                // filled (an idle filler begun full runs its time)
                val full = needs[primary] >= cap - 0.02f && m.startNeed < cap - 0.05f
                full || (run >= MIN_RUN_SECONDS && pressed(m, primary, doing))
            }
        }
        if (done) world.finish(i)
    }

    /** Whether a sleep (or nap) is over: rested and not the night, or woken by hunger or thirst after half a night. */
    private fun sleepDone(m: Mind, run: Float): Boolean {
        val needs = m.needs
        val e = needs[Need.ENERGY.ordinal]
        val night = sleepHour(m.traits, clock.hourOfDay)
        if (e >= 0.97f && !night) return true
        if (run < MIN_RUN_SECONDS) return false
        val food = minOf(needs[Need.HUNGER.ordinal], needs[Need.THIRST.ordinal])
        if (food < URGENT && e >= WAKE_FOR_FOOD) return true
        return !night && e >= 0.7f && food < 0.12f
    }

    /** Whether another need presses on [doing] (filling [primary]) enough to cut it short (see [interrupt]). */
    private fun pressed(m: Mind, primary: Int, doing: InteractionId): Boolean {
        val k = lowest(m.needs, doing, primary)
        if (k < 0) return false
        val other = m.needs[k]
        val p = m.needs[primary]
        if (other >= URGENT || other > p - URGENT_MARGIN) return false
        return !(m.startNeed < CRITICAL && p < MOSTLY_FILLED * minOf(doing.cap, 1f))
    }

    /** The lowest need [doing] doesn't fill (not [except]), or -1. */
    private fun lowest(needs: FloatArray, doing: InteractionId, except: Int = -1): Int {
        var best = -1
        for (k in 0 until Need.COUNT) if (k != except && doing.gains[k] <= 0f && (best < 0 || needs[k] < needs[best])) best = k
        return best
    }

    /**
     * The need to see to first when one is critical (under [CRITICAL]), or -1: the lowest, a quick one (a meal, a drink)
     * before a night's sleep when they are about as low, so nothing sits empty while the long one runs.
     */
    private fun critical(m: Mind): Int {
        var best = -1
        var low = Float.MAX_VALUE
        for (k in 0 until Need.COUNT) {
            if (m.needs[k] >= CRITICAL) continue
            val v = m.needs[k] + if (k == Need.ENERGY.ordinal) SLOW_BIAS else 0f
            if (v < low) { low = v; best = k }
        }
        return best
    }

    /** The need [id] is for: sleep's is energy (though a bed is comfortable too), else the one it fills most. */
    private fun primary(id: InteractionId): Int? {
        if (id == InteractionId.SLEEP || id == InteractionId.NAP || id == InteractionId.REST) return Need.ENERGY.ordinal
        var best = -1; var g = 0f
        val gains = id.gains
        for (k in gains.indices) if (gains[k] > g) { g = gains[k]; best = k }
        return best.takeIf { it >= 0 }
    }

    /** Whether [hour] is when someone with [traits] sleeps: 22 to 6, an early bird 21 to 5, a night owl 1 to 9. */
    fun sleepHour(traits: List<Trait>, hour: Double): Boolean {
        val (from, to) = sleepHours(traits)
        return if (from < to) hour >= from && hour < to else hour >= from || hour < to
    }

    private fun sleepHours(traits: List<Trait>): Pair<Double, Double> = when {
        Trait.EARLY_BIRD in traits -> 21.0 to 5.0
        Trait.NIGHT_OWL in traits -> 1.0 to 9.0
        else -> 22.0 to 6.0
    }

    /** Game hours from now to the end of [traits]' sleeping hours (their morning). */
    private fun untilMorning(traits: List<Trait>): Double {
        val wake = sleepHours(traits).second
        val h = clock.hourOfDay
        return if (h <= wake) wake - h else 24.0 - h + wake
    }

    // ---- choosing: the utility AI

    private class Place(val provider: LifeProvider, val item: SceneObject?, val prop: LifeProp?, val distance: Float, val bonus: Float)

    /**
     * What [i] does next by itself, or null (life is off). Scores every interaction that can happen now and takes the best;
     * remembers it. When the best thing it wants can't be had here (no bed free), it says so in a thought and does the next
     * best, never a fake of it.
     */
    fun decide(i: Int, world: LifeWorld): LifePlan? {
        if (!enabled || i !in 0 until size) return null
        decisions++
        val m = minds[i]
        m.complaint = null
        var best: LifePlan? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var wanted: InteractionId? = null
        var wantedScore = 0f
        val x = world.x(i); val y = world.y(i)
        // a critical need first: what fills it outranks the rest, and company waits
        val crit = critical(m)
        for (id in AUTONOMOUS_SOLO) {
            if (sated(m, id)) continue
            val value = value(m, id) + timeBonus(m, id)
            if (value <= 0f && BASELINE[id.ordinal] <= 0f) continue
            val place = place(i, id, world, x, y)
            if (place == null) {
                if (value > wantedScore) { wanted = id; wantedScore = value }
                continue
            }
            var s = maxOf(value + BASELINE[id.ordinal], 0.001f) * likes(m, id) * place.bonus * distance(place.distance) * novelty(m, id, -1) * noise()
            if (crit >= 0 && primary(id) == crit) s *= CRITICAL_BOOST
            if (s > bestScore) { bestScore = s; best = solo(i, id, place) }
        }
        val company = crit < 0 || crit == Need.SOCIAL.ordinal || crit == Need.FUN.ordinal
        if (company && size > 1 && (world.chatterOn || world.pairsOn)) {
            for (j in 0 until size) {
                if (j == i || !world.approachable(j)) continue
                val d = hypot(world.x(j) - x, world.y(j) - y)
                if (d > SOCIAL_RANGE) continue
                val r = relationship(i, j)
                for (id in AUTONOMOUS_SOCIAL) {
                    if (!permitted(id, world)) continue
                    if (!RelationshipRules.needs(id, r) || !wants(i, j, id, r)) continue
                    // the Personality tab's boundaries: a stage neither starts unless both allow it and the other takes being started
                    if (!LifePreferences.wouldStart(prefs[i], id) || !LifePreferences.mayStart(prefs[j], id)) continue
                    val s = (value(m, id) + social(i, j, id, r)) * likes(m, id) * LifePreferences.paceFactor(prefs[i], id) *
                        distance(d) * novelty(m, id, j) * noise()
                    if (s > bestScore) { bestScore = s; best = socialPlan(i, j, id, world, byNpc = true) }
                }
            }
        }
        if (wanted != null && wantedScore > MISSING_AT && (best == null || wantedScore > bestScore)) {
            m.complaint = wanted
            m.nextThought = realTime
        }
        best?.let { remember(m, it.id, it.other) }
        return best ?: LifePlan(InteractionId.WANDER, LifeProvider.FLOOR)
    }

    /** Whether [m] has no call for [id] now: no meal when not hungry, no drink when not thirsty, no wash when clean. */
    private fun sated(m: Mind, id: InteractionId): Boolean = when (id) {
        InteractionId.EAT -> m.needs[Need.HUNGER.ordinal] > EAT_BELOW
        InteractionId.DRINK -> m.needs[Need.THIRST.ordinal] > EAT_BELOW
        InteractionId.WASH, InteractionId.TIDY -> m.needs[Need.HYGIENE.ordinal] > WASH_BELOW
        else -> false
    }

    private fun permitted(id: InteractionId, world: LifeWorld): Boolean = when (id) {
        InteractionId.HUG, InteractionId.KISS, InteractionId.DANCE_TOGETHER, InteractionId.PLAY_TOGETHER -> world.pairsOn
        else -> world.chatterOn
    }

    /** What [id] is worth to [m] for its needs: urgency x advertised gain, summed. */
    private fun value(m: Mind, id: InteractionId): Float {
        var v = 0f
        val gains = id.gains
        for (k in 0 until Need.COUNT) {
            val g = gains[k]
            if (g == 0f) continue
            val need = m.needs[k]
            val delta = if (g > 0f) minOf(g * id.hours, maxOf(0f, (if (k == primary(id)) id.cap else 1f) - need)) else g * id.hours
            v += urgency(need) * delta
        }
        return v
    }

    /**
     * How pressing a need at [v] is: nothing while it's three quarters full (nobody eats who isn't hungry: three meals a day,
     * not six), then rising steeply as it empties.
     */
    private fun urgency(v: Float): Float {
        val e = maxOf(0f, SATISFIED - v) / SATISFIED
        return e * e * 1.8f + if (v < 0.3f) (0.3f - v) * 3f else 0f
    }

    /** The hour's say: sleep at night (and only at night unless worn out); a meal near mealtimes. */
    private fun timeBonus(m: Mind, id: InteractionId): Float {
        val hour = clock.hourOfDay
        val energy = m.needs[Need.ENERGY.ordinal]
        // up rested in the night (a light sleeper, a sleep cut short by the clock): back to bed only once tired again
        val bedtime = if (m.wokeRested && clock.simHours - m.lastSleepEnd < RESLEEP_HOURS) RESLEEP_BELOW else SLEEP_BELOW
        return when (id) {
            InteractionId.SLEEP -> when {
                sleepHour(m.traits, hour) && energy < bedtime -> 0.6f * (1.1f - energy)
                energy < 0.25f -> 0.2f
                else -> -value(m, id) * 0.75f
            }
            // the couch is a bed for the night when the bed's taken (less good)
            InteractionId.NAP -> when {
                sleepHour(m.traits, hour) && energy < bedtime -> 0.4f * (1.1f - energy)
                energy > 0.45f -> -value(m, id) * 0.8f
                else -> 0f
            }
            // a meal near mealtimes (only for the hungry: [sated])
            InteractionId.EAT -> if (hour in 7.0..9.0 || hour in 12.0..14.0 || hour in 18.0..20.0) 0.05f else 0f
            InteractionId.DANCE -> if (sleepHour(m.traits, hour)) -value(m, id) * 0.7f else 0f
            else -> 0f
        }
    }

    private fun likes(m: Mind, id: InteractionId): Float {
        var f = 1f
        for (t in m.traits) f *= t.likes(id)
        return f
    }

    private fun distance(d: Float) = 1f / (1f + d / DISTANCE_YARDS)

    private fun novelty(m: Mind, id: InteractionId, other: Int): Float {
        var f = 1f
        for (r in m.recent) {
            if (r / 1000 == id.ordinal) f *= if (id == InteractionId.WANDER) 0.85f else 0.6f
            if (other >= 0 && r % 1000 == other + 1) f *= 0.85f
        }
        return f
    }

    private fun noise() = 1f - NOISE + random.nextFloat() * NOISE * 2f

    private fun remember(m: Mind, id: InteractionId, other: Int) {
        m.recent.addLast(id.ordinal * 1000 + other + 1)
        while (m.recent.size > RECENT) m.recent.removeFirst()
    }

    /** Where [i] would do [id] now (the best free place for it), or null when nowhere here. */
    private fun place(i: Int, id: InteractionId, world: LifeWorld, x: Float, y: Float): Place? {
        var best: Place? = null
        for (p in id.providers) {
            val at: Place? = when (p) {
                LifeProvider.FLOOR -> Place(p, null, null, 0f, when (id) {
                    InteractionId.EAT, InteractionId.DRINK -> 0.75f
                    // dozing sat on the floor is the last resort of the tired: any seat or bed first
                    InteractionId.NAP -> 0.35f
                    else -> 1f
                })
                LifeProvider.BED, LifeProvider.COUCH, LifeProvider.CHAIR, LifeProvider.TABLE -> {
                    val item = when (p) {
                        LifeProvider.BED -> SceneObject.BED
                        LifeProvider.COUCH -> SceneObject.COUCH
                        LifeProvider.CHAIR -> SceneObject.CHAIR
                        else -> SceneObject.TABLE
                    }
                    world.freeSpot(item, i)?.let { spot ->
                        // an inn meal at the table beats a bite from the pack; a nap in a chair is a poor one
                        val bonus = when {
                            p == LifeProvider.TABLE && (id == InteractionId.EAT || id == InteractionId.DRINK) -> 1.25f
                            p == LifeProvider.CHAIR && id == InteractionId.NAP -> 0.6f
                            else -> 1f
                        }
                        Place(p, item, null, hypot(spot.x - x, spot.y - y), bonus)
                    }
                }
                LifeProvider.FIREPLACE, LifeProvider.BASIN, LifeProvider.BOOKSHELF -> {
                    val kind = when (p) { LifeProvider.FIREPLACE -> LifePropKind.FIREPLACE; LifeProvider.BASIN -> LifePropKind.BASIN; else -> LifePropKind.BOOKSHELF }
                    var found: Place? = null
                    for ((k, prop) in props.withIndex()) {
                        if (prop.kind != kind || (propHolder[k] >= 0 && propHolder[k] != i && minds[propHolder[k]].doing == id)) continue
                        val d = hypot(prop.entry.x - x, prop.entry.y - y)
                        if (found == null || d < found.distance) found = Place(p, null, prop, d, 1f)
                    }
                    found
                }
                LifeProvider.PERSON -> null
            }
            if (at != null && (best == null || at.bonus * distance(at.distance) > best.bonus * distance(best.distance))) best = at
        }
        return best
    }

    private fun solo(i: Int, id: InteractionId, place: Place): LifePlan {
        val m = minds[i]
        val p = primary(id)
        val hours = if (p == null) id.hours.toDouble() else {
            val gain = id.gains[p]
            val fill = ((minOf(id.cap, 1f) - m.needs[p]).coerceAtLeast(0.1f) / gain).toDouble()
            val night = sleepHour(m.traits, clock.hourOfDay)
            // a night's sleep runs to the morning (a night on the couch too, not a nap)
            if (id == InteractionId.SLEEP || (id == InteractionId.NAP && night)) maxOf(fill + 0.5, if (night) untilMorning(m.traits) else 0.0).coerceIn(1.0, 10.0)
            else fill.coerceIn(id.hours * 0.5, id.hours * 1.6)
        }
        val seconds = clock.realSeconds(hours).coerceIn(MIN_HOLD, MAX_HOLD)
        val cycles = if (id.anim >= 0 && id.cycle > 0f) ceil(seconds / id.cycle).toInt().coerceIn(1, 100) else 1
        place.prop?.let { prop -> val k = props.indexOf(prop); if (k >= 0) propHolder[k] = i }
        return LifePlan(id, place.provider, place.item, place.prop, holdSeconds = seconds, cycles = cycles)
    }

    /** Whether [i] would go for [id] with [j] at all (beyond the standing it needs: [RelationshipRules.needs]). */
    private fun wants(i: Int, j: Int, id: InteractionId, r: Relationship): Boolean {
        val m = minds[i]
        return when (id) {
            InteractionId.ARGUE -> Trait.CALM !in m.traits && (m.mood == Mood.IRRITABLE || m.mood == Mood.SAD || r.friendship < -10f)
            InteractionId.FLIRT, InteractionId.KISS -> {
                // one sweetheart at a time; a stranger is flirted with only by a romantic
                val other = sweetheart(i)
                (other == null || other == j) && compatibility(i, j) > -0.2f &&
                    (Trait.ROMANTIC in m.traits || r.friendship >= FriendTier.HONORED.min || r.romance >= RomanceTier.ACQUAINTANCE.min)
            }
            InteractionId.INVITE_SIT -> false // offered to the player; NPCs simply sit near each other
            else -> r.friendship >= RelationshipRules.SHUNNED || id == InteractionId.CHAT
        }
    }

    /** [i]'s sweetheart (romance at Sweethearts or more), or null. */
    private fun sweetheart(i: Int): Int? {
        for (j in 0 until size) if (j != i && relationship(i, j).romance >= RomanceTier.SWEETHEARTS.min) return j
        return null
    }

    /** What a social [id] with [j] adds besides needs: liking the person, and the steady pull of wanting company. */
    private fun social(i: Int, j: Int, id: InteractionId, r: Relationship): Float {
        val base = when (id) {
            InteractionId.CHAT -> 0.06f
            InteractionId.JOKE -> 0.04f
            InteractionId.COMPLIMENT -> 0.025f
            InteractionId.FLIRT -> 0.03f + r.romance / 1500f
            InteractionId.KISS -> 0.03f + r.romance / 1200f
            InteractionId.HUG -> 0.025f + r.friendship / 2500f
            InteractionId.DANCE_TOGETHER -> 0.03f
            InteractionId.PLAY_TOGETHER -> 0.02f + r.friendship / 3000f
            InteractionId.GIVE_GIFT -> 0.01f
            InteractionId.ARGUE -> 0.05f
            else -> 0f
        }
        return base * (1f + compatibility(i, j))
    }

    private fun compatibility(i: Int, j: Int) = Traits.compatibility(minds[i].traits, minds[j].traits)

    /** The chance [j] says yes to [i]'s [id]; [byNpc]: [i] started it by themselves (j's Personality tab boundaries apply). */
    fun acceptance(j: Int, i: Int, id: InteractionId, byNpc: Boolean = false): Float {
        if (byNpc && LifePreferences.acceptanceFactor(prefs[j], id) == 0f) return 0f
        return acceptanceOf(j, i, id) * (if (byNpc) LifePreferences.acceptanceFactor(prefs[j], id) else 1f)
    }

    private fun acceptanceOf(j: Int, i: Int, id: InteractionId): Float {
        val r = relationship(i, j)
        val mj = minds[j]
        val compat = compatibility(i, j)
        var p = when (id) {
            InteractionId.ARGUE -> 1f
            InteractionId.CHAT, InteractionId.JOKE -> 0.85f + r.friendship / 300f + mj.score * 0.15f
            InteractionId.FLIRT, InteractionId.KISS -> {
                var q = 0.3f + r.romance / 90f + r.friendship / 300f + compat * 0.3f + mj.score * 0.2f
                if (Trait.ROMANTIC in mj.traits) q += 0.15f
                if (id == InteractionId.KISS && r.romance < RomanceTier.FLIRTING.min) q -= 0.35f
                val other = sweetheart(j)
                if (other != null && other != i) q *= 0.15f
                q
            }
            else -> 0.6f + r.friendship / 160f + compat * 0.3f + mj.score * 0.25f
        }
        if (id == InteractionId.ARGUE) return 1f
        if (Trait.LONER in mj.traits && id.social) p -= (mj.needs[Need.SOCIAL.ordinal] - 0.6f).coerceAtLeast(0f)
        if (mj.needs[Need.ENERGY.ordinal] < 0.12f) p *= 0.4f
        // busy with something they enjoy: a little less willing to stop
        val busy = mj.doing
        if (busy != null && !busy.social && busy != InteractionId.WANDER) p *= 0.85f
        if (r.friendship < RelationshipRules.SHUNNED && id != InteractionId.CHAT) p *= 0.3f
        return p.coerceIn(0.03f, 0.98f)
    }

    private fun socialPlan(i: Int, j: Int, id: InteractionId, world: LifeWorld, byNpc: Boolean = false): LifePlan {
        val accepted = random.nextFloat() < acceptance(j, i, id, byNpc)
        val (ask, answer) = LifeLines.exchange(id, accepted, random)
        return LifePlan(id, LifeProvider.PERSON, other = j, accepted = accepted,
            ask = LifeLines.fill(ask, people[i].first, people[j].first), answer = LifeLines.fill(answer, people[j].first, people[i].first),
            holdSeconds = clock.realSeconds(id.hours.toDouble()).coerceIn(MIN_HOLD, MAX_HOLD))
    }

    // ---- the player's interactions (the radial menu)

    /**
     * The player's choice: [a] does [id] (alone, or with [b]). Solo: the best place for it here (null and a message when
     * there's none: no basin in this room). Social: [b] accepts or refuses by their standing and mood (the message says
     * which). The plan is the scene's to carry out as the player's order.
     */
    fun request(a: Int, b: Int, id: InteractionId, world: LifeWorld): Pair<LifePlan?, LifeResult> {
        val name = people[a].first
        if (!id.social) {
            val resolved = if (id == InteractionId.REST) {
                if (world.freeSpot(SceneObject.BED, a) != null) InteractionId.SLEEP
                else if (world.freeSpot(SceneObject.COUCH, a) != null) InteractionId.NAP else InteractionId.RELAX
            } else id
            val place = place(a, resolved, world, world.x(a), world.y(a))
                ?: return null to answer(false, "$name can't ${id.label.lowercase()} here: ${LifeLines.missing(resolved).lowercase()}")
            remember(minds[a], resolved, -1)
            return solo(a, resolved, place) to answer(true, "$name: ${resolved.label}")
        }
        if (b < 0 || b == a) return null to answer(false, "Choose someone for ${id.label}.")
        val plan = socialPlan(a, b, id, world)
        remember(minds[a], id, b)
        val other = people[b].first
        return plan to if (plan.accepted) answer(true, "$other accepted: ${id.label}.")
        else answer(false, "$other refused $name's ${id.label.lowercase()}.")
    }

    private fun answer(ok: Boolean, text: String) = LifeResult(ok, text).also { lastResult = it; version++ }

    /** The player's menu for [a] (and [b]): each interaction and whether it can be had here (a social one may still be refused). */
    fun menu(a: Int, b: Int, world: LifeWorld): List<Pair<InteractionId, Boolean>> =
        if (b < 0) InteractionId.SOLO_MENU.map { id ->
            id to (id == InteractionId.REST && (world.freeSpot(SceneObject.BED, a) != null || world.freeSpot(SceneObject.COUCH, a) != null ||
                world.freeSpot(SceneObject.CHAIR, a) != null) || id != InteractionId.REST && place(a, id, world, world.x(a), world.y(a)) != null)
        } else InteractionId.SOCIAL_MENU.map { it to (b != a) }

    // ---- what came of it

    /**
     * A conversation ran its course: [a] began it with [b], it was [kind] (refused: [refused]) on [topic] ([Chatter]'s; null
     * for a spoken exchange). Social needs were filled while it ran; the relationship moves by what it was, whether the topic
     * suited them both and how they both felt.
     */
    fun talkEnded(a: Int, b: Int, kind: InteractionId, refused: Boolean, topic: String?) {
        if (a !in 0 until size || b !in 0 until size || a == b) return
        val r = relationship(a, b)
        r.apartHours = 0.0
        if (refused) {
            val (f, ro) = RelationshipRules.refusal(kind)
            change(a, b, f, ro)
            moodlet(a, "refused", Mood.SAD, -0.12f, 2.0, "Turned Down")
            event("${people[b].first} turned down ${people[a].first}'s ${kind.label.lowercase()}.", "event_refused")
            return
        }
        var (f, ro) = RelationshipRules.gain(kind)
        if (topic != null) {
            for (t in minds[a].traits) { if (topic in t.topics) f += 1f; if (topic in t.dislikes) f -= 1.5f }
            for (t in minds[b].traits) { if (topic in t.topics) f += 1f; if (topic in t.dislikes) f -= 1.5f }
        }
        val tone = (minds[a].score + minds[b].score) * 0.5f
        if (kind != InteractionId.ARGUE) {
            f += tone * 2f
            if (f > 0f) f *= 1f + compatibility(a, b)
        }
        change(a, b, f, ro)
        when (kind) {
            InteractionId.JOKE -> { moodlet(a, "joke", Mood.PLAYFUL, 0.1f, 1.5, "Shared a Laugh"); moodlet(b, "joke", Mood.PLAYFUL, 0.1f, 1.5, "Shared a Laugh") }
            InteractionId.COMPLIMENT -> moodlet(b, "compliment", Mood.HAPPY, 0.12f, 3.0, "Complimented")
            InteractionId.FLIRT -> { moodlet(a, "flirt", Mood.FLIRTY, 0.15f, 2.0, "Flirty"); moodlet(b, "flirt", Mood.FLIRTY, 0.15f, 2.0, "Flirty") }
            InteractionId.GIVE_GIFT -> { moodlet(b, "gift", Mood.HAPPY, 0.18f, 6.0, "A Thoughtful Gift"); event("${people[a].first} gave ${people[b].first} a gift.", "event_gift") }
            InteractionId.ARGUE -> {
                moodlet(a, "argue", Mood.IRRITABLE, -0.2f, 3.0, "Had an Argument")
                moodlet(b, "argue", Mood.IRRITABLE, -0.2f, 3.0, "Had an Argument")
                event("${people[a].first} and ${people[b].first} argued.", "event_argue")
            }
            else -> {}
        }
    }

    /** [a] and [b] finished a two-person animation of [kind] (a hug, a dance, a kiss; the player's own: [InteractionId.PLAY_TOGETHER]). */
    fun together(a: Int, b: Int, kind: InteractionId) {
        if (a !in 0 until size || b !in 0 until size || a == b) return
        val (f, ro) = RelationshipRules.gain(if (kind.social) kind else InteractionId.PLAY_TOGETHER)
        val tone = (minds[a].score + minds[b].score) * 0.5f
        change(a, b, (f + tone) * (1f + compatibility(a, b)).coerceAtLeast(0.5f), ro)
        relationship(a, b).apartHours = 0.0
        if (kind == InteractionId.KISS) { moodlet(a, "kiss", Mood.FLIRTY, 0.2f, 3.0, "Smitten"); moodlet(b, "kiss", Mood.FLIRTY, 0.2f, 3.0, "Smitten") }
        if (kind == InteractionId.DANCE_TOGETHER) { moodlet(a, "dance", Mood.PLAYFUL, 0.14f, 2.0, "Danced Together"); moodlet(b, "dance", Mood.PLAYFUL, 0.14f, 2.0, "Danced Together") }
    }

    /** A short exchange of greetings for [a] and [b] when the scene has no small talk ([Chatter]) of its own. */
    fun smallTalk(a: Int, b: Int): List<ChatLine> {
        val (ask, answer) = LifeLines.exchange(InteractionId.CHAT, true, random)
        return listOf(ChatLine(0, LifeLines.fill(ask, people[a].first, people[b].first), Gestures.WAVE),
            ChatLine(1, LifeLines.fill(answer, people[b].first, people[a].first), Gestures.TALK))
    }

    /** Whether, a conversation done, [a] and [b] go into something together by themselves (good friends, now and then). */
    fun followUp(a: Int, b: Int): Boolean {
        val r = relationship(a, b)
        return r.friendship >= FriendTier.FRIENDLY.min && random.nextFloat() < FOLLOW_UP
    }

    private fun change(a: Int, b: Int, friendship: Float, romance: Float) {
        val r = relationship(a, b)
        val ft = r.friendTier
        val rt = r.romanceTier
        r.friendship = (r.friendship + friendship).coerceIn(-100f, 100f)
        r.romance = (r.romance + romance).coerceIn(-100f, 100f)
        val names = "${people[r.a].first} and ${people[r.b].first}"
        val nf = r.friendTier
        val nr = r.romanceTier
        if (nf != ft) {
            if (nf.ordinal > ft.ordinal && nf.ordinal >= FriendTier.FRIENDLY.ordinal) {
                event(if (nf == FriendTier.FRIENDLY) "$names became friends." else "$names are now ${nf.label}.", "event_friends")
            } else if (nf.ordinal < ft.ordinal && nf.ordinal <= FriendTier.UNFRIENDLY.ordinal) {
                event("$names had a falling out (${nf.label}).", "event_falling_out")
            }
        }
        if (nr != rt && nr.ordinal > rt.ordinal && nr.ordinal >= RomanceTier.FLIRTING.ordinal) event("$names are now ${nr.label}.", "event_romance")
        version++
    }

    private fun moodlet(i: Int, key: String, mood: Mood, value: Float, hours: Double, label: String) {
        val m = minds[i]
        m.moodlets.removeAll { it.key == key }
        m.moodlets += Moodlet(key, mood, value, clock.simHours + hours, label)
        while (m.moodlets.size > MAX_MOODLETS) m.moodlets.removeAt(0)
    }

    private fun event(text: String, icon: String) {
        events.addLast(LifeEvent(clock.simHours, text, icon))
        while (events.size > MAX_EVENTS) events.removeFirst()
        version++
    }

    // ---- the screen's view

    /** Everything the screen shows of life now (allocates: on change only). */
    fun view(): LifeView = LifeView(
        enabled, clock.speed, clock.day, clock.hourOfDay,
        List(size) { i ->
            val m = minds[i]
            LifePersonView(ids[i], m.needs.toList(), m.mood, (m.score * 100f).toInt(), thoughtOf(i), m.traits, m.wish,
                (0 until size).filter { it != i }.map { j ->
                    val r = relationship(i, j)
                    RelationshipView(ids[j], r.friendship.toInt(), r.romance.toInt(), r.friendTier.label, r.romanceTier.label)
                }, m.frozen, m.doing, m.moodlets.map { it.label })
        },
        events.toList().asReversed(), lastResult,
    )

    // ---- saving

    /** The whole life (clock, speed, each one's needs, traits, freeze and moodlets, the relationships, the feed) as JSON. */
    fun toJson(): String {
        val o = JSONObject()
        o.put("version", 1)
        o.put("hours", clock.simHours)
        o.put("speed", clock.speed.name)
        o.put("enabled", enabled)
        val ps = JSONObject()
        for (i in 0 until size) {
            val m = minds[i]
            val p = JSONObject()
            p.put("needs", JSONArray().apply { for (v in m.needs) put(v.toDouble()) })
            p.put("traits", JSONArray().apply { for (t in m.traits) put(t.name) })
            p.put("frozen", m.frozen)
            p.put("moodlets", JSONArray().apply {
                for (ml in m.moodlets) put(JSONObject().put("key", ml.key).put("mood", ml.mood.name).put("value", ml.value.toDouble())
                    .put("until", ml.until).put("label", ml.label))
            })
            ps.put(ids[i], p)
        }
        o.put("people", ps)
        o.put("relationships", JSONArray().apply {
            for (r in rels) if (r != null && (r.friendship != 0f || r.romance != 0f)) put(JSONObject().put("a", ids[r.a]).put("b", ids[r.b])
                .put("friendship", r.friendship.toDouble()).put("romance", r.romance.toDouble()).put("apart", r.apartHours))
        })
        o.put("events", JSONArray().apply {
            for (e in events) put(JSONObject().put("hours", e.hours).put("text", e.text).put("icon", e.icon))
        })
        return o.toString()
    }

    /** Takes back a saved life ([toJson]); people not in it keep theirs, ones not in this cast are passed over. False: unreadable. */
    fun load(json: String): Boolean {
        val o = try { JSONObject(json) } catch (e: Exception) { return false }
        clock.restore(o.optDouble("hours", clock.simHours))
        clock.speed = LifeSpeed.of(o.optString("speed"))
        enabled = o.optBoolean("enabled", true)
        o.optJSONObject("people")?.let { ps ->
            for (i in 0 until size) {
                val p = ps.optJSONObject(ids[i]) ?: continue
                val m = minds[i]
                p.optJSONArray("needs")?.let { a -> for (k in 0 until minOf(a.length(), Need.COUNT)) m.needs[k] = a.optDouble(k, 0.7).toFloat().coerceIn(0f, 1f) }
                p.optJSONArray("traits")?.let { a ->
                    val t = (0 until a.length()).mapNotNull { k -> Trait.entries.firstOrNull { it.name == a.optString(k) } }
                    if (t.isNotEmpty()) m.retrait(t)
                }
                m.frozen = p.optBoolean("frozen", false)
                m.moodlets.clear()
                p.optJSONArray("moodlets")?.let { a ->
                    for (k in 0 until a.length()) {
                        val ml = a.optJSONObject(k) ?: continue
                        val mood = Mood.entries.firstOrNull { it.name == ml.optString("mood") } ?: continue
                        m.moodlets += Moodlet(ml.optString("key"), mood, ml.optDouble("value").toFloat(), ml.optDouble("until"), ml.optString("label"))
                    }
                }
            }
        }
        o.optJSONArray("relationships")?.let { a ->
            for (k in 0 until a.length()) {
                val r = a.optJSONObject(k) ?: continue
                val i = indexOf[r.optString("a")] ?: continue
                val j = indexOf[r.optString("b")] ?: continue
                if (i == j) continue
                val rel = relationship(i, j)
                rel.friendship = r.optDouble("friendship", 0.0).toFloat().coerceIn(-100f, 100f)
                rel.romance = r.optDouble("romance", 0.0).toFloat().coerceIn(-100f, 100f)
                rel.apartHours = r.optDouble("apart", 0.0)
            }
        }
        o.optJSONArray("events")?.let { a ->
            events.clear()
            for (k in 0 until a.length()) {
                val e = a.optJSONObject(k) ?: continue
                events.addLast(LifeEvent(e.optDouble("hours"), e.optString("text"), e.optString("icon")))
            }
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        version++
        return true
    }

    companion object {
        /** The interactions an NPC weighs by itself, alone and with others. */
        val AUTONOMOUS_SOLO = listOf(InteractionId.SLEEP, InteractionId.NAP, InteractionId.RELAX, InteractionId.EAT, InteractionId.DRINK,
            InteractionId.READ, InteractionId.DANCE, InteractionId.WASH, InteractionId.TIDY, InteractionId.STRETCH, InteractionId.WARM,
            InteractionId.WANDER)
        val AUTONOMOUS_SOCIAL = listOf(InteractionId.CHAT, InteractionId.JOKE, InteractionId.COMPLIMENT, InteractionId.FLIRT,
            InteractionId.KISS, InteractionId.HUG, InteractionId.DANCE_TOGETHER, InteractionId.PLAY_TOGETHER, InteractionId.GIVE_GIFT,
            InteractionId.ARGUE)

        /** Each interaction's worth when no need asks for it (by ordinal): idle hands wander, stretch, chat. */
        private val BASELINE = FloatArray(InteractionId.entries.size).also {
            it[InteractionId.WANDER.ordinal] = 0.045f
            it[InteractionId.STRETCH.ordinal] = 0.012f
            it[InteractionId.DANCE.ordinal] = 0.01f
            it[InteractionId.READ.ordinal] = 0.01f
            it[InteractionId.RELAX.ordinal] = 0.01f
        }

        /** How fast each need falls asleep, against awake (energy fills instead; hunger and thirst slow; social and fun rest). */
        private val SLEEP_DECAY = floatArrayOf(0f, 0.5f, 0.5f, 0.1f, 0.1f, 1f, 0f)
        /** What walking costs a game hour on top. */
        private val WALK_COST = floatArrayOf(0.02f, 0f, 0.02f, 0f, 0f, 0f, 0.05f)

        /** How many choices each remembers to keep from doing the same thing over and over. */
        const val RECENT = 6
        /** A need over this asks for nothing. */
        const val SATISFIED = 0.75f
        /** A need under this cuts short whatever else is being done by oneself ([interrupt]: by [URGENT_MARGIN] under it). */
        const val URGENT = 0.08f
        /** Another need cuts an activity short only when this much lower than the need it fills. */
        const val URGENT_MARGIN = 0.15f
        /** A need under this is critical: seen to first ([CRITICAL_BOOST]), and what fills it runs to [MOSTLY_FILLED]. */
        const val CRITICAL = 0.2f
        const val CRITICAL_BOOST = 3f
        const val MOSTLY_FILLED = 0.7f
        /** Energy counts this much higher when picking the critical need: a quick meal or drink before a night's sleep. */
        const val SLOW_BIAS = 0.08f
        /** Real seconds an activity runs before another need may cut it short. */
        const val MIN_RUN_SECONDS = 4f
        /** A sleeper wakes for urgent hunger or thirst only with this much energy back. */
        const val WAKE_FOR_FOOD = 0.5f
        /** Night: to bed under this energy; woken rested less than [RESLEEP_HOURS] ago, only under [RESLEEP_BELOW]. */
        const val SLEEP_BELOW = 0.85f
        const val RESLEEP_BELOW = 0.55f
        const val RESLEEP_HOURS = 6.0
        /** No meal or drink over this; no wash over [WASH_BELOW]. */
        const val EAT_BELOW = 0.65f
        const val WASH_BELOW = 0.8f
        /** Game hours of needs a conversation costs at most, however long its lines are read. */
        const val TALK_COST_HOURS = 0.4
        /** A wanted thing that can't be had and scores over this is complained of. */
        const val MISSING_AT = 0.12f
        const val NOISE = 0.15f
        /** Distance halves an interaction's score this far away (yards). */
        const val DISTANCE_YARDS = 8f
        /** How far one goes to someone for a social interaction (yards). */
        const val SOCIAL_RANGE = 14f
        /** Within this people count as together (yards). */
        const val TOGETHER_YARDS = 3f
        const val PAIR_PASS_HOURS = 0.25
        /** Seconds between each one's mood and thought check. */
        const val CHECK_SECONDS = 1f
        /** How long a thought bubble stays up, and the gap before the next (real seconds). */
        const val THOUGHT_SHOWN = 7f
        const val THOUGHT_GAP_MIN = 12f
        const val THOUGHT_GAP_MAX = 26f
        /** Hold times on furniture and emotes (real seconds). */
        const val MIN_HOLD = 6f
        const val MAX_HOLD = 900f
        const val FOLLOW_UP = 0.25f
        const val MAX_MOODLETS = 6
        const val MAX_EVENTS = 40

        /** The file name the screen keeps a cast's life in (the same cast in any order: the same file). */
        fun fileName(ids: Collection<String>): String {
            val crc = java.util.zip.CRC32()
            crc.update(ids.distinct().sorted().joinToString("|").toByteArray(Charsets.UTF_8))
            return "life_%08x.json".format(crc.value)
        }
    }
}
