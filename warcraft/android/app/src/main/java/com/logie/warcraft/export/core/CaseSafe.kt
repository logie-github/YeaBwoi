// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export.core

/**
 * What the shared storage the export is written to can't hold exactly: names that differ only in case
 * (below), names with characters it doesn't allow (`\ : * ? " < > |`, control characters), a name
 * ending in a dot or a space or starting with a space, one longer than 200 bytes, and names that
 * differ only in Unicode form (`é` as one character or as two). Each is stored under a name it can
 * hold, `<safe name>~<8 hex>`, listed in the manifest's `renames`.
 *
 * Shared storage (the folder the user picks) doesn't tell `FlyByWorgen.m2` from `flybyworgen.m2`:
 * writing the second one gives a file named "flybyworgen (1).m2", or replaces the first. The app's
 * own storage does tell them apart (the client's data and the modules' data can hold both), so
 * every name that has such a twin beside it is stored under a name of its own, `<name>~<8 hex>`,
 * and the export's manifest lists the renames (`renames`: the real path to the stored one, both
 * relative to `files/`). Both twins are renamed, so the names don't depend on which was written
 * first: a run that carries on, or a check, finds each where it was put. Pure Kotlin, unit-tested.
 */
object CaseSafe {
    /** The stored path of every path that needs a different one (the rest are stored as they are). */
    fun stored(paths: Collection<String>): Map<String, String> {
        val split = paths.associateWith { it.split('/') }
        val storedSegments = paths.associateWith { ArrayList<String>() }
        val depth = split.values.maxOfOrNull { it.size } ?: 0
        for (level in 0 until depth) {
            // The names at this level, by (where they are as stored, their name in lower case): which spellings are there.
            val spellings = HashMap<String, MutableSet<String>>()
            for ((path, segments) in split) {
                if (segments.size <= level) continue
                val parent = storedSegments.getValue(path).joinToString("/")
                spellings.getOrPut(key(parent, segments[level])) { HashSet() }.add(segments[level])
            }
            for ((path, segments) in split) {
                if (segments.size <= level) continue
                val parent = storedSegments.getValue(path).joinToString("/")
                val name = segments[level]
                val twins = spellings.getValue(key(parent, name))
                val original = segments.subList(0, level + 1).joinToString("/")
                storedSegments.getValue(path).add(if (twins.size > 1 || !holdsExactly(name)) "${safe(name)}~${hash(original)}" else name)
            }
        }
        return paths.mapNotNull { p -> storedSegments.getValue(p).joinToString("/").takeIf { it != p }?.let { p to it } }.toMap()
    }

    /** Two names are one to shared storage when these are equal: case and Unicode form don't count. */
    private fun key(parent: String, name: String) =
        java.text.Normalizer.normalize(parent, java.text.Normalizer.Form.NFC).lowercase() + "/" +
            java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).lowercase()

    private const val ILLEGAL = "\\:*?\"<>|"

    /** Whether shared storage keeps [name] exactly as it is. */
    internal fun holdsExactly(name: String): Boolean =
        name.none { it in ILLEGAL || it.code < 0x20 || it.code == 0x7f } && !name.endsWith('.') && !name.endsWith(' ') && !name.startsWith(' ') &&
            name.toByteArray(Charsets.UTF_8).size <= 200

    /** [name] with what shared storage doesn't allow replaced or trimmed (the hash after it keeps it unique). */
    internal fun safe(name: String): String {
        var out = name.map { if (it in ILLEGAL || it.code < 0x20 || it.code == 0x7f) '_' else it }.joinToString("").trimStart(' ').trimEnd('.', ' ')
        while (out.toByteArray(Charsets.UTF_8).size > 190) out = out.dropLast(1)
        return out.ifEmpty { "_" }
    }

    /** Eight hex digits of the path (a stable function of it: the same on every run). */
    internal fun hash(text: String): String {
        var h = 0x811C9DC5.toInt()
        for (c in text) { h = h xor c.code; h *= 16777619 }
        return "%08x".format(h)
    }
}
