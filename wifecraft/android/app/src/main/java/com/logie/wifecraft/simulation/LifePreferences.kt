package com.logie.wifecraft.simulation

import com.logie.wifecraft.creator.CasPersonality
import com.logie.wifecraft.creator.Initiation
import com.logie.wifecraft.creator.Pace
import com.logie.wifecraft.creator.Preferences
import com.logie.wifecraft.creator.StageKind

/**
 * How the Personality tab's choices ([CasPersonality]: traits, [Preferences]) reach the life simulation: the traits she was
 * given instead of the ones her id would give her, and the boundaries LifeSim honours for what NPCs start by themselves
 * (the player's own orders are the player's). Pure JVM.
 */
object LifePreferences {
    /** The traits chosen on the Personality tab, by name (unknown names dropped, opposites after the first dropped); empty: none chosen. */
    fun traits(p: CasPersonality?): List<Trait> {
        val out = ArrayList<Trait>()
        for (n in p?.traits.orEmpty()) {
            val t = Trait.entries.firstOrNull { it.name == n } ?: continue
            if (t !in out && out.none { Traits.opposed(it, t) } && out.size < Traits.PER_NPC) out += t
        }
        return out
    }

    /** The stage a two-person interaction is (null: not a stage, a word or a gift). */
    fun kindOf(id: InteractionId): StageKind? = when (id) {
        InteractionId.KISS -> StageKind.KISS
        InteractionId.HUG -> StageKind.EMBRACE
        InteractionId.DANCE_TOGETHER -> StageKind.DANCE
        InteractionId.PLAY_TOGETHER -> StageKind.PLAY
        else -> null
    }

    /**
     * The most a pair started by themselves may be ([Intimacy]) by her allowed stages: Friendly when she allows an embrace,
     * a dance or play; Romantic when also a kiss or a cuddle; Intimate when also the intimate ones. Casual always.
     */
    fun cap(p: Preferences): Intimacy {
        val friendly = p.allows(StageKind.EMBRACE) || p.allows(StageKind.DANCE) || p.allows(StageKind.PLAY)
        val romantic = friendly && (p.allows(StageKind.KISS) || p.allows(StageKind.CUDDLE))
        return when {
            romantic && p.allows(StageKind.INTIMATE) -> Intimacy.INTIMATE
            romantic -> Intimacy.ROMANTIC
            friendly -> Intimacy.FRIENDLY
            else -> Intimacy.CASUAL
        }
    }

    /** Whether someone may start [id] with her by themselves: her initiation not No and the stage allowed. */
    fun mayStart(p: Preferences, id: InteractionId): Boolean {
        val kind = kindOf(id) ?: return true
        return p.initiation != Initiation.NO && p.allows(kind)
    }

    /** Whether she would start [id] herself: the stage allowed (her initiation is about others). */
    fun wouldStart(p: Preferences, id: InteractionId): Boolean = kindOf(id)?.let { p.allows(it) } ?: true

    /** Her pace's say on how much she wants [id]: a slow one less of a kiss, an intense one more. */
    fun paceFactor(p: Preferences, id: InteractionId): Float = when (kindOf(id)) {
        StageKind.KISS, StageKind.CUDDLE, StageKind.INTIMATE -> when (p.pace) { Pace.SLOW -> 0.7f; Pace.INTENSE -> 1.3f; Pace.EITHER -> 1f }
        StageKind.EMBRACE -> when (p.pace) { Pace.SLOW -> 0.9f; Pace.INTENSE -> 1.1f; Pace.EITHER -> 1f }
        else -> 1f
    }

    /** Her answer's chance to a stage someone started by themselves: as the life says, less when she wants to be asked, none at No. */
    fun acceptanceFactor(p: Preferences, id: InteractionId): Float {
        val kind = kindOf(id) ?: return 1f
        if (!p.allows(kind)) return 0f
        return when (p.initiation) { Initiation.YES -> 1f; Initiation.ASK -> ASK_FACTOR; Initiation.NO -> 0f }
    }

    const val ASK_FACTOR = 0.6f
}
