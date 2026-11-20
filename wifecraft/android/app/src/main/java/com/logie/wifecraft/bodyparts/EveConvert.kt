package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.sims.Dbpf
import com.logie.wifecraft.sims.DbpfFile
import com.logie.wifecraft.sims.SimsFormatException
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * Converts the user's Eve body packages (picked on the phone: .package files, folders or .zip archives of them) into our own
 * Eve source set, written to [convert]'s out folder (on the phone files/bodyparts/eve/), on a background thread. Pure Kotlin.
 *
 * What it finds, by what the packages hold (not by their names, but where noted):
 * - the body: the CAS part with a mesh of body type 6 (upper body; the one named "top" first) and each one of body type 7
 *   (lower body), one Eve mesh per lower body: `plain`, `animated` (its name or package says so), `fairy` (its package
 *   says so), `default` (a defaults package's), any other `lower2`, `lower3`... Each is the upper and lower mesh merged at
 *   their finest level ([EveFile]: positions, normals, both texture sets, four weights by Warcraft bone name through
 *   [EveProfile.wowBone], the rings, the Sims joints).
 * - the skins: every CAS part without a mesh whose texture covers Eve's body (85% or more of its vertices land on opaque
 *   texels); a nearly grey one is a neutral skin (the brightest is kept as `skin_neutral.png`, tinted to each NPC's skin by
 *   the engine), the others are skin tones (`tone_NN.png`, the table's mean colours: what the menus offer). A texture that
 *   covers only part of the body (a face's freckles, creases) is left out.
 * - the overlays: every CAS part without a mesh whose texture covers less than half the body but some of it: each
 *   package of them a slot (named after the package's last word: "...overlay-acne" is `acne`), each of its parts a
 *   shade (`ov_<slot>_NN.png`, cropped to the texels it paints, its rectangle in the body texture, the swatch it was made
 *   for and its own mean colour).
 * Every texture is cropped to the rows of the Sims body atlas Eve uses and stored 1024 pixels wide (a 2048 x 4096 atlas
 * halved), Eve's own CAS textures (its upper and lower body's diffuse: a detail layer) laid over each skin.
 *
 * Memory: one texture decoded at a time (a 2048 x 4096 atlas is 32 MB as pixels) and only the resource being read held
 * from a package (packages are read from disk, a zip's packages unpacked to [out]/.tmp one by one and deleted after),
 * so the peak stays under about 100 MB however many packages are given.
 */
object EveConvert {
    const val WIDTH = 1024

    class Limits(
        val maxPackages: Int = 64,
        val maxPackageBytes: Long = 400L * 1024 * 1024,
        val maxTones: Int = 64,
        val maxShades: Int = 200,
    )

    class Result(val dir: File, val index: EveSetIndex, val problems: List<String>, val decoded: Int, val peakHeapBytes: Long)

    private class Pkg(val label: String, val file: DbpfFile)
    private class Res(val pkg: Pkg, val entry: Dbpf.Entry) { fun bytes(): ByteArray = pkg.file.read(entry) }
    private class Cas(val res: Res, val desc: PartDescription)

    /**
     * Converts [sources] into [out] (made empty first: its old set is replaced). [profileJson]: assets/sims_profile.json.
     * [onProgress] is told how far it is (done, total, what). Throws [BodyPartFormatException] with a message for the user
     * when no Eve body is found or a file can't be read.
     */
    fun convert(
        sources: List<File>,
        out: File,
        profileJson: String,
        limits: Limits = Limits(),
        onProgress: (done: Int, total: Int, what: String) -> Unit = { _, _, _ -> },
    ): Result {
        val problems = ArrayList<String>()
        out.mkdirs()
        out.listFiles()?.forEach { if (it.isFile && (it.name.endsWith(".png") || it.name.endsWith(".weve") || it.name == "set.json")) it.delete() }
        val tmp = File(out, ".tmp")
        val pkgs = ArrayList<Pkg>()
        var peak = 0L
        fun sample() { val rt = Runtime.getRuntime(); peak = maxOf(peak, rt.totalMemory() - rt.freeMemory()) }
        try {
            openAll(sources, tmp, limits, pkgs, problems)
            if (pkgs.isEmpty()) throw BodyPartFormatException("None of the files picked is a Sims 4 package.")
            // ---- the index: resources by (type, instance); a resource in two packages: the larger (a 2048 texture over a 1024 one)
            val byKey = HashMap<Pair<Int, Long>, Res>()
            val casps = ArrayList<Cas>()
            for (p in pkgs) for (e in p.file.entries) {
                val k = e.type to e.instance
                val had = byKey[k]
                if (had == null || (e.memSize.toLong() and 0xFFFFFFFFL) > (had.entry.memSize.toLong() and 0xFFFFFFFFL)) byKey[k] = Res(p, e)
                if (e.type == Casp.TYPE) {
                    try { casps += Cas(Res(p, e), Casp.parse(p.file.read(e), e.key)) } catch (x: Exception) { problems += "${p.label} CAS part ${e.key}: ${x.message}" }
                }
            }
            fun load(k: ResourceKey): ByteArray? = byKey[k.type to k.instance]?.bytes()
            // ---- the body
            val withMesh = casps.filter { it.desc.finestMeshes().isNotEmpty() }
            val top = withMesh.filter { it.desc.bodyType == 6 }.sortedWith(compareBy(
                { if (it.desc.name.contains("top", true)) 0 else 1 }, { if (it.res.pkg.label.contains("default", true)) 1 else 0 }, { it.desc.name })).firstOrNull()
                ?: throw BodyPartFormatException("No Eve upper body (a CAS top with a mesh) is in the files picked: pick Eve's TOP package too.")
            val bottoms = withMesh.filter { it.desc.bodyType == 7 }
            if (bottoms.isEmpty()) throw BodyPartFormatException("No Eve lower body (a CAS bottom with a mesh) is in the files picked: pick one of Eve's BOTTOM packages too.")
            fun mesh(c: Cas): SimsMesh {
                val ms = c.desc.finestMeshes().map { k -> Geom.parse(load(k) ?: throw BodyPartFormatException("The mesh $k of ${c.desc.name} is not in the files picked."), k.toString()) }
                return if (ms.size == 1) ms[0] else mergeMeshes(ms)
            }
            val topMesh = mesh(top)
            val variants = LinkedHashMap<String, Pair<Cas, SimsMesh>>()
            for (b in bottoms.sortedBy { variantOf(it.desc.name, it.res.pkg.label) }) {
                var v = variantOf(b.desc.name, b.res.pkg.label)
                if (v in variants) { var i = 2; while ("lower$i" in variants) i++; v = "lower$i" }
                variants[v] = b to mesh(b)
            }
            // ---- the crop: the rows of the atlas every mesh uses, padded, on a 1/512 grid (whole rows of a 2048 or 4096 atlas)
            var vmin = 1f; var vmax = 0f
            for (m in listOf(topMesh) + variants.values.map { it.second }) m.uvs?.let { uv -> for (i in 0 until m.vertexCount) { vmin = minOf(vmin, uv[i * 2 + 1]); vmax = maxOf(vmax, uv[i * 2 + 1]) } }
            if (vmax <= vmin) { vmin = 0f; vmax = 1f }
            val cropV0 = (floor((vmin - 4f / 2048f) * 512f) / 512f).coerceIn(0f, 1f)
            val cropV1 = (ceil((vmax + 4f / 2048f) * 512f) / 512f).coerceIn(cropV0 + 1f / 512f, 1f)
            val height = Math.round((cropV1 - cropV0) * 2048f)
            val profile = EveProfile.profileBones(profileJson)
            val meshes = LinkedHashMap<String, EveMesh>()
            for ((v, pair) in variants) meshes[v] = buildMesh(topMesh, pair.second, v, profile, cropV0, cropV1, height, problems)
            val probe = meshes.values.first()
            // ---- the textures: Eve's own detail layers first, then every skin and overlay candidate
            val details = ArrayList<RgbaImage>()
            val firstBottom = variants.values.first().first
            val textured = casps.filter { it.desc.finestMeshes().isEmpty() && it.desc.diffuse != null }
            val byTexture = LinkedHashMap<Long, MutableList<Cas>>()
            for (c in textured) byTexture.getOrPut(c.desc.diffuse!!.instance) { ArrayList() } += c
            val total = 2 + byTexture.size
            var done = 0
            for (c in listOf(top, firstBottom)) {
                onProgress(done++, total, "Eve's own texture (${c.desc.name})")
                val k = c.desc.diffuse ?: continue
                try { load(k)?.let { details += decodeBody(it, k.toString(), cropV0, cropV1, height) } }
                catch (x: Exception) { problems += "Eve's texture $k: ${x.message}" }
                sample()
            }
            var neutral: Pair<EveSetIndex.Texture, Float>? = null
            val tones = ArrayList<EveSetIndex.Texture>()
            val slots = LinkedHashMap<String, MutableList<EveSetIndex.Shade>>()
            val skinPackages = HashSet<String>()      // packages that gave a full skin: their partial textures are skin pieces
            val slotPackage = HashMap<String, String>()
            for ((instance, group) in byTexture) {
                val lead = group.sortedBy { it.desc.name }.first()
                onProgress(done++, total, lead.desc.name)
                val k = lead.desc.diffuse!!
                val raw = byKey[k.type to instance] ?: continue
                if (!SimsTexture.isTexture(raw.entry.type)) continue
                val img = try { decodeBody(raw.bytes(), k.toString(), cropV0, cropV1, height) } catch (x: Exception) { problems += "${lead.desc.name}: ${x.message}"; continue }
                sample()
                val cov = coverage(img, probe)
                val id = "%016X".format(instance)
                val swatch = lead.desc.swatchColors.firstOrNull()?.and(0xFFFFFF) ?: 0
                when {
                    cov >= 0.85f -> {
                        skinPackages += raw.pkg.label
                        for (d in details) over(img, d)
                        val mean = meanAt(img, probe, null)
                        val neck = meanAt(img, probe, nearRing(probe, "neck"))
                        val ankle = meanAt(img, probe, nearRing(probe, "L_ankle", "R_ankle"))
                        val grey = greyness(img, probe) < 6f
                        if (grey) {
                            val lum = luminance(mean)
                            if (neutral == null || lum > neutral.second) {
                                File(out, "skin_neutral.png").writeBytes(Png.encode(img))
                                neutral = EveSetIndex.Texture(id, lead.desc.name, "skin_neutral.png", swatch, mean, neck, ankle, 0f) to lum
                            }
                        } else if (tones.size < limits.maxTones) {
                            val file = "tone_%02d.png".format(tones.size)
                            File(out, file).writeBytes(Png.encode(img))
                            tones += EveSetIndex.Texture(id, lead.desc.name, file, swatch, mean, neck, ankle, 0f)
                        }
                    }
                    cov in 0.0001f..0.5f -> {
                        val slot = slotOf(raw.pkg.label)
                        slotPackage[slot] = raw.pkg.label
                        val list = slots.getOrPut(slot) { ArrayList() }
                        if (slots.values.sumOf { it.size } > limits.maxShades) continue
                        val rect = alphaBox(img) ?: continue
                        val crop = cropImage(img, rect)
                        val file = "ov_%s_%02d.png".format(slot, list.size)
                        File(out, file).writeBytes(Png.encode(crop))
                        list += EveSetIndex.Shade(id, file, swatch, meanAlpha(crop), rect)
                    }
                    else -> {}   // a piece of the body only (a face's freckles, creases): not a skin
                }
            }
            // a package with full skins is a skin set: its partial textures (a face's freckles, creases) are not overlays
            for (slot in slots.keys.toList()) if (slotPackage[slot] in skinPackages) {
                slots.remove(slot)?.forEach { File(out, it.file).delete() }
            }
            onProgress(total, total, "writing the set")
            if (neutral == null && tones.isEmpty()) throw BodyPartFormatException("No skin for the Eve body is in the files picked (a full-body skin in Eve's layout): pick a skin package too.")
            val meshFiles = LinkedHashMap<String, String>()
            for ((v, m) in meshes) { val f = "eve_$v.weve"; File(out, f).writeBytes(EveFile.write(m)); meshFiles[v] = f }
            val notes = ArrayList<String>()
            notes += "converted from ${pkgs.size} packages; ${meshes.size} lower bodies (${meshes.keys.joinToString()}), ${tones.size} skin tones, ${slots.size} overlay slots"
            for ((v, m) in meshes) notes += "$v: ${m.vertexCount} vertices, ${m.triangleCount} triangles, rings " + m.rings.entries.joinToString { "${it.key} ${it.value.size}" }
            val index = EveSetIndex(meshFiles, WIDTH, height, cropV0, cropV1, neutral?.first, tones, slots.map { EveSetIndex.OverlaySlot(it.key, it.value) },
                pkgs.map { it.label }, notes + problems.take(20))
            File(out, "set.json").writeText(index.toJson())
            return Result(out, index, problems, done, peak)
        } catch (x: SimsFormatException) {
            throw BodyPartFormatException(x.message ?: "A package can't be read.")
        } catch (x: IOException) {
            throw BodyPartFormatException("The Eve files can't be read (${x.message}).")
        } finally {
            pkgs.forEach { runCatching { it.file.close() } }
            tmp.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------------------------------------ the packages

    private fun openAll(sources: List<File>, tmp: File, limits: Limits, out: MutableList<Pkg>, problems: MutableList<String>) {
        fun add(label: String, f: File) {
            if (out.size >= limits.maxPackages) throw BodyPartFormatException("More than ${limits.maxPackages} packages were picked.")
            if (f.length() > limits.maxPackageBytes) { problems += "$label: larger than ${limits.maxPackageBytes / (1024 * 1024)} MB, skipped"; return }
            try { out += Pkg(label, DbpfFile(f)) } catch (x: SimsFormatException) { problems += "$label: ${x.message}" }
        }
        for (s in sources) {
            when {
                s.isDirectory -> s.walkTopDown().maxDepth(6).filter { it.isFile && it.name.endsWith(".package", true) }.sortedBy { it.path }.forEach { add(it.name, it) }
                isZip(s) -> {
                    tmp.mkdirs()
                    ZipInputStream(s.inputStream().buffered()).use { z ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val e = z.nextEntry ?: break
                            val n = e.name.replace('\\', '/')
                            if (e.isDirectory || !n.endsWith(".package", true)) continue
                            val f = File(tmp, "${out.size}.package")
                            var size = 0L
                            f.outputStream().buffered().use { o ->
                                while (true) {
                                    val r = z.read(buf)
                                    if (r < 0) break
                                    size += r
                                    if (size > limits.maxPackageBytes) throw BodyPartFormatException("$n in ${s.name} is larger than ${limits.maxPackageBytes / (1024 * 1024)} MB.")
                                    o.write(buf, 0, r)
                                }
                            }
                            add(n.substringAfterLast('/'), f)
                        }
                    }
                }
                else -> add(s.name, s)
            }
        }
    }

    private fun isZip(f: File): Boolean = f.isFile && f.length() >= 4 && f.inputStream().use { s ->
        val h = ByteArray(4); s.read(h) == 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() == 3 && h[3].toInt() == 4
    }

    /** A lower body's variant name, from its CAS part's name and its package's. */
    fun variantOf(name: String, label: String): String {
        val n = name.lowercase(); val l = label.lowercase()
        return when {
            "fairy" in l || "fairy" in n -> "fairy"
            "nonanimated" in n -> "plain"
            "animated" in l || "animated" in n -> "animated"
            "default" in l || "default" in n -> "default"
            else -> "plain"
        }
    }

    /** An overlay package's slot: its name's last word ("SMSims-juicyboobsoverlay-acne.package": acne). */
    fun slotOf(label: String): String {
        val base = label.substringAfterLast('/').substringBeforeLast('.')
        val word = base.split('-', '_', ' ').lastOrNull { it.isNotBlank() } ?: base
        return PartPackage.safeName(word.lowercase()).take(24).ifEmpty { "overlay" }
    }

    // ------------------------------------------------------------------------------------------------ the mesh

    private fun mergeMeshes(ms: List<SimsMesh>): SimsMesh {
        val merged = PartPackage.merge(ms)
        val uv2 = if (ms.all { it.uvs2 != null }) FloatArray(merged.vertexCount * 2).also { o -> var b = 0; for (m in ms) { System.arraycopy(m.uvs2!!, 0, o, b * 2, m.vertexCount * 2); b += m.vertexCount } } else null
        return SimsMesh(merged.version, merged.shaderHash, merged.vertexCount, merged.positions, merged.normals, merged.uvs, merged.uvSets, merged.tangents,
            merged.boneIndices, merged.weights, merged.vertexIds, merged.indices, merged.boneHashes, merged.states, merged.references, merged.notes, uv2)
    }

    /** [top] and [bottom] as one Eve mesh (Warcraft axes, Sims metres; weights by Warcraft bone name; the rings found). */
    fun buildMesh(top: SimsMesh, bottom: SimsMesh, variant: String, profile: Map<String, String>, cropV0: Float, cropV1: Float, height: Int,
                  problems: MutableList<String> = ArrayList()): EveMesh {
        val parts = listOf(top, bottom)
        val n = parts.sumOf { it.vertexCount }
        val pos = FloatArray(n * 3); val nrm = FloatArray(n * 3); val uv = FloatArray(n * 2); val uv2 = FloatArray(n * 2)
        val bi = ByteArray(n * 4); val bw = ByteArray(n * 4)
        val idx = ArrayList<Int>()
        val bones = ArrayList<String>()
        val unknown = HashSet<Int>()
        var base = 0
        for (m in parts) {
            val names = EveProfile.namesOf(m.boneHashes)
            for (v in 0 until m.vertexCount) {
                val o = base + v
                EveProfile.toWow(m.positions[v * 3], m.positions[v * 3 + 1], m.positions[v * 3 + 2], pos, o * 3)
                m.normals?.let { EveProfile.toWow(it[v * 3], it[v * 3 + 1], it[v * 3 + 2], nrm, o * 3) }
                m.uvs?.let { uv[o * 2] = it[v * 2]; uv[o * 2 + 1] = (it[v * 2 + 1] - cropV0) / (cropV1 - cropV0) }
                m.uvs2?.let { uv2[o * 2] = it[v * 2]; uv2[o * 2 + 1] = it[v * 2 + 1] }
                val acc = LinkedHashMap<String, Float>()
                val bix = m.boneIndices; val wts = m.weights
                if (bix != null && wts != null) for (k in 0..3) {
                    val w = wts[v * 4 + k]
                    if (w <= 0f) continue
                    val hi = bix[v * 4 + k].toInt() and 0xFF
                    val short = names.getOrNull(hi)
                    if (short == null) unknown += m.boneHashes.getOrElse(hi) { 0 }
                    val wow = if (short == null) "Pelvis" else EveProfile.wowBone(short, profile)
                    acc[wow] = (acc[wow] ?: 0f) + w
                }
                if (acc.isEmpty()) acc["Pelvis"] = 1f
                val top4 = acc.entries.sortedByDescending { it.value }.take(4)
                val tot = top4.sumOf { it.value.toDouble() }.toFloat()
                val q = IntArray(4)
                for ((k, e) in top4.withIndex()) q[k] = Math.round(e.value / tot * 255f)
                q[0] += 255 - q.sum()
                for ((k, e) in top4.withIndex()) {
                    var b = bones.indexOf(e.key)
                    if (b < 0) { bones += e.key; b = bones.size - 1 }
                    bi[o * 4 + k] = b.toByte(); bw[o * 4 + k] = q[k].toByte()
                }
            }
            for (i in m.indices) idx += base + i
            base += m.vertexCount
        }
        if (bones.size > 255) throw BodyPartFormatException("The Eve mesh uses ${bones.size} bones.")
        if (unknown.isNotEmpty()) problems += "$variant: ${unknown.size} bones not in the Sims rig's body (a mod's own) folded into the pelvis"
        val rings = findRings(pos, idx.toIntArray())
        if (rings["neck"].let { it == null || it.isEmpty() }) problems += "$variant: no open neck found (the head and the body may not meet cleanly)"
        return EveMesh(variant, bones, EveProfile.JOINTS, rings, pos, nrm, uv, uv2, bi, bw, idx.toIntArray(), WIDTH, height, cropV0, cropV1)
    }

    /**
     * The open rings (positions welded at 0.1 mm, edges used by one triangle only): the neck's (above 1.4 m) and each ankle's
     * (under 0.4 m, left +y, right -y); every vertex at a ring's places is listed (texture seams double them).
     */
    fun findRings(pos: FloatArray, idx: IntArray): Map<String, IntArray> {
        val n = pos.size / 3
        val weld = HashMap<Triple<Long, Long, Long>, Int>()
        val wid = IntArray(n) { v ->
            val k = Triple(Math.round(pos[v * 3] / 1e-4), Math.round(pos[v * 3 + 1] / 1e-4), Math.round(pos[v * 3 + 2] / 1e-4))
            weld.getOrPut(k) { weld.size }
        }
        val count = HashMap<Long, Int>()
        for (t in 0 until idx.size / 3) for (k in 0..2) {
            val a = wid[idx[t * 3 + k]]; val b = wid[idx[t * 3 + (k + 1) % 3]]
            if (a == b) continue
            val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
            count[key] = (count[key] ?: 0) + 1
        }
        val open = HashSet<Int>()
        for ((k, c) in count) if (c == 1) { open += (k ushr 32).toInt(); open += (k and 0xFFFFFFFFL).toInt() }
        val out = LinkedHashMap<String, MutableList<Int>>()
        for (name in listOf("neck", "L_ankle", "R_ankle")) out[name] = ArrayList()
        for (v in 0 until n) {
            if (wid[v] !in open) continue
            val y = pos[v * 3 + 1]; val z = pos[v * 3 + 2]
            when {
                z > 1.4f -> out["neck"]!! += v
                z < 0.4f && y > 0 -> out["L_ankle"]!! += v
                z < 0.4f && y < 0 -> out["R_ankle"]!! += v
            }
        }
        return out.mapValues { it.value.toIntArray() }
    }

    // ------------------------------------------------------------------------------------------------ the textures

    private val toLin = FloatArray(256) { i -> val c = i / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat() }
    private fun toSrgb(c: Float): Int { val s = if (c <= 0.0031308f) c * 12.92f else (1.055f * c.toDouble().pow(1 / 2.4).toFloat() - 0.055f); return (s.coerceIn(0f, 1f) * 255f + 0.5f).toInt() }

    /**
     * A Sims texture (RLE2, RLES, LRLE, DDS) decoded straight into the body crop ([toBody]'s result) as its 4x4 tiles stream
     * in: only eight of its rows are held at a time, never the whole atlas.
     */
    fun decodeBody(bytes: ByteArray, key: String, v0: Float, v1: Float, height: Int): RgbaImage {
        val out = ByteArray(WIDTH * height * 4)
        var w = 0; var h = 0; var tilesX = 0
        var band = ByteArray(0)
        var y0 = 0f; var sy = 1f; var sx = 1f; var bx = 1; var by = 1
        var next = 0
        fun row(y: Int) {
            val ys = (y0 + y * sy).toInt().coerceIn(0, h - 1)
            for (x in 0 until WIDTH) {
                val xs = (x * sx).toInt().coerceIn(0, w - 1)
                var r = 0; var g = 0; var b = 0; var a = 0; var c = 0
                for (dy in 0 until by) for (dx in 0 until bx) {
                    val yy = minOf(h - 1, ys + dy); val xx = minOf(w - 1, xs + dx)
                    val i = ((yy % 8) * w + xx) * 4
                    r += band[i].toInt() and 0xFF; g += band[i + 1].toInt() and 0xFF; b += band[i + 2].toInt() and 0xFF; a += band[i + 3].toInt() and 0xFF; c++
                }
                val o = (y * WIDTH + x) * 4
                out[o] = (r / c).toByte(); out[o + 1] = (g / c).toByte(); out[o + 2] = (b / c).toByte(); out[o + 3] = (a / c).toByte()
            }
        }
        SimsTexture.decodeTiles(bytes, key, { ww, hh ->
            w = ww; h = hh; tilesX = (ww + 3) / 4; band = ByteArray(8 * ww * 4)
            y0 = v0 * hh; sy = (v1 - v0) * hh / height; sx = ww.toFloat() / WIDTH
            bx = maxOf(1, sx.toInt()); by = maxOf(1, minOf(4, sy.toInt()))
        }) { tx, ty, tile ->
            for (py in 0..3) {
                val y = ty * 4 + py
                if (y >= h) break
                for (px in 0..3) { val x = tx * 4 + px; if (x < w) System.arraycopy(tile, (py * 4 + px) * 4, band, ((y % 8) * w + x) * 4, 4) }
            }
            if (tx == tilesX - 1) {
                val done = minOf(h, ty * 4 + 4)
                while (next < height) {
                    val ys = (y0 + next * sy).toInt().coerceIn(0, h - 1)
                    if (minOf(h - 1, ys + by - 1) >= done) break
                    row(next++)
                }
            }
        }
        return RgbaImage(WIDTH, height, out)
    }

    /** [img] (a Sims body atlas, any size) cropped to the rows [v0]..[v1] and scaled to [WIDTH] x [height] (box filter). */
    fun toBody(img: RgbaImage, v0: Float, v1: Float, height: Int): RgbaImage {
        val out = ByteArray(WIDTH * height * 4)
        val y0 = v0 * img.height; val sy = (v1 - v0) * img.height / height; val sx = img.width.toFloat() / WIDTH
        val bx = maxOf(1, sx.toInt()); val by = maxOf(1, sy.toInt())
        for (y in 0 until height) {
            val ys = (y0 + y * sy).toInt().coerceIn(0, img.height - 1)
            for (x in 0 until WIDTH) {
                val xs = (x * sx).toInt().coerceIn(0, img.width - 1)
                var r = 0; var g = 0; var b = 0; var a = 0; var c = 0
                for (dy in 0 until by) for (dx in 0 until bx) {
                    val yy = minOf(img.height - 1, ys + dy); val xx = minOf(img.width - 1, xs + dx)
                    val i = (yy * img.width + xx) * 4
                    r += img.rgba[i].toInt() and 0xFF; g += img.rgba[i + 1].toInt() and 0xFF; b += img.rgba[i + 2].toInt() and 0xFF; a += img.rgba[i + 3].toInt() and 0xFF; c++
                }
                val o = (y * WIDTH + x) * 4
                out[o] = (r / c).toByte(); out[o + 1] = (g / c).toByte(); out[o + 2] = (b / c).toByte(); out[o + 3] = (a / c).toByte()
            }
        }
        return RgbaImage(WIDTH, height, out)
    }

    /** [top] laid over [base] by its alpha (straight), in place; [base] stays opaque. */
    fun over(base: RgbaImage, top: RgbaImage) {
        if (base.width != top.width || base.height != top.height) return
        for (i in 0 until base.width * base.height) {
            val a = (top.rgba[i * 4 + 3].toInt() and 0xFF) / 255f
            if (a <= 0f) continue
            for (c in 0..2) {
                val v = (base.rgba[i * 4 + c].toInt() and 0xFF) * (1 - a) + (top.rgba[i * 4 + c].toInt() and 0xFF) * a
                base.rgba[i * 4 + c] = (v + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
            base.rgba[i * 4 + 3] = -1
        }
    }

    private fun texel(img: RgbaImage, m: EveMesh, v: Int): Int {
        val x = (m.uvs[v * 2] * img.width).toInt().coerceIn(0, img.width - 1)
        val y = (m.uvs[v * 2 + 1] * img.height).toInt().coerceIn(0, img.height - 1)
        return (y * img.width + x) * 4
    }

    /** The share of [m]'s vertices on opaque texels (alpha 128 or more) of [img]. */
    fun coverage(img: RgbaImage, m: EveMesh): Float {
        var c = 0
        for (v in 0 until m.vertexCount) if ((img.rgba[texel(img, m, v) + 3].toInt() and 0xFF) >= 128) c++
        return c.toFloat() / maxOf(1, m.vertexCount)
    }

    /** How far from grey [img] is under [m]'s vertices (mean of max - min channel, 0..255). */
    fun greyness(img: RgbaImage, m: EveMesh): Float {
        var s = 0.0; var c = 0
        for (v in 0 until m.vertexCount) {
            val i = texel(img, m, v)
            if ((img.rgba[i + 3].toInt() and 0xFF) < 128) continue
            val r = img.rgba[i].toInt() and 0xFF; val g = img.rgba[i + 1].toInt() and 0xFF; val b = img.rgba[i + 2].toInt() and 0xFF
            s += maxOf(r, g, b) - minOf(r, g, b); c++
        }
        return if (c == 0) 255f else (s / c).toFloat()
    }

    /** The vertices within 4 cm of the named rings (null: none named). */
    fun nearRing(m: EveMesh, vararg names: String): BooleanArray {
        val ring = names.flatMap { (m.rings[it] ?: IntArray(0)).toList() }
        return BooleanArray(m.vertexCount) { v ->
            ring.any { r ->
                val dx = m.positions[v * 3] - m.positions[r * 3]; val dy = m.positions[v * 3 + 1] - m.positions[r * 3 + 1]; val dz = m.positions[v * 3 + 2] - m.positions[r * 3 + 2]
                dx * dx + dy * dy + dz * dz < 0.04f * 0.04f
            }
        }
    }

    /** The mean colour (0xRRGGBB, averaged in linear light) of [img] under [m]'s vertices ([only]: those), opaque texels only. */
    fun meanAt(img: RgbaImage, m: EveMesh, only: BooleanArray?): Int {
        var r = 0.0; var g = 0.0; var b = 0.0; var c = 0
        for (v in 0 until m.vertexCount) {
            if (only != null && !only[v]) continue
            val i = texel(img, m, v)
            if ((img.rgba[i + 3].toInt() and 0xFF) < 128) continue
            r += toLin[img.rgba[i].toInt() and 0xFF]; g += toLin[img.rgba[i + 1].toInt() and 0xFF]; b += toLin[img.rgba[i + 2].toInt() and 0xFF]; c++
        }
        if (c == 0) return 0
        return (toSrgb((r / c).toFloat()) shl 16) or (toSrgb((g / c).toFloat()) shl 8) or toSrgb((b / c).toFloat())
    }

    fun luminance(rgb: Int): Float = 0.2126f * toLin[(rgb shr 16) and 255] + 0.7152f * toLin[(rgb shr 8) and 255] + 0.0722f * toLin[rgb and 255]

    /** The mean colour of [img]'s painted texels, weighted by their alpha (alpha over 32). */
    fun meanAlpha(img: RgbaImage): Int {
        var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
        for (i in 0 until img.width * img.height) {
            val a = img.rgba[i * 4 + 3].toInt() and 0xFF
            if (a <= 32) continue
            r += toLin[img.rgba[i * 4].toInt() and 0xFF] * a; g += toLin[img.rgba[i * 4 + 1].toInt() and 0xFF] * a; b += toLin[img.rgba[i * 4 + 2].toInt() and 0xFF] * a; w += a
        }
        if (w == 0.0) return 0
        return (toSrgb((r / w).toFloat()) shl 16) or (toSrgb((g / w).toFloat()) shl 8) or toSrgb((b / w).toFloat())
    }

    /** The rectangle (x, y, w, h) of [img]'s texels with alpha over 4, padded by 4; null when none is. */
    fun alphaBox(img: RgbaImage): IntArray? {
        var x0 = img.width; var y0 = img.height; var x1 = -1; var y1 = -1
        for (y in 0 until img.height) for (x in 0 until img.width) if ((img.rgba[(y * img.width + x) * 4 + 3].toInt() and 0xFF) > 4) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        x0 = maxOf(0, x0 - 4); y0 = maxOf(0, y0 - 4); x1 = minOf(img.width - 1, x1 + 4); y1 = minOf(img.height - 1, y1 + 4)
        return intArrayOf(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    fun cropImage(img: RgbaImage, r: IntArray): RgbaImage {
        val px = ByteArray(r[2] * r[3] * 4)
        for (y in 0 until r[3]) System.arraycopy(img.rgba, ((r[1] + y) * img.width + r[0]) * 4, px, y * r[2] * 4, r[2] * 4)
        return RgbaImage(r[2], r[3], px)
    }

}
