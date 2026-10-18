// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import com.logie.warcraft.client.ClientConfig
import com.logie.warcraft.client.HdPatches
import com.logie.warcraft.client.WowExePatch
import com.logie.warcraft.client.ZipDownload
import java.io.File
import java.io.IOException

/**
 * Changes to the user's client chosen in the launcher, put in and taken out again by
 * [ImportService] (a job like the others in [Imports]):
 *
 * - **An HD look** ([HdPatches]): the chosen patches' MPQ files in the client's `Data`, downloaded a
 *   file at a time out of their zips. Files of the client's own with the same names are kept aside
 *   (`.user`) and come back when the look is turned off.
 * - **The executable** ([WowExePatch]): `WowPatched.exe`, a copy of `Wow.exe` that uses 4 GB (and
 *   loads changed interface files), which the game starts with while a look is in, or the realm's
 *   modules' patch ([hasModulePatch]). `Wow.exe` itself is never changed.
 *
 * What's in the client is listed in `files/mods/hd.txt`, so taking things out removes exactly
 * what was put in.
 */
object ClientMods {
    const val PATCHED_EXE = "WowPatched.exe"

    private const val PREFS = "mods"
    private const val KEY_HD = "hd"
    private const val KEY_HD_INSTALLED = "hd_installed"

    /** The look the user chose (applied by [apply]). */
    fun hd(context: Context): HdPatches.Choice = choice(context, KEY_HD)

    /** The look that's in the client. */
    fun hdInstalled(context: Context): HdPatches.Choice = choice(context, KEY_HD_INSTALLED)

    private fun choice(context: Context, key: String): HdPatches.Choice {
        val name = prefs(context).getString(key, null)
        return HdPatches.Choice.entries.firstOrNull { it.name == name } ?: HdPatches.Choice.OFF
    }

    fun setHd(context: Context, choice: HdPatches.Choice) {
        prefs(context).edit().putString(KEY_HD, choice.name).apply()
    }

    /** Whether the client is as chosen. */
    fun isApplied(context: Context): Boolean = hd(context) == hdInstalled(context)

    /** The program the game starts: the patched copy while a look or the modules' patch is in. */
    fun exeName(context: Context): String =
        if ((hdInstalled(context) != HdPatches.Choice.OFF || hasModulePatch(context)) && File(WowFiles.client(context), PATCHED_EXE).isFile) PATCHED_EXE else "Wow.exe"

    /**
     * A module's patch for the game is in its `Data`: the realm's picked modules' (`patch-M.MPQ`, put
     * there by the realm's start: `ctl.sh modules`; mod-worgoblin's, mod-arac's...) or the Skyborne's
     * (`patch-Z.MPQ`, made by mod-skyborne as the world server starts). Their interface files only
     * load in the patched executable.
     */
    fun hasModulePatch(context: Context): Boolean {
        val data = File(WowFiles.client(context), "Data")
        return File(data, "patch-M.MPQ/realm-modules.stamp").isFile || File(data, "patch-Z.MPQ.skyborne").isFile
    }

    /** Makes the patched executable when the modules' patch needs it and it isn't there. Background thread. */
    fun prepareExe(context: Context) {
        val client = WowFiles.client(context)
        if (hasModulePatch(context) && !File(client, PATCHED_EXE).isFile) patchExe(context, client)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Applying ------------------------------------------------------------------------------------

    /** Brings the client in line with the choices. Background thread, in [ImportService]. */
    fun apply(context: Context, onProgress: (Float?, String?) -> Unit) {
        val client = WowFiles.client(context)
        if (!WowFiles.hasClient(context)) throw Imports.ImportException(context.getString(R.string.wow_mods_error_no_client))
        val choice = hd(context)
        LegacyRaces.cleanClient(context)
        val locale = locale(client)
        val wanted = HdPatches.files(choice).associateBy { it.target(locale) }
        val listed = installedFiles(context).toMutableMap()

        // The previous look's files go (the two looks share some names); the client's own come back.
        for ((target, from) in listed.toMap()) {
            if (target in wanted && from == choice) continue
            File(client, target).delete()
            File(client, "$target.off").delete()
            File(client, "$target.download").delete()
            File(client, "$target.download.part").delete()
            File(client, "$target.user").takeIf { it.isFile }?.renameTo(File(client, target))
            listed -= target
            saveInstalled(context, listed)
        }

        // A file that's gone from the client (removed by hand) is fetched again.
        for (target in listed.keys.toList()) {
            if (!File(client, target).isFile && !File(client, "$target.off").isFile) listed -= target
        }
        saveInstalled(context, listed)

        bringBackSetAside(context)

        val missing = wanted.filter { (target, _) -> target !in listed }
        if (missing.isNotEmpty()) {
            val source = HdPatches.source(choice)!!
            download(context, client, choice, source, missing, listed, onProgress)
        }

        if (choice != HdPatches.Choice.OFF) patchExe(context, client) else File(client, PATCHED_EXE).delete()
        prefs(context).edit().putString(KEY_HD_INSTALLED, choice.name).apply()
    }

    /** A new client came in (Game files): none of the changes are in it. */
    fun clientReplaced(context: Context) {
        installedList(context).delete()
        prefs(context).edit().remove(KEY_HD_INSTALLED).apply()
    }

    /**
     * The look's files set aside (`.off`) while the custom races of earlier versions were on come
     * back ([LegacyRaces]).
     */
    fun bringBackSetAside(context: Context) {
        val client = WowFiles.client(context)
        for (target in installedFiles(context).keys) {
            val on = File(client, target)
            val off = File(client, "$target.off")
            if (!on.isFile && off.isFile) off.renameTo(on)
        }
    }

    /** The client's language folder in `Data` (enUS...). */
    fun locale(client: File): String =
        File(client, "Data").listFiles { file -> file.isDirectory && ClientConfig.LOCALE.matches(file.name) }
            ?.map { it.name }?.sorted()?.firstOrNull() ?: "enUS"

    private fun installedList(context: Context) = File(context.filesDir, "mods/hd.txt")

    /** The HD files in the client, and which look each is from: "<look> <path>" a line. */
    private fun installedFiles(context: Context): Map<String, HdPatches.Choice> =
        installedList(context).takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
            val choice = HdPatches.Choice.entries.firstOrNull { it.name == line.substringBefore(' ') } ?: return@mapNotNull null
            line.substringAfter(' ').takeIf { it.isNotBlank() }?.let { it to choice }
        }.toMap()

