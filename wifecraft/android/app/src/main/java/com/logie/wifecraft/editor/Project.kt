package com.logie.wifecraft.editor

import com.logie.wifecraft.creator.Appearance
import org.json.JSONArray
import org.json.JSONObject

/** An NPC the author made. */
data class Actor(val id: String, val name: String, val appearance: Appearance, val note: String = "")

/** Where a scene happens: a place id from assets/places.json, or "studio" (a plain void stage), in a light (day, dusk, night). */
data class Stage(val id: String, val name: String, val place: String, val light: String)

/** What a [Placement] is: PROP a model/WMO by file id; CREATURE a creature display id; NPC a cast actor of the project. */
enum class PlaceKind { PROP, CREATURE, NPC }

/**
 * Something put in a scene's stage with the placement editor. WoW coordinates (Z up), [yaw] in radians.
 * [asset] is the file id (PROP) or CreatureDisplayInfo id (CREATURE), 0 for an NPC (then [actorId] is the cast actor).
 * [anim] is a game animation id played on it (null: idle); [emoteId] the EmoteCatalog id it was picked by (then [anim] is derived).
 */
data class Placement(
    val id: String, val kind: PlaceKind, val asset: Int,
    val actorId: String?,
    val name: String,
    val x: Float, val y: Float, val z: Float, val yaw: Float, val scale: Float,
    val anim: Int? = null,
    val emoteId: Int? = null,
    val loop: Boolean = true, val sound: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.name).put("asset", asset)
        .put("actorId", actorId ?: JSONObject.NULL).put("name", name)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("z", z.toDouble())
        .put("yaw", yaw.toDouble()).put("scale", scale.toDouble())
        .put("anim", anim ?: JSONObject.NULL).put("emoteId", emoteId ?: JSONObject.NULL)
        .put("loop", loop).put("sound", sound)

    companion object {
        fun fromJson(o: JSONObject): Placement = Placement(
            id = o.getString("id"),
            kind = runCatching { PlaceKind.valueOf(o.optString("kind", "PROP")) }.getOrDefault(PlaceKind.PROP),
            asset = o.optInt("asset", 0),
            actorId = if (o.isNull("actorId")) null else o.optString("actorId").ifEmpty { null },
            name = o.optString("name", ""),
            x = o.optDouble("x", 0.0).toFloat(), y = o.optDouble("y", 0.0).toFloat(), z = o.optDouble("z", 0.0).toFloat(),
            yaw = o.optDouble("yaw", 0.0).toFloat(), scale = o.optDouble("scale", 1.0).toFloat(),
            anim = if (o.isNull("anim")) null else o.optInt("anim"),
            emoteId = if (o.isNull("emoteId")) null else o.optInt("emoteId"),
            loop = o.optBoolean("loop", true), sound = o.optBoolean("sound", true),
        )
    }
}

/** One step of a scene, in order. */
sealed interface Beat {
    /** [who] is an actor id; null is narration. */
    data class Say(val who: String?, val emote: String, val text: String) : Beat
    /** [spot] is left, center or right; [shot] is "", "bust", "wide", "two ACTOR" or "view NAME" (see [Compile]). */
    data class Show(val who: String, val spot: String, val shot: String) : Beat
    data class Hide(val who: String) : Beat
    data class Camera(val shot: String) : Beat
    /** [who] holds the library animation [animId] (a pose, or a clip: anim/AnimLibrary) until another beat moves them. */
    data class Pose(val who: String, val animId: String) : Beat
    data class Choice(val prompt: String, val options: List<Option>) : Beat {
        data class Option(val text: String, val goto: String)
    }
    /** The placed thing [placementId] plays the game emote [emoteId] (EmoteCatalog) mid-scene. */
    data class Emote(val placementId: String, val emoteId: Int) : Beat
    /** The placed thing [placementId] plays the game animation [anim] (looping or once). */
    data class Anim(val placementId: String, val anim: Int, val loop: Boolean = false) : Beat
    data class Goto(val scene: String) : Beat
    data class End(val title: String) : Beat
}

/**
 * [stageId] is where the scene happens: a place id of places.json, "studio", or "loc:<locationId>:<view>" (e.g.
 * "loc:embassy:inside"); it defaults to [stage]'s place (old saves). [placements] are what the placement editor put there.
 */
data class Scene(
    val id: String, val name: String, val stage: Stage, val beats: List<Beat>,
    val stageId: String = stage.place,
    val placements: List<Placement> = emptyList(),
)

