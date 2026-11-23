package com.logie.wifecraft.props

import com.logie.wifecraft.pack.PackKind
import com.logie.wifecraft.sims.DbpfFile
import com.logie.wifecraft.sims.SimsReader
import com.logie.wifecraft.sims.Stage
import com.logie.wifecraft.sims.StageTuning
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the store knows of one prop: where it came from and how big it is. */
data class PropInfo(val guid: Long, val kind: PackKind, val source: String, val vertices: Int, val triangles: Int, val size: List<Float>, val variant: Int?)

/** One stage's use of a prop: the actors' clip names (lower case) that identify the stage, the prop's id in the stage, its object ids and its own clip. */
data class PropUse(val stage: String, val actorClips: List<String>, val propId: Int, val guids: List<Long>, val clip: String)

/** What importing one package into the store did. */
class PropImport(val objects: Int, val clips: Int, val uses: Int, val problems: List<String>)

/**
 * The props on the phone (`files/props`): `models/<guid>.wcprop` (the engine's file, [PropFile]), `clips/<name>.wcpa` (a prop's own
 * animation, [PropClip]), `index.json` (what is there) and `links.json` (which stages use which props). Nothing is executed; a
 * package is only read. A prop of the game's own (an object id no package here defines: a plate, a cup) has no model and its
 * stage plays without it.
 */
class PropStore(val root: File) {
    private val models = File(root, "models")
    private val clips = File(root, "clips")

    fun modelFile(guid: Long): File = File(models, "$guid.wcprop")
    fun has(guid: Long): Boolean = modelFile(guid).isFile
    fun clipFile(name: String): File = File(clips, safe(name) + ".wcpa")

    fun model(guid: Long): PropModel? = runCatching { PropFile.read(modelFile(guid).readBytes()) }.getOrNull()
    fun clip(name: String): PropClip? = clipFile(name).takeIf { it.isFile }?.let { runCatching { PropClip.fromBytes(it.readBytes()) }.getOrNull() }

    private class Cached<T>(var stamp: Long = -1, var value: T)
    private val indexCache = Cached<List<PropInfo>>(value = emptyList())
    private val usesCache = Cached<List<PropUse>>(value = emptyList())

    /** [read]'s result for [file], read again only when the file changed (the scene asks every frame). */
    private fun <T> cached(c: Cached<T>, file: File, read: () -> T): T {
        val stamp = if (file.isFile) file.lastModified() * 31 + file.length() else 0L
        synchronized(c) { if (c.stamp != stamp) { c.value = read(); c.stamp = stamp }; return c.value }
    }

    fun index(): List<PropInfo> = cached(indexCache, File(root, "index.json")) { readIndex() }

