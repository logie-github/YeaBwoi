package com.logie.wifecraft.anim

/** Which part of a staged animation a clip is: played once first, repeated, played once last. */
enum class StagePart { START, LOOP, END }

/**
 * One clip name read by [StageRules]: its [family] (the name with the part's word replaced by "#" and a numbered stage's
 * words taken out: the clips of one animation share it), its [part], and [word], the word that said so (several LOOP
 * clips of one family are played in its order).
 */
data class StageName(val family: String, val part: StagePart, val word: String, val strong: Boolean)

/** One staged animation found by [StageRules.group]: its clips in play order (one START at most, LOOPs, one END at most). */
data class StageGroup<T>(val family: String, val start: T?, val loops: List<T>, val end: T?) {
    val segments: List<Pair<StagePart, T>> get() =
        listOfNotNull(start?.let { StagePart.START to it }) + loops.map { StagePart.LOOP to it } +
            listOfNotNull(end?.let { StagePart.END to it })
    val members: List<T> get() = segments.map { it.second }
}

/**
 * The rules that join a pack's start, loop and end clips into one animation, read from the clips' names only (all data:
 * [WORDS], [NUMBERED_LOOP], [STAGE]). Names are split into words at '_', '-' and spaces, case aside. The word that says
 * the part is the last strong one (start, loop, end, stop, intro, outro...), else the last weak one (idle, regular,
 * anim1...); a numbered stage's "stage N" is left out of the family, so an author's `..._start_stage_1`, `..._loop_stage_2`
 * and `..._end_stage_3` are one animation. A family is a staged animation only with at least one LOOP and a START or an
 * END, never two STARTs or two ENDs, and every clip of it for the same people ([group]'s scope); anything else (a single
 * clip, a pose pack's `_set_1`/`_set_2`, an author's numbered stages without these words) stays as it was.
 *
 * Seen in the user's WickedWhims package (names only): `EA:a_money_makeItRain_start_x` / `_loop_x` / `_stop_x`,
 * `WickedWhims_CrampsPain_idle_start_x` / `idle_loop_x` / `idle_stop_x` (and the `_seated` ones), `Camera Recording
 * Start_x` / `Loop_x` / `Stop_x`, `lupobianco_ballerina_statue_start` / `_loop` / `_end`, `a_sexylunella_floordancing_intro_x`
 * / `idle_x`, `regular_x`, `anim1_x`...`anim5_x` / `outro_x` (and the `_y` actor's), and the handjob `start_stage_1` /
 * `loop_stage_2` / `end_stage_3` (its `end_stage_3_caught` is another ending: it stays a clip of its own).
 */
object StageRules {
    data class Word(val part: StagePart, val strong: Boolean)

    val WORDS: Map<String, Word> = mapOf(
        "start" to Word(StagePart.START, true), "begin" to Word(StagePart.START, true),
        "intro" to Word(StagePart.START, true), "enter" to Word(StagePart.START, true),
        "loop" to Word(StagePart.LOOP, true),
        "idle" to Word(StagePart.LOOP, false), "regular" to Word(StagePart.LOOP, false), "main" to Word(StagePart.LOOP, false),
        "end" to Word(StagePart.END, true), "stop" to Word(StagePart.END, true), "outro" to Word(StagePart.END, true),
        "exit" to Word(StagePart.END, true), "finish" to Word(StagePart.END, true),
    )

    /** A weak loop word with a number: anim1, dance2, loop3 ("loop3" is strong, as "loop"). */
    val NUMBERED_LOOP = Regex("(anim|dance|loop)(\\d+)")

    /** A numbered stage: the word "stage" and the number after it (2, 2b) are not part of the family. */
    const val STAGE = "stage"
    private val STAGE_NUMBER = Regex("\\d+[a-z]?")
    private val SPLIT = Regex("[_\\s-]+")

    /** [name]'s family and part, or null when no word of it says a part. */
    fun parse(name: String): StageName? {
        val all = name.trim().lowercase().split(SPLIT).filter { it.isNotEmpty() }
        val words = ArrayList<String>(all.size)
        var i = 0
        while (i < all.size) {
            if (all[i] == STAGE && i + 1 < all.size && STAGE_NUMBER.matches(all[i + 1])) { i += 2; continue }
            words += all[i]; i++
        }
        var at = -1
        var found: Word? = null
        for ((k, w) in words.withIndex()) {
            val word = WORDS[w] ?: NUMBERED_LOOP.matchEntire(w)?.let { m -> Word(StagePart.LOOP, m.groupValues[1] == "loop") } ?: continue
            // the last strong word wins; a weak one only while no strong one was seen
            if (word.strong || found?.strong != true) { at = k; found = word }
        }
        val part = found ?: return null
        if (words.size < 2) return null
        val family = words.mapIndexed { k, w -> if (k == at) "#" else w }.joinToString("_")
        return StageName(family, part.part, words[at], part.strong)
    }

