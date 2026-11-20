package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.DbpfFile
import com.logie.wifecraft.sims.Stbl
import com.logie.wifecraft.story.Vec3
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class PerformanceRole(val source: String, val offset: Vec3, val facing: Float)

/**
 * An author's performance read from a pack's tuning: its [roles] (each clip's offset in the tuning's Sims metres: x forward,
 * y left, z up; facing in radians). [scale] is model units per Sims metre of the skeleton its clips were converted with
 * ([Retargeter.unitsPerMetre]), the scale of the clips' own root movement, so the offsets are scaled as the clips are;
 * 0: not recorded (imported before it was: [PERFORMANCE_SCALE]).
 */
data class ImportedPerformance(
    val id: String, val name: String, val dance: Boolean, val roles: List<PerformanceRole>, val setting: String = "",
    val stage: String = "", val nextStages: List<String> = emptyList(), val repetitions: Int = 1, val style: String = "",
    val scale: Float = 0f,
) {
    /** Model units per tuning metre for this performance's offsets: the recorded skeleton's, else the old fixed one. */
    val offsetScale: Float get() = if (scale > 0f && scale.isFinite()) scale else PERFORMANCE_SCALE

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("dance", dance).put("setting", setting)
        .put("stage", stage).put("nextStages", JSONArray(nextStages)).put("repetitions", repetitions).put("style", style)
        .put("scale", scale.toDouble())
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
                json.optInt("repetitions", 1).coerceIn(1, 100), json.optString("style"),
                json.optDouble("scale", 0.0).toFloat().takeIf { it.isFinite() && it > 0f } ?: 0f)
        }
    }
}

/**
 * Reads only authored actor roles and placement from the pack’s tuning. It leaves Sims events and scripts alone.
 * Community packs write the same tuning in two generations of field names, both read: an actor's clip is
 * `animation_clip_name` or the older `animation_name`, its turn `animation_angle_offset` or `animation_facing_offset`
 * (degrees); lists are `<L>` or `<T>` elements. A display name given as a string-table hash (`0x0CBDA31C`) is looked up in
 * the package's own tables ([Stbl]). A pose pack's pose list (`PosePackInstance`) names its single poses the same way.
 * Entries for three or more people are left out (a scene plays one or two).
 */
internal object ImportedPerformances {
    private const val TUNING = 0x7DF2169C

    fun read(packageFile: DbpfFile, sources: Map<String, String>): List<ImportedPerformance> {
        val strings by lazy { runCatching { Stbl.read(packageFile) }.getOrDefault(emptyMap()) }
        return packageFile.entries.filter { it.type == TUNING && it.size in 1..2_000_000 }.flatMap { entry ->
            runCatching {
                val xml = packageFile.read(entry).decodeToString()
                read(xml, entry.key, sources, if (xml.contains(">0x")) strings else emptyMap())
            }.getOrDefault(emptyList())
        }
    }

    /** What a pack's tuning says about its clips: which it plays on an object, which on people and in groups of how many. */
    class ClipUse(val objects: Set<String>, val small: Set<String>, val groups: Set<String>) {
        /** Clip name (lower case) to why the importer leaves it out: an object's clip, or a clip only a group of three or more plays. */
        fun leftOut(): Map<String, String> = HashMap<String, String>().also { out ->
            for (name in groups) if (name !in small) out[name] = GROUP_CLIP
            for (name in objects) out[name] = OBJECT_CLIP
        }
    }

    const val OBJECT_CLIP = "A furniture or prop animation (the pack plays it on an object, not a person)"
    const val GROUP_CLIP = "One person's part of an animation for three or more (a scene plays one or two people)"

