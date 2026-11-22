package com.logie.wifecraft.dialogue

/** Words and turns of speech that don't belong to Azeroth. A line with one is regenerated once, then dropped. */
object Anachronisms {
    private val WORDS = listOf(
        "okay", "ok", "o\\.k\\.", "guys", "dude", "bro", "awesome", "yeah", "yep", "nope", "lol", "omg", "wow", "whatever", "totally", "literally",
        "internet", "email", "e-mail", "phone", "smartphone", "computer", "laptop", "wifi", "wi-fi", "tv", "television", "movie", "movies", "youtube",
        "netflix", "tinder", "facebook", "google", "microsoft", "iphone", "uber", "pizza", "coffee", "weekend", "selfie", "boyfriend", "girlfriend",
        "gym", "america", "american", "europe", "robot", "app", "apps", "online", "download",
        "software", "hashtag", "emoji", "vibe", "vibes", "hang out", "hanging out", "no worries", "my bad", "sucks", "dating app",
        "player", "players", "npc", "npcs", "quest log", "menu", "cinematic", "ai", "chatbot", "language model", "simulation",
    )
    private val SLANG = Regex("(^|[.!?]\\s+)cool\\b|\\b(that's|that is|so|pretty|really|very) cool\\b", RegexOption.IGNORE_CASE)
    private val WORD_RE = Regex("(?<![\\p{L}\\p{N}'])(${WORDS.joinToString("|")})(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    /** The first offending token in [line], or null. */
    fun find(line: String): String? {
        WORD_RE.find(line)?.let { return it.value }
        SLANG.find(line)?.let { return it.value.trim() }
        return null
    }

    fun clean(line: String) = find(line) == null
}

/**
 * Turns the model's raw answer into one spoken line: the speaker's name tag, quotation marks, actions in asterisks or brackets
 * stripped, at most [MAX_SENTENCES] sentences and [MAX_CHARS] characters, cut at a sentence end (or a word) and never
 * mid-sentence with nothing to close it. Null: nothing speakable.
 */
object LineCleaner {
    const val MAX_SENTENCES = 2
    const val MAX_CHARS = 180

    fun clean(raw: String?, speakerName: String? = null, maxChars: Int = MAX_CHARS): String? {
        var s = raw ?: return null
        s = s.replace(Regex("\\*[^*]*\\*"), " ").replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\[[^\\]]*]"), " ")
        s = s.trim().lines().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (speakerName != null) {
            val first = speakerName.substringBefore(' ')
            s = s.replace(Regex("^(${Regex.escape(speakerName)}|${Regex.escape(first)})\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE), "")
        }
        s = s.replace(Regex("^[\"“”'‘’]+|[\"“”]+$"), "").replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return null
        // sentences
        val parts = Regex("[^.!?…]+[.!?…]+[\"”']?|[^.!?…]+$").findAll(s).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        val out = StringBuilder()
        var n = 0
        for (p in parts) {
            if (n >= MAX_SENTENCES) break
            val add = if (out.isEmpty()) p else " $p"
            if (out.length + add.length > maxChars) {
                if (out.isEmpty()) { // one long sentence: cut at a word
                    val cut = p.take(maxChars - 1).substringBeforeLast(' ', p.take(maxChars - 1)).trimEnd(',', ';', ':', ' ')
                    out.append(cut).append("...")
                }
                break
            }
            out.append(add); n++
        }
        val line = out.toString().trim()
        return if (line.length < 2) null else line
    }
}

/**
 * The line between talking about sex and sexual live vocalising. Talk about desire, bodies, memories, plans and positions
 * is allowed in any talk (the NPC's nature and the comfort band decide how much); the sounds and the running commentary of
 * what is happening right now ("oh yeah", "harder", "don't stop", "I'm coming", "you're inside me") belong to a sexual stage that
 * is playing. [rewrite] strips them from a line spoken outside one; what is left is kept when it still says something.
 */
object LiveFilter {
    private val PATTERNS: List<Regex> = listOf(
        "\\b(o+h+|a+h+|m+h*m+|u+h+|o+o+h+)\\b[,!.…\\s]*(o+h+|a+h+|m+m*|yes|yeah|baby|gods?)?",
        "\\boh,? (yes|yeah|baby|gods?|my gods?|Light)\\b",
        "\\b(harder|faster|deeper)\\b[,!.]?",
        "\\bdon'?t (you )?(stop|dare stop)\\b",
        "\\bright there\\b",
        "\\bi'?m (coming|cumming)\\b",
        "\\byou'?re (inside|filling|touching|licking|sucking|thrusting|stroking|riding|taking)\\b[^.!?]*",
        "\\bi(?:'m| am) (coming|cumming)\\b",
        "\\b(yes,? )?yes,? yes\\b",
        "\\bmore[,!]\\s*(more)?",
        "\\b(fuck|take) me( now| harder)?\\b",
        "\\b(there|that)'?s? it,? (baby|love|right there)\\b",
        "\\bkeep going\\b",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun isLive(line: String): Boolean = PATTERNS.any { it.containsMatchIn(line) }

    /** [line] with the live vocalisation removed; null when nothing worth saying is left. */
    fun rewrite(line: String): String? {
        var s = line
        for (p in PATTERNS) s = p.replace(s, " ")
        s = s.replace(Regex("\\s+([,.!?])"), "$1").replace(Regex("^[,.!?\\s-]+"), "").replace(Regex("\\s+"), " ").trim()
        return if (s.split(' ').count { it.any(Char::isLetter) } >= 3) s else null
    }
}
