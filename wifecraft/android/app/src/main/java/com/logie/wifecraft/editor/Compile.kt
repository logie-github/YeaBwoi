package com.logie.wifecraft.editor

/**
 * Project -> the story script (story/Script.kt's .wfs text), so the existing StoryRunner and
 * Director play it. Naming: an actor `id` becomes the script person `p_<id>` (letters and
 * underscores only, so a name can never be read as a command), narration is `narrator`; a scene
 * becomes the label `s_<id>`; the three spots are the marks `left`, `center` and `right` of the
 * scene's place. Shot strings: "" (none), "bust", "wide", "two ACTOR", "view NAME", and for a
 * Camera beat "bust ACTOR" (a Show's bust is the one shown). An End plays `ending <id of the title>`
 * then `finish`.
 */
object Compile {
    val spots = listOf("left", "center", "right")
    private val lights = setOf("day", "dusk", "night")

    private fun slug(s: String, digits: Boolean): String {
        val r = s.lowercase().map { if (it in 'a'..'z' || it == '_' || (digits && it in '0'..'9')) it else '_' }.joinToString("")
        return r.ifEmpty { "x" }
    }

    /** Distinct script names for [ids], in order. */
    private fun names(ids: List<String>, prefix: String, digits: Boolean): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        for (id in ids) {
            if (id in out) continue
            var n = prefix + slug(id, digits)
            var k = 0
            while (n in used) { k++; n = prefix + slug(id, digits) + "_" + (if (digits) k.toString() else ('a' + (k - 1) % 26).toString().repeat((k - 1) / 26 + 1)) }
            used += n; out[id] = n
        }
        return out
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s*\\R\\s*|\\s+"), " ").trim()
    private fun optText(s: String) = oneLine(s).replace("|", "/")

    /** Readable problems that would stop the project playing; empty when it is fine. */
    fun validate(p: Project): List<String> {
        val e = mutableListOf<String>()
        if (p.scenes.isEmpty()) { e += "The project has no scenes."; return e }
        val actors = p.cast.map { it.id }
        p.cast.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { e += "Two people share the id \"$it\"." }
        val sceneIds = p.scenes.map { it.id }
        p.scenes.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { e += "Two scenes share the id \"$it\"." }
        if (p.start == null) e += "No scene is chosen to start the story."
        else if (p.start !in sceneIds) e += "The start scene \"${p.start}\" doesn't exist."
        for (s in p.scenes) {
            val at = "Scene \"${s.name.ifBlank { s.id }}\""
            if (s.stage.place.isBlank()) e += "$at has no place."
            if (s.stage.light !in lights) e += "$at: the light \"${s.stage.light}\" isn't day, dusk or night."
            s.beats.forEachIndexed { i, b ->
                val here = "$at, step ${i + 1}"
                fun actor(w: String) { if (w !in actors) e += "$here: the person \"$w\" isn't in the cast." }
                fun target(t: String) {
                    if (t.isBlank()) e += "$here: a jump has no scene to go to."
                    else if (t !in sceneIds) e += "$here: jumps to the scene \"$t\", which doesn't exist."
                }
                when (b) {
                    is Beat.Say -> { b.who?.let(::actor); if (b.text.isBlank()) e += "$here: an empty line." }
                    is Beat.Show -> {
                        actor(b.who)
                        if (b.spot !in spots) e += "$here: the spot \"${b.spot}\" isn't left, center or right."
                        if (parseShot(b.shot, b.who) == null) e += "$here: can't read the shot \"${b.shot}\"."
                        else shotActors(b.shot).forEach(::actor)
                    }
                    is Beat.Hide -> actor(b.who)
                    is Beat.Camera -> {
                        if (parseShot(b.shot, null) == null) e += "$here: can't read the shot \"${b.shot}\"."
                        else shotActors(b.shot).forEach(::actor)
                    }
                    is Beat.Choice -> {
                        if (b.options.isEmpty()) e += "$here: a choice with no options."
                        b.options.forEachIndexed { k, o ->
                            if (o.text.isBlank()) e += "$here: option ${k + 1} has no text."
                            target(o.goto)
                        }
                    }
                    is Beat.Goto -> target(b.scene)
                    is Beat.End -> Unit
                }
            }
        }
        return e
    }

    /** A shot as script words ("bust p_x", "wide", ...), or null when unreadable; "" is no shot (an empty list). */
    private fun parseShot(shot: String, shown: String?): List<String>? {
        val w = shot.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when (w.firstOrNull()) {
            null -> emptyList()
            "wide" -> listOf("wide")
            "bust" -> (w.getOrNull(1) ?: shown)?.let { listOf("bust", it) }
            "two" -> if (w.size == 2 && shown != null) listOf("two", shown, w[1]) else if (w.size == 3) w else null
            "view" -> if (w.size == 2) w else null
            else -> null
        }
    }

    private fun shotActors(shot: String): List<String> {
        val w = shot.trim().split(Regex("\\s+"))
        return if (w[0] == "bust" || w[0] == "two") w.drop(1) else emptyList()
    }

    fun toScript(p: Project): String {
        val who = names(p.cast.map { it.id }, "p_", false)
        val label = names(p.scenes.map { it.id }, "s_", true)
        val out = StringBuilder()
        fun line(s: String) { out.append(s).append('\n') }
        fun person(id: String) = who[id] ?: "p_" + slug(id, false)
        fun shotLine(shot: String, shown: String?) {
            val w = parseShot(shot, shown) ?: return
            if (w.isEmpty()) return
            val mapped = when (w[0]) {
                "bust" -> listOf("bust", person(w[1]))
                "two" -> listOf("two", person(w[1]), person(w[2]))
                else -> w
            }
            line("shot ${mapped.joinToString(" ")} 0")
        }
        line("# Compiled from the project \"${oneLine(p.title)}\"; do not edit.")
        val start = p.start?.takeIf { it in label } ?: p.scenes.firstOrNull()?.id
        if (start != null) line("goto ${label[start]}")
        var endings = 0
        for (s in p.scenes) {
            line("")
            line(":${label[s.id]}")
            line("scene ${slug(s.stage.place, true).ifEmpty { "studio" }} ${if (s.stage.light in lights) s.stage.light else "day"}")
            var closed = false
            for (b in s.beats) {
                if (closed) break
                when (b) {
                    is Beat.Say -> {
                        val e = slug(b.emote, false).takeIf { b.emote.isNotBlank() && b.emote != "talk" }
                        val name = b.who?.let(::person) ?: "narrator"
                        line("$name${e?.let { " [$it]" } ?: ""}: ${oneLine(b.text)}")
                    }
                    is Beat.Show -> {
                        line("cast ${person(b.who)} ${if (b.spot in spots) b.spot else "center"} face camera")
                        shotLine(b.shot, b.who)
                    }
                    is Beat.Hide -> line("remove ${person(b.who)}")
                    is Beat.Camera -> shotLine(b.shot, null)
                    is Beat.Choice -> {
                        line("choice ${oneLine(b.prompt)}")
                        for (o in b.options) line("  > ${optText(o.text)} | goto ${label[o.goto] ?: "s_" + slug(o.goto, true)}")
                        line("end")
                        closed = true   // every option jumps away
                    }
                    is Beat.Goto -> { line("goto ${label[b.scene] ?: "s_" + slug(b.scene, true)}"); closed = true }
                    is Beat.End -> {
                        endings++
                        line("ending ${slug(b.title, true).take(40).ifEmpty { "end$endings" }}")
                        line("finish")
                        closed = true
                    }
                }
            }
            if (!closed) line("finish")
        }
        return out.toString()
    }
}
