// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

import java.io.File
import java.io.IOException

/**
 * Addons the realm package brings for the client: TortoiseBotsManager (`/tbm`), the bots' control
 * panel, in the package's `addons/`. [install] puts each into the client's `Interface/AddOns`
 * (Turtle's own copy of the client, never anything else), through [SafeFs]: copied beside it first,
 * then swapped in, and only when it differs from what's there, so a newer package brings a newer
 * addon and an unchanged one costs a comparison.
 */
object TurtleAddons {
    /** The addon folder in the client's AddOns, whatever the case of `Interface/AddOns` in this copy. */
    fun target(client: File, name: String): File =
        File(ClientManifest.find(client, "Interface/AddOns") ?: File(client, "Interface/AddOns"), name)

    /**
     * Puts every addon folder in [addons] (the package's) into [client]'s AddOns. Returns the
     * names it put in or updated. [fs] must have [client] in its scope.
     */
    fun install(addons: File, client: File, fs: SafeFs): List<String> {
        val changed = mutableListOf<String>()
        for (source in addons.listFiles()?.sortedBy { it.name }.orEmpty()) {
            if (!source.isDirectory || !ClientManifest.isSafePath(source.name)) continue
            val target = target(client, source.name)
            if (same(source, target)) continue
            val staging = File(target.parentFile, "." + source.name + ".new")
            fs.delete(staging)
            fs.mkdirs(staging)
            copyTree(source, staging, fs)
            fs.replace(staging, target)
            changed += source.name
        }
        return changed
    }

    /** The two folders hold the same files with the same bytes. */
    fun same(a: File, b: File): Boolean {
        if (!b.isDirectory) return false
        val left = files(a)
        val right = files(b)
        if (left.keys != right.keys) return false
        return left.all { (path, file) -> right[path]!!.let { it.length() == file.length() && it.readBytes().contentEquals(file.readBytes()) } }
    }

    private fun files(root: File): Map<String, File> =
        root.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(root).invariantSeparatorsPath }

    private fun copyTree(from: File, to: File, fs: SafeFs) {
        for (file in from.listFiles().orEmpty()) {
            val target = fs.check(File(to, file.name))
            when {
                java.nio.file.Files.isSymbolicLink(file.toPath()) -> throw IOException("Link in an addon: $file")
                file.isDirectory -> {
                    fs.mkdirs(target)
                    copyTree(file, target, fs)
                }
                else -> file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
