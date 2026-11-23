package com.logie.wifecraft.props

/**
 * What Build mode's New tab needs to stand a pack's furniture model in for a Bed (or Chair, Table): the footprint as a box's half
 * sizes and the surface's height, in the scene's own units (yards; [scale] model units per Sims metre). The model's axes are
 * Warcraft's: x forward, y left, z up. The surface is the top of the model's lowest wide, flat layer from the floor up: found from
 * the triangles that face up ([surfaceHeight]), else 0.75 of its height.
 */
object PropFootprint {
    class Spot(val halfX: Float, val halfY: Float, val surface: Float)

    /** The footprint of [m] at [scale]; [Spots]-style limits are the caller's. */
    fun of(m: PropModel, scale: Float = PropStageDriver.DEFAULT_SCALE): Spot {
        val size = m.size
        return Spot(size[0] * scale / 2f, size[1] * scale / 2f, surfaceHeight(m) * scale)
    }

    /**
     * The height (metres over the model's own floor, Warcraft z) of the widest upward-facing layer: triangles whose normal points
     * up (z > 0.9) are binned by the height of their centre (2 cm bins) and weighted by area; the highest bin holding at least
     * a quarter of the largest bin's area is the surface a body rests on (a mattress top, not a headboard's cap).
     */
    fun surfaceHeight(m: PropModel): Float {
        val low = m.bounds[2]
        val height = m.bounds[5] - low
        if (height <= 0f) return 0f
        val bins = FloatArray((height / 0.02f).toInt() + 2)
        for (mesh in m.meshes) {
            val p = mesh.positions
            var i = 0
            while (i + 2 < mesh.indices.size) {
                val a = mesh.indices[i] * 3; val b = mesh.indices[i + 1] * 3; val c = mesh.indices[i + 2] * 3
                i += 3
                val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
                val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                if (len < 1e-9f || kotlin.math.abs(nz) / len < 0.9f) continue
                val z = (p[a + 2] + p[b + 2] + p[c + 2]) / 3f - low
                bins[(z / 0.02f).toInt().coerceIn(0, bins.size - 1)] += len / 2f
            }
        }
        val most = bins.max()
        if (most <= 0f) return height * 0.75f
        for (k in bins.indices.reversed()) if (bins[k] >= most * 0.25f) return (k + 1) * 0.02f
        return height * 0.75f
    }
}
