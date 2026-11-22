package com.logie.wifecraft.dialogue

import kotlin.math.max
import kotlin.random.Random

/** Where a spoken line goes: the scene's speech bubble ([com.logie.wifecraft.simulation.SceneSimulation.say]). False: it could not be shown. */
fun interface SpeechSink { fun say(npcId: String, text: String): Boolean }

/**
 * Decides when and who speaks, and what the model is asked. Driven by the scene each frame ([update] with a [SceneView]); the
 * model is asked for one line at a time ([ModelRunner]), the NPCs near the camera and the selected one first, pairs alternate
 * for a few turns on a topic, depth and pace follow their natures and the talkativeness setting. Sexual stages get live talk
 * (and, before one starts, the willingness flow: [ConsentCoordinator]); nothing is said in cinematic mode. Every line is the
 * model's: no engine, no speech. History is in memory and cleared with the scene ([reset]). Not thread-safe: call from the
 * scene's thread only.
 */
class DialogueDirector(
    /** The model, asked for each time (the runtime loads and unloads it: [DialogueRuntime.engine]); null: none. */
    private val engineOf: () -> DialogueEngine?,
    val lore: Lore,
    private val sink: SpeechSink,
    settings: DialogueSettings = DialogueSettings(),
    private val random: Random = Random.Default,
    /** The kink and JOI pack (null: none): its notes go into intimate and desire talk, and it plans an in-world JOI. */
    private val kink: KinkPack? = null,
) : DialogueHook {
    constructor(engine: DialogueEngine?, lore: Lore, sink: SpeechSink, settings: DialogueSettings = DialogueSettings(),
        random: Random = Random.Default, kink: KinkPack? = null) : this({ engine }, lore, sink, settings, random, kink)

    @Volatile var settings: DialogueSettings = settings
    val comfort = ComfortLedger()
    val steers = SteerBoard()
    private val runner = ModelRunner(engineOf)

    private var clock = 0.0
    private var generation = 0
    private var view: SceneView? = null
    private val systems = HashMap<String, String>()
    private val history = HashMap<String, ArrayDeque<DialogueTurn>>()
    private val lastSpoke = HashMap<String, Double>()
    private var nextSpeechAt = 0.0
    private var nullStreak = 0

    private class Conv(val a: String, val b: String, var turnsLeft: Int, var next: String, var heard: String?, val seed: String?)
    private val convs = HashMap<String, Conv>()
    private val pairCooldown = HashMap<String, Double>()
    private val liveTurn = HashMap<String, String>()

    // stage tracking
    private class Run(val a: String, val b: String, val started: Double, val name: String)
    private val runs = HashMap<String, Run>()
    private val events = ArrayList<Pair<Double, SceneEvent>>()
    private var known: Set<String> = emptySet()

    private val consent = ConsentCoordinator(runner, lore, comfort, { id, line -> deliver(id, line, TalkMode.WORLD, null, consentLine = true) },
        { id -> view?.person(id)?.profile }, { a, b -> view?.relation?.invoke(a, b) ?: Relation() }, { clock })

    override val active: Boolean get() = settings.enabled && engineOf()?.ready == true
    override var cinematic: Boolean = false
        private set

    override fun takesOverChat(a: String, b: String): Boolean = active

    override fun consent(initiator: String, target: String, directed: Boolean, stageName: String): ConsentGate {
        if (!active || cinematic) return ConsentGate.PROCEED
        return consent.ask(initiator, target, directed, stageName)
    }

    override fun declined(npcId: String, otherId: String, actName: String): Boolean =
        active && !cinematic && consent.boundary(npcId, otherId, actName)

    /** Hesitant NPCs waiting to be talked round (the persuasion chips). */
    fun hesitant(): List<HesitantView> = consent.hesitant()

    /** The player's persuasion try on a hesitant NPC. */
    fun persuade(npcId: String, approach: Approach): Boolean = consent.persuade(npcId, approach)

    /** A new scene: everything forgotten (no history, comfort, steers or sessions between scenes or runs). */
    fun reset() {
        engineOf()?.let { e -> seenProfiles.keys.forEach(e::forget); e.setFocus(null, emptySet()) }
        seenProfiles.clear(); focusSent = null; focusEngine = null
        generation++
        runner.clear(); consent.reset(); comfort.clear(); steers.clearAll()
        history.clear(); lastSpoke.clear(); convs.clear(); pairCooldown.clear(); liveTurn.clear(); jois.clear(); runs.clear(); events.clear()
        known = emptySet(); nextSpeechAt = 0.0; nullStreak = 0; systems.clear()
    }

    fun historyOf(id: String): List<DialogueTurn> = history[id]?.toList().orEmpty()

    /** What the screen shows of the layer (the persuasion chips, the steer chips); refreshed every [update], readable from any thread. */
    @Volatile var snapshot = DialogueSnapshot()
        private set

    /** One frame: [dt] seconds passed; [v] is the scene as it stands. */
    fun update(dt: Float, v: SceneView) {
        step(dt, v)
        val next = DialogueSnapshot(consent.hesitant(), steers.entries(), active)
        if (next != snapshot) snapshot = next
    }

    private fun step(dt: Float, v: SceneView) {
        clock += dt.coerceIn(0f, 1f)
        view = v
        cinematic = v.cinematic
        runner.pump(clock)
        track(v)
        syncEngine(v)
        if (!active) return
        consent.tick()
        runner.pump(clock)
        if (cinematic) { runner.cancel("speech"); return }
        speechTick(v)
    }

    // ---- what happened

    private fun track(v: SceneView) {
        val ids = v.people.map { it.profile.id }.toSet()
        if (known.isNotEmpty()) {
            for (id in ids - known) events += clock to SceneEvent(SceneEvent.Kind.ARRIVED, id)
            for (id in known - ids) events += clock to SceneEvent(SceneEvent.Kind.LEFT, id)
        }
        known = ids
        val now = HashSet<String>()
        for (p in v.people) {
            val o = p.withId?.let { v.person(it) } ?: continue
            if (!(p.inStage && p.sexualStage && o.inStage && o.sexualStage && o.withId == p.profile.id)) continue
            val k = pk(p.profile.id, o.profile.id)
            if (!now.add(k)) continue
            if (k !in runs) {
                runs[k] = Run(p.profile.id, o.profile.id, clock, p.stageName)
                events += clock to SceneEvent(SceneEvent.Kind.STAGE_STARTED, p.profile.id, o.profile.id, p.stageName.lowercase())
            }
        }
        for (k in runs.keys.toList()) if (k !in now) {
            val r = runs.remove(k)!!
            comfort.stage(r.a, r.b, (clock - r.started).toFloat())
            events += clock to SceneEvent(SceneEvent.Kind.STAGE_ENDED, r.a, r.b)
            liveTurn.remove(k); jois.remove(k)
        }
        events.removeAll { clock - it.first > EVENT_AGE }
        while (events.size > 8) events.removeAt(0)
    }

    // ---- what the runtime is told: who is in focus, and whose state to give up

    private var focusSent: Pair<String?, Set<String>>? = null
    private var focusEngine: DialogueEngine? = null
    private val seenProfiles = HashMap<String, NpcProfile>()

    /** The NPC the player has selected and the pair that is talking (a sexual stage's, else the selected one's, else a conversation's). */
    private fun focusOf(v: SceneView): Pair<String?, Set<String>> {
        val sel = v.people.firstOrNull { it.selected }
        fun pairOf(p: PersonView) = p.withId?.let { setOf(p.profile.id, it) }.orEmpty()
        val pair = sel?.takeIf { it.inStage || it.inConversation }?.let(::pairOf)
            ?: v.people.firstOrNull { it.inStage && it.sexualStage && it.withId != null }?.let(::pairOf)
            ?: v.people.firstOrNull { it.inConversation && it.withId != null }?.let(::pairOf)
            ?: emptySet()
        return sel?.profile?.id to pair
    }

    private fun syncEngine(v: SceneView) {
        val engine = engineOf()
        // an NPC whose persona changed, or who left: their state in the model is given up (the next request reads the whole prompt again)
        val ids = v.people.map { it.profile.id }.toSet()
        for (p in v.people) {
            val old = seenProfiles.put(p.profile.id, p.profile)
            if (old != null && old != p.profile) { systems.remove(p.profile.id); history.remove(p.profile.id); engine?.forget(p.profile.id) }
        }
        for (id in seenProfiles.keys.toList()) if (id !in ids) {
            seenProfiles.remove(id); systems.remove(id); history.remove(id); lastSpoke.remove(id); engine?.forget(id)
        }
        if (engine == null) { focusEngine = null; focusSent = null; return }
        val f = focusOf(v)
        if (engine !== focusEngine || f != focusSent) { focusEngine = engine; focusSent = f; engine.setFocus(f.first, f.second) }
    }

    private fun recent() = events.map { it.second }

    // ---- who speaks

    private fun pk(a: String, b: String) = if (a <= b) "$a|$b" else "$b|$a"

    private class Candidate(val speaker: PersonView, val other: PersonView?, val mode: TalkMode, val score: Float, val conv: Conv? = null)

    private fun speechTick(v: SceneView) {
        if (runner.count("speech") > 0 || clock < nextSpeechAt) return
        val talk = settings.talk.factor
        val best = ArrayList<Candidate>()
        val seenPairs = HashSet<String>()
        for (p in v.people) {
            val o = p.withId?.let { v.person(it) }
            val prox = (1f - p.cameraDistance / 20f).coerceIn(0f, 1f)
            val sel = if (p.selected || o?.selected == true) 2f else 0f
            when {
                // a sexual stage: they take turns
                o != null && p.inStage && p.sexualStage && o.inStage && o.sexualStage && o.withId == p.profile.id -> {
                    val k = pk(p.profile.id, o.profile.id)
                    if (!seenPairs.add(k)) continue
                    val turn = liveTurn[k] ?: p.profile.id
                    val who = if (turn == p.profile.id) p else o
                    val other = if (who === p) o else p
                    if (clock - (lastSpoke[who.profile.id] ?: -99.0) >= LIVE_GAP / talk)
                        best += Candidate(who, other, TalkMode.LIVE, 3f + sel + prox)
                }
                // an ordinary conversation: alternate for a few turns
                o != null && p.inConversation && o.inConversation && o.withId == p.profile.id && !p.inStage -> {
                    val k = pk(p.profile.id, o.profile.id)
                    if (!seenPairs.add(k)) continue
                    if ((pairCooldown[k] ?: 0.0) > clock) continue
                    val conv = convs.getOrPut(k) {
                        val lead = if (p.leads || !o.leads) p else o
                        Conv(p.profile.id, o.profile.id, turns(p, o), lead.profile.id, null, seedFor(lead.profile, o.profile, v))
                    }
                    if (conv.turnsLeft <= 0) { convs.remove(k); pairCooldown[k] = clock + PAIR_COOLDOWN / talk; continue }
                    val who = if (conv.next == p.profile.id) p else o
                    val other = if (who === p) o else p
                    best += Candidate(who, other, TalkMode.WORLD, 2f + sel + prox, conv)
                }
                // alone and idle: now and then a remark to the room
                o == null && !p.inStage && !p.inConversation && !p.moving ->
                    if (clock - (lastSpoke[p.profile.id] ?: -99.0) >= SOLO_GAP / talk && random.nextFloat() < 0.15f * talk)
                        best += Candidate(p, null, TalkMode.WORLD, 1f + sel + prox)
            }
        }
        val pick = best.maxByOrNull { it.score } ?: return
        ask(pick, v)
    }

    private fun turns(a: PersonView, b: PersonView): Int {
        val depth = (a.profile.disposition.curiosity + b.profile.disposition.curiosity + a.profile.disposition.warmth + b.profile.disposition.warmth) / 4f
        val base = if (depth > 0.2f) 5 else if (depth < -0.1f) 2 else 3
        return (base + random.nextInt(2)).coerceIn(2, 6)
    }

    private fun seedFor(me: NpcProfile, other: NpcProfile, v: SceneView): String? {
        val seeds = ArrayList<String>()
        seeds += "how your day has gone"; seeds += "the place you are in"; seeds += "what you think of ${other.first}"
        seeds += "something that happened to you on the road"; seeds += "the news from the wider world"
        v.people.filter { it.profile.id != me.id && it.profile.id != other.id }.randomOrNull(random)?.let { seeds += "${it.profile.first}, who is here" }
        v.place.props.randomOrNull(random)?.let { seeds += it }
        return seeds.random(random)
    }

    private fun ask(c: Candidate, v: SceneView) {
        val me = c.speaker.profile
        val other = c.other?.profile
        val rel = other?.let { v.relation(me.id, other.id) } ?: Relation()
        val band = other?.let { comfort.band(me.disposition, me.id, it.id, rel.friendship, rel.romance) } ?: ComfortBand.STRANGERS
        val desire = c.mode == TalkMode.LIVE || (other != null && band >= ComfortBand.FAMILIAR && random.nextFloat() < me.disposition.desireTalk * 0.5f)
        val system = systems.getOrPut(me.id) { PersonaPrompt.system(me, lore) }
        val user = TurnPrompt.build(lore, TurnPrompt.Input(
            me, v, c.mode, other, band, desire, steers.hints(me.id, other?.id), recent(),
            stageName = c.speaker.stageName, heard = c.conv?.heard, seed = c.conv?.seed?.takeIf { c.conv.heard == null },
            kink = kink, joiStage = joiStageFor(c, band),
        ))
        val req = DialogueRequest(me.id, system, history[me.id]?.toList().orEmpty(), user, maxTokens = 60, temperature = 0.85f)
        val gen = generation
        val prio = (c.score * 10).toInt()
        val urgency = when {
            c.speaker.selected || c.other?.selected == true || c.mode == TalkMode.LIVE -> DialoguePriority.SELECTED
            c.conv != null -> DialoguePriority.PAIR
            c.score >= 1.5f -> DialoguePriority.NEARBY
            else -> DialoguePriority.AMBIENT
        }
        runner.submit(prio, "speech", req, ttl = 15.0, urgency = urgency) { raw ->
            if (gen != generation) return@submit
            onAnswer(c, req, raw, retried = false, gen, prio)
        }
    }

    // an in-world JOI: one of the pair tells the other what to do, step by step ([KinkPack.joiPlan]), from the intimate band, in a stage that plays
    private class Joi(val leader: String, val plan: List<String>, var at: Int = 0)
    private val jois = HashMap<String, Joi>()

    private fun joiStageFor(c: Candidate, band: ComfortBand): String? {
        val pack = kink ?: return null
        val other = c.other ?: return null
        if (c.mode != TalkMode.LIVE || band.kink < Band.INTIMATE) return null
        val k = pk(c.speaker.profile.id, other.profile.id)
        var j = jois[k]
        if (j == null) {
            val style = c.speaker.profile.disposition.style
            if ((style != "dominant" && style != "bold") || random.nextFloat() >= JOI_CHANCE) return null
            val plan = pack.joiPlan(band.kink, random.nextLong())
            if (plan.isEmpty()) return null
            j = Joi(c.speaker.profile.id, plan); jois[k] = j
        }
        if (j.leader != c.speaker.profile.id) return null
        val stage = j.plan.getOrNull(j.at++)
        if (stage == null) { jois.remove(k); return null }
        return stage
    }

    private fun onAnswer(c: Candidate, req: DialogueRequest, raw: String?, retried: Boolean, gen: Int, prio: Int) {
        val me = c.speaker.profile
        val line = LineCleaner.clean(raw, me.name)
        if (line == null) {
            // nothing came back (no model, busy, a bad answer): ask less and less often, never in a tight loop
            nullStreak++
            nextSpeechAt = clock + minOf(1.0 * (1 shl minOf(nullStreak - 1, 4)), 16.0)
            return
        }
        nullStreak = 0
        val bad = Anachronisms.find(line)
        if (bad != null) {
            if (!retried) {
                val again = req.copy(user = req.user + "\nSpeak as people of Azeroth do; do not use the word \"$bad\" or anything like it.")
                runner.submit(prio, "speech", again, ttl = 15.0, urgency = req.priority) { r2 -> if (gen == generation) onAnswer(c, again, r2, retried = true, gen, prio) }
            } else nextSpeechAt = clock + 1.0
            return
        }
        if (deliver(me.id, line, c.mode, c.other?.profile?.id, consentLine = false)) {
            val tl = lastSpoke
            tl[me.id] = clock
            c.other?.let { o ->
                note(me.id, DialogueTurn.Role.ASSISTANT, line)
                note(o.profile.id, DialogueTurn.Role.USER, "${me.first}: $line")
                val k = pk(me.id, o.profile.id)
                c.conv?.let { it.turnsLeft--; it.next = o.profile.id; it.heard = line }
                if (c.mode == TalkMode.LIVE) liveTurn[k] = o.profile.id
            } ?: note(me.id, DialogueTurn.Role.ASSISTANT, line)
        }
        nextSpeechAt = clock + BASE_GAP / settings.talk.factor
    }

    /** Filters a line by the gates, then shows it. True: shown. */
    private fun deliver(id: String, rawLine: String, mode: TalkMode, other: String?, consentLine: Boolean): Boolean {
        if (!active || cinematic) return false
        var line = LineCleaner.clean(rawLine) ?: return false
        if (Anachronisms.find(line) != null) return false
        val p = view?.person(id)
        val sexualNow = p?.inStage == true && p.sexualStage
        // live vocalisation belongs to a stage that is playing; talk about desire doesn't
        if (!sexualNow && LiveFilter.isLive(line)) line = LiveFilter.rewrite(line) ?: return false
        return sink.say(id, line)
    }

    private fun note(id: String, role: DialogueTurn.Role, text: String) {
        val q = history.getOrPut(id) { ArrayDeque() }
        q.addLast(DialogueTurn(role, text))
        while (q.size > HISTORY) q.removeFirst()
    }

    companion object {
        const val HISTORY = 8
        const val EVENT_AGE = 25.0
        const val BASE_GAP = 2.5
        const val LIVE_GAP = 5.5
        const val SOLO_GAP = 30.0
        const val PAIR_COOLDOWN = 45.0
        const val JOI_CHANCE = 0.25f
    }
}

/** The layer as the screen shows it. */
data class DialogueSnapshot(val hesitant: List<HesitantView> = emptyList(), val steers: List<SteerEntry> = emptyList(), val active: Boolean = false)
