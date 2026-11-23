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

    /**
     * The persona prompt, laid out for the model's cache: [shared] first (the world and its rules, the same bytes for every NPC and
     * in every mode, so a new NPC starts from the prefix another NPC already read), then this NPC (stable for as long as they are in
     * the scene), then [LENGTH_AMBIENT] last. Nothing in it changes from line to line. How much of the lore it carries is capped
     * ([PEOPLE_CHARS], [FACTION_CHARS]): the race's and the side's paragraphs are read once per NPC, so they are short.
     */
    fun system(p: NpcProfile, lore: Lore, compact: Boolean = false): String = persona(p, lore, compact) + LENGTH_AMBIENT

    /**
     * [shared] and this NPC, without the closing line about how long a line is (chat and ambient talk each add their own).
     * [compact] (the Fast model): the rules in a third of the words, the people and the calling cut short, no side paragraph and the
     * character core and era slice held to [PersonaBudget.CORE_COMPACT] and [PersonaBudget.ERA_COMPACT] tokens; the persona then
     * stays under about 200 tokens.
     */
    fun persona(p: NpcProfile, lore: Lore, compact: Boolean = false): String {
        if (compact) return compactPersona(p, lore)
        val race = lore.race(p.raceId)
        val sex = when (p.female) { true -> "woman"; false -> "man"; null -> "person" }
        val calling = lore.className(p.classId)?.let { " $it" } ?: ""
        val b = StringBuilder()
        b.append(shared(lore)).append("\n\n")
        b.append("You are ${p.name}, a ${race?.name ?: "traveller"}$calling $sex of Azeroth, a full-grown adult.\n")
        val tr = p.traits.mapNotNull { TRAIT_TEXT[it.uppercase()] }
        if (tr.isNotEmpty()) b.append("Your nature: ").append(tr.joinToString("; ")).append(".\n")
        if (p.bio.isNotBlank()) b.append("About you: ").append(p.bio.trim()).append('\n')
        if (race != null) b.append("Your voice: ").append(race.register).append(". ")
        b.append("In love and in bed you are ").append(p.disposition.style).append(".\n")
        race?.let { b.append("Your people: ").append(cap("${it.about} ${it.customs}", PEOPLE_CHARS)).append('\n') }
        lore.className(p.classId)?.let { b.append("Your calling: ").append(lore.classAbout(p.classId)).append('\n') }
        lore.factionAbout(lore.faction(p.raceId)).takeIf { it.isNotEmpty() }?.let { b.append(cap(it, FACTION_CHARS)).append('\n') }
        lore.kit?.let { b.append(it.systemAddition(p, lore)).append('\n') }   // the character core and the era slice (PersonaKit)
        return b.toString()
    }

    const val PEOPLE_CHARS = 220
    const val FACTION_CHARS = 110
    const val PEOPLE_CHARS_COMPACT = 100
    const val CALLING_CHARS_COMPACT = 90
    const val BIO_CHARS_COMPACT = 140

    private fun compactPersona(p: NpcProfile, lore: Lore): String {
        val race = lore.race(p.raceId)
        val sex = when (p.female) { true -> "woman"; false -> "man"; null -> "person" }
        val calling = lore.className(p.classId)?.let { " $it" } ?: ""
        val b = StringBuilder()
        b.append(shared(lore, compact = true)).append("\n\n")
        b.append("You are ${p.name}, a ${race?.name ?: "traveller"}$calling $sex of Azeroth, a full-grown adult.\n")
        val tr = p.traits.mapNotNull { TRAIT_TEXT[it.uppercase()] }
        if (tr.isNotEmpty()) b.append("Your nature: ").append(tr.take(2).joinToString("; ")).append(".\n")
        if (p.bio.isNotBlank()) b.append("About you: ").append(cap(p.bio, BIO_CHARS_COMPACT)).append('\n')
        if (race != null) b.append("Your voice: ").append(race.register).append(". ")
        b.append("In love and in bed you are ").append(p.disposition.style).append(".\n")
        race?.let { b.append("Your people: ").append(cap("${it.about} ${it.customs}", PEOPLE_CHARS_COMPACT)).append('\n') }
        lore.className(p.classId)?.let { b.append("Your calling: ").append(cap(lore.classAbout(p.classId).orEmpty(), CALLING_CHARS_COMPACT)).append('\n') }
        lore.kit?.let { b.append(it.systemAddition(p, lore, compact = true)).append('\n') }
        return b.toString()
    }

    /** [text] cut at a sentence end (else a word) to at most [max] characters. */
    fun cap(text: String, max: Int): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.length <= max) return t
        val cut = t.take(max)
        val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("! "), cut.lastIndexOf("? "))
        if (end > max / 2) return cut.substring(0, end + 1)
        // no sentence end: at the last comma or semicolon in the second half, else at a word that does not leave a connecting word hanging
        val comma = maxOf(cut.lastIndexOf(", "), cut.lastIndexOf("; "))
        var out = if (comma > max / 2) cut.substring(0, comma) else cut.substringBeforeLast(' ', cut)
        while (true) {
            val last = out.substringAfterLast(' ', "").lowercase().trimEnd(',', ';', ':')
            if (last in DANGLING && out.contains(' ')) out = out.substringBeforeLast(' ') else break
        }
        return out.trimEnd(',', ';', ':', ' ') + "."
    }

    private val DANGLING = setOf("and", "the", "of", "a", "an", "to", "in", "with", "but", "or", "for", "by", "on", "at", "as", "that", "who", "from", "its", "their", "his", "her")

    /**
     * The world and the rules every NPC lives by, in the prompt's own terms, first in every system prompt: the era line in force
     * ([era]) and [RULES_CORE]. The same text for every NPC and every mode (for as long as the era is the same), so the model reads it
     * once and every later NPC borrows it.
     */
    fun shared(lore: Lore? = null, compact: Boolean = false): String = (if (lore != null) era(lore) else ERA) + "\n" + (if (compact) RULES_COMPACT else RULES_CORE)

    /** [RULES_CORE] in about 60 tokens instead of 120, for the small model. */
    const val RULES_COMPACT = "You are a real person of this world and know nothing beyond it. Speak in the first person, in your own voice, in the speech of your time, " +
        "with no modern words or things; what lies beyond Azeroth only puzzles you. Say only the words you speak aloud: no narration, quotes or name tags. " +
        "You decide what you want, and usually say yes to what you desire."

    /** The rules without the era and without the line about how long a line is (the closing line is the mode's own). */
    const val RULES_CORE = "You are a real person living in this world; this is your whole life and you know nothing beyond it. " +
        "Speak in the first person, in your own voice, as people of your time and land do: gold, ale, the Light, the Horde and the Alliance, Orgrimmar and Stormwind. " +
        "Use no modern speech, slang, machines, brands or customs of a distant age. " +
        "Anything beyond Azeroth, or talk of unseen hands moving you, leaves you honestly puzzled; answer it as someone who has simply never heard of such a thing. " +
        "Say only the words you speak aloud: no narration, no stage directions, no quotation marks, no name tags. " +
        "You decide for yourself what you want and whether you will; you usually say yes to what you desire."

    /** The closing line of an ambient NPC's persona (the length of a spoken line), and of a chat's. */
    const val LENGTH_AMBIENT = "Speak in short natural lines, one or two sentences."
    const val LENGTH_CHAT = "Speak in natural lines."
}
