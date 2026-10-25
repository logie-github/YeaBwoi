package com.logie.wifecraft.data

import android.content.Context
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Script
import com.logie.wifecraft.story.Vec3
import org.json.JSONArray
import org.json.JSONObject

/** How a person looks: the files and settings tools/looks.py worked out from the game's tables. */
data class Look(
    val model: Int,
    val scale: Float,
    val textures: Map<Int, Int>,
    val geosetGroups: Map<Int, Int>,
    val attachments: List<Attachment>,
) {
    data class Attachment(val point: Int, val model: Int, val texture: Int)
}

data class Place(val id: String, val name: String, val marks: Map<String, Vec3>, val views: Map<String, CameraPose>, val twoSide: Int)

/** Everything the story is made of: its script, its places and its people. */
class Content(context: Context) {
    val looks: Map<String, Look>
    val places: Map<String, Place>
    val script: Script

    init {
        val l = JSONObject(context.assets.open("looks.json").bufferedReader().use { it.readText() })
        looks = l.keys().asSequence().associateWith { key ->
            val o = l.getJSONObject(key)
            Look(
                model = o.getInt("model"),
                scale = o.optDouble("scale", 1.0).toFloat(),
                textures = o.optJSONObject("textures")?.let { t -> t.keys().asSequence().associate { it.toInt() to t.getInt(it) } } ?: emptyMap(),
                geosetGroups = o.optJSONObject("geosetGroups")?.let { g -> g.keys().asSequence().associate { it.toInt() to g.getInt(it) } } ?: emptyMap(),
                attachments = o.optJSONArray("attachments")?.let { a ->
                    List(a.length()) { i -> a.getJSONObject(i).let { Look.Attachment(it.getInt("attachment"), it.getInt("model"), it.optInt("texture")) } }
                } ?: emptyList(),
            )
        }
        val p = JSONObject(context.assets.open("places.json").bufferedReader().use { it.readText() }).getJSONObject("places")
        places = p.keys().asSequence().associateWith { id ->
            val o = p.getJSONObject(id)
            val marks = o.optJSONObject("marks")?.let { m -> m.keys().asSequence().associateWith { vec(m.getJSONArray(it)) } } ?: emptyMap()
            val views = o.optJSONObject("views")?.let { v ->
                v.keys().asSequence().associateWith { k ->
                    val view = v.getJSONObject(k)
                    CameraPose(vec(view.getJSONArray("eye")), vec(view.getJSONArray("at")), view.optDouble("fov", 50.0).toFloat())
                }
            } ?: emptyMap()
            Place(id, o.optString("name", id), marks, views, o.optInt("twoSide", 1))
        }
        script = Script.parse(context.assets.open("story/demo.wfs").bufferedReader().use { it.readText() })
    }

    /** Every mark of every place: the city's props near these are drawn. */
    fun allMarks(): List<Vec3> = places.values.flatMap { it.marks.values }

    private fun vec(a: JSONArray) = Vec3(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())

    companion object {
        /** Dalaran over the Broken Isles, and its outdoor groups (as tools/manifest.py fetches). */
        const val CITY_ROOT = 1120838
        val CITY_GROUPS = intArrayOf(2, 4, 5, 7, 8, 9, 10, 11, 15, 21, 44, 45, 46, 47, 48, 51, 53, 55, 57, 58, 80,
            90, 91, 92, 93, 94, 95, 96, 97, 115, 122, 124)
        const val PROP_RADIUS = 20f
    }
}

/** The people: who plays whom, their names on the talking-head box, and how tall they stand. */
object Cast {
    data class Person(val key: String, val look: String, val name: String, val height: Float)

    fun person(who: String, playerLook: String, playerName: String): Person? = when (who) {
        "alleria" -> Person("alleria", "alleria", "Alleria Windrunner", 2.4f)
        "nyrelle" -> Person("nyrelle", "nyrelle", "Nyrelle Moonquill", 2.15f)
        "saber" -> Person("saber", "saber", "Shadowpaw", 1.3f)
        "you" -> Person("you", playerLook, playerName, if (playerLook == "player_woman") 1.85f else 1.95f)
        else -> null
    }

    /** The people whose affection the story keeps, as reputations. */
    val bonds = listOf(
        Triple("alleria", "Alleria Windrunner", "Ranger of Quel'Thalas, touched by the Void"),
        Triple("nyrelle", "Nyrelle Moonquill", "Night elf huntress, a Sentinel far from Darnassus"),
    )
}

/** The game's animation ids (AnimationData) for the story's emote names. */
object Emotes {
    const val STAND = 0
    const val WALK = 4
    const val RUN = 5
    const val TALK = 60
    val byName = mapOf(
        "stand" to 0, "talk" to 60, "exclaim" to 64, "question" to 65, "bow" to 66, "wave" to 67, "cheer" to 68,
        "dance" to 69, "laugh" to 70, "rude" to 73, "roar" to 74, "kneel" to 75, "kiss" to 76, "cry" to 77,
        "beg" to 79, "applaud" to 80, "shout" to 81, "flex" to 82, "shy" to 83, "point" to 84, "salute" to 113,
        "yes" to 185, "no" to 186,
    )
}

/** Light for a time of day: sky, sun, ambient and fog. */
object Lights {
    fun of(name: String): FloatArray = when (name) {
        "dusk" -> floatArrayOf(
            0.20f, 0.18f, 0.42f, 0.93f, 0.58f, 0.45f, 0.30f, 0.22f, 0.35f,
            0.75f, 0.35f, -0.25f, 1.0f, 0.70f, 0.50f, 0.46f, 0.38f, 0.50f,
            0.93f, 0.58f, 0.45f, 220f, 1500f,
        )
        "night" -> floatArrayOf(
            0.02f, 0.03f, 0.10f, 0.13f, 0.14f, 0.30f, 0.05f, 0.05f, 0.11f,
            0.3f, -0.5f, -0.8f, 0.42f, 0.48f, 0.78f, 0.30f, 0.30f, 0.46f,
            0.13f, 0.14f, 0.30f, 160f, 1200f,
        )
        else -> floatArrayOf(
            0.25f, 0.38f, 0.66f, 0.72f, 0.78f, 0.86f, 0.35f, 0.42f, 0.52f,
            -0.4f, 0.3f, -0.85f, 1.0f, 0.95f, 0.85f, 0.47f, 0.49f, 0.57f,
            0.72f, 0.78f, 0.86f, 250f, 1600f,
        )
    }
}
