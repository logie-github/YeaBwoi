// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.net.Uri
import com.logie.warcraft.addons.AddonRecord
import com.logie.warcraft.addons.AddonRecords
import com.logie.warcraft.addons.Warperia
import com.logie.warcraft.client.AddonLayout
import com.logie.warcraft.client.TouchAddon
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * A game's add-ons: its client, warperia.com's section for it, and how its files are changed. The
 * 3.3.5a game's ([WRATH]) as the app has always done; Turtle WoW's (com.logie.warcraft.turtle) in
 * its own client, through its folder guard.
 */
interface AddonGame {
    val flavor: Warperia.Flavor

    /** The interface number add-ons made for this client carry ("30300" for 3.3.5a). */
    val interfaceVersion: String

    /** Add-on folders that are the app's own, not the user's to manage. */
    val ownFolders: Set<String>

    fun hasClient(context: Context): Boolean

    fun client(context: Context): File

    /** Where what came from warperia.com is recorded. */
    fun recordsFile(context: Context): File

    /** Where a download or a picked zip waits while it's installed. */
    fun temp(context: Context): File

    /** [file] may be written (an add-on's file, the records); returns it. */
    fun check(context: Context, file: File): File

    fun mkdirs(context: Context, folder: File)

    /** Deletes [target] (an add-on's folder, a file) and everything in it. */
    fun delete(context: Context, target: File): Boolean

    /** Moves [from] to [to], which isn't there (an add-on turned on or off). */
    fun move(context: Context, from: File, to: File): Boolean

    /** Puts the finished [temp] in place of [file]. */
    fun replace(context: Context, temp: File, file: File): Boolean

    /** What to say when there's no client to put add-ons in. */
    fun noClientMessage(context: Context): String

    /** The 3.3.5a game: files/client, the records in files/addons, the files changed as they always were. */
    object WRATH : AddonGame {
        override val flavor = Warperia.Flavor.WOTLK
        override val interfaceVersion = AddonLayout.INTERFACE
        override val ownFolders = setOf(TouchAddon.FOLDER.lowercase())
        override fun hasClient(context: Context) = WowFiles.hasClient(context)
        override fun client(context: Context) = WowFiles.client(context)
        override fun recordsFile(context: Context) = File(context.filesDir, "addons/warperia.tsv")
        override fun temp(context: Context): File = context.cacheDir
        override fun check(context: Context, file: File) = file
        override fun mkdirs(context: Context, folder: File) {
            folder.mkdirs()
        }
        override fun delete(context: Context, target: File) = target.deleteRecursively()
        override fun move(context: Context, from: File, to: File) = from.renameTo(to)
        override fun replace(context: Context, temp: File, file: File) = temp.renameTo(file)
        override fun noClientMessage(context: Context): String = context.getString(R.string.wow_addons_no_client)
    }
}

