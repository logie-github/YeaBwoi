package com.logie.wifecraft.creator

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The game's file names to its file ids: what turns a zip's `Character/Human/Female/humanfemaleskin00_03_hd.blp` into
 * the id of the retail file it replaces. The names are the community listfile's (wow-listfile, `id;path` lines, lower case);
 * the app fetches it once on the phone and keeps only the `character/` lines of it ([ListfileNames]); nothing of it is in the
 * repository. A plain interface, so tests use a fake ([MapNames]).
 */
interface ImportNames {
    /** True when names are at hand (fetched and cached). */
    fun available(): Boolean
    /** The file id of a game path (lower case, `/`), or null. */
    fun id(path: String): Int?

    companion object {
        /** A zip entry's name as the game's path: lower case, `/`, from its `character/` folder on; null if it has none. */
        fun gamePath(entry: String): String? {
            val p = entry.replace('\\', '/').lowercase()
            val at = if (p.startsWith("character/")) 0 else p.indexOf("/character/").let { if (it < 0) -1 else it + 1 }
            return if (at < 0) null else p.substring(at)
        }

        /**
         * The ids of the retail files a zip's [path] stands for: the file of that name, and its `_hd` twin (or the file
         * without `_hd`) where the game has both: it draws the older models' or the newer's, whichever its tables name.
         */
        fun retailIds(names: ImportNames, path: String): List<Int> {
            val stem = path.removeSuffix(".blp")
            val twin = if (stem.endsWith("_hd")) stem.removeSuffix("_hd") else stem + "_hd"
            return listOfNotNull(names.id(path), names.id("$twin.blp")).distinct()
        }
    }
}

/** Names from a map (tests). */
class MapNames(private val map: Map<String, Int>) : ImportNames {
    override fun available() = map.isNotEmpty()
    override fun id(path: String): Int? = map[path]
}

/**
 * The listfile on the phone: [refresh] fetches it ([open] gives the CSV's stream; [URL] by default), keeps the
 * `character/` lines in [cache] (written whole, then renamed), and [id] answers from the cache.
 */
class ListfileNames(private val cache: File, private val open: () -> InputStream = { fetch() }) : ImportNames {
    @Volatile private var table: Map<String, Int>? = null

    private fun loaded(): Map<String, Int> {
        table?.let { return it }
        val t = if (cache.isFile) runCatching { cache.inputStream().use { parse(it) } }.getOrNull().orEmpty() else emptyMap()
        table = t
        return t
    }

    override fun available() = loaded().isNotEmpty()
    override fun id(path: String): Int? = loaded()[path]

    /** Fetches and caches the names; returns how many it kept. Throws when it can't (nothing is changed then). */
    @Synchronized
    fun refresh(): Int {
        val t = open().use { parse(it) }
        if (t.isEmpty()) throw IOException("The file list had no character files in it.")
        cache.parentFile?.mkdirs()
        val tmp = File(cache.path + ".part")
        tmp.bufferedWriter().use { w -> for ((p, id) in t) w.write("$id;$p\n") }
        if (!tmp.renameTo(cache)) { cache.delete(); if (!tmp.renameTo(cache)) throw IOException("The file list couldn't be saved.") }
        table = t
        return t.size
    }

    companion object {
        const val URL = "https://github.com/wowdev/wow-listfile/releases/latest/download/community-listfile.csv"

        /** `id;path` lines, kept when the path is under `character/`. */
        fun parse(input: InputStream): Map<String, Int> {
            val out = HashMap<String, Int>()
            input.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val at = line.indexOf(';')
                    if (at <= 0) continue
                    val path = line.substring(at + 1).trim().lowercase()
                    if (!path.startsWith("character/")) continue
                    val id = line.substring(0, at).trim().toIntOrNull() ?: continue
                    out[path] = id
                }
            }
            return out
        }

        fun fetch(): InputStream {
            val conn = java.net.URI(URL).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Wifecraft")
            if (conn.responseCode != 200) { conn.disconnect(); throw IOException("The file list answered ${conn.responseCode}.") }
            return conn.inputStream
        }
    }
}
