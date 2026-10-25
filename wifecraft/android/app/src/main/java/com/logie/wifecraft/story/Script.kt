package com.logie.wifecraft.story

/**
 * Wifecraft's story scripts (the .wfs files in assets/story): one command per line.
 *
 *     # a comment
 *     :label                         a place to jump to
 *     title Chapter I | Morning      a chapter card
 *     scene krasus dusk              cut to a place, in a light (day, dusk, night)
 *     fadeout 600 / fadein 600       to and from black
 *     letterbox on|off               the black bars of a cutscene
 *     cast alleria rail [face you|camera|DEGREES]   puts someone on a place's mark
 *     remove alleria
 *     walk you rail 2500 / run saber far 1200       move to a mark, taking that long
 *     face alleria you               turn toward someone (or "camera")
 *     emote alleria laugh [loop]     an animation by name (talk, laugh, bow, point, ...)
 *     shot bust alleria 800          the camera: bust WHO, two A B, wide, view NAME (a place's
 *                                    camera), and how long the move takes (0: a cut)
 *     shake 400                      the camera shakes
 *     music alleria [2000]           the game's music by its name (assets/sounds.json), faded in
 *                                    over that long; "music off" fades it out
 *     sound saber_roar               a sound effect, by its name in assets/sounds.json
 *     wait 1200                      a pause, in milliseconds
 *     alleria [laugh]: Words...      a line; "you:" is the player, "narrator:" has no name
 *     choice Where to?               a choice, its options below, then "end"
 *       > Text | alleria +10 | goto there
 *     end
 *     alleria +5 / set flag          affection, and flags
 *     if alleria >= 60 goto there    also "if flag NAME goto there"
 *     goto there
 *     ending windrunner              an ending (by its id in the story's endings)
 *     finish                         back to the title
 *
 * `{name}` in any text is the player's name.
 */
sealed interface Command {
    val line: Int

    data class Label(val name: String, override val line: Int) : Command
    data class Title(val heading: String, val sub: String, override val line: Int) : Command
    data class Scene(val place: String, val light: String, override val line: Int) : Command
    data class Fade(val toBlack: Boolean, val ms: Int, override val line: Int) : Command
    data class Letterbox(val on: Boolean, override val line: Int) : Command
    data class Cast(val who: String, val mark: String, val facing: String?, override val line: Int) : Command
    data class Remove(val who: String, override val line: Int) : Command
    data class Move(val who: String, val mark: String, val ms: Int, val run: Boolean, override val line: Int) : Command
    data class Face(val who: String, val target: String, override val line: Int) : Command
    data class Emote(val who: String, val emote: String, val loop: Boolean, override val line: Int) : Command
    data class Shot(val kind: String, val args: List<String>, val ms: Int, override val line: Int) : Command
    data class Shake(val ms: Int, override val line: Int) : Command
    data class Music(val name: String, val fadeMs: Int, override val line: Int) : Command
    data class Sound(val name: String, override val line: Int) : Command
    data class Wait(val ms: Int, override val line: Int) : Command
    data class Say(val who: String, val emote: String?, val text: String, override val line: Int) : Command
    data class Choice(val prompt: String, val options: List<Option>, override val line: Int) : Command
    data class Affect(val who: String, val amount: Int, override val line: Int) : Command
    data class SetFlag(val flag: String, override val line: Int) : Command
    data class If(val condition: Condition, val target: String, override val line: Int) : Command
    data class Goto(val target: String, override val line: Int) : Command
    data class Ending(val id: String, override val line: Int) : Command
    data class Finish(override val line: Int) : Command
}

data class Option(val text: String, val effects: List<Effect>)

sealed interface Effect {
    data class Affect(val who: String, val amount: Int) : Effect
    data class SetFlag(val flag: String) : Effect
    data class Goto(val target: String) : Effect
}

sealed interface Condition {
    data class Compare(val who: String, val op: String, val value: Int) : Condition
    data class Flag(val flag: String) : Condition
}

class ScriptException(message: String) : Exception(message)

class Script(val commands: List<Command>) {
    private val labels: Map<String, Int> =
        commands.withIndex().filter { it.value is Command.Label }.associate { (it.value as Command.Label).name to it.index }

    fun labelIndex(name: String): Int = labels[name] ?: throw ScriptException("No label :$name")

