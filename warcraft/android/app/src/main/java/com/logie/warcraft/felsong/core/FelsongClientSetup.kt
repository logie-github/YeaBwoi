// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.core

import com.logie.warcraft.client.ClientConfig
import java.io.File

/**
 * Points the Legion client at the realm on this phone before each start, in its own folder only:
 * `WTF/Config.wtf` (the client's other settings stay). A 7.x client has no realmlist.wtf: its
 * `portal` is the Battle.net server it connects to (the patched program takes a plain host), and
 * the realm list comes from that server. The screen, the intro off, and the account's e-mail in
 * the login box.
 */
object FelsongClientSetup {
    const val PORTAL = "127.0.0.1"

    fun values(width: Int, height: Int, accountName: String?, api: String = FelsongGraphics.valid(null)): Map<String, String> = buildMap {
        put("portal", PORTAL)
        put("readTOS", "1")
        put("readEULA", "1")
        put("readTerminationWithoutNotice", "1")
        put("readContest", "1")
        put("movie", "0")
        // (Legion's intro: the client plays it while this is at most the expansion shown, 6)
        put("playIntroMovie", "7")
        put("checkAddonVersion", "0")
        put("gxWindow", "1")
        put("gxMaximize", "1")
        put("gxWindowedResolution", "${width}x$height")
        put("gxFullscreenResolution", "${width}x$height")
        // (the graphics API chosen, or the one a failed start moved on to: FelsongGraphics)
        put("gxApi", FelsongGraphics.valid(api))
        // The game's cursor shown by the system: taps read it to tell what a click would do.
        put("gxCursor", "1")
        put("hwDetect", "0")
        if (accountName != null) put("accountName", accountName)
    }

    /**
     * The game's graphics on a phone to start with (Legion's lowest preset, no anti-aliasing, 30
     * frames a second, 5 behind other apps), written only where Config.wtf has none yet: what's
     * chosen in the game's own options stays.
     */
    val START_GRAPHICS = mapOf(
        "graphicsQuality" to "1",
        "RAIDgraphicsQuality" to "1",
        "MSAAQuality" to "0",
        "ffxAntiAliasingMode" to "0",
        "maxFPS" to "30",
        "maxFPSBk" to "5",
    )

    /** Writes the settings into [client]'s WTF/Config.wtf. */
    fun apply(client: File, width: Int, height: Int, accountName: String?, api: String = FelsongGraphics.valid(null)) {
        val config = File(client, "WTF/Config.wtf")
        config.parentFile?.mkdirs()
        val text = if (config.isFile) config.readText() else ""
        val unset = START_GRAPHICS.filterKeys { key -> !Regex("(?im)^\\s*SET\\s+${Regex.escape(key)}\\s").containsMatchIn(text) }
        config.writeText(ClientConfig.updateConfig(text, unset + values(width, height, accountName, api)))
    }

    /**
     * Where a Windows program finds the Battle.net certificate bundle the patched client checks its
     * login server against: Wine's ProgramData (`C:\ProgramData`, Wine 4 and later) and the older
     * `C:\users\Public\Application Data`, both relative to a prefix's `drive_c`. The game's start
     * writes it to both.
     */
    val CERT_BUNDLE_IN_PREFIX = listOf(
        "ProgramData/Blizzard Entertainment/Battle.net/Cache/web_cert_bundle",
        "users/Public/Application Data/Blizzard Entertainment/Battle.net/Cache/web_cert_bundle",
    )
}
