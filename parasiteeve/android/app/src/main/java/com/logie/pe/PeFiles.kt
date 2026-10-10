// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.logie.pe.discs.DiscSorter
import java.io.File
import java.io.IOException

/**
 * Where the app keeps the game, the BIOS, the memory card and the save states, all in its private
 * storage:
 *
 * - game/: the two discs, disc1/ and disc2/, each a disc image copied in from the files the player
 *   picked, and the playlist the core starts (Parasite Eve.m3u), which lists the discs there are
 * - system/: the PlayStation BIOS the player added (the core uses OpenBIOS without one)
 * - saves/: memory card 1 (card1.mcd), which the app writes as soon as the game saves, and when the
 *   game pauses
 * - states/: save states, slot1-3.state, each with a .bios file naming the BIOS it was made on
 *
 * Parasite Eve comes on two discs. The core starts the playlist and the in-game menu swaps discs
 * through it. A .pbp holding both discs (made for the PSP) is started directly instead.
 */
object PeFiles {
    const val DISCS = DiscSorter.DISCS
    private const val PLAYLIST = "Parasite Eve.m3u"

    const val MEMORY_CARD_SIZE = 128 * 1024
    private const val MEMORY_CARD_NAME = "card1.mcd"
    private const val BIOS_SIZE = 512 * 1024

    const val SLOTS = 3

    class ImportException(val reason: Reason, val detail: String = "") : IOException(reason.name) {
        enum class Reason { NO_DISC, MISSING_TRACKS, TOO_MANY_DISCS, PBP_HAS_BOTH, NOT_A_BIOS, NOT_A_MEMORY_CARD }
    }

    fun gameDir(context: Context) = File(context.filesDir, "game")
    private fun discDir(context: Context, disc: Int) = File(gameDir(context), "disc$disc")
    fun systemDir(context: Context) = File(context.filesDir, "system").apply { mkdirs() }
    fun savesDir(context: Context) = File(context.filesDir, "saves").apply { mkdirs() }
    private fun statesDir(context: Context) = File(context.filesDir, "states").apply { mkdirs() }

    /** The file to start disc [disc] (1 or 2) from, or null if it isn't installed. */
    fun discFile(context: Context, disc: Int): File? = DiscSorter.startFile(discDir(context, disc))

    /** Disc 1 is a .pbp with both discs in it. */
    fun hasBothDiscsInOne(context: Context): Boolean = discFile(context, 1)?.let { DiscSorter.isMultiDiscPbp(it) } == true

    /** Whether [disc] can be played: installed, or in the .pbp that holds both. */
    fun hasDisc(context: Context, disc: Int): Boolean = discFile(context, disc) != null || hasBothDiscsInOne(context)

    /** The file to start the game from, or null if no disc is installed. */
    fun launchFile(context: Context): File? {
        val first = discFile(context, 1)
        if (first != null && DiscSorter.isMultiDiscPbp(first)) return first
        if ((1..DISCS).none { discFile(context, it) != null }) return null
        return File(gameDir(context), PLAYLIST).takeIf { it.isFile } ?: writePlaylist(context)
    }

    /** Total size of the installed discs. */
    fun gameSize(context: Context): Long = gameDir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** The size of one installed disc. */
    fun discSize(context: Context, disc: Int): Long =
        discDir(context, disc).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Lists the installed discs in the playlist, in order. Its paths are relative to it, and it
     * keeps its name, so save states (which note it) keep working when a disc is added.
     */
    private fun writePlaylist(context: Context): File {
        val playlist = File(gameDir(context), PLAYLIST)
        val lines = (1..DISCS).mapNotNull { disc -> discFile(context, disc)?.let { "disc$disc/${it.name}" } }
        playlist.parentFile?.mkdirs()
        playlist.writeText(lines.joinToString("\n", postfix = "\n"))
        return playlist
    }

    /**
     * Copies the disc images the player picked in. Each disc is a .cue with its .bin tracks, or a
     * single .chd, .pbp, .iso, ... file. The picked files go to [disc] (1 or 2), or with null, to
     * the discs their names say ("Disc 1", "CD2", ...), in name order otherwise; a .pbp with both
     * discs takes both places. A disc the player didn't pick stays as it was, and a replaced one
     * stays until its new copy is complete. Background thread.
     */
    fun importDiscs(context: Context, uris: List<Uri>, disc: Int?, onProgress: (done: Long, total: Long) -> Unit) {
        val staging = File(context.filesDir, "game.importing")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val sources = uris.map { it to displayName(context, it) }
            val total = sources.sumOf { (uri, _) -> size(context, uri) }
            var done = 0L
            for ((uri, name) in sources) {
                // A playlist that came with the discs: the app writes its own.
                if (name.endsWith(".m3u", ignoreCase = true)) continue
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

            val sorted = try {
                DiscSorter.sort(staging, disc)
            } catch (e: DiscSorter.SortException) {
                throw ImportException(ImportException.Reason.valueOf(e.reason.name), e.detail)
            }
            val bothInOne = sorted.values.any { DiscSorter.isMultiDiscPbp(it) }
            if (bothInOne && sorted.size > 1) throw ImportException(ImportException.Reason.TOO_MANY_DISCS)
            // A .pbp with both discs takes disc 1's place, whichever it was added as.
            val discs = if (bothInOne) mapOf(1 to sorted.values.single()) else sorted
            if (1 !in discs && hasBothDiscsInOne(context)) throw ImportException(ImportException.Reason.PBP_HAS_BOTH)

            gameDir(context).mkdirs()
            for ((number, start) in discs) {
                val incoming = start.parentFile ?: throw IOException("Can't install disc $number")
                val target = discDir(context, number)
                val old = File(gameDir(context), "disc$number.old")
                old.deleteRecursively()
                if (target.exists() && !target.renameTo(old)) throw IOException("Can't replace disc $number")
                if (!incoming.renameTo(target)) {
                    old.renameTo(target)
                    throw IOException("Can't install disc $number")
                }
                old.deleteRecursively()
            }
            // Both discs in one .pbp: the other disc isn't needed.
            if (bothInOne) discDir(context, 2).deleteRecursively()
            writePlaylist(context)
        } finally {
            staging.deleteRecursively()
        }
    }

