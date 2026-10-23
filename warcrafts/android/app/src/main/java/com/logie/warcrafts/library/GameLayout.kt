// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

/**
 * Where a game is in the files the user picked, and which of its programs starts it. Plain Kotlin,
 * unit-tested: paths are relative, with "/" between folders.
 */
object GameLayout {
    /** A program in the game's folder: its path in the folder and its size. */
    data class Program(val path: String, val size: Long)

    /** Programs that come with the games but don't start them. */
    private val HELPERS = Regex(
        "(?i)(launcher|blizzarderror|systemsurvey|crash|error|unins|setup|install|helper|clientsdk|" +
            "mdns|firewall|scenecef|cef|editor|jasshelper|pjass|redist|dxsetup|agent|update|repair|" +
            "battle\\.net|bnet)"
    )

    /**
     * The folder the game is in: the one [mainNames]' program is in (a name with folders, like
     * `x86/Warcraft II.exe`, is matched from the game's folder), else the top folder that all the
     * picked files share (a zip's single folder), else the top itself (""). Null when there's no
     * Windows program nor any of [mainNames] in what was picked.
     */
    fun findRoot(paths: List<String>, mainNames: List<String>): String? {
        if (paths.none { path -> path.endsWith(".exe", ignoreCase = true) || mainNames.any { path.endsWith(it, ignoreCase = true) } }) return null
        for (name in mainNames) {
            val match = paths
                .filter { it.equals(name, ignoreCase = true) || it.endsWith("/$name", ignoreCase = true) }
                .minByOrNull { it.count { c -> c == '/' } }
            if (match != null) return match.dropLast(name.length).trimEnd('/')
        }
        return commonTop(paths)
    }

    /** Whether [paths] hold the game itself: one of [mainNames] (as [findRoot] matches them). */
    fun hasMain(paths: List<String>, mainNames: List<String>): Boolean =
        paths.any { path -> mainNames.any { path.equals(it, ignoreCase = true) || path.endsWith("/$it", ignoreCase = true) } }

    /** A GOG offline installer: its setup program and the .bin files that are part of it. */
    data class Installer(val setup: String, val parts: List<String>)

    /**
     * The GOG offline installer in [paths] (Inno Setup: setup_<game>_<version>.exe, often with
     * setup_<game>_<version>-1.bin and more beside it): the setup program (the one [hint] matches
     * if there are several, the shallowest), and the .bin files in its folder named after it, or
     * if none is, every .bin there that isn't another installer's. Null when there's no setup program.
     */
    fun findInstaller(paths: List<String>, hint: Regex?): Installer? {
        val setups = paths.filter { path -> name(path).let { it.startsWith("setup", ignoreCase = true) && it.endsWith(".exe", ignoreCase = true) } }
        val setup = setups
            .sortedWith(compareByDescending<String> { hint?.containsMatchIn(name(it)) == true }.thenBy { depth(it) }.thenBy { it })
            .firstOrNull() ?: return null
        val folder = setup.substringBeforeLast('/', "")
        fun base(path: String) = name(path).dropLast(".exe".length)
        fun partOf(bin: String, setup: String) = name(bin).startsWith("${base(setup)}-", ignoreCase = true)
        val bins = paths.filter { it.substringBeforeLast('/', "") == folder && it.endsWith(".bin", ignoreCase = true) }
        val named = bins.filter { partOf(it, setup) }
        // Parts renamed: those that aren't another installer's.
        val others = setups.filter { it != setup && it.substringBeforeLast('/', "") == folder }
        return Installer(setup, named.ifEmpty { bins.filter { bin -> others.none { partOf(bin, it) } } }.sorted())
    }

    /**
     * The installer's English language, from innoextract's `--list-languages --silent` lines
     * ("<name> <language name>"): its name, for `--default-language`, or null.
     */
    fun englishLanguage(lines: List<String>): String? =
        lines.map { it.trim() }.filter { it.isNotEmpty() }.firstOrNull { line ->
            val name = line.substringBefore(' ')
            name.equals("en", ignoreCase = true) || name.startsWith("en-", ignoreCase = true) || name.startsWith("en_", ignoreCase = true) ||
                line.contains("english", ignoreCase = true)
        }?.substringBefore(' ')

    /** The single top folder every path is in, or "". */
    fun commonTop(paths: List<String>): String {
        val tops = paths.map { if ('/' in it) it.substringBefore('/') else null }.toSet()
        return tops.singleOrNull() ?: ""
    }

    /** A path that stays inside the game's folder (no "..", no absolute path). */
    fun isSafe(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && !path.contains('\\') &&
            path.split('/').none { it == ".." || it == "." || it.isEmpty() }

    /**
     * The program that starts the game: one of [mainNames] (in that order) if it's there (a name
     * with folders matched from the game's folder, a plain name anywhere), else the largest program
     * that isn't one of the helpers that come with the games, the shallowest first.
     */
    fun pickProgram(programs: List<Program>, mainNames: List<String>): Program? {
        for (name in mainNames) {
            val match = if ('/' in name) {
                programs.firstOrNull { it.path.equals(name, ignoreCase = true) }
            } else {
                programs.filter { it.path.substringAfterLast('/').equals(name, ignoreCase = true) }.minByOrNull { depth(it.path) }
            }
            if (match != null) return match
        }
        return candidates(programs).firstOrNull()
    }

    /** Warcraft III's expansion program (`Frozen Throne.exe`), the shallowest, if it's there. */
    fun expansionProgram(programs: List<Program>): String? =
        programs.filter { it.path.substringAfterLast('/').equals("Frozen Throne.exe", ignoreCase = true) }
            .minByOrNull { depth(it.path) }?.path

    /** The programs that could start a game, the likeliest first. */
    fun candidates(programs: List<Program>): List<Program> =
        programs
            .filter { it.path.endsWith(".exe", ignoreCase = true) && !HELPERS.containsMatchIn(it.path.substringAfterLast('/')) }
            .sortedWith(compareBy<Program> { depth(it.path) }.thenByDescending { it.size })

    private fun depth(path: String) = path.count { it == '/' }

    private fun name(path: String) = path.substringAfterLast('/')
}
