package com.logie.wifecraft.catalog

/**
 * Which of a model's emotes and animations play well on their own, standing on a floor (pure; the picker's filter,
 * [EmoteCatalog.playableForModel]). The catalog lists every sequence the model has; some need what a scene never gives:
 * a mount under the rider (Mount, MountSelf*, CarriageMount, ReclinedMount: the rider sits in the air), flight (Fly,
 * Glide, Hover, a Dracthyr's air dashes: flown on the floor), water (Swim*, Submerge, Emerge, Torpedo: lying flat on the
 * floor), mid-air (Fall, Jump), something to hang from (WAHang), someone carrying them (Carried2H), and layers
 * ([EmoteCatalog.LAYERS]: part of the body only). Those are left out, each with a plain reason ([problem]).
 */
object EmotePlayability {

    /** Why the animation [anim] named [name] (wow.export's name; null: unknown) can't be played alone, or null when it can. */
    fun problem(anim: Int, name: String?): String? {
        if (anim < 0) return "No animation (voice only)."
        if (anim in EmoteCatalog.LAYERS) return "Moves only part of the body; the game plays it over another animation."
        val n = name ?: return null
        return when {
            "Mount" in n || n == "Carried2H" -> "Needs a mount or someone to carry them."
            n == "Fly" || n == "Glide" || n == "DHGlide" || n == "Hover" || n.startsWith("PriHover") ||
                n.startsWith("DracAir") || n.startsWith("DracFly") || n.startsWith("FlyStand") || n.startsWith("FlyWalk") ||
                n.startsWith("FlyRun") || n.startsWith("FlyIdle") -> "Made for flying."
            n.startsWith("Swim") || n.startsWith("Submerge") || n == "Emerge" || n == "Torpedo" -> "Made for swimming."
            n == "Fall" || n == "Jump" -> "Made for mid-air."
            n.startsWith("WAHang") -> "Needs something to hang from."
            else -> null
        }
    }

    /** The emote [e]'s [problem] ([animName]: the animation's name by id). A voice-only text emote has none when kept. */
    fun problem(e: Emote, animName: (Int) -> String?): String? = problem(e.anim, if (e.anim >= 0) animName(e.anim) else null)

    /**
     * [emotes] without those with a [problem]; [includeVoiceOnly] keeps text emotes with a voice and no animation
     * (they still speak). The order is kept.
     */
    fun playable(emotes: List<Emote>, includeVoiceOnly: Boolean = true, animName: (Int) -> String?): List<Emote> =
        emotes.filter { e ->
            if (e.anim < 0) includeVoiceOnly && e.soundFiles.isNotEmpty()
            else problem(e, animName) == null
        }

    /**
     * Whether an animation the emote tables don't describe (EmoteKind.ANIM) is held (loops) by default, by its name: states
     * and cycles loop (stances, sitting, sleeping, dances, walking, channels, the WA* work loops: played once, a dance stopped
     * on its last frame); starts, ends, deaths and single moves play once and hold their last frame.
     */
    fun heldByName(name: String): Boolean {
        val n = name.lowercase()
        if (listOf("start", "end", "down", "up", "precast", "death", "dead", "knockdown").any { n.endsWith(it) }) return false
        if ("death" in n || n.startsWith("wadead")) return false
        if (n.endsWith("loop") || "dance" in n || "channel" in n || "idle" in n) return true
        if (name.startsWith("WA") && name.length > 2 && name[2].isUpperCase()) return !n.startsWith("waemote")
        val states = listOf("stand", "ready", "hold", "sit", "sleep", "walk", "run", "sprint", "shuffle", "stealth", "kneel",
            "meditate", "cower", "stun", "instocks", "lean", "crouch")
        return states.any { n.startsWith(it) || n.startsWith("dh$it") || n.startsWith("monk") && it in n }
    }
}
