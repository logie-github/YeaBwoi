// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

/** What runs a game. */
enum class Engine {
    /** Stratagus with War1gus or Wargus, native on Android, on data made from the user's files. */
    STRATAGUS,
    /** The game's own Windows program on Winlator (Wine and Box64). */
    WINE,
}

/**
 * The games the app plays, from the user's own files. Each [Install] is a folder of its own in the
 * app's storage. Warcraft I and II are the GOG releases, played natively by Stratagus once their
 * files are converted; Warcraft III's two games are one install (1.30 has both in one program: the
 * game's main menu switches between them), each with its own look in the launcher ([Title]).
 */
enum class Install(
    val key: String,
    val engine: Engine,
    /**
     * The file that marks the game's folder in what was picked, by its path in the folder (or its
     * name anywhere), the likeliest first ([GameLayout.findRoot]); for Warcraft III, also the
     * program that starts it ([GameLayout.pickProgram]).
     */
    val mainNames: List<String>,
    /** What the program is started with, unless changed in Game files (Warcraft III). */
    val defaultArguments: String,
    /** The Stratagus game package ([Engine.STRATAGUS]): its scripts in the APK and its data tool. */
    val stratagusGame: String? = null,
    /** Its GOG installer's name, to tell it from another game's ([GameLayout.findInstaller]). */
    val installerHint: Regex? = null,
) {
    /** Warcraft: Orcs & Humans (GOG, the DOS game): War1gus. */
    WC1("wc1", Engine.STRATAGUS, listOf("WAR.EXE", "DATA/DATA.WAR"), "", stratagusGame = "war1gus", installerHint = Regex("(?i)orcs")),

    /** Warcraft II: Battle.net Edition (GOG): Wargus. */
    WC2(
        "wc2", Engine.STRATAGUS, listOf("Warcraft II BNE.exe", "Support/TOMES/TOME.1", "Install.mpq"), "",
        stratagusGame = "wargus", installerHint = Regex("(?i)warcraft[ _]?(ii|2)|tides|bne"),
    ),

    /** Warcraft III 1.30.4: Reign of Chaos and The Frozen Throne. */
    WC3("wc3", Engine.WINE, listOf("Warcraft III.exe", "Frozen Throne.exe", "war3.exe"), ""),
}

/** A game as the launcher shows it: its own picture, logo and colours. */
enum class Title(val key: String, val install: Install) {
    WC1("wc1", Install.WC1),
    WC2("wc2", Install.WC2),
    ROC("roc", Install.WC3),
    TFT("tft", Install.WC3);

    companion object {
        fun of(key: String?): Title? = entries.firstOrNull { it.key == key }
    }
}
