package com.logie.wifecraft.anim

import com.logie.wifecraft.story.Vec3
import org.json.JSONArray
import org.json.JSONObject

/**
 * An actor's start from the pair's shared origin, in WoW coordinates: [x] forward, [y] left, [z] up, in yards
 * (the reference skeleton's model units, as the Root track's), and [yawDeg] the actor's facing, degrees
 * counter-clockwise about up from the origin's facing. The simulation stands the actor here and plays its
 * [Anim], whose Root track then moves relative to this start (the start itself is taken out of the track).
 */
data class PairOffset(val x: Float, val y: Float, val z: Float, val yawDeg: Float) {
    companion object { val ZERO = PairOffset(0f, 0f, 0f, 0f) }
}

/** A sound a pair's clip names at [timeSec] for [actor] (0: [PairAnim.a], 1: [PairAnim.b]). */
data class SoundEvent(val timeSec: Float, val name: String, val actor: Int)

/**
 * A partner animation: two actors' clips made to play together from one shared origin and one clock. [a] and [b]
 * are the two actors' converted animations (the same duration), [offsetA] and [offsetB] where each stands from
 * the shared origin, [loops] whether the pair repeats, [locations] the furniture categories the source made it
 * for (empty: anywhere; see [PairLocations]), [sounds] the clips' sound events in time order. [source] is the two
 * clips' sources (the library's duplicate policy compares it), [category] "Partner" unless changed.
 */
data class PairAnim(
    val id: String,
    val name: String,
    val category: String,
    val a: Anim,
    val b: Anim,
    val offsetA: PairOffset,
    val offsetB: PairOffset,
    val loops: Boolean,
    val locations: Set<String>,
    val sounds: List<SoundEvent>,
    val enabled: Boolean = true,
    val source: String,
    val importedAt: Long,
    /** The pack's category for the stage (sims/AnimTuning PairDefinition.kind; bodyparts/Arousal's ArousalMap reads it); empty: none. */
    val kind: String = "",
    /**
     * The creature NPC ([com.logie.wifecraft.characters.CreatureSpec.character]) this stage is for: it is offered and played only
     * when she is one of the two, the other person in role [a] (active) and she in [b] (receiving). Null: any two. Built-in stages
     * only ([com.logie.wifecraft.characters.DragonStages]); never stored.
     */
    val exclusiveTo: String? = null,
) {
    val duration: Float get() = maxOf(a.duration, b.duration)

    /** This partner animation as an entry of the animation browser (group: its category); ids "pair:<id>". */
    fun choice(): AnimationChoice {
        val clips = listOf(CheckedAnimation(a), CheckedAnimation(b))
        val roles = listOf(offsetA, offsetB).zip(listOf(a, b)) { o, clip ->
            PerformanceRole(clip.source, Vec3(o.x, o.y, o.z), Math.toRadians(o.yawDeg.toDouble()).toFloat())
        }
        return AnimationChoice("$CHOICE_PREFIX$id", name, category, clips[0], clips, roles,
            locations.sorted().joinToString(", ") { it.lowercase().replace('_', ' ') }, yards = true, sounds = sounds, kind = kind,
            exclusiveTo = exclusiveTo)
    }

    companion object {
        const val CATEGORY = "Partner"
        /** The browser's ids of partner animations: "pair:" and the pair's id. */
        const val CHOICE_PREFIX = "pair:"

        /** The source of a pair made of the clips with sources [a] and [b], the same whichever is first. */
        fun sourceOf(a: String, b: String): String = listOf(a, b).sorted().joinToString("+")
    }
}

/**
 * The furniture categories a pair's tuning names (WickedWhims-style: SOFA, DOUBLE_BED...) as the scene's own
 * kinds (BED, COUCH, CHAIR); the rest is kept as written. Floor or none: anywhere.
 */
object PairLocations {
    private val map = mapOf(
        "DOUBLE_BED" to "BED", "SINGLE_BED" to "BED", "BED" to "BED", "KIDS_BED" to "BED",
        "SOFA" to "COUCH", "LOVESEAT" to "COUCH", "COUCH" to "COUCH",
        "CHAIR" to "CHAIR", "DINING_CHAIR" to "CHAIR", "LIVING_CHAIR" to "CHAIR", "DESK_CHAIR" to "CHAIR",
        "TABLE" to "TABLE", "DINING_TABLE" to "TABLE", "COUNTER" to "TABLE",
    )

