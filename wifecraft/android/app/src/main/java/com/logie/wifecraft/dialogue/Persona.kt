package com.logie.wifecraft.dialogue

/**
 * How an NPC carries themself, worked out from the traits chosen at creation (the life simulation's Trait names), the bio
 * and the stated preferences; each axis -1..1. It tunes the voice, how fast the NPC loosens with a partner ([speed]), how
 * readily they hesitate ([WillingnessModel]) and how they take to being persuaded. Pure JVM.
 */
data class Disposition(
    val caution: Float = 0f,
    val warmth: Float = 0f,
    val boldness: Float = 0f,
    val prejudice: Float = 0f,
    val curiosity: Float = 0f,
    val devout: Float = 0f,
) {
    /** shy, bold, tender, dominant or natural: how they sound in intimate talk. */
    val style: String get() = when {
        boldness >= 0.5f && warmth < 0.2f -> "dominant"
        boldness >= 0.4f -> "bold"
        caution >= 0.3f && boldness < 0.2f -> "shy"
        warmth >= 0.4f -> "tender"
        else -> "natural"
    }

    /** How fast they loosen with a partner (0.5 slow .. 1.6 fast). */
    val speed: Float get() = (1f + boldness * 0.6f - caution * 0.5f + curiosity * 0.1f).coerceIn(0.5f, 1.6f)

    /** How readily they bring up desire in ordinary talk (0..1). */
    val desireTalk: Float get() = (0.3f + boldness * 0.35f + warmth * 0.1f + curiosity * 0.1f - caution * 0.3f - devout * 0.2f).coerceIn(0.03f, 0.9f)

    companion object {
        private fun t(name: String, vararg w: Pair<Char, Float>): Map<String, Map<Char, Float>> = mapOf(name to w.toMap())
        // axes: c caution, w warmth, b boldness, p prejudice, u curiosity, d devout
        private val TRAITS: Map<String, Map<Char, Float>> = mapOf(
            "CALM" to mapOf('c' to .2f, 'w' to .3f),
            "LONER" to mapOf('c' to .4f, 'w' to -.3f, 'b' to -.3f, 'p' to .2f),
            "ASCETIC" to mapOf('c' to .4f, 'w' to -.1f, 'u' to -.2f, 'p' to .3f, 'd' to .3f),
            "BOOKISH" to mapOf('c' to .2f, 'b' to -.2f, 'u' to .5f),
            "EARLY_BIRD" to mapOf('c' to .1f, 'p' to .1f),
            "NIGHT_OWL" to mapOf('u' to .2f),
            "BRAVE" to mapOf('c' to -.5f, 'b' to .6f),
            "PLAYFUL" to mapOf('c' to -.3f, 'w' to .2f, 'b' to .3f, 'u' to .2f),
            "MISCHIEVOUS" to mapOf('c' to -.3f, 'b' to .4f, 'u' to .3f),
            "GREGARIOUS" to mapOf('c' to -.2f, 'w' to .3f, 'b' to .2f, 'p' to -.2f),
            "ROMANTIC" to mapOf('c' to -.2f, 'w' to .5f, 'u' to .1f),
            "GLUTTON" to mapOf('b' to .1f),
        )
        private val BIO_WORDS: List<Pair<Regex, Map<Char, Float>>> = listOf(
            Regex("\\b(devout|pious|faithful|priest|chaste|virtuous)\\b", RegexOption.IGNORE_CASE) to mapOf('d' to .4f, 'c' to .15f),
            Regex("\\b(prejudiced|bigot|distrusts?|hates?|xenophob\\w*)\\b", RegexOption.IGNORE_CASE) to mapOf('p' to .4f),
            Regex("\\b(shy|timid|nervous|reserved|cautious)\\b", RegexOption.IGNORE_CASE) to mapOf('c' to .35f, 'b' to -.2f),
            Regex("\\b(bold|brazen|fearless|reckless)\\b", RegexOption.IGNORE_CASE) to mapOf('b' to .35f, 'c' to -.2f),
            Regex("\\b(kind|gentle|warm|caring|tender)\\b", RegexOption.IGNORE_CASE) to mapOf('w' to .3f, 'p' to -.15f),
            Regex("\\b(curious|adventurous|open-minded)\\b", RegexOption.IGNORE_CASE) to mapOf('u' to .35f),
        )

        /** [paceSlow]/[paceIntense]/[asksFirst]: the creator's preferences (Pace.SLOW, Pace.INTENSE, Initiation.ASK). */
        fun of(traits: List<String>, bio: String = "", paceSlow: Boolean = false, paceIntense: Boolean = false, asksFirst: Boolean = false): Disposition {
            val sum = HashMap<Char, Float>()
            fun add(m: Map<Char, Float>?) { m?.forEach { (k, v) -> sum[k] = (sum[k] ?: 0f) + v } }
            for (t in traits) add(TRAITS[t.uppercase()])
            for ((re, m) in BIO_WORDS) if (re.containsMatchIn(bio)) add(m)
            if (paceSlow) add(mapOf('c' to .15f))
            if (paceIntense) add(mapOf('b' to .2f))
            if (asksFirst) add(mapOf('c' to .1f))
            fun g(k: Char) = (sum[k] ?: 0f).coerceIn(-1f, 1f)
            return Disposition(g('c'), g('w'), g('b'), g('p'), g('u'), g('d'))
        }
    }
}

