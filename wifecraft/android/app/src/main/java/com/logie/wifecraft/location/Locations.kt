package com.logie.wifecraft.location

import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import org.json.JSONArray
import org.json.JSONObject

/**
 * assets/locations.json as the Kotlin side needs it (the renderer reads the geometry itself: cpp engine/location.h): each
 * location's name, its views (the camera, where someone stands and faces) and its files, pack by pack, to fetch before
 * SceneView.loadLocation. Pure: org.json only.
 */
data class LocationInfo(
    val id: String,
    val name: String,
    /** "interior" (a real building of the game, furnished as the game furnishes it), "composed" (a real building of the
     *  game with props of the game added to it) or the Embassy's "interior+exterior". */
    val kind: String,
    val views: Map<String, View>,
    val packs: List<Pack>,
    /** The area (AreaTable) whose music and ambience it has (ambience.area; 0: none given). */
    val areaId: Int,
    val interactions: List<Interaction> = emptyList(),
    /** Whose home it is ("human", "orc"...; empty: no one's in particular). */
    val people: String = "",
    /** Its furniture for animations (locations.json "furniture"): beds, chairs, tables, each with its surface's height. */
    val furniture: List<FurniturePiece> = emptyList(),
) {
    data class ModelReference(val id: Int, val position: Vec3)
    /**
     * Furniture someone can use: walked to at [entry] (the floor beside it), then used at [pose] (lying on the bed's
     * mattress, sitting in the chair; the entry itself when the row names no pose), facing [facing] (radians, the
     * location's frame). [models] are the furniture's own models in the scene, found by id near their position.
     */
    data class Interaction(
        val item: String, val entry: Vec3, val facing: Float, val models: List<ModelReference>, val pose: Vec3 = entry,
    )
    data class View(val camera: CameraPose?, val spawn: Vec3?, val facing: Float)
    data class Pack(val name: String, val files: List<Pair<Int, Long>>) {
        val bytes: Long get() = files.sumOf { it.second }
    }

    /** Every file of the location (each once), in pack order; without the sound packs when [sound] is false. */
    fun files(sound: Boolean = true): List<Pair<Int, Long>> =
        packs.filter { sound || it.name != SOUND }.flatMap { it.files }.distinctBy { it.first }

    val bytes: Long get() = files().sumOf { it.second }

    companion object {
        const val SOUND = "sound"

        private fun vec(a: JSONArray?): Vec3? =
            if (a == null || a.length() < 3) null else Vec3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())

        /** Every location in the file, in its order. */
        fun parseAll(json: String): List<LocationInfo> {
            val root = JSONObject(json)
            val list = root.optJSONArray("locations") ?: return if (root.has("wmo")) listOf(parse(root)) else emptyList()
            return List(list.length()) { parse(list.getJSONObject(it)) }
        }

        /** One entry. */
        fun parse(e: JSONObject): LocationInfo {
            val spawns = e.optJSONObject("spawns")
            val views = LinkedHashMap<String, View>()
            e.optJSONObject("views")?.let { v ->
                for (name in v.keys()) {
                    val o = v.getJSONObject(name)
                    val cam = o.optJSONObject("camera") ?: o
                    val eye = vec(cam.optJSONArray("eye"))
                    val at = vec(cam.optJSONArray("at") ?: cam.optJSONArray("target"))
                    val camera = if (eye != null && at != null) CameraPose(eye, at, cam.optDouble("fov", 50.0).toFloat()) else null
                    val sp = o.opt("spawn") ?: spawns?.opt(name)
                    val (spawn, facing) = when (sp) {
                        is JSONObject -> vec(sp.optJSONArray("pos") ?: sp.optJSONArray("position")) to sp.optDouble("facing", 0.0).toFloat()
                        is JSONArray -> vec(sp) to (if (sp.length() >= 4) sp.getDouble(3).toFloat() else 0f)
                        else -> null to 0f
                    }
                    views[name] = View(camera, spawn, facing)
                }
            }
            val packs = ArrayList<Pack>()
            e.optJSONObject("files")?.optJSONArray("packs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val files = p.getJSONArray("files")
                    packs += Pack(p.getString("name"), List(files.length()) { k ->
                        val f = files.get(k)
                        if (f is JSONArray) f.getInt(0) to f.optLong(1, 0) else (f as Number).toInt() to 0L
                    })
                }
            }
            val interactions = ArrayList<Interaction>()
            e.optJSONArray("interactions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val row = arr.getJSONObject(i)
                    val entry = vec(row.optJSONArray("entry")) ?: continue
                    val models = row.getJSONArray("models")
                    val references = List(models.length()) { j ->
                        val model = models.getJSONObject(j)
                        ModelReference(model.getInt("id"), requireNotNull(vec(model.optJSONArray("position"))))
                    }
                    val pose = vec(row.optJSONArray("pose")) ?: entry
                    interactions += Interaction(row.getString("object"), entry, row.getDouble("facing").toFloat(), references, pose)
                }
            }
            val furniture = ArrayList<FurniturePiece>()
            e.optJSONArray("furniture")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONObject(i) ?: continue
                    val at = vec(row.optJSONArray("position")) ?: continue
                    furniture += FurniturePiece(row.optString("object"), row.optString("kind"), at,
                        row.optDouble("yaw", 0.0).toFloat(), row.optDouble("surface", 0.0).toFloat(),
                        FurnitureExtent.parse(row.optJSONObject("extent")))
                }
            }
            val id = e.getString("id")
            return LocationInfo(
                id, e.optString("name", id), e.optString("kind", ""), views, packs,
                e.optJSONObject("ambience")?.optInt("area", 0) ?: 0, interactions, e.optString("people", ""), furniture,
            )
        }
    }
}