    /**
     * The clips a pack's tuning plays on the furniture or a prop (`object_animation_clip_name`, `animation_props_list`'s
     * `prop_animation_clip_name`: they move an object's own rig, not a person's) and the ones only its entries for three or
     * more people use, lower case, across all its tuning.
     */
    fun clipUse(packageFile: DbpfFile): ClipUse {
        val parts = packageFile.entries.filter { it.type == TUNING && it.size in 1..2_000_000 }.mapNotNull { entry ->
            runCatching { clipUse(packageFile.read(entry).decodeToString()) }.getOrNull()
        }
        return ClipUse(parts.flatMapTo(HashSet()) { it.objects }, parts.flatMapTo(HashSet()) { it.small }, parts.flatMapTo(HashSet()) { it.groups })
    }

    fun clipUse(xml: String): ClipUse {
        val none = ClipUse(emptySet(), emptySet(), emptySet())
        if (!xml.contains("animations_list")) return none
        val root = parse(xml)?.takeIf { it.getAttribute("c") == "WickedWhimsAnimationPackage" } ?: return none
        val list = root.child("animations_list") ?: return none
        val objects = HashSet<String>(); val small = HashSet<String>(); val groups = HashSet<String>()
        for (item in list.children()) {
            item.field("object_animation_clip_name")?.let { objects += it.lowercase() }
            item.child("animation_props_list")?.children()?.forEach { prop -> prop.field("prop_animation_clip_name")?.let { objects += it.lowercase() } }
            val names = item.child("animation_actors_list")?.children().orEmpty()
                .mapNotNull { (it.field("animation_clip_name") ?: it.field("animation_name"))?.lowercase() }
            if (names.size > 2) groups.addAll(names) else small.addAll(names)
        }
        return ClipUse(objects, small, groups)
    }

