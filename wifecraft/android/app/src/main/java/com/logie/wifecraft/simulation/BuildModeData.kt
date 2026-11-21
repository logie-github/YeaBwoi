package com.logie.wifecraft.simulation

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Build mode's things (the live scene's build mode: ui/BuildModeUi.kt): location markers ([BuildMarker]: a spot to stand, sit,
 * lie or kneel, a pair's shared origin, a camera spot), interactions given to items ([ItemInteraction]: any prop classed as a
 * bed, a seat, a top, the floor, a wall, with the animation classes it takes and its surface's height), pairs moved while
 * they played ([PairMove]) and remembered race placements ([RaceOffset]); all in the place's own frame (locations.json's,
 * yards, degrees). The session's lot ([BuildDoc]) is kept on the phone (files/build/<place>.json) and sent as one GitHub issue
 * ([BuildReport], schema 2) that tools/item_reports.py merges into assets/item_overrides.json, assets/place_markers.json and
 * assets/race_offsets.json. Pure.
 */
enum class MarkerType(val id: String, val label: String, val r: Float, val g: Float, val b: Float) {
    STAND("stand", "Stand", 0.35f, 0.85f, 0.45f),
    SIT("sit", "Sit", 0.30f, 0.65f, 1.00f),
    LIE("lie", "Lie", 0.75f, 0.45f, 1.00f),
    KNEEL("kneel", "Kneel", 1.00f, 0.60f, 0.25f),
    PAIR_ORIGIN("pair_origin", "Pair origin", 1.00f, 0.40f, 0.60f),
    CAMERA("camera", "Camera spot", 1.00f, 0.85f, 0.30f);

    companion object { fun of(id: String?): MarkerType? = entries.firstOrNull { it.id == id } }
}

/** A marker: [name], [type], where ([pose]), the item it belongs to ([itemKey]); [shipped]: from assets/place_markers.json. */
data class BuildMarker(val id: String, val name: String, val type: MarkerType, val pose: BuildPose, val itemKey: String? = null,
                       val shipped: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type.id)
        .put("x", rd3(pose.x)).put("y", rd3(pose.y)).put("z", rd3(pose.z)).put("yawDeg", rd3(pose.yawDeg)).put("item", itemKey ?: JSONObject.NULL)

    companion object {
        fun of(o: JSONObject, shipped: Boolean = false): BuildMarker? {
            val type = MarkerType.of(o.optString("type")) ?: return null
            val p = BuildPose(o.optDouble("x", Double.NaN).toFloat(), o.optDouble("y", Double.NaN).toFloat(),
                o.optDouble("z", Double.NaN).toFloat(), o.optDouble("yawDeg", 0.0).toFloat())
            if (!p.x.isFinite() || !p.y.isFinite() || !p.z.isFinite() || !p.yawDeg.isFinite()) return null
            return BuildMarker(o.optString("id").ifEmpty { "m${(p.x * 100).toInt()}_${(p.y * 100).toInt()}" },
                o.optString("name").ifEmpty { type.label }.take(BuildDocs.MAX_NAME), type, p,
                o.optString("item").takeIf { o.has("item") && !o.isNull("item") && it.isNotEmpty() }, shipped)
        }
    }
}

/**
 * An interaction given to an item: its key (ItemMapping.key: place/object/model; a location doodad's object is
 * "doodad:<index>"), its name and model, where it stands ([frame], the place's frame) and its box ([halfX], [halfY], [top]
 * yards, its own frame), the class picked ([cls]), the animation classes ticked ([supports]), the surface's height ([surface],
 * yards over its floor) and the anchor ([anchor]: where a body goes on it, in its own frame), and the scene's object it
 * stands in for ([slot]: the furniture system's bed, couch, chair or table; null: the item is a scene object itself, or none).
 */
