// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww.core

import java.io.File
import java.io.IOException

/** Script generation for the one dedicated Wine session. Imported text is never interpolated as shell code. */
object TwwSession {
    /** The realmlist row belongs to the world configuration, never an assumed default. */
    fun realmId(configuration: String): Int {
        val matches = Regex("(?im)^\\s*RealmID\\s*=\\s*([^#\\r\\n]+)").findAll(configuration).toList()
        if (matches.size != 1) throw IOException("The world configuration must contain one explicit positive RealmID; found ${matches.size} settings.")
        return matches.single().groupValues[1].trim().removeSurrounding("\"").toIntOrNull()?.takeIf { it > 0 }
            ?: throw IOException("The world configuration's RealmID must be a positive integer.")
    }

    fun windows(file: File, root: File, drive: Char): String {
        val base = root.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        if (!path.startsWith(base)) throw IOException("Runtime path escapes the installation: ${file.name}")
        val relative = base.relativize(path).toString().replace('/', '\\')
        return "$drive:\\$relative".also(::safe)
    }

    fun argument(value: String): String {
        safe(value)
        if ('"' in value) throw IOException("A startup argument contains embedded quotes and needs manual review.")
        return "\"$value\""
    }

    /**
     * A command's input from [file] (a Windows path), for a script: `< "R:\x.sql"`, the space before
     * the quote kept. Wine's cmd says "Invalid name." to `<"R:\x.sql"` and doesn't run the command.
     */
    fun input(file: String): String = "< " + argument(file)

    fun launch(command: TwwInstall.StartupCommand, server: File, logName: String): String {
        val arguments = command.arguments.map { value ->
            // Detected --defaults-file paths use the imported host path; translate only paths
            // proven to be inside this repack. Unknown Windows absolute paths remain blockers.
            val prefix = value.substringBefore('=', "")
            val candidate = if (prefix.isNotEmpty()) value.substringAfter('=') else value
            val host = File(candidate)
            val translated = if (host.isAbsolute) windows(host, server, 'S') else candidate
            if (Regex("^[A-Za-z]:").containsMatchIn(translated) && !translated.startsWith("S:\\", true)) {
                throw IOException("Startup path points outside the imported repack: $candidate")
            }
            argument(if (prefix.isEmpty()) translated else "$prefix=$translated")
        }
        return script(
            windows(command.workingDirectory, server, 'S'),
            argument(windows(command.executable, server, 'S')) + " " + arguments.joinToString(" "),
            logName,
        )
    }

    fun script(directory: String, command: String, name: String): String {
        require(name.matches(Regex("[a-z0-9-]+")))
        safe(directory)
        // Winlator's TMPDIR is a Unix path (the runtime's /tmp) that Windows programs inherit as
        // is: MariaDB takes its temporary folder from TMPDIR first, finds no such folder, and
        // InnoDB stops ("unable to create temporary file"). Without it they use Windows' TEMP.
        return "@echo off\r\nchcp 65001 >nul\r\nsetlocal DisableDelayedExpansion\r\nset \"TMPDIR=\"\r\ncd /d ${argument(directory)}\r\n" +
            "$command >\"R:\\runtime\\$name.log\" 2>&1\r\n" +
            "echo %ERRORLEVEL% >\"R:\\runtime\\$name.exit\"\r\n"
    }

    private fun safe(value: String) {
        if (value.any { it in "\r\n\u0000% !^&|<>" && it != ' ' }) {
            throw IOException("A startup path or argument contains unsupported command characters; review the repack's startup script.")
        }
    }
}
