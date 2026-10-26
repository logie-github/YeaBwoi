// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts

import android.content.Context
import com.logie.warcrafts.library.Wc3Pickup
import java.io.File

/**
 * The heroes' walk-over pickup ([Wc3Pickup]) put into Warcraft III's folder at each start, or taken
 * out: the game's own `Blizzard.j` taken from its `War3Patch.mpq` by `wc3unpack`, with the addition,
 * written as `Blizzard.j` at the top of the folder. Made again only when the archive or the addition
 * changes. A `Blizzard.j` that isn't the app's is left alone.
 */
object Wc3PickupSetup {
    /** Puts it in place ([on]) or takes it out; what was done, for the report. "on" first when it's in place. */
    fun prepare(context: Context, game: File, on: Boolean): String {
        val files = game.listFiles().orEmpty()
        val target = files.firstOrNull { it.name.equals(Wc3Pickup.FILE, ignoreCase = true) } ?: File(game, Wc3Pickup.FILE)
        val firstLine = if (target.isFile) runCatching { target.bufferedReader(Charsets.ISO_8859_1).use { it.readLine() } }.getOrNull() else null
        val ours = firstLine != null && Wc3Pickup.isOurs(firstLine)
        if (target.isFile && !ours) return "off: a ${target.name} of the player's own is in the game's folder, left as it is"
        if (!on) {
            if (ours) target.delete()
            return "off (Graphics)"
        }
        val archive = files.firstOrNull { it.name.equals(Wc3Pickup.ARCHIVE, ignoreCase = true) }
        if (archive == null) {
            if (ours) target.delete()
            return "off: no ${Wc3Pickup.ARCHIVE} in the game's folder"
        }
        val source = Wc3Pickup.source(archive.name, archive.length(), archive.lastModified())
        if (ours && firstLine?.trimEnd('\r') == Wc3Pickup.header(source)) return "on (as before)"

        val work = File(context.cacheDir, "wc3pickup").apply { deleteRecursively(); mkdirs() }
        try {
            val extracted = File(work, "Blizzard.j")
            // Run here, not with Tools.run: that one stops when an import was cancelled.
            val result = runCatching {
                val process = ProcessBuilder(Tools.file(context, "libwc3unpack.so").path, "extract", archive.path, Wc3Pickup.IN_ARCHIVE, extracted.path)
                    .directory(work)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor() to output
            }
            val (code, output) = result.getOrNull() ?: (-1 to (result.exceptionOrNull()?.message ?: "not run"))
            if (code != 0 || !extracted.isFile) {
                if (ours) target.delete()
                val why = output.lines().lastOrNull { it.isNotBlank() } ?: "exit $code"
                return "off: ${Wc3Pickup.IN_ARCHIVE} not taken from ${archive.name} ($why)"
            }
            val patched = Wc3Pickup.patch(String(extracted.readBytes(), Charsets.ISO_8859_1), source)
            if (patched == null) {
                if (ours) target.delete()
                return "off: ${archive.name}'s ${Wc3Pickup.IN_ARCHIVE} isn't laid out as expected"
            }
            val part = File(game, Wc3Pickup.FILE + ".part")
            part.writeBytes(patched.toByteArray(Charsets.ISO_8859_1))
            if (target.exists() && target.name != Wc3Pickup.FILE) target.delete()
            if (!part.renameTo(File(game, Wc3Pickup.FILE))) {
                part.delete()
                return "off: ${Wc3Pickup.FILE} couldn't be written"
            }
            return "on (made from ${archive.name})"
        } finally {
            work.deleteRecursively()
        }
    }
}
