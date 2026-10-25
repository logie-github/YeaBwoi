package com.logie.wifecraft.story

/**
 * Where a story stands: the next command, each person's affection and the flags set, the stage
 * commands since the last scene cut (to set the stage again when a save is loaded), and what was
 * said (the log).
 */
data class StoryState(
    var pc: Int = 0,
    val affection: MutableMap<String, Int> = mutableMapOf(),
    val flags: MutableSet<String> = mutableSetOf(),
    var sceneAt: Int = -1,
    val stage: MutableList<Int> = mutableListOf(),
    val log: MutableList<LogEntry> = mutableListOf(),
    var chapter: String = "",
    var ending: String? = null,
)

data class LogEntry(val who: String, val text: String)

/** What the story asks of the screen next. */
sealed interface Step {
    /** Something on stage (a cut, a walk, the camera...): do it and carry on. */
    data class Stage(val command: Command) : Step
    /** A pause the story waits out (fades, waits, chapter cards). */
    data class Pause(val command: Command) : Step
    data class Line(val who: String, val emote: String?, val text: String, val index: Int) : Step
    data class Choose(val prompt: String, val options: List<String>, val index: Int) : Step
    data class Ending(val id: String) : Step
    object Finished : Step
}

class StoryRunner(val script: Script, val state: StoryState, private val playerName: () -> String = { "" }) {

    fun affection(who: String): Int = state.affection[who] ?: 0

    private fun affect(who: String, amount: Int) {
        state.affection[who] = (affection(who) + amount).coerceIn(0, 100)
    }

    private fun text(s: String): String = s.replace("{name}", playerName())

    /** Runs the story on to its next step. */
    fun next(): Step {
        var guard = 0
        while (state.pc < script.commands.size) {
            if (++guard > 10_000) throw ScriptException("The story runs in a circle near line ${script.commands[state.pc].line}")
            val at = state.pc
            val c = script.commands[at]
            state.pc++
            when (c) {
                is Command.Label -> Unit
                is Command.Affect -> affect(c.who, c.amount)
                is Command.SetFlag -> state.flags += c.flag
                is Command.Goto -> state.pc = script.labelIndex(c.target)
                is Command.If -> if (holds(c.condition)) state.pc = script.labelIndex(c.target)
                is Command.Scene -> {
                    state.sceneAt = at
                    state.stage.clear()
                    return Step.Stage(c)
                }
                is Command.Cast, is Command.Remove, is Command.Move, is Command.Face, is Command.Emote,
                is Command.Shot, is Command.Letterbox -> {
                    state.stage += at
                    return Step.Stage(c)
                }
                is Command.Shake -> return Step.Stage(c)
                is Command.Fade, is Command.Wait -> return Step.Pause(c)
                is Command.Title -> {
                    state.chapter = listOf(c.heading, c.sub).filter { it.isNotEmpty() }.joinToString(" · ")
                    return Step.Pause(c)
                }
                is Command.Say -> {
                    val t = text(c.text)
                    state.log += LogEntry(c.who, t)
                    if (state.log.size > 300) state.log.removeAt(0)
                    return Step.Line(c.who, c.emote, t, at)
                }
                is Command.Choice -> {
                    state.pc = at   // stays here until chosen
                    return Step.Choose(text(c.prompt), c.options.map { text(it.text) }, at)
                }
                is Command.Ending -> {
                    state.ending = c.id
                    return Step.Ending(c.id)
                }
                is Command.Finish -> {
                    state.pc = script.commands.size
                    return Step.Finished
                }
            }
        }
        return Step.Finished
    }

    /** Takes option [option] of the choice the story is waiting on. */
    fun choose(option: Int) {
        val c = script.commands.getOrNull(state.pc) as? Command.Choice ?: return
        val o = c.options.getOrNull(option) ?: return
        state.log += LogEntry("you", text(o.text))
        state.pc++
        for (e in o.effects) when (e) {
            is Effect.Affect -> affect(e.who, e.amount)
            is Effect.SetFlag -> state.flags += e.flag
            is Effect.Goto -> state.pc = script.labelIndex(e.target)
        }
    }

    /** The commands that set the current stage: the last cut and what happened since. */
    fun stageCommands(): List<Command> {
        val out = mutableListOf<Command>()
        script.commands.getOrNull(state.sceneAt)?.let { out += it }
        state.stage.mapNotNullTo(out) { script.commands.getOrNull(it) }
        return out
    }

    private fun holds(c: Condition): Boolean = when (c) {
        is Condition.Flag -> c.flag in state.flags
        is Condition.Compare -> {
            val v = affection(c.who)
            when (c.op) {
                ">=" -> v >= c.value
                "<=" -> v <= c.value
                ">" -> v > c.value
                "<" -> v < c.value
                else -> v == c.value
            }
        }
    }
}

/** WoW's reputation standings, which show how each person feels. */
object Standing {
    val names = listOf("Neutral", "Friendly", "Honored", "Revered", "Exalted")

    /** The standing for an affection of 0..100, and how far through it (0..1). */
    fun of(affection: Int): Pair<String, Float> {
        val a = affection.coerceIn(0, 100)
        val i = (a / 20).coerceAtMost(4)
        val into = if (i == 4) (a - 80) / 20f else (a - i * 20) / 20f
        return names[i] to into.coerceIn(0f, 1f)
    }
}
