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
