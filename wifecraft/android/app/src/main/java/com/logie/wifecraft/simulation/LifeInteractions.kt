package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3

/**
 * What NPCs can do in the life simulation ([LifeSim]): every interaction ([InteractionId]) with what it gives a game hour
 * ([InteractionId.gains]), where it happens ([LifeProvider]: a piece of the scene's furniture, a prop, the open floor) and
 * the animation it plays. Sleeping and sitting use the scene's own REST and SIT on its furniture; eating, drinking,
 * dancing, kneeling by a fire play a Warcraft emote ([PlaybackKeys.emote]) where they stand. Pure JVM.
 */

/** The scene's things life uses besides [SceneObject]: a fireplace, a wash basin, a bookshelf (none in a place until a
 *  location names one: [LifeProp]). */
enum class LifePropKind(val label: String) { FIREPLACE("Fireplace"), BASIN("Wash basin"), BOOKSHELF("Bookshelf") }

/** A prop of [kind] used standing at [entry] facing [facing] (radians, the scene's frame). */
data class LifeProp(val kind: LifePropKind, val entry: Vec3, val facing: Float)

/** Where an interaction happens. */
enum class LifeProvider { BED, COUCH, CHAIR, TABLE, FLOOR, FIREPLACE, BASIN, BOOKSHELF, PERSON }

/** The game animations (AnimationData ids) life plays as emotes (EmotesText Eat and Drink both play EmoteEat). */
object LifeAnims {
    const val EAT = 61
    const val USE_STANDING = 63
    const val DANCE = 69
    const val KNEEL = 75
    const val FLEX = 82
    const val READ_LOOP = 520
}

/**
 * One interaction. [label] the menu's word; [social]: done with another NPC; [menu]: offered in the player's menu
 * ([SOLO_MENU], [SOCIAL_MENU]); [hours] how long it usually lasts (game hours, for its advertised gain); [providers] where
 * it happens, best first; [anim] the emote it plays (-1: the furniture's own pose, a conversation or a partner animation);
 * [cycle] seconds of one play of that emote; [cap] how far it can fill its first need (freshening up is not a bath).
 */
enum class InteractionId(
    val label: String, val social: Boolean, val hours: Float, val providers: List<LifeProvider>,
    val anim: Int = -1, val cycle: Float = 0f, val cap: Float = 1f,
) {
    SLEEP("Sleep", false, 7f, listOf(LifeProvider.BED)),
    NAP("Nap", false, 1.5f, listOf(LifeProvider.COUCH, LifeProvider.BED)),
    REST("Rest", false, 1f, listOf(LifeProvider.BED, LifeProvider.COUCH, LifeProvider.CHAIR)),
    RELAX("Relax", false, 0.5f, listOf(LifeProvider.COUCH, LifeProvider.CHAIR)),
    EAT("Eat", false, 0.35f, listOf(LifeProvider.TABLE, LifeProvider.FLOOR), LifeAnims.EAT, 2.6f),
    DRINK("Drink", false, 0.25f, listOf(LifeProvider.TABLE, LifeProvider.FLOOR), LifeAnims.EAT, 2.6f),
    READ("Read", false, 0.75f, listOf(LifeProvider.TABLE, LifeProvider.BOOKSHELF), LifeAnims.READ_LOOP, 4f),
    DANCE("Dance", false, 0.3f, listOf(LifeProvider.FLOOR), LifeAnims.DANCE, 4f),
    WASH("Wash", false, 0.3f, listOf(LifeProvider.BASIN), LifeAnims.USE_STANDING, 3f),
    TIDY("Freshen up", false, 0.25f, listOf(LifeProvider.FLOOR), LifeAnims.USE_STANDING, 3f, cap = 0.7f),
    STRETCH("Stretch", false, 0.15f, listOf(LifeProvider.FLOOR), LifeAnims.FLEX, 3f),
    WARM("Warm up", false, 0.5f, listOf(LifeProvider.FIREPLACE), LifeAnims.KNEEL, 5f),
    WANDER("Wander", false, 0.1f, listOf(LifeProvider.FLOOR)),
    CHAT("Chat", true, 0.4f, listOf(LifeProvider.PERSON)),
    JOKE("Tell a joke", true, 0.3f, listOf(LifeProvider.PERSON)),
    COMPLIMENT("Compliment", true, 0.2f, listOf(LifeProvider.PERSON)),
    HUG("Hug", true, 0.2f, listOf(LifeProvider.PERSON)),
    FLIRT("Flirt", true, 0.2f, listOf(LifeProvider.PERSON)),
    KISS("Kiss", true, 0.2f, listOf(LifeProvider.PERSON)),
    ARGUE("Argue", true, 0.2f, listOf(LifeProvider.PERSON)),
    INVITE_SIT("Invite to sit", true, 0.6f, listOf(LifeProvider.PERSON)),
    DANCE_TOGETHER("Dance together", true, 0.3f, listOf(LifeProvider.PERSON)),
    GIVE_GIFT("Give a gift", true, 0.2f, listOf(LifeProvider.PERSON)),
    PLAY_TOGETHER("Spend time together", true, 0.3f, listOf(LifeProvider.PERSON));

    /** What it gives each need a game hour while it lasts (by [Need] ordinal; negative: it costs). */
    val gains: FloatArray get() = GAINS[ordinal]

    companion object {
        /** The player's menu on an NPC. */
        val SOLO_MENU = listOf(REST, EAT, DRINK, READ, DANCE, WASH, WANDER)
        /** The player's menu from an NPC to another. */
        val SOCIAL_MENU = listOf(CHAT, JOKE, COMPLIMENT, HUG, FLIRT, KISS, ARGUE, INVITE_SIT, DANCE_TOGETHER, GIVE_GIFT)

        /** Gains by need order: energy, hunger, thirst, social, fun, hygiene, comfort. */
        private fun g(e: Float = 0f, h: Float = 0f, t: Float = 0f, s: Float = 0f, f: Float = 0f, y: Float = 0f, c: Float = 0f) =
            floatArrayOf(e, h, t, s, f, y, c)

        private val GAINS: List<FloatArray> = entries.map {
            when (it) {
                SLEEP -> g(e = 0.16f, c = 0.4f)
                NAP -> g(e = 0.11f, c = 0.35f)
                REST -> g(e = 0.1f, c = 0.6f)
                RELAX -> g(e = 0.03f, f = 0.12f, c = 0.9f)
                EAT -> g(h = 2.4f)
                DRINK -> g(t = 3f)
                READ -> g(f = 0.55f, c = 0.1f)
                DANCE -> g(e = -0.12f, s = 0.1f, f = 1.1f, c = -0.2f)
                WASH -> g(y = 2.5f, c = 0.2f)
                TIDY -> g(y = 0.9f)
                STRETCH -> g(e = 0.05f, c = 0.7f)
                WARM -> g(f = 0.2f, c = 1f)
                WANDER -> g(f = 0.05f, c = -0.05f)
                CHAT -> g(s = 1.4f, f = 0.25f)
                JOKE -> g(s = 1.3f, f = 0.6f)
                COMPLIMENT, GIVE_GIFT, FLIRT -> g(s = 1.3f, f = 0.2f)
                ARGUE -> g(s = 0.4f, f = -0.3f, c = -0.3f)
                HUG, KISS, PLAY_TOGETHER -> g(s = 1.2f, f = 0.6f)
                DANCE_TOGETHER -> g(e = -0.1f, s = 1f, f = 1f)
                INVITE_SIT -> g(s = 0.5f, f = 0.12f, c = 0.9f)
            }
        }

        fun of(name: String?): InteractionId? = entries.firstOrNull { it.name == name }
    }
}

