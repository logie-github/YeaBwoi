// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export.core

import org.json.JSONArray
import org.json.JSONObject

/** One line of checksums.tsv: the file's SHA-256 (lowercase hex), its size and its path under `files/`. */
data class ChecksumLine(val sha256: String, val size: Long, val path: String)

/**
 * checksums.tsv, the contract with the Blizzard app's importer: UTF-8, a line per file under
 * `files/`, sorted by path: `<sha256>\t<size>\t<path>\n`.
 */
object Checksums {
    const val FILE = "checksums.tsv"

    /** The working file of a run in progress: the files copied so far, with the source's modified time as a fourth column. */
    const val PARTIAL = "checksums.partial"

    private val HEX = Regex("[0-9a-f]{64}")

    fun write(lines: Collection<ChecksumLine>): String {
        val out = StringBuilder()
        for (line in lines.sortedBy { it.path }) {
            require(HEX.matches(line.sha256)) { "Not a SHA-256: ${line.sha256}" }
            require(line.path.none { it == '\t' || it == '\n' || it == '\r' }) { "A tab or line break in a file name: ${line.path}" }
            out.append(line.sha256).append('\t').append(line.size).append('\t').append(line.path).append('\n')
        }
        return out.toString()
    }

    /** The lines of [text]; a damaged line is an error (a checksum file that can't be trusted is no use). */
    fun parse(text: String): List<ChecksumLine> = text.split('\n').filter { it.isNotEmpty() }.map { raw ->
        val line = raw.removeSuffix("\r")
        val parts = line.split('\t', limit = 3)
        require(parts.size == 3 && HEX.matches(parts[0]) && parts[1].toLongOrNull()?.let { it >= 0 } == true && parts[2].isNotEmpty()) {
            "Damaged checksum line: $line"
        }
        ChecksumLine(parts[0], parts[1].toLong(), parts[2])
    }

    /** A copied file as the working file records it. */
    data class Done(val sha256: String, val size: Long, val modified: Long)

    fun writePartial(done: Map<String, Done>): String {
        val out = StringBuilder()
        for ((path, d) in done.toSortedMap()) {
            out.append(d.sha256).append('\t').append(d.size).append('\t').append(path).append('\t').append(d.modified).append('\n')
        }
        return out.toString()
    }

    /** The working file's records; lines that don't read (it may have been cut off) are dropped, so those files are copied again. */
    fun parsePartial(text: String): Map<String, Done> {
        val done = HashMap<String, Done>()
        for (raw in text.split('\n')) {
            val parts = raw.removeSuffix("\r").split('\t')
            if (parts.size != 4 || !HEX.matches(parts[0])) continue
            val size = parts[1].toLongOrNull() ?: continue
            val modified = parts[3].toLongOrNull() ?: continue
            done[parts[2]] = Done(parts[0], size, modified)
        }
        return done
    }
}

