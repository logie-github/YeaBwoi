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
    val stage: String = "", val nextStages: List<String> = emptyList(), val repetitions: Int = 1, val style: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("dance", dance).put("setting", setting)
        .put("stage", stage).put("nextStages", JSONArray(nextStages)).put("repetitions", repetitions).put("style", style)
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
                json.optString("setting"), json.optString("stage"),
                json.optJSONArray("nextStages")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
                json.optInt("repetitions", 1).coerceIn(1, 100), json.optString("style"))
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
            val stage = item.field("animation_stage_name")?.takeUnless { it.equals("None", ignoreCase = true) }.orEmpty()
            val next = item.child("animation_next_stages")?.let { element ->
                element.children().mapNotNull { it.textContent?.trim()?.takeIf(String::isNotEmpty) }
                    .ifEmpty { element.textContent.split(',').map(String::trim).filter(String::isNotEmpty) }
            }.orEmpty()
            val repetitions = item.field("animation_loops")?.toIntOrNull()?.coerceIn(1, 100) ?: 1
            val style = if (dance) item.field("dance_type").orEmpty() else ""
            val setting = item.child("animation_locations")?.textContent.orEmpty().trim().replace('_', ' ').lowercase()
            ImportedPerformance("$resource:$index", name, dance, roles, setting, stage, next, repetitions, style)
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
    val stage: String = "", val nextStages: List<String> = emptyList(), val repetitions: Int = 1,
) {
    val participantCount: Int get() = roles.size
    val playable: Boolean get() = clips.size == roles.size && clips.all { it.inspection.type != ClipType.INVALID }
}

fun animationChoices(clips: List<CheckedAnimation>): List<AnimationChoice> {
    val bySource = clips.filter { it.clip.source.isNotBlank() }.associateBy { it.clip.source }
    val groups = clips.flatMap { it.clip.performances }.distinctBy { it.id }
    val authoredGroups = groups.distinctBy { it.copy(id = "") }
    val authoredSources = groups.flatMap { it.roles }.mapNotNullTo(HashSet()) { it.source.takeIf(String::isNotBlank) }
    val coordinated = authoredGroups.mapNotNull { group ->
        val lead = group.roles.firstNotNullOfOrNull { bySource[it.source] } ?: return@mapNotNull null
        val numbered = stageName.matchEntire(group.stage)
        val groupName = when {
            numbered != null -> "${readable(numbered.groupValues[1])} sequence"
            group.dance && group.style.isNotEmpty() -> readable(group.style)
            group.dance && group.roles.size == 2 -> "Partner dances"
            group.dance -> "Solo dances"
            group.roles.size == 2 -> "Partner animations"
            else -> lead.inspection.type.label
        }
        val stageLabel = numbered?.groupValues?.get(2)?.let { "Stage $it" } ?: group.stage.takeIf(String::isNotBlank)?.let(::readable)
        val name = listOfNotNull(stageLabel, group.name).joinToString(" · ")
        AnimationChoice(group.id, name, groupName, lead, group.roles.mapNotNull { bySource[it.source] }, group.roles,
            group.setting, group.stage, group.nextStages, group.repetitions)
    }
    val standalone = clips.filterNot { it.clip.source in authoredSources }
    val families = standalone.mapNotNull { sourceFamily(it)?.lowercase() }.groupingBy { it }.eachCount()
    val solos = standalone.map {
        val family = sourceFamily(it)?.takeIf { label -> (families[label.lowercase()] ?: 0) > 1 }
        val label = family ?: "Solo · ${it.inspection.type.label}"
        AnimationChoice(it.clip.id, it.clip.name, label, it, listOf(it),
            listOf(PerformanceRole(it.clip.source, Vec3(0f, 0f, 0f), 0f)))
    }
    return (coordinated + solos).sortedWith(compareBy({ it.group },
        { stageName.matchEntire(it.stage)?.groupValues?.get(2)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: Int.MAX_VALUE },
        { it.stage.lowercase() }, { it.name.lowercase() }))
}

private val stageName = Regex("""(.+?)[_-]stage[_-](\d+[a-z]*)""", RegexOption.IGNORE_CASE)
private val clipFamily = Regex("""(.+?)[_-](?:set[_-]\d+|start|loop|end|enter|exit)""", RegexOption.IGNORE_CASE)
private fun sourceFamily(animation: CheckedAnimation): String? {
    val name = animation.clip.sourceName.ifBlank { animation.clip.name }
    val family = clipFamily.matchEntire(name.substringAfterLast(':'))?.groupValues?.get(1) ?: return null
    return listOfNotNull(name.substringBefore(':', "").takeIf(String::isNotBlank), readable(family)).joinToString(" · ")
}

private fun readable(name: String) = name.replace('_', ' ').split(' ').joinToString(" ") { word ->
    if (word.all { !it.isLetter() || it.isUpperCase() }) word.lowercase().replaceFirstChar(Char::uppercase) else word
}

/** Follow the author’s stage references. Stop before an ambiguous, missing, or already visited stage. */
fun animationSequence(start: AnimationChoice, choices: List<AnimationChoice>): List<AnimationChoice> {
    val scope = start.id.substringBeforeLast(':')
    val stages = choices.filter { it.stage.isNotEmpty() && it.playable && it.participantCount == start.participantCount &&
        it.id.substringBeforeLast(':') == scope }.groupBy { it.stage.lowercase() }
    val visited = mutableSetOf<String>()
    val sequence = mutableListOf<AnimationChoice>()
    var current: AnimationChoice? = start
    while (current != null && visited.add(current.id)) {
        sequence += current
        val successors = current.nextStages.flatMap { stages[it.lowercase()].orEmpty() }.distinctBy { it.id }
        current = successors.singleOrNull()
    }
    return sequence
}
