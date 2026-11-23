package com.logie.wifecraft.dialogue

/** What a person is doing, as the live scene reports it. */
data class PersonView(
    val profile: NpcProfile,
    /** "standing idle", "walking to the bed", "sitting on the couch", "talking with Jaina": plain words for the prompt. */
    val doing: String = "standing about",
    /** The one they talk or play a stage with, or null. */
    val withId: String? = null,
    val inConversation: Boolean = false,
    /** In a conversation: this one went over to the other (speaks first). */
    val leads: Boolean = false,
    val inStage: Boolean = false,
    /** The stage is a sexual one ([DialogueGate.isSexual]). */
    val sexualStage: Boolean = false,
    val stageName: String = "",
    val moving: Boolean = false,
    /** Yards from the camera (smaller: nearer). */
    val cameraDistance: Float = 10f,
    val selected: Boolean = false,
)

data class PlaceView(
    val name: String,
    val indoor: Boolean = true,
    /** Hour of the day 0..24, NaN unknown. */
    val hour: Double = Double.NaN,
    val weather: String? = null,
    /** Notable furniture and props at hand ("a double bed", "a hearth"). */
    val props: List<String> = emptyList(),
)

/** Something that happened lately, for the NPCs to remark on. */
data class SceneEvent(val kind: Kind, val who: String, val other: String? = null, val what: String = "") {
    enum class Kind { ARRIVED, LEFT, STAGE_STARTED, STAGE_ENDED, CONVERSATION_STARTED }
}

data class SceneView(
    val place: PlaceView,
    val people: List<PersonView>,
    val cinematic: Boolean = false,
    val relation: (String, String) -> Relation = { _, _ -> Relation() },
) {
    fun person(id: String): PersonView? = people.firstOrNull { it.profile.id == id }
}

/** The live scene made into the words of a prompt: where they are, who is here and what they do, what just happened. */
object SceneContext {
    fun timeOfDay(hour: Double): String? = when {
        hour.isNaN() -> null
        hour < 5 -> "the dead of night"; hour < 7.5 -> "dawn"; hour < 12 -> "morning"; hour < 14 -> "midday"
        hour < 18 -> "afternoon"; hour < 21 -> "evening"; else -> "night"
    }

    fun place(p: PlaceView): String {
        val sb = StringBuilder("You are in ${p.name}, ${if (p.indoor) "indoors" else "out in the open"}")
        timeOfDay(p.hour)?.let { sb.append("; it is $it") }
        p.weather?.takeIf { it.isNotBlank() }?.let { sb.append("; the weather is ${it.lowercase()}") }
        sb.append('.')
        if (p.props.isNotEmpty()) sb.append(" Nearby: ").append(p.props.take(6).joinToString(", ")).append('.')
        return sb.toString()
    }

    fun people(me: String, view: SceneView, lore: Lore, maxPeople: Int = 5): String = people(me, view, lore, maxPeople, null) ?: "Nobody else is here."

    /** The people in the room but [me] (and [except]), the nearest [maxPeople]; null when there is nobody to list. */
    fun people(me: String, view: SceneView, lore: Lore, maxPeople: Int, except: String?): String? {
        val others = view.people.filter { it.profile.id != me && it.profile.id != except }
            .sortedBy { it.cameraDistance }.take(maxPeople)
        if (others.isEmpty()) return null
        return others.joinToString("\n") { o ->
            val p = o.profile
            val r = lore.race(p.raceId)?.name ?: "stranger"
            val c = lore.className(p.classId)?.let { " $it" } ?: ""
            val f = lore.faction(p.raceId).label
            "- ${p.name}, a $r$c ($f), ${o.doing}"
        }
    }