data class ItemInteraction(
    val key: String, val name: String, val model: Int, val frame: ItemFrame, val halfX: Float, val halfY: Float, val top: Float,
    val cls: ItemClass, val supports: Set<SupportClass>, val surface: Float, val anchor: LocalOffset, val slot: SceneObject? = null,
) {
    /** The override the furniture system takes (its class, its anchor as the class's held place, the ticks). */
    fun override(): ItemOverride {
        val pose = when {
            SupportClass.LYING in supports && cls != ItemClass.SEAT -> HeldPose.LIE
            SupportClass.SITTING in supports && cls != ItemClass.FLOOR && cls != ItemClass.WALL -> HeldPose.SIT
            else -> cls.holds.first()
        }
        return ItemOverride(key, cls, surface.takeIf { it > 0f }, mapOf(cls to HeldSpot(anchor, pose)), supports = supports)
    }

    fun toJson(): JSONObject = JSONObject().put("key", key).put("name", name).put("model", model).put("class", cls.id)
        .put("supports", JSONArray(supports.sortedBy { it.ordinal }.map { it.id })).put("surface", rd3(surface))
        .put("position", JSONArray(listOf(rd3(frame.x), rd3(frame.y), rd3(frame.z)))).put("yawDeg", rd3(Math.toDegrees(frame.yaw.toDouble()).toFloat()))
        .put("scale", rd3(frame.scale)).put("box", JSONObject().put("x", rd3(2 * halfX)).put("y", rd3(2 * halfY)).put("z", rd3(top)))
        .put("anchor", JSONObject().put("x", rd3(anchor.x)).put("y", rd3(anchor.y)).put("z", rd3(anchor.z)).put("yaw", rd3(anchor.yawDeg)))
        .put("slot", slot?.name ?: JSONObject.NULL)

    companion object {
        fun of(o: JSONObject): ItemInteraction? {
            val key = o.optString("key").ifEmpty { return null }
            val cls = ItemClass.of(o.optString("class")) ?: return null
            val pos = o.optJSONArray("position") ?: return null
            val frame = ItemFrame(pos.optDouble(0, Double.NaN).toFloat(), pos.optDouble(1, Double.NaN).toFloat(), pos.optDouble(2, Double.NaN).toFloat(),
                Math.toRadians(o.optDouble("yawDeg", 0.0)).toFloat(), o.optDouble("scale", 1.0).toFloat())
            if (!frame.x.isFinite() || !frame.y.isFinite() || !frame.z.isFinite()) return null
            val a = o.optJSONObject("anchor") ?: JSONObject()
            val anchor = LocalOffset(a.optDouble("x", 0.0).toFloat(), a.optDouble("y", 0.0).toFloat(), a.optDouble("z", 0.0).toFloat(), a.optDouble("yaw", 0.0).toFloat())
            val box = o.optJSONObject("box") ?: JSONObject()
            val slot = o.optString("slot").takeIf { o.has("slot") && !o.isNull("slot") }?.let { n -> SceneObject.entries.firstOrNull { it.name == n } }
            return ItemInteraction(key, o.optString("name").ifEmpty { key }, o.optInt("model"), frame, box.optDouble("x", 1.0).toFloat() / 2f,
                box.optDouble("y", 1.0).toFloat() / 2f, box.optDouble("z", 0.5).toFloat(), cls, SupportClass.parse(o.optJSONArray("supports")),
                o.optDouble("surface", 0.0).toFloat(), anchor, slot)
        }

        /** The scene object a newly classed doodad stands in for: a bed's or lying thing's the bed, a seat the chair, a top or wall the table. */
        fun slotFor(cls: ItemClass, supports: Set<SupportClass>): SceneObject? = when (cls) {
            ItemClass.BED -> SceneObject.BED
            ItemClass.SEAT -> SceneObject.CHAIR
            ItemClass.TABLETOP, ItemClass.WALL -> SceneObject.TABLE
            ItemClass.FLOOR -> null
            ItemClass.OTHER -> when {
                SupportClass.LYING in supports -> SceneObject.BED
                SupportClass.SITTING in supports -> SceneObject.CHAIR
                SupportClass.LEANING in supports -> SceneObject.TABLE
                else -> null
            }
        }
    }
}

