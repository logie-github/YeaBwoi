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
    val kind: String,
    val views: Map<String, View>,
    val packs: List<Pack>,
    /** The area (AreaTable) whose music and ambience it has (ambience.area; 0: none given). */
    val areaId: Int,
) {
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
            val list = root.optJSONArray("locations") ?: return if (root.has("wmo") || root.has("room")) listOf(parse(root)) else emptyList()
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
            val id = e.getString("id")
            return LocationInfo(
                id, e.optString("name", id), e.optString("kind", ""), views, packs,
                e.optJSONObject("ambience")?.optInt("area", 0) ?: 0,
            )
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
