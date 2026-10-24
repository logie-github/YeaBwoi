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

    fun values(width: Int, height: Int, accountName: String?): Map<String, String> = buildMap {
        put("portal", PORTAL)
        // (its language, always: with none, and no launcher's LOCALE, the client asks with a dialog,
        // and its OK sets the portal to the region's code, "US", in place of the realm's address; the
        // client's data is enUS only, its .build.info's tags)
        put("textLocale", "enUS")
        put("audioLocale", "enUS")
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
        put("gxApi", "D3D11")
        // The game's cursor shown by the system: taps read it to tell what a click would do.
        put("gxCursor", "1")
        put("hwDetect", "0")
        if (accountName != null) put("accountName", accountName)
    }

    /** Writes the settings into [client]'s WTF/Config.wtf. */
    fun apply(client: File, width: Int, height: Int, accountName: String?) {
        val config = File(client, "WTF/Config.wtf")
        config.parentFile?.mkdirs()
        val text = if (config.isFile) config.readText() else ""
        config.writeText(ClientConfig.updateConfig(text, values(width, height, accountName)))
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