/**
 * A game's add-ons ([AddonGame]; the 3.3.5a game's unless another is named), in the client's `Interface/AddOns`: any the user picks, from warperia.com
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
        /** Made for this version of the game (3.3.5a, or Turtle's 1.12); others load too (the app turns the check off). */
        val forThisGame: Boolean,
        /** In `AddOns`, where the game loads it; else turned off. */
        val on: Boolean,
    )

    class NoAddonException : IOException("No add-on in this zip")

    fun folder(context: Context, game: AddonGame = AddonGame.WRATH) = File(game.client(context), "Interface/AddOns")

    /** Where turned-off add-ons wait, next to `AddOns`. */
    private fun offFolder(context: Context, game: AddonGame) = File(game.client(context), "Interface/AddOns-off")

    fun list(context: Context, game: AddonGame = AddonGame.WRATH): List<Addon> =
        (read(folder(context, game), on = true, game) + read(offFolder(context, game), on = false, game).filter { off ->
            // A copy in both places: the one the game loads counts.
            !File(folder(context, game), off.folder).isDirectory
        }).sortedBy { it.title.lowercase() }

    private fun read(dir: File, on: Boolean, game: AddonGame): List<Addon> =
        // The app's own add-ons (the touch controls', the bots') aren't the user's to manage.
        dir.listFiles { file -> file.isDirectory && file.name.lowercase() !in game.ownFolders }.orEmpty()
            .mapNotNull { addon ->
                val toc = addon.listFiles { file -> file.isFile && file.name.equals("${addon.name}.toc", ignoreCase = true) }
                    ?.firstOrNull() ?: return@mapNotNull null
                val fields = runCatching { AddonLayout.tocFields(toc.readText()) }.getOrDefault(emptyMap())
                Addon(
                    folder = addon.name,
                    title = fields["Title"]?.takeIf { it.isNotBlank() } ?: addon.name,
                    version = fields["Version"]?.takeIf { it.isNotBlank() },
                    forThisGame = fields["Interface"]?.trim() == game.interfaceVersion,
                    on = on,
                )
            }

    /** Installs the add-ons in the zip at [uri]; returns their folders. Background thread. */
    fun install(context: Context, uri: Uri, game: AddonGame = AddonGame.WRATH): List<String> {
        val copy = game.check(context, File(game.temp(context).also { game.mkdirs(context, it) }, "addon.zip"))
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copy.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("Couldn't open the file")
            return installZip(context, copy, game)
        } finally {
            game.delete(context, copy)
        }
    }

    /**
     * Installs the add-ons in [zip]; returns their folders. Each goes where its older copy is: an
     * add-on that was turned off stays off. Background thread.
     */
    fun installZip(context: Context, zip: File, game: AddonGame = AddonGame.WRATH): List<String> {
        val addOns = folder(context, game)
        val off = offFolder(context, game)
        ZipFile(zip).use { file ->
            val entries = file.entries().toList().filterNot { it.isDirectory }
            val plan = AddonLayout.plan(entries.map { it.name })
            if (plan.folders.isEmpty()) throw NoAddonException()
            game.mkdirs(context, addOns)
            val places = plan.folders.associateWith { name ->
                if (File(off, name).isDirectory && !File(addOns, name).isDirectory) off else addOns
            }
            // Each add-on whole: older copies go first, so files it no longer has don't linger.
            for (name in plan.folders) {
                game.delete(context, File(addOns, name))
                game.delete(context, File(off, name))
            }
            for (entry in entries) {
                val target = plan.targets[entry.name.replace('\\', '/').trimStart('/')] ?: continue
                val place = places[target.substringBefore('/')] ?: continue
                val out = File(place, target)
                if (!out.canonicalPath.startsWith(place.canonicalPath + File.separator)) continue
                out.parentFile?.let { game.mkdirs(context, it) }
                file.getInputStream(entry).use { input -> game.check(context, out).outputStream().use { input.copyTo(it) } }
            }
            return plan.folders
        }
    }

    /** Turns an add-on on (the game loads it) or off (it waits outside `AddOns`). */
    fun setOn(context: Context, folder: String, on: Boolean, game: AddonGame = AddonGame.WRATH): Boolean {
        val (fromPlace, toPlace) = if (on) offFolder(context, game) to folder(context, game) else folder(context, game) to offFolder(context, game)
        val from = File(fromPlace, folder)
        val to = File(toPlace, folder)
        if (!isIn(from, fromPlace) || !isIn(to, toPlace)) return false
        if (!from.isDirectory) return to.isDirectory
        to.parentFile?.let { game.mkdirs(context, it) }
        game.delete(context, to)
        return game.move(context, from, to)
    }

    fun delete(context: Context, folder: String, game: AddonGame = AddonGame.WRATH): Boolean {
        var gone = false
        for (place in listOf(folder(context, game), offFolder(context, game))) {
            val dir = File(place, folder)
            if (isIn(dir, place) && dir.exists()) gone = game.delete(context, dir) || gone
        }
        return gone
    }

    /** The add-on's folder is there, on or off. */
    fun exists(context: Context, folder: String, game: AddonGame = AddonGame.WRATH): Boolean =
        File(folder(context, game), folder).isDirectory || File(offFolder(context, game), folder).isDirectory

    /** [dir] is a folder right inside [place], not anywhere else a name like `..` leads. */
    private fun isIn(dir: File, place: File): Boolean =
        dir.canonicalFile.parentFile?.path == place.canonicalPath

    // What came from warperia.com ----------------------------------------------------------------

    @Synchronized
    fun records(context: Context, game: AddonGame = AddonGame.WRATH): List<AddonRecord> =
        game.recordsFile(context).takeIf { it.isFile }?.let { AddonRecords.parse(it.readText()) }.orEmpty()

    /** Changes the records in one go, so changes made at the same time don't undo each other. */
    @Synchronized
    fun updateRecords(context: Context, game: AddonGame = AddonGame.WRATH, change: (List<AddonRecord>) -> List<AddonRecord>) {
        val before = records(context, game)
        val after = change(before)
        if (after != before) saveRecords(context, game, after)
    }

    @Synchronized
    private fun saveRecords(context: Context, game: AddonGame, records: List<AddonRecord>) {
        val file = game.recordsFile(context)
        file.parentFile?.let { game.mkdirs(context, it) }
        val temp = game.check(context, File(file.path + ".new"))
        temp.writeText(AddonRecords.format(records))
        if (!game.replace(context, temp, file)) throw IOException("Couldn't save the add-ons list")
    }
}
