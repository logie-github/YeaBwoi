package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.ChatCharacter
import com.logie.wifecraft.dialogue.ChatSession
import com.logie.wifecraft.dialogue.ChatSetup
import kotlin.random.Random

// Pure Kotlin (no Android, no Compose): Chat mode's state machine and her gestures, JVM-tested (ChatModeTest). The selected NPC talks with
// the player's character as with an ordinary person standing in front of her. This class knows the phases (the setup sheet, the model
// missing, the talk), what was on before ([JoiPrior]: put back on leaving), and when a small gesture is due; the words come from
// [ChatSession] (the dialogue engine) and the camera is [ChatCamera].

enum class ChatPhase { OFF, SETUP, NEEDS_MODEL, CHATTING }

sealed class ChatEvent {
    /** A small idle or talking gesture is due ([ChatGestures.pick] one); [speaking]: she is speaking now. */
    data class GestureDue(val speaking: Boolean) : ChatEvent()
}

/** What the screen shows while the camera is on: the view from the front (the NPC is turned to face it). */
class ChatMode(seed: Long = Random.nextLong()) {
    var phase = ChatPhase.OFF
        private set
    var setup: ChatSetup? = null
        private set
    var session: ChatSession? = null
        private set
    /** The speaker's id and whether she is a creature (Sindragosa: her own animation, her camera from in front of her head). */
    var actor: String? = null
        private set
    var creature = false
        private set
    /** What was on before Chat mode (cinematic mode, camera mode and furniture view): put back by [leave]. */
    var prior = JoiPrior(false)
        private set
    val gestures = ChatGestures(seed)

    private val rnd = Random(seed)
    private var clock = 0f
    private var due = 0f
    private var hold = 0f
    private var wasSpeaking = false
    private val events = ArrayList<ChatEvent>()

    val on: Boolean get() = phase != ChatPhase.OFF
    /** The camera is in front of her (the talk or the no-model note, not the setup sheet). */
    val cameraOn: Boolean get() = phase == ChatPhase.CHATTING || phase == ChatPhase.NEEDS_MODEL

    /** The Chat order: the setup sheet opens for [actor] ([setup] already opened by the caller or opened here); [prior] is what to put back. */
    fun open(actor: String, creature: Boolean, prior: JoiPrior, setup: ChatSetup) {
        this.actor = actor; this.creature = creature; this.prior = prior
        this.setup = setup; setup.open()
        session = null; events.clear(); clock = 0f; hold = 0f
        phase = ChatPhase.SETUP
    }

    /** Cancel on the sheet: nothing changed, the scene as it was. Returns what to put back (nothing was changed: the caller need do nothing). */
    fun cancel(): JoiPrior {
        setup?.cancel()
        val p = prior
        reset()
        return p
    }

    /**
     * Start on the sheet: the character is made (and remembered), the session built by [makeSession], and the camera goes to the front.
     * [modelReady] false: the no-model note, and [modelReady] again later. False: the sheet isn't complete.
     */
    fun start(makeSession: (ChatCharacter) -> ChatSession, modelReady: Boolean): Boolean {
        if (phase != ChatPhase.SETUP) return false
        val c = setup?.start() ?: return false
        session = makeSession(c)
        phase = if (modelReady) ChatPhase.CHATTING else ChatPhase.NEEDS_MODEL
        clock = 0f; hold = 0f; due = gestures.gap(false, rnd)
        return true
    }

    /** The model arrived (or a retry): the talk begins. */
    fun modelReady() { if (phase == ChatPhase.NEEDS_MODEL) { phase = ChatPhase.CHATTING; due = clock + gestures.gap(false, rnd) } }

    /** The model went away mid-talk (nothing came back and none is loaded): the no-model note, and [modelReady] when it is back. */
    fun modelLost() { if (phase == ChatPhase.CHATTING) phase = ChatPhase.NEEDS_MODEL }

    /** Leave the chat (Leave, Back, the Cinematic button): the talk, the ease and the steers are forgotten; returns what to put back. */
    fun leave(): JoiPrior {
        session?.reset()
        val p = prior
        reset()
        return p
    }

    private fun reset() {
        phase = ChatPhase.OFF; session = null; setup = null; actor = null; creature = false; events.clear(); hold = 0f
    }

    /** No gesture for [seconds] (one is playing). */
    fun holdGestures(seconds: Float) { hold = seconds.coerceAtLeast(0f) }

