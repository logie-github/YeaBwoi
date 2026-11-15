package com.logie.wifecraft.ui

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Race
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.editor.Beat
import com.logie.wifecraft.editor.Compile
import com.logie.wifecraft.editor.Project
import com.logie.wifecraft.editor.ProjectStore
import com.logie.wifecraft.editor.Scene
import com.logie.wifecraft.editor.Stage
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Shots
import com.logie.wifecraft.story.Vec3
import org.json.JSONArray
import org.json.JSONObject

// Pure Kotlin, no android.* and no Compose: the story editor's state and its actions, so the JVM
// tests (unit-tests/.../ui/EditorStateTest.kt) cover them. The screens (EditorHome, EditorCast,
// EditorScenes, EditorScene) only call these and draw what they return.

/** What the projects list needs of a project store (ProjectStore fits it in four lines). */
interface ProjectsApi {
    fun list(): List<Project>
    fun save(p: Project)
    fun delete(id: String)
    fun create(title: String): Project
}

/** The projects on disk as the editor's list sees them. */
class StoreApi(private val store: ProjectStore) : ProjectsApi {
    override fun list() = store.list()
    override fun save(p: Project) = store.save(p)
    override fun delete(id: String) = store.delete(id)
    override fun create(title: String) = store.newProject(title)
}

/** A short ID from a name: lower-case letters, digits and underscores, unique among [taken]. */
fun freshId(base: String, taken: Collection<String>): String {
    val slug = base.lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }.joinToString("")
        .replace(Regex("_+"), "_").trim('_').ifEmpty { "x" }
    if (slug !in taken) return slug
    var n = 2
    while ("${slug}_$n" in taken) n++
    return "${slug}_$n"
}

/** The words a beat's kind has in the "add a beat" menu, in order. */
enum class BeatKind(val label: String) {
    SAY("Say"), SHOW("Show"), HIDE("Hide"), CAMERA("Camera"), POSE("Pose"), CHOICE("Choice"), GOTO("Goto"), END("End");

    companion object {
        fun of(b: Beat): BeatKind = when (b) {
            is Beat.Say -> SAY
            is Beat.Show -> SHOW
            is Beat.Hide -> HIDE
            is Beat.Camera -> CAMERA
            is Beat.Pose -> POSE
            is Beat.Choice -> CHOICE
            is Beat.Goto -> GOTO
            is Beat.End -> END
        }
    }
}

/** The values the beat editors offer. A shot is the words after `shot` in a script: "bust", "two", "wide", "view NAME". */
object EditorChoices {
    val spots = listOf("left", "center", "right")
    val lights = listOf("day", "dusk", "night")
    const val STUDIO = "studio"

    /**
     * The shots a beat can take, as (shot string, label): Compile's words. A Show's [shown] actor is
     * implied ("bust", "two OTHER"); a Camera beat names them ("bust ACTOR", "two A B").
     */
    fun shots(cast: List<Pair<String, String>>, views: Collection<String>, shown: String? = null): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (shown != null) {
            out += "" to "No shot"
            out += "bust" to "Bust"
            out += "wide" to "Wide"
            for ((id, name) in cast) if (id != shown) out += "two $id" to "Two with $name"
        } else {
            out += "wide" to "Wide"
            for ((id, name) in cast) out += "bust $id" to "Bust of $name"
            for ((i, a) in cast.withIndex()) for (b in cast.drop(i + 1)) out += "two ${a.first} ${b.first}" to "${a.second} and ${b.second}"
        }
        for (v in views) out += "view $v" to "View $v"
        return out
    }
}

/**
 * A one-line summary of a beat for the beat list; [nameOf] turns an actor id into its name, [animName] a library
 * animation's id into its name (the id itself where the library has no such animation).
 */
