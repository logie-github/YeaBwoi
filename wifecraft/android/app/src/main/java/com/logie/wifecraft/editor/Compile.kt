package com.logie.wifecraft.editor

/**
 * Project -> the story script (story/Script.kt's .wfs text), so the existing StoryRunner and
 * Director play it. Naming: an actor `id` becomes the script person `p_<id>` (letters and
 * underscores only, so a name can never be read as a command), narration is `narrator`; a scene
 * becomes the label `s_<id>`; the three spots are the marks `left`, `center` and `right` of the
 * scene's place. Shot strings: "" (none), "bust", "wide", "two ACTOR", "view NAME", and for a
 * Camera beat "bust ACTOR" (a Show's bust is the one shown). An End plays `ending <id of the title> <the title>`
 * then `finish`; [cast] says which actor each script name is (the player resolves each to its saved look).
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

    /** The prefix of a Say's emote that names a library animation instead of a game emote: "anim:ID". */
    const val ANIM_EMOTE = "anim:"

    /** The library animation id a Say's emote names, or null for a game emote. */
    fun animOfEmote(emote: String): String? = emote.takeIf { it.startsWith(ANIM_EMOTE) }?.removePrefix(ANIM_EMOTE)

    /** [validate] without a library to check animation ids against (only that each is named). */
    fun validate(p: Project): List<String> = validate(p, null)

    /** The stage a scene is on: its [Scene.stageId], or its place for a scene saved before placements. */
    fun stageOf(s: Scene): String = s.stageId.ifBlank { s.stage.place }

    /** A "loc:<locationId>:<view>" stage as (locationId, view), or null when it isn't one or is malformed. */
    fun parseLoc(stage: String): Pair<String, String>? {
        if (!stage.startsWith("loc:")) return null
        val w = stage.split(":")
        return if (w.size == 3 && w[1].isNotBlank() && w[2].isNotBlank() && w.drop(1).all { part -> part.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' } }) w[1] to w[2] else null
    }

    /** A placement id as one script word: letters, digits, `_` and `-` (anything else becomes `_`). */
    private fun placeWord(id: String): String = id.map { if (it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-') it else '_' }.joinToString("").ifEmpty { "x" }

    /** Script word of each placement id of [s] (distinct; an id already safe is kept). */
    fun placeWords(s: Scene): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        for (pl in s.placements) {
            if (pl.id in out) continue
            var w = placeWord(pl.id)
            var k = 1
            while (w in used) { k++; w = placeWord(pl.id) + "_" + k }
            used += w; out[pl.id] = w
        }
        return out
    }

    private fun num(f: Float): String = (if (f.isFinite()) f else 0f).toString()

    /**
     * Readable problems that would stop the project playing; empty when it is fine. [animIds] are the ids in the
     * animation library: a Pose or a Say naming another one is a problem (null: not checked). [stageIds] are the stages
     * that exist: "studio", the places of places.json and every "loc:<locationId>:<view>" of locations.json (null: a
     * "loc:" stage is only checked to be well formed).
     */
    fun validate(p: Project, animIds: Set<String>?, stageIds: Set<String>? = null): List<String> {
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
            val stageId = stageOf(s)
            if (stageId.isBlank()) e += "$at has no place."
            else if (stageId.startsWith("loc:") && parseLoc(stageId) == null) e += "$at: the location \"$stageId\" isn't written loc:<location>:<view>."
            else if (stageIds != null && stageId !in stageIds) e += "$at: the place \"$stageId\" doesn't exist."
            if (s.stage.light !in lights) e += "$at: the light \"${s.stage.light}\" isn't day, dusk or night."
            val placeIds = s.placements.map { it.id }
            s.placements.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { e += "$at: two placed things share the id \"$it\"." }
            for (pl in s.placements) {
                val of = "$at, placed \"${pl.name.ifBlank { pl.id }}\""
                if (pl.id.isBlank()) e += "$at: a placed thing has no id."
                if (!(pl.x.isFinite() && pl.y.isFinite() && pl.z.isFinite() && pl.yaw.isFinite())) e += "$of: its position isn't a number."
                if (!pl.scale.isFinite() || pl.scale <= 0f) e += "$of: its size must be more than 0."
                when (pl.kind) {
                    PlaceKind.NPC -> if (pl.actorId == null || pl.actorId !in actors) e += "$of: the person \"${pl.actorId ?: ""}\" isn't in the cast."
                    else -> if (pl.asset <= 0) e += "$of: no ${if (pl.kind == PlaceKind.PROP) "model" else "creature"} is picked."
                }
                pl.anim?.let { if (it < 0) e += "$of: a negative animation." }
            }
            s.beats.forEachIndexed { i, b ->
                val here = "$at, step ${i + 1}"
                fun actor(w: String) { if (w !in actors) e += "$here: the person \"$w\" isn't in the cast." }
                fun anim(id: String) {
                    if (id.isBlank()) e += "$here: no animation is picked."
                    else if (id.any { it.isWhitespace() }) e += "$here: the animation id \"$id\" has a space in it."
                    else if (animIds != null && id !in animIds) e += "$here: the animation \"$id\" isn't in the library (it was deleted, or never imported on this phone)."
                }
                fun target(t: String) {
                    if (t.isBlank()) e += "$here: a jump has no scene to go to."
                    else if (t !in sceneIds) e += "$here: jumps to the scene \"$t\", which doesn't exist."
                }
                when (b) {
                    is Beat.Say -> {
                        b.who?.let(::actor)
                        if (b.text.isBlank()) e += "$here: an empty line."
                        animOfEmote(b.emote)?.let { id -> if (b.who == null) e += "$here: narration can't hold a pose." else anim(id) }
                    }
                    is Beat.Pose -> { actor(b.who); anim(b.animId) }
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
                    is Beat.Emote -> if (b.placementId !in placeIds) e += "$here: the placed thing \"${b.placementId}\" isn't in this scene." else if (b.emoteId < 0) e += "$here: no emote is picked."
                    is Beat.Anim -> if (b.placementId !in placeIds) e += "$here: the placed thing \"${b.placementId}\" isn't in this scene." else if (b.anim < 0) e += "$here: no animation is picked."
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

    /** The script name of each actor of [p] (`p_<letters>`, as [toScript] writes them) to the actor. */
    fun cast(p: Project): Map<String, Actor> {
        val who = names(p.cast.map { it.id }, "p_", false)
        return p.cast.distinctBy { it.id }.associateBy { who.getValue(it.id) }
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
            val stageId = stageOf(s)
            val where = parseLoc(stageId)?.let { "loc:${it.first}:${it.second}" } ?: slug(stageId, true).ifEmpty { "studio" }
            line("scene $where ${if (s.stage.light in lights) s.stage.light else "day"}")
            val pw = placeWords(s)
            for (pl in s.placements) {
                line("place ${pw.getValue(pl.id)} ${pl.kind.name.lowercase()} ${pl.asset} ${if (pl.kind == PlaceKind.NPC) pl.actorId?.let(::person) ?: "-" else "-"} " +
                    "${num(pl.x)} ${num(pl.y)} ${num(pl.z)} ${num(pl.yaw)} ${num(pl.scale)} ${pl.anim ?: "-"} ${if (pl.loop) 1 else 0} ${if (pl.sound) 1 else 0}")
            }
            var closed = false
            for (b in s.beats) {
                if (closed) break
                when (b) {
                    is Beat.Say -> {
                        val animId = animOfEmote(b.emote)
                        val e = slug(b.emote, false).takeIf { animId == null && b.emote.isNotBlank() && b.emote != "talk" }
                        val name = b.who?.let(::person) ?: "narrator"
                        if (animId != null && b.who != null && animId.isNotBlank()) line("pose $name $animId")
                        line("$name${e?.let { " [$it]" } ?: ""}: ${oneLine(b.text)}")
                    }
                    is Beat.Show -> {
                        line("cast ${person(b.who)} ${if (b.spot in spots) b.spot else "center"} face camera")
                        shotLine(b.shot, b.who)
                    }
                    is Beat.Hide -> line("remove ${person(b.who)}")
                    is Beat.Pose -> if (b.animId.isNotBlank()) line("pose ${person(b.who)} ${b.animId}")
                    is Beat.Camera -> shotLine(b.shot, null)
                    is Beat.Choice -> {
                        line("choice ${oneLine(b.prompt)}")
                        for (o in b.options) line("  > ${optText(o.text)} | goto ${label[o.goto] ?: "s_" + slug(o.goto, true)}")
                        line("end")
                        closed = true   // every option jumps away
                    }
                    is Beat.Emote -> line("emote ${pw[b.placementId] ?: placeWord(b.placementId)} ${b.emoteId}")
                    is Beat.Anim -> line("anim ${pw[b.placementId] ?: placeWord(b.placementId)} ${b.anim} ${if (b.loop) 1 else 0}")
                    is Beat.Goto -> { line("goto ${label[b.scene] ?: "s_" + slug(b.scene, true)}"); closed = true }
                    is Beat.End -> {
                        endings++
                        val id = slug(b.title, true).take(40).ifEmpty { "end$endings" }
                        line("ending $id ${oneLine(b.title)}".trimEnd())
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