    private fun readIndex(): List<PropInfo> = runCatching {
        val a = JSONArray(File(root, "index.json").readText())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            PropInfo(o.getString("guid").toLong(), PackKind.of(o.optString("kind")) ?: PackKind.PROPS, o.optString("source"),
                o.optInt("vertices"), o.optInt("triangles"), o.optJSONArray("size")?.let { s -> List(s.length()) { s.getDouble(it).toFloat() } }.orEmpty(),
                if (o.has("variant")) o.getInt("variant") else null)
        }
    }.getOrDefault(emptyList())

    fun uses(): List<PropUse> = cached(usesCache, File(root, "links.json")) { readUses() }

    private fun readUses(): List<PropUse> = runCatching {
        val a = JSONArray(File(root, "links.json").readText())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            PropUse(o.optString("stage"), o.getJSONArray("actors").let { x -> List(x.length()) { x.getString(it) } }, o.optInt("prop"),
                o.getJSONArray("guids").let { x -> List(x.length()) { x.getString(it).toLong() } }, o.optString("clip"))
        }
    }.getOrDefault(emptyList())

    /** The props the stage that plays [actorClips] uses (clip names compared in lower case), in the stage's own order. */
    fun usesFor(actorClips: Collection<String>): List<PropUse> {
        val wanted = actorClips.map { it.lowercase() }.toSet()
        return uses().filter { u -> u.actorClips.isNotEmpty() && u.actorClips.all { it in wanted } }
    }

    /**
     * Reads the objects of [pkg] (a package of kind [kind]) into models, every clip it holds that a stage names as a prop's
     * (`prop_animation_clip_name`, `object_animation_clip_name`) into a prop animation, and its stages' prop uses into the links.
     * One bad object or clip is a problem line; the rest carries on. Models are replaced atomically, so a stopped import leaves
     * whole files only.
     */
    fun importPackage(pkg: File, kind: PackKind, source: String = pkg.name): PropImport {
        val problems = ArrayList<String>()
        var objects = 0
        var clipCount = 0
        val stages: List<Stage>
        DbpfFile(pkg).use { d ->
            stages = StageTuning.stages(d)
            val found = SimsObjects.read(d, problems)
            val infos = index().filter { i -> found.none { it.guid == i.guid } }.toMutableList()
            val cache = HashMap<Key, com.logie.wifecraft.bodyparts.RgbaImage?>()
            for (o in found) {
                try {
                    val model = PropFile.fromObject(o) { k -> cache.getOrPut(k) { SimsObjects.texture(d, k, PropFile.MAX_TEXTURE_SIDE) } }
                    writeAtomic(modelFile(o.guid), PropFile.write(model))
                    infos += PropInfo(o.guid, kind, source, model.vertexCount, model.triangleCount, model.size.toList(), o.variantHash)
                    objects++
                } catch (e: Exception) {
                    problems += "object ${o.guid}: ${e.message?.take(120)}"
                }
            }
            writeAtomic(File(root, "index.json"), JSONArray().also { a ->
                for (i in infos) a.put(JSONObject().put("guid", i.guid.toString()).put("kind", i.kind.key).put("source", i.source)
                    .put("vertices", i.vertices).put("triangles", i.triangles).put("size", JSONArray(i.size.map { it.toDouble() }))
                    .also { o -> i.variant?.let { v -> o.put("variant", v) } })
            }.toString().toByteArray())
            val wanted = stages.flatMap { s -> s.props.map { it.clip } + s.objectClip }.filter { it.isNotEmpty() }.map { it.lowercase() }.toSet()
            if (wanted.isNotEmpty()) {
                SimsReader.visitClips(d, { c ->
                    if (c.name.lowercase() in wanted) {
                        try { writeAtomic(clipFile(c.name), PropClip.of(c).toBytes()); clipCount++ }
                        catch (e: Exception) { problems += "clip ${c.name}: ${e.message?.take(100)}" }
                    }
                }, { key, why -> problems += "clip $key: ${why.take(100)}" })
            }
        }
        val added = linksOf(stages)
        if (added.isNotEmpty()) {
            val keep = uses().filter { old -> added.none { it.stage == old.stage && it.actorClips == old.actorClips && it.propId == old.propId } }
            writeAtomic(File(root, "links.json"), JSONArray().also { a ->
                for (u in keep + added) a.put(JSONObject().put("stage", u.stage).put("actors", JSONArray(u.actorClips)).put("prop", u.propId)
                    .put("guids", JSONArray(u.guids.map { it.toString() })).put("clip", u.clip))
            }.toString().toByteArray())
        }
        return PropImport(objects, clipCount, added.size, problems)
    }

    fun remove(): Boolean = root.deleteRecursively()

    companion object {
        /** The props every stage names, one [PropUse] each (a stage with no prop gives none). */
        fun linksOf(stages: List<Stage>): List<PropUse> = stages.flatMap { s ->
            val clips = s.actors.map { it.clip.lowercase() }.filter { it.isNotEmpty() }
            s.props.filter { it.guids.isNotEmpty() || it.clip.isNotEmpty() }.map { PropUse(s.stage.ifEmpty { s.name }, clips, it.id, it.guids, it.clip) }
        }

        fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(120)

        private fun writeAtomic(f: File, bytes: ByteArray) {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
