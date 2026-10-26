// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Warcraft III's models (MDX) in the version 1.27 reads, 800, from Reforged's 900, 1000 and 1100
 * (what graphics mods made for 1.32 and later save, Classic+ among them). Only three things differ
 * where a model doesn't use Reforged's HD art (its own shaders, normal maps, skin weights):
 *
 * - **VERS**: the version number.
 * - **MTLS**: a material's shader name (900 and 1000: 80 bytes); a layer's emissive gain (900),
 *   fresnel colour, opacity and team colour (1000), and (1100) its shader type and list of
 *   textures, the first of which is the layer's own texture (its ID, or its KMTF track); the
 *   tracks of those fields (KMTE, KFC3, KFCA, KFTC).
 * - **GEOS**: a geoset's level of detail and name (84 bytes after its selection flags), its tangents
 *   (TANG) and skin weights (SKIN).
 *
 * Reforged's own chunks (CORN popcorn emitters, FAFX face effects, BPOS bind poses) are left out;
 * everything else is copied as it is. The layout follows war3-model 4.0.1's parser (MIT), which
 * reads 1100. A model that uses skin weights or tangents isn't one 800 can draw: [toV800] says so.
 */
object Mdx {
    const val TARGET = 800

    /** Chunks 800 doesn't have. */
    private val REFORGED_CHUNKS = setOf("CORN", "FAFX", "BPOS")

    class NotConvertible(message: String) : IllegalArgumentException(message)

    /** The model's version, or null if [bytes] isn't an MDX. */
    fun version(bytes: ByteArray): Int? {
        if (bytes.size < 16 || String(bytes, 0, 4, Charsets.ISO_8859_1) != "MDLX") return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (tag(buffer, 4) != "VERS") return null
        return buffer.getInt(12)
    }

    /** The model as version 800, or null if it already is one (or older). */
    fun toV800(bytes: ByteArray): ByteArray? {
        val version = version(bytes) ?: throw NotConvertible("not an MDX model")
        if (version <= TARGET) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = Writer()
        out.raw(bytes, 0, 4)
        var at = 4
        while (at + 8 <= bytes.size) {
            val name = tag(input, at)
            val size = input.getInt(at + 4)
            val start = at + 8
            require(size >= 0 && start + size <= bytes.size) { "chunk $name runs past the end" }
            when (name) {
                "VERS" -> out.chunk(name) { it.int(TARGET) }
                "MTLS" -> out.chunk(name) { materials(input, start, start + size, version, it) }
                "GEOS" -> out.chunk(name) { geosets(input, start, start + size, version, it) }
                in REFORGED_CHUNKS -> {}
                else -> out.chunk(name) { it.raw(bytes, start, size) }
            }
            at = start + size
        }
        return out.bytes()
    }

    // Materials -------------------------------------------------------------------------------

    private fun materials(input: ByteBuffer, from: Int, end: Int, version: Int, out: Writer) {
        var at = from
        while (at < end) {
            val size = input.getInt(at)
            val next = at + size
            out.sized { material ->
                material.int(input.getInt(at + 4)) // priority plane
                material.int(input.getInt(at + 8)) // flags
                var p = at + 12
                if (version in 900 until 1100) p += 80 // shader
                check(tag(input, p) == "LAYS") { "a material without LAYS" }
                val count = input.getInt(p + 4)
                material.raw(input.array(), p, 8)
                p += 8
                repeat(count) { p = layer(input, p, version, material) }
                check(p == next) { "a material's layers don't fill it" }
            }
            at = next
        }
    }

