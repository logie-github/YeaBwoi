// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcrafts.library

/**
 * The games the app plays, from the user's own files. Each [Install] is a folder of its own in the
 * app's storage; Warcraft III's two games are one install (1.30 has both in one program: the
 * game's main menu switches between them), each with its own look in the launcher ([Title]).
 */
enum class Install(
    val key: String,
    /**
     * The program that starts it, by its path in the game's folder (or its name anywhere), the
     * likeliest first ([GameLayout.pickProgram]).
     */
    val mainNames: List<String>,
    /** What the program is started with, unless changed in Game files. */
    val defaultArguments: String,
) {
    /** Warcraft: Orcs & Humans, Remastered (2024). */
    WC1(
        "wc1",
        // Battle.net installs it as Warcraft I Remastered\x86\Warcraft.exe.
        listOf("x86/Warcraft.exe", "x86_64/Warcraft.exe", "Warcraft.exe", "Warcraft I Remastered.exe"),
        // The way past the launcher that Blizzard's other games take; unconfirmed for this one.
        "-launch",
    ),

    /** Warcraft II: Tides of Darkness, Remastered (2024). */
    WC2(
        "wc2",
        // Battle.net installs it as Warcraft II Remastered\x86\Warcraft II.exe.
        listOf("x86/Warcraft II.exe", "x86_64/Warcraft II.exe", "Warcraft II.exe", "Warcraft II BNE.exe"),
        "-launch",
    ),

    /** Warcraft III 1.30.4: Reign of Chaos and The Frozen Throne. */
    WC3(
        "wc3",
        listOf("Warcraft III.exe", "Frozen Throne.exe", "war3.exe"),
        "",
    ),
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
