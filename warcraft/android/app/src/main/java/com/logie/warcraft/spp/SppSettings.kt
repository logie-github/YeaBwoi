// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp

import android.content.Context
import com.logie.warcraft.config.ConfFile
import com.logie.warcraft.local.LocalConfig
import java.io.IOException

object SppSettings {
    data class Values(val xp: String = "1", val solo: Boolean = true)
    val RATES = listOf("1", "2", "3", "5", "10")
    fun load(context: Context): Values {
        val file = SppFiles.report(context).detected?.worldConfig ?: throw IOException("Install the SPP server first.")
        val conf = ConfFile(file.readText())
        return Values(conf["Rate.XP.Kill"] ?: "1", conf["Solocraft.Enable"] != "0")
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
    }
}
