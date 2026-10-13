// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The app's launcher icon, chosen in the app: each icon is an `activity-alias` of [MainActivity]
 * in the manifest, and the chosen one is the only one enabled. Launchers can take a moment to show
 * the change, and drop home-screen shortcuts of the icon that was switched off.
 */
object AppIcons {
    enum class Icon(val alias: String, val foreground: Int, val background: Int, val label: Int) {
        WOTLK("com.logie.warcraft.IconWotlk", R.mipmap.ic_wotlk_foreground, R.mipmap.ic_wotlk_background, R.string.wow_icon_wotlk),
        CATACLYSM("com.logie.warcraft.IconCataclysm", R.mipmap.ic_cata_foreground, R.mipmap.ic_cata_background, R.string.wow_icon_cataclysm),
    }

    fun current(context: Context): Icon = Icon.entries.firstOrNull { isEnabled(context, it) } ?: Icon.WOTLK

    private fun isEnabled(context: Context, icon: Icon): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, icon.alias))
        return when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            // As the manifest says: WotLK on, the others off.
            else -> icon == Icon.WOTLK
        }
    }

    fun set(context: Context, chosen: Icon) {
        val pm = context.packageManager
        // The new one first, so the app always has a launcher icon.
        for (icon in listOf(chosen) + Icon.entries.filter { it != chosen }) {
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, icon.alias),
                if (icon == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
