// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/**
 * Where the app keeps the game, the BIOS, the memory card and the save states, all in its private
 * storage:
 *
 * - game/: the disc image, copied in from the files the player picks
 * - system/: an optional PlayStation BIOS (the core has its own replacement otherwise)
 * - saves/: memory card 1 (pcsx-card1.mcd), which the core writes to as the game saves
 * - states/: save states, slot1-3.state and resume.state (where the game was left)
 */
object AdFiles {
    /** Disc image formats the core opens, in the order to prefer them as the file to start. */
    private val LAUNCH_ORDER = listOf("m3u", "cue", "toc", "chd", "pbp", "cbn", "iso", "img", "mdf", "bin")

    const val MEMORY_CARD_SIZE = 128 * 1024
    private const val MEMORY_CARD_NAME = "pcsx-card1.mcd"
    private const val BIOS_SIZE = 512 * 1024

    const val SLOTS = 3

    class ImportException(val reason: Reason, val detail: String = "") : IOException(reason.name) {
        enum class Reason { NO_DISC, MISSING_TRACKS, NOT_A_BIOS, NOT_A_MEMORY_CARD }
    }

    fun gameDir(context: Context) = File(context.filesDir, "game")
    fun systemDir(context: Context) = File(context.filesDir, "system").apply { mkdirs() }
    fun savesDir(context: Context) = File(context.filesDir, "saves").apply { mkdirs() }
    private fun statesDir(context: Context) = File(context.filesDir, "states").apply { mkdirs() }

