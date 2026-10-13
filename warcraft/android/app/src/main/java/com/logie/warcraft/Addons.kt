// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.net.Uri
import com.logie.warcraft.addons.AddonRecord
import com.logie.warcraft.addons.AddonRecords
import com.logie.warcraft.client.AddonLayout
import com.logie.warcraft.client.TouchAddon
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * The game's add-ons, in the client's `Interface/AddOns`: any the user picks, from warperia.com
 * ([AddonStore]) or from zips (none come with the app). A zip's add-ons are found however it's
 * packed ([AddonLayout]) and put in place, each replacing an older copy of itself. An add-on that's
 * turned off waits in `Interface/AddOns-off`, where the game doesn't look. The game loads them the
 * next time it starts (or `/reload` in the game).
 */
object Addons {
    data class Addon(
        val folder: String,
        val title: String,
        val version: String?,
        /** Made for this version of the game (3.3.5a); others load too (the app turns the check off). */
        val forThisGame: Boolean,
        /** In `AddOns`, where the game loads it; else turned off. */
        val on: Boolean,
    )

    class NoAddonException : IOException("No add-on in this zip")

    fun folder(context: Context) = File(WowFiles.client(context), "Interface/AddOns")

    /** Where turned-off add-ons wait, next to `AddOns`. */
    private fun offFolder(context: Context) = File(WowFiles.client(context), "Interface/AddOns-off")

    fun list(context: Context): List<Addon> =
        (read(folder(context), on = true) + read(offFolder(context), on = false).filter { off ->
            // A copy in both places: the one the game loads counts.
            !File(folder(context), off.folder).isDirectory
        }).sortedBy { it.title.lowercase() }

    private fun read(dir: File, on: Boolean): List<Addon> =
        // The app's own add-on (for the touch controls) isn't the user's to manage.
        dir.listFiles { file -> file.isDirectory && !file.name.equals(TouchAddon.FOLDER, ignoreCase = true) }.orEmpty()
            .mapNotNull { addon ->
                val toc = addon.listFiles { file -> file.isFile && file.name.equals("${addon.name}.toc", ignoreCase = true) }
                    ?.firstOrNull() ?: return@mapNotNull null
                val fields = runCatching { AddonLayout.tocFields(toc.readText()) }.getOrDefault(emptyMap())
                Addon(
                    folder = addon.name,
                    title = fields["Title"]?.takeIf { it.isNotBlank() } ?: addon.name,
                    version = fields["Version"]?.takeIf { it.isNotBlank() },
                    forThisGame = fields["Interface"]?.trim() == AddonLayout.INTERFACE,
                    on = on,
                )
            }

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

    /**
     * Installs the add-ons in [zip]; returns their folders. Each goes where its older copy is: an
     * add-on that was turned off stays off. Background thread.
     */
    fun installZip(context: Context, zip: File): List<String> {
        val addOns = folder(context)
        val off = offFolder(context)
        ZipFile(zip).use { file ->
            val entries = file.entries().toList().filterNot { it.isDirectory }
            val plan = AddonLayout.plan(entries.map { it.name })
            if (plan.folders.isEmpty()) throw NoAddonException()
            addOns.mkdirs()
            val places = plan.folders.associateWith { name ->
                if (File(off, name).isDirectory && !File(addOns, name).isDirectory) off else addOns
            }
            // Each add-on whole: older copies go first, so files it no longer has don't linger.
            for (name in plan.folders) {
                File(addOns, name).deleteRecursively()
                File(off, name).deleteRecursively()
            }
            for (entry in entries) {
                val target = plan.targets[entry.name.replace('\\', '/').trimStart('/')] ?: continue
                val place = places[target.substringBefore('/')] ?: continue
                val out = File(place, target)
                if (!out.canonicalPath.startsWith(place.canonicalPath + File.separator)) continue
                out.parentFile?.mkdirs()
                file.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            return plan.folders
        }
    }

    /** Turns an add-on on (the game loads it) or off (it waits outside `AddOns`). */
    fun setOn(context: Context, folder: String, on: Boolean): Boolean {
        val (fromPlace, toPlace) = if (on) offFolder(context) to folder(context) else folder(context) to offFolder(context)
        val from = File(fromPlace, folder)
        val to = File(toPlace, folder)
        if (!isIn(from, fromPlace) || !isIn(to, toPlace)) return false
        if (!from.isDirectory) return to.isDirectory
        to.parentFile?.mkdirs()
        to.deleteRecursively()
        return from.renameTo(to)
    }

    fun delete(context: Context, folder: String): Boolean {
        var gone = false
        for (place in listOf(folder(context), offFolder(context))) {
            val dir = File(place, folder)
            if (isIn(dir, place) && dir.exists()) gone = dir.deleteRecursively() || gone
        }
        return gone
    }

    /** The add-on's folder is there, on or off. */
    fun exists(context: Context, folder: String): Boolean =
        File(folder(context), folder).isDirectory || File(offFolder(context), folder).isDirectory

    /** [dir] is a folder right inside [place], not anywhere else a name like `..` leads. */
    private fun isIn(dir: File, place: File): Boolean =
        dir.canonicalFile.parentFile?.path == place.canonicalPath

    // What came from warperia.com ----------------------------------------------------------------

    private fun recordsFile(context: Context) = File(context.filesDir, "addons/warperia.tsv")

    @Synchronized
    fun records(context: Context): List<AddonRecord> =
        recordsFile(context).takeIf { it.isFile }?.let { AddonRecords.parse(it.readText()) }.orEmpty()

    /** Changes the records in one go, so changes made at the same time don't undo each other. */
    @Synchronized
    fun updateRecords(context: Context, change: (List<AddonRecord>) -> List<AddonRecord>) {
        val before = records(context)
        val after = change(before)
        if (after != before) saveRecords(context, after)
    }

    @Synchronized
    private fun saveRecords(context: Context, records: List<AddonRecord>) {
        val file = recordsFile(context)
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".new")
        temp.writeText(AddonRecords.format(records))
        if (!temp.renameTo(file)) throw IOException("Couldn't save the add-ons list")
    }
}
