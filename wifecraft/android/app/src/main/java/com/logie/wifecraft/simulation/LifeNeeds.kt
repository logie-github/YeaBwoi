package com.logie.wifecraft.simulation

/**
 * The life simulation's needs and moods ([LifeSim]): seven needs from 0 (empty) to 1 (full), each falling over game time
 * and filled by what the NPC does; a mood made from them and from what happened lately ([Moodlet]). Pure JVM.
 */

/**
 * One need: [label] the plain word, [wow] how the game would put it (the bar's title), [icon] the UI's icon key, and
 * [hoursToEmpty] how many game hours it takes to run from full to empty awake, before traits ([Trait.decay]). Tuned so a
 * day's cycle fits in about 24 game hours: about 17 waking hours on a full night's sleep, three meals, drinks between.
 */
enum class Need(val label: String, val wow: String, val icon: String, val hoursToEmpty: Float) {
    ENERGY("Energy", "Rested", "need_energy", 19f),
    HUNGER("Hunger", "Well Fed", "need_hunger", 12f),
    THIRST("Thirst", "Refreshed", "need_thirst", 9f),
    SOCIAL("Social", "Company", "need_social", 12f),
    FUN("Fun", "Amused", "need_fun", 10f),
    HYGIENE("Hygiene", "Clean", "need_hygiene", 30f),
    COMFORT("Comfort", "At Ease", "need_comfort", 8f);

    /** How much of the bar it loses a game hour (before traits). */
    val decayPerHour: Float get() = 1f / hoursToEmpty

    companion object {
        val COUNT = entries.size
    }
}

/** A mood ([icon]: the UI's icon key) and whether it is a good one. */
enum class Mood(val label: String, val icon: String, val good: Boolean) {
    HAPPY("Happy", "mood_happy", true),
    CONTENT("Content", "mood_content", true),
    INSPIRED("Inspired", "mood_inspired", true),
    PLAYFUL("Playful", "mood_playful", true),
    FLIRTY("Flirty", "mood_flirty", true),
    FINE("Fine", "mood_fine", true),
    TIRED("Tired", "mood_tired", false),
    HUNGRY("Hungry", "mood_hungry", false),
    THIRSTY("Thirsty", "mood_thirsty", false),
    LONELY("Lonely", "mood_lonely", false),
    BORED("Bored", "mood_bored", false),
    UNCOMFORTABLE("Uncomfortable", "mood_uncomfortable", false),
    GRUBBY("Grubby", "mood_grubby", false),
    IRRITABLE("Irritable", "mood_irritable", false),
    SAD("Sad", "mood_sad", false),
}

/**
 * Something that happened lately and colours the mood for a while: [mood] it leans toward, [value] (-1..1) it adds to the
 * mood score, until game hour [until]. [key] identifies it (the same key again refreshes it rather than adding one).
 */
data class Moodlet(val key: String, val mood: Mood, val value: Float, val until: Double, val label: String)

/** A thought the NPC has now (the UI's thought bubble): an icon key and a short line. */
data class LifeThought(val iconKey: String, val text: String)

/** The mood maths. */
object Moods {
    /** How much each need weighs in the mood score (energy and hunger most). */
    private val WEIGHT = floatArrayOf(1.3f, 1.2f, 1.1f, 1f, 1f, 0.6f, 0.7f)

    /** The mood score, -1..1: the weighted needs around their middle, plus the moodlets still running at [now]. */
    fun score(needs: FloatArray, moodlets: List<Moodlet>, now: Double): Float {
        var sum = 0f
        var w = 0f
        for (i in needs.indices) {
            // a need that is low hurts more than a full one helps
            val v = needs[i]
            val centred = if (v < 0.5f) (v - 0.5f) * 2.6f else (v - 0.5f) * 1.6f
            sum += centred * WEIGHT[i]
            w += WEIGHT[i]
        }
        var s = sum / w
        for (m in moodlets) if (m.until > now) s += m.value
        return s.coerceIn(-1f, 1f)
    }

    /** The need the mood is about now (the lowest under [LOW]), or null. */
    fun pressing(needs: FloatArray): Need? {
        var low = LOW
        var which: Need? = null
        for (i in needs.indices) if (needs[i] < low) { low = needs[i]; which = Need.entries[i] }
        return which
    }

    /**
     * The mood: a pressing need's own (tired, hungry...); else the strongest moodlet that is running when it outweighs the
     * needs (inspired after a good book, irritable after a quarrel); else by the score.
     */
    fun mood(needs: FloatArray, moodlets: List<Moodlet>, now: Double, score: Float): Mood {
        pressing(needs)?.let { need ->
            return when (need) {
                Need.ENERGY -> Mood.TIRED
                Need.HUNGER -> Mood.HUNGRY
                Need.THIRST -> Mood.THIRSTY
                Need.SOCIAL -> Mood.LONELY
                Need.FUN -> Mood.BORED
                Need.HYGIENE -> Mood.GRUBBY
                Need.COMFORT -> Mood.UNCOMFORTABLE
            }
        }
        var best: Moodlet? = null
        for (m in moodlets) if (m.until > now && (best == null || kotlin.math.abs(m.value) > kotlin.math.abs(best.value))) best = m
        if (best != null && kotlin.math.abs(best.value) >= 0.12f) return best.mood
        return when {
            score >= 0.5f -> Mood.HAPPY
            score >= 0.2f -> Mood.CONTENT
            score >= -0.15f -> Mood.FINE
            score >= -0.45f -> Mood.IRRITABLE
            else -> Mood.SAD
        }
    }

    /** A need under this is pressing (the mood and the thought are about it). */
    const val LOW = 0.2f
}
