package com.logie.wifecraft.dialogue

/**
 * How a request becomes the text the model reads. The two models the app offers are Qwen3-based and speak ChatML; the
 * smaller one is a "hybrid" that thinks aloud unless told not to, so its assistant turn is opened with an empty think block.
 * The text is a function of the messages alone and grows by appending: turn N+1's prompt starts with turn N's prompt, the
 * NPC's own answer and the turn's end mark, which is what lets the model keep its place (DialogueEngine's note).
 */
enum class ChatTemplate(val thinkOff: Boolean) {
    CHATML(false),
    /** ChatML with the answer opened by an empty think block (Qwen3 hybrids). */
    CHATML_NO_THINK(true);

    fun render(system: String, history: List<DialogueMessage>, user: String): String {
        val sb = StringBuilder(system.length + user.length + 64 * (history.size + 2))
        if (system.isNotEmpty()) turn(sb, "system", system)
        for (m in history) turn(sb, when (m.role) {
            DialogueRole.SYSTEM -> "system"
            DialogueRole.USER -> "user"
            DialogueRole.ASSISTANT -> "assistant"
        }, clean(m.content))
        if (user.isNotEmpty()) turn(sb, "user", clean(user))
        sb.append("<|im_start|>assistant\n")
        if (thinkOff) sb.append("<think>\n\n</think>\n\n")
        return sb.toString()
    }

    private fun turn(sb: StringBuilder, role: String, text: String) {
        sb.append("<|im_start|>").append(role).append('\n').append(text.trim()).append("<|im_end|>\n")
    }

    /** The control marks of the format inside a player's or NPC's text would close a turn early: they are taken out. */
    private fun clean(text: String): String = text.replace("<|im_start|>", "").replace("<|im_end|>", "").replace("<|endoftext|>", "")

    companion object {
        /** Takes a think block and stray control marks out of a raw answer, and trims it. */
        fun tidy(raw: String): String {
            var s = raw
            val close = s.lastIndexOf("</think>")
            if (close >= 0) s = s.substring(close + "</think>".length)
            else if (s.contains("<think>")) s = s.substringBefore("<think>")
            return s.replace("<|im_end|>", "").replace("<|im_start|>", "").replace("<|endoftext|>", "").trim()
        }
    }
}
