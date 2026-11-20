package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.sims.Dbpf
import com.logie.wifecraft.sims.SimsFormatException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Which state a part shows: DEFAULT (what every NPC starts in), ON (switched by an action in a scene), OTHER (unknown). */
enum class PartState { DEFAULT, ON, OTHER }

/** A part's variant, read from its names: [size] (Small, Medium, Large), [cut] (Uncut, Cut, Retracted), [placement] (High, Low). */
class PartVariant(val size: String?, val cut: String?, val placement: String?) {
    val label: String get() = listOfNotNull(size, cut, placement).joinToString(" ").ifEmpty { "default" }
    override fun equals(other: Any?) = other is PartVariant && other.label == label
    override fun hashCode() = label.hashCode()

    companion object {
        private val SIZES = listOf("Small", "Medium", "Large", "XLarge", "XL")
        private val CUTS = listOf("Uncut", "Retracted", "Cut")
        private val PLACES = listOf("High", "Low")

        fun parse(name: String): PartVariant {
            val tokens = name.split('_', ' ', '-').filter { it.isNotEmpty() }
            fun pick(set: List<String>) = set.firstOrNull { s -> tokens.any { it.equals(s, ignoreCase = true) } }
            return PartVariant(pick(SIZES), pick(CUTS), pick(PLACES))
        }
    }
}

/**
 * One body part found in a package: a CAS part with meshes. [fullMesh] is its finest level of detail as the package
 * has it (a CAS bottom: the whole lower body); [mesh] the pieces that are the part itself ([PartMesh.extract]), or the
 * full mesh when nothing could be told apart. The diffuse is decoded on demand ([diffuse]: textures are large).
 */
class Part(
    val id: String,
    val name: String,
    val caspName: String,
    val state: PartState,
    val variant: PartVariant,
    val description: PartDescription,
    val caspKey: ResourceKey,
    val lod: Int,
    val fullMesh: SimsMesh?,
    val mesh: SimsMesh?,
    val extracted: Boolean,
    val diffuseKey: ResourceKey?,
    /** Other CAS parts drawing the same meshes in the same state (a mod's copies for the CAS menu). */
    val aliases: List<String>,
    val problems: List<String>,
    private val loader: (ResourceKey) -> ByteArray?,
) {
    val male: Boolean get() = description.isMale
    val adult: Boolean get() = description.isAdult

    /** The decoded diffuse, null when the part has none; throws [BodyPartFormatException] when it cannot be decoded. */
    fun diffuse(): RgbaImage? {
        val k = diffuseKey ?: return null
        val bytes = loader(k) ?: throw BodyPartFormatException("The texture $k is not in the imported packages.")
        return SimsTexture.decode(bytes, k.toString())
    }
}

/** A CAS part without a mesh (a skin overlay or detail texture), listed so the import says what it left aside. */
class Overlay(val name: String, val bodyType: Int, val texture: ResourceKey?)

class PartImport(
    val set: String,
    val packages: List<String>,
    val parts: List<Part>,
    val overlays: List<Overlay>,
    val problems: List<String>,
)

/** Caps for an import from the phone: a bad or hostile archive stops with a message instead of filling memory. */
class ImportLimits(
    val maxPackages: Int = 64,
    val maxPackageBytes: Long = 160L * 1024 * 1024,
    val maxTotalBytes: Long = 640L * 1024 * 1024,
    val maxDepth: Int = 6,
)

/**
 * Opens a body-part mod (one .package, a folder of them, or a .zip of them) and lists its [Part]s; [export] writes them
 * as part files. Pure Kotlin.
 *
 * States: from the mod's WickedWhims CAS-parts tuning when it has one (snippet tuning 0x7DF2169C listing
 * cas_part_id and cas_part_type: a type with SOFT is [PartState.DEFAULT], HARD is [PartState.ON]), else from the CAS
 * part's name (Soft or Default: DEFAULT; Hard: ON).
 */
object PartPackage {
    const val TUNING = 0x7DF2169C

