// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Every delete, move and replace Turtle WoW's code makes on the phone's storage goes through here,
 * and each one names the folder it must stay in ([scope], Turtle's root or a folder inside it):
 *
 * - a path outside [scope], or [scope] itself where only what's inside may go, is refused
 *   ([Refused]) before anything is touched;
 * - so is a path that is, or is inside, one of the other environments' folders ([protected]), even
 *   if a mistake made [scope] wider than it should be;
 * - deleting never follows a link: a link is removed as a link, and what it points to (the game's
 *   drive links in a Wine prefix point at `/`) stays;
 * - paths are compared as written and as resolved, so `..` or a link in the middle can't step out.
 */
class SafeFs(private val scope: File, private val protected: List<File> = emptyList()) {
    class Refused(message: String) : IOException(message)

    private val scopePath: Path = scope.toPath().toAbsolutePath().normalize()

    /** [target] is strictly inside the scope, and in nobody else's folder. Throws [Refused] if not. */
    fun check(target: File, allowScope: Boolean = false): File {
        val path = target.toPath().toAbsolutePath().normalize()
        val inside = path.startsWith(scopePath) && (allowScope || path != scopePath)
        if (!inside) throw Refused("Outside ${scope.path}: ${target.path}")
        // The same as resolved: a link in the scope's own path is fine, a link that leads out isn't.
        val realScope = realPath(scopePath)
        val realParent = realPath(path.parent ?: path)
        val real = realParent.resolve(path.fileName ?: path)
        if (!real.startsWith(realScope) || (!allowScope && real == realScope)) {
            throw Refused("Leads outside ${scope.path}: ${target.path}")
        }
        // In another game's folder, or holding one (deleting it would take that folder with it).
        for (other in protected) {
            val otherPath = other.toPath().toAbsolutePath().normalize()
            val otherReal = realPath(otherPath)
            if (path.startsWith(otherPath) || otherPath.startsWith(path) || real.startsWith(otherReal) || otherReal.startsWith(real)) {
                throw Refused("Belongs to another game: ${target.path}")
            }
        }
        return target
    }

    /**
     * Deletes [target] and everything in it, without following links; nothing if it isn't there.
     * The scope itself can't be deleted this way ([deleteContents] empties it).
     */
    fun delete(target: File) {
        check(target)
        val path = target.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path)
            return
        }
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Links come here as files (walkFileTree doesn't follow them): the link goes.
                check(file.toFile())
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                check(file.toFile())
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                check(dir.toFile())
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Deletes what's inside [folder] (the scope or a folder in it), keeping the folder. */
    fun deleteContents(folder: File) {
        check(folder, allowScope = true)
        val path = folder.toPath()
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(path).use { entries -> entries.forEach { delete(it.toFile()) } }
    }

    /**
     * Puts [source] in place of [target] (both in the scope): [target] is set aside first and
     * deleted once [source] is in, so a failure leaves the old one in place.
     */
    fun replace(source: File, target: File) {
        check(source)
        check(target)
        val aside = File(target.path + ".old")
        check(aside)
        delete(aside)
        val hadTarget = Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (hadTarget) Files.move(target.toPath(), aside.toPath(), StandardCopyOption.ATOMIC_MOVE)
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            if (hadTarget) Files.move(aside.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            throw e
        }
        delete(aside)
    }

    /** Moves [source] to [target], both in the scope ([target] must not be there). */
    fun move(source: File, target: File) {
        check(source)
        check(target)
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Moves [source], inside [from]'s scope (another guard's), to [target] inside this one's: one
     * rename, on the same storage only (an IOException otherwise, nothing moved).
     */
    fun moveFrom(from: SafeFs, source: File, target: File) {
        from.check(source)
        check(target)
        Files.move(source.toPath(), target.toPath())
    }

    /** Makes [folder] (and its parents inside the scope). */
    fun mkdirs(folder: File): File {
        check(folder, allowScope = true)
        Files.createDirectories(folder.toPath())
        return folder
    }

    /** Writes [text] to [file] through a temporary file and a rename, so it's never half written. */
    fun writeAtomically(file: File, text: String) {
        check(file)
        val tmp = File(file.path + ".tmp")
        check(tmp)
        file.parentFile?.let { Files.createDirectories(it.toPath()) }
        tmp.writeText(text)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** A folder inside the scope, as its own scope (same protected folders). */
    fun within(folder: File): SafeFs = SafeFs(check(folder, allowScope = true), protected)

    private companion object {
        /** The path with every link along it resolved, as far as it exists. */
        fun realPath(path: Path): Path {
            var existing: Path? = path
            val rest = ArrayDeque<Path>()
            while (existing != null && !Files.exists(existing)) {
                existing.fileName?.let { rest.addFirst(it) }
                existing = existing.parent
            }
            var real = existing?.toRealPath() ?: path.root ?: path
            for (part in rest) real = real.resolve(part)
            return real.normalize()
        }
    }
}
