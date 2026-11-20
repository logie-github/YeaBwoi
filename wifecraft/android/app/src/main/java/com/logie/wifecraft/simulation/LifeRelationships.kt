package com.logie.wifecraft.simulation

/**
 * What two NPCs are to each other ([LifeSim]): a friendship and a romance, each -100..100. Friendship is read in the
 * game's reputation standings ([FriendTier]: Hated .. Exalted), romance on its own track ([RomanceTier]: Spurned, Stranger
 * .. Soulbound). Both rise with time well spent together and fall with quarrels and refusals; apart, they drift slowly back
 * toward nothing. Pure JVM.
 */

/** Friendship's standings, as the game's reputation names them, from [min] (inclusive). */
enum class FriendTier(val label: String, val min: Float) {
    HATED("Hated", -100f),
    HOSTILE("Hostile", -60f),
    UNFRIENDLY("Unfriendly", -30f),
    NEUTRAL("Neutral", -10f),
    FRIENDLY("Friendly", 10f),
    HONORED("Honored", 30f),
    REVERED("Revered", 55f),
    EXALTED("Exalted", 80f);

    companion object {
        fun of(value: Float): FriendTier = entries.last { value >= it.min || it == HATED }
    }
}

/** Romance's track, from [min] (inclusive). */
enum class RomanceTier(val label: String, val min: Float) {
    SPURNED("Spurned", -100f),
    STRANGER("Stranger", -20f),
    ACQUAINTANCE("Acquaintance", 5f),
    FLIRTING("Flirting", 20f),
    SWEETHEARTS("Sweethearts", 40f),
    ROMANTIC("Romantic", 65f),
    SOULBOUND("Soulbound", 85f);

    companion object {
        fun of(value: Float): RomanceTier = entries.last { value >= it.min || it == SPURNED }
    }
}

/** Two people's standing ([a] < [b] by cast order). [apartHours]: game hours since they last did something together. */
class Relationship(val a: Int, val b: Int, var friendship: Float = 0f, var romance: Float = 0f) {
    var apartHours = 0.0
    val friendTier: FriendTier get() = FriendTier.of(friendship)
    val romanceTier: RomanceTier get() = RomanceTier.of(romance)

    /** The most intimate two-person animation they would pick by themselves. */
    val allows: Intimacy get() = when {
        romance >= RomanceTier.ROMANTIC.min && friendship >= FriendTier.NEUTRAL.min -> Intimacy.INTIMATE
        romance >= RomanceTier.SWEETHEARTS.min && friendship >= FriendTier.NEUTRAL.min -> Intimacy.ROMANTIC
        friendship >= FriendTier.FRIENDLY.min -> Intimacy.FRIENDLY
        friendship >= FriendTier.UNFRIENDLY.min -> Intimacy.CASUAL
        else -> Intimacy.CASUAL
    }

    fun permits(level: Intimacy): Boolean = friendship >= FriendTier.UNFRIENDLY.min && level.ordinal <= allows.ordinal
}

/** Rules of thumb for how much each thing done together is worth (before compatibility and mood). */
object RelationshipRules {
    /** Below what an NPC accepts nothing from another but a chat. */
    const val SHUNNED = -30f
    /** Hours apart before a relationship starts to drift, and how far a game hour then moves it back toward 0. */
    const val DRIFT_AFTER_HOURS = 12.0
    const val DRIFT_PER_HOUR = 0.04f

    /** The friendship and romance a done interaction gives (before compatibility). */
    fun gain(id: InteractionId): Pair<Float, Float> = when (id) {
        InteractionId.CHAT -> 4.5f to 0f
        InteractionId.JOKE -> 4f to 0f
        InteractionId.COMPLIMENT -> 4.5f to 1.5f
        InteractionId.HUG -> 4f to 1f
        InteractionId.FLIRT -> 1f to 6f
        InteractionId.KISS -> 1.5f to 6f
        InteractionId.ARGUE -> -9f to -4f
        InteractionId.INVITE_SIT -> 3f to 1f
        InteractionId.DANCE_TOGETHER -> 4f to 1.5f
        InteractionId.GIVE_GIFT -> 6f to 1.5f
        InteractionId.PLAY_TOGETHER -> 3f to 1.5f
        else -> 0f to 0f
    }

    /** A refusal's cost to the one refused (friendship, romance). */
    fun refusal(id: InteractionId): Pair<Float, Float> = when (id) {
        InteractionId.FLIRT, InteractionId.KISS -> -1f to -4f
        InteractionId.HUG, InteractionId.DANCE_TOGETHER -> -2f to -0.5f
        else -> -1.5f to 0f
    }

    /**
     * The least standing at which an NPC goes for [id] by itself (the player's own choice still asks, and the other may
     * refuse).
     */
    fun needs(id: InteractionId, r: Relationship): Boolean = when (id) {
        InteractionId.HUG, InteractionId.DANCE_TOGETHER, InteractionId.INVITE_SIT, InteractionId.GIVE_GIFT ->
            r.friendship >= FriendTier.FRIENDLY.min
        InteractionId.FLIRT -> r.friendship >= FriendTier.FRIENDLY.min || r.romance >= RomanceTier.ACQUAINTANCE.min
        InteractionId.KISS -> r.romance >= RomanceTier.SWEETHEARTS.min
        InteractionId.COMPLIMENT -> r.friendship >= FriendTier.NEUTRAL.min
        InteractionId.PLAY_TOGETHER -> r.friendship >= FriendTier.NEUTRAL.min
        else -> true
    }
}
