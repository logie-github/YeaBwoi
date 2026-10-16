// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * How Turtle WoW's realm package is unpacked. scripts/package-tortoise.sh makes it with
 * `tar -cf - server | xz`: every entry is `server/...`, folders' names end in `/`. Each one is
 * written into a staging folder through [SafeFs]; nothing may land outside it.
 */
object TurtlePackageFormat {
    class BadEntry(message: String) : IOException(message)

    /**
     * Where entry [name] goes inside `server/`: "" for `server/` itself, null for a name the app
     * refuses (not under `server/`, absolute, with `..`, `.` or empty parts).
     */
    fun relative(name: String): String? {
        val path = name.removePrefix("./")
        if (path == "server" || path == "server/") return ""
        if (!path.startsWith("server/")) return null
        val inside = path.removePrefix("server/").removeSuffix("/")
        if (inside.isEmpty() || '\\' in inside || '\u0000' in inside) return null
        return inside.takeIf { p -> p.split('/').none { it.isEmpty() || it == "." || it == ".." } }
    }

    /** A link the package may hold: to a file beside it or below it (a library's other names). */
    fun isSafeLink(link: String): Boolean =
        link.isNotEmpty() && !link.startsWith("/") && link.split('/').none { it.isEmpty() || it == ".." }

    /**
     * Unpacks the package read from [input] (tar.xz) into [into], which [staging] scopes. [symlink]
     * makes a link (the platform's call); [cancelled] is asked before each entry, [onEntry] told
     * after it.
     */
    fun unpack(
        input: InputStream,
        into: File,
        staging: SafeFs,
        symlink: (target: String, link: File) -> Unit,
        cancelled: () -> Boolean = { false },
        onEntry: () -> Unit = {},
    ) {
        TarArchiveInputStream(XZCompressorInputStream(input)).use { tar ->
            while (true) {
                if (cancelled()) throw VerifiedDownload.Cancelled()
                val entry = tar.nextEntry ?: break
                val relative = relative(entry.name) ?: throw BadEntry("Bad path in the package: ${entry.name}")
                if (relative.isNotEmpty()) {
                    val target = staging.check(File(into, relative))
                    when {
                        entry.isDirectory -> staging.mkdirs(target)
                        entry.isSymbolicLink -> {
                            if (!isSafeLink(entry.linkName)) throw BadEntry("Bad link in the package: ${entry.name}")
                            target.parentFile?.let { staging.mkdirs(it) }
                            symlink(entry.linkName, target)
                        }
                        // The package has none: a hard link's entry has no data of its own.
                        entry.isLink -> throw BadEntry("Hard link in the package: ${entry.name}")
                        entry.isFile -> {
                            target.parentFile?.let { staging.mkdirs(it) }
                            target.outputStream().use { tar.copyTo(it, 1 shl 16) }
                            if (entry.mode and 0b001_000_000 != 0) target.setExecutable(true, true)
                        }
                        else -> throw BadEntry("Unexpected entry in the package: ${entry.name}")
                    }
                }
                onEntry()
            }
        }
    }
}
