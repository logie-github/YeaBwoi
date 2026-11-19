package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.DbpfFile
import com.logie.wifecraft.story.Vec3
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class PerformanceRole(val source: String, val offset: Vec3, val facing: Float)

data class ImportedPerformance(
    val id: String, val name: String, val dance: Boolean, val roles: List<PerformanceRole>, val setting: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("dance", dance).put("setting", setting)
        .put("roles", JSONArray().also { array ->
            for (role in roles) array.put(JSONObject().put("source", role.source)
                .put("x", role.offset.x.toDouble()).put("y", role.offset.y.toDouble()).put("z", role.offset.z.toDouble())
                .put("facing", role.facing.toDouble()))
        })

    companion object {
        fun fromJson(json: JSONObject): ImportedPerformance {
            val array = json.getJSONArray("roles")
            val roles = List(array.length()) { i ->
                val role = array.getJSONObject(i)
                PerformanceRole(role.getString("source"),
                    Vec3(role.getDouble("x").toFloat(), role.getDouble("y").toFloat(), role.getDouble("z").toFloat()),
                    role.getDouble("facing").toFloat())
            }
            return ImportedPerformance(json.getString("id"), json.getString("name"), json.optBoolean("dance"), roles,
                json.optString("setting"))
        }
    }
}

/** Reads only authored actor roles and placement from the pack’s tuning. It leaves Sims events and scripts alone. */
internal object ImportedPerformances {
    private const val TUNING = 0x7DF2169C

    fun read(packageFile: DbpfFile, sources: Map<String, String>): List<ImportedPerformance> =
        packageFile.entries.filter { it.type == TUNING && it.size in 1..2_000_000 }.flatMap { entry ->
            runCatching { read(packageFile.read(entry).decodeToString(), entry.key, sources) }.getOrDefault(emptyList())
        }

    fun read(xml: String, resource: String, sources: Map<String, String>): List<ImportedPerformance> {
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) return emptyList()
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val root = builder.parse(InputSource(StringReader(xml))).documentElement
        val dance = when (root.getAttribute("c")) {
            "WickedWhimsAnimationPackage" -> false
            "StripClubDanceAnimationPackage" -> true
            else -> return emptyList()
        }
        val list = root.child(if (dance) "dance_animations_list" else "animations_list") ?: return emptyList()
        return list.children().mapIndexedNotNull { index, item ->
            val positions = if (dance) emptyList() else item.child("animation_actors_list")?.children()
                ?.sortedBy { it.field("actor_id")?.toIntOrNull() ?: Int.MAX_VALUE }.orEmpty()
            val names = if (dance) {
                listOfNotNull(item.field("dancer_animation_clip_name"), item.field("watcher_animation_clip_name"))
            } else positions.map { it.field("animation_clip_name").orEmpty() }
            if (names.size !in 1..2 || names.any(String::isEmpty)) return@mapIndexedNotNull null
            if (names.none { it.lowercase() in sources }) return@mapIndexedNotNull null
            val roles = names.mapIndexed { i, name ->
                val source = sources[name.lowercase()].orEmpty()
                val actor = positions.getOrNull(i)
                val offset = Vec3(actor.number("animation_z_offset"), actor.number("animation_x_offset"), actor.number("animation_y_offset"))
                val facing = Math.toRadians(actor.number("animation_angle_offset").toDouble()).toFloat()
                PerformanceRole(source, offset, facing)
            }
            val name = item.field(if (dance) "raw_display_name" else "animation_raw_display_name")
                ?: names.first().substringAfterLast(':').replace('_', ' ')
            val stage = item.field("animation_stage_name")?.replace('_', ' ')
            val setting = item.child("animation_locations")?.textContent.orEmpty().trim().replace('_', ' ').lowercase()
            ImportedPerformance("$resource:$index", listOfNotNull(name, stage).joinToString(" · "), dance, roles, setting)
        }
    }

    private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.child(name: String): Element? = children().firstOrNull { it.getAttribute("n") == name }
    private fun Element.field(name: String): String? = child(name)?.textContent?.trim()?.takeIf(String::isNotEmpty)
    private fun Element?.number(name: String): Float = this?.field(name)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
}

data class AnimationChoice(
    val id: String, val name: String, val group: String, val preview: CheckedAnimation,
    val clips: List<CheckedAnimation>, val roles: List<PerformanceRole>, val setting: String = "",
) {
    val participantCount: Int get() = roles.size
    val playable: Boolean get() = clips.size == roles.size && clips.all { it.inspection.type != ClipType.INVALID }
}

fun animationChoices(clips: List<CheckedAnimation>): List<AnimationChoice> {
    val bySource = clips.filter { it.clip.source.isNotBlank() }.associateBy { it.clip.source }
    val groups = clips.flatMap { it.clip.performances }.distinctBy { it.id }
    val authoredSources = groups.flatMap { it.roles }.mapNotNullTo(HashSet()) { it.source.takeIf(String::isNotBlank) }
    val coordinated = groups.mapNotNull { group ->
        val lead = group.roles.firstNotNullOfOrNull { bySource[it.source] } ?: return@mapNotNull null
        val groupName = when {
            group.dance && group.roles.size == 2 -> "Partner dances"
            group.dance -> "Solo dances"
            group.roles.size == 2 -> "Partner animations"
            else -> lead.inspection.type.label
        }
        AnimationChoice(group.id, group.name, groupName, lead, group.roles.mapNotNull { bySource[it.source] }, group.roles, group.setting)
    }
    val solos = clips.filterNot { it.clip.source in authoredSources }.map {
        AnimationChoice(it.clip.id, it.clip.name, "Solo · ${it.inspection.type.label}", it, listOf(it),
            listOf(PerformanceRole(it.clip.source, Vec3(0f, 0f, 0f), 0f)))
    }
    return (coordinated + solos).sortedWith(compareBy({ it.group }, { it.name.lowercase() }))
}
