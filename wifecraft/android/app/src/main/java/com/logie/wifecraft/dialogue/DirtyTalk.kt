package com.logie.wifecraft.dialogue

import org.json.JSONObject

/*
 * Dirty talk: how filthy the NPCs' words are, one seven-step level for JOI mode, sex-stage banter and Chat flirting. The level
 * sets the register (the style and the vocabulary the prompt asks for, the few-shot lines it shows, the sampling); the comfort
 * band never blocks it in JOI mode. Only the fourth wall and the anachronisms are filtered; no explicit word is refused at any
 * level. For adult (18+) fictional characters only: every speaker and listener is a full-grown adult of Azeroth.
 * Pure Kotlin plus org.json, so the JVM tests run it.
 */

/** Cute .. WTF??!, in the order the slider shows them. */
enum class DirtyLevel(val key: String, val label: String, val blurb: String) {
    CUTE("cute", "Cute", "Giggly and teasing, pet names, light innuendo"),
    MILD("mild", "Mild", "Flirty, suggestive and sensual"),
    SPICY("spicy", "Spicy", "Explicit and dirty"),
    HOT("hot", "Hot", "Graphic, commanding and possessive"),
    NASTY("nasty", "Nasty", "The crudest words, degradation and humiliation"),
    DEPRAVED("depraved", "Depraved", "Extreme, perverse and shocking"),
    WTF("wtf", "WTF??!", "Ten times the nastiest you can imagine");

    /** From Spicy up the words are the plain explicit ones and the wellness blocklist applies. */
    val explicit: Boolean get() = this >= SPICY
    /** From Nasty up: the crudest vocabulary, degradation, humiliation. */
    val crude: Boolean get() = this >= NASTY

    /**
     * The kink pack's comfort band a JOI of this level is planned and asked with: the longer, bolder plan (more edges, a count
     * from ten) from Nasty up. It only ever raises what the prompt may use; it never lowers the level.
     */
    val joiBand: Band get() = if (this >= NASTY) Band.UNINHIBITED else Band.INTIMATE

    /** The most words a JOI line may have at this level (the angrier, the longer a rant runs). */
    val joiWords: Int get() = when (this) { CUTE, MILD -> 30; SPICY -> 32; HOT -> 36; NASTY -> 40; DEPRAVED -> 44; WTF -> 48 }

    /** What the order is asked to be, from Spicy up (the last words of the task). */
    val taskTail: String get() = when (this) {
        DEPRAVED -> "crude, perverse and shameless, as depraved as you can"
        WTF -> "obscene beyond all limits, ten times filthier than anything before, wildly over the top"
        else -> "crude and plain"
    }

    /** Tokens a JOI line may take. */
    val joiTokens: Int get() = (joiWords * 2.3f).toInt() + 12

    /** Sampling: livelier the further up; the repeat penalty stays low (the sampler's window is the last 64 tokens, where the crude words of the lines she has said sit: a high one pushes her to poetry). */
    val temperature: Float get() = when (this) { CUTE, MILD -> 0.8f; SPICY, HOT -> 0.85f; NASTY -> 0.9f; DEPRAVED -> 0.95f; WTF -> 1.0f }
    val topP: Float get() = when (this) { CUTE, MILD, SPICY, HOT -> 0.93f; NASTY -> 0.95f; DEPRAVED, WTF -> 0.96f }
    val repeatPenalty: Float get() = when (this) { CUTE, MILD -> 1.08f; else -> 1.04f }

    companion object {
        /** What Options' Dirty talk level starts as (and JOI's first level). */
        val DEFAULT = HOT

        fun of(key: String?): DirtyLevel? = entries.firstOrNull { it.key == key?.trim()?.lowercase() || it.name.equals(key?.trim(), true) }
        fun ofOrDefault(key: String?): DirtyLevel = of(key) ?: DEFAULT
    }
}

/**
 * The few-shot lines of every level and stage family ([DirtyLevel] x [families]), from assets/dialogue/kink/dirty.json. A line that
 * starts with "f|" or "m|" is only for a woman or a man speaking. The model writes its own words from them; they carry most of
 * the register for a small model.
 */
class DirtyPack(private val lines: Map<DirtyLevel, Map<String, List<String>>>, private val family: Map<String, String>) {
    /** The family a JOI stage ("setup", "hold", ...) or a talk kind ("live", "chat") draws its lines from. */
    fun familyOf(stage: String): String = family[stage] ?: stage

