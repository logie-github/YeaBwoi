package com.logie.wifecraft.pack

import org.json.JSONArray
import org.json.JSONObject

/** Anything wrong with a pack: the message is for the person (what is wrong, what to do). */
class PackException(message: String) : Exception(message)

/**
 * What a pack entry holds, and the folder of the zip it lives in (`pack/<dir>/`). The kinds are the pack builder's
 * (`tools/build_pack.py`) and the app routes on them ([PackImporter]); a kind the app does not know is refused, not guessed.
 *  - [ANIMATIONS]: Sims 4 animation packages (WickedWhims stages) for the animation library;
 *  - [BODY_LAYER]: an animation-linked layer on a body (the cum mesh): an object model a stage's prop names;
 *  - [PROPS]: toys, dolls, handcuffs: object models a stage's props name, with their own animations;
 *  - [FURNITURE]: object models placed from Build mode's New tab (a bed);
 *  - [SCRIPT_INFO]: a text note about a script of the mod (never the script itself);
 *  - [UNUSED]: packages kept in the pack that the app has no use for (a purchase menu): listed, never imported.
 */
enum class PackKind(val key: String, val dir: String, val label: String) {
    ANIMATIONS("animations", "animations", "Animations"),
    BODY_LAYER("body-layer", "bodylayers", "Body layers"),
    PROPS("props", "props", "Props"),
    FURNITURE("furniture", "furniture", "Furniture"),
    SCRIPT_INFO("script-info", "info", "Script notes"),
    UNUSED("unused", "other", "Kept, not used");

    companion object {
        fun of(key: String): PackKind? = values().firstOrNull { it.key == key }
    }
}

/** One file of a pack: [path] inside the zip, its [sha256] (lower-case hex) and [size], where it came from ([source]). */
data class PackEntry(
    val id: String,
    val kind: PackKind,
    val path: String,
    val source: String,
    val sha256: String,
    val size: Long,
    /** The builder's reading of it: [category] for the library, [stages], [clips], [objects]... free-form numbers and names. */
    val summary: JSONObject = JSONObject(),
) {
    val file: String get() = path.substringAfterLast('/')
    val category: String get() = summary.optString("category", "")
}

data class PackManifest(val schema: Int, val name: String, val entries: List<PackEntry>, val notes: List<String>) {
    /** Entries of [kind] in the pack's order. */
    fun of(kind: PackKind): List<PackEntry> = entries.filter { it.kind == kind }

    companion object {
        const val SCHEMA = 1
        const val FORMAT = "wifecraft-pack"
        const val MANIFEST_PATH = "pack/manifest.json"
        /** A note for people who open the zip; the app reads nothing in it. */
        const val README_PATH = "pack/README.txt"
        const val MAX_ENTRIES = 512
        const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 1 shl 20
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9 ._()\\[\\]+,'&-]{0,120}")

        /**
         * A manifest read and checked: the format and schema (a newer schema is refused, never half-read), every entry's kind, path
         * (inside `pack/<kind's folder>/`, no `..`, no absolute or drive paths, no backslashes, `.package` or `.txt` only),
         * digest and size, unique ids and paths, and the counts and sizes within the limits.
         */
        fun parse(text: String): PackManifest {
            if (text.length > MAX_MANIFEST_BYTES) throw PackException("The pack's manifest is too large to be one (over 1 MB).")
            val o = try { JSONObject(text) } catch (e: Exception) { throw PackException("The pack's manifest is not valid JSON (${e.message?.take(80)}).") }
            if (o.optString("format") != FORMAT) throw PackException("This zip is not a Wifecraft pack (its manifest has no format \"$FORMAT\").")
            val schema = o.optInt("schema", 0)
            if (schema < 1) throw PackException("The pack's manifest has no schema number.")
            if (schema > SCHEMA) throw PackException("This pack is made for a newer version of the app (pack schema $schema; this app reads $SCHEMA). Update the app.")
            val arr = o.optJSONArray("entries") ?: throw PackException("The pack's manifest lists no entries.")
            if (arr.length() > MAX_ENTRIES) throw PackException("The pack lists ${arr.length()} files; the most it may hold is $MAX_ENTRIES.")
            val ids = HashSet<String>()
            val paths = HashSet<String>()
            var total = 0L
            val entries = ArrayList<PackEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: throw PackException("Entry ${i + 1} of the manifest is not an object.")
                val id = e.optString("id")
                if (id.isEmpty() || id.length > 160) throw PackException("Entry ${i + 1} of the manifest has no usable id.")
                if (!ids.add(id)) throw PackException("The manifest lists the id \"$id\" twice.")
                val kind = PackKind.of(e.optString("type")) ?: throw PackException("Entry \"$id\" has a type this app does not know (\"${e.optString("type")}\").")
                val path = e.optString("path")
                checkPath(id, kind, path)
                if (!paths.add(path.lowercase())) throw PackException("The manifest lists the path \"$path\" twice.")
                val sha = e.optString("sha256").lowercase()
                if (!HEX64.matches(sha)) throw PackException("Entry \"$id\" has no valid sha256.")
                val size = e.optLong("size", -1)
                if (size < 0 || size > MAX_ENTRY_BYTES) throw PackException("Entry \"$id\" says it is $size bytes; a file of a pack is at most ${MAX_ENTRY_BYTES / 1048576} MB.")
                total += size
                if (total > MAX_TOTAL_BYTES) throw PackException("The pack's files add up to over ${MAX_TOTAL_BYTES / 1048576} MB; that is more than the app takes.")
                entries += PackEntry(id, kind, path, e.optString("source", path.substringAfterLast('/')).take(200), sha, size, e.optJSONObject("summary") ?: JSONObject())
            }
            val notes = o.optJSONArray("notes")?.let { n -> List(n.length()) { n.optString(it) } }.orEmpty()
            return PackManifest(schema, o.optString("name", "Pack").take(80), entries, notes)
        }

        /** A pack path is `pack/<kind folder>/<one file name>`: nothing that could leave the folder it is unpacked into. */
        fun checkPath(id: String, kind: PackKind, path: String) {
            fun bad(why: String): Nothing = throw PackException("Entry \"$id\" has the path \"${path.take(120)}\": $why.")
            if (path.isEmpty()) bad("it is empty")
            if (path.contains('\\') || path.contains('\u0000') || path.contains(':')) bad("it has a backslash, a colon or a null")
            if (path.startsWith("/")) bad("it is absolute")
            val parts = path.split('/')
            if (parts.size != 3) bad("it must be pack/<folder>/<file>")
            if (parts.any { it.isEmpty() || it == "." || it == ".." }) bad("it has an empty or relative part")
            if (parts[0] != "pack" || parts[1] != kind.dir) bad("a ${kind.key} entry belongs under pack/${kind.dir}/")
            if (!SEGMENT.matches(parts[2])) bad("the file name has characters a pack file may not have")
            val ext = parts[2].substringAfterLast('.', "").lowercase()
            val allowed = if (kind == PackKind.SCRIPT_INFO) "txt" else "package"
            if (ext != allowed) bad("a ${kind.key} entry is a .$allowed file")
        }
    }
}

/** The manifest as JSON, in the order the builder writes it (tests build packs with it). */
fun PackManifest.toJson(): JSONObject = JSONObject().put("format", PackManifest.FORMAT).put("schema", schema).put("name", name)
    .put("entries", JSONArray().also { a ->
        for (e in entries) a.put(JSONObject().put("id", e.id).put("type", e.kind.key).put("path", e.path).put("source", e.source)
            .put("sha256", e.sha256).put("size", e.size).put("summary", e.summary))
    }).put("notes", JSONArray(notes))