    fun open(source: File, set: String? = null, limits: ImportLimits = ImportLimits()): PartImport {
        val name = set ?: source.name.substringBeforeLast('.')
        val pkgs = ArrayList<Pair<String, ByteArray>>()
        val problems = ArrayList<String>()
        var total = 0L
        fun add(label: String, bytes: ByteArray) {
            if (pkgs.size >= limits.maxPackages) throw BodyPartFormatException("The import holds more than ${limits.maxPackages} packages.")
            total += bytes.size
            if (total > limits.maxTotalBytes) throw BodyPartFormatException("The import is larger than ${limits.maxTotalBytes / (1024 * 1024)} MB.")
            pkgs += label to bytes
        }
        try {
            when {
                source.isDirectory -> {
                    val root = source.canonicalFile
                    root.walkTopDown().maxDepth(limits.maxDepth).filter { it.isFile && it.name.endsWith(".package", ignoreCase = true) }
                        .sortedBy { it.path }.forEach { f ->
                            val c = f.canonicalFile
                            if (!c.path.startsWith(root.path + File.separator)) { problems += "${f.name}: outside the folder (a link), skipped"; return@forEach }
                            if (c.length() > limits.maxPackageBytes) { problems += "${f.name}: larger than ${limits.maxPackageBytes / (1024 * 1024)} MB, skipped"; return@forEach }
                            add(c.relativeTo(root).path, c.readBytes())
                        }
                }
                isZip(source) -> source.inputStream().buffered().use { readZip(it, limits, problems, ::add) }
                else -> {
                    if (source.length() > limits.maxPackageBytes) throw BodyPartFormatException("${source.name} is larger than ${limits.maxPackageBytes / (1024 * 1024)} MB.")
                    add(source.name, source.readBytes())
                }
            }
        } catch (x: IOException) {
            throw BodyPartFormatException("${source.name} cannot be read (${x.message}).")
        }
        if (pkgs.isEmpty()) throw BodyPartFormatException("${source.name} holds no .package file.")
        val imp = fromPackages(name, pkgs)
        return PartImport(imp.set, imp.packages, imp.parts, imp.overlays, problems + imp.problems)
    }

    private fun isZip(f: File): Boolean = f.isFile && f.length() >= 4 && f.inputStream().use { s ->
        val h = ByteArray(4); s.read(h) == 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() == 3 && h[3].toInt() == 4
    }

    /** Reads the .package entries of a zip into memory; names are only labels (nothing is written), unsafe ones are still refused. */
    fun readZip(input: InputStream, limits: ImportLimits, problems: MutableList<String>, add: (String, ByteArray) -> Unit) {
        val z = ZipInputStream(input)
        val buf = ByteArray(65536)
        while (true) {
            val e = z.nextEntry ?: break
            val n = e.name.replace('\\', '/')
            if (e.isDirectory || !n.endsWith(".package", ignoreCase = true)) continue
            if (n.startsWith("/") || n.split('/').any { it == ".." } || n.contains(':')) { problems += "$n: unsafe path in the archive, skipped"; continue }
            val out = java.io.ByteArrayOutputStream()
            var size = 0L
            while (true) {
                val r = z.read(buf)
                if (r < 0) break
                size += r
                if (size > limits.maxPackageBytes) throw BodyPartFormatException("$n in the archive is larger than ${limits.maxPackageBytes / (1024 * 1024)} MB.")
                out.write(buf, 0, r)
            }
            add(n, out.toByteArray())
        }
    }