    /** Every line of [level] for [stage]'s family, as written (the sex marks kept). */
    fun all(level: DirtyLevel, stage: String): List<String> = lines[level]?.get(familyOf(stage)).orEmpty()

    /**
     * Up to [n] lines of [level] for [stage], those of the speaker's sex ([female] null: only the ones for anyone), starting at a place
     * the [seed] picks and going round, so each ask shows other lines and the same ask the same ones.
     */
    fun examples(level: DirtyLevel, stage: String, n: Int, female: Boolean?, seed: Int = 0): List<String> {
        val ok = all(level, stage).mapNotNull { l ->
            when {
                l.startsWith("f|") -> if (female == true) l.substring(2) else null
                l.startsWith("m|") -> if (female == false) l.substring(2) else null
                else -> l
            }
        }
        if (ok.isEmpty() || n <= 0) return emptyList()
        val start = Math.floorMod(seed, ok.size)
        return List(minOf(n, ok.size)) { ok[(start + it) % ok.size] }
    }

    companion object {
        fun parse(text: String): DirtyPack {
            val o = JSONObject(text)
            val fam = o.optJSONObject("families")?.let { f -> f.keys().asSequence().associateWith { f.getString(it) } } ?: emptyMap()
            val lv = o.getJSONObject("levels")
            val out = LinkedHashMap<DirtyLevel, Map<String, List<String>>>()
            for (level in DirtyLevel.entries) {
                val ex = lv.optJSONObject(level.key)?.optJSONObject("examples") ?: continue
                out[level] = ex.keys().asSequence().associateWith { k -> ex.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } }
            }
            return DirtyPack(out, fam)
        }
    }
}

/** The words of the prompts for a level, and the checks that keep the lines in that register. */
object DirtyTalk {
    /** How she talks at [level]: the style. */
    fun style(level: DirtyLevel): String = when (level) {
        DirtyLevel.CUTE -> "Cute and playful, like a giggly, flirty e-girl: bubbly, teasing, sweetly bossy, with pet names (cutie, pet, silly) and little hehes. Cheeky innuendo, never harsh."
        DirtyLevel.MILD -> "Warm, confident and flirty: sensual and suggestive, direct about what you want and what they must do, never crude."
        DirtyLevel.SPICY -> "Dirty and explicit, seductive and teasing: you order them to stroke, you say what you want to do to them, in plain sexual words."
        DirtyLevel.HOT -> "Graphic, commanding and possessive: you are in total control, praising them with filth and barking short hard orders."
        DirtyLevel.NASTY -> "Crude, degrading and relentless: you mock them, humiliate them and call them filthy names, and you enjoy making them desperate."
        DirtyLevel.DEPRAVED -> "Extreme, perverse and shocking, as far as an adult fantasy goes: total ownership and debasement, denied and ruined orgasms, begging, worship, cum play, shameless filth no one says in daylight."
        DirtyLevel.WTF -> "The absolute extreme, ten times beyond the nastiest thing you can imagine: relentless, over the top, absurdly intense; every line escalates past the last, with wild exaggeration and the most obscene talk you can invent. No limit, no pause, no softness."
    }

    /** The words she uses at [level]. */
    fun vocab(level: DirtyLevel): String = when (level) {
        DirtyLevel.CUTE -> "Euphemism and teasing: down there, your little friend, naughty, make a mess. Nothing crude or harsh."
        DirtyLevel.MILD -> "Touch yourself, hard, wet, turned on, finish, your body. No crude words and no insults."
        DirtyLevel.SPICY -> "The plain explicit words: cock, pussy, tits, ass, cum, hard, wet, stroke, fuck. Direct and sexual in every line."
        DirtyLevel.HOT -> "Explicit and graphic: cock, cunt, pussy, tits, ass, balls, cum, fuck, dripping, throbbing. Say exactly what they are doing and what you would do to them."
        DirtyLevel.NASTY -> "The crudest words you know: cock, cunt, cum, slut, whore, bitch, fuck, spit, balls. Degradation and humiliation in every line."
        DirtyLevel.DEPRAVED -> "Every crude word is allowed and expected, and perverse talk of ownership, degradation, ruin and humiliation. Shock them in every line."
        DirtyLevel.WTF -> "Every crude word, stacked and shouted, and wild exaggerations (floods, gods, armies, whole worlds): pure obscene excess."
    }

    /** The register block for a prompt: style, then vocabulary. */
    fun register(level: DirtyLevel): String = "How you talk: ${style(level)} Words: ${vocab(level)} ${plain(level)}"

