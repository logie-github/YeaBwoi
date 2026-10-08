// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd.savestate

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Decides whether a save state may be loaded without forking the player's save.
 *
 * A save state holds the memory card as it was when the state was made, so loading one from
 * before an in-game save would put the old card back. Each state therefore records a fingerprint
 * of the memory card files when it's made ([record]), and it may only be loaded while the card
 * files still have exactly that content and the game hasn't written to the card since ([canLoad]).
 * The second check matters because Dolphin writes the card files a while after the game saves.
 */
object CardGuard {
    private val RAW_CARD = Regex("""MemoryCardA\..+\.raw""")

    /** Allowance between the time a save is requested and the time Dolphin stamps on the state. */
    private const val CLOCK_SLACK_MS = 1000L

    /**
     * The memory card files under [root]: anything in a "Card A" GCI folder, and raw memory card
     * images ("MemoryCardA.<region>.raw"). Everything else (BIOS files, save states) is ignored.
     */
    fun cardFiles(root: File): List<Pair<String, File>> =
        root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path to it }
            .filter { (path, file) ->
                "Card A" in path.split(File.separatorChar).dropLast(1) || RAW_CARD.matches(file.name)
            }
            .sortedBy { it.first }
            .toList()

    /** A fingerprint of the content of every memory card file under [root]. */
    fun fingerprint(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        for ((path, file) in cardFiles(root)) {
            digest.update(path.toByteArray())
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(file.length()).array())
            try {
                file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            } catch (_: IOException) {
                // Unreadable right now (being written). The fingerprint then won't match anything,
                // which can only block a load, never allow a wrong one.
                digest.update(ByteArray(1) { 1 })
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The last time any memory card file under [root] was written. */
    fun newestWrite(root: File): Long =
        cardFiles(root).maxOfOrNull { it.second.lastModified() } ?: 0L

    /**
     * What to store for a save state requested at [requestedAt] (milliseconds since 1970). [card]
     * must be the fingerprint taken before the state is made, never after: if the game writes the
     * card in between, the state then won't match and simply won't load.
     */
    fun record(card: String, requestedAt: Long): String = "$card@$requestedAt"

    /**
     * Whether a save state may be loaded. Null if there is no state.
     *
     * @param stateTime when Dolphin made the state (milliseconds since 1970), 0 if there is none
     * @param recorded what [record] stored for this slot, if anything
     * @param lastGameWrite when the running game last wrote to the memory card (milliseconds since
     *   1970), 0 if it hasn't; includes writes Dolphin hasn't flushed to the card files yet
     */
    fun canLoad(
        stateTime: Long,
        recorded: String?,
        lastGameWrite: Long,
        currentCard: () -> String,
        newestCardWrite: () -> Long,
    ): Boolean? {
        if (stateTime <= 0L) return null
        val card = recorded?.substringBefore('@')
        val requestedAt = recorded?.substringAfter('@', "")?.toLongOrNull()
        // The recorded card belongs to this state only if the state is at least as new as the
        // request. An older state means that save never happened (it failed), so ignore the record.
        if (card != null && requestedAt != null && stateTime + CLOCK_SLACK_MS >= requestedAt) {
            return lastGameWrite < requestedAt && card == currentCard()
        }
        // A state without a record (made before this check existed): it must be newer than every
        // write to the memory card.
        return stateTime >= lastGameWrite && stateTime >= newestCardWrite()
    }
}
