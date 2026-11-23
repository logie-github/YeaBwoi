package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.DialogueEngine
import com.logie.wifecraft.dialogue.JoiLineRequest
import com.logie.wifecraft.dialogue.JoiPersona
import com.logie.wifecraft.dialogue.JoiPrompt
import com.logie.wifecraft.dialogue.KinkContext

/**
 * What runs JOI mode against the dialogue engine, apart from the screen (JoiRunner in ui/JoiUi.kt calls it every step): reading the
 * speaker's persona into the model before the first line is asked ([warmUp]), asking for each line with the mode's own state, putting the
 * words on the mode as they are written ([JoiMode.offerPartial]: the subtitle types itself out) and the finished line when it is done
 * ([JoiMode.offerLine]), keeping the lines she said ([recent]) for the next ask, and reporting how far the warm-up has got ([tickReadiness])
 * so the layer can say "Getting ready..." with a bar rather than sit silent. Pure Kotlin (JVM-tested: JoiPipelineTest).
 */
class JoiDriver(private val mode: JoiMode, private val pack: KinkContext?) {
    /** The lines she said, newest last (the model must not repeat them). */
    val recent = ArrayList<String>()
    private var warmedKey: String? = null

    /** The request for [stage] from the mode's present state (the level, the chips, the steer, the hand) for [persona]. */
    fun requestFor(persona: JoiPersona, stage: String, countdownFrom: Int?, compact: Boolean): JoiLineRequest =
        JoiLineRequest(persona, stage, mode.band, mode.steer.toSet(), recent.toList(), countdownFrom, npcId = mode.actor.orEmpty(), action = mode.action(),
            level = mode.level, customSteer = mode.customSteer, compact = compact)

    /**
     * Reads the speaker's stable prefix into the model: when JOI starts and whenever the level changes (the examples and the register are
     * the level's, so another level is another place in the runtime). The same key again is free (the engine knows what it has read).
     */
    fun warmUp(engine: DialogueEngine?, persona: JoiPersona?) {
        if (engine == null || persona == null || !engine.ready) return
        val r = requestFor(persona, mode.stage ?: "setup", null, engine.compactPrompts)
        warmedKey = JoiPrompt.engineKey(r)
        JoiPrompt.warm(engine, r, pack)
    }

    /** Called every step: the warm-up's progress goes to the mode (1 when nothing is being read), and a changed level is warmed at once. */
    fun tickReadiness(engine: DialogueEngine?, persona: JoiPersona?) {
        if (engine == null || persona == null || !engine.ready) { mode.setReadiness(1f); return }
        val r = requestFor(persona, mode.stage ?: "setup", null, engine.compactPrompts)
        val key = JoiPrompt.engineKey(r)
        if (key != warmedKey) warmUp(engine, persona)
        mode.setReadiness(engine.warmProgress(key))
    }

    /**
     * Asks [engine] for the line [e] wants and hands it to the mode: the words as they come, then the whole line (null: none came). A
     * stage skipped or a steer typed meanwhile makes the mode drop the answer (its serial no longer matches).
     */
    suspend fun fetch(engine: DialogueEngine?, persona: JoiPersona?, e: JoiEvent.NeedLine) {
        val text = if (persona != null && engine != null)
            runCatching { JoiPrompt.line(engine, requestFor(persona, e.stage, e.countdownFrom, engine.compactPrompts), pack) { part -> mode.offerPartial(e.serial, part) } }.getOrNull()
        else null
        mode.offerLine(e.serial, text)
        if (text != null) { recent += text; while (recent.size > 6) recent.removeAt(0) }
    }

    fun reset() { recent.clear(); warmedKey = null }
}
