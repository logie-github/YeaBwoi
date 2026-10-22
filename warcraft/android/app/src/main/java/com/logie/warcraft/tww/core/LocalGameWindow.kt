// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww.core

import java.util.Locale

/** A managed server's console must never receive the game's password or touch-login automation. */
object LocalGameWindow {
    @JvmStatic fun isClient(windowClass: String): Boolean = windowClass.lowercase(Locale.ROOT).let {
        it.contains("wow") || it.contains("gxwindow")
    }
}
