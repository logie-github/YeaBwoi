// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp

import android.content.Context
import com.logie.warcraft.config.ConfFile
import com.logie.warcraft.local.LocalConfig
import java.io.IOException

object SppSettings {
    /** [graphics]: the Vulkan driver the realm's session (the client's DirectX 11, through DXVK) uses. */
    data class Values(val xp: String = "1", val solo: Boolean = true, val graphics: String = VORTEK)
    val RATES = listOf("1", "2", "3", "5", "10")
    /** The phone's own driver, through Winlator's Vortek (what the app's other games use). */
    const val VORTEK = "vortek"
    /** Mesa's Turnip, for Adreno GPUs only. */
    const val TURNIP = "turnip"
    private const val PREFS = "spp"
    private const val GRAPHICS = "graphics"

    /** Turnip can be chosen: the phone's GPU is an Adreno. */
    fun turnipAvailable(context: Context) = runCatching { com.winlator.core.GPUHelper.getAdrenoModelId(context) > 0 }.getOrDefault(false)

    private fun graphics(context: Context): String {
        val chosen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(GRAPHICS, VORTEK)
        return if (chosen == TURNIP && turnipAvailable(context)) TURNIP else VORTEK
    }

    /** Winlator's graphics driver setting for the realm's container: the chosen Vulkan driver, Gladio for OpenGL. */
    fun graphicsDriver(context: Context) = graphics(context) + "," + com.winlator.container.GraphicsDrivers.GLADIO

    fun load(context: Context): Values {
        val file = SppFiles.report(context).detected?.worldConfig ?: throw IOException("Install the SPP server first.")
        val conf = ConfFile(file.readText())
        return Values(conf["Rate.XP.Kill"] ?: "1", conf["Solocraft.Enable"] != "0", graphics(context))
    }
    fun save(context: Context, values: Values) {
        if (SppServer.isBusy || SppJobs.busy) throw IOException("Stop SPP and finish its installation before changing realm settings.")
        require(values.xp.toDoubleOrNull()?.let { it.isFinite() && it > 0 && it <= 100 } == true) { "Choose a valid experience rate." }
        val file = SppFiles.report(context).detected?.worldConfig ?: throw IOException("Install the SPP server first.")
        LocalConfig.backup(file)
        val conf = ConfFile(file.readText())
        for (key in listOf("Rate.XP.Kill", "Rate.XP.Quest", "Rate.XP.Explore")) conf[key] = values.xp
        conf.setSwitch("Solocraft.Enable", values.solo)
        LocalConfig.atomicWrite(file, conf.toString())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(GRAPHICS, if (values.graphics == TURNIP) TURNIP else VORTEK).apply()
    }
}
