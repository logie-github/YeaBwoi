package com.logie.wifecraft.dialogue

import com.logie.wifecraft.simulation.Intimacy
import com.logie.wifecraft.simulation.PairKinds

/** What the simulation does with a sexual stage about to start: go on, wait for the answer, or end the invitation (refused for now). */
enum class ConsentGate { PROCEED, WAIT, REFUSED }

/**
 * The simulation's side of the dialogue layer (SceneSimulation.dialogue): null or not [active], everything runs exactly as
 * before the layer existed. Implemented by [DialogueDirector].
 */
interface DialogueHook {
    /** The model is there and the layer is on. */
    val active: Boolean

    /** Cinematic mode is on: no speech bubbles at all, and no questions asked before a stage. */
    val cinematic: Boolean

    /** Whether the words of an ordinary conversation of [a] and [b] come from the layer (the simulation then plays it without scripted lines). */
    fun takesOverChat(a: String, b: String): Boolean

    /** The answer to "may [initiator] and [target] begin [stageName]?" now ([directed]: the player ordered it). May be asked again until it isn't WAIT. */
    fun consent(initiator: String, target: String, directed: Boolean, stageName: String): ConsentGate

    /**
     * [npcId] will not do [actName] with [otherId] (the player's Consent and limits): the layer voices the refusal in
     * character. True: it will speak a line; false (the default): it has none, and the simulation shows its own bubble.
     */
    fun declined(npcId: String, otherId: String, actName: String): Boolean = false
}

/** Which stages are sexual: the line the live talk is gated by. */
object DialogueGate {

    /**
     * Sexual by the app's own classification of a pair's name ([PairKinds]: [Intimacy.INTIMATE], the library's unnamed pairs
     * being lovers'; kisses, embraces, cuddles, dances, hugs and play are not) unless the pack's category says it is calm. A
     * Warcraft emote is never sexual. [classify], when the screen has the arousal map loaded, decides first (its StageClass.intimate).
     */
    fun isSexual(name: String, category: String = "", emote: Int = -1, classify: ((name: String, category: String) -> Boolean)? = null): Boolean {
        if (emote >= 0) return false
        classify?.let { return it(name, category) }
        if (category.uppercase() == "CALM") return false
        return PairKinds.of(name) == Intimacy.INTIMATE
    }
}