    private fun parse(xml: String): Element? {
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) return null
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return runCatching { builder.parse(InputSource(StringReader(xml))).documentElement }.getOrNull()
    }

    fun read(xml: String, resource: String, sources: Map<String, String>, strings: Map<Int, String> = emptyMap()): List<ImportedPerformance> {
        val root = parse(xml) ?: return emptyList()
        val dance = when (root.getAttribute("c")) {
            "WickedWhimsAnimationPackage" -> false
            "StripClubDanceAnimationPackage" -> true
            "PosePackInstance" -> return poses(root, resource, sources, strings)
            else -> return emptyList()
        }
        val list = root.child(if (dance) "dance_animations_list" else "animations_list") ?: return emptyList()
        return list.children().mapIndexedNotNull { index, item ->
            val positions = if (dance) emptyList() else item.child("animation_actors_list")?.children()
                ?.sortedBy { it.field("actor_id")?.toIntOrNull() ?: Int.MAX_VALUE }.orEmpty()
            val names = if (dance) {
                listOfNotNull(item.field("dancer_animation_clip_name"), item.field("watcher_animation_clip_name"))
            } else positions.map { (it.field("animation_clip_name") ?: it.field("animation_name")).orEmpty() }
            if (names.size !in 1..2 || names.any(String::isEmpty)) return@mapIndexedNotNull null
            if (names.none { it.lowercase() in sources }) return@mapIndexedNotNull null
            val roles = names.mapIndexed { i, name ->
                val source = sources[name.lowercase()].orEmpty()
                val actor = positions.getOrNull(i)
                val offset = Vec3(actor.number("animation_z_offset"), actor.number("animation_x_offset"), actor.number("animation_y_offset"))
                val degrees = actor?.field("animation_angle_offset")?.let { actor.number("animation_angle_offset") }
                    ?: actor.number("animation_facing_offset")
                PerformanceRole(source, offset, Math.toRadians(degrees.toDouble()).toFloat())
            }
            val name = item.field(if (dance) "raw_display_name" else "animation_raw_display_name")
                ?: Stbl.lookup(item.field(if (dance) "display_name" else "animation_display_name"), strings)
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

    /** A pose pack's poses ([read]'s `PosePackInstance`): each a single role, named by its display name, in the pack's order. */
    private fun poses(root: Element, resource: String, sources: Map<String, String>, strings: Map<Int, String>): List<ImportedPerformance> {
        val list = root.child("pose_list") ?: return emptyList()
        return list.children().mapIndexedNotNull { index, pose ->
            val clip = pose.field("pose_name") ?: return@mapIndexedNotNull null
            val source = sources[clip.lowercase()] ?: return@mapIndexedNotNull null
            val name = Stbl.lookup(pose.field("pose_display_name"), strings) ?: return@mapIndexedNotNull null
            ImportedPerformance("$resource:$index", name, false, listOf(PerformanceRole(source, Vec3(0f, 0f, 0f), 0f)))
        }
    }

    private fun Element.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.child(name: String): Element? = children().firstOrNull { it.getAttribute("n") == name }
    private fun Element.field(name: String): String? = child(name)?.textContent?.trim()?.takeIf(String::isNotEmpty)
    private fun Element?.number(name: String): Float = this?.field(name)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
}

/**
 * Model units per tuning metre for a performance imported before its skeleton's own scale was recorded
 * ([ImportedPerformance.scale]). It was the only scale until then and is 16% short of the clips' own (the Sims adult
 * skeleton's 1.4233): such a performance is put right by importing its pack again.
 */
const val PERFORMANCE_SCALE = 1.2f

/**
 * One entry of the animation browser: an author's performance, a single clip, or a partner animation of the library
 * ([PairAnim.choice]: [yards] its offsets are already in yards, as [Retarget.convertPair] made them, so they are not
 * scaled; [sounds] its sound events, [SoundEvent.actor] the role).
 */
data class AnimationChoice(
    val id: String, val name: String, val group: String, val preview: CheckedAnimation,
    val clips: List<CheckedAnimation>, val roles: List<PerformanceRole>, val setting: String = "",
    val stage: String = "", val nextStages: List<String> = emptyList(), val repetitions: Int = 1,
    val yards: Boolean = false, val sounds: List<SoundEvent> = emptyList(),
    /** Model units per offset unit of [roles] when not [yards] (the performance's [ImportedPerformance.offsetScale]). */
    val offsetScale: Float = PERFORMANCE_SCALE,
    /** A staged animation's parts in play order ([foldStages]: start once, loops, end once); empty: it is one clip. */
    val segments: List<ChoiceSegment> = emptyList(),
) {
    /** What [roles]' offsets are multiplied by for the scene (model units; then [com.logie.wifecraft.anim.HumanScale.PAIR] across). */
    val roleScale: Float get() = if (yards) 1f else offsetScale
    val participantCount: Int get() = roles.size
    val playable: Boolean get() = clips.size == roles.size && clips.all { it.inspection.type != ClipType.INVALID }
}

fun animationChoices(clips: List<CheckedAnimation>): List<AnimationChoice> {
    val bySource = clips.filter { it.clip.source.isNotBlank() }.associateBy { it.clip.source }
    val groups = clips.flatMap { it.clip.performances }.distinctBy { it.id }
    val authoredGroups = groups.distinctBy { it.copy(id = "", scale = 0f) }
    val authoredSources = groups.flatMap { it.roles }.mapNotNullTo(HashSet()) { it.source.takeIf(String::isNotBlank) }
    val chains = stageChains(authoredGroups)
    val coordinated = authoredGroups.mapNotNull { group ->
        val lead = group.roles.firstNotNullOfOrNull { bySource[it.source] } ?: return@mapNotNull null
        val chained = chains[group.id]
        val numbered = stageName.matchEntire(group.stage)
        val groupName = when {
            chained != null -> "${readable(chainFamily(chained.first.stage)).replaceFirstChar(Char::uppercase)} sequence"
            numbered != null -> "${readable(numbered.groupValues[1])} sequence"
            group.dance && group.style.isNotEmpty() -> readable(group.style)
            group.dance && group.roles.size == 2 -> "Partner dances"
            group.dance -> "Solo dances"
            group.roles.size == 2 -> "Partner animations"
            else -> lead.inspection.type.label
        }
        val stageLabel = chained?.let { "Stage ${it.second}" } ?: numbered?.groupValues?.get(2)?.let { "Stage $it" }
            ?: group.stage.takeIf(String::isNotBlank)?.let { readable(it.substringAfter(':')) }
        val name = listOfNotNull(stageLabel, group.name).joinToString(" · ")
        AnimationChoice(group.id, name, groupName, lead, group.roles.mapNotNull { bySource[it.source] }, group.roles,
            group.setting, group.stage, group.nextStages, group.repetitions, offsetScale = group.offsetScale)
    }
    val standalone = clips.filterNot { it.clip.source in authoredSources }
    val families = standalone.mapNotNull { sourceFamily(it)?.lowercase() }.groupingBy { it }.eachCount()
    val solos = standalone.map {
        val family = sourceFamily(it)?.takeIf { label -> (families[label.lowercase()] ?: 0) > 1 }
        val label = family ?: "Solo · ${it.inspection.type.label}"
        AnimationChoice(it.clip.id, it.clip.name, label, it, listOf(it),
            listOf(PerformanceRole(it.clip.source, Vec3(0f, 0f, 0f), 0f)))
    }
    // a pack's start, loop and end clips are one entry (StageRules)
    return foldStages((coordinated + solos).sortedWith(compareBy({ it.group },
        { chains[it.id]?.second ?: stageName.matchEntire(it.stage)?.groupValues?.get(2)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: Int.MAX_VALUE },
        { it.stage.lowercase() }, { it.name.lowercase() })))
}

/**
 * The author's stage chains among [groups]: from each entry no other entry leads to, its next-stage links followed while
 * they lead to exactly one entry not yet visited (as [animationSequence] plays them), within one tuning resource and one
 * number of people. Each entry of a chain of two or more to the chain's first entry and its place in it (1, 2...);
 * an entry two chains reach belongs to the first. Packs that name stages freely ("morning1", "…_S01", "…V1 Stage 2", "…Climax")
 * are grouped by their links, not their names.
 */
private fun stageChains(groups: List<ImportedPerformance>): Map<String, Pair<ImportedPerformance, Int>> {
    val out = HashMap<String, Pair<ImportedPerformance, Int>>()
    val staged = groups.filter { it.stage.isNotEmpty() }
    for (scope in staged.groupBy { it.id.substringBeforeLast(':') + "\u0000" + it.roles.size }.values) {
        val byStage = scope.groupBy { it.stage.lowercase() }
        val pointed = scope.flatMapTo(HashSet()) { g -> g.nextStages.map { it.lowercase() } }
        for (head in scope) {
            if (head.stage.lowercase() in pointed || head.id in out) continue
            val chain = ArrayList<ImportedPerformance>()
            val seen = HashSet<String>()
            var current: ImportedPerformance? = head
            while (current != null && current.id !in out && seen.add(current.stage.lowercase())) {
                chain += current
                current = current.nextStages.flatMap { byStage[it.lowercase()].orEmpty() }.distinctBy { it.id }.singleOrNull()
            }
            if (chain.size >= 2) chain.forEachIndexed { i, g -> out[g.id] = head to i + 1 }
        }
    }
    return out
}

/** A chain's name from its first stage's: the author's prefix ("Azmodan22:") and a trailing stage number left out. */
private fun chainFamily(stage: String): String =
    stage.substringAfter(':').replace(chainNumber, "").trim(' ', '_', '-').ifEmpty { stage }

private val chainNumber = Regex("""[_\s-]*(?:stage[_\s-]*\d+[a-z]?|s\d+|\d+)$""", RegexOption.IGNORE_CASE)

private val stageName = Regex("""(.+?)[_\s-]+stage[_\s-]*(\d+[a-z]*)""", RegexOption.IGNORE_CASE)
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