/**
 * A pair (or one NPC) moved while playing: the animation ([animation] its id, the name it plays under), who played which role
 * ([roles]: race and sex), the shared origin where it was computed ([from]) and where it was left ([to]), the item it was
 * on ([itemKey]).
 */
data class PairMove(val actorId: String, val animation: String, val roles: List<RoleActor>, val from: BuildPose, val to: BuildPose,
                    val itemKey: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("animationId", animation).put("animationName", animation)
        .put("roles", JSONArray(roles.mapIndexed { i, r -> JSONObject().put("role", if (i == 0) "A" else "B").put("race", r.race).put("sex", if (r.female) "female" else "male") }))
        .put("origin", pose(to)).put("computed", pose(from)).put("item", itemKey ?: JSONObject.NULL)

    companion object {
        private fun pose(p: BuildPose) = JSONObject().put("x", rd3(p.x)).put("y", rd3(p.y)).put("z", rd3(p.z)).put("yawDeg", rd3(p.yawDeg))
        private fun poseOf(o: JSONObject?): BuildPose? = o?.let {
            BuildPose(it.optDouble("x", Double.NaN).toFloat(), it.optDouble("y", Double.NaN).toFloat(), it.optDouble("z", Double.NaN).toFloat(), it.optDouble("yawDeg", 0.0).toFloat())
        }?.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }

        fun of(o: JSONObject): PairMove? {
            val to = poseOf(o.optJSONObject("origin")) ?: return null
            val from = poseOf(o.optJSONObject("computed")) ?: to
            val roles = o.optJSONArray("roles")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { RoleActor(it.optInt("race"), it.optString("sex") == "female") } } }.orEmpty()
            return PairMove("", o.optString("animationId"), roles, from, to, o.optString("item").takeIf { o.has("item") && !o.isNull("item") })
        }
    }
}

private fun rd3(v: Float): Double = Math.round(v * 1000.0) / 1000.0

/** One session's build edits: markers, item interactions (by key), pair moves (by actor), remembered race placements. */
data class BuildDoc(
    val markers: List<BuildMarker> = emptyList(),
    val items: Map<String, ItemInteraction> = emptyMap(),
    val pairs: Map<String, PairMove> = emptyMap(),
    val raceOffsets: List<RaceOffset> = emptyList(),
) {
    val empty: Boolean get() = markers.isEmpty() && items.isEmpty() && pairs.isEmpty() && raceOffsets.isEmpty()
    val count: Int get() = markers.size + items.size + pairs.size + raceOffsets.size

    fun toJson(placeId: String, placeName: String): JSONObject = JSONObject().put("schema", BuildDocs.SCHEMA)
        .put("place", JSONObject().put("id", placeId).put("name", placeName))
        .put("markers", JSONArray(markers.map { it.toJson() }))
        .put("items", JSONArray(items.values.map { it.toJson() }))
        .put("pairs", JSONArray(pairs.values.map { it.toJson().put("actor", it.actorId) }))
        .put("raceOffsets", JSONArray(raceOffsets.map { it.toJson() }))

    companion object {
        fun of(o: JSONObject): BuildDoc {
            fun <T> list(name: String, f: (JSONObject) -> T?): List<T> =
                o.optJSONArray(name)?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(f) } }.orEmpty()
            return BuildDoc(list("markers") { BuildMarker.of(it) }, list("items") { ItemInteraction.of(it) }.associateBy { it.key },
                list("pairs") { j -> PairMove.of(j)?.copy(actorId = j.optString("actor")) }.associateBy { it.actorId },
                list("raceOffsets") { RaceOffset.of(it) })
        }
    }
}