/**
 * A piece of a location's furniture for animations (locations.json "furniture"): [item] the interaction it belongs to (BED,
 * CHAIR, TABLE: the scene object it is), its [kind] (simulation/FurnitureKind: BED_DOUBLE, CHAIR, TABLE...), [position] its
 * middle on the floor it stands on (the location's frame, yards), [yaw] its front's direction (radians: a bed's foot, the
 * way a sitter faces, the side a table is used from) and [surface] its bed's, seat's or top's height over that floor (yards;
 * 0: the kind's usual); [extent] its measured size and shape, when measured.
 */
data class FurniturePiece(
    val item: String, val kind: String, val position: Vec3, val yaw: Float, val surface: Float,
    /** Its real size and shape measured on its model (locations.json "extent"); null: not measured, the kind's usual. */
    val extent: FurnitureExtent? = null,
)

/** A rectangle of a piece's own frame (x its front, y its left; yards). */
data class PieceRect(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float) {
    val midX: Float get() = (minX + maxX) / 2
    val midY: Float get() = (minY + maxY) / 2
    val lengthX: Float get() = maxX - minX
    val widthY: Float get() = maxY - minY
    fun shifted(dx: Float, dy: Float) = PieceRect(minX + dx, maxX + dx, minY + dy, maxY + dy)
    fun overlaps(o: PieceRect) = minX < o.maxX && o.minX < maxX && minY < o.maxY && o.minY < maxY
}

/** A part of a piece standing in [rect] up to [top] over its floor (yards). */
data class PiecePart(val rect: PieceRect, val top: Float)

/**
 * A furniture piece's real extent (locations.json "extent", engine-test/furniture_measure on the piece's model as the engine
 * places it), in the row's own frame (x its front, y its left, z up from its floor; yards): its footprint [box] and
 * [height]; the [surface] people rest on (a bed's mattress, a seat, a table's top) at [surfaceTop]; a bed's [headEnd] along
 * x (-1 its back, as the row's yaw has it; +1 its front: the row's yaw is the wrong way round; 0 can't say); its solid
 * [parts] standing over the surface (a headboard, a footboard, posts, a chair's back and arms), the [rises] in the surface
 * that are lain on (a bolster) and the free room beside each [sides] ("front", "back", "left", "right"; at most 1.2).
 */
