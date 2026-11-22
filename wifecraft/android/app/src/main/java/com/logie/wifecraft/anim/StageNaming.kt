package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Stage

/** The stage kinds a pack's `animation_category` names, as the app tells them apart (the arousal map keeps its own, finer, table). */
enum class StageKind(val label: String) {
    TEASE("Tease"), HAND("Handjob"), FOOT("Footjob"), ORAL("Oral"), VAGINAL("Vaginal"), ANAL("Anal"), CLIMAX("Climax"), OTHER("Other");

    companion object {
        /** A pack's category word (any case, typos the packs have: ORAJOB, VAGNAL...) as a kind; [OTHER] for a word not known. */
        fun of(category: String): StageKind = when (category.trim().uppercase().replace(' ', '_')) {
            "TEASING", "TEASE", "FONDLE", "FOREPLAY", "STRIP" -> TEASE
            "HANDJOB", "HAND", "MASTURBATION", "FINGERING" -> HAND
            "FOOTJOB", "FOOT", "FEET" -> FOOT
            "ORALJOB", "ORAL", "ORAJOB", "BLOWJOB", "ORAL_GIVE", "CUNNILINGUS" -> ORAL
            "VAGINAL", "VAGNAL", "VAGINA", "SEX" -> VAGINAL
            "ANAL" -> ANAL
            "CLIMAX", "ORGASM", "CUM" -> CLIMAX
            else -> OTHER
        }
    }
}

/** One role of a stage: A, B, C... by actor id, with what the tuning says of who plays it. */
data class StageRole(val letter: Char, val actorId: Int, val clip: String, val preferredGender: String, val naked: String)

/**
 * A stage as the app names it: the pack's author or family, a kind, a position name without the numbering and tags, its roles
 * (A, B, C by actor id), how many people play it and whether a scene can ([playable]: one or two). The naming schemes seen:
 *  - WickedWhims' own and LPGS's (`LPGS:PosePack_..._set_2` clips, display names in words, tags in brackets: `[Ahegao loop]`,
 *    `[Dialogue+HandCuffed]`, a trailing number the variant);
 *  - CherryPie's (`CherryPie:PosePack_..._set_1` clips, `blowjob1`, `blowjobS` stage names);
 *  - FEMDOMANIA's (upper-case display names; a people count in the name, `(3P)`, `(8P)`; clips `Femdomania:...`);
 *  - 0nizu's toys (`0nizu_HandcuffedPronebone_110824_a0` clips, `Toy | Position` display names, the toy before the bar).
 */
data class StageProfile(
    val author: String, val kind: StageKind, val position: String, val variant: String, val tags: List<String>,
    val toy: String, val roles: List<StageRole>, val people: Int, val declaredPeople: Int?, val props: Int,
) {
    val playable: Boolean get() = people in 1..2
}

object StageNaming {
    private val TAG = Regex("""\s*\[([^\]]*)]""")
    private val PEOPLE = Regex("""\s*\(\s*(\d+)\s*P\s*\)""", RegexOption.IGNORE_CASE)
    private val PAREN = Regex("""\s*\(([^)]*)\)""")
    private val TRAILING = Regex("""[\s_-]*(\d+|[SV]\d+)$""", RegexOption.IGNORE_CASE)

    fun profile(s: Stage): StageProfile {
        var name = s.name.trim()
        val tags = TAG.findAll(name).flatMap { it.groupValues[1].split('+', ',', '/') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toList()
        name = TAG.replace(name, "")
        val declared = PEOPLE.find(name)?.groupValues?.get(1)?.toIntOrNull()
        name = PEOPLE.replace(name, "")
        val extra = PAREN.findAll(name).map { it.groupValues[1].trim().lowercase() }.filter { it.isNotEmpty() }.toList()
        name = PAREN.replace(name, "")
        var toy = ""
        if (name.contains('|')) { toy = name.substringBefore('|').trim(); name = name.substringAfter('|').trim() }
        var variant = TRAILING.find(name)?.groupValues?.get(1).orEmpty()
        if (variant.isNotEmpty()) name = TRAILING.replace(name, "")
        val position = pretty(name.trim().ifEmpty { s.stage.substringAfter(':') })
        val roles = s.actors.map { StageRole(('A' + it.id.coerceIn(0, 25)), it.id, it.clip, it.preferredGender, it.naked) }
        val clipAuthor = s.actors.firstOrNull { it.clip.isNotEmpty() }?.clip.orEmpty()
        val author = authorOf(s.author, s.stage, clipAuthor)
        if (variant.isEmpty() && extra.isNotEmpty()) variant = extra.joinToString(" ")
        return StageProfile(author, StageKind.of(s.category), position, variant, tags + extra.filter { it !in tags && it.length > 2 && !it.all(Char::isDigit) }.take(2),
            toy, roles, roles.size.coerceAtLeast(1), declared, s.props.size)
    }

    /** The pack family of a stage: the tuning's author, else the prefix of its stage or first clip name (`LPGS:`, `0nizu_`...). */
    fun authorOf(author: String, stage: String, clip: String): String {
        if (author.isNotBlank()) return author.trim()
        for (n in listOf(stage, clip)) {
            if (n.contains(':')) return n.substringBefore(':').trim()
            Regex("^(\\w+?)_").find(n)?.let { if (it.groupValues[1].length in 3..20) return it.groupValues[1] }
        }
        return ""
    }

    /** UPPER CASE names (FEMDOMANIA's) in words with capitals; mixed case left alone. */
    private fun pretty(s: String): String {
        val t = s.replace('_', ' ').trim()
        return if (t.any { it.isLowerCase() }) t else t.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }
    }
}

/**
 * The furniture a stage names (`animation_locations`: a list or one text with commas) as the app's own classes. A pack names a
 * Sims object category (`DOUBLE_BED`, `CHAIR_LIVING`, `CHAIR_LOUNGE_SOFA`, `TABLE_DINING_LONG`, `DESK`, `OTTOMAN`, `LOVESEAT`,
 * `MIRROR`); the class is where the stage is tried first ("marked surface is a preference, never a gate": every stage plays
 * anywhere).
 */
object StageLocations {
    /** BED, COUCH, CHAIR or TABLE; null for the floor, no place, or a place with no class here (a mirror: kept as written). */
    fun canonical(name: String): String? {
        val tokens = name.trim().uppercase().replace(' ', '_').split('_').filter { it.isNotEmpty() }
        return when {
            tokens.isEmpty() || tokens == listOf("FLOOR") || tokens == listOf("NONE") -> null
            tokens.any { it == "BED" || it == "BUNK" || it == "COT" } -> "BED"
            tokens.any { it == "SOFA" || it == "LOVESEAT" || it == "COUCH" || it == "OTTOMAN" || it == "BENCH" || it == "SETTEE" } -> "COUCH"
            tokens.any { it == "CHAIR" || it == "ARMCHAIR" || it == "STOOL" } -> "CHAIR"
            tokens.any { it == "TABLE" || it == "DESK" || it == "COUNTER" || it == "BAR" } -> "TABLE"
            else -> null
        }
    }
}
