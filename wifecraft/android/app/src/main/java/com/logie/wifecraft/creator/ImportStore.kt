package com.logie.wifecraft.creator

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** The imports, as Wrath++ has them: each a zip the user picks on the phone. Nothing of any zip is in the app or the repository. */
enum class ImportKind(val key: String, val label: String, val zipNames: List<String>, val textures: Boolean) {
    /** Ralia's mod: character textures named as today's game names them. */
    RALIA("ralia", "Ralia's mod", listOf("ralia.zip"), true),
    /** Alternate Female Options: the women's textures alone. */
    ALTERNATE("alternate-female", "Alternate Female Options", listOf("alternate-female.zip"), true),
    /** Wrath++'s body options: Shape, Style, Density (the package `pack2.py` makes, format 2). */
    BODY("wrathpp-body", "Body options (Shape, Style, Density)", listOf("wrathpp-body.zip", "body.zip"), false),
    /** Wrath++'s undead skins (undead_pack.py's package, WrathppUndead.kt): new skin colours for the living races, each a body and a face. */
    UNDEAD("wrathpp-undead", "Undead skins", listOf("wrathpp-undead-skins.zip", "undead-skins.zip"), false),
}

data class ImportStatus(
    val kind: ImportKind,
    val installed: Boolean,
    /** Files kept (textures matched to the game's, or the package's files). */
    val files: Int,
    /** Textures in the zip the game's file list had no name for (textures only). */
    val unmatched: Int,
    /** Where it came from: the zip's name (and for a found one, its size and date). */
    val source: String?,
    /** For a texture set: it is the one drawn (one set at a time, as the launcher's switches). */
    val active: Boolean,
    /** Taken off by the user: not looked for again until a zip is picked. */
    val removed: Boolean,
)

class ImportException(message: String) : IOException(message)

/**
 * What the user's zips put into the creator. [root] is the app's files folder: what is taken from a zip is kept under
 * `imports/<kind>/` (its `meta.json` and the files, by the game's path or the package's own), and each file is also written
 * to `game/<id>` under a synthetic id (>= [FIRST_ID], stable per path, recorded in `imports/ids.json`), where the renderer reads
 * the game's files by id. [names] maps a texture's game path to the retail file id it replaces. Everything is written whole
 * and renamed into place; a zip that fails leaves what was there as it was. Implements [Overrides]. Pure JVM; NOT run on a phone.
 */