fun describe(b: Beat, nameOf: (String) -> String, animName: (String) -> String = { it }): String = when (b) {
    is Beat.Say -> (b.who?.let(nameOf) ?: "Narration") + (if (b.who != null && b.emote.isNotBlank() && b.emote != "talk") " (${Compile.animOfEmote(b.emote)?.let { "pose " + animName(it) } ?: b.emote})" else "") + ": " + b.text
    is Beat.Pose -> "Pose ${nameOf(b.who)}: " + (if (b.animId.isBlank()) "(none)" else animName(b.animId))
    is Beat.Show -> "Show ${nameOf(b.who)} ${b.spot}" + (if (b.shot.isBlank()) "" else ", ${b.shot}")
    is Beat.Hide -> "Hide ${nameOf(b.who)}"
    is Beat.Camera -> "Camera ${b.shot.ifBlank { "(none)" }}"
    is Beat.Choice -> "Choice: ${b.prompt} (${b.options.size})"
    is Beat.Goto -> "Goto ${b.scene}"
    is Beat.End -> "End: ${b.title}"
}

/** What a cast card says of a look: "Night Elf woman, 14 choices". */
fun lookSummary(race: Race?, a: Appearance): String {
    val who = (race?.name ?: "Race ${a.raceId}") + if (a.sex.name == "FEMALE") " woman" else " man"
    val n = a.picks.size
    return who + if (n == 0) "" else ", $n choice" + (if (n == 1) "" else "s")
}

/**
 * One open project and every change the editor can make to it. Each action replaces [project]
 * with a changed copy and tells [onChange] (the screens save and redraw there). Actions on an id
 * that is not there do nothing. [validator] is `editor.Compile.validate`.
 */