    fun normalize(raw: Collection<String>): Set<String> =
        raw.map { it.trim().uppercase() }.filter { it.isNotEmpty() && it != "FLOOR" && it != "NONE" }.map { StageLocations.canonical(it) ?: map[it] ?: it }.toSet()
}

/** The pair file format, version [VERSION]: {"v","kind":"pair","id","name","category","loops","enabled","source",
 *  "importedAt","locations":[],"sounds":[{"t","name","actor"}],"offsetA":{"x","y","z","yaw"},"offsetB",
 *  "a":<Anim>,"b":<Anim>} with each Anim as [AnimJson] writes it. A newer version is refused, never half-read. */
object PairJson {
    const val VERSION = 1

    fun toJson(p: PairAnim): JSONObject {
        for (o in listOf(p.offsetA, p.offsetB)) require(listOf(o.x, o.y, o.z, o.yawDeg).all { it.isFinite() }) { "An offset is not a number" }
        val sounds = JSONArray()
        for (s in p.sounds) {
            require(s.timeSec.isFinite()) { "A sound time is not a number" }
            sounds.put(JSONObject().put("t", s.timeSec.toDouble()).put("name", s.name).put("actor", s.actor))
        }
        return JSONObject().put("v", VERSION).put("kind", "pair").put("id", p.id).put("name", p.name).put("category", p.category)
            .put("loops", p.loops).put("enabled", p.enabled).put("source", p.source).put("importedAt", p.importedAt)
            .put("locations", JSONArray(p.locations.sorted()))
            .put("sounds", sounds)
            .put("offsetA", offset(p.offsetA)).put("offsetB", offset(p.offsetB))
            .put("a", AnimJson.toJson(p.a)).put("b", AnimJson.toJson(p.b))
            .also { o -> if (p.kind.isNotEmpty()) o.put("stageKind", p.kind) }
    }

    fun fromJson(o: JSONObject): PairAnim {
        val v = o.optInt("v", 0)
        require(v in 1..VERSION) { "Partner animation file version $v is not supported (this app reads version $VERSION)" }
        require(o.optString("kind", "pair") == "pair") { "Not a partner animation file" }
        val sounds = ArrayList<SoundEvent>()
        o.optJSONArray("sounds")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val actor = s.optInt("actor", 0)
                require(actor == 0 || actor == 1) { "A sound's actor is $actor, not 0 or 1" }
                sounds += SoundEvent(s.getDouble("t").toFloat(), s.getString("name"), actor)
            }
        }
        val locs = o.optJSONArray("locations")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty().toSet()
        return PairAnim(
            id = o.getString("id"), name = o.getString("name"), category = o.optString("category", PairAnim.CATEGORY),
            a = AnimJson.fromJson(o.getJSONObject("a")), b = AnimJson.fromJson(o.getJSONObject("b")),
            offsetA = offset(o.getJSONObject("offsetA")), offsetB = offset(o.getJSONObject("offsetB")),
            loops = o.optBoolean("loops", false), locations = locs, sounds = sounds,
            enabled = o.optBoolean("enabled", true), source = o.optString("source", ""), importedAt = o.optLong("importedAt", 0L),
            kind = o.optString("stageKind", ""),
        )
    }

    fun parse(text: String): PairAnim = fromJson(JSONObject(text))

    private fun offset(p: PairOffset) = JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble()).put("z", p.z.toDouble()).put("yaw", p.yawDeg.toDouble())
    private fun offset(o: JSONObject) = PairOffset(
        o.optDouble("x", 0.0).toFloat(), o.optDouble("y", 0.0).toFloat(), o.optDouble("z", 0.0).toFloat(), o.optDouble("yaw", 0.0).toFloat(),
    ).also { require(listOf(it.x, it.y, it.z, it.yawDeg).all { v -> v.isFinite() }) { "An offset is not a number" } }
}