    /** The parts of [packages] (label, bytes); keys resolve across all of them (an add-on package uses the base's meshes). */
    fun fromPackages(set: String, packages: List<Pair<String, ByteArray>>): PartImport {
        val problems = ArrayList<String>()
        val dbpfs = ArrayList<Pair<String, Dbpf>>()
        for ((label, bytes) in packages) {
            try { dbpfs += label to Dbpf(bytes) } catch (x: SimsFormatException) { problems += "$label: ${x.message}" }
        }
        val exact = HashMap<ResourceKey, Pair<Dbpf, Dbpf.Entry>>()
        val loose = HashMap<Pair<Int, Long>, Pair<Dbpf, Dbpf.Entry>>()
        for ((_, d) in dbpfs) for (e in d.entries) {
            val k = ResourceKey(e.type, e.group, e.instance)
            exact[k] = d to e
            loose.putIfAbsent(e.type to e.instance, d to e)
        }
        fun load(k: ResourceKey): ByteArray? = (exact[k] ?: loose[k.type to k.instance])?.let { (d, e) -> d.read(e) }

        // the mod's own tuning: CAS part id -> (display name, type)
        val tuned = HashMap<Long, Pair<String, String>>()
        for ((_, d) in dbpfs) for (e in d.entries.filter { it.type == TUNING }) {
            runCatching { tuned.putAll(parseTuning(String(d.read(e), Charsets.UTF_8))) }.onFailure { problems += "tuning ${keyOf(e)}: ${it.message}" }
        }

        class Found(val key: ResourceKey, val d: PartDescription, val meshKeys: List<ResourceKey>)
        val found = ArrayList<Found>()
        val overlays = ArrayList<Overlay>()
        for ((label, d) in dbpfs) for (e in d.entries.filter { it.type == Casp.TYPE }) {
            val key = keyOf(e)
            val desc = try { Casp.parse(d.read(e), key.toString()) } catch (x: Exception) { problems += "$label CAS part $key: ${x.message}"; continue }
            val meshes = desc.finestMeshes()
            if (meshes.isEmpty()) overlays += Overlay(desc.name, desc.bodyType, desc.diffuse) else found += Found(key, desc, meshes)
        }

        // one part per (state, variant, meshes); the tuned CAS part names it, the others are its aliases
        val groups = LinkedHashMap<String, MutableList<Found>>()
        fun stateOf(f: Found): PartState = tuned[f.key.instance]?.second?.let { stateFromType(it) } ?: stateFromName(f.d.name)
        for (f in found) groups.getOrPut("${stateOf(f)}|${f.meshKeys.joinToString()}") { ArrayList() } += f
        val parts = ArrayList<Part>()
        for ((_, g) in groups) {
            val lead = g.sortedWith(compareBy<Found>({ if (tuned.containsKey(it.key.instance)) 0 else 1 }, { it.d.parmFlags }, { it.d.name })).first()
            val tune = tuned[lead.key.instance]
            val state = stateOf(lead)
            val variant = PartVariant.parse(tune?.first ?: lead.d.name)
            val pp = ArrayList<String>()
            if (!lead.d.isMale) pp += "not marked male (age/gender 0x%X)".format(lead.d.ageGender)
            var full: SimsMesh? = null
            try {
                val meshes = lead.meshKeys.map { k -> Geom.parse(load(k) ?: throw BodyPartFormatException("The mesh $k is not in the imported packages."), k.toString()) }
                full = if (meshes.size == 1) meshes[0] else merge(meshes)
            } catch (x: BodyPartFormatException) { pp += x.message ?: "mesh unreadable" }
            val part = full?.let { PartMesh.extract(it) }
            if (full != null && part == null) pp += "no pieces weighted to the part's own bones: the whole mesh is kept"
            val display = tune?.first ?: lead.d.name.replace(Regex("_\\d{12,}(_CAS)?$"), "")
            parts += Part("%016X".format(lead.key.instance), display, lead.d.name, state, variant, lead.d, lead.key, lead.d.finestLevel(), full,
                part ?: full, part != null, lead.d.diffuse, g.filter { it !== lead }.map { it.d.name }, pp, ::load)
        }
        parts.sortWith(compareBy({ it.state }, { it.variant.label }, { it.name }))
        return PartImport(set, packages.map { it.first }, parts, overlays, problems)
    }

    private fun keyOf(e: Dbpf.Entry) = ResourceKey(e.type, e.group, e.instance)