    /** Keeps a small model off poetry (it likes similes and lullabies): short, literal, in character. */
    fun plain(level: DirtyLevel): String =
        if (level.explicit) "Blunt and literal, like a filthy voice-over: no poetry, no similes, no metaphors, no soft or dreamy talk; say the crude thing itself. The filth is sexual (words, acts, degradation), never violence or threats of harm. No emojis."
        else "Short and playful: no poetry, no long similes. No emojis."

    /** The few-shot block: the lines, one a row. */
    fun examplesBlock(lines: List<String>, heading: String = "Lines in this voice for the moment (for the feel; never copy them, say something new):"): String =
        if (lines.isEmpty()) "" else heading + "\n" + lines.joinToString("\n") { "- $it" } + "\n"

    // ---- the wellness blocklist ----

    /**
     * Calming, breathing, wellness-guide and therapy language: wrong for a filthy lover giving orders. From Spicy up a line with any of
     * it is asked for again once, and what is left of it after is cut out ([stripWellness]).
     */
    val WELLNESS = Regex(
        "\\b(breath\\w*|inhal\\w*|exhal\\w*|stillness|mindful\\w*|relax\\w*|doing (so |just |really )?(beautifully|wonderfully|great|well|amazing|fine)|" +
            "let your body|your body (knows|wants to|will)|settle into|sink into|melt into|lean into|soften into|ground(ing)? yourself|be present|safe space|" +
            "check(ing)? in|sensations?|shift your weight)\\b", RegexOption.IGNORE_CASE)

    fun wellness(text: String): Boolean = WELLNESS.containsMatchIn(text)

    /** Whether [text] is refused at [level]: wellness language from Spicy up (Cute and Mild may be sweet and soft). */
    fun refused(text: String, level: DirtyLevel): Boolean = level.explicit && wellness(text)

    /** [text] without its sentences that hold wellness language; null when nothing worth saying (three words) is left. */
    fun stripWellness(text: String): String? {
        val parts = Regex("[^.!?…]+[.!?…]*").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() && !wellness(it) }.toList()
        val s = parts.joinToString(" ").trim()
        return if (s.split(' ').count { it.any(Char::isLetter) } >= 3) s else null
    }

    /** The retry note when a line was refused as wellness talk. */
    const val WELLNESS_NOTE = "Not like that: no breathing, calming or coaching talk. Say it as a filthy lover who gives orders and wants them to obey."

    /** The retry note when a line copied one of the shown lines. */
    const val COPY_NOTE = "Say something new, in the same voice."

    /** Whether [text] repeats one of the [shown] lines (nearly every word the same). */
    fun copies(text: String, shown: List<String>): Boolean {
        fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }.toSet()
        fun sentences(s: String) = Regex("[^.!?…]+").findAll(s).map { it.value.lowercase().replace(Regex("[^\\p{L}' ]"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.split(' ').size >= 3 }.toList()
        // half or more of its sentences are sentences of the shown lines word for word (a small model strings the examples together)
        val mine = sentences(text)
        val theirs = shown.flatMap { sentences(it) }.toSet()
        if (mine.isNotEmpty() && mine.count { it in theirs } * 2 >= mine.size && mine.any { it in theirs }) return true
        val a = words(text)
        if (a.size < 4) return false
        return shown.any { ex ->
            val b = words(ex)
            b.isNotEmpty() && a.intersect(b).size.toFloat() / maxOf(a.size, b.size) >= 0.75f
        }
    }

    // ---- the viewer's own steer ----

    const val STEER_MAX = 140

    /** A steer typed by the viewer, tidied: one line, no quotes, at most [STEER_MAX] characters; null when empty. */
    fun cleanSteer(text: String?): String? {
        val t = (text ?: return null).replace(Regex("[\"“”\\n\\r\\t]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        return if (t.length <= STEER_MAX) t else t.take(STEER_MAX).substringBeforeLast(' ', t.take(STEER_MAX)).trimEnd(',', ';', ' ')
    }

    /**
     * The steer as the prompt words it: something in the moment for her, never a reference to anyone outside the room. Worded about "her"
     * or "him" it is still an idea for her to say or do now, as herself.
     */
    fun steerHint(text: String): String =
        "This very moment something takes hold of you and comes out in this line: \"$text\". Take it as your own idea, say it as yourself to them (I and you), and do not mention that it was suggested."
}