    fun events(me: String, events: List<SceneEvent>, view: SceneView, max: Int = 3): String? {
        fun name(id: String?) = id?.let { view.person(it)?.profile?.first } ?: "someone"
        val lines = events.filter { it.who != me }.takeLast(max).map { e ->
            when (e.kind) {
                SceneEvent.Kind.ARRIVED -> "${name(e.who)} has just come in"
                SceneEvent.Kind.LEFT -> "${name(e.who)} has just left"
                SceneEvent.Kind.STAGE_STARTED -> "${name(e.who)} and ${name(e.other)} have just begun ${e.what.ifBlank { "something together" }}"
                SceneEvent.Kind.STAGE_ENDED -> "${name(e.who)} and ${name(e.other)} have just finished"
                SceneEvent.Kind.CONVERSATION_STARTED -> "${name(e.who)} and ${name(e.other)} have started talking"
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { "Lately: $it." }
    }
}

/** How a line is asked for. */
enum class TalkMode {
    /** Ordinary talk: the world, the day, each other, lore; desire and bodies may be talked of, but nothing is happening right now. */
    WORLD,
    /** A sexual stage is playing: react to what is happening, with the sounds of it. */
    LIVE,
}

/**
 * The user message of one request: everything that changes from line to line (the scene, who is present, lately, what the
 * lore says of the others, the comfort with the partner, the steers, the mode) and the task. The persona stays in the system
 * prompt so its prefix is stable per NPC.
 */
object TurnPrompt {
    /** What an unprompted line's prompt carries of the scene: three people, two lore snippets of at most 360 characters (it is read anew for every line). */
    const val PRESENT_MAX = 3
    const val KNOW_ITEMS = 2
    const val KNOW_CHARS = 400
    /** The kink pack's snippets a line draws on. */
    const val KINK_SNIPPETS = 2

    class Input(
        val me: NpcProfile,
        val view: SceneView,
        val mode: TalkMode,
        val partner: NpcProfile?,
        val band: ComfortBand,
        /** Desire may be brought up in this line (a roll the director makes by nature and band; always allowed in [TalkMode.LIVE]). */
        val desireTopic: Boolean,
        val steers: List<Steer>,
        val events: List<SceneEvent> = emptyList(),
        val stageName: String = "",
        /** What the listener last said, if answering. */
        val heard: String? = null,
        val seed: String? = null,
        val maxWords: Int = 28,
        /** The kink and JOI pack's notes (null: none). */
        val kink: KinkContext? = null,
        /** The stage of an in-world JOI this line belongs to ([KinkPack.joiPlan]), or null. */
        val joiStage: String? = null,
        /** How filthy the words are (Options' Dirty talk level): the register of what she says about and in desire. */
        val dirty: DirtyLevel = DirtyLevel.DEFAULT,
    )

    fun build(lore: Lore, i: Input): String {
        val b = StringBuilder()
        b.append(SceneContext.place(i.view.place)).append('\n')
        // the partner is told of below: the list is for the others in the room
        val present = SceneContext.people(i.me.id, i.view, lore, maxPeople = PRESENT_MAX, except = i.partner?.id)
        if (present != null) b.append("Present:\n").append(present).append('\n')
        SceneContext.events(i.me.id, i.events, i.view)?.let { b.append(it).append('\n') }
        val others = i.view.people.filter { it.profile.id != i.me.id }.sortedBy { it.cameraDistance }.take(4).map { it.profile.raceId to it.profile.classId }
        val withBodies = i.partner != null && (i.mode == TalkMode.LIVE || (i.band.bodyTalk && i.desireTopic))
        val know = lore.forScene(i.me.raceId, i.me.classId, i.view.place.name, others, withBodies = false, maxChars = KNOW_CHARS, includeSelf = false, maxItems = KNOW_ITEMS)
        if (know.isNotEmpty()) b.append("What you know: ").append(know.joinToString(" ")).append('\n')
        if (i.partner != null) {
            val pr = lore.race(i.partner.raceId)
            b.append("Your partner here is ${i.partner.name}")
            if (pr != null) b.append(", a ${pr.name}")
            b.append(". You and ${i.partner.first} are ${i.band.label} with each other.\n")
            if (withBodies) lore.body(i.partner.raceId)?.let { b.append("Their body, for you to draw on as you like: ").append(it).append('\n') }
            if (i.mode == TalkMode.LIVE || i.desireTopic) lore.kinkGuide(i.band.key)?.let { b.append("How open you are now: ").append(it).append('\n') }
            if (i.mode == TalkMode.LIVE || i.desireTopic) b.append(DirtyTalk.register(i.dirty)).append('\n')
            if (i.mode == TalkMode.LIVE || i.desireTopic) b.append("In bed you are ${i.me.disposition.style}; your pace of loosening is ${paceWord(i.me.disposition)}.\n")
        }
        kinkNotes(lore, i)?.let { b.append(it) }
        for (s in i.steers) b.append(s.hint()).append('\n')
        i.seed?.let { b.append("A thing on your mind: ").append(it).append('\n') }
        b.append(modeText(i)).append('\n')
        i.heard?.let { b.append("They just said: \"").append(it).append("\"\n") }
        val to = i.partner?.let { "to ${it.first}" } ?: "aloud"
        b.append("Say your next line $to in at most ${i.maxWords} words, one or two sentences, in your own voice. Reply with the spoken words only.")
        return b.toString()
    }

    /** The words the kink pack knows a nature by. */
    fun personalityWords(p: NpcProfile): Set<String> {
        val out = linkedSetOf(p.disposition.style)
        for (t in p.traits) when (t.uppercase()) {
            "PLAYFUL" -> out += "playful"; "MISCHIEVOUS" -> { out += "brat"; out += "playful" }; "ROMANTIC", "CALM" -> out += "tender"
            "LONER", "ASCETIC" -> out += "shy"; "BOOKISH" -> out += "curious"; "BRAVE" -> out += "bold"
        }
        return out
    }

    /** The speaker's role in the dynamic, as the pack reads it. */
    fun roleOf(d: Disposition): String? = when (d.style) { "dominant" -> "dom"; "shy" -> "sub"; "bold" -> "switch"; else -> null }

    /** The pack's query for [i]: the band mapped, the natures, the races and classes of the two, the stage. */
    fun kinkQuery(lore: Lore, i: Input, limit: Int = KINK_SNIPPETS): KinkQuery {
        val people = listOfNotNull(i.me, i.partner)
        return KinkQuery(i.band.kink, personalityWords(i.me), roleOf(i.me.disposition),
            people.mapNotNull { lore.raceKey(it.raceId) }, people.mapNotNull { lore.className(it.classId)?.replace(" ", "")?.lowercase() },
            stage = if (i.joiStage != null) "joi" else if (i.mode == TalkMode.LIVE) "scene" else "flirt", limit = limit, level = i.dirty)
    }

    private fun kinkNotes(lore: Lore, i: Input): String? {
        val k = i.kink ?: return null
        if (i.partner == null || !(i.mode == TalkMode.LIVE || i.desireTopic) || i.band.kink < Band.ACQUAINTANCES) return null
        val q = kinkQuery(lore, i)
        val sb = StringBuilder()
        val snippets = k.snippets(q)
        if (snippets.isNotEmpty()) sb.append("Ideas you may draw on, in your own words: ").append(snippets.joinToString(" ")).append('\n')
        i.joiStage?.let { st -> k.joiHint(st, q)?.let { sb.append("The one telling the other what to do, this step: ").append(it).append('\n') } }
        // no consent-care line here: it makes her sound like a therapist; care belongs to the care and aftercare stages
        val lines = k.dirty()?.examples(i.dirty, if (i.mode == TalkMode.LIVE) "live" else "chat", 4, i.me.female, (i.heard?.length ?: 0) + i.stageName.length)
        if (!lines.isNullOrEmpty()) sb.append(DirtyTalk.examplesBlock(lines, "Lines in this register, for the feel (never copy them, say something new):"))
        else k.examples(q, 1).firstOrNull()?.let { sb.append("A sample of the register (do not copy it):\n").append(it.render()).append('\n') }
        return sb.takeIf { it.isNotEmpty() }?.toString()
    }

    private fun paceWord(d: Disposition) = when { d.speed < 0.8f -> "slow"; d.speed > 1.25f -> "quick"; else -> "ordinary" }

    private fun modeText(i: Input): String = when (i.mode) {
        TalkMode.LIVE -> "Right now you are in the middle of ${i.stageName.ifBlank { "lovemaking" }} with ${i.partner?.first ?: "your partner"}. " +
            "Speak as the moment takes you: what is being done, what you like, what you want next, with the sounds of it, in the register above."
        TalkMode.WORLD -> "Nothing intimate is happening this moment. Talk of the world, your day, the people here, the place, lore and gossip" +
            (if (i.desireTopic) ", or, as your nature and ease allow, of desire, bodies, memories and what you would like to do with someone; that is talk only" else "") +
            ". Do not make the sounds of lovemaking and do not describe an act as it happens."
    }
}
