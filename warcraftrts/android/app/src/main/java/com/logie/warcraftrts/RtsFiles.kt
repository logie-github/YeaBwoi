// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraftrts

import android.content.Context
import com.logie.warcraftrts.library.CdKeys
import com.logie.warcraftrts.library.GameLayout
import com.logie.warcraftrts.library.Install
import com.logie.warcraftrts.library.Title
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The app's storage (`files/`): each game the user brought in `games/<wc1|wc2|wc3>/` (imported by
 * [Imports]), and the choices made for it: the program that starts it, what it's started with,
 * which game the launcher shows.
 */
object RtsFiles {
    private const val PREFS = "games"

    fun gamesRoot(context: Context) = File(context.filesDir, "games")

    fun folder(context: Context, install: Install) = File(gamesRoot(context), install.key)

    /** Where an import puts the files until they're all there. */
    fun staging(context: Context) = File(context.filesDir, "import.staging")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The Windows programs in a game's folder, a few folders deep. */
    fun programs(context: Context, install: Install): List<GameLayout.Program> {
        val root = folder(context, install)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().maxDepth(4)
            .filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
            .map { GameLayout.Program(it.relativeTo(root).invariantSeparatorsPath, it.length()) }
            .toList()
    }

    /** The program that starts the game: the one chosen in Game files, else the likeliest. */
    fun program(context: Context, install: Install): String? {
        val chosen = prefs(context).getString("program_${install.key}", null)
        if (chosen != null && File(folder(context, install), chosen).isFile) return chosen
        return GameLayout.pickProgram(programs(context, install), install.mainNames)?.path
    }

    fun setProgram(context: Context, install: Install, path: String?) {
        prefs(context).edit().putString("program_${install.key}", path).apply()
    }

    fun hasGame(context: Context, install: Install): Boolean = program(context, install) != null

    /** What the program is started with. */
    fun arguments(context: Context, install: Install): String =
        prefs(context).getString("arguments_${install.key}", null) ?: install.defaultArguments

    fun setArguments(context: Context, install: Install, arguments: String) {
        prefs(context).edit().putString("arguments_${install.key}", arguments.trim()).apply()
    }

    /** Blizzard's servers kept out of reach ([com.logie.warcraftrts.library.Offline]): on unless turned off. */
    fun isPrivate(context: Context): Boolean = prefs(context).getBoolean("private", true)

    fun setPrivate(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("private", value).apply()
    }

    /** The CD keys in a text file next to the game's program (Warcraft III's repack has one). */
    fun cdKeys(context: Context, install: Install): List<CdKeys.Found> {
        val program = program(context, install) ?: return emptyList()
        val dir = File(folder(context, install), program).parentFile ?: return emptyList()
        return dir.listFiles { file -> file.isFile && file.length() < 64 * 1024 && CdKeys.isKeyFile(file.name) }
            .orEmpty()
            .flatMap { file -> runCatching { CdKeys.find(file.readText()) }.getOrDefault(emptyList()) }
            .distinctBy { it.key }
    }

    /** How much room a game takes. */
    fun size(context: Context, install: Install): Long =
        folder(context, install).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun delete(context: Context, install: Install) {
        folder(context, install).deleteRecursively()
        setProgram(context, install, null)
    }

    // The game the launcher shows -------------------------------------------------------------

    private val _title = MutableStateFlow<Title?>(null)

    fun title(context: Context): StateFlow<Title?> {
        if (_title.value == null) _title.value = readTitle(context)
        return _title
    }

    fun currentTitle(context: Context): Title = _title.value ?: readTitle(context).also { _title.value = it }

    fun selectTitle(context: Context, title: Title) {
        prefs(context).edit().putString("title", title.key).apply()
        _title.value = title
    }

    private fun readTitle(context: Context): Title = Title.of(prefs(context).getString("title", null)) ?: Title.TFT
}
