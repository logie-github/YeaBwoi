// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import com.logie.warcraft.client.CustomRaceList
import com.logie.warcraft.client.ZipDownload
import java.io.File
import java.io.IOException

/**
 * The custom races switch (Goblin, Worgen and more: [CustomRaceList]). On, the app puts the races'
 * package in: its client part into the client's `Data/patch-Z.MPQ` folder, its server part (the
 * game's tables with the races, the world database's SQL) into `files/races`, with the mark `on`
 * that has the server add them when it starts (ctl.sh races). Off, the client folder is set aside
 * (`.off`) and the mark goes, and the server's next start makes the world database again without
 * them; characters of the races stay, left out of the character list until the races are back.
 *
 * The package is a release of this repository's own (BuildConfig.WOW_RACES_TAG), downloaded the
 * way updates are. Applied by [ClientMods.apply], in [ImportService]'s background job.
 */
object CustomRaces {
    const val CLIENT_ZIP = "WarcraftRaces-client.zip"
    const val SERVER_ZIP = "WarcraftRaces-server.zip"

    private const val PREFS = "mods"
    private const val KEY_WANTED = "races"
    private const val KEY_INSTALLED = "races_installed"
    /** The release whose client files are in the client (in place or set aside). */
    private const val KEY_CLIENT = "races_client"
    private const val KEY_SERVER = "races_server"
    /** The release whose client files are coming in (`.part`). */
    private const val KEY_PART = "races_part"
    /** Off, the files are kept to come back without a download (until the user deletes them). */
    private const val KEY_KEEP = "races_keep"

    /** Whether this build has a races package to download. */
    val isAvailable: Boolean get() = BuildConfig.WOW_RACES_TAG.isNotEmpty() && WowUpdater.isConfigured

    fun isWanted(context: Context): Boolean = prefs(context).getBoolean(KEY_WANTED, false)

    /** Whether the races are in, as of the last [apply]. */
    fun isInstalled(context: Context): Boolean = prefs(context).getBoolean(KEY_INSTALLED, false)

    fun setWanted(context: Context, wanted: Boolean) {
        prefs(context).edit().putBoolean(KEY_WANTED, wanted).putBoolean(KEY_KEEP, true).apply()
    }

    /** Whether [apply] has something to do. */
    fun isApplied(context: Context): Boolean {
        val prefs = prefs(context)
        if (!isWanted(context)) return !isInstalled(context) && (prefs.getBoolean(KEY_KEEP, true) || !hasFilesAside(context))
        val tag = BuildConfig.WOW_RACES_TAG
        return isInstalled(context) && prefs.getString(KEY_CLIENT, null) == tag && prefs.getString(KEY_SERVER, null) == tag
    }

    /** Whether the server has to stop for [apply]: the races go in or out. */
    fun changesServer(context: Context): Boolean =
        isWanted(context) != isInstalled(context) ||
            (isWanted(context) && prefs(context).getString(KEY_SERVER, null) != BuildConfig.WOW_RACES_TAG)

    /** Off, with the files kept for later. */
    fun hasFilesAside(context: Context): Boolean =
        !isWanted(context) && (aside(context).exists() || part(context).exists() || File(WowFiles.races(context), "world.sql").exists())

    /** The kept files go, at the next [apply]. */
    fun deleteFiles(context: Context) {
        prefs(context).edit().putBoolean(KEY_KEEP, false).apply()
    }

    /** The races, as the downloaded package lists them (or the list it's made from). */
    fun races(context: Context): List<CustomRaceList.Race> =
        File(WowFiles.races(context), "races.txt").takeIf { it.isFile }
            ?.let { CustomRaceList.parse(it.readText()) }?.takeIf { it.isNotEmpty() } ?: CustomRaceList.RACES

    /** A new client came in (Game files): its races are what [apply] makes of it. */
    fun clientReplaced(context: Context) {
        prefs(context).edit().remove(KEY_CLIENT).remove(KEY_PART).putBoolean(KEY_INSTALLED, false).apply()
        File(WowFiles.races(context), "on").delete()
    }

    private fun data(context: Context) = File(WowFiles.client(context), "Data")
    private fun folder(context: Context) = File(data(context), CustomRaceList.PATCH_FOLDER)
    private fun aside(context: Context) = File(data(context), CustomRaceList.PATCH_FOLDER + ".off")
    private fun part(context: Context) = File(data(context), CustomRaceList.PATCH_FOLDER + ".part")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Applying ------------------------------------------------------------------------------------