class ImportStore(
    private val root: File,
    private val names: ImportNames,
    private val maxEntry: Long = 512L shl 20,
    private val maxTotal: Long = 6L shl 30,
    val supportedKinds: Set<ImportKind> = ImportKind.entries.toSet(),
) : Overrides {
    private val imports = File(root, "imports")
    private val game = File(root, "game")

    private class Loaded(val metas: Map<ImportKind, JSONObject>, val active: ImportKind?, val retail: Map<Int, Int>, val textureKind: ImportKind?)

    @Volatile private var loaded = load()

    init {
        current = this
        repair()
    }

    /**
     * Puts back what of an import's files is missing from `game/<id>` while its own copy is still under `imports/<kind>/`
     * (release 1.0.17's move to a pinned game build deleted imported body files from `game/` by mistake: the package's
     * models look like the game's). Without it the Body options said their files were missing until the zip was picked again.
     */
    private fun repair() {
        val ids = File(imports, "ids.json")
        if (!ids.isFile) return
        val map = runCatching { JSONObject(ids.readText()) }.getOrNull() ?: return
        game.mkdirs()
        for (key in map.keys()) {
            val id = map.optInt(key, 0).takeIf { it >= FIRST_ID } ?: continue
            val target = File(game, id.toString())
            if (target.isFile && target.length() > 0) continue
            // where importZip keeps it: imports/<kind>/files/<path> (the key is <kind>/<path>); imports/<key> as before
            val slash = key.indexOf('/')
            val kept = if (slash > 0) File(imports, key.substring(0, slash) + "/files/" + key.substring(slash + 1)) else null
            val src = kept?.takeIf { it.isFile } ?: File(imports, key)
            if (!src.isFile || !src.canonicalPath.startsWith(imports.canonicalPath + File.separator)) continue
            runCatching {
                val part = File(game, "$id.part").apply { delete() }
                src.copyTo(part, overwrite = true)
                if (!part.renameTo(target)) { target.delete(); part.renameTo(target) }
            }
        }
    }

    private fun dir(kind: ImportKind) = File(imports, kind.key)
    private fun off(kind: ImportKind) = File(imports, kind.key + ".off")

    // ---------------------------------------------------------------- what is in

    fun status(kind: ImportKind): ImportStatus {
        val l = loaded
        val m = l.metas[kind]
        return ImportStatus(kind, m != null, m?.optInt("files") ?: 0, m?.optInt("unmatched") ?: 0, m?.optString("source")?.ifEmpty { null },
            kind.textures && m != null && l.textureKind == kind, off(kind).exists())
    }

    fun statuses(): List<ImportStatus> = ImportKind.entries.filter { it in supportedKinds }.map { status(it) }

    /** The texture set drawn: the one picked last, or the other when that is gone. */
    val activeTextures: ImportKind? get() = loaded.textureKind

    @Synchronized
    fun setActive(kind: ImportKind) {
        require(kind.textures) { "Only a texture set can be switched on." }
        if (loaded.metas[kind] == null) throw ImportException("${kind.label} is not imported.")
        write(File(imports, "active.txt"), kind.key.toByteArray())
        loaded = load()
    }

    private fun load(): Loaded {
        val metas = HashMap<ImportKind, JSONObject>()
        for (k in supportedKinds) {
            val f = File(dir(k), "meta.json")
            if (f.isFile) runCatching { JSONObject(f.readText()) }.getOrNull()?.let { metas[k] = it }
        }
        val wanted = runCatching { File(imports, "active.txt").readText().trim() }.getOrNull()
        val textureKind = ImportKind.values().filter { it.textures && it in metas }.let { sets -> sets.firstOrNull { it.key == wanted } ?: sets.firstOrNull() }
        val retail = HashMap<Int, Int>()
        textureKind?.let { k ->
            val r = metas.getValue(k).optJSONObject("retail")
            if (r != null) for (key in r.keys()) retail[key.toInt()] = r.getInt(key)
        }
        return Loaded(metas, textureKind, retail, textureKind)
    }

    // ---------------------------------------------------------------- Overrides

    override fun texture(retailFileId: Int, female: Boolean): Int {
        val l = loaded
        if (l.textureKind == ImportKind.ALTERNATE && !female) return retailFileId
        return l.retail[retailFileId] ?: retailFileId
    }

    private fun body(): JSONObject? = loaded.metas[ImportKind.BODY]

    private fun modelEntry(raceId: Int, shape: Int): JSONObject? {
        val a = body()?.optJSONArray("models") ?: return null
        for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.getInt("race_id") == raceId && it.getInt("shape") == shape) return it }
        return null
    }

    override fun bodyModel(raceId: Int, shape: Int): Int? = if (shape == 0) null else modelEntry(raceId, shape)?.optInt("model", 0)?.takeIf { it != 0 }

    /** The skin file the shape's model goes with (the package's one skin for the shapes), or null. */
    fun bodySkin(raceId: Int, shape: Int): Int? = modelEntry(raceId, shape)?.optInt("skin", 0)?.takeIf { it != 0 }

    override fun bodyLayer(raceId: Int, style: Int, density: Int): Int? {
        val a = body()?.optJSONArray("layers") ?: return null
        for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.getInt("race_id") == raceId && it.getInt("style") == style && it.getInt("density") == density) return it.getInt("id") }
        return null
    }

    override fun hasRalia() = loaded.textureKind != null

    override fun signature(): String {
        val l = loaded
        val on = l.metas.filterKeys { !it.textures || it == l.textureKind }
        return on.keys.sortedBy { it.ordinal }.joinToString(";") { k -> k.key + ":" + l.metas.getValue(k).optString("sha256").take(16) }
    }
    override fun hasBody() = body() != null

    // ---------------------------------------------------------------- the undead skins

    override fun hasUndead() = loaded.metas[ImportKind.UNDEAD] != null

    private fun undeadModel(raceId: Int, sex: Sex): JSONObject? {
        val a = loaded.metas[ImportKind.UNDEAD]?.optJSONArray("models") ?: return null
        val s = if (sex == Sex.FEMALE) 1 else 0
        for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.optInt("race_id") == raceId && it.optInt("sex") == s) return it }
        return null
    }

    override fun undeadSkins(raceId: Int, sex: Sex): List<UndeadSkin> {
        val skins = undeadModel(raceId, sex)?.optJSONArray("skins") ?: return emptyList()
        return (0 until skins.length()).map { skins.getJSONObject(it) }.map { UndeadSkin(it.getString("name"), it.optInt("swatch"), it.getInt("skin"), it.getInt("face")) }
    }

    override fun undeadUnderwear(raceId: Int, sex: Sex): String = undeadModel(raceId, sex)?.optString("underwear", "race")?.ifEmpty { "race" } ?: "race"

    /** What the imported undead skins have, for the Imports row: the race and sex keys ("10_1") and how many skins. */
    data class UndeadSummary(val models: List<String>, val skins: Int)

    fun undeadSummary(): UndeadSummary? {
        val a = loaded.metas[ImportKind.UNDEAD]?.optJSONArray("models") ?: return null
        val models = (0 until a.length()).map { a.getJSONObject(it) }
        return UndeadSummary(models.map { "${it.getInt("race_id")}_${it.getInt("sex")}" }, models.sumOf { it.optJSONArray("skins")?.length() ?: 0 })
    }

    /** What the imported body options have, for the Imports row: the races (ids) with shapes, and those with designs. */
    data class BodySummary(val shapes: Set<Int>, val layers: Set<Int>)

    fun bodySummary(): BodySummary? {
        val b = body() ?: return null
        fun ids(name: String, keep: (JSONObject) -> Boolean): Set<Int> {
            val a = b.optJSONArray(name) ?: return emptySet()
            return (0 until a.length()).map { a.getJSONObject(it) }.filter(keep).map { it.getInt("race_id") }.toSortedSet()
        }
        return BodySummary(ids("models") { it.optInt("shape") > 0 }, ids("layers") { true })
    }

    // ---------------------------------------------------------------- taking a zip in

    private class Staged(val path: String, val file: File)

    /**
     * Takes the zip in, in place of what was there; returns the new status. [source]: what to show as where it came from;
     * [mark]: for a zip found by name, its size and date ([lookFor]). A zip that has nothing of the kind, a path leaving
     * its folder, names the game's file list can't resolve, or a package whose files don't match its list is refused, and
     * what was there is left as it was.
     */
    @Synchronized
    fun importZip(kind: ImportKind, zip: InputStream, source: String, mark: String? = null): ImportStatus {
        if (kind.textures && !names.available()) throw ImportException("The game's file names aren't loaded yet; connect and try again.")
        imports.mkdirs(); game.mkdirs()
        val staging = File(imports, kind.key + ".new").apply { deleteRecursively() }
        val staged = ArrayList<Staged>()
        val written = ArrayList<File>()
        val oldStored = storedIds(kind)
        try {
            val filesDir = File(staging, "files").apply { mkdirs() }
            val digest = MessageDigest.getInstance("SHA-256")
            val counted = DigestInputStream(zip, digest)
            val byPath = LinkedHashMap<String, Staged>()
            var total = 0L
            ZipInputStream(counted).use { z ->
                while (true) {
                    val entry = z.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val raw = entry.name.replace('\\', '/')
                    if (raw.startsWith("/") || raw.contains(':') || raw.split('/').any { it == ".." || it.isEmpty() }) throw ImportException("Bad path in the zip: ${entry.name}")
                    val path: String = if (kind.textures) {
                        val p = ImportNames.gamePath(raw) ?: continue
                        if (!p.endsWith(".blp")) continue
                        if (kind == ImportKind.ALTERNATE && !p.contains("/female/")) continue
                        p
                    } else if (kind == ImportKind.UNDEAD) {
                        // the manifest and the textures, as WrathppUndead.importZip keeps them; anything else is left
                        if (!raw.endsWith(UNDEAD_LIST) && !raw.endsWith(".blp", ignoreCase = true)) continue
                        raw
                    } else raw
                    val target = File(filesDir, path)
                    if (!target.canonicalPath.startsWith(filesDir.canonicalPath + File.separator)) throw ImportException("Bad path in the zip: ${entry.name}")
                    target.parentFile?.mkdirs()
                    var size = 0L
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = z.read(buf)
                            if (n < 0) break
                            size += n; total += n
                            if (size > maxEntry || total > maxTotal) throw ImportException("This zip is too large.")
                            out.write(buf, 0, n)
                        }
                    }
                    byPath[path] = Staged(path, target)
                }
                val rest = ByteArray(1 shl 14)
                while (counted.read(rest) >= 0) { /* the digest is of the whole file */ }
            }
            staged.addAll(byPath.values)
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val ids = Ids.read(File(imports, "ids.json"))
            val meta = JSONObject().put("kind", kind.key).put("source", source).put("sha256", sha).put("mark", mark ?: "")
            val stored = LinkedHashSet<Int>()
            when {
                kind.textures -> buildTextures(kind, staged, ids, meta, stored)
                kind == ImportKind.UNDEAD -> buildUndead(kind, staged, filesDir, ids, meta, stored)
                else -> buildBody(kind, staged, filesDir, ids, meta, stored)
            }
            meta.put("stored", JSONArray(stored.toList()))
            // each file under its synthetic id, where the renderer reads the game's files: whole, then renamed
            val byId = HashMap<Int, File>()
            for (s in staged) ids.peek(kind.key + "/" + s.path)?.let { byId[it] = s.file }
            for (id in stored) {
                val src = byId[id] ?: throw IOException("A file of the zip went missing.")
                val target = File(game, id.toString())
                val part = File(game, "$id.part").apply { delete() }
                try { Files.createLink(part.toPath(), src.toPath()) } catch (e: Exception) { src.copyTo(part, overwrite = true) }
                if (!part.renameTo(target)) { target.delete(); if (!part.renameTo(target)) { part.delete(); throw IOException("File $id couldn't be saved.") } }
                written.add(target)
            }
            write(File(staging, "meta.json"), meta.toString(1).toByteArray())
            // swap in: the old folder aside until the new is in place
            val here = dir(kind)
            val aside = File(imports, kind.key + ".old").apply { deleteRecursively() }
            if (here.exists() && !here.renameTo(aside)) throw IOException("The old import couldn't be moved aside.")
            if (!staging.renameTo(here)) { aside.renameTo(here); throw IOException("The import couldn't be put in place.") }
            aside.deleteRecursively()
            for (id in oldStored - stored) File(game, id.toString()).delete()
            ids.write()
            off(kind).delete()
            if (kind.textures) write(File(imports, "active.txt"), kind.key.toByteArray())
            loaded = load()
            return status(kind)
        } catch (e: Exception) {
            for (f in written) if (f.name.toInt() !in oldStored) f.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun buildTextures(kind: ImportKind, staged: List<Staged>, ids: Ids, meta: JSONObject, stored: MutableSet<Int>) {
        if (staged.isEmpty()) throw ImportException(
            if (kind == ImportKind.ALTERNATE) "This zip has no women's character textures (a Character folder with Female .blp files) in it."
            else "This zip has no character textures (a Character folder of .blp files) in it.")
        val retail = JSONObject()
        var unmatched = 0
        for (s in staged) {
            val retailIds = ImportNames.retailIds(names, s.path)
            if (retailIds.isEmpty()) { unmatched++; continue }
            val id = ids.idFor(kind.key + "/" + s.path)
            stored.add(id)
            for (r in retailIds) retail.put(r.toString(), id)
        }
        if (stored.isEmpty()) throw ImportException("None of this zip's textures has the name of a file of the game.")
        meta.put("retail", retail).put("files", stored.size).put("unmatched", unmatched)
    }

    private fun buildBody(kind: ImportKind, staged: List<Staged>, filesDir: File, ids: Ids, meta: JSONObject, stored: MutableSet<Int>) {
        // the list at the zip's top, as pack2.py writes it, or in the one folder a zip made again of the unpacked package has
        val prefix = when {
            staged.any { it.path == LIST } -> ""
            else -> staged.map { it.path }.filter { it.endsWith("/$LIST") && it.count { c -> c == '/' } == 1 }.singleOrNull()?.removeSuffix(LIST)
                ?: throw ImportException("This zip has no body options in it (no $LIST).")
        }
        val listFile = File(filesDir, prefix + LIST)
        val list = try { JSONObject(listFile.readText()) } catch (e: Exception) { throw ImportException("The package's list can't be read.") }
        if (list.optString("kind") != "wrathpp-body") throw ImportException("This zip isn't a Wrath++ body options package.")
        if (list.optInt("format") != 2) throw ImportException("This is an older body options package (format ${list.optInt("format")}); the current one is format 2.")
        val models = list.optJSONArray("models")
        if (models == null || models.length() == 0) throw ImportException("The package names no models.")
        val have = staged.associateBy { it.path }
        fun idOf(entry: JSONObject, what: String): Int {
            val name = prefix + entry.getString("file")
            val s = have[name]?.takeIf { it.file.length() == entry.getLong("size") && sha256(it.file) == entry.getString("sha256") }
                ?: throw ImportException("$what is missing or damaged.")
            return ids.idFor(kind.key + "/" + s.path).also { stored.add(it) }
        }
        val raceIds = HashMap<String, Int>()
        val factions = HashMap<Int, String>()
        val races = list.optJSONArray("races") ?: JSONArray()
        for (i in 0 until races.length()) races.getJSONObject(i).let { raceIds[it.getString("race")] = it.getInt("race_id"); factions[it.getInt("race_id")] = it.getString("faction") }
        val outModels = JSONArray()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val race = m.getString("race")
            val raceId = raceIds[race] ?: throw ImportException("The package's list has no race $race.")
            // the package's model is a patch of the 3.3.5 looks' model, not a model: kept as the zip has it
            val o = JSONObject().put("race_id", raceId).put("shape", m.getInt("shape")).put("model", idOf(m.getJSONObject("patch"), "$race's model"))
            m.optJSONObject("skin_file")?.let { o.put("skin", idOf(it, "$race's skin")) }
            outModels.put(o)
        }
        val styles = list.optJSONObject("styles") ?: JSONObject()
        val textures = list.optJSONArray("textures") ?: JSONArray()
        val design = HashMap<Pair<String, Int>, Int>()
        for (i in 0 until textures.length()) textures.getJSONObject(i).let { design[it.optString("design") to it.optInt("density")] = idOf(it, "A design (${it.optString("design")})") }
        val layers = JSONArray()
        for ((raceId, faction) in factions) {
            val names = styles.optJSONArray(faction) ?: continue
            for (s in 1..minOf(16, names.length())) for (d in 1..4) {
                val id = design[names.getString(s - 1) to d] ?: continue
                layers.put(JSONObject().put("race_id", raceId).put("style", s).put("density", d).put("id", id))
            }
        }
        design[("" to 0)]?.let { meta.put("empty", it) }
        meta.put("models", outModels).put("layers", layers).put("files", stored.size)
    }

    /**
     * Wrath++'s undead skins (undead_pack.py: `manifest.json`, format 1, kind wrathpp-undead-skins, and the textures under
     * `client/` at the game's paths it names; the zip's top, or one folder a zip made again of it has): each skin's body and
     * face under synthetic ids. A face cut turned (UndeadSkins.HEADS) is turned back here, the turned copy kept beside it.
     * A manifest naming a texture the zip hasn't, or one that isn't a BLP2, is refused (WrathppUndead.importZip's checks).
     */
    private fun buildUndead(kind: ImportKind, staged: MutableList<Staged>, filesDir: File, ids: Ids, meta: JSONObject, stored: MutableSet<Int>) {
        val prefix = when {
            staged.any { it.path == UNDEAD_LIST } -> ""
            else -> staged.map { it.path }.filter { it.endsWith("/$UNDEAD_LIST") && it.count { c -> c == '/' } == 1 }.singleOrNull()?.removeSuffix(UNDEAD_LIST)
                ?: throw ImportException("This zip has no undead skins in it (no $UNDEAD_LIST).")
        }
        val list = try { JSONObject(File(filesDir, prefix + UNDEAD_LIST).readText()) } catch (e: Exception) { throw ImportException("The skins' manifest can't be read.") }
        if (list.optString("kind") != "wrathpp-undead-skins" || list.optInt("format") != 1) throw ImportException("This isn't a file of undead skins for Wrath++.")
        val models = list.optJSONObject("models") ?: throw ImportException("The file has no skins in it.")
        val have = staged.associateBy { it.path }
        val lower = staged.associateBy { it.path.lowercase() }
        fun texture(gamePath: String): Staged {
            val name = prefix + "client/" + gamePath.replace('\\', '/')
            val s = have[name] ?: lower[name.lowercase()] ?: throw ImportException("The file names a texture it hasn't: $gamePath")
            if (UndeadSkins.size(s.file.readBytes()) == null) throw ImportException("$gamePath isn't a texture of the game's kind (BLP2).")
            return s
        }
        val out = JSONArray()
        var skinsIn = 0
        for (key in models.keys().asSequence().sorted()) {
            val m = models.getJSONObject(key)
            val skins = m.optJSONArray("skins") ?: continue
            if (skins.length() == 0) continue
            val raceId = m.getInt("race"); val sexN = m.getInt("sex")
            val sex = if (sexN == 1) Sex.FEMALE else Sex.MALE
            val head = UndeadSkins.head(raceId, sex)
            val outSkins = JSONArray()
            for (i in 0 until skins.length()) {
                val sk = skins.getJSONObject(i)
                val body = texture(sk.getString("skin"))
                val faceIn = texture(sk.getString("face"))
                // the face as the head's box wants it: turned back where undead_pack.py turned it
                val face = if (head?.rotate == true) {
                    val path = "turned/" + faceIn.path
                    val f = File(filesDir, path).apply { parentFile?.mkdirs() }
                    val turned = try { UndeadSkins.rotateBack(faceIn.file.readBytes()) } catch (e: IOException) { throw ImportException("${sk.getString("face")}: ${e.message}") }
                    f.writeBytes(turned)
                    Staged(path, f).also { staged.add(it) }
                } else faceIn
                val bodyId = ids.idFor(kind.key + "/" + body.path).also { stored.add(it) }
                val faceId = ids.idFor(kind.key + "/" + face.path).also { stored.add(it) }
                outSkins.put(JSONObject().put("name", sk.optString("name", "Undead ${i + 1}")).put("swatch", sk.optInt("swatch") and 0xFFFFFF)
                    .put("skin", bodyId).put("face", faceId).put("turned", head?.rotate == true))
                skinsIn++
            }
            out.put(JSONObject().put("key", key).put("race_id", raceId).put("sex", sexN).put("name", m.optString("name", key))
                .put("underwear", m.optString("underwear", "race")).put("head", head != null).put("skins", outSkins))
        }
        if (skinsIn == 0) throw ImportException("The file has no skins in it.")
        meta.put("models", out).put("files", stored.size).put("skins", skinsIn).put("build", list.optString("build"))
    }

    /**
     * The zip looked for by its name ([ImportKind.zipNames]; [found] is [ImportFinder]'s): taken in by itself when nothing is
     * imported, and again when the file there has changed since it was taken from there. Never after [remove], and never over a
     * zip picked by hand. Returns the new status, or null when it took nothing.
     */
    fun lookFor(kind: ImportKind, found: File?): ImportStatus? {
        if (found == null || !found.isFile || off(kind).exists()) return null
        val mark = "${found.length()} ${found.lastModified()}"
        val meta = loaded.metas[kind]
        if (meta != null && (meta.optString("mark").isEmpty() || meta.optString("mark") == mark)) return null
        return found.inputStream().use { importZip(kind, it, found.name, mark) }
    }

    /** Taken off the phone, and not looked for again until a zip is picked. */
    @Synchronized
    fun remove(kind: ImportKind) {
        for (id in storedIds(kind)) File(game, id.toString()).delete()
        dir(kind).deleteRecursively()
        imports.mkdirs()
        write(off(kind), "removed\n".toByteArray())
        loaded = load()
    }

    private fun storedIds(kind: ImportKind): Set<Int> {
        val a = loaded.metas[kind]?.optJSONArray("stored") ?: return emptySet()
        return (0 until a.length()).map { a.getInt(it) }.toSet()
    }

    // ---------------------------------------------------------------- the ids

    /** Synthetic ids by `<kind>/<path>`: stable (the hash of the key, probed past a taken one, recorded). */
    private class Ids(private val file: File, private val map: HashMap<String, Int>) {
        private val used = HashSet<Int>(map.values)
        fun peek(key: String): Int? = map[key]
        fun idFor(key: String): Int {
            map[key]?.let { return it }
            var h = 0x811c9dc5.toInt()
            for (c in key) h = (h xor c.code) * 16777619
            var id = FIRST_ID + Math.floorMod(h, SPAN)
            while (id in used) id = FIRST_ID + (id - FIRST_ID + 1) % SPAN
            used.add(id); map[key] = id
            return id
        }
        fun write() {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v)
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw IOException("The id list couldn't be saved.") }
        }
        companion object {
            fun read(file: File): Ids {
                val map = HashMap<String, Int>()
                if (file.isFile) runCatching { JSONObject(file.readText()) }.getOrNull()?.let { o -> for (k in o.keys()) map[k] = o.getInt(k) }
                return Ids(file, map)
            }
        }
    }

    private fun write(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw IOException("${file.path} couldn't be written") }
    }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Synthetic ids start here, above the game's own (they stay below Int.MAX_VALUE). */
        const val FIRST_ID = 2_000_000_000
        private const val SPAN = 147_000_000
        const val LIST = "wrathpp-body.json"
        const val UNDEAD_LIST = "manifest.json"

        /** The store the app opened last (the app opens one): what the creator asks whether the body options are in. */
        @Volatile var current: ImportStore? = null
    }
}
