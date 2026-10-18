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

    /** The programs that could start a game, the likeliest first. */
    fun candidates(programs: List<Program>): List<Program> =
        programs
            .filter { it.path.endsWith(".exe", ignoreCase = true) && !HELPERS.containsMatchIn(it.path.substringAfterLast('/')) }
            .sortedWith(compareBy<Program> { depth(it.path) }.thenByDescending { it.size })

    private fun depth(path: String) = path.count { it == '/' }
}
