package com.logie.wifecraft.creator

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Whether others may start a two-person animation with her: yes, only after asking (she may say no), or never. */
enum class Initiation(val label: String) { YES("Yes"), ASK("Ask me"), NO("No") }

/** The pace she likes: slow (the gentler stages first, the intimate ones less often), either, intense. */
enum class Pace(val label: String) { SLOW("Slow"), EITHER("Either"), INTENSE("Intense") }

/** The kinds of two-person stages she allows (the life simulation's pairs and partner animations). */
enum class StageKind(val label: String) {
    KISS("Kiss"), EMBRACE("Embrace"), CUDDLE("Cuddle"), DANCE("Dance"), PLAY("Play"), INTIMATE("Intimate"),
}

/**
 * Her boundaries and likes, which the life simulation reads (LifeSim: what NPCs start with her by themselves and whether she
 * accepts): [initiation], [pace] and the stages [allowed]. The defaults are everything, as before.
 */
data class Preferences(
    val initiation: Initiation = Initiation.YES,
    val pace: Pace = Pace.EITHER,
    val allowed: Set<StageKind> = StageKind.entries.toSet(),
) {
    fun allows(kind: StageKind): Boolean = kind in allowed

    fun toJson(): JSONObject = JSONObject().put("initiation", initiation.name).put("pace", pace.name)
        .put("allowed", JSONArray().also { a -> StageKind.entries.filter { it in allowed }.forEach { a.put(it.name) } })

    companion object {
        fun fromJson(o: JSONObject?): Preferences {
            if (o == null) return Preferences()
            val allowed = o.optJSONArray("allowed")?.let { a -> (0 until a.length()).mapNotNull { k -> StageKind.entries.firstOrNull { it.name == a.optString(k) } }.toSet() }
            return Preferences(
                Initiation.entries.firstOrNull { it.name == o.optString("initiation") } ?: Initiation.YES,
                Pace.entries.firstOrNull { it.name == o.optString("pace") } ?: Pace.EITHER,
                allowed ?: StageKind.entries.toSet(),
            )
        }
    }
}

/**
 * The Personality tab: up to three life traits ([traits]: the simulation's Trait names; empty: the ones her id gives her,
 * Traits.assign, as before), her [preferences], a short [bio] and a [favouriteColour] (0xRRGGBB; null none). Saved in
 * [Appearance] as `"personality":{...}` only when something differs from the defaults ([isDefault]).
 */
data class CasPersonality(
    val traits: List<String> = emptyList(),
    val preferences: Preferences = Preferences(),
    val bio: String = "",
    val favouriteColour: Int? = null,
) {
    val isDefault: Boolean get() = traits.isEmpty() && preferences == Preferences() && bio.isEmpty() && favouriteColour == null

    /** [name] on or off: on only while fewer than [MAX_TRAITS] are chosen and none of [opposed] is. */
    fun toggle(name: String, opposed: (String, String) -> Boolean = { _, _ -> false }): CasPersonality = when {
        name in traits -> copy(traits = traits - name)
        traits.size >= MAX_TRAITS || traits.any { opposed(it, name) } -> this
        else -> copy(traits = traits + name)
    }

    fun toJson(): JSONObject = JSONObject().also { j ->
        if (traits.isNotEmpty()) j.put("traits", JSONArray(traits))
        if (preferences != Preferences()) j.put("preferences", preferences.toJson())
        if (bio.isNotEmpty()) j.put("bio", bio)
        favouriteColour?.let { j.put("colour", String.format("#%06X", it and 0xFFFFFF)) }
    }

    companion object {
        const val MAX_TRAITS = 3
        const val MAX_BIO = 280

        fun fromJson(o: JSONObject?): CasPersonality? {
            if (o == null) return null
            val traits = o.optJSONArray("traits")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.distinct().take(MAX_TRAITS) }.orEmpty()
            val colour = o.optString("colour", "").removePrefix("#").toIntOrNull(16)
            return CasPersonality(traits, Preferences.fromJson(o.optJSONObject("preferences")), o.optString("bio", "").take(MAX_BIO), colour)
                .takeIf { !it.isDefault }
        }

        /** Three of [all] at random, none of them opposed to another ([opposed]). */
        fun randomTraits(all: List<String>, random: Random, opposed: (String, String) -> Boolean = { _, _ -> false }): List<String> {
            val out = ArrayList<String>()
            for (t in all.shuffled(random)) {
                if (out.size >= MAX_TRAITS) break
                if (out.none { opposed(it, t) }) out += t
            }
            return out
        }
    }
}