    /** One layer, from [at]: written as 800's, and where the next one starts. */
    private fun layer(input: ByteBuffer, at: Int, version: Int, out: Writer): Int {
        val size = input.getInt(at)
        val end = at + size
        val filterMode = input.getInt(at + 4)
        val flags = input.getInt(at + 8)
        var textureId = input.getInt(at + 12)
        val textureAnimation = input.getInt(at + 16)
        val coord = input.getInt(at + 20)
        val alpha = input.getInt(at + 24) // a float, kept as its bits
        var p = at + 28
        if (version >= 900) p += 4 // emissive gain
        if (version >= 1000) p += 20 // fresnel colour (3), opacity, team colour
        val tracks = ArrayList<Pair<Int, Int>>() // (start, length) of the tracks 800 keeps
        var textureTrack: Pair<Int, Int>? = null
        if (version >= 1100) {
            p += 4 // shader type
            val textures = input.getInt(p)
            p += 4
            for (i in 0 until textures) {
                val id = input.getInt(p)
                p += 8 // the ID, then what the texture is for
                var track: Pair<Int, Int>? = null
                if (p + 4 <= end && tag(input, p) == "KMTF") {
                    val length = trackLength(input, p, 1)
                    track = p to length
                    p += length
                }
                // The first texture is the layer's own (diffuse); the HD ones (normal, ORM, emissive,
                // team colour, reflections) aren't drawn by 800.
                if (i == 0) {
                    textureId = id
                    textureTrack = track
                }
            }
        }
        while (p < end) {
            val name = tag(input, p)
            val length = when (name) {
                "KMTA", "KMTF", "KMTE", "KFCA", "KFTC" -> trackLength(input, p, 1)
                "KFC3" -> trackLength(input, p, 3)
                else -> throw NotConvertible("a layer has an unknown track $name")
            }
            if (name == "KMTA" || name == "KMTF") tracks += p to length
            p += length
        }
        check(p == end) { "a layer's tracks don't fill it" }
        out.sized { layer ->
            layer.int(filterMode)
            layer.int(flags)
            layer.int(textureId)
            layer.int(textureAnimation)
            layer.int(coord)
            layer.int(alpha)
            for ((start, length) in tracks) layer.raw(input.array(), start, length)
            // The first texture's own track is the layer's KMTF, unless the layer has one already.
            val track = textureTrack
            if (track != null && tracks.none { tag(input, it.first) == "KMTF" }) layer.raw(input.array(), track.first, track.second)
        }
        return end
    }

    /** A track's whole length from its tag: the tag, its keys and their tangents (Hermite, Bezier). */
    private fun trackLength(input: ByteBuffer, at: Int, values: Int): Int {
        val keys = input.getInt(at + 4)
        val interpolation = input.getInt(at + 8)
        val perKey = 4 + 4 * values * (if (interpolation >= 2) 3 else 1)
        return 16 + keys * perKey
    }

    // Geosets ---------------------------------------------------------------------------------

    private fun geosets(input: ByteBuffer, from: Int, end: Int, version: Int, out: Writer) {
        var at = from
        while (at < end) {
            val size = input.getInt(at)
            val next = at + size
            out.sized { geoset(input, at + 4, next, version, it) }
            at = next
        }
    }

    private fun geoset(input: ByteBuffer, from: Int, end: Int, version: Int, out: Writer) {
        var p = from
        // VRTX, NRMS (3 floats each), PTYP, PCNT (ints), PVTX (shorts), GNDX (bytes), MTGC, MATS (ints).
        for ((name, width) in listOf("VRTX" to 12, "NRMS" to 12, "PTYP" to 4, "PCNT" to 4, "PVTX" to 2, "GNDX" to 1, "MTGC" to 4, "MATS" to 4)) {
            check(tag(input, p) == name) { "a geoset without $name" }
            p += 8 + input.getInt(p + 4) * width
        }
        p += 12 // material, selection group, selection flags
        out.raw(input.array(), from, p - from)
        if (version >= 900) p += 84 // level of detail, its name
        val extents = p
        p += 28 // the geoset's extent
        val animations = input.getInt(p)
        p += 4 + 28 * animations
        out.raw(input.array(), extents, p - extents)
        while (version >= 900 && tag(input, p) != "UVAS") {
            when (tag(input, p)) {
                "TANG" -> throw NotConvertible("a geoset has tangents (Reforged's HD art)")
                "SKIN" -> throw NotConvertible("a geoset has skin weights (Reforged's HD art)")
                else -> throw NotConvertible("a geoset has an unknown part ${tag(input, p)}")
            }
        }
        check(tag(input, p) == "UVAS") { "a geoset without UVAS" }
        out.raw(input.array(), p, end - p)
    }

    // Bytes -----------------------------------------------------------------------------------

    private fun tag(buffer: ByteBuffer, at: Int) = String(buffer.array(), at, 4, Charsets.ISO_8859_1)

    /** Little-endian output with chunks and size-prefixed parts whose sizes are filled in after. */
    private class Writer {
        private val out = ByteArrayOutputStream()
        private val scratch = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

        fun int(value: Int) {
            scratch.clear()
            scratch.putInt(value)
            out.write(scratch.array(), 0, 4)
        }

        fun raw(bytes: ByteArray, from: Int, length: Int) = out.write(bytes, from, length)

        /** A chunk: its tag, its size (not counting these 8 bytes), what [body] writes. */
        fun chunk(name: String, body: (Writer) -> Unit) {
            val inner = Writer()
            body(inner)
            val data = inner.bytes()
            out.write(name.toByteArray(Charsets.ISO_8859_1))
            int(data.size)
            out.write(data)
        }

        /** A part that starts with its own size, counting those 4 bytes (a material, a layer, a geoset). */
        fun sized(body: (Writer) -> Unit) {
            val inner = Writer()
            body(inner)
            val data = inner.bytes()
            int(data.size + 4)
            out.write(data)
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}
