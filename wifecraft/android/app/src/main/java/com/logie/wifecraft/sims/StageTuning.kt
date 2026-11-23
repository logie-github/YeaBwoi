package com.logie.wifecraft.sims

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** One actor of a WickedWhims stage as the tuning writes it: its clip, role hints and what it wears (naked). */
data class StageActor(
    val id: Int, val clip: String, val type: String = "", val preferredGender: String = "", val naked: String = "",
)

/** A prop of a stage: [guids] are object ids (an OBJD's instance, decimal), [clip] the clip that moves it (the object's own rig). */
data class StageProp(val id: Int, val type: String, val guids: List<Long>, val clip: String)

/**
 * One stage of a WickedWhims animation tuning (`WickedWhimsAnimationPackage`), read in full for the pack import: names and
 * category, the furniture it names ([locations], as written, a list or a comma-separated text), the actors, the props, the
 * events (`EFFECT`, `CUM`, sounds...) counted by type, the stage links and the loops. Both generations of the field names are
 * read (`animation_clip_name` / `animation_name`, `<L><U>` / `<T><T>`).
 */
data class Stage(
    val name: String, val author: String, val stage: String, val category: String, val locations: List<String>,
    val nextStages: List<String>, val loops: Int?, val actors: List<StageActor>, val props: List<StageProp>,
    val events: Map<String, Int>, val objectClip: String,
) {
    val actorCount: Int get() = actors.size
}

object StageTuning {
    const val TYPE = 0x7DF2169C
    private const val MAX_XML = 4_000_000

    /** Every stage of [xml], or null when it is not a WickedWhims animation tuning (damaged XML and DOCTYPEs included). */
    fun parse(xml: String): List<Stage>? {
        if (xml.length > MAX_XML || !xml.contains("WickedWhimsAnimationPackage")) return null
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return null
        val root = try {
            val f = DocumentBuilderFactory.newInstance()
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            val b = f.newDocumentBuilder()
            b.setErrorHandler(object : org.xml.sax.ErrorHandler {
                override fun warning(e: org.xml.sax.SAXParseException) {}
                override fun error(e: org.xml.sax.SAXParseException) { throw e }
                override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            })
            b.parse(InputSource(StringReader(xml.trimStart('﻿', ' ', '\n', '\r', '\t')))).documentElement
        } catch (e: Exception) { return null }
        if (root.getAttribute("c") != "WickedWhimsAnimationPackage") return null
        val list = root.child("animations_list") ?: return emptyList()
        return list.kids().map { stage(it) }
    }

    /** Every stage of a package's tuning resources (the first parse of each); an unreadable resource gives none. */
    fun stages(pkg: Dbpf): List<Stage> = stages(pkg.entries) { pkg.read(it) }

    /** As [stages] for a package read from disk one resource at a time. */
    fun stages(pkg: DbpfFile): List<Stage> = stages(pkg.entries) { pkg.read(it) }

    private fun stages(entries: List<Dbpf.Entry>, read: (Dbpf.Entry) -> ByteArray): List<Stage> {
        val out = ArrayList<Stage>()
        for (e in entries) {
            if (e.type != TYPE || e.size !in 1..MAX_XML) continue
            val xml = runCatching { read(e).decodeToString() }.getOrNull() ?: continue
            parse(xml)?.let { out += it }
        }
        return out
    }

    private fun stage(item: Element): Stage {
        val actors = item.child("animation_actors_list")?.kids().orEmpty().mapIndexed { i, a ->
            StageActor(a.field("actor_id")?.toIntOrNull() ?: i, a.field("animation_clip_name") ?: a.field("animation_name") ?: "",
                a.field("animation_type").orEmpty(), a.field("animation_pref_gender").orEmpty(),
                a.field("animation_naked_type") ?: a.field("animation_naked_flags").orEmpty())
        }.sortedBy { it.id }
        val props = item.child("animation_props_list")?.kids().orEmpty().mapIndexed { i, p ->
            StageProp(p.field("prop_id")?.toIntOrNull() ?: i, p.field("prop_type").orEmpty(),
                p.field("prop_guids").orEmpty().split(',', ' ', '\n', '\t').mapNotNull { it.trim().toULongOrNull()?.toLong() },
                p.field("prop_animation_clip_name").orEmpty())
        }
        val events = HashMap<String, Int>()
        item.child("animation_events_list")?.kids()?.forEach { ev -> events.merge(ev.field("event_type") ?: "?", 1, Int::plus) }
        return Stage(
            name = item.field("animation_raw_display_name").orEmpty(), author = item.field("animation_author").orEmpty(),
            stage = item.field("animation_stage_name").orEmpty(), category = item.field("animation_category").orEmpty().uppercase(),
            locations = item.child("animation_locations")?.let { listText(it) }.orEmpty(),
            nextStages = item.child("animation_next_stages")?.let { listText(it) }.orEmpty(),
            loops = item.field("animation_loops")?.toIntOrNull(), actors = actors, props = props, events = events,
            objectClip = item.field("object_animation_clip_name").orEmpty(),
        )
    }

    /** A list's values: its children's texts, or one text split at commas. */
    private fun listText(e: Element): List<String> {
        val kids = e.kids()
        val raw = if (kids.isEmpty()) e.textContent.orEmpty().split(',') else kids.map { it.textContent.orEmpty() }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun Element.kids(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    private fun Element.child(name: String): Element? = kids().firstOrNull { it.getAttribute("n") == name }
    private fun Element.field(name: String): String? = child(name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
