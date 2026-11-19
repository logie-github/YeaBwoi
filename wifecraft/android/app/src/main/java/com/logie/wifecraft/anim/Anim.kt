package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.PairDefinition
import com.logie.wifecraft.sims.SimsClip
import org.json.JSONArray
import org.json.JSONObject

/** One bone's keyframes. [bone] is the WoW-side stable name from the profile ("Head", "L_UpperArm").
 *  Rotation values are x,y,z,w per time; position values are x,y,z per time. */
class BoneTrack(val bone: String, val channel: Channel, val times: FloatArray, val values: FloatArray) {
    override fun equals(other: Any?) = other is BoneTrack && bone == other.bone && channel == other.channel &&
        times.contentEquals(other.times) && values.contentEquals(other.values)
    override fun hashCode() = ((bone.hashCode() * 31 + channel.hashCode()) * 31 + times.contentHashCode()) * 31 + values.contentHashCode()
    override fun toString() = "BoneTrack($bone, $channel, ${times.size} keys)"
}

/** A converted animation. [source] identifies where it came from ("<file hash>:<clip name>"): the library's
 *  duplicate policy compares it. */
data class Anim(
    val id: String,
    val name: String,
    val category: String,
    val duration: Float,
    val loop: Boolean,
    val tracks: List<BoneTrack>,
    val source: String,
    val importedAt: Long,
    val conversionRevision: Int = 0,
    val omittedChannels: Boolean = false,
    val performances: List<ImportedPerformance> = emptyList(),
    val sourceName: String = "",
)

/** Converts one Sims clip to a WoW-side [Anim] (implemented by the Retargeter). May throw for a clip it cannot convert. */
interface Retarget {
    fun convert(clip: SimsClip): Anim

    /**
     * Converts two clips made to play together into a [PairAnim] ([a] is actor 0). This default converts each
     * alone and stands both at the origin; the [Retargeter] keeps each actor's start from the shared origin and
     * one horizontal scale for both. Throws when the clips' lengths differ by more than a frame.
     */
    fun convertPair(a: SimsClip, b: SimsClip, def: PairDefinition?): PairAnim =
        PairAssembly.assemble(a, b, convert(a), convert(b), def, PairOffset.ZERO, PairOffset.ZERO)
}

/** The Anim file format, version [VERSION]: {"v":1,"id","name","category","duration","loop","source","importedAt","tracks":[{"bone","ch","times":[],"values":[]}]}.
 *  An unknown newer version is refused, never half-read. */
object AnimJson {
    const val VERSION = 1

    fun toJson(a: Anim): JSONObject {
        val tracks = JSONArray()
        for (t in a.tracks) {
            require(t.times.all { it.isFinite() } && t.values.all { it.isFinite() }) { "Track ${t.bone} has a value that is not a number" }
            tracks.put(JSONObject()
                .put("bone", t.bone).put("ch", t.channel.name)
                .put("times", JSONArray().also { arr -> t.times.forEach { arr.put(it.toDouble()) } })
                .put("values", JSONArray().also { arr -> t.values.forEach { arr.put(it.toDouble()) } }))
        }
        require(a.duration.isFinite()) { "Duration is not a number" }
        return JSONObject().put("v", VERSION).put("id", a.id).put("name", a.name).put("category", a.category)
            .put("duration", a.duration.toDouble()).put("loop", a.loop).put("source", a.source)
            .put("importedAt", a.importedAt).put("tracks", tracks)
            .put("conversionRevision", a.conversionRevision).put("omittedChannels", a.omittedChannels)
            .put("sourceName", a.sourceName)
            .put("performances", JSONArray().also { array -> a.performances.forEach { array.put(it.toJson()) } })
    }

    fun fromJson(o: JSONObject): Anim {
        val v = o.optInt("v", 0)
        require(v in 1..VERSION) { "Animation file version $v is not supported (this app reads version $VERSION)" }
        val tracks = ArrayList<BoneTrack>()
        val arr = o.getJSONArray("tracks")
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val times = floats(t.getJSONArray("times"))
            val values = floats(t.getJSONArray("values"))
            val channel = Channel.valueOf(t.getString("ch"))
            val per = if (channel == Channel.ROTATION) 4 else 3
            require(values.size == times.size * per) { "Track ${t.optString("bone")} has ${values.size} values for ${times.size} keys" }
            tracks.add(BoneTrack(t.getString("bone"), channel, times, values))
        }
        return Anim(
            id = o.getString("id"), name = o.getString("name"), category = o.optString("category", ""),
            duration = o.optDouble("duration", 0.0).toFloat(), loop = o.optBoolean("loop", false),
            tracks = tracks, source = o.optString("source", ""), importedAt = o.optLong("importedAt", 0L),
            conversionRevision = o.optInt("conversionRevision", 0), omittedChannels = o.optBoolean("omittedChannels", false),
            performances = o.optJSONArray("performances")?.let { array ->
                List(array.length()) { ImportedPerformance.fromJson(array.getJSONObject(it)) }
            }.orEmpty(),
            sourceName = o.optString("sourceName"),
        )
    }

    fun parse(text: String): Anim = fromJson(JSONObject(text))

    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
}