    fun deleteDisc(context: Context, disc: Int) {
        discDir(context, disc).deleteRecursively()
        File(gameDir(context), PLAYLIST).delete()
        if ((1..DISCS).any { discFile(context, it) != null }) writePlaylist(context)
    }

    fun deleteGame(context: Context) {
        gameDir(context).deleteRecursively()
    }

    // BIOS

    /** The PlayStation BIOS in use, if the player added one. */
    fun bios(context: Context): File? =
        systemDir(context).listFiles()?.firstOrNull { it.isFile && it.length() == BIOS_SIZE.toLong() }

    /** What the game runs on: the added BIOS's file name, or OpenBIOS, the core's free stand-in. */
    fun biosTag(context: Context): String = bios(context)?.name ?: OPENBIOS

    private const val OPENBIOS = "OpenBIOS"

    /** Copies a BIOS file in, in place of the old one. Background thread. */
    fun importBios(context: Context, uri: Uri) {
        val name = displayName(context, uri).replace('/', '_')
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Can't open $name")
        if (bytes.size != BIOS_SIZE || !looksLikeBios(bytes)) throw ImportException(ImportException.Reason.NOT_A_BIOS)
        deleteBios(context)
        File(systemDir(context), if (name.endsWith(".bin", ignoreCase = true)) name else "$name.bin").writeBytes(bytes)
    }

    /** A PlayStation BIOS names its maker (or, for OpenBIOS, itself) near its start. */
    private fun looksLikeBios(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, 0x10000, Charsets.ISO_8859_1)
        return BIOS_MARKS.any { it in head }
    }

    private val BIOS_MARKS = listOf("Sony Computer Entertainment", "PS compatible", "OpenBIOS")

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

    /** Memory card 1, to hand to the emulator, or null if there's none yet. */
    fun readMemoryCard(context: Context): ByteArray? =
        memoryCard(context).takeIf { it.length() == MEMORY_CARD_SIZE.toLong() }?.readBytes()

    /**
     * Writes memory card 1 in one go, so a crash can't leave half a card, after copying the card it
     * replaces to a backup if [backup]. Background thread.
     */
    fun writeMemoryCard(context: Context, card: ByteArray, backup: Boolean) {
        val target = memoryCard(context)
        if (backup && target.exists()) target.copyTo(File(savesDir(context), "$MEMORY_CARD_NAME.backup"), overwrite = true)
        val partial = File(target.path + ".part")
        partial.writeBytes(card)
        if (!partial.renameTo(target)) throw IOException("Can't save the memory card")
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

    /** A save state slot: 1 to [SLOTS]. */
    fun stateFile(context: Context, slot: Int): File = File(statesDir(context), "slot$slot.state")

    /** When [slot] was saved (milliseconds since 1970), or null if it's empty. */
    fun stateTime(context: Context, slot: Int): Long? =
        stateFile(context, slot).takeIf { it.isFile && it.length() > 0 }?.lastModified()

    fun deleteState(context: Context, slot: Int) {
        stateFile(context, slot).delete()
        stateBiosFile(context, slot).delete()
    }

    private fun stateBiosFile(context: Context, slot: Int) = File(stateFile(context, slot).path + ".bios")

    /**
     * The state in [slot] was made on another BIOS than the game runs on now (before the player
     * added theirs, say). The game's own code from that BIOS comes back with it, so saving to the
     * memory card may not work, or the game may crash.
     */
    fun stateFromOtherBios(context: Context, slot: Int): Boolean {
        val made = runCatching { stateBiosFile(context, slot).readText() }.getOrNull() ?: return false
        return stateTime(context, slot) != null && made != biosTag(context)
    }

    /**
     * Writes [state] to [slot] in one go, so a crash can't leave half a state, noting the BIOS it was
     * made on ([biosTag] when the game started). Background thread.
     */
    fun writeState(context: Context, slot: Int, state: ByteArray, bios: String) {
        val target = stateFile(context, slot)
        val partial = File(target.path + ".part")
        partial.writeBytes(state)
        if (!partial.renameTo(target)) throw IOException("Can't save the state")
        stateBiosFile(context, slot).writeText(bios)
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
