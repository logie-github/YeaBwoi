// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraftrts.library

/**
 * The games the app plays, from the user's own files. Each [Install] is a folder of its own in the
 * app's storage; Warcraft III's two games are one install (1.30 has both in one program: the
 * game's main menu switches between them), each with its own look in the launcher ([Title]).
 */
enum class Install(
    val key: String,
    /** The program that starts it, by name, the likeliest first ([GameLayout.pickProgram]). */
    val mainNames: List<String>,
    /** What the program is started with, unless changed in Game files. */
    val defaultArguments: String,
) {
    /** Warcraft: Orcs & Humans, Remastered (2024). */
    WC1(
        "wc1",
        listOf("Warcraft I Remastered.exe", "Warcraft Remastered.exe", "Warcraft.exe", "x86_64/Warcraft.exe"),
        // The way past the launcher that Blizzard's other games take; unconfirmed for this one.
        "-launch",
    ),

    /** Warcraft II: Tides of Darkness, Remastered (2024). */
    WC2(
        "wc2",
        listOf("Warcraft II Remastered.exe", "Warcraft II.exe", "Warcraft2.exe", "Warcraft II BNE.exe"),
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