/** export.json: written last, so its presence means the export finished. */
data class ExportManifest(
    val format: Int,
    val app: String,
    val appVersion: String,
    val createdAt: String,
    val sourceFilesDir: String,
    val files: Int,
    val bytes: Long,
    val folders: List<String>,
    val excluded: List<String>,
    val checksums: String,
    val prefs: List<String>,
    /** Left out inside exported folders (an addition to the contract; readers may ignore it). */
    val excludedInside: List<String> = emptyList(),
    /**
     * Files stored under another name than they have in the app (names that differ only in case,
     * which shared storage can't tell apart: [CaseSafe]): the real path under `files/` to the stored one.
     */
    val renames: Map<String, String> = emptyMap(),
    /** The game mode a per-mode zip holds ([ExportRules.Mode.key]); null for an export of everything. */
    val mode: String? = null,
) {
    fun toJson(): String {
        fun strings(list: List<String>) = list.joinToString(",", "[", "]") { Json.quote(it) }
        return buildString {
            append("{\n")
            append("  \"format\": ").append(format).append(",\n")
            append("  \"app\": ").append(Json.quote(app)).append(",\n")
            append("  \"appVersion\": ").append(Json.quote(appVersion)).append(",\n")
            append("  \"createdAt\": ").append(Json.quote(createdAt)).append(",\n")
            append("  \"sourceFilesDir\": ").append(Json.quote(sourceFilesDir)).append(",\n")
            append("  \"files\": ").append(files).append(",\n")
            append("  \"bytes\": ").append(bytes).append(",\n")
            append("  \"folders\": ").append(strings(folders)).append(",\n")
            append("  \"excluded\": ").append(strings(excluded)).append(",\n")
            append("  \"excludedInside\": ").append(strings(excludedInside)).append(",\n")
            append("  \"renames\": {").append(renames.entries.sortedBy { it.key }.joinToString(",") { "\n    ${Json.quote(it.key)}: ${Json.quote(it.value)}" }).append(if (renames.isEmpty()) "" else "\n  ").append("},\n")
            if (mode != null) append("  \"mode\": ").append(Json.quote(mode)).append(",\n")
            append("  \"checksums\": ").append(Json.quote(checksums)).append(",\n")
            append("  \"prefs\": ").append(strings(prefs)).append("\n")
            append("}\n")
        }
    }

    companion object {
        const val FILE = "export.json"
        const val FORMAT = 1
        const val APP = "com.logie.warcraft"

        fun parse(json: String): ExportManifest {
            val o = JSONObject(json)
            fun strings(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            return ExportManifest(
                o.getInt("format"), o.getString("app"), o.getString("appVersion"), o.getString("createdAt"),
                o.getString("sourceFilesDir"), o.getInt("files"), o.getLong("bytes"), strings("folders"), strings("excluded"),
                o.getString("checksums"), strings("prefs"), strings("excludedInside"),
                o.optJSONObject("renames")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) } } ?: emptyMap(),
                o.optString("mode").takeIf { it.isNotEmpty() },
            )
        }
    }
}

internal object Json {
    /** [s] as a JSON string, escaped (JSONObject.quote also escapes '/', which is legal but unreadable in paths). */
    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}

/** One SharedPreferences file as `prefs/<name>.json`: `{"<key>":{"type":"string|int|long|float|boolean|stringset","value":...}}`. */
object PrefsDump {
    const val FOLDER = "prefs"

    fun path(name: String) = "$FOLDER/$name.json"

    /** [all] is `SharedPreferences.getAll()`. A value of a type the preferences don't have is an error. Keys sorted. */
    fun toJson(all: Map<String, Any?>): String {
        if (all.isEmpty()) return "{}\n"
        val out = StringBuilder("{\n")
        val keys = all.keys.sorted()
        for ((index, key) in keys.withIndex()) {
            val (type, value) = when (val v = all[key]) {
                is String -> "string" to Json.quote(v)
                is Int -> "int" to v.toString()
                is Long -> "long" to v.toString()
                is Float -> "float" to (if (v.isFinite()) v.toString() else "null")
                is Boolean -> "boolean" to v.toString()
                is Set<*> -> "stringset" to v.map { it.toString() }.sorted().joinToString(",", "[", "]") { Json.quote(it) }
                else -> throw IllegalArgumentException("Preference $key has a value of a kind preferences don't have: ${v?.javaClass}")
            }
            out.append("  ").append(Json.quote(key)).append(": {\"type\": \"").append(type).append("\", \"value\": ").append(value).append("}")
            out.append(if (index == keys.size - 1) "\n" else ",\n")
        }
        return out.append("}\n").toString()
    }

    /** The reverse of [toJson]: key to its value as Kotlin has it (String, Int, Long, Float, Boolean, Set of String). */
    fun parse(json: String): Map<String, Any?> {
        val o = JSONObject(json)
        val result = LinkedHashMap<String, Any?>()
        for (key in o.keys().asSequence().sorted()) {
            val entry = o.getJSONObject(key)
            result[key] = when (val type = entry.getString("type")) {
                "string" -> entry.getString("value")
                "int" -> entry.getInt("value")
                "long" -> entry.getLong("value")
                "float" -> if (entry.isNull("value")) Float.NaN else entry.getDouble("value").toFloat()
                "boolean" -> entry.getBoolean("value")
                "stringset" -> entry.getJSONArray("value").let { a: JSONArray -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> throw IllegalArgumentException("Unknown preference type $type for $key")
            }
        }
        return result
    }
}