class EditorState(
    project: Project,
    private val validator: (Project) -> List<String> = Compile::validate,
    private val onChange: (Project) -> Unit = {},
) {
    var project: Project = project
        private set

    private fun set(p: Project) {
        if (p == project) return
        project = p
        onChange(p)
    }

    private fun mapScene(id: String, f: (Scene) -> Scene) = set(project.copy(scenes = project.scenes.map { if (it.id == id) f(it) else it }))

    fun sceneOf(id: String): Scene? = project.scenes.firstOrNull { it.id == id }
    fun actorOf(id: String): Actor? = project.cast.firstOrNull { it.id == id }
    fun nameOf(id: String): String = actorOf(id)?.name ?: id
    fun problems(): List<String> = validator(project)

    fun rename(title: String) = set(project.copy(title = title.trim().ifEmpty { project.title }))

    // ---------------------------------------------------------------- the cast

    fun addActor(name: String, appearance: Appearance, note: String = ""): String {
        val n = name.trim().ifEmpty { "NPC" }
        val id = freshId(n, project.cast.map { it.id })
        set(project.copy(cast = project.cast + Actor(id, n, appearance, note)))
        return id
    }

    fun updateActor(id: String, name: String, appearance: Appearance) =
        set(project.copy(cast = project.cast.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }, appearance = appearance) else it }))

    fun duplicateActor(id: String): String? {
        val a = actorOf(id) ?: return null
        val newId = freshId(a.name + " copy", project.cast.map { it.id })
        val at = project.cast.indexOf(a) + 1
        set(project.copy(cast = project.cast.toMutableList().also { it.add(at, a.copy(id = newId, name = a.name + " (copy)")) }))
        return newId
    }

    /** How many beats mention this NPC (to warn before deleting). */
    fun actorUses(id: String): Int = project.scenes.sumOf { s ->
        s.beats.count {
            when (it) {
                is Beat.Say -> it.who == id
                is Beat.Show -> it.who == id
                is Beat.Hide -> it.who == id
                is Beat.Pose -> it.who == id
                else -> false
            }
        }
    }

    /** Removes the NPC; their Show and Hide beats go, their lines become narration. */
    fun deleteActor(id: String) {
        if (actorOf(id) == null) return
        set(project.copy(
            cast = project.cast.filter { it.id != id },
            scenes = project.scenes.map { s ->
                s.copy(beats = s.beats.mapNotNull {
                    when {
                        it is Beat.Show && it.who == id -> null
                        it is Beat.Hide && it.who == id -> null
                        it is Beat.Pose && it.who == id -> null
                        it is Beat.Say && it.who == id -> it.copy(who = null, emote = if (Compile.animOfEmote(it.emote) != null) "talk" else it.emote)
                        else -> it
                    }
                })
            },
        ))
    }

    // ---------------------------------------------------------------- the scenes

    fun addScene(name: String): String {
        val n = name.trim().ifEmpty { "Scene ${project.scenes.size + 1}" }
        val id = freshId(n, project.scenes.map { it.id })
        val scene = Scene(id, n, Stage("stage_$id", "Studio", EditorChoices.STUDIO, "day"), emptyList())
        set(project.copy(scenes = project.scenes + scene, start = project.start?.takeIf { s -> project.scenes.any { it.id == s } } ?: id))
        return id
    }

    fun renameScene(id: String, name: String) {
        val n = name.trim()
        if (n.isNotEmpty()) mapScene(id) { it.copy(name = n) }
    }

    fun moveScene(id: String, delta: Int): Boolean {
        val from = project.scenes.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in project.scenes.indices) return false
        set(project.copy(scenes = project.scenes.toMutableList().also { it.add(to, it.removeAt(from)) }))
        return true
    }

    /** Removes the scene; Goto beats and choice options that led there go too (an empty choice with them). */
    fun deleteScene(id: String) {
        if (sceneOf(id) == null) return
        val left = project.scenes.filter { it.id != id }.map { s ->
            s.copy(beats = s.beats.mapNotNull { b ->
                when (b) {
                    is Beat.Goto -> if (b.scene == id) null else b
                    is Beat.Choice -> b.options.filter { it.goto != id }.let { o -> if (o.isEmpty()) null else b.copy(options = o) }
                    else -> b
                }
            })
        }
        set(project.copy(scenes = left, start = if (project.start == id) left.firstOrNull()?.id else project.start))
    }

    fun setStart(id: String) {
        if (sceneOf(id) != null) set(project.copy(start = id))
    }

    fun setStage(sceneId: String, place: String, light: String, placeName: String = place) =
        mapScene(sceneId) { it.copy(stage = it.stage.copy(name = placeName, place = place, light = light)) }

    // ---------------------------------------------------------------- the beats

    /** A fresh beat of [kind], filled in from what the project has (the first NPC, the first scene). */
    fun newBeat(kind: BeatKind, sceneId: String? = null): Beat {
        val who = project.cast.firstOrNull()?.id
        val other = project.scenes.firstOrNull { it.id != sceneId }?.id ?: project.scenes.firstOrNull()?.id ?: ""
        return when (kind) {
            BeatKind.SAY -> Beat.Say(who, "talk", "")
            BeatKind.SHOW -> Beat.Show(who ?: "", "center", "bust")
            BeatKind.HIDE -> Beat.Hide(who ?: "")
            BeatKind.POSE -> Beat.Pose(who ?: "", "")
            BeatKind.CAMERA -> Beat.Camera("wide")
            BeatKind.CHOICE -> Beat.Choice("", listOf(Beat.Choice.Option("", other), Beat.Choice.Option("", other)))
            BeatKind.GOTO -> Beat.Goto(other)
            BeatKind.END -> Beat.End("The End")
        }
    }

    /** Adds [beat] at [at] (the end by default); returns where it went, or -1. */
    fun addBeat(sceneId: String, beat: Beat, at: Int? = null): Int {
        val s = sceneOf(sceneId) ?: return -1
        val i = (at ?: s.beats.size).coerceIn(0, s.beats.size)
        mapScene(sceneId) { it.copy(beats = it.beats.toMutableList().also { l -> l.add(i, beat) }) }
        return i
    }

    fun replaceBeat(sceneId: String, index: Int, beat: Beat) {
        val s = sceneOf(sceneId) ?: return
        if (index !in s.beats.indices) return
        mapScene(sceneId) { it.copy(beats = it.beats.toMutableList().also { l -> l[index] = beat }) }
    }

    fun removeBeat(sceneId: String, index: Int) {
        val s = sceneOf(sceneId) ?: return
        if (index !in s.beats.indices) return
        mapScene(sceneId) { it.copy(beats = it.beats.toMutableList().also { l -> l.removeAt(index) }) }
    }

    /** Moves a beat up (-1) or down (+1); returns its new place (the old one if it can't go). */
    fun moveBeat(sceneId: String, index: Int, delta: Int): Int {
        val s = sceneOf(sceneId) ?: return index
        val to = index + delta
        if (index !in s.beats.indices || to !in s.beats.indices) return index
        mapScene(sceneId) { it.copy(beats = it.beats.toMutableList().also { l -> l.add(to, l.removeAt(index)) }) }
        return to
    }

    fun duplicateBeat(sceneId: String, index: Int): Int {
        val b = sceneOf(sceneId)?.beats?.getOrNull(index) ?: return -1
        return addBeat(sceneId, b, index + 1)
    }
}