    /**
     * The staged animations among [items]: each named by [nameOf], kept apart by [scopeOf] (who plays it: its number of
     * people, its package). Families that are not staged animations are left out (their items stay as they are).
     */
    fun <T> group(items: List<T>, nameOf: (T) -> String, scopeOf: (T) -> String = { "" }): List<StageGroup<T>> {
        val families = LinkedHashMap<String, MutableList<Pair<StageName, T>>>()
        for (item in items) {
            val parsed = parse(nameOf(item)) ?: continue
            families.getOrPut(scopeOf(item) + "\u0000" + parsed.family) { ArrayList() } += parsed to item
        }
        val out = ArrayList<StageGroup<T>>()
        for (members in families.values) {
            val starts = members.filter { it.first.part == StagePart.START }
            val ends = members.filter { it.first.part == StagePart.END }
            val loops = members.filter { it.first.part == StagePart.LOOP }
            if (loops.isEmpty() || (starts.isEmpty() && ends.isEmpty()) || starts.size > 1 || ends.size > 1) continue
            val ordered = loops.sortedWith(compareBy<Pair<StageName, T>>({ !it.first.strong }, { it.first.word != "idle" },
                { it.first.word.filter(Char::isLetter) }, { it.first.word.filter(Char::isDigit).toIntOrNull() ?: 0 }))
            out += StageGroup(members.first().first.family, starts.firstOrNull()?.second, ordered.map { it.second },
                ends.firstOrNull()?.second)
        }
        return out
    }
}

/** One part of a staged [AnimationChoice] ([AnimationChoice.segments]): its [part] and the clip(s) that play it. */
data class ChoiceSegment(val part: StagePart, val choice: AnimationChoice)

/** The id of a staged animation's entry: its first loop's own id after this. */
const val STAGED_PREFIX = "staged:"

/**
 * [choices] with each staged animation ([StageRules]) as ONE entry: the first loop's choice (its clips, roles, group, what it
 * needs) named for the whole and carrying every part in [AnimationChoice.segments]; its parts' own entries are left out.
 * Only playable choices with no segments of their own are joined, and only those for the same number of people.
 */
fun foldStages(choices: List<AnimationChoice>): List<AnimationChoice> {
    val candidates = choices.filter { it.segments.isEmpty() && it.playable && it.clips.isNotEmpty() }
    val groups = StageRules.group(candidates, ::stageNameOf) { c ->
        "${c.participantCount}:${stageNameOf(c).substringBefore(':', "")}"
    }
    if (groups.isEmpty()) return choices
    val joined = HashSet<String>()
    val staged = groups.map { g ->
        g.members.forEach { joined += it.id }
        val lead = g.loops.first()
        lead.copy(id = STAGED_PREFIX + lead.id, name = stagedName(lead, g), stage = "", nextStages = emptyList(),
            segments = g.segments.map { (part, c) -> ChoiceSegment(part, c) })
    }
    val out = choices.filterTo(ArrayList(choices.size)) { it.id !in joined }
    // each staged entry goes in front of the first entry of its group that sorts after it by name (the list is sorted)
    for (s in staged) {
        val at = out.indexOfFirst { it.group == s.group && it.name.lowercase() > s.name.lowercase() }
            .takeIf { it >= 0 } ?: (out.indexOfLast { it.group == s.group }.takeIf { it >= 0 }?.plus(1) ?: out.size)
        out.add(at, s)
    }
    return out
}

/** The name the stage rules read for [choice]: its lead clip's source name, else its name. */
fun stageNameOf(choice: AnimationChoice): String {
    val clip = choice.clips.firstOrNull()?.clip ?: return choice.name
    return clip.sourceName.ifBlank { clip.name }
}

/** A staged animation's name: its first loop's name without the part's word (else the family's words), never blank. */
private fun stagedName(lead: AnimationChoice, group: StageGroup<AnimationChoice>): String {
    val word = StageRules.parse(lead.name)?.takeIf { it.part == StagePart.LOOP }?.word
    val fromName = if (word == null) "" else {
        val words = lead.name.split(Regex("[_\\s-]+")).filter { it.isNotEmpty() }
        // without the part's word and a numbered stage's "stage 2"
        words.filterIndexed { i, w ->
            !w.equals(word, ignoreCase = true) && !w.equals(StageRules.STAGE, ignoreCase = true) &&
                !(i > 0 && words[i - 1].equals(StageRules.STAGE, ignoreCase = true) && w.first().isDigit())
        }.joinToString(" ").trim()
    }
    val base = fromName.ifEmpty {
        group.family.substringAfterLast(':').split('_').filter { it != "#" }.joinToString(" ")
    }.ifEmpty { lead.name }
    return base.replace('_', ' ').replaceFirstChar(Char::uppercase)
}
