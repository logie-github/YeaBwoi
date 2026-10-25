// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

/**
 * Warcraft III 1.27's CD keys, as Blizzard's installer writes them into the game's archives after
 * installing (the app's silent install does the same with the keys the player types, through
 * `wc3unpack key`, which encodes each file as Game.dll reads it). Without them the game stops at
 * start: "Warcraft III was unable to find a CD Key".
 *
 * What the installer writes (its script in `Installer Tome.mpq`): into `war3.mpq` the Reign of
 * Chaos key (`font\font.gid`), the owner's name (`font\font.clh`) and its mark (`font\font.ccd`,
 * from the package's `Files\font\font.ccd`: "sgubon"); into `War3x.mpq` The Frozen Throne's key
 * (`font\font.exp`) and the same mark. A key is 26 characters of [ALPHABET] (Game.dll's own table,
 * upper or lower case), written as typed without dashes.
 */
object Wc3Keys {
    const val ALPHABET = "246789BCDEFGHJKMNPRTVWXYZ"
    const val LENGTH = 26
    private const val MARK = "sgubon"
    private const val OWNER = "Player"

    /** The key as the game reads it (upper case, no dashes or spaces), or null if it can't be one. */
    fun normalize(text: String): String? {
        val key = text.uppercase().filter { !it.isWhitespace() && it != '-' }
        return key.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }

    /** One file of the keys: [text] encoded into the game's archive [archive] as [name]. */
    data class Write(val archive: String, val name: String, val text: String)

    fun writes(roc: String, tft: String): List<Write> = listOf(
        Write("War3.mpq", "font\\font.gid", roc),
        Write("War3.mpq", "font\\font.clh", OWNER),
        Write("War3.mpq", "font\\font.ccd", MARK),
        Write("War3x.mpq", "font\\font.exp", tft),
        Write("War3x.mpq", "font\\font.ccd", MARK),
    )

    /** In the game's folder once the keys are written. */
    const val SAVED = "keys.done"
}