data class Project(val id: String, val title: String, val cast: List<Actor>, val scenes: List<Scene>, val start: String?) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title)
        .put("start", start ?: JSONObject.NULL)
        .put("cast", JSONArray().also { a ->
            for (c in cast) a.put(JSONObject().put("id", c.id).put("name", c.name).put("note", c.note).put("appearance", c.appearance.toJson()))
        })
        .put("scenes", JSONArray().also { a -> for (s in scenes) a.put(sceneJson(s)) })

    companion object {
        fun fromJson(o: JSONObject): Project = Project(
            id = o.getString("id"),
            title = o.optString("title", ""),
            cast = o.optJSONArray("cast").objects().map {
                Actor(it.getString("id"), it.optString("name", ""), Appearance.fromJson(it.getJSONObject("appearance")), it.optString("note", ""))
            },
            scenes = o.optJSONArray("scenes").objects().map { sceneFrom(it) },
            start = if (o.isNull("start")) null else o.optString("start").ifEmpty { null },
        )

        private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

        private fun sceneJson(s: Scene) = JSONObject().put("id", s.id).put("name", s.name)
            .put("stage", JSONObject().put("id", s.stage.id).put("name", s.stage.name).put("place", s.stage.place).put("light", s.stage.light))
            .put("stageId", s.stageId)
            .put("placements", JSONArray().also { a -> for (pl in s.placements) a.put(pl.toJson()) })
            .put("beats", JSONArray().also { a -> for (b in s.beats) a.put(beatJson(b)) })

        private fun sceneFrom(o: JSONObject): Scene {
            val st = o.getJSONObject("stage")
            val stage = Stage(st.optString("id", ""), st.optString("name", ""), st.optString("place", "studio"), st.optString("light", "day"))
            return Scene(o.getString("id"), o.optString("name", ""), stage,
                o.optJSONArray("beats").objects().map { beatFrom(it) },
                stageId = o.optString("stageId", "").ifEmpty { stage.place },
                placements = o.optJSONArray("placements").objects().map { Placement.fromJson(it) })
        }

        private fun beatJson(b: Beat): JSONObject = when (b) {
            is Beat.Say -> JSONObject().put("type", "say").put("who", b.who ?: JSONObject.NULL).put("emote", b.emote).put("text", b.text)
            is Beat.Show -> JSONObject().put("type", "show").put("who", b.who).put("spot", b.spot).put("shot", b.shot)
            is Beat.Hide -> JSONObject().put("type", "hide").put("who", b.who)
            is Beat.Camera -> JSONObject().put("type", "camera").put("shot", b.shot)
            is Beat.Pose -> JSONObject().put("type", "pose").put("who", b.who).put("animId", b.animId)
            is Beat.Choice -> JSONObject().put("type", "choice").put("prompt", b.prompt).put("options", JSONArray().also { a ->
                for (o in b.options) a.put(JSONObject().put("text", o.text).put("goto", o.goto))
            })
            is Beat.Emote -> JSONObject().put("type", "emote").put("placementId", b.placementId).put("emoteId", b.emoteId)
            is Beat.Anim -> JSONObject().put("type", "anim").put("placementId", b.placementId).put("anim", b.anim).put("loop", b.loop)
            is Beat.Goto -> JSONObject().put("type", "goto").put("scene", b.scene)
            is Beat.End -> JSONObject().put("type", "end").put("title", b.title)
        }

        private fun beatFrom(o: JSONObject): Beat = when (val t = o.getString("type")) {
            "say" -> Beat.Say(if (o.isNull("who")) null else o.getString("who"), o.optString("emote", "talk"), o.optString("text", ""))
            "show" -> Beat.Show(o.getString("who"), o.optString("spot", "center"), o.optString("shot", ""))
            "hide" -> Beat.Hide(o.getString("who"))
            "camera" -> Beat.Camera(o.optString("shot", ""))
            "pose" -> Beat.Pose(o.getString("who"), o.optString("animId", ""))
            "choice" -> Beat.Choice(o.optString("prompt", ""), o.optJSONArray("options").objects().map {
                Beat.Choice.Option(it.optString("text", ""), it.optString("goto", ""))
            })
            "emote" -> Beat.Emote(o.getString("placementId"), o.optInt("emoteId", 0))
            "anim" -> Beat.Anim(o.getString("placementId"), o.optInt("anim", 0), o.optBoolean("loop", false))
            "goto" -> Beat.Goto(o.getString("scene"))
            "end" -> Beat.End(o.optString("title", ""))
            else -> throw IllegalArgumentException("Unknown beat type \"$t\"")
        }
    }
}
