package com.logie.wifecraft.simulation

import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Build mode's state (the live scene's build mode, ui/BuildModeUi.kt): the tool on the bar ([BuildBarTool]), what is selected
 * ([BuildSelection]: an item, a marker, a playing pair or an NPC), a handle being dragged ([BuildPhase.DRAGGING]), the
 * session's edits ([BuildDoc]) with undo and redo (each gesture one step: a drag is one, however long), and the report's
 * sending (sent, queued, failed). The scene is never paused by it. Every change is a new state. Pure.
 */
enum class BuildBarTool(val label: String) { SELECT("Select"), MARKER("Marker"), MOVE("Move"), INTERACT("Interact"), SEND("Send") }

enum class BuildPhase { IDLE, SELECTED, DRAGGING, SENDING, SENT, QUEUED, FAILED }

/** What is selected: its kind, its key (an item's ItemMapping key, a marker's id, an NPC's id) and its name. */
sealed class BuildSelection(val key: String, val name: String) {
    /** An item of the place: its scene object (null: a location doodad no scene object is), its frame and box. */
    class Item(key: String, name: String, val model: Int, val hitKey: String, val sceneObject: SceneObject?, val frame: ItemFrame,
               val halfX: Float, val halfY: Float, val top: Float) : BuildSelection(key, name)
    class Marker(id: String, name: String) : BuildSelection(id, name)
    /** An NPC ([key] its id); [playing]: in an animation (the gizmo moves its pair's shared origin, live). */
    class Npc(id: String, name: String, val playing: Boolean) : BuildSelection(id, name)
}

data class BuildState(
    val phase: BuildPhase = BuildPhase.IDLE,
    val tool: BuildBarTool = BuildBarTool.SELECT,
    val selection: BuildSelection? = null,
    val doc: BuildDoc = BuildDoc(),
    val undo: List<BuildDoc> = emptyList(),
    val redo: List<BuildDoc> = emptyList(),
    val snap: BuildSnap = BuildSnap.CM1,
    val handle: GizmoHandle? = null,
    val issue: Int = 0,
    val message: String? = null,
) {
    val canUndo: Boolean get() = undo.isNotEmpty() && phase != BuildPhase.DRAGGING && phase != BuildPhase.SENDING
    val canRedo: Boolean get() = redo.isNotEmpty() && phase != BuildPhase.DRAGGING && phase != BuildPhase.SENDING
    val canSend: Boolean get() = !doc.empty && phase != BuildPhase.SENDING && phase != BuildPhase.DRAGGING

    fun withTool(t: BuildBarTool): BuildState = copy(tool = t, message = null)
    fun withSnap(s: BuildSnap): BuildState = copy(snap = s)

    fun select(s: BuildSelection?): BuildState =
        if (phase == BuildPhase.DRAGGING || phase == BuildPhase.SENDING) this
        else copy(phase = if (s == null) BuildPhase.IDLE else BuildPhase.SELECTED, selection = s, handle = null, message = null)

    /** One step: [change] made to the edits, the old ones kept for undo (redo forgotten). */
    fun edit(change: (BuildDoc) -> BuildDoc): BuildState {
        if (phase == BuildPhase.SENDING) return this
        val next = change(doc)
        if (next == doc) return this
        return copy(doc = next, undo = (undo + doc).takeLast(MAX_UNDO), redo = emptyList(), message = null,
            phase = if (phase == BuildPhase.DRAGGING) phase else if (selection != null) BuildPhase.SELECTED else BuildPhase.IDLE)
    }

    /** A finger took [h]: the edits before the drag kept as one undo step. */
    fun beginDrag(h: GizmoHandle): BuildState =
        if (selection == null || phase == BuildPhase.SENDING || phase == BuildPhase.DRAGGING) this
        else copy(phase = BuildPhase.DRAGGING, handle = h, undo = (undo + doc).takeLast(MAX_UNDO), redo = emptyList(), message = null)

    /** While dragging: the edits as they are now (no new step). */
    fun dragged(change: (BuildDoc) -> BuildDoc): BuildState = if (phase != BuildPhase.DRAGGING) this else copy(doc = change(doc))

    /** The finger let go: the drag committed (nothing moved: its step dropped). */
    fun endDrag(): BuildState {
        if (phase != BuildPhase.DRAGGING) return this
        val unchanged = undo.lastOrNull() == doc
        return copy(phase = if (selection != null) BuildPhase.SELECTED else BuildPhase.IDLE, handle = null,
            undo = if (unchanged) undo.dropLast(1) else undo)
    }

    fun undo(): BuildState {
        if (!canUndo) return this
        return copy(doc = undo.last(), undo = undo.dropLast(1), redo = redo + doc, message = null)
    }

    fun redo(): BuildState {
        if (!canRedo) return this
        return copy(doc = redo.last(), redo = redo.dropLast(1), undo = undo + doc, message = null)
    }

    fun sending(): BuildState = if (canSend) copy(phase = BuildPhase.SENDING, message = null) else this
    fun sent(number: Int): BuildState = if (phase == BuildPhase.SENDING) copy(phase = BuildPhase.SENT, issue = number, message = null) else this
    fun queued(why: String): BuildState = if (phase == BuildPhase.SENDING) copy(phase = BuildPhase.QUEUED, message = why) else this
    fun failed(why: String): BuildState = if (phase == BuildPhase.SENDING) copy(phase = BuildPhase.FAILED, message = why) else this

    companion object { const val MAX_UNDO = 64 }
}

