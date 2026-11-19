package com.logie.wifecraft.audio

import java.util.Locale

/**
 * Sound names as animation clips carry them (a SOUND event's 128-byte name, for example `vo_cas_trait_artlover`), and what
 * they are matched against: the file names a user keeps. Pure JVM; no Android.
 */
object SoundNames {
    /** The audio formats the phone's MediaPlayer plays and the user may import. */
    val FORMATS = listOf("ogg", "wav", "mp3", "m4a", "aac", "flac")

    /** Typical prefixes of a sound's name that a file may leave out ("vo_x" kept as "x.ogg"). */
    val PREFIXES = listOf("vo_", "sfx_", "sf_", "snd_", "sound_", "amb_", "mus_", "play_", "stop_")

    /** The group file used when nothing else matches. */
    const val DEFAULT = "default"

    private const val FNV32_BASIS = 0x811c9dc5u
    private const val FNV32_PRIME = 0x01000193u
    private const val FNV64_BASIS = 0xcbf29ce484222325uL
    private const val FNV64_PRIME = 0x00000100000001b3uL

    /** FNV-1a 32 of the UTF-8 bytes of [s] as given (lower-case it first for a sound's hash: [hashOf]). */
    fun fnv32(s: String): UInt {
        var h = FNV32_BASIS
        for (b in s.toByteArray(Charsets.UTF_8)) { h = h xor (b.toInt() and 0xff).toUInt(); h *= FNV32_PRIME }
        return h
    }

    /** FNV-1a 64 of the UTF-8 bytes of [s] as given. */
    fun fnv64(s: String): ULong {
        var h = FNV64_BASIS
        for (b in s.toByteArray(Charsets.UTF_8)) { h = h xor (b.toInt() and 0xff).toULong(); h *= FNV64_PRIME }
        return h
    }

    /** The sound's hashes, of its lower-case name: the 32-bit and the 64-bit one. */
    fun hashOf(name: String): Pair<UInt, ULong> { val n = name.trim().lowercase(Locale.ROOT); return fnv32(n) to fnv64(n) }

    fun hex32(v: UInt): String = v.toString(16).padStart(8, '0')
    fun hex64(v: ULong): String = v.toString(16).padStart(16, '0')

    /**
     * A file's or a sound's name made comparable: the path and a known audio extension removed, lower case, anything but
     * letters, digits, '_' and '-' turned into '_' (spaces and dots included).
     */
    fun normalise(name: String): String {
        var n = name.trim().replace('\\', '/').substringAfterLast('/')
        val dot = n.lastIndexOf('.')
        if (dot > 0 && n.substring(dot + 1).lowercase(Locale.ROOT) in FORMATS) n = n.substring(0, dot)
        return n.lowercase(Locale.ROOT).map { if (it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-') it else '_' }.joinToString("")
    }

    /** The extension of a file name when it is one of [FORMATS], else null. */
    fun format(fileName: String): String? =
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { fileName.contains('.') && it in FORMATS }

    /** A name, then the same with each leading prefix of [PREFIXES] taken off in turn ("sfx_vo_a" gives "sfx_vo_a", "vo_a", "a"). Without repeats. */
    fun candidates(eventName: String): List<String> {
        val out = LinkedHashSet<String>()
        var n = normalise(eventName)
        if (n.isEmpty()) return emptyList()
        out.add(n)
        while (true) {
            val p = PREFIXES.firstOrNull { n.startsWith(it) && n.length > it.length } ?: break
            n = n.substring(p.length)
            out.add(n)
        }
        return out.toList()
    }

    /** "name_01" gives ("name", 1); a name without a trailing number gives null. */
    fun variantOf(base: String): Pair<String, Int>? {
        val m = VARIANT.matchEntire(base) ?: return null
        return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: return null)
    }

    /** A file name that is a hash: 8 hex digits (a 32-bit hash) or 16 (a 64-bit one), "0x" optional; the digits' value and bit size. */
    fun hashFileName(base: String): Pair<ULong, Int>? {
        val s = base.lowercase(Locale.ROOT).removePrefix("0x")
        if (s.length != 8 && s.length != 16) return null
        if (!s.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return (s.toULongOrNull(16) ?: return null) to s.length * 4
    }

    /** Which of [files] a play should use: a stable pick by [seed] over the name-sorted list (same seed, same file). */
    fun <T> choose(files: List<T>, seed: Long, key: (T) -> String): T? {
        if (files.isEmpty()) return null
        val sorted = files.sortedBy(key)
        return sorted[Math.floorMod(mix(seed), sorted.size.toLong()).toInt()]
    }

    /** A seed for one play: the sound, who it is for and how many times that pair has played (so repeats vary, and replays agree). */
    fun seed(name: String, actorKey: String?, nth: Int): Long = fnv64(normalise(name) + "|" + (actorKey ?: "")).toLong() * 31 + nth

    /** splitmix64's finaliser: neighbouring seeds pick unrelated variants. */
    private fun mix(x: Long): Long {
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return (z xor (z ushr 31)) and Long.MAX_VALUE
    }

    private val VARIANT = Regex("^(.+?)[_-]?(\\d{1,4})$")
}
