// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts

import android.content.Context
import com.logie.warcrafts.library.Install
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Makes the data Stratagus plays Warcraft I or II from, out of the user's GOG files in the app
 * (`files/games/wc1`, `files/games/wc2`): the game package's scripts, maps and graphics from the
 * APK (War1gus's or Wargus's, scripts/build-stratagus.sh), then its tool (war1tool, wartool, shipped
 * as libwar1tool.so and libwartool.so) converting the GOG files beside them. Into
 * `files/stratagus/<war1gus|wargus>`, through a staging folder: a failed conversion changes nothing.
 * The tool leaves a file named `extracted` when it's done.
 *
 * Without ffmpeg on the phone, the tools leave out the intro videos and Warcraft II's music (they
 * convert those with it); everything else of the games is made.
 */
object Conversion {
    fun tool(context: Context, install: Install): File =
        File(context.applicationInfo.nativeLibraryDir, if (install == Install.WC2) "libwartool.so" else "libwar1tool.so")

    fun run(context: Context, install: Install, onLine: (String) -> Unit) {
        val game = install.stratagusGame ?: error("$install isn't played by Stratagus")
        val target = RtsFiles.stratagusDir(context, install)
        val staging = File(target.parentFile, "$game.converting")
        staging.deleteRecursively()
        staging.mkdirs()

        onLine(context.getString(R.string.rts_convert_scripts))
        context.assets.open("stratagus/$game.zip").use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name.startsWith("/") || name.split('/').any { it == ".." }) continue
                    val file = File(staging, name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }

        val log = File(staging, "conversion.log")
        val process = ProcessBuilder(tool(context, install).path, RtsFiles.folder(context, install).path, staging.path)
            .directory(staging)
            .redirectErrorStream(true)
            .apply { environment()["HOME"] = staging.path }
            .start()
        val tail = ArrayDeque<String>()
        log.bufferedWriter().use { out ->
            process.inputStream.bufferedReader().forEachLine { line ->
                out.appendLine(line)
                tail.addLast(line)
                if (tail.size > 12) tail.removeFirst()
                if (Imports.cancelled) process.destroy()
                onLine(line)
            }
        }
        val code = process.waitFor()
        if (Imports.cancelled) throw Imports.ImportException(context.getString(R.string.rts_import_cancelled))
        if (code != 0 || !File(staging, "extracted").isFile) {
            throw Imports.ImportException(context.getString(R.string.rts_convert_failed, code, tail.joinToString("\n")))
        }
        target.deleteRecursively()
        if (!staging.renameTo(target)) throw Imports.ImportException("Can't move the converted game into place")
    }
}
