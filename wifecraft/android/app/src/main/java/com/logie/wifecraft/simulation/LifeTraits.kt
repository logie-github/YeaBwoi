package com.logie.wifecraft.simulation

import kotlin.random.Random

/**
 * Who an NPC is in the life simulation: three [Trait]s, given once from their id, race and sex ([Traits.assign]: the same
 * NPC always gets the same ones), changing how fast their needs fall, what they like doing and talking about, and how well
 * they get on with others ([Traits.compatibility]). A few lean by race, lightly (a dwarf more often loves a drink, a night
 * elf keeps the night, a tauren is calm); sex never decides one. Pure JVM.
 */
enum class Trait(val label: String, val description: String, val icon: String) {
    EARLY_BIRD("Early Bird", "Up with the sun, abed soon after dark.", "trait_early_bird"),
    NIGHT_OWL("Night Owl", "Comes alive when the stars are out.", "trait_night_owl"),
    GREGARIOUS("Social", "Never happier than in company.", "trait_social"),
    LONER("Loner", "Likes a quiet corner and fewer words.", "trait_loner"),
    GLUTTON("Hearty Appetite", "A good meal and a better ale.", "trait_glutton"),
    ASCETIC("Ascetic", "Needs little and wants less.", "trait_ascetic"),
    ROMANTIC("Romantic", "Falls for kind words and moonlight.", "trait_romantic"),
    PLAYFUL("Playful", "Always up for a joke or a dance.", "trait_playful"),
    BOOKISH("Bookish", "Happiest with a book and a candle.", "trait_bookish"),
    BRAVE("Brave", "Unshaken, and a little restless sitting still.", "trait_brave"),
    MISCHIEVOUS("Mischievous", "Loves a prank and a piece of gossip.", "trait_mischievous"),
    CALM("Calm", "Slow to anger, easy to be around.", "trait_calm");

    /** How fast [need] falls for someone with this trait (1: as everyone's). */
    fun decay(need: Need): Float = when (this) {
        GLUTTON -> when (need) { Need.HUNGER -> 1.3f; Need.THIRST -> 1.15f; else -> 1f }
        ASCETIC -> when (need) { Need.HUNGER -> 0.8f; Need.FUN -> 0.9f; Need.COMFORT -> 0.85f; else -> 1f }
        GREGARIOUS -> if (need == Need.SOCIAL) 1.35f else 1f
        LONER -> if (need == Need.SOCIAL) 0.6f else 1f
        PLAYFUL -> if (need == Need.FUN) 1.25f else 1f
        CALM -> when (need) { Need.COMFORT -> 0.85f; Need.FUN -> 0.9f; else -> 1f }
        BRAVE -> if (need == Need.COMFORT) 0.85f else 1f
        BOOKISH -> if (need == Need.FUN) 1.1f else 1f
        else -> 1f
    }

    /** How much more (or less) this trait wants [id] (1: no opinion). */
    fun likes(id: InteractionId): Float = when (this) {
        GLUTTON -> when (id) { InteractionId.EAT -> 1.4f; InteractionId.DRINK -> 1.5f; else -> 1f }
        ASCETIC -> when (id) { InteractionId.EAT, InteractionId.DRINK -> 0.85f; InteractionId.STRETCH -> 1.4f; else -> 1f }
        BOOKISH -> when (id) { InteractionId.READ -> 1.8f; InteractionId.DANCE -> 0.8f; else -> 1f }
        PLAYFUL -> when (id) { InteractionId.DANCE, InteractionId.JOKE, InteractionId.DANCE_TOGETHER -> 1.6f
            InteractionId.READ -> 0.85f; else -> 1f }
        ROMANTIC -> when (id) { InteractionId.FLIRT, InteractionId.KISS, InteractionId.COMPLIMENT, InteractionId.GIVE_GIFT -> 1.6f
            InteractionId.INVITE_SIT -> 1.3f; else -> 1f }
        GREGARIOUS -> if (id.social) 1.3f else 1f
        LONER -> when { id.social -> 0.6f; id == InteractionId.READ || id == InteractionId.WANDER -> 1.2f; else -> 1f }
        MISCHIEVOUS -> when (id) { InteractionId.JOKE -> 1.5f; InteractionId.ARGUE -> 1.3f; else -> 1f }
        CALM -> when (id) { InteractionId.WARM, InteractionId.RELAX, InteractionId.REST -> 1.3f; InteractionId.ARGUE -> 0.4f; else -> 1f }
        BRAVE -> when (id) { InteractionId.STRETCH, InteractionId.WANDER, InteractionId.DANCE -> 1.25f; InteractionId.RELAX -> 0.85f; else -> 1f }
        EARLY_BIRD, NIGHT_OWL -> 1f
    }