// -------------------------------------------------------------------- the stage, for the preview

/**
 * A place from assets/places.json: its marks (every place has `left`, `center` and `right`, where a Show beat stands
 * someone), its camera views, which side "two" is filmed from, and whether it is [void] (the studio: nothing of the city
 * is drawn, only the people).
 */
data class PlaceInfo(
    val id: String, val name: String, val marks: Map<String, Vec3>, val views: Map<String, CameraPose>,
    val twoSide: Int = 1, val void: Boolean = false,
) {
    companion object {
        /** The plain stage: nothing but the people, in the void (assets/places.json's "studio" says the same). */
        val STUDIO = PlaceInfo(
            EditorChoices.STUDIO, "Studio",
            mapOf("left" to Vec3(-1.4f, 0f, 0f), "center" to Vec3(0f, 0f, 0f), "right" to Vec3(1.4f, 0f, 0f)),
            mapOf("studio" to CameraPose(Vec3(0f, -7f, 2.2f), Vec3(0f, 0f, 1.2f), 42f)),
            void = true,
        )

        private fun vec(a: JSONArray) = Vec3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())

        /** assets/places.json's places (the file's order), the studio first (the file's own, when it has one). */
        fun parseAll(json: String): List<PlaceInfo> {
            val p = JSONObject(json).getJSONObject("places")
            val places = p.keys().asSequence().map { id ->
                val o = p.getJSONObject(id)
                PlaceInfo(
                    id, o.optString("name", id),
                    o.optJSONObject("marks")?.let { m -> m.keys().asSequence().associateWith { vec(m.getJSONArray(it)) } } ?: emptyMap(),
                    o.optJSONObject("views")?.let { v ->
                        v.keys().asSequence().associateWith { k ->
                            val view = v.getJSONObject(k)
                            CameraPose(vec(view.getJSONArray("eye")), vec(view.getJSONArray("at")), view.optDouble("fov", 50.0).toFloat())
                        }
                    } ?: emptyMap(),
                    o.optInt("twoSide", 1),
                    o.optBoolean("void", id == EditorChoices.STUDIO),
                )
            }.toList()
            val studio = places.firstOrNull { it.id == EditorChoices.STUDIO } ?: STUDIO
            return listOf(studio) + places.filter { it.id != EditorChoices.STUDIO }
        }
    }
}

/** Who is on stage after some beats, and the camera's shot as script words ("bust ID", "two A B", "wide", "view NAME"; empty: none yet). */
data class Preview(val shown: List<Shown>, val shot: List<String>) {
    data class Shown(val actor: String, val spot: String)
}

/** Where someone stands: ground position and facing in radians. */
data class Standing(val pos: Vec3, val facing: Float)

object Staging {
    const val HEIGHT = 2.1f
    private const val SPREAD = 1.4f

