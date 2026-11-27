package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.Disposition
import com.logie.wifecraft.dialogue.WillingnessModel
import org.json.JSONObject

// Pure Kotlin (JVM-tested: InteractModeTest): "tell her what to do". The things she can be told ([InteractAction]), the keyword intents
// that read them from what the player types, the constrained JSON a model may answer with (only an id of the allowed list is ever
// accepted), and the consent gate every one of them passes: an act she Won't is never performed (she declines in character), one she
// hesitates at goes to the persuasion flow first.

/** The standing poses and solo moves she can be told to take: [words] find them in a clip's name and in what the player types. */
enum class PoseKind(val label: String, val words: List<String>, val tender: Float) {
    DANCE("Dance", listOf("dance", "danc", "twirl", "sway"), 0.1f),
    TURN_AROUND("Turn around", listOf("turn around", "turn round", "spin around", "show your back", "turn about"), 0.05f),
    BEND_OVER("Bend over", listOf("bend over", "bend down", "bent over", "lean over", "stoop"), 0.5f),
    SIT("Sit", listOf("sit down", "sit", "take a seat", "have a seat"), 0.05f),
    KNEEL("Kneel", listOf("kneel"), 0.3f),
    LIE_DOWN("Lie down", listOf("lie down", "lay down", "lie back", "lie on"), 0.45f),
    STRETCH("Stretch", listOf("stretch", "yawn"), 0.1f),
    HAIR_PLAY("Play with hair", listOf("hair"), 0.08f);
}

/** Where she looks. */
enum class LookTarget(val label: String) { CAMERA("at me"), AWAY("away"), DOWN("down") }

/** What the outfit commands do ([arg]: the stockings' style, colour or denier as typed, read by the stockings model). */
enum class OutfitAct(val label: String) { GEAR_ON("Gear on"), GEAR_OFF("Gear off"), STOCKINGS_ON("Put on stockings"), STOCKINGS_OFF("Take off stockings") }

/** One thing she can be told to do. [id] is its stable name (the model may answer with it, saves keep it). */
sealed class InteractAction(val id: String, val label: String) {
    data class Pose(val pose: PoseKind) : InteractAction("pose:${pose.name.lowercase()}", pose.label)
    data class Look(val target: LookTarget) : InteractAction("look:${target.name.lowercase()}", "Look ${target.label}")
    data class Express(val expression: Expression) : InteractAction("express:${expression.name.lowercase()}", expression.label.replaceFirstChar { it.uppercase() })
    data class Outfit(val act: OutfitAct, val arg: String? = null) : InteractAction("outfit:${act.name.lowercase()}", act.label)
    /** Walk to the spot the player taps next (the next tap on the floor). */
    object MoveToSpot : InteractAction("move:spot", "Move there")
    /** Come to the player (the camera's side). */
    object ComeHere : InteractAction("move:come", "Come here")
    object Follow : InteractAction("follow", "Follow me")
    object Stay : InteractAction("stay", "Stay")

    companion object {
        /** Every action the buttons and the model may choose from (the outfit ones without an [Outfit.arg]). */
        fun catalogue(): List<InteractAction> =
            PoseKind.entries.map { Pose(it) } + LookTarget.entries.map { Look(it) } + Expression.entries.filter { it != Expression.NEUTRAL }.map { Express(it) } +
                OutfitAct.entries.map { Outfit(it) } + listOf(MoveToSpot, ComeHere, Follow, Stay)

        fun byId(id: String): InteractAction? = catalogue().firstOrNull { it.id == id.trim().lowercase() }
    }
}

object InteractIntents {
    private fun norm(s: String): String = " " + s.lowercase().replace(Regex("[^a-z0-9' ]+"), " ").replace(Regex("\\s+"), " ").trim() + " "
    private fun contains(t: String, vararg w: String): Boolean = w.any { t.contains(it) }

    private val STOCKING_WORDS = listOf("stocking", "stockings", "pantyhose", "tights", "hosiery", "thigh high", "thigh-high", "knee high", "knee-high", "nylons")

