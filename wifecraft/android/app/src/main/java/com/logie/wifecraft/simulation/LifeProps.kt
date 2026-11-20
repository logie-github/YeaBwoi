package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import org.json.JSONObject

/**
 * The life simulation's props of a location ([LifeProp]: a fireplace, a wash basin, a bookshelf), read from
 * locations.json's "props" (tools/locations.py: only where the game's own model of one stands in the room, with the floor
 * spot its user stands at and the way they face). Pure JVM.
 */
object LifeProps {
    /**
     * The props of the location named or id'd [location] in [locationsJson], in the scene's frame: each spot by [toLocal],
     * each facing turned by [rotation] (as Furniture.of turns the furniture). Empty when it names none or can't be read.
     */
    fun of(locationsJson: String, location: String, toLocal: (Vec3) -> Vec3 = { it }, rotation: Float = 0f): List<LifeProp> {
        val all = try { JSONObject(locationsJson).optJSONArray("locations") } catch (e: Exception) { null } ?: return emptyList()
        for (i in 0 until all.length()) {
            val loc = all.optJSONObject(i) ?: continue
            if (loc.optString("id") != location && loc.optString("name") != location) continue
            val props = loc.optJSONArray("props") ?: return emptyList()
            val out = ArrayList<LifeProp>()
            for (k in 0 until props.length()) {
                val p = props.optJSONObject(k) ?: continue
                val kind = LifePropKind.entries.firstOrNull { it.name == p.optString("kind") } ?: continue
                val e = p.optJSONArray("entry") ?: continue
                if (e.length() < 3) continue
                val at = Vec3(e.optDouble(0).toFloat(), e.optDouble(1).toFloat(), e.optDouble(2).toFloat())
                if (!at.x.isFinite() || !at.y.isFinite() || !at.z.isFinite()) continue
                out += LifeProp(kind, toLocal(at), p.optDouble("facing", 0.0).toFloat() + rotation)
            }
            return out
        }
        return emptyList()
    }
}