    fun stateFromType(type: String): PartState = when {
        type.contains("SOFT", ignoreCase = true) -> PartState.DEFAULT
        type.contains("HARD", ignoreCase = true) -> PartState.ON
        else -> PartState.OTHER
    }

    fun stateFromName(name: String): PartState {
        val t = name.split('_', ' ', '-').map { it.lowercase() }
        return when {
            "hard" in t -> PartState.ON
            "soft" in t || t.any { it.endsWith("default") } -> PartState.DEFAULT
            else -> PartState.OTHER
        }
    }

    /** WickedWhims CAS-parts tuning: each entry of cas_parts_list as id -> (cas_part_raw_display_name, cas_part_type). */
    fun parseTuning(xml: String): Map<Long, Pair<String, String>> {
        if (!xml.contains("cas_parts_list")) return emptyMap()
        val out = HashMap<Long, Pair<String, String>>()
        val entry = Regex("<T>\\s*((?:<T n=\"[^\"]+\">[^<]*</T>\\s*)+)</T>")
        val field = Regex("<T n=\"([^\"]+)\">([^<]*)</T>")
        for (m in entry.findAll(xml)) {
            val f = field.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2].trim() }
            val id = f["cas_part_id"]?.toULongOrNull()?.toLong() ?: continue
            out[id] = (f["cas_part_raw_display_name"] ?: "") to (f["cas_part_type"] ?: "")
        }
        return out
    }

    /** Several meshes as one (bone tables joined). */
    fun merge(meshes: List<SimsMesh>): SimsMesh {
        val hashes = ArrayList<Int>()
        for (m in meshes) for (h in m.boneHashes) if (h !in hashes) hashes += h
        val n = meshes.sumOf { it.vertexCount }
        val pos = FloatArray(n * 3); val uv = FloatArray(n * 2); val nrm = FloatArray(n * 3)
        val bi = ByteArray(n * 4); val wt = FloatArray(n * 4)
        val idx = ArrayList<Int>()
        var base = 0
        for (m in meshes) {
            System.arraycopy(m.positions, 0, pos, base * 3, m.vertexCount * 3)
            m.uvs?.let { System.arraycopy(it, 0, uv, base * 2, m.vertexCount * 2) }
            m.normals?.let { System.arraycopy(it, 0, nrm, base * 3, m.vertexCount * 3) }
            if (m.boneIndices != null && m.weights != null) for (v in 0 until m.vertexCount) for (k in 0..3) {
                val h = m.boneHashes.getOrNull(m.boneIndices[v * 4 + k].toInt() and 0xFF)
                bi[(base + v) * 4 + k] = (if (h == null) 0 else hashes.indexOf(h)).toByte()
                wt[(base + v) * 4 + k] = if (h == null) 0f else m.weights[v * 4 + k]
            }
            for (i in m.indices) idx += base + i
            base += m.vertexCount
        }
        if (hashes.size > 255) throw BodyPartFormatException("The merged meshes use ${hashes.size} bones.")
        return SimsMesh(meshes[0].version, meshes[0].shaderHash, n, pos, nrm, uv, 1, null, bi, wt, null, idx.toIntArray(), hashes.toIntArray(), emptyList(),
            meshes.flatMap { it.references }, meshes.flatMap { it.notes }.distinct())
    }

    /** A file or folder name made only of letters, digits, '-' and '_' (at most 64), never empty. */
    fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(64).ifEmpty { "set" }

    /**
     * The texels a mesh uses, cropped out of a shared atlas (padded by [pad], kept a multiple of 4) and the UVs moved to
     * the crop: a 1024x2048 body atlas shrinks to the part's own corner.
     */
    fun cropToUvs(img: RgbaImage, uvs: FloatArray, pad: Int = 4): Pair<RgbaImage, FloatArray> {
        if (uvs.isEmpty()) return img to uvs
        var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
        for (i in 0 until uvs.size / 2) { u0 = minOf(u0, uvs[i * 2]); u1 = maxOf(u1, uvs[i * 2]); v0 = minOf(v0, uvs[i * 2 + 1]); v1 = maxOf(v1, uvs[i * 2 + 1]) }
        val x0 = ((u0 * img.width).toInt() - pad).coerceIn(0, img.width - 1)
        val y0 = ((v0 * img.height).toInt() - pad).coerceIn(0, img.height - 1)
        var x1 = (Math.ceil((u1 * img.width).toDouble()).toInt() + pad).coerceIn(x0 + 1, img.width)
        var y1 = (Math.ceil((v1 * img.height).toDouble()).toInt() + pad).coerceIn(y0 + 1, img.height)
        x1 = minOf(img.width, x0 + ((x1 - x0 + 3) / 4) * 4); y1 = minOf(img.height, y0 + ((y1 - y0 + 3) / 4) * 4)
        val w = x1 - x0; val h = y1 - y0
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) System.arraycopy(img.rgba, ((y0 + y) * img.width + x0) * 4, px, y * w * 4, w * 4)
        val out = FloatArray(uvs.size)
        for (i in 0 until uvs.size / 2) {
            out[i * 2] = (uvs[i * 2] * img.width - x0) / w
            out[i * 2 + 1] = (uvs[i * 2 + 1] * img.height - y0) / h
        }
        return RgbaImage(w, h, px) to out
    }

    /** What [export] wrote: the set's folder, its files, and what went wrong per part. */
    class Export(val dir: File, val files: List<File>, val problems: List<String>)

    /**
     * Writes [imp]'s parts to [root]/<set>/ (on the phone root is files/bodyparts): per part <id>.wcbp ([PartFile]) and
     * <id>.png (its diffuse cropped to what it uses, uncoloured: the scene recolours it per NPC with [Recolor]), then
     * parts.jsonl, one line per part. Only male parts with a mesh are written unless [maleOnly] is false.
     */
    fun export(imp: PartImport, spec: AttachSpec, root: File, maleOnly: Boolean = true): Export {
        val r = root.canonicalFile
        val dir = File(r, safeName(imp.set)).canonicalFile
        if (dir.parentFile != r) throw BodyPartFormatException("The set name ${imp.set} does not make a folder inside ${r.path}.")
        dir.mkdirs()
        val files = ArrayList<File>()
        val problems = ArrayList<String>()
        val lines = ArrayList<String>()
        for (p in imp.parts) {
            if (maleOnly && !p.male) continue
            val mesh = p.mesh ?: continue
            val pp = ArrayList(p.problems)
            var tex: RgbaImage? = null
            var uvs = mesh.uvs
            try {
                val d = p.diffuse()
                if (d != null && uvs != null) { val (c, u) = cropToUvs(d, uvs); tex = c; uvs = u } else tex = d
            } catch (x: BodyPartFormatException) { pp += x.message ?: "texture unreadable" }
            val m = if (uvs !== mesh.uvs) SimsMesh(mesh.version, mesh.shaderHash, mesh.vertexCount, mesh.positions, mesh.normals, uvs, mesh.uvSets, mesh.tangents,
                mesh.boneIndices, mesh.weights, mesh.vertexIds, mesh.indices, mesh.boneHashes, mesh.states, mesh.references, mesh.notes) else mesh
            val conv = PartMesh.convert(m, spec, p.id, p.state.name, p.variant.label, tex)
            val id = safeName(p.id)
            val f = File(dir, "$id.wcbp").also { it.writeBytes(PartFile.write(conv)) }
            files += f
            var texName: String? = null
            if (tex != null) { texName = "$id.png"; files += File(dir, texName).also { it.writeBytes(Png.encode(tex)) } }
            lines += PartFile.manifestLine(conv, p.name, f.name, texName, pp)
            problems += pp.map { "${p.name}: $it" }
        }
        files += File(dir, "parts.jsonl").also { it.writeText(lines.joinToString("\n", postfix = "\n")) }
        return Export(dir, files, problems)
    }
}
