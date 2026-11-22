package com.logie.wifecraft.simulation

/**
 * NPCs talking to each other: one line of a conversation ([ChatLine]), a conversation's clock ([Conversation]: which line
 * is spoken now, the speaker's gesture and the listener's), how often idle NPCs start one ([ChatterFrequency]) and the
 * game animations they gesture with ([Gestures]). The lines themselves come from [Chatter]. Pure JVM.
 */

/** Line [text] spoken by [speaker] (0: the one who began the conversation, 1: the other), with [gesture] (a game
 *  animation, [Gestures]) at its start; [nod]: the listener nods as it ends. */
data class ChatLine(val speaker: Int, val text: String, val gesture: Int, val nod: Boolean = false)

/** Off, Sometimes, Often: the chance an idle NPC's decision is to go and talk to another (the scene setup's Chatter row). */
enum class ChatterFrequency(val label: String, val chance: Float) {
    OFF("Off", 0f),
    SOMETIMES("Sometimes", 0.3f),
    OFTEN("Often", 0.65f);

    companion object {
        fun of(name: String?): ChatterFrequency = entries.firstOrNull { it.name == name } ?: SOMETIMES
    }
}

/** The game animations (AnimationData ids, data/Emotes) a talker uses; every look fetches them. */
object Gestures {
    const val STAND = 0
    const val TALK = 60
    const val EXCLAIM = 64
    const val QUESTION = 65
    const val WAVE = 67
    const val LAUGH = 70
    const val YES = 185

    /** The gesture a free line suggests (the player's Say): a question, a laugh, an exclamation, else talking. */
    fun of(text: String): Int {
        val t = text.trim()
        return when {
            t.startsWith("Ha") && (t.startsWith("Ha!") || t.startsWith("Ha,") || t.startsWith("Haha")) -> LAUGH
            t.endsWith("?") -> QUESTION
            t.endsWith("!") -> EXCLAIM
            else -> TALK
        }
    }
}

/** How long a line stays up to be read: longer lines longer, between [MIN_READ] and [MAX_READ] seconds. */
object Reading {
    const val MIN_READ = 2.4f
    const val MAX_READ = 7.5f
    fun seconds(text: String): Float = (1.3f + text.length * 0.06f).coerceIn(MIN_READ, MAX_READ)
}

/**
 * One conversation's clock: [start] begins its first line; [advance] moves it on (each line is up for its reading time,
 * then a short pause with nobody speaking); [done] after the last. [gesture] says what each side plays now: the speaker its
 * line's gesture for the first moments, then talking; the listener standing, nodding at the end of a line marked so.
 */
class Conversation(val lines: List<ChatLine>) {
    /** The line being spoken or just spoken (-1: not started). */
    var index = -1
        private set
    /** Seconds into the line ([speaking]) or into the pause after it. */
    var clock = 0f
        private set
    var speaking = false
        private set
    val started: Boolean get() = index >= 0
    val done: Boolean get() = index >= lines.size

    /** The line up now, or null (a pause, before the start, after the end). */
    val line: ChatLine? get() = if (speaking) lines.getOrNull(index) else null

    /** Begins the first line: its index (0), or -1 when there are no lines. */
    fun start(): Int {
        index = 0; clock = 0f
        speaking = lines.isNotEmpty()
        return if (speaking) 0 else -1
    }

    /** The clock moves [dt] seconds: the index of a line that began now, else -1. */
    fun advance(dt: Float): Int {
        if (!started || done) return -1
        clock += dt
        if (speaking) {
            val length = Reading.seconds(lines[index].text)
            if (clock >= length) { clock -= length; speaking = false }
        }
        if (!speaking && clock >= PAUSE) {
            clock = 0f
            index++
            if (done) return -1
            speaking = true
            return index
        }
        return -1
    }

    /** The game animation side [role] (0 or 1) plays now, or -1 (the conversation is not running). */
    fun gesture(role: Int): Int {
        if (!started || done) return -1
        val l = line ?: return Gestures.STAND
        // every gesture goes through the pose check ([ConversationPoses]): a laugh or an exclamation that folds or twists the
        // body (the shoulder armour over her head, her arms out behind) never plays; a nod or plain talking does instead
        if (l.speaker == role) return if (clock < GESTURE_SECONDS) ConversationPoses.vet(l.gesture) else Gestures.TALK
        val length = Reading.seconds(l.text)
        return if (l.nod && clock >= length - NOD_SECONDS) ConversationPoses.vet(Gestures.YES, Gestures.STAND) else Gestures.STAND
    }

    companion object {
        /** Seconds between lines, nobody speaking. */
        const val PAUSE = 0.55f
        /** A line's own gesture (a wave, a laugh) plays this long, then the speaker talks. */
        const val GESTURE_SECONDS = 2.2f
        /** The listener nods for the last this-many seconds of a line marked [ChatLine.nod]. */
        const val NOD_SECONDS = 1.4f
    }
}
