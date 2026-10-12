// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.net.Uri
import com.logie.warcraft.client.AddonLayout
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * The game's add-ons, in the client's `Interface/AddOns`: any the user picks, from zips (none
 * come with the app). A zip is copied into the app, its add-ons found however it's packed
 * ([AddonLayout]) and put in place, each replacing an older copy of itself. The game loads them
 * the next time it starts (or `/reload` in the game).
 */
object Addons {
    data class Addon(
        val folder: String,
        val title: String,
        val version: String?,
        /** Made for this version of the game (3.3.5a); others load too (the app turns the check off). */
        val forThisGame: Boolean,
    )

    class NoAddonException : IOException("No add-on in this zip")

    fun folder(context: Context) = File(WowFiles.client(context), "Interface/AddOns")

    fun list(context: Context): List<Addon> =
        folder(context).listFiles { file -> file.isDirectory }.orEmpty()
            .mapNotNull { dir ->
                val toc = dir.listFiles { file -> file.isFile && file.name.equals("${dir.name}.toc", ignoreCase = true) }
                    ?.firstOrNull() ?: return@mapNotNull null
                val fields = runCatching { AddonLayout.tocFields(toc.readText()) }.getOrDefault(emptyMap())
                Addon(
                    folder = dir.name,
                    title = fields["Title"]?.takeIf { it.isNotBlank() } ?: dir.name,
                    version = fields["Version"]?.takeIf { it.isNotBlank() },
                    forThisGame = fields["Interface"]?.trim() == AddonLayout.INTERFACE,
                )
            }
            .sortedBy { it.title.lowercase() }

    /** Installs the add-ons in the zip at [uri]; returns their folders. Background thread. */
    fun install(context: Context, uri: Uri): List<String> {
        val copy = File(context.cacheDir, "addon.zip")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copy.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("Couldn't open the file")
            return installZip(context, copy)
        } finally {
            copy.delete()
        }
    }

    private fun installZip(context: Context, zip: File): List<String> {
        val addOns = folder(context)
        ZipFile(zip).use { file ->
            val entries = file.entries().toList().filterNot { it.isDirectory }
            val plan = AddonLayout.plan(entries.map { it.name })
            if (plan.folders.isEmpty()) throw NoAddonException()
            addOns.mkdirs()
            // Each add-on whole: an older copy goes first, so files it no longer has don't linger.
            for (name in plan.folders) File(addOns, name).deleteRecursively()
            val root = addOns.canonicalPath + File.separator
            for (entry in entries) {
                val target = plan.targets[entry.name.replace('\\', '/').trimStart('/')] ?: continue
                val out = File(addOns, target)
                if (!out.canonicalPath.startsWith(root)) continue
                out.parentFile?.mkdirs()
                file.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            return plan.folders
        }
    }

    fun delete(context: Context, folder: String): Boolean {
        val dir = File(folder(context), folder)
        return dir.parentFile?.canonicalPath == folder(context).canonicalPath && dir.deleteRecursively()
    }
}
