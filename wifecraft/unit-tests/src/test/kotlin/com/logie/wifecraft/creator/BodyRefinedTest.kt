package com.logie.wifecraft.creator

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The shapes' refined vertices read back (BodyShapes.morphRefined): refine.py made each the Phong midpoint of an edge, and the
 * package keeps only where the shape put it, so the renderer gets where it was before worked out again, exactly as refine.py
 * placed it. Laid out as Wrath++'s refine.py and presets.py make a shape: a square of four looks vertices (two triangles), its
 * diagonal split (the new vertex after the looks', each triangle in two), the whole moved by the shape; a looks vertex no
 * triangle of the shapes' skin uses (a worn piece cut down to fit) is left out.
 */
class BodyRefinedTest {
    private class Vx(val p: DoubleArray, val n: DoubleArray, val uv: DoubleArray, val bones: List<Pair<Int, Int>>)

    private fun unit(x: Double, y: Double, z: Double): DoubleArray { val l = sqrt(x * x + y * y + z * z); return doubleArrayOf(x / l, y / l, z / l) }

    // the looks: a curved square (normals leaning out, so the Phong midpoint isn't the plain one); 4 is the cut-down piece's
    private val looks = listOf(
        Vx(doubleArrayOf(0.10, -0.05, 1.35), unit(1.0, -0.4, -0.3), doubleArrayOf(0.60, 0.50), listOf(3 to 255)),
        Vx(doubleArrayOf(0.10, 0.05, 1.35), unit(1.0, 0.4, -0.3), doubleArrayOf(0.70, 0.50), listOf(9 to 255)),
        Vx(doubleArrayOf(0.11, 0.05, 1.45), unit(1.0, 0.4, 0.3), doubleArrayOf(0.70, 0.40), listOf(3 to 128, 7 to 127)),
        Vx(doubleArrayOf(0.11, -0.05, 1.45), unit(1.0, -0.4, 0.3), doubleArrayOf(0.60, 0.40), listOf(9 to 200, 7 to 55)),
        Vx(doubleArrayOf(0.105, 0.0, 1.40), unit(1.0, 0.0, 0.0), doubleArrayOf(0.65, 0.45), listOf(5 to 255)),
    )

    /** refine.py's midpoint of a and b: the middle pulled onto both corners' tangent planes, halfway (in float, as stored). */
    private fun phong(a: Vx, b: Vx): DoubleArray {
        val m = DoubleArray(3) { (a.p[it] + b.p[it]) / 2 }
        val q = DoubleArray(3)
        for (c in listOf(a, b)) {
            val d = (0 until 3).sumOf { (m[it] - c.p[it]) * c.n[it] }
            for (k in 0 until 3) q[k] += 0.5 * (m[k] - d * c.n[k])
        }
        return DoubleArray(3) { q[it].toFloat().toDouble() }
    }

    private fun mesh(vs: List<Vx>): BodyShapes.Mesh {
        val pos = FloatArray(vs.size * 3); val nrm = FloatArray(vs.size * 3); val uv = FloatArray(vs.size * 2); val bones = ByteArray(vs.size * 8)
        for ((i, v) in vs.withIndex()) {
            for (k in 0 until 3) { pos[i * 3 + k] = v.p[k].toFloat(); nrm[i * 3 + k] = v.n[k].toFloat() }
            uv[i * 2] = v.uv[0].toFloat(); uv[i * 2 + 1] = v.uv[1].toFloat()
            for ((s, bw) in v.bones.withIndex()) { bones[i * 8 + s] = bw.second.toByte(); bones[i * 8 + 4 + s] = bw.first.toByte() }
        }
        return BodyShapes.Mesh(vs.size, pos, nrm, uv, bones)
    }

    /** A skin as refine.write_skin lays it out: the vertex lookup, the indices, one body section, and the layer's (1901). */
    private fun skin(tris: List<Int>, layer: List<Int>): ByteArray {
        val lookup = (tris + layer).distinct()
        val local = (tris + layer).map { lookup.indexOf(it) }
        val vo = 48; val io = vo + lookup.size * 2; val so = (io + local.size * 2 + 15) / 16 * 16
        val b = ByteBuffer.allocate(so + 2 * 48).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0, 'S'.code.toByte()); b.put(1, 'K'.code.toByte()); b.put(2, 'I'.code.toByte()); b.put(3, 'N'.code.toByte())
        b.putInt(4, lookup.size); b.putInt(8, vo); b.putInt(12, local.size); b.putInt(16, io); b.putInt(28, 2); b.putInt(32, so)
        for ((i, v) in lookup.withIndex()) b.putShort(vo + i * 2, v.toShort())
        for ((i, v) in local.withIndex()) b.putShort(io + i * 2, v.toShort())
        b.putShort(so + 8, 0); b.putShort(so + 10, tris.size.toShort())
        b.putShort(so + 48, 1901); b.putShort(so + 48 + 8, tris.size.toShort()); b.putShort(so + 48 + 10, layer.size.toShort())
        return b.array()
    }

    @Test fun mixingBonesIsRefinesOwn() {
        // half of each corner's weights per bone, the 4 heaviest, bytes summing to 255, a tie's spare byte to the first met
        assertEquals(listOf(3 to 192, 7 to 63), BodyShapes.mixBones(listOf(3 to 255), listOf(3 to 128, 7 to 127)))
        assertEquals(listOf(3 to 128, 9 to 127), BodyShapes.mixBones(listOf(3 to 255), listOf(9 to 255)))
        assertEquals(255, BodyShapes.mixBones(listOf(1 to 100, 2 to 100, 3 to 55), listOf(4 to 200, 5 to 55)).sumOf { it.second })
        assertEquals(4, BodyShapes.mixBones(listOf(1 to 100, 2 to 100, 3 to 55), listOf(4 to 200, 5 to 55)).size)
    }

    @Test fun theRefinedVerticesComeBackWhereRefineMadeThem() {
        // the diagonal 0-2 split: its midpoint 5 (refine.py's numbering: after the looks' own)
        val mid = Vx(phong(looks[0], looks[2]), unit(looks[0].n[0] + looks[2].n[0], looks[0].n[1] + looks[2].n[1], looks[0].n[2] + looks[2].n[2]),
            doubleArrayOf((0.60 + 0.70) / 2, (0.50 + 0.40) / 2), BodyShapes.mixBones(looks[0].bones, looks[2].bones))
        val refined = looks + mid
        // the shape: forward and down, more at the middle (a mound), the cut-down piece (4) left where it was
        val move = { i: Int -> if (i == 4) doubleArrayOf(0.0, 0.0, 0.0) else if (i == 5) doubleArrayOf(0.03, 0.0, -0.012) else doubleArrayOf(0.01, 0.0, -0.004) }
        val shaped = refined.mapIndexed { i, v -> Vx(DoubleArray(3) { v.p[it] + move(i)[it] }, v.n, v.uv, v.bones) }
        // triangles (0,1,2), (0,2,3) split along 0-2: 5 between 0 and 2; 1 and 3 face each other across it (a decoy pair)
        val tris = listOf(0, 1, 5, 5, 1, 2, 0, 5, 3, 5, 2, 3)
        val s = skin(tris, listOf(6, 7, 8))
        assertContentEquals(tris.toIntArray(), BodyShapes.triangles(s))
        val m = BodyShapes.morphRefined(mesh(looks), looks.size, mesh(shaped), refined.size, BodyShapes.triangles(s))
        val st = BodyShapes.STRIDE
        assertEquals(5 * st, m.size)                      // looks 0..3 (4 is no triangle's: left out), then the refined 5
        for (i in 0 until 4) {
            assertEquals(looks[i].p[0].toFloat(), m[i * st]); assertEquals(looks[i].p[2].toFloat(), m[i * st + 2])
            assertEquals(0.01f, m[i * st + 3], 1e-6f)
        }
        val r = 4 * st
        val want = phong(looks[0], looks[2])
        for (k in 0 until 3) assertEquals(want[k].toFloat(), m[r + k], 1e-7f, "rest $k")
        assertEquals(0.03f, m[r + 3], 1e-6f); assertEquals(-0.012f, m[r + 5], 1e-6f)
        assertEquals(0.65f, m[r + 12], 1e-6f); assertEquals(0.45f, m[r + 13], 1e-6f)
        // the plain midpoint isn't it: the surface is round
        assertEquals(true, kotlin.math.abs(want[0] - (looks[0].p[0] + looks[2].p[0]) / 2) > 1e-4)
    }

    @Test fun aShapeWithoutTrianglesKeepsEveryLooksVertex() {
        val m = BodyShapes.morphRefined(mesh(looks), looks.size, mesh(looks), looks.size, IntArray(0))
        assertEquals(looks.size * BodyShapes.STRIDE, m.size)
    }
}