    private fun saveInstalled(context: Context, files: Map<String, HdPatches.Choice>) {
        val list = installedList(context)
        list.parentFile?.mkdirs()
        list.writeText(files.entries.sortedBy { it.key }.joinToString("") { "${it.value.name} ${it.key}\n" })
    }

    // The executable ------------------------------------------------------------------------------

    /**
     * `WowPatched.exe` from `Wow.exe`: with the signature edits, or, when they don't fit this
     * executable, with 4 GB only.
     */
    private fun patchExe(context: Context, client: File) {
        val original = File(client, "Wow.exe")
        val bytes = original.readBytes()
        var result = WowExePatch.patch(bytes, signatures = true)
        if (result is WowExePatch.Result.Unknown) {
            val again = original.readBytes()
            result = WowExePatch.patch(again, signatures = false)
            if (result is WowExePatch.Result.Unknown) throw Imports.ImportException(context.getString(R.string.wow_mods_error_exe, result.reason))
            write(File(client, PATCHED_EXE), again)
            return
        }
        write(File(client, PATCHED_EXE), bytes)
    }

    private fun write(target: File, bytes: ByteArray) {
        val part = File(target.path + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(target)) throw IOException("Couldn't write ${target.name}")
    }

    // Downloading ---------------------------------------------------------------------------------

    /** A browser's name: the HD patch's server only sends its file to a browser coming from ChromieCraft's page. */
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun download(
        context: Context,
        client: File,
        choice: HdPatches.Choice,
        source: HdPatches.Source,
        files: Map<String, HdPatches.File>,
        listed: MutableMap<String, HdPatches.Choice>,
        onProgress: (Float?, String?) -> Unit,
    ) {
        onProgress(null, context.getString(R.string.wow_mods_reading))
        val zip = ZipDownload(source.url, source.referer, BROWSER) { Imports.cancelled }
        val entries = network(context) { zip.directory() }
        val plan = files.map { (target, file) ->
            target to (HdPatches.find(entries, file) ?: throw Imports.ImportException(context.getString(R.string.wow_mods_error_missing, file.entry)))
        }
        val total = plan.sumOf { it.second.compressedSize }
        val already = plan.sumOf { (target, _) -> File(client, "$target.download.part").length() }
        Imports.checkSpace(context, plan.sumOf { it.second.size } - already)
        var done = 0L
        for ((target, entry) in plan) {
            val name = target.substringAfterLast('/')
            val before = done
            val file = File(client, target)
            val downloaded = File(client, "$target.download")
            // A dropped connection carries on (a compressed file starts again).
            var tries = 0
            while (true) {
                try {
                    zip.fetch(entry, downloaded) { got ->
                        done = before + got
                        onProgress(done.toFloat() / total, "$name · ${shortSize(context, done)} / ${shortSize(context, total)}")
                    }
                    break
                } catch (e: ZipDownload.Cancelled) {
                    throw Imports.ImportException("cancelled")
                } catch (e: IOException) {
                    if (++tries >= 4) throw Imports.ImportException(context.getString(R.string.wow_download_error_network, e.message ?: e.javaClass.simpleName))
                    Thread.sleep(3_000L * tries)
                }
            }
            // The client's own file of that name is kept, and comes back when the look goes.
            if (file.isFile) {
                val user = File(client, "$target.user")
                if (!user.exists()) file.renameTo(user) else file.delete()
            }
            if (!downloaded.renameTo(file)) throw Imports.ImportException(context.getString(R.string.wow_import_error_write))
            done = before + entry.compressedSize
            listed[target] = choice
            saveInstalled(context, listed)
        }
    }

    private fun <T> network(context: Context, block: () -> T): T =
        try {
            block()
        } catch (e: ZipDownload.Cancelled) {
            throw Imports.ImportException("cancelled")
        } catch (e: IOException) {
            throw Imports.ImportException(context.getString(R.string.wow_download_error_network, e.message ?: e.javaClass.simpleName))
        }

    private fun shortSize(context: Context, bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
}