    /** The file to start the game from, or null if no game is installed. */
    fun launchFile(context: Context): File? {
        val files = gameDir(context).listFiles()?.filter { it.isFile } ?: return null
        for (extension in LAUNCH_ORDER) {
            files.firstOrNull { it.extension.equals(extension, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    /** Total size of the installed game's files. */
    fun gameSize(context: Context): Long = gameDir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Copies the disc image the player picked (a .cue with its .bin tracks, or a single .chd, .pbp,
     * .iso, ...) in place of the installed game. The old game stays until the new one is complete.
     * Background thread.
     */
    fun importGame(context: Context, uris: List<Uri>, onProgress: (done: Long, total: Long) -> Unit) {
        val staging = File(context.filesDir, "game.importing")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val sources = uris.map { it to displayName(context, it) }
            val total = sources.sumOf { (uri, _) -> size(context, uri) }
            var done = 0L
            for ((uri, name) in sources) {
                val target = File(staging, name.replace('/', '_'))
                context.contentResolver.openInputStream(uri).use { input ->
                    input ?: throw IOException("Can't open $name")
                    target.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
            }
            checkDisc(staging)

            val game = gameDir(context)
            val old = File(context.filesDir, "game.old")
            old.deleteRecursively()
            if (game.exists() && !game.renameTo(old)) throw IOException("Can't replace the old game")
            if (!staging.renameTo(game)) {
                old.renameTo(game)
                throw IOException("Can't install the game")
            }
            old.deleteRecursively()
            // Where the old disc was left doesn't fit a different one. Saved slots stay.
            deleteState(context, RESUME_SLOT)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Makes sure [dir] holds a disc the core can start, and that a .cue's tracks are all there,
     * matching their names' upper and lower case to what the .cue says.
     */
    private fun checkDisc(dir: File) {
        val files = dir.listFiles()?.toList().orEmpty()
        val start = LAUNCH_ORDER.firstNotNullOfOrNull { extension ->
            files.firstOrNull { it.extension.equals(extension, ignoreCase = true) }
        } ?: throw ImportException(ImportException.Reason.NO_DISC)

        if (start.extension.equals("cue", ignoreCase = true)) {
            val tracks = Regex("""^\s*FILE\s+"?(.+?)"?\s+\S+\s*$""", RegexOption.IGNORE_CASE)
            val missing = ArrayList<String>()
            for (line in start.readLines()) {
                val track = tracks.find(line)?.groupValues?.get(1)?.substringAfterLast('/')?.substringAfterLast('\\')
                    ?: continue
                if (File(dir, track).exists()) continue
                val sameName = files.firstOrNull { it.name.equals(track, ignoreCase = true) }
                if (sameName == null || !sameName.renameTo(File(dir, track))) missing.add(track)
            }
            if (missing.isNotEmpty()) {
                throw ImportException(ImportException.Reason.MISSING_TRACKS, missing.joinToString(", "))
            }
        }
    }

    fun deleteGame(context: Context) {
        gameDir(context).deleteRecursively()
    }

    // BIOS

    /** The PlayStation BIOS in use, if the player added one. */
    fun bios(context: Context): File? =
        systemDir(context).listFiles()?.firstOrNull { it.isFile && it.length() == BIOS_SIZE.toLong() }

    /** Copies a BIOS file in, in place of the old one. Background thread. */
    fun importBios(context: Context, uri: Uri) {
        val name = displayName(context, uri).replace('/', '_')
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Can't open $name")
        if (bytes.size != BIOS_SIZE) throw ImportException(ImportException.Reason.NOT_A_BIOS)
        deleteBios(context)
        File(systemDir(context), if (name.endsWith(".bin", ignoreCase = true)) name else "$name.bin").writeBytes(bytes)
    }

    fun deleteBios(context: Context) {
        systemDir(context).listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    // Memory card

    fun memoryCard(context: Context) = File(savesDir(context), MEMORY_CARD_NAME)

    /**
     * Replaces memory card 1 with a card from another emulator or a PlayStation: a raw 128 KB card
     * (.mcd, .mcr, .srm, ...), or a DexDrive (.gme) or PSP (.vmp) card. Background thread.
     */
    fun importMemoryCard(context: Context, uri: Uri) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Can't open the memory card")
        val card = when (bytes.size) {
            MEMORY_CARD_SIZE -> bytes
            MEMORY_CARD_SIZE + DEXDRIVE_HEADER -> bytes.copyOfRange(DEXDRIVE_HEADER, bytes.size)
            MEMORY_CARD_SIZE + VMP_HEADER -> bytes.copyOfRange(VMP_HEADER, bytes.size)
            else -> throw ImportException(ImportException.Reason.NOT_A_MEMORY_CARD)
        }
        if (card[0] != 'M'.code.toByte() || card[1] != 'C'.code.toByte()) {
            throw ImportException(ImportException.Reason.NOT_A_MEMORY_CARD)
        }
        val target = memoryCard(context)
        if (target.exists()) target.copyTo(File(savesDir(context), "$MEMORY_CARD_NAME.backup"), overwrite = true)
        target.writeBytes(card)
    }

    /** Writes memory card 1 to [uri] as a raw 128 KB card. Background thread. */
    fun exportMemoryCard(context: Context, uri: Uri) {
        val card = memoryCard(context)
        if (!card.exists()) throw IOException("No memory card yet")
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            card.inputStream().use { it.copyTo(output) }
        } ?: throw IOException("Can't write the memory card")
    }

    private const val DEXDRIVE_HEADER = 3904
    private const val VMP_HEADER = 0x80

    // Save states

    /** A save state slot: 1 to [SLOTS], or [RESUME_SLOT] for where the game was left. */
    fun stateFile(context: Context, slot: Int): File =
        File(statesDir(context), if (slot == RESUME_SLOT) "resume.state" else "slot$slot.state")

    const val RESUME_SLOT = 0

    /** When [slot] was saved (milliseconds since 1970), or null if it's empty. */
    fun stateTime(context: Context, slot: Int): Long? =
        stateFile(context, slot).takeIf { it.isFile && it.length() > 0 }?.lastModified()

    fun deleteState(context: Context, slot: Int) {
        stateFile(context, slot).delete()
    }

    /** Writes [state] to [slot] in one go, so a crash can't leave half a state. Background thread. */
    fun writeState(context: Context, slot: Int, state: ByteArray) {
        val target = stateFile(context, slot)
        val partial = File(target.path + ".part")
        partial.writeBytes(state)
        if (!partial.renameTo(target)) throw IOException("Can't save the state")
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "game.bin"
    }

    private fun size(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return 0L
    }
}
