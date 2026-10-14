// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

import com.logie.warcraft.client.ClientConfig
import java.io.File

/**
 * Points the Turtle WoW client (1.18.1, a vanilla 1.12 client underneath) at the realm on this
 * phone before each start, in its own folder only:
 *
 * - `realmlist.wtf` next to WoW.exe (where a 1.12 client reads it, unlike 3.3.5a's `Data/<locale>/`):
 *   `set realmlist 127.0.0.1`;
 * - `WTF/Config.wtf` (the client's other settings stay): the realm by name, the screen, the intro
 *   off, and the account's name in the login box.
 */
object TurtleClientSetup {
    const val REALM_NAME = "Tortoise WoW"

    fun values(width: Int, height: Int, accountName: String?): Map<String, String> = buildMap {
        put("realmList", "127.0.0.1")
        put("realmName", REALM_NAME)
        put("readTOS", "1")
        put("readEULA", "1")
        put("movie", "0")
        put("gxResolution", "${width}x$height")
        put("hwDetect", "0")
        put("gxWindow", "1")
        put("gxMaximize", "1")
        // The game's cursor shown by the system: taps read it to tell what a click would do.
        put("gxCursor", "1")
        if (accountName != null) put("accountName", accountName)
    }

    /** Writes the realm list and the settings into [client] (Turtle's client folder). */
    fun apply(client: File, width: Int, height: Int, accountName: String?) {
        File(client, "realmlist.wtf").writeText(ClientConfig.realmlist("127.0.0.1"))
        val config = File(client, "WTF/Config.wtf")
        config.parentFile?.mkdirs()
        val text = if (config.isFile) config.readText() else ""
        config.writeText(ClientConfig.updateConfig(text, values(width, height, accountName)))
    }
}