    companion object {
        private val SAY = Regex("^([a-z_]+)\\s*(?:\\[([a-z_]+)])?\\s*:\\s*(.*)$")
        private val AFFECT = Regex("^([a-z_]+)\\s+([+-]\\d+)$")
        private val COMPARE = Regex("^([a-z_]+)\\s*(>=|<=|>|<|==)\\s*(-?\\d+)$")

        /** Reads a script; the line numbers in errors are the file's. */
        fun parse(source: String): Script {
            val out = mutableListOf<Command>()
            val lines = source.lines()
            var i = 0
            fun fail(n: Int, why: String): Nothing = throw ScriptException("Line $n: $why")
            while (i < lines.size) {
                val n = i + 1
                val raw = lines[i].trim()
                i++
                if (raw.isEmpty() || raw.startsWith("#")) continue
                val words = raw.split(Regex("\\s+"))
                val rest = raw.substringAfter(' ', "").trim()
                fun int(at: Int, default: Int): Int =
                    words.getOrNull(at)?.let { it.toIntOrNull() ?: fail(n, "\"$it\" isn't a number") } ?: default
                val cmd: Command = when {
                    raw.startsWith(":") -> Command.Label(raw.substring(1).trim(), n)
                    words[0] == "title" -> {
                        val parts = rest.split("|").map { it.trim() }
                        Command.Title(parts[0], parts.getOrElse(1) { "" }, n)
                    }
                    words[0] == "scene" -> Command.Scene(words.getOrNull(1) ?: fail(n, "scene needs a place"), words.getOrElse(2) { "day" }, n)
                    words[0] == "fadeout" -> Command.Fade(true, int(1, 600), n)
                    words[0] == "fadein" -> Command.Fade(false, int(1, 600), n)
                    words[0] == "letterbox" -> Command.Letterbox(words.getOrNull(1) == "on", n)
                    words[0] == "cast" -> {
                        if (words.size < 3) fail(n, "cast needs who and a mark")
                        val facing = if (words.getOrNull(3) == "face") words.getOrNull(4) else words.getOrNull(3)
                        Command.Cast(words[1], words[2], facing, n)
                    }
                    words[0] == "remove" -> Command.Remove(words.getOrNull(1) ?: fail(n, "remove who?"), n)
                    words[0] == "walk" || words[0] == "run" -> {
                        if (words.size < 3) fail(n, "${words[0]} needs who and a mark")
                        Command.Move(words[1], words[2], int(3, 2000), words[0] == "run", n)
                    }
                    words[0] == "face" -> {
                        if (words.size < 3) fail(n, "face needs who and a target")
                        Command.Face(words[1], words[2], n)
                    }
                    words[0] == "emote" -> {
                        if (words.size < 3) fail(n, "emote needs who and an emote")
                        Command.Emote(words[1], words[2], words.getOrNull(3) == "loop", n)
                    }
                    words[0] == "shot" -> {
                        if (words.size < 2) fail(n, "shot needs a kind")
                        val args = words.drop(2)
                        val ms = args.lastOrNull()?.toIntOrNull()
                        Command.Shot(words[1], if (ms != null) args.dropLast(1) else args, ms ?: 0, n)
                    }
                    words[0] == "shake" -> Command.Shake(int(1, 400), n)
                    words[0] == "music" -> Command.Music(words.getOrNull(1) ?: fail(n, "music needs a name, or off"), int(2, 1500), n)
                    words[0] == "sound" -> Command.Sound(words.getOrNull(1) ?: fail(n, "sound needs a name"), n)
                    words[0] == "wait" -> Command.Wait(int(1, 1000), n)
                    words[0] == "choice" -> {
                        val options = mutableListOf<Option>()
                        while (i < lines.size) {
                            val o = lines[i].trim()
                            i++
                            if (o.isEmpty() || o.startsWith("#")) continue
                            if (o == "end") break
                            if (!o.startsWith(">")) fail(i, "a choice's options start with >")
                            val parts = o.substring(1).split("|").map { it.trim() }
                            val effects = parts.drop(1).map { e ->
                                val ew = e.split(Regex("\\s+"))
                                when {
                                    ew[0] == "goto" && ew.size == 2 -> Effect.Goto(ew[1])
                                    ew[0] == "set" && ew.size == 2 -> Effect.SetFlag(ew[1])
                                    AFFECT.matches(e) -> AFFECT.find(e)!!.let { Effect.Affect(it.groupValues[1], it.groupValues[2].toInt()) }
                                    else -> fail(i, "can't read \"$e\"")
                                }
                            }
                            options += Option(parts[0], effects)
                        }
                        if (options.isEmpty()) fail(n, "a choice needs options")
                        Command.Choice(rest, options, n)
                    }
                    words[0] == "set" -> Command.SetFlag(words.getOrNull(1) ?: fail(n, "set what?"), n)
                    words[0] == "if" -> {
                        val gotoAt = words.indexOf("goto")
                        if (gotoAt < 2 || gotoAt != words.size - 2) fail(n, "if ... goto LABEL")
                        val cond = words.subList(1, gotoAt).joinToString(" ")
                        val c = when {
                            cond.startsWith("flag ") -> Condition.Flag(cond.removePrefix("flag ").trim())
                            COMPARE.matches(cond) -> COMPARE.find(cond)!!.let {
                                Condition.Compare(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt())
                            }
                            else -> fail(n, "can't read the condition \"$cond\"")
                        }
                        Command.If(c, words.last(), n)
                    }
                    words[0] == "goto" -> Command.Goto(words.getOrNull(1) ?: fail(n, "goto where?"), n)
                    words[0] == "ending" -> Command.Ending(words.getOrNull(1) ?: fail(n, "which ending?"), n)
                    words[0] == "finish" -> Command.Finish(n)
                    AFFECT.matches(raw) -> AFFECT.find(raw)!!.let { Command.Affect(it.groupValues[1], it.groupValues[2].toInt(), n) }
                    SAY.matches(raw) -> SAY.find(raw)!!.let {
                        Command.Say(it.groupValues[1], it.groupValues[2].ifEmpty { null }, it.groupValues[3].trim(), n)
                    }
                    else -> fail(n, "can't read \"$raw\"")
                }
                out += cmd
            }
            val script = Script(out)
            // Every jump must land somewhere.
            for (c in out) {
                val targets = when (c) {
                    is Command.Goto -> listOf(c.target)
                    is Command.If -> listOf(c.target)
                    is Command.Choice -> c.options.flatMap { o -> o.effects.filterIsInstance<Effect.Goto>().map { it.target } }
                    else -> emptyList()
                }
                for (t in targets) if (t !in script.labels) throw ScriptException("Line ${c.line}: no label :$t")
            }
            return script
        }
    }
}