    /** Brings the client and the server's files in line with the switch. Background thread. */
    fun apply(context: Context, onProgress: (Float?, String?) -> Unit) {
        val prefs = prefs(context)
        val folder = folder(context)
        val aside = aside(context)
        val part = part(context)
        val server = WowFiles.races(context)
        val ours = prefs.getString(KEY_CLIENT, null) != null

        // A folder of the user's own by that name is kept, and stays out of the way.
        if (folder.exists() && !ours) {
            var kept = File(data(context), CustomRaceList.PATCH_FOLDER + ".user")
            var n = 1
            while (kept.exists()) kept = File(data(context), CustomRaceList.PATCH_FOLDER + ".user" + ++n)
            if (!folder.renameTo(kept)) throw Imports.ImportException(context.getString(R.string.wow_import_error_write))
        }

        if (!isWanted(context)) {
            if (folder.isDirectory) {
                aside.deleteRecursively()
                if (!folder.renameTo(aside)) throw Imports.ImportException(context.getString(R.string.wow_import_error_write))
            }
            File(server, "on").delete()
            prefs.edit().putBoolean(KEY_INSTALLED, false).apply()
            if (!prefs.getBoolean(KEY_KEEP, true)) {
                onProgress(null, context.getString(R.string.wow_races_deleting))
                aside.deleteRecursively()
                part.deleteRecursively()
                // Which achievements are the races' stays, for the server to keep its records of them.
                server.listFiles()?.filter { it.name != "achievements.txt" }?.forEach { it.deleteRecursively() }
                prefs.edit().remove(KEY_CLIENT).remove(KEY_SERVER).remove(KEY_PART).putBoolean(KEY_KEEP, true).apply()
            }
            return
        }

        if (!isAvailable) throw Imports.ImportException(context.getString(R.string.wow_races_error_unavailable))
        val tag = BuildConfig.WOW_RACES_TAG
        val serverReady = prefs.getString(KEY_SERVER, null) == tag && File(server, "world.sql").isFile && File(server, "VERSION").isFile
        val clientReady = prefs.getString(KEY_CLIENT, null) == tag && (folder.isDirectory || aside.isDirectory)
        if (!serverReady || !clientReady) {
            onProgress(null, context.getString(R.string.wow_races_finding))
            val assets = network(context) { WowUpdater.releaseAssets(tag) }
            fun asset(name: String) = assets.firstOrNull { it.name == name }
                ?: throw Imports.ImportException(context.getString(R.string.wow_races_error_missing, name, tag))
            if (!serverReady) {
                val staging = File(server.path + ".download")
                staging.deleteRecursively()
                extract(context, asset(SERVER_ZIP), staging, context.getString(R.string.wow_races_server_part), onProgress)
                server.deleteRecursively()
                if (!staging.renameTo(server)) throw Imports.ImportException(context.getString(R.string.wow_import_error_write))
                prefs.edit().putString(KEY_SERVER, tag).apply()
            }
            if (!clientReady) {
                // What came of an earlier try at this same package carries on.
                if (prefs.getString(KEY_PART, null) != tag) {
                    part.deleteRecursively()
                    prefs.edit().putString(KEY_PART, tag).apply()
                }
                extract(context, asset(CLIENT_ZIP), part, context.getString(R.string.wow_races_client_part), onProgress)
                folder.deleteRecursively()
                aside.deleteRecursively()
                if (!part.renameTo(folder)) throw Imports.ImportException(context.getString(R.string.wow_import_error_write))
                prefs.edit().putString(KEY_CLIENT, tag).remove(KEY_PART).apply()
            }
        }
        if (!folder.isDirectory && aside.isDirectory && !aside.renameTo(folder)) {
            throw Imports.ImportException(context.getString(R.string.wow_import_error_write))
        }
        File(server, "on").createNewFile()
        prefs.edit().putBoolean(KEY_INSTALLED, true).apply()
    }

    /** [asset]'s files into [folder]; a dropped connection carries on from the file it was on. */
    private fun extract(
        context: Context,
        asset: WowUpdater.Asset,
        folder: File,
        label: String,
        onProgress: (Float?, String?) -> Unit,
    ) {
        val zip = ZipDownload(ZipDownload.Http(asset.url, WowUpdater.userAgent, WowUpdater::assetHeaders)) { Imports.cancelled }
        var tries = 0
        var spaceChecked = false
        var reported = 0L
        while (true) {
            try {
                onProgress(null, label)
                val entries = zip.directory()
                if (!spaceChecked) {
                    Imports.checkSpace(context, entries.sumOf { it.size } - WowFiles.sizeOf(folder))
                    spaceChecked = true
                }
                val total = zip.totalOf(entries).coerceAtLeast(1)
                zip.extractAll(entries, folder) { done ->
                    if (done - reported >= 1 shl 20 || done == total) {
                        reported = done
                        onProgress(done.toFloat() / total, "$label · ${shortSize(context, done)} / ${shortSize(context, total)}")
                    }
                }
                return
            } catch (e: ZipDownload.Cancelled) {
                throw Imports.ImportException("cancelled")
            } catch (e: Imports.ImportException) {
                throw e
            } catch (e: IOException) {
                if (++tries >= 6) {
                    throw Imports.ImportException(context.getString(R.string.wow_download_error_network, e.message ?: e.javaClass.simpleName))
                }
                Thread.sleep(3_000L * tries)
            }
        }
    }

    private fun <T> network(context: Context, block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw Imports.ImportException(context.getString(R.string.wow_download_error_network, e.message ?: e.javaClass.simpleName))
        }

    private fun shortSize(context: Context, bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
}
