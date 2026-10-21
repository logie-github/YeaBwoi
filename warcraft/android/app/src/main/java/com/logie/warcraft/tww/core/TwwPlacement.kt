// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww.core

import com.logie.warcraft.turtle.core.SafeFs
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Recoverable placement of validated components, including an independently staged MMAPS copy. */
object TwwPlacement {
    private const val JOURNAL = "install.transaction"

    fun pending(root: File): Boolean = File(root, JOURNAL).exists()

    /** Failure keeps the previous installation. A process death is rolled back by [recover]. */
    fun replace(root: File, changes: List<Pair<File, File>>) {
        require(changes.isNotEmpty()) { "No components to install" }
        val fs = SafeFs(root)
        recover(root)
        val journal = File(root, JOURNAL)
        val targets = changes.map { it.second.toPath().toAbsolutePath().normalize() }
        if (targets.distinct().size != targets.size || targets.any { left -> targets.any { right -> left != right && left.startsWith(right) } }) {
            throw IOException("Installation targets overlap")
        }
        val records = changes.map { (source, target) ->
            fs.check(source)
            fs.check(target)
            if (Files.isSymbolicLink(source.toPath())) throw IOException("Installation source is a symbolic link")
            val from = source.toPath().toAbsolutePath().normalize()
            if (targets.any { destination -> from.startsWith(destination) || destination.startsWith(from) }) throw IOException("Installation source overlaps a target")
            val old = fs.check(File(target.path + ".tww-old"))
            if (old.exists()) fs.delete(old)
            listOf(source.relativeTo(root).invariantSeparatorsPath, target.relativeTo(root).invariantSeparatorsPath,
                old.relativeTo(root).invariantSeparatorsPath, target.exists().toString())
        }
        fs.writeAtomically(journal, records.joinToString("\n") { it.joinToString("\t") })
        try {
            changes.forEachIndexed { index, (source, target) ->
                target.parentFile?.let { fs.mkdirs(it) }
                if (target.exists()) fs.move(target, File(root, records[index][2]))
                fs.move(source, target)
            }
        } catch (e: Exception) {
            try { recover(root) } catch (rollback: Exception) { e.addSuppressed(rollback) }
            throw e
        }
        // Removing the journal commits. Backups may then be cleaned independently.
        fs.delete(journal)
        records.forEach { runCatching { fs.delete(File(root, it[2])) } }
    }

    fun recover(root: File) {
        val fs = SafeFs(root)
        val journal = File(root, JOURNAL)
        if (!journal.isFile) return
        val entries = journal.readLines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('\t')
            if (parts.size != 4 || parts[3] !in setOf("true", "false")) throw IOException("Installation recovery journal is damaged. Existing backups have been preserved.")
            parts.take(3).forEach { relative ->
                if (relative.startsWith('/') || relative.contains('\\') || relative.split('/').any { it.isEmpty() || it == ".." || it == "." }) throw IOException("Unsafe recovery journal path.")
                fs.check(File(root, relative))
            }
            if (parts.take(3).distinct().size != 3) throw IOException("Installation recovery journal contains overlapping paths")
            parts
        }
        if (entries.isEmpty()) throw IOException("Installation recovery journal is empty. Existing backups have been preserved.")
        for (parts in entries.asReversed()) {
            val source = File(root, parts[0])
            val target = File(root, parts[1])
            val backup = File(root, parts[2])
            if (backup.exists()) { fs.delete(target); fs.move(backup, target) }
            else if (parts[3] == "false" && !source.exists()) fs.delete(target)
        }
        fs.delete(journal)
    }
}