    /** The conversation topics ([Chatter]'s names) this trait enjoys. */
    val topics: Set<String> get() = when (this) {
        BOOKISH -> setOf("magic", "embassy")
        GLUTTON -> setOf("food", "tavern")
        ASCETIC -> setOf("weather", "road")
        MISCHIEVOUS -> setOf("gossip", "jokes")
        PLAYFUL -> setOf("jokes", "tavern")
        BRAVE -> setOf("road", "work")
        CALM -> setOf("weather", "home")
        ROMANTIC -> setOf("home", "gossip")
        GREGARIOUS -> setOf("tavern", "gossip")
        LONER -> setOf("magic", "weather")
        EARLY_BIRD -> setOf("work", "weather")
        NIGHT_OWL -> setOf("magic", "road")
    }

    /** Topics this trait would rather not hear about. */
    val dislikes: Set<String> get() = when (this) {
        ASCETIC -> setOf("tavern", "food")
        LONER -> setOf("gossip")
        CALM -> setOf("gossip")
        BOOKISH -> setOf("tavern")
        else -> emptySet()
    }
}

object Traits {
    /** How many traits each NPC has. */
    const val PER_NPC = 3

    /** Traits that can't go together (and that rub each other the wrong way in two people). */
    val OPPOSITES: List<Pair<Trait, Trait>> = listOf(
        Trait.EARLY_BIRD to Trait.NIGHT_OWL, Trait.GREGARIOUS to Trait.LONER, Trait.GLUTTON to Trait.ASCETIC,
        Trait.PLAYFUL to Trait.CALM, Trait.MISCHIEVOUS to Trait.CALM,
    )

    fun opposed(a: Trait, b: Trait) = OPPOSITES.any { (x, y) -> (x == a && y == b) || (x == b && y == a) }

    /**
     * The traits a race leans to (ChrRaces ids; light touches only): dwarves and Dark Irons love their food and ale, night
     * elves and their kin keep the night, tauren and draenei are calm, gnomes read, goblins and vulpera scheme, trolls play,
     * orcs are brave, humans rise early, pandaren eat well.
     */
    fun leaning(race: Int): List<Trait> = when (race) {
        3, 34 -> listOf(Trait.GLUTTON)
        4, 27, 29 -> listOf(Trait.NIGHT_OWL)
        5, 22 -> listOf(Trait.NIGHT_OWL, Trait.LONER)
        6, 28 -> listOf(Trait.CALM)
        11, 30 -> listOf(Trait.CALM, Trait.BRAVE)
        7, 37 -> listOf(Trait.BOOKISH)
        9, 35 -> listOf(Trait.MISCHIEVOUS)
        8, 31 -> listOf(Trait.PLAYFUL)
        2, 36 -> listOf(Trait.BRAVE)
        1, 32 -> listOf(Trait.EARLY_BIRD)
        10 -> listOf(Trait.ROMANTIC, Trait.BOOKISH)
        24, 25, 26 -> listOf(Trait.GLUTTON, Trait.CALM)
        else -> emptyList()
    }

    /** [id]'s traits: the same for the same id, race and sex; one of the race's leanings more often than not. */
    fun assign(id: String, race: Int, female: Boolean?): List<Trait> {
        var h = 1125899906842597L
        for (c in id) h = 31 * h + c.code
        h = h * 31 + race
        h = h * 31 + when (female) { true -> 2; false -> 1; null -> 0 }
        // mixed (splitmix64) so ids that differ by a letter don't start their draws alike
        h = (h xor (h ushr 30)) * -4658895280553007687L
        h = (h xor (h ushr 27)) * -7723592293110705685L
        h = h xor (h ushr 31)
        val random = Random(h)
        val out = ArrayList<Trait>(PER_NPC)
        val lean = leaning(race)
        if (lean.isNotEmpty() && random.nextFloat() < LEAN_CHANCE) out += lean[random.nextInt(lean.size)]
        val all = Trait.entries
        var guard = 0
        while (out.size < PER_NPC && guard++ < 100) {
            val t = all[random.nextInt(all.size)]
            if (t !in out && out.none { opposed(it, t) }) out += t
        }
        return out
    }

    /**
     * How well two people get on (-0.5..0.6): each trait they share +0.15, each opposite pair between them -0.2, a romantic
     * and a playful, a bookish and a calm, a brave and a mischievous get on a little better.
     */
    fun compatibility(a: List<Trait>, b: List<Trait>): Float {
        var c = 0f
        for (t in a) if (t in b) c += 0.15f
        for (x in a) for (y in b) if (opposed(x, y)) c -= 0.2f
        fun both(x: Trait, y: Trait) = (x in a && y in b) || (y in a && x in b)
        if (both(Trait.ROMANTIC, Trait.PLAYFUL)) c += 0.05f
        if (both(Trait.BOOKISH, Trait.CALM)) c += 0.05f
        if (both(Trait.BRAVE, Trait.MISCHIEVOUS)) c += 0.05f
        return c.coerceIn(-0.5f, 0.6f)
    }

    /** The chance an NPC's first trait is one of its race's leanings. */
    const val LEAN_CHANCE = 0.6f
}