data class FurnitureExtent(
    val box: PieceRect, val height: Float, val surface: PieceRect, val surfaceTop: Float,
    val headEnd: Int = 0, val parts: List<PiecePart> = emptyList(), val rises: List<PiecePart> = emptyList(),
    val sides: Map<String, Float> = emptyMap(),
) {
    /** The free room beside [side] ("front", "back", "left", "right"); unmeasured: as much as is asked of it. */
    fun free(side: String): Float = sides[side] ?: Float.MAX_VALUE

    companion object {
        private fun rect(a: JSONArray?): PieceRect? = if (a == null || a.length() < 4) null else
            PieceRect(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())

        private fun parts(a: JSONArray?): List<PiecePart> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            val p = a.optJSONArray(i) ?: return@mapNotNull null
            if (p.length() < 5) null else PiecePart(requireNotNull(rect(p)), p.getDouble(4).toFloat())
        }

        /** A row's "extent", or null when it has none or it can't be read (the row then keeps the kind's usual shape). */
        fun parse(o: JSONObject?): FurnitureExtent? {
            if (o == null) return null
            val box = rect(o.optJSONArray("box")) ?: return null
            val surface = rect(o.optJSONArray("surfaceRect")) ?: return null
            val top = o.optDouble("surfaceTop", Double.NaN).toFloat()
            if (top.isNaN() || box.lengthX <= 0f || box.widthY <= 0f || surface.lengthX <= 0f || surface.widthY <= 0f) return null
            val sides = LinkedHashMap<String, Float>()
            o.optJSONObject("sides")?.let { sd -> for (k in sd.keys()) sides[k] = sd.getDouble(k).toFloat() }
            return FurnitureExtent(box, o.optDouble("height", top.toDouble()).toFloat(), surface, top,
                o.optInt("headEnd", 0).coerceIn(-1, 1), parts(o.optJSONArray("parts")), parts(o.optJSONArray("rises")), sides)
        }
    }
}

/**
 * What a WMO root names beside itself, read from its chunks (as cpp engine/wmo.cpp reads them): its groups' files (GFID,
 * the full groups only, not their LODs), its materials' textures (MOMT) and the models of its doodads (MODI through MODD) in
 * the doodad [sets] (0, the default, always). Pure.
 */
object WmoFiles {
    data class Deps(val groups: List<Int>, val textures: List<Int>, val models: List<Int>) {
        val all: List<Int> get() = (groups + textures + models).distinct()
    }

    fun deps(root: ByteArray, sets: Collection<Int> = listOf(0)): Deps {
        fun u32(at: Int): Int = (root[at].toInt() and 0xFF) or ((root[at + 1].toInt() and 0xFF) shl 8) or
            ((root[at + 2].toInt() and 0xFF) shl 16) or ((root[at + 3].toInt() and 0xFF) shl 24)
        var groupCount = -1
        var gfid = IntArray(0)
        var modi = IntArray(0)
        val textures = LinkedHashSet<Int>()
        val setRanges = ArrayList<Pair<Int, Int>>()
        val modd = ArrayList<Int>()
        var o = 0
        while (o + 8 <= root.size) {
            // stored back to front ("REVM" for MVER)
            val tag = String(charArrayOf(root[o + 3].toInt().toChar(), root[o + 2].toInt().toChar(), root[o + 1].toInt().toChar(), root[o].toInt().toChar()))
            val size = u32(o + 4)
            val at = o + 8
            if (size < 0 || at + size > root.size) break
            when (tag) {
                "MOHD" -> if (size >= 8) groupCount = u32(at + 4)
                "GFID" -> gfid = IntArray(size / 4) { u32(at + 4 * it) }
                "MODI" -> modi = IntArray(size / 4) { u32(at + 4 * it) }
                "MOMT" -> for (m in 0 until size / 64) for (off in intArrayOf(12, 24, 36)) u32(at + m * 64 + off).takeIf { it != 0 }?.let { textures += it }
                "MODS" -> for (s in 0 until size / 32) setRanges += u32(at + s * 32 + 20) to u32(at + s * 32 + 24)
                "MODD" -> for (d in 0 until size / 40) modd += u32(at + d * 40) and 0xFFFFFF
            }
            o = at + size
        }
        val groups = (if (groupCount in 0..gfid.size) gfid.take(groupCount) else gfid.toList()).filter { it != 0 }
        val models = LinkedHashSet<Int>()
        val chosen = (sets + 0).toSet()
        val ranges = if (setRanges.isEmpty()) listOf(0 to modd.size) else chosen.sorted().mapNotNull { setRanges.getOrNull(it) }
        for ((first, count) in ranges)
            for (i in first until minOf(first + count, modd.size))
                modi.getOrNull(modd[i])?.takeIf { it != 0 }?.let { models += it }
        return Deps(groups, textures.toList(), models.toList())
    }

    /** Whether a file is a WMO root (its first chunk is MVER, stored back to front) rather than an M2. */
    fun isWmo(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'R'.code.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'V'.code.toByte() && head[3] == 'M'.code.toByte()
}
