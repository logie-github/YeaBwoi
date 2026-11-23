package com.logie.wifecraft.anim

/**
 * Options' Character size (Graphics page): one factor on every person and creature of the live scene, so the people fit the
 * rooms. Pure JVM (no Android).
 *
 * **Why.** The game's rooms are drawn big for their people: Lion's Pride's bedroom door is 4.0 yards to its lintel and its bed's
 * posts 3.7 yards (a human woman is 1.9 to her crown: 2.1 and 1.9 times her height; a real door is about 1.2 times an adult and
 * a four-poster 1.3), while its mattress (0.74 yards) and chair (0.48) are about as high as in life (0.39 and 0.25 of her height
 * against 0.32 and 0.26). The measured places (README, "Character size") put the factor that brings door, posts, mattress and
 * table nearest real proportions, without sinking a body into a chair, at 1.25: [MATCH_ROOM] ("Match the room", the default).
 *
 * **What it scales.** Everything drawn as a person or creature in the live scene and the placement editor's ghost
 * (SceneView): its body, gear, hair, parts and held items scale about the feet with the actor; the pair frame
 * ([HumanScale.PAIR], so the clips' steps across the floor, a pair's slots, [simulation.SceneSimulation.HUMAN_BODY_UNIT] and
 * with it every furniture fit, the spacing of two bodies, the camera's body heights) and a person's speech bubble
 * ([HumanScale.head]) go with it; furniture, rooms and walls do not. Each race keeps its size relative to the others
 * ([HumanScale.bodyScale]: dwarves and gnomes stay small). The creator and the animation library's preview keep each race's
 * own size.
 *
 * **Where it is read.** [active] is the factor in force: 1 in JVM tests and before the app sets it ([apply]); the scene
 * session takes the setting when it starts and keeps it until it ends (a change in Options applies to the next scene).
 * Race offsets and Build mode's corrections are stored at size 1 and scaled by [active] when laid on (RoleDelta.applyTo).
 */
object CharacterSize {
    /** "Match the room": the measured factor, in percent. */
    const val MATCH_ROOM = 125
    const val MIN = 80
    const val MAX = 180
    const val STEP = 5

    /** The factor in force (1: none). Set by [apply]; read wherever a body's size matters. */
    @Volatile var active: Float = 1f
        private set

    /** The percent kept, within limits; [MATCH_ROOM] when nothing or something unreadable was. */
    fun parse(text: String?): Int = text?.trim()?.toIntOrNull()?.let(::clamp) ?: MATCH_ROOM

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)

    /** The text kept for a percent. */
    fun format(percent: Int): String = clamp(percent).toString()

    /** The factor a percent stands for. */
    fun factor(percent: Int): Float = clamp(percent) / 100f

    /** Whether a percent is "Match the room". */
    fun isMatch(percent: Int): Boolean = clamp(percent) == MATCH_ROOM

    /** Puts [percent] in force (the scene session's start, the app's start). */
    fun apply(percent: Int) { active = factor(percent) }

    /** [block] with [factor] in force, then what was (tests, tools). */
    inline fun <T> with(factor: Float, block: () -> T): T {
        val before = active
        force(factor)
        try { return block() } finally { force(before) }
    }

    /** Puts [factor] itself in force (tests and tools: any factor, not only whole percents). */
    fun force(factor: Float) { active = if (factor.isFinite() && factor > 0f) factor else 1f }
}
