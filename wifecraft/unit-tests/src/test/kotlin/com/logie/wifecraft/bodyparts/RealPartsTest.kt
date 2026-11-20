package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.sims.DbpfFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real mod, when it is at hand: WIFECRAFT_SIMS_PARTS = the folder (or zip, or package) of the user's own body-part
 * mod; skipped otherwise. Writes a report, the part files and PNGs of the decoded textures to WIFECRAFT_SIMS_PARTS_OUT
 * (default: the system temp folder). Nothing of the mod is committed.
 */
class RealPartsTest {
    @Test
    fun importRealMod() {
        val src = System.getenv("WIFECRAFT_SIMS_PARTS")?.let(::File)?.takeIf { it.exists() } ?: return
        val out = File(System.getenv("WIFECRAFT_SIMS_PARTS_OUT") ?: File(System.getProperty("java.io.tmpdir"), "wifecraft-bodyparts").path).apply { mkdirs() }
        val rep = StringBuilder()
        fun line(s: String) { rep.append(s).append('\n') }

        // every CAS part and mesh, package by package
        val pkgs = if (src.isDirectory) src.walkTopDown().filter { it.name.endsWith(".package") }.sortedBy { it.path }.toList() else listOf(src)
        for (f in pkgs) DbpfFile(f).use { p ->
            line("== ${f.name}: ${p.entries.size} resources: " + p.entries.groupingBy { "%08X".format(it.type) }.eachCount().entries.joinToString { "${it.value} x ${it.key}" })
            val byTI = p.entries.groupBy { it.type to it.instance }
            for (e in p.entries.filter { it.type == Casp.TYPE }) {
                val d = Casp.parse(p.read(e), e.key)
                line("CASP %016X v%d '%s' body=%s ageGender=0x%X%s%s parm=%d swatches=%d tags=%d layer=%d uvSpace=%d diffuse=%s region=%s".format(
                    e.instance, d.version, d.name, PartDescription.bodyTypeName(d.bodyType), d.ageGender, if (d.isMale) " male" else "", if (d.isAdult) " adult" else "",
                    d.parmFlags, d.swatchColors.size, d.tags.size, d.sortLayer, d.sharedUvSpace, d.diffuse, d.regionMap))
                for (l in d.lods.sortedBy { it.level }) for (k in l.meshes) {
                    val ge = byTI[k.type to k.instance]?.let { c -> c.firstOrNull { it.group == k.group } ?: c.first() }
                    if (ge == null) { line("   LOD${l.level} $k (not in this package)"); continue }
                    val m = Geom.parse(p.read(ge), ge.key)
                    line("   LOD${l.level} $k GEOM v${m.version} ${m.vertexCount} vertices ${m.triangleCount} triangles ${m.boneHashes.size} bones")
                }
            }
            val tex = p.entries.filter { SimsTexture.isTexture(it.type) }.groupingBy { "%08X %s".format(it.type, SimsTexture.kind(p.read(it))) }.eachCount()
            line("textures: $tex")
        }

        val imp = PartPackage.open(src)
        line("\n== parts: ${imp.parts.size} (overlays without a mesh: ${imp.overlays.size}; problems: ${imp.problems})")
        val pngDir = File(out, "png").apply { mkdirs() }
        for (p in imp.parts) {
            val full = p.fullMesh!!; val m = p.mesh!!
            val bb = m.bounds()
            for (i in m.indices) assertTrue(i in 0 until m.vertexCount, "${p.name}: index $i")
            assertTrue(m.positions.all { it.isFinite() } && (m.normals?.all { it.isFinite() } ?: true) && (m.uvs?.all { it.isFinite() } ?: true), "${p.name}: NaN")
            val d = p.diffuse()
            line("%-7s %-22s '%s' LOD%d full %d v/%d t, part %d v/%d t (extracted=%s) bbox %s; diffuse %s; aliases %d; %s".format(
                p.state, p.variant.label, p.name, p.lod, full.vertexCount, full.triangleCount, m.vertexCount, m.triangleCount, p.extracted,
                bb.joinToString(",") { "%.3f".format(it) }, d?.let { "${it.width}x${it.height}" }, p.aliases.size, p.problems))
            if (d != null && m.uvs != null) {
                val (crop, _) = PartPackage.cropToUvs(d, m.uvs!!)
                File(pngDir, "${p.state}_${PartPackage.safeName(p.variant.label)}.png").writeBytes(Png.encode(crop))
                if (p.state == PartState.DEFAULT && p.variant.label == "Medium Uncut High") {
                    for ((label, rgb) in listOf("tan" to 0xC68E6A, "dark" to 0x6B4430, "nightelf" to 0x8C7FB8)) {
                        File(pngDir, "recolor_${label}.png").writeBytes(Png.encode(Recolor.recolor(crop, rgb)))
                    }
                    line("   reference colour of its texels: %06X".format(Recolor.averageColor(crop)))
                }
            }
        }
        val states = imp.parts.groupingBy { it.state }.eachCount()
        line("states: $states; variants: ${imp.parts.map { it.variant.label }.distinct().size}")
        assertTrue((states[PartState.DEFAULT] ?: 0) > 0 && (states[PartState.ON] ?: 0) > 0)

        val assets = System.getProperty("wifecraft.assets")
        val spec = assets?.let { AttachSpec.fromProfile(File(it, "sims_profile.json").readText()) } ?: AttachSpec.default()
        val ex = PartPackage.export(imp, spec, File(out, "bodyparts"))
        var bytes = 0L
        for (f in ex.files.filter { it.name.endsWith(".wcbp") }) {
            val c = PartFile.read(f.readBytes()); bytes += f.length()
            assertTrue(c.positions.all { it.isFinite() })
        }
        val one = PartFile.read(ex.files.first { it.name.endsWith(".wcbp") }.readBytes())
        line("export: ${ex.files.size} files, ${bytes / 1024} KB of part files in ${ex.dir}; problems ${ex.problems}")
        line("sample part: anchor ${one.anchorSims.joinToString { "%.3f".format(it) }} scale %.3f bones ${one.bones} texture ${one.textureWidth}x${one.textureHeight}".format(one.defaultScale))
        File(out, "REPORT.txt").writeText(rep.toString())
        println(rep)
    }
}
