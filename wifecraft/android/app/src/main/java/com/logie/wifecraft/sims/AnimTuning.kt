package com.logie.wifecraft.sims

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One actor of a partner-animation entry: [actorId] (0, 1...), the CLIP's own name it plays ([clipName],
 * matched against [SimsClip.name]), and the entry's per-actor offset as written: [x], [y], [z] in the
 * Sims' own axes and units (metres, y up: an assumption, the files that use them were not seen) and
 * [angle] degrees about up.
 */
data class ActorSlot(val actorId: Int, val clipName: String, val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val angle: Float = 0f)

/**
 * One animation entry of a package's tuning that names its actors: [name] (the entry's own, else its first
 * clip's), [actors] by actor id, [loops] when the entry says (null: it does not), [locations] (the
 * furniture categories it is made for, e.g. "SOFA", "DOUBLE_BED"; empty: anywhere), [stageLinks] the stages
 * it may go on to, [stageName] its own stage name.
 */
data class PairDefinition(
    val name: String,
    val actors: List<ActorSlot>,
    val loops: Boolean? = null,
    val locations: Set<String> = emptySet(),
    val stageLinks: List<String> = emptyList(),
    val stageName: String = "",
)

/**
 * The snippet tuning (resource type 0x7DF2169C, XML) of an animation package, read for entries that list
 * their actors (`animation_actors_list`, as WickedWhims-style animation packs write them: element names
 * from a public modding guide, the class and root names unknown, so an entry is found by that list alone).
 * Only the neutral fields are read: per actor `actor_id`, `animation_clip_name` (or the older `animation_name`),
 * `animation_x_offset`, `animation_y_offset`, `animation_z_offset`, `animation_angle_offset` (or the older
 * `animation_facing_offset`); per entry `animation_loops`,
 * `animation_locations`, `animation_stage_name`, `animation_next_stages` and a name. Tuning without
 * actors (a pose pack's pose list) gives nothing. Damaged XML, or XML with a DOCTYPE, is skipped.
 */
object AnimTuning {
    const val TYPE = 0x7DF2169C

    /** Every entry with actors in [pkg]'s snippet tuning; an unreadable package or resource gives none (never throws). */
    fun pairs(pkg: ByteArray): List<PairDefinition> {
        if (!Dbpf.isDbpf(pkg)) return emptyList()
        val d = try { Dbpf(pkg) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<PairDefinition>()
        for (e in d.entries) {
            if (e.type != TYPE) continue
            val bytes = try { d.read(e) } catch (x: Exception) { continue }
            out += parse(bytes)
        }
        return out
    }

    /** As [pairs] for a package read from disk one resource at a time (the importer's streamed path). */
    fun pairs(pkg: DbpfFile): List<PairDefinition> {
        val out = ArrayList<PairDefinition>()
        for (e in pkg.entries) {
            if (e.type != TYPE || e.size !in 1..MAX_TUNING) continue
            val bytes = try { pkg.read(e) } catch (x: Exception) { continue }
            out += parse(bytes)
        }
        return out
    }

    private const val MAX_TUNING = 2_000_000

    fun parse(xml: ByteArray): List<PairDefinition> = parse(String(xml, Charsets.UTF_8))

    /** The entries with actors in one tuning file; empty for anything that is not readable XML. */
    fun parse(xml: String): List<PairDefinition> {
        val text = xml.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (!text.contains("animation_actors_list")) return emptyList()
        // no document type: no entities to expand, no outside files to reach
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) return emptyList()
        val doc = try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            f.isValidating = false
            f.isExpandEntityReferences = false
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            val builder = f.newDocumentBuilder()
            // damaged XML is reported by the exception alone, not printed
            builder.setErrorHandler(object : org.xml.sax.ErrorHandler {
                override fun warning(e: org.xml.sax.SAXParseException) {}
                override fun error(e: org.xml.sax.SAXParseException) { throw e }
                override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            })
            builder.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<PairDefinition>()
        val lists = doc.getElementsByTagName("*")
        for (i in 0 until lists.length) {
            val list = lists.item(i) as? Element ?: continue
            if (list.getAttribute("n") != "animation_actors_list") continue
            val entry = list.parentNode as? Element ?: continue
            entry(entry, list)?.let { out += it }
        }
        return out
    }

    private fun entry(entry: Element, list: Element): PairDefinition? {
        val actors = ArrayList<ActorSlot>()
        for ((k, a) in children(list).withIndex()) {
            val f = fields(a)
            // the older generation of the format names the clip animation_name and the turn animation_facing_offset
            val clip = (f["animation_clip_name"] ?: f["animation_name"])?.let { text(it) }.orEmpty()
            if (clip.isEmpty()) continue
            actors += ActorSlot(
                actorId = f["actor_id"]?.let { text(it).toIntOrNull() } ?: k,
                clipName = clip,
                x = num(f["animation_x_offset"]), y = num(f["animation_y_offset"]), z = num(f["animation_z_offset"]),
                angle = num(f["animation_angle_offset"] ?: f["animation_facing_offset"]),
            )
        }
        if (actors.isEmpty()) return null
        val f = fields(entry)
        val stage = f["animation_stage_name"]?.let { text(it) }.orEmpty()
        val name = listOf("animation_display_name", "animation_name", "display_name")
            .firstNotNullOfOrNull { k -> f[k]?.let { text(it) }?.takeIf { it.isNotEmpty() && !HEX.matches(it) } }
            ?: stage.ifEmpty { null }
            ?: entry.getAttribute("n").takeIf { it.isNotEmpty() && entry.parentNode?.nodeType == Node.DOCUMENT_NODE && !HEX.matches(it) }
            ?: actors.first().clipName.substringAfterLast(':')
        return PairDefinition(
            name = name,
            actors = actors.sortedBy { it.actorId },
            loops = f["animation_loops"]?.let { loops(text(it)) },
            locations = f["animation_locations"]?.let { values(it) }.orEmpty().map { it.uppercase() }.toSet(),
            stageLinks = f["animation_next_stages"]?.let { values(it) }.orEmpty(),
            stageName = stage,
        )
    }

    /** "True"/"False", or a repeat count: once (1) or none (0) does not loop, more or endless (-1) does. */
    private fun loops(s: String): Boolean? = when {
        s.equals("true", true) -> true
        s.equals("false", true) -> false
        else -> s.toIntOrNull()?.let { it < 0 || it > 1 }
    }

    private fun children(e: Element): List<Element> {
        val out = ArrayList<Element>()
        var n = e.firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }

    /** The direct children of [e] by their n="..." attribute (the first of a name wins). */
    private fun fields(e: Element): Map<String, Element> {
        val m = LinkedHashMap<String, Element>()
        for (c in children(e)) { val n = c.getAttribute("n"); if (n.isNotEmpty()) m.putIfAbsent(n, c) }
        return m
    }

    private fun text(e: Element) = e.textContent.orEmpty().trim()

    private fun num(e: Element?): Float = e?.let { text(it).toFloatOrNull() }?.takeIf { it.isFinite() } ?: 0f

    /** A list's values (its children's texts), or a single value split at commas. */
    private fun values(e: Element): List<String> {
        val kids = children(e)
        val raw = if (kids.isEmpty()) text(e).split(',') else kids.map { text(it) }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val HEX = Regex("0x[0-9A-Fa-f]{1,16}")
}
