package com.logie.wifecraft.dialogue

/**
 * How a request becomes the text the model reads. The models the app offers speak ChatML (Mag-Mell; the Qwen3 family, whose "hybrid"
 * models think aloud unless told not to: [CHATML_NO_THINK] opens the answer with an empty think block) or Gemma's turn format with a
 * `system` turn, which is how the Fast model (Gemmasutra Mini 2B, a Gemma 2 fine-tune) was trained: its card says "use the Gemma Instruct
 * template and modify it to support the system role (`<start_of_turn>system`)". The start-of-text mark is not written here: the runtime
 * adds the model's own (Gemma's `<bos>`, none for Qwen) the same way for every prompt.
 * The text is a function of the messages alone and grows by appending: turn N+1's prompt starts with turn N's prompt, the
 * NPC's own answer and the turn's end mark, which is what lets the model keep its place (DialogueEngine's note).
 */
enum class ChatTemplate(
    val thinkOff: Boolean, private val start: String = "<|im_start|>", private val end: String = "<|im_end|>", private val assistant: String = "assistant",
    /**
     * The answer is opened with a double quote and ends at the next one: a role-play tune writes narration around speech ("I reply calmly:
     * \"Good evening.\""), and the NPCs speak only words. Earlier answers in the prompt are quoted the same way, so the prompt still grows by appending.
     */
    val speechQuote: Boolean = false,
) {
    CHATML(false),
    /** ChatML with the answer opened by an empty think block (Qwen3 hybrids). */
    CHATML_NO_THINK(true),
    /** Gemma's turns with the model's own `system` role: `<start_of_turn>system\n...<end_of_turn>\n`, the answer is the `model` turn. */
    GEMMA(false, "<start_of_turn>", "<end_of_turn>", "model", speechQuote = true);

    fun render(system: String, history: List<DialogueTurn>, user: String, spoken: Boolean = true): String {
        val quote = speechQuote && spoken
        val sb = StringBuilder(system.length + user.length + 64 * (history.size + 2))
        if (system.isNotEmpty()) turn(sb, "system", system)
        for (m in history) {
            if (m.role == DialogueTurn.Role.USER || !quote) turn(sb, if (m.role == DialogueTurn.Role.USER) "user" else assistant, clean(m.text))
            else turn(sb, assistant, "\"" + clean(m.text).replace("\"", "").trim() + "\"")
        }
        if (user.isNotEmpty()) turn(sb, "user", clean(user))
        sb.append(start).append(assistant).append('\n')
        if (quote) sb.append('"')
        if (thinkOff) sb.append("<think>\n\n</think>\n\n")
        return sb.toString()
    }

    /**
     * Turn marks a model may print as text (Mag-Mell's card: with a quantised cache it sometimes "leaks" them) end the answer
     * there; [already] are the request's own stops, not repeated.
     */
    fun leakStops(already: List<String>, spoken: Boolean = true): List<String> = (listOf(start, end) + if (speechQuote && spoken) listOf("\"") else emptyList()).filter { it !in already }

    private fun turn(sb: StringBuilder, role: String, text: String) {
        sb.append(start).append(role).append('\n').append(text.trim()).append(end).append('\n')
    }

    /** The control marks of the format inside a player's or NPC's text would close a turn early: they are taken out. */
    private fun clean(text: String): String = MARKS.fold(text) { t, m -> t.replace(m, "") }

    companion object {
        private val MARKS = listOf("<|im_start|>", "<|im_end|>", "<|endoftext|>", "<start_of_turn>", "<end_of_turn>", "<bos>", "<eos>")

        /** Takes a think block and stray control marks out of a raw answer, and trims it. */
        fun tidy(raw: String): String {
            var s = raw
            val close = s.lastIndexOf("</think>")
            if (close >= 0) s = s.substring(close + "</think>".length)
            else if (s.contains("<think>")) s = s.substringBefore("<think>")
            return MARKS.fold(s) { t, m -> t.replace(m, "") }.trim()
        }
    }
}