object BuildDocs {
    const val SCHEMA = 2
    const val MAX_NAME = 40

    /** The shipped markers (assets/place_markers.json): each place's, by its id. Bad entries are skipped. */
    fun shippedMarkers(json: String): Map<String, List<BuildMarker>> {
        val root = try { JSONObject(json) } catch (e: Exception) { return emptyMap() }
        val places = root.optJSONObject("places") ?: return emptyMap()
        val out = LinkedHashMap<String, List<BuildMarker>>()
        for (id in places.keys()) {
            val a = places.optJSONArray(id) ?: continue
            out[id] = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { BuildMarker.of(it, shipped = true) } }
        }
        return out
    }

    /** The phone's item interactions (files/build/items.json): by key, every place's. */
    fun parseItems(json: String): Map<String, ItemInteraction> {
        val a = try { JSONObject(json).optJSONArray("items") } catch (e: Exception) { null } ?: return emptyMap()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(ItemInteraction::of) }.associateBy { it.key }
    }

    fun itemsJson(items: Collection<ItemInteraction>): String =
        JSONObject().put("schema", SCHEMA).put("items", JSONArray(items.map { it.toJson() })).toString(1)

    /** A file name for a place's session (letters, digits, - and _ only). */
    fun fileName(placeId: String): String = placeId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]+"), "_").trim('_').ifEmpty { "place" } + ".json"
}

/**
 * Build mode's files on the phone ([dir]: files/build): each place's session ([saveDoc], [loadDoc]), the item interactions
 * (items.json: they take effect at once in every scene of their place) and the remembered race placements (race_offsets.json).
 */
class BuildStore(private val dir: File) {
    val raceOffsets get() = RaceOffsetStore(File(dir, "race_offsets.json"))
    private val itemsFile get() = File(dir, "items.json")

    fun loadDoc(placeId: String): BuildDoc = runCatching { BuildDoc.of(JSONObject(File(dir, BuildDocs.fileName(placeId)).readText())) }.getOrDefault(BuildDoc())

    fun saveDoc(placeId: String, placeName: String, doc: BuildDoc): File {
        val f = File(dir, BuildDocs.fileName(placeId))
        write(f, doc.toJson(placeId, placeName).toString(1))
        return f
    }

    fun loadItems(): Map<String, ItemInteraction> = runCatching { BuildDocs.parseItems(itemsFile.readText()) }.getOrDefault(emptyMap())

    fun saveItem(i: ItemInteraction): Map<String, ItemInteraction> = (loadItems() + (i.key to i)).also { write(itemsFile, BuildDocs.itemsJson(it.values)) }
    fun deleteItem(key: String): Map<String, ItemInteraction> = (loadItems() - key).also { write(itemsFile, BuildDocs.itemsJson(it.values)) }

    private fun write(f: File, text: String) {
        dir.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }
}

/**
 * A playing animation as build mode sees it (SceneSimulation.playingOf): its [name] (the animation's id here), the [lead] and
 * [other] NPCs, its shared origin and heading now ([anchor], [heading]) and as computed ([baseAnchor], [baseHeading]), the
 * item it is on, its roles as computed and each role's live nudge ([extra]). The simulation's frame.
 */
data class PlayingPair(
    val name: String, val lead: String, val other: String?, val anchor: com.logie.wifecraft.story.Vec3, val heading: Float,
    val baseAnchor: com.logie.wifecraft.story.Vec3, val baseHeading: Float, val furniture: SceneObject?,
    val roles: List<PlaybackRole>, val extra: List<RoleDelta>,
) {
    val moved: Boolean get() = baseAnchor != anchor || baseHeading != heading || extra.any { !it.none }
    /** The role [id] plays (0 the lead's, 1 the other's; -1 neither). */
    fun roleOf(id: String): Int = if (id == lead) 0 else if (id == other) 1 else -1
}
