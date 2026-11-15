package com.logie.wifecraft.data

/** Dalaran over the Broken Isles, the backdrop of the places in assets/places.json: its outdoor groups (as tools/manifest.py fetches). */
object City {
    const val ROOT = 1120838
    val GROUPS = intArrayOf(2, 4, 5, 7, 8, 9, 10, 11, 15, 21, 44, 45, 46, 47, 48, 51, 53, 55, 57, 58, 80,
        90, 91, 92, 93, 94, 95, 96, 97, 115, 122, 124)
    const val PROP_RADIUS = 20f
}

/** The game's animation ids (AnimationData) for the story's emote names (the editor's Say beats pick them). */
object Emotes {
    const val STAND = 0
    const val WALK = 4
    const val RUN = 5
    const val TALK = 60
    val byName = mapOf(
        "stand" to 0, "talk" to 60, "exclaim" to 64, "question" to 65, "bow" to 66, "wave" to 67, "cheer" to 68,
        "dance" to 69, "laugh" to 70, "rude" to 73, "roar" to 74, "kneel" to 75, "kiss" to 76, "cry" to 77,
        "beg" to 79, "applaud" to 80, "shout" to 81, "flex" to 82, "shy" to 83, "point" to 84, "salute" to 113,
        "yes" to 185, "no" to 186,
    )

    /** Every animation a look's files are fetched for: these, standing, walking and running. */
    val all: Set<Int> = byName.values.toSet() + setOf(STAND, WALK, RUN)
}

/** Light for a time of day: sky, sun, ambient and fog. */
object Lights {
    fun of(name: String): FloatArray = when (name) {
        "dusk" -> floatArrayOf(
            0.20f, 0.18f, 0.42f, 0.93f, 0.58f, 0.45f, 0.30f, 0.22f, 0.35f,
            0.75f, 0.35f, -0.25f, 1.0f, 0.70f, 0.50f, 0.46f, 0.38f, 0.50f,
            0.93f, 0.58f, 0.45f, 220f, 1500f,
        )
        "night" -> floatArrayOf(
            0.02f, 0.03f, 0.10f, 0.13f, 0.14f, 0.30f, 0.05f, 0.05f, 0.11f,
            0.3f, -0.5f, -0.8f, 0.42f, 0.48f, 0.78f, 0.30f, 0.30f, 0.46f,
            0.13f, 0.14f, 0.30f, 160f, 1200f,
        )
        else -> floatArrayOf(
            0.25f, 0.38f, 0.66f, 0.72f, 0.78f, 0.86f, 0.35f, 0.42f, 0.52f,
            -0.4f, 0.3f, -0.85f, 1.0f, 0.95f, 0.85f, 0.47f, 0.49f, 0.57f,
            0.72f, 0.78f, 0.86f, 250f, 1600f,
        )
    }
}
