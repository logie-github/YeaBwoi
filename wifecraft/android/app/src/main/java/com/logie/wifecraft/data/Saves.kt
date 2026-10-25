package com.logie.wifecraft.data

import android.content.Context
import com.logie.wifecraft.story.LogEntry
import com.logie.wifecraft.story.StoryState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved story: where it stood, and who the player was then. */
data class Save(
    val slot: Int,
    val state: StoryState,
    val playerName: String,
    val playerLook: String,
    val place: String,
    val savedAt: Long,
)

/** Six save slots and the autosave (slot 0), as JSON in files/saves. */
class Saves(context: Context) {
    private val dir = File(context.filesDir, "saves").apply { mkdirs() }

    fun file(slot: Int) = File(dir, "slot-$slot.json")

    fun load(slot: Int): Save? = try {
        file(slot).takeIf { it.isFile }?.readText()?.let { parse(slot, JSONObject(it)) }
    } catch (e: Exception) {
        null
    }

    fun all(): List<Save?> = (0..6).map { load(it) }

    fun latest(): Save? = all().filterNotNull().maxByOrNull { it.savedAt }

    fun write(save: Save) {
        val s = save.state
        val json = JSONObject()
            .put("pc", s.pc)
            .put("affection", JSONObject(s.affection as Map<*, *>))
            .put("flags", JSONArray(s.flags.toList()))
            .put("sceneAt", s.sceneAt)
            .put("stage", JSONArray(s.stage))
            .put("log", JSONArray(s.log.takeLast(120).map { JSONArray(listOf(it.who, it.text)) }))
            .put("chapter", s.chapter)
            .put("playerName", save.playerName)
            .put("playerLook", save.playerLook)
            .put("place", save.place)
            .put("savedAt", save.savedAt)
        val tmp = File(dir, "slot-${save.slot}.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file(save.slot))
    }

    fun delete(slot: Int) {
        file(slot).delete()
    }

    private fun parse(slot: Int, o: JSONObject): Save {
        val aff = o.getJSONObject("affection")
        val state = StoryState(
            pc = o.getInt("pc"),
            affection = aff.keys().asSequence().associateWith { aff.getInt(it) }.toMutableMap(),
            flags = o.getJSONArray("flags").let { a -> MutableList(a.length()) { a.getString(it) }.toMutableSet() },
            sceneAt = o.getInt("sceneAt"),
            stage = o.getJSONArray("stage").let { a -> MutableList(a.length()) { a.getInt(it) } },
            log = o.getJSONArray("log").let { a ->
                MutableList(a.length()) { a.getJSONArray(it).let { e -> LogEntry(e.getString(0), e.getString(1)) } }
            },
            chapter = o.optString("chapter"),
        )
        return Save(slot, state, o.optString("playerName"), o.optString("playerLook", "player_man"), o.optString("place"), o.optLong("savedAt"))
    }
}

/** The player's settings and what they've found, kept between runs. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("wifecraft", Context.MODE_PRIVATE)

    var playerName: String
        get() = prefs.getString("playerName", "") ?: ""
        set(v) = prefs.edit().putString("playerName", v).apply()
    var playerLook: String
        get() = prefs.getString("playerLook", "player_man") ?: "player_man"
        set(v) = prefs.edit().putString("playerLook", v).apply()
    /** Characters per second the words appear at; 0 shows a line at once. */
    var textSpeed: Int
        get() = prefs.getInt("textSpeed", 45)
        set(v) = prefs.edit().putInt("textSpeed", v).apply()
    /** "dark" (Legion's talking head) or "parchment". */
    var boxStyle: String
        get() = prefs.getString("boxStyle", "dark") ?: "dark"
        set(v) = prefs.edit().putString("boxStyle", v).apply()
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(v) = prefs.edit().putBoolean("wifiOnly", v).apply()
    var vibrate: Boolean
        get() = prefs.getBoolean("vibrate", true)
        set(v) = prefs.edit().putBoolean("vibrate", v).apply()
    var endings: Set<String>
        get() = prefs.getStringSet("endings", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("endings", v).apply()
    /** Lines already read (by script position), for fast-forward. */
    var seen: Set<String>
        get() = prefs.getStringSet("seen", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("seen", v).apply()
}

/** The demo's endings, in the order the Endings list shows them. */
object Endings {
    data class Ending(val id: String, val title: String, val who: String, val kind: String)

    val all = listOf(
        Ending("windrunner", "Windrunner's Promise", "Alleria", "Romance"),
        Ending("arrows", "Arrows at Dusk", "Alleria", "Friendship"),
        Ending("moonlight", "Under Elune's Light", "Nyrelle", "Romance"),
        Ending("saber", "A Saber's Trust", "Nyrelle", "Friendship"),
        Ending("strangers", "A City of Strangers", "Dalaran", "Alone"),
    )

    fun of(id: String) = all.firstOrNull { it.id == id }
}