/**
 * How intimate a two-person animation is (what a relationship must reach before NPCs pick it by themselves: [LifeSim]).
 * The player's own Play animation and Send to partner are never held back by it.
 */
enum class Intimacy { CASUAL, FRIENDLY, ROMANTIC, INTIMATE }

/** A two-person animation's [Intimacy], by its name; the Warcraft emotes by theirs. */
object PairKinds {
    private val CASUAL = listOf("wave", "cheer", "laugh", "bow", "applaud", "salute", "high five", "highfive", "greet",
        "handshake", "shake hands", "talk", "chat", "toast", "clap")
    private val FRIENDLY = listOf("hug", "dance", "waltz", "piggyback", "pat", "tickle", "play", "pillow", "spin", "twirl")
    private val ROMANTIC = listOf("kiss", "embrace", "cuddle", "snuggle", "smooch", "romance", "romantic", "slow dance",
        "hold hands", "holding hands", "caress", "nuzzle", "spoon", "carry", "propose", "love")

    /** Hugs: [InteractionId.HUG]'s pairs. */
    val HUG_WORDS = listOf("hug", "embrace", "cuddle")
    val DANCE_WORDS = listOf("dance", "waltz", "twirl", "spin")
    val KISS_WORDS = listOf("kiss", "smooch")

    /**
     * A name's intimacy: romantic words first (a "slow dance" is romantic), then friendly, then casual; a library pair named
     * otherwise is taken as [Intimacy.INTIMATE] (the libraries' unnamed pairs are mostly lovers'), never casual by default.
     */
    fun of(name: String): Intimacy {
        val n = name.lowercase()
        return when {
            ROMANTIC.any { it in n } -> Intimacy.ROMANTIC
            FRIENDLY.any { it in n } -> Intimacy.FRIENDLY
            CASUAL.any { it in n } -> Intimacy.CASUAL
            else -> Intimacy.INTIMATE
        }
    }

    /** A Warcraft emote's intimacy (the fallback both play: wave, cheer... casual; dance friendly; kiss romantic). */
    fun ofEmote(anim: Int): Intimacy = when (anim) {
        LifeAnims.DANCE -> Intimacy.FRIENDLY
        76 -> Intimacy.ROMANTIC
        else -> Intimacy.CASUAL
    }

    fun matches(name: String, words: List<String>): Boolean {
        val n = name.lowercase()
        return words.any { it in n }
    }
}