    /** A shot string as words with the shown actor filled in; null when unreadable, empty for none (as Compile reads them). */
    fun shotWords(shot: String, shown: String?): List<String>? {
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

    /** The stage as it is after the first [upTo] beats (all of them when null). */
    fun preview(scene: Scene, upTo: Int? = null): Preview {
        var shown = listOf<Preview.Shown>()
        var shot = listOf("wide")
        for (b in scene.beats.take(upTo ?: scene.beats.size)) {
            when (b) {
                is Beat.Show -> {
                    shown = shown.filter { it.actor != b.who && it.spot != b.spot } + Preview.Shown(b.who, b.spot)
                    shotWords(b.shot, b.who)?.takeIf { it.isNotEmpty() }?.let { shot = it }
                }
                is Beat.Hide -> shown = shown.filter { it.actor != b.who }
                is Beat.Camera -> shotWords(b.shot, null)?.takeIf { it.isNotEmpty() }?.let { shot = it }
                else -> Unit
            }
        }
        return Preview(shown, shot)
    }

    /**
     * The library animation each actor holds after the first [upTo] beats (all when null): the last Pose beat (or Say
     * with a library animation as its emote) for them; a Hide, or a Say with a game emote, lets go of it.
     */
    fun poses(scene: Scene, upTo: Int? = null): Map<String, String> {
        val held = LinkedHashMap<String, String>()
        for (b in scene.beats.take(upTo ?: scene.beats.size)) {
            when (b) {
                is Beat.Pose -> if (b.animId.isBlank()) held.remove(b.who) else held[b.who] = b.animId
                is Beat.Hide -> held.remove(b.who)
                is Beat.Say -> if (b.who != null) {
                    val id = Compile.animOfEmote(b.emote)
                    if (id != null) { if (id.isNotBlank()) held[b.who] = id } else if (b.emote.isNotBlank() && b.emote != "talk") held.remove(b.who)
                }
                else -> Unit
            }
        }
        return held
    }

    private fun home(p: PlaceInfo): CameraPose? = p.views.values.firstOrNull()

    /** The middle of the stage: the marks' centre, or what the first view looks at. */
    fun anchor(p: PlaceInfo): Vec3 {
        if (p.marks.isNotEmpty()) {
            val m = p.marks.values
            return Vec3(m.map { it.x }.average().toFloat(), m.map { it.y }.average().toFloat(), m.map { it.z }.average().toFloat())
        }
        return home(p)?.at ?: Vec3(0f, 0f, 0f)
    }

    private fun eye(p: PlaceInfo): Vec3 = home(p)?.eye ?: Vec3(0f, -7f, 2f)

    /**
     * Left, centre or right of the stage, turned toward the first camera: the place's own mark of that name (where the
     * player stands them too), else worked out from the stage's middle as that camera sees it.
     */
    fun standing(p: PlaceInfo, spot: String): Standing {
        val e = eye(p)
        p.marks[spot]?.let { return Standing(it, Shots.facing(it, e)) }
        val a = anchor(p)
        val dx = a.x - e.x; val dy = a.y - e.y
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(0.01f)
        val fx = dx / len; val fy = dy / len
        val rx = fy; val ry = -fx   // to the viewer's right
        val k = when (spot) { "left" -> -SPREAD; "right" -> SPREAD; else -> 0f }
        val pos = Vec3(a.x + rx * k, a.y + ry * k, a.z)
        return Standing(pos, Shots.facing(pos, e))
    }

    /** The camera for the preview's shot; a shot of someone not on stage is the wide one. */
    fun pose(p: PlaceInfo, pre: Preview): CameraPose {
        val on = pre.shown.associate { it.actor to standing(p, it.spot) }
        val wide = home(p) ?: CameraPose(Vec3(0f, -7f, 2.2f), Vec3(0f, 0f, 1.2f), 42f)
        val w = pre.shot
        return when (w.firstOrNull()) {
            "bust" -> on[w.getOrNull(1)]?.let { Shots.bust(it.pos, it.facing, HEIGHT) } ?: wide
            "two" -> {
                val a = on[w.getOrNull(1)]; val b = on[w.getOrNull(2)]
                if (a != null && b != null) Shots.two(a.pos, b.pos, p.twoSide, HEIGHT)
                else (a ?: b)?.let { Shots.bust(it.pos, it.facing, HEIGHT) } ?: wide
            }
            "view" -> w.getOrNull(1)?.let { p.views[it] } ?: wide
            else -> wide
        }
    }

    /** Every mark of every place, for the renderer to load the city's props around. */
    fun allMarks(places: List<PlaceInfo>): List<Vec3> = places.flatMap { it.marks.values + listOfNotNull(home(it)?.at) }.filter { it != Vec3(0f, 0f, 0f) }
}
