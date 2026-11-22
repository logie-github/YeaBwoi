package com.logie.wifecraft.dialogue

import org.json.JSONObject

/** How often NPCs speak: Quiet, Normal, Chatty. [factor] scales the gaps between lines. */
enum class Talkativeness(val label: String, val factor: Float) {
    QUIET("Quiet", 0.5f), NORMAL("Normal", 1f), CHATTY("Chatty", 1.8f);

    companion object { fun of(name: String?): Talkativeness = entries.firstOrNull { it.name == name } ?: NORMAL }
}

/** Options → Dialogue: whether NPCs speak with the on-device model, and how much. Kept as JSON in Settings. */
data class DialogueSettings(val enabled: Boolean = true, val talk: Talkativeness = Talkativeness.NORMAL) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("talk", talk.name)

    companion object {
        fun parse(s: String?): DialogueSettings {
            if (s.isNullOrBlank()) return DialogueSettings()
            return runCatching { JSONObject(s).let { DialogueSettings(it.optBoolean("enabled", true), Talkativeness.of(it.optString("talk"))) } }
                .getOrDefault(DialogueSettings())
        }
    }
}
