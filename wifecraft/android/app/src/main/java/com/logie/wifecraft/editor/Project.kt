package com.logie.wifecraft.editor

import com.logie.wifecraft.creator.Appearance
import org.json.JSONArray
import org.json.JSONObject

/** An NPC the author made. */
data class Actor(val id: String, val name: String, val appearance: Appearance, val note: String = "")

/** Where a scene happens: a place id from assets/places.json, or "studio" (a plain void stage), in a light (day, dusk, night). */
data class Stage(val id: String, val name: String, val place: String, val light: String)

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
    data class Goto(val scene: String) : Beat
    data class End(val title: String) : Beat
}

data class Scene(val id: String, val name: String, val stage: Stage, val beats: List<Beat>)

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
            .put("beats", JSONArray().also { a -> for (b in s.beats) a.put(beatJson(b)) })

        private fun sceneFrom(o: JSONObject): Scene {
            val st = o.getJSONObject("stage")
            return Scene(o.getString("id"), o.optString("name", ""),
                Stage(st.optString("id", ""), st.optString("name", ""), st.optString("place", "studio"), st.optString("light", "day")),
                o.optJSONArray("beats").objects().map { beatFrom(it) })
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
            "goto" -> Beat.Goto(o.getString("scene"))
            "end" -> Beat.End(o.optString("title", ""))
            else -> throw IllegalArgumentException("Unknown beat type \"$t\"")
        }
    }
}
