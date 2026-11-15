package com.logie.wifecraft.creator

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * The game's tables the creator and the dressing read (Catalog, LookResolver, Dressing), as wago.tools' CSV exports
 * (`https://wago.tools/db2/<Name>/csv`, the current build, as the files themselves are fetched by id), fetched once on the
 * phone into a folder ([CsvTables] reads it) and kept. Nothing of the game is in the app or the repository. Pure JVM.
 */
object GameTables {
    /**
     * Every table read; tools/creator_probe.py's TABLES, CharComponentTextureSections (the layers' rectangles) and
     * GlobalStrings (the creator screen's own words: ui/CreatorLayout.kt's CreatorStrings).
     */
    val NAMES = listOf(
        "ChrRaces", "ChrRaceXChrModel", "ChrModel", "ChrCustomizationCategory", "ChrCustomizationOption",
        "ChrCustomizationChoice", "ChrCustomizationElement", "ChrCustomizationGeoset", "ChrCustomizationMaterial",
        "ChrCustomizationSkinnedModel", "ChrCustomizationReq", "ChrCustomizationBoneSet", "ChrCustomizationCondModel",
        "ChrModelTextureLayer", "ChrModelMaterial", "CharComponentTextureSections",
        "CreatureDisplayInfo", "CreatureModelData", "TextureFileData", "GlobalStrings",
    )

    /**
     * The build every table and every game file is taken at, the same for both (assets/files.json's source names it too).
     * wago.tools' unversioned links mix builds: in November 2026 they gave the Blood Elf man's model from one build and its
     * skin from another (the triangles joined the wrong vertices), and most character models in an older form than the
     * current tables'. Pinned, a model, its skin, its skeleton and the tables that name them always agree.
     */
    const val BUILD = "12.1.7.70892"

    /**
     * Tables taken at a build of their own: GlobalStrings at Shadowlands' last (9.2.7.45745), the build the creator's
     * screen and its art are Shadowlands' from (ui/CreatorArt.kt). The rest are [BUILD]'s, as the files are.
     */
    val BUILDS = mapOf("GlobalStrings" to "9.2.7.45745")

    /** A game file's link at [BUILD] (assets/files.json's source is this with `<id>`). */
    fun fileUrl(id: Int): String = "https://wago.tools/api/casc/$id?download&version=$BUILD"

    /**
     * Files fetched before [BUILD] was pinned (or at another) can't be trusted to agree: once, when `game/.build` under
     * [root] (the app's files folder) isn't [BUILD], the models, skins, skeletons and animation files in `game/` (told by
     * their first bytes; textures, sounds and the city keep), the tables and the creator's fetch plan and its "complete"
     * mark are deleted, to be fetched again at [BUILD]. Returns how many files went. Pure JVM; the app's start calls it.
     */
    fun migrate(root: File): Int {
        val game = File(root, "game")
        val mark = File(game, ".build")
        if (mark.isFile && runCatching { mark.readText().trim() }.getOrNull() == BUILD) return 0
        var gone = 0
        game.listFiles()?.forEach { f ->
            if (f.isFile && f.name.all { it.isDigit() } && modelFamily(f) && f.delete()) gone++
        }
        File(root, "cache/tables").listFiles()?.forEach { if (it.isFile && it.name.endsWith(".csv") && it.delete()) gone++ }
        File(root, "cache/looks-plan.txt").delete()
        File(root, "cache/looks-complete").delete()
        game.mkdirs()
        mark.writeText(BUILD)
        return gone
    }

    /** Whether a file is a model, skin, skeleton or animation file (its first four bytes). */
    fun modelFamily(f: File): Boolean {
        val head = ByteArray(4)
        val n = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (n < 4) return false
        return String(head, Charsets.ISO_8859_1) in MODEL_MAGIC
    }

    private val MODEL_MAGIC = setOf("MD21", "MD20", "SKIN", "SKL1", "SKS1", "SKB1", "AFM2", "AFSA", "AFSB", "AFID")

    /** About how much the creator's tables weigh together (19 MB in November 2026, GlobalStrings not counted), for the first launch's screen. */
    const val APPROX_BYTES = 19_500_000L

    fun url(name: String): String = "https://wago.tools/db2/$name/csv?build=" + (BUILDS[name] ?: BUILD)

    private fun file(dir: File, name: String) = File(dir, "$name.csv")

    fun has(dir: File, name: String): Boolean = file(dir, name).let { it.isFile && it.length() > 0 }

    /** The tables not in [dir] yet. */
    fun missing(dir: File): List<String> = NAMES.filter { !has(dir, it) }

    fun complete(dir: File): Boolean = missing(dir).isEmpty()

    /**
     * Fetches each missing table into [dir] ([open] gives a table's CSV by name; wago.tools by default), written whole as
     * `<Name>.csv.part` and renamed, so a table cut off half way is never read. [onTable] after each one (name, how many
     * of the missing are done). [stop] is asked between tables. Throws what the last try of a table threw.
     */
    fun fetch(
        dir: File,
        open: (String) -> InputStream = ::download,
        stop: () -> Boolean = { false },
        pause: (Long) -> Unit = { Thread.sleep(it) },
        onTable: (name: String, done: Int, of: Int) -> Unit = { _, _, _ -> },
    ) {
        dir.mkdirs()
        val todo = missing(dir)
        todo.forEachIndexed { i, name ->
            if (stop()) return
            var last: Exception? = null
            for (attempt in 0 until 4) {
                try {
                    val part = File(dir, "$name.csv.part")
                    open(name).use { input -> part.outputStream().use { input.copyTo(it, 64 * 1024) } }
                    if (part.length() == 0L) throw IOException("The table $name came empty.")
                    val target = file(dir, name)
                    if (!part.renameTo(target)) { target.delete(); if (!part.renameTo(target)) throw IOException("The table $name couldn't be saved.") }
                    last = null
                    break
                } catch (e: Exception) {
                    last = e
                    if (stop()) return
                    if (attempt < 3) pause(1000L shl attempt)
                }
            }
            last?.let { throw it }
            onTable(name, i + 1, todo.size)
        }
    }

    fun download(name: String): InputStream {
        val conn = java.net.URI(url(name)).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 120000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Wifecraft")
        if (conn.responseCode != 200) { conn.disconnect(); throw IOException("wago.tools answered ${conn.responseCode} for the table $name.") }
        return conn.inputStream
    }
}