    /** The action [text] asks for ("dance for me", "put on thigh high stockings", "look at me"), or null when it names none. */
    fun parse(text: String): InteractAction? {
        val t = norm(text)
        if (t.isBlank()) return null
        // outfit first: "take off stockings" is not "take off gear"
        val stock = STOCKING_WORDS.any { t.contains(it.replace('-', ' ')) }
        val off = contains(t, " take off", " remove", " peel off", " strip off", " off ", " without")
        val on = contains(t, " put on", " wear", " don ", " slip on", " pull on", " get into", " on your")
        if (stock) {
            return if (off && !contains(t, " put on", " wear")) InteractAction.Outfit(OutfitAct.STOCKINGS_OFF) else InteractAction.Outfit(OutfitAct.STOCKINGS_ON, t.trim())
        }
        if (contains(t, " get dressed", " dress up", " put your gear on", " gear on", " put on your gear", " put on your clothes", " clothes on")) return InteractAction.Outfit(OutfitAct.GEAR_ON)
        if (contains(t, " undress", " gear off", " take off your gear", " take off your clothes", " clothes off", " strip ")) return InteractAction.Outfit(OutfitAct.GEAR_OFF)
        if (on && contains(t, " gear", " armor", " armour", " clothes", " outfit")) return InteractAction.Outfit(OutfitAct.GEAR_ON)
        if (off && contains(t, " gear", " armor", " armour", " clothes", " outfit")) return InteractAction.Outfit(OutfitAct.GEAR_OFF)
        // look
        if (contains(t, " look at me", " look here", " look at the camera", " look over here", " eyes on me", " look up at me")) return InteractAction.Look(LookTarget.CAMERA)
        if (contains(t, " look away", " look aside", " avert your", " look elsewhere")) return InteractAction.Look(LookTarget.AWAY)
        if (contains(t, " look down", " lower your eyes", " eyes down", " look at the floor")) return InteractAction.Look(LookTarget.DOWN)
        // expression
        if (contains(t, " smile", " grin")) return InteractAction.Express(Expression.SMILE)
        if (contains(t, " blush")) return InteractAction.Express(Expression.BLUSH)
        if (contains(t, " tease", " flirt", " wink", " be cheeky")) return InteractAction.Express(Expression.TEASE)
        if (contains(t, " be shy", " shy ", " act shy", " look shy", " bashful")) return InteractAction.Express(Expression.SHY)
        // movement
        if (contains(t, " follow me", " come with me", " follow along", " walk with me")) return InteractAction.Follow
        if (contains(t, " stay", " wait here", " stay put", " stand still", " hold still", " stop following")) return InteractAction.Stay
        if (contains(t, " come here", " come to me", " come closer", " come over", " step closer", " approach")) return InteractAction.ComeHere
        if (contains(t, " walk to", " go to", " move to", " walk over", " go over", " move over", " move there", " walk there", " go there")) return InteractAction.MoveToSpot
        // poses, the most specific first (the order of [PoseKind] puts "turn around" before "turn"...)
        for (p in PoseKind.entries) if (p.words.any { w -> t.contains(" $w ") || t.contains(" $w") }) return InteractAction.Pose(p)
        return null
    }

    /** The sentence the player typed with its object (stocking style words found in it: "thigh high", "red", "nude"...), for the stockings' model. */
    fun stockingWords(text: String): String = norm(text).trim()

    // ---------------------------------------------------------------- the model's constrained choice
    /** The prompt's task: the player's words and the only answers allowed (ids). */
    fun modelPrompt(text: String, allowed: List<InteractAction>): String =
        "The player tells her: \"${text.trim().take(160)}\". Choose the one action from this list that best fits what they ask, or \"none\" if nothing fits. " +
            "Allowed: ${allowed.joinToString(", ") { it.id }}. Reply as JSON only: {\"action\":\"<id or none>\"}"

    /** The action a model's [raw] answer names, only if it is one of [allowed] (anything else, junk, an invented id: null). */
    fun parseChoice(raw: String?, allowed: List<InteractAction>): InteractAction? {
        val json = Regex("\\{[^{}]*}").find(raw.orEmpty())?.value ?: return null
        val id = runCatching { JSONObject(json).optString("action", "") }.getOrNull()?.trim()?.lowercase() ?: return null
        if (id.isEmpty() || id == "none") return null
        return allowed.firstOrNull { it.id == id }
    }
}

/** The consent gate for commands: every action is an act with a category, like a touch ([TouchConsent]). */
object InteractGate {
    /** How tender [a] is to ask of her (0..1). */
    fun tenderness(a: InteractAction): Float = when (a) {
        is InteractAction.Pose -> a.pose.tender
        is InteractAction.Look -> 0.02f
        is InteractAction.Express -> if (a.expression == Expression.TEASE) 0.15f else 0.03f
        is InteractAction.Outfit -> when (a.act) {
            OutfitAct.GEAR_OFF -> 0.6f; OutfitAct.STOCKINGS_OFF -> 0.45f; OutfitAct.STOCKINGS_ON -> 0.1f; OutfitAct.GEAR_ON -> 0.02f
        }
        else -> 0.02f
    }

    private fun kind(a: InteractAction): String = when (a) {
        is InteractAction.Pose -> when (a.pose) {
            PoseKind.DANCE -> "dance"
            PoseKind.BEND_OVER, PoseKind.LIE_DOWN, PoseKind.KNEEL -> "tease"
            else -> "pat"
        }
        is InteractAction.Express -> if (a.expression == Expression.TEASE) "flirt" else "pat"
        is InteractAction.Outfit -> if (a.act == OutfitAct.GEAR_OFF || a.act == OutfitAct.STOCKINGS_OFF) "strip" else "pat"
        else -> "pat"
    }

    /** The act [a] is to the Consent and limits screen. */
    fun act(a: InteractAction): ConsentAct = ConsentAct("cmd:${a.id}", a.label, kind(a), people = 1)

    /** Whether she does [a] ([policy]: the Consent and limits; [p]: her nature and how well she knows the player). */
    fun verdict(policy: ConsentPolicy?, npc: String, a: InteractAction, p: InteractPersona): Verdict {
        TouchConsent.decided(policy, npc, act(a))?.let { return it }
        val d: Disposition = p.disposition
        var r = tenderness(a) + ConsentDefaults.shift(d) * 0.8f - p.affinity.coerceIn(0f, 1f) * 0.4f - p.mood.coerceIn(-1f, 1f) * 0.1f
        r = r.coerceIn(0f, 1f)
        return when {
            r < WillingnessModel.AGREE_BELOW -> Verdict.ALLOW
            r >= WillingnessModel.REFUSE_AT -> Verdict.DECLINE
            else -> Verdict.HESITATE
        }
    }

    /** The resistance a hesitation starts at (for the persuasion flow). */
    fun resistance(a: InteractAction, p: InteractPersona): Float =
        (tenderness(a) + ConsentDefaults.shift(p.disposition) * 0.8f - p.affinity.coerceIn(0f, 1f) * 0.4f).coerceIn(WillingnessModel.AGREE_BELOW, WillingnessModel.REFUSE_AT - 0.01f)
}