/** The edits a [BuildDoc] gets (each one gesture). */
object BuildEdits {
    fun addMarker(d: BuildDoc, m: BuildMarker): BuildDoc = d.copy(markers = d.markers + m)
    fun moveMarker(d: BuildDoc, id: String, p: BuildPose): BuildDoc = d.copy(markers = d.markers.map { if (it.id == id) it.copy(pose = p) else it })
    fun renameMarker(d: BuildDoc, id: String, name: String): BuildDoc =
        d.copy(markers = d.markers.map { if (it.id == id) it.copy(name = name.trim().take(BuildDocs.MAX_NAME).ifEmpty { it.type.label }) else it })
    fun retypeMarker(d: BuildDoc, id: String, t: MarkerType): BuildDoc = d.copy(markers = d.markers.map { if (it.id == id) it.copy(type = t) else it })
    fun deleteMarker(d: BuildDoc, id: String): BuildDoc = d.copy(markers = d.markers.filter { it.id != id })
    fun setItem(d: BuildDoc, i: ItemInteraction): BuildDoc = d.copy(items = d.items + (i.key to i))
    fun removeItem(d: BuildDoc, key: String): BuildDoc = d.copy(items = d.items - key)
    fun movePair(d: BuildDoc, m: PairMove): BuildDoc = d.copy(pairs = d.pairs + (m.actorId to m))
    fun resetPair(d: BuildDoc, actorId: String): BuildDoc = d.copy(pairs = d.pairs - actorId)
    fun remember(d: BuildDoc, o: RaceOffset): BuildDoc = d.copy(raceOffsets = RaceOffsets.upsert(d.raceOffsets, o))

    /** A new marker's name: its type and the next free number ("Sit 2"). */
    fun markerName(d: BuildDoc, t: MarkerType): String {
        val used = d.markers.filter { it.type == t }.map { it.name }.toSet()
        var n = 1
        while ("${t.label} $n" in used) n++
        return "${t.label} $n"
    }
}

/**
 * Build mode's report: the session's markers, item interactions, moved pairs and remembered placements of one place, as one
 * GitHub issue titled "Build report: <place>", labelled [LABEL]: a readable summary and the same as a fenced JSON block
 * (schema 2) for tools/item_reports.py. Nothing personal: no device, account or path; the NPCs only by race and sex.
 */
data class BuildReport(val id: String, val placeId: String, val placeName: String, val appVersion: String, val build: Int,
                       val doc: BuildDoc, val notes: String = "") {
    fun title(): String = "Build report: $placeName"

    fun toJson(): JSONObject = doc.toJson(placeId, placeName)
        .put("kind", "build-report").put("report", id)
        .put("app", JSONObject().put("version", appVersion).put("build", build))
        .put("notes", notes.take(ItemMapping.MAX_NOTES))

    fun body(): String = buildString {
        appendLine("**Build report** for $placeName (`$placeId`): ${doc.markers.size} marker(s), ${doc.items.size} item(s), " +
            "${doc.pairs.size} moved pair(s), ${doc.raceOffsets.size} race placement(s).")
        appendLine()
        for (m in doc.markers) appendLine(String.format(Locale.ROOT, "- Marker **%s** (%s) at x %.2f, y %.2f, z %.2f, facing %.0f°%s",
            m.name, m.type.label, m.pose.x, m.pose.y, m.pose.z, m.pose.yawDeg, m.itemKey?.let { " for `$it`" } ?: ""))
        for (i in doc.items.values) appendLine(String.format(Locale.ROOT, "- Item **%s** (`%s`, model %d) as %s: %s, surface %.2f yd",
            i.name, i.key, i.model, i.cls.label, i.supports.sortedBy { it.ordinal }.joinToString { it.label.lowercase() }.ifEmpty { "its class's" }, i.surface))
        for (p in doc.pairs.values) appendLine(String.format(Locale.ROOT, "- Pair **%s** origin moved to x %.2f, y %.2f, z %.2f, facing %.0f° (computed x %.2f, y %.2f, z %.2f, %.0f°)",
            p.animation, p.to.x, p.to.y, p.to.z, p.to.yawDeg, p.from.x, p.from.y, p.from.z, p.from.yawDeg))
        for (o in doc.raceOffsets) appendLine("- Race placement: ${o.describe()}")
        if (notes.isNotBlank()) appendLine("- Notes: ${notes.take(ItemMapping.MAX_NOTES).replace("\n", " ")}")
        appendLine("- Wifecraft $appVersion (build $build)")
        appendLine()
        appendLine("```json")
        appendLine(toJson().toString(1))
        appendLine("```")
    }
}

object BuildReports {
    const val LABEL = "build-report"

    /** What is wrong with a build report's JSON (empty: it is good). */
    fun validate(o: JSONObject): List<String> {
        val bad = ArrayList<String>()
        if (o.optInt("schema", -1) != BuildDocs.SCHEMA) bad += "schema is not ${BuildDocs.SCHEMA}"
        if (o.optJSONObject("place")?.optString("id").isNullOrBlank()) bad += "no place id"
        if (listOf("markers", "items", "pairs", "raceOffsets").none { (o.optJSONArray(it)?.length() ?: 0) > 0 }) bad += "nothing in it"
        return bad
    }

    /** [r] sent as an issue (or why not), with [http] (the app's UrlTransport, the tests' fake). */
    fun send(repo: String, token: String, r: BuildReport, http: HttpTransport, api: String = ItemReports.API): SendResult =
        ItemReports.send(repo, token, r.title(), r.body(), r.appVersion, http, api, LABEL)

    /** [r] kept to go later in [queue] (files/build/queue). */
    fun enqueue(queue: ReportQueue, r: BuildReport, now: Long = System.currentTimeMillis()): File = queue.enqueue(r.id, "build/${r.placeId}", r.title(), r.body(), now)
}