/** One NPC as the dialogue knows them. [raceId] 0: unknown (a creature); [classId] 0: none (a civilian). */
data class NpcProfile(
    val id: String,
    val name: String,
    val raceId: Int,
    val female: Boolean?,
    val classId: Int = 0,
    val traits: List<String> = emptyList(),
    val bio: String = "",
    val disposition: Disposition = Disposition.of(traits, bio),
    /** The id of the named character this NPC is (assets/characters.json), or null: a made-up NPC. Its profile and era state come from [PersonaKit]. */
    val named: String? = null,
) {
    val first: String get() = name.trim().substringBefore(' ').ifEmpty { name }
}

/**
 * The system prompt: the NPC as a person of Azeroth. Stable per NPC (nothing in it depends on the scene, the hour or who is
 * near), so the runtime can cache its prefix; everything that changes goes into [TurnPrompt]'s user message. It never names
 * what it forbids (the fourth wall is kept by what the prompt says exists, not by a list of what does not).
 */
object PersonaPrompt {
    /** The era the realm's story is set in, taken in the prompt as the present. */
    const val ERA = "The world is Azeroth in the years after the Third War and the Scourge, with the Horde and the Alliance uneasy neighbours and old powers stirring."

    /** The era line in force: the kit's era when the lore carries one ([Lore.kit]), else [ERA]. */
    fun era(lore: Lore): String = lore.kit?.era?.present ?: ERA

    private val TRAIT_TEXT = mapOf(
        "EARLY_BIRD" to "you rise with the sun and tire early", "NIGHT_OWL" to "you come alive after dark",
        "GREGARIOUS" to "you love company", "LONER" to "you like quiet corners and few words",
        "GLUTTON" to "you love a good meal and a better ale", "ASCETIC" to "you need little and want less",
        "ROMANTIC" to "you fall for kind words and moonlight", "PLAYFUL" to "you are always up for a joke or a dance",
        "BOOKISH" to "you are happiest with a book and a candle", "BRAVE" to "you are unshaken and restless sitting still",
        "MISCHIEVOUS" to "you love a prank and a piece of gossip", "CALM" to "you are slow to anger and easy to be around",
    )

    fun system(p: NpcProfile, lore: Lore): String {
        val race = lore.race(p.raceId)
        val sex = when (p.female) { true -> "woman"; false -> "man"; null -> "person" }
        val calling = lore.className(p.classId)?.let { " $it" } ?: ""
        val b = StringBuilder()
        b.append("You are ${p.name}, a ${race?.name ?: "traveller"}$calling $sex of Azeroth, a full-grown adult. ").append(era(lore)).append('\n')
        val tr = p.traits.mapNotNull { TRAIT_TEXT[it.uppercase()] }
        if (tr.isNotEmpty()) b.append("Your nature: ").append(tr.joinToString("; ")).append(".\n")
        if (p.bio.isNotBlank()) b.append("About you: ").append(p.bio.trim()).append('\n')
        if (race != null) b.append("Your voice: ").append(race.register).append(". ")
        b.append("In love and in bed you are ").append(p.disposition.style).append(".\n")
        b.append(RULES).append('\n')
        lore.race(p.raceId)?.let { b.append("Your people: ").append(it.about).append(' ').append(it.customs).append('\n') }
        lore.className(p.classId)?.let { b.append("Your calling: ").append(lore.classAbout(p.classId)).append('\n') }
        lore.factionAbout(lore.faction(p.raceId)).takeIf { it.isNotEmpty() }?.let { b.append(it).append('\n') }
        lore.kit?.let { b.append(it.systemAddition(p, lore)).append('\n') }   // the character core and the era slice (PersonaKit)
        return b.toString().trim()
    }

    /** The rules every NPC lives by, in the prompt's own terms. */
    const val RULES = "You are a real person living in this world; this is your whole life and you know nothing beyond it. " +
        "Speak in the first person, in your own voice, as people of your time and land do: gold, ale, the Light, the Horde and the Alliance, Orgrimmar and Stormwind. " +
        "Use no modern speech, slang, machines, brands or customs of a distant age. " +
        "Anything beyond Azeroth, or talk of unseen hands moving you, leaves you honestly puzzled; answer it as someone who has simply never heard of such a thing. " +
        "Speak in short natural lines, one or two sentences. Say only the words you speak aloud: no narration, no stage directions, no quotation marks, no name tags. " +
        "You decide for yourself what you want and whether you will; you usually say yes to what you desire."
}