    /** Advances by [dt] seconds; [speaking]: she is saying a line (the reply is being shown). Events come out of [drain]. */
    fun update(dt: Float, speaking: Boolean) {
        if (phase != ChatPhase.CHATTING || dt <= 0f) return
        val step = dt.coerceAtMost(0.25f)
        clock += step
        if (hold > 0f) hold = (hold - step).coerceAtLeast(0f)
        // she starts to speak: the next gesture is brought forward to a speaking gap
        if (speaking && !wasSpeaking && due - clock > 9f) due = clock + gestures.gap(true, rnd)
        wasSpeaking = speaking
        // (a creature keeps her own animation; the neck and head sway layer runs over it)
        if (clock >= due) {
            due = clock + gestures.gap(speaking, rnd)
            if (hold <= 0f && !creature) events += ChatEvent.GestureDue(speaking)
        }
    }

    fun drain(): List<ChatEvent> = if (events.isEmpty()) emptyList() else events.toList().also { events.clear() }
}

/**
 * Her small gestures while she talks and listens: the gesture, pose, hair, look-back and flirt kinds of the solo library (the same
 * classification as JOI's, [JoiAnimations.classify]) and any clip named for talking; never a dance, a tease, a kiss or a handjob, and no
 * emote whose measured pose folds the body ([ConversationPoses]).
 * One is due every 4 to 9 s while she speaks, every 14 to 30 s while she listens; never the one just played nor any within [COOLDOWN].
 */
class ChatGestures(seed: Long = Random.nextLong()) {
    private val rnd = Random(seed xor 0x5DEECE66DL)
    private val last = HashMap<String, Float>()
    var lastKey: String? = null
        private set

    /** The clips that are small gestures, and what each is. */
    fun catalogue(clips: List<JoiClip>): Map<JoiClip, Set<JoiMove>> {
        val out = LinkedHashMap<JoiClip, Set<JoiMove>>()
        for (c in clips) {
            if (c.handjob || c.procedural) continue
            // a game animation (an emote) is played only when its measured pose passes the conversation check, as in NPC-NPC talk
            // ([ConversationPoses]: Laugh, Exclamation and the nod fold the body; an unmeasured one is not played); the library's own clips are
            // not measured (their poses are whatever the imported pack made: gestures, poses, hair, look-backs and flirts by name)
            PlaybackKeys.emoteOf(c.key)?.let { anim -> if (ConversationPoses.verdict(anim)?.ok != true) continue }
            val moves = JoiAnimations.classify(c).filter { it in SMALL }.toSet()
            if (moves.isNotEmpty() && JoiAnimations.classify(c).all { it in SMALL }) { out[c] = moves; continue }
            if (moves.isEmpty()) {
                val t = " ${c.label} ${c.group} ${c.key}".lowercase()
                if (TALK.containsMatchIn(t) && !AVOID.containsMatchIn(t)) out[c] = setOf(JoiMove.GESTURE)
            }
        }
        return out
    }

    /** Seconds until the next gesture. */
    fun gap(speaking: Boolean, rnd: Random = this.rnd): Float = if (speaking) 4f + 5f * rnd.nextFloat() else 14f + 16f * rnd.nextFloat()

    /** One clip at random from [catalogue] at [now] (seconds): not the last, none within [COOLDOWN]; null when none is eligible. */
    fun pick(catalogue: Map<JoiClip, Set<JoiMove>>, now: Float): JoiClip? {
        val eligible = catalogue.keys.filter { it.key != lastKey && (last[it.key] ?: Float.NEGATIVE_INFINITY) + COOLDOWN <= now }
        val pick = eligible.randomOrNull(rnd) ?: return null
        last[pick.key] = now; lastKey = pick.key
        return pick
    }

    fun reset() { last.clear(); lastKey = null }

    companion object {
        const val COOLDOWN = 45f
        val SMALL = setOf(JoiMove.GESTURE, JoiMove.POSE, JoiMove.HAIR, JoiMove.LOOK_BACK, JoiMove.FLIRT)
        private val TALK = Regex("talk|speak|question|exclaim|shrug|\\bnod\\b|explain|ponder|think|conversation|\\bchat\\b")
        private val AVOID = Regex("\\bsit|sleep|\\blie|lying|kneel|crouch|cry|attack|fight|combat|death|dead|walk|\\brun|swim|mount|cast|partner|pair:|sex|oral|stage|kill|dance")
    }
}
