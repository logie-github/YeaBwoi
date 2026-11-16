package com.logie.wifecraft.editor

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Projects on disk: dir/<id>/project.json, written atomically (a temp file, then a rename). */
class ProjectStore(private val dir: File) {
    private fun fileOf(id: String): File {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(id)) { "Bad project id \"$id\"" }
        return File(File(dir, id), "project.json")
    }

    /** Every readable project, the most recently saved first; a damaged file is skipped. */
    fun list(): List<Project> {
        val folders = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return folders.mapNotNull { f -> File(f, "project.json").takeIf { it.isFile } }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f -> runCatching { Project.fromJson(JSONObject(f.readText())) }.getOrNull() }
    }

    /** When project [id] was last saved (ms since 1970), 0 when it isn't there. */
    fun modified(id: String): Long = runCatching { fileOf(id).lastModified() }.getOrDefault(0L)

    fun load(id: String): Project? {
        val f = fileOf(id)
        if (!f.isFile) return null
        return runCatching { Project.fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun save(p: Project) {
        val f = fileOf(p.id)
        f.parentFile!!.mkdirs()
        val tmp = File(f.parentFile, "project.json.tmp")
        tmp.writeText(p.toJson().toString(1))
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** The library's favourites and recent picks of project [id] (dir/<id>/prefs.json); none when it isn't there or can't be read. */
    fun prefs(id: String): LibraryPrefs {
        val f = File(fileOf(id).parentFile, "prefs.json")
        if (!f.isFile) return LibraryPrefs()
        return runCatching { LibraryPrefs.fromJson(JSONObject(f.readText())) }.getOrDefault(LibraryPrefs())
    }

    /** Keeps [prefs] for project [id] (written atomically, as the project is). */
    fun savePrefs(id: String, prefs: LibraryPrefs) {
        val dirOf = fileOf(id).parentFile!!
        dirOf.mkdirs()
        val f = File(dirOf, "prefs.json")
        val tmp = File(dirOf, "prefs.json.tmp")
        tmp.writeText(prefs.toJson().toString(1))
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(id: String) {
        fileOf(id).parentFile!!.deleteRecursively()
    }

    /** A new project with one empty scene on the plain stage, saved. */
    fun newProject(title: String): Project {
        val p = Project(
            id = "p" + UUID.randomUUID().toString().replace("-", "").take(12),
            title = title, cast = emptyList(),
            scenes = listOf(Scene("scene1", "Scene 1", Stage("stage1", "Studio", "studio", "day"), emptyList())),
            start = "scene1",
        )
        save(p)
        return p
    }
}

/**
 * The placement library's own lists of a project: the entries starred ([favourites], in the order they were starred) and
 * the last [RECENT_MAX] placed ([recent], the newest first), by the library's keys ("p:<file>", "c:<id>", "n:<actor>"...).
 */
data class LibraryPrefs(val favourites: List<String> = emptyList(), val recent: List<String> = emptyList()) {
    fun isFavourite(key: String): Boolean = key in favourites
    /** Starred, or unstarred when it was. */
    fun toggleFavourite(key: String): LibraryPrefs =
        if (key in favourites) copy(favourites = favourites - key) else copy(favourites = favourites + key)
    /** [key] was placed: it goes to the front of Recent (once), the oldest beyond [RECENT_MAX] dropped. */
    fun used(key: String): LibraryPrefs = copy(recent = (listOf(key) + recent.filter { it != key }).take(RECENT_MAX))

    fun toJson(): JSONObject = JSONObject()
        .put("favourites", org.json.JSONArray(favourites))
        .put("recent", org.json.JSONArray(recent))

    companion object {
        const val RECENT_MAX = 30
        fun fromJson(o: JSONObject): LibraryPrefs {
            fun list(k: String): List<String> {
                val a = o.optJSONArray(k) ?: return emptyList()
                return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }.distinct()
            }
            return LibraryPrefs(list("favourites"), list("recent").take(RECENT_MAX))
        }
    }
}
