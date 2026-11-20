package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.PartOptions
import com.logie.wifecraft.editor.Actor
import org.json.JSONArray
import org.json.JSONObject

/**
 * The saved NPCs (files/npc-roster.json: `[{"id","name","appearance"}]`) as pure operations on its text: read, add,
 * change, delete; MainActivity writes the result with an AtomicFile (all or nothing). Pure JVM.
 *
 * Only the NPCs the user made are deleted ([canDelete]): those in the file. The built-in starters ("starter:..."), the
 * named characters ("character:...") and the NPCs of older projects ("<project>:<actor>", read from the projects
 * themselves) have no delete control.
 */
object NpcRosterFile {
    const val STARTER_PREFIX = "starter:"

    fun parse(text: String?): List<Actor> {
        if (text.isNullOrBlank()) return emptyList()
        val rows = JSONArray(text)
        return List(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            Actor(row.getString("id"), row.getString("name"), Appearance.fromJson(row.getJSONObject("appearance")))
        }
    }

    fun write(actors: List<Actor>): String {
        val rows = JSONArray()
        for (a in actors) rows.put(JSONObject().put("id", a.id).put("name", a.name).put("appearance", a.appearance.toJson()))
        return rows.toString()
    }

    fun add(text: String?, actor: Actor): String = write(parse(text).filter { it.id != actor.id } + actor)

    /** [actor] in place of the saved NPC of its id (its place in the list kept); unchanged when it isn't saved. */
    fun replace(text: String?, actor: Actor): String = write(parse(text).map { if (it.id == actor.id) actor else it })

    /** The file without the NPC [id] (unchanged when it isn't in it). */
    fun delete(text: String?, id: String): String = write(parse(text).filter { it.id != id })

    fun isBuiltIn(id: String): Boolean = id.startsWith(STARTER_PREFIX) || NamedCast.isNamed(id)

    /** Whether the NPC [id] has a delete control: one of [saved] (the file's ids), never a built-in one. */
    fun canDelete(id: String, saved: Set<String>): Boolean = id in saved && !isBuiltIn(id)

    /** The scene's cast with [id] taken out (it was deleted). */
    fun castWithout(cast: Set<String>, id: String): Set<String> = cast - id

    /**
     * Of the thumbnails' file names (engine/Thumbs: files/thumbs/npc_<asset>_<look>_<size>.png, asset the NPC id's hash as
     * the roster's cards name it), those of the NPC [id]: deleted with it.
     */
    fun thumbnails(names: List<String>, id: String): List<String> {
        val prefix = "npc_${id.hashCode()}_"
        return names.filter { it.startsWith(prefix) }
    }
}

/**
 * What the user chose for the NPCs that aren't in npc-roster.json (the starters and the named characters):
 * files/npc-looks.json, `{"<actor id>": {"variant": "<key>", "parts": {...}}}`. Pure JVM; immutable.
 */
data class NpcLooks(val variants: Map<String, String> = emptyMap(), val parts: Map<String, PartOptions> = emptyMap()) {
    fun withVariant(id: String, key: String) = copy(variants = variants + (id to key))
    fun withParts(id: String, options: PartOptions) = copy(parts = parts + (id to options))

    /** [actor] with its saved body-part settings. */
    fun apply(actor: Actor): Actor = parts[actor.id]?.let { actor.copy(appearance = actor.appearance.copy(parts = it)) } ?: actor

    fun toJson(): String {
        val o = JSONObject()
        for (id in (variants.keys + parts.keys).sorted()) {
            val row = JSONObject()
            variants[id]?.let { row.put("variant", it) }
            parts[id]?.let { row.put("parts", it.toJson()) }
            o.put(id, row)
        }
        return o.toString()
    }

    companion object {
        fun parse(text: String?): NpcLooks {
            if (text.isNullOrBlank()) return NpcLooks()
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return NpcLooks()
            val variants = LinkedHashMap<String, String>()
            val parts = LinkedHashMap<String, PartOptions>()
            for (id in o.keys()) {
                val row = o.optJSONObject(id) ?: continue
                row.optString("variant").takeIf { it.isNotEmpty() }?.let { variants[id] = it }
                PartOptions.fromJson(row.optJSONObject("parts"))?.let { parts[id] = it }
            }
            return NpcLooks(variants, parts)
        }
    }
}
