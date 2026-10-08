// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold

import org.vita3k.emulator.data.AppInfo

/** The one game V3 Gold is for. */
object GoldGame {
    /** Persona 4 Golden: North America, Europe, Japan, Japan (PlayStation Vita the Best), Asia. */
    val TITLE_IDS = listOf("PCSE00120", "PCSB00245", "PCSG00004", "PCSG00563", "PCSH00021")

    /** Persona 4 Golden among the installed apps, if it's there. */
    fun find(apps: List<AppInfo>): AppInfo? =
        apps.firstOrNull { it.titleId in TITLE_IDS }
            ?: apps.firstOrNull { it.title.contains("Persona 4", ignoreCase = true) || it.title.contains("ペルソナ4") }
}

/** Whether the main menu should go straight into the game: only when the app was just opened. */
object GoldLaunch {
    @Volatile
    private var autoplay = false

    /** MainActivity is being created ([fresh]: not restored after a rotation or process death). */
    @JvmStatic
    fun onActivityCreated(fresh: Boolean) {
        autoplay = fresh
    }

    /** True once per app open. */
    fun consumeAutoplay(): Boolean {
        val result = autoplay
        autoplay = false
        return result
    }
}
