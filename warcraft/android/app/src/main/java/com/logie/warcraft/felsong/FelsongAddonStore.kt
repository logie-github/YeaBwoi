// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong

import android.content.Context
import com.logie.warcraft.AddonGame
import com.logie.warcraft.AddonStoreOf
import com.logie.warcraft.R
import com.logie.warcraft.addons.Warperia
import java.io.File

/**
 * Legion's add-ons, managed the way the other games' are (the same add-on manager): from
 * warperia.com's Legion 7.3.5 section, into Legion's client (`files/felsong/client`), with their
 * records, downloads and every change to their files inside `files/felsong`, through its folder
 * guard (SafeFs).
 */
object FelsongAddonGame : AddonGame {
    /** The 7.3.5 client's add-ons carry `## Interface: 70300`. */
    const val INTERFACE = "70300"

    override val flavor = Warperia.Flavor.LEGION
    override val interfaceVersion = INTERFACE
    override val ownFolders = emptySet<String>()

    override fun hasClient(context: Context) = FelsongFiles.hasClient(context)
    override fun client(context: Context): File = FelsongFiles.layout(context).client
    override fun recordsFile(context: Context): File = FelsongFiles.layout(context).addonRecords
    override fun temp(context: Context): File = FelsongFiles.layout(context).downloads
    override fun check(context: Context, file: File): File = FelsongFiles.fs(context).check(file)

    override fun mkdirs(context: Context, folder: File) {
        FelsongFiles.fs(context).mkdirs(folder)
    }

    override fun delete(context: Context, target: File): Boolean {
        FelsongFiles.fs(context).delete(target)
        return true
    }

    override fun move(context: Context, from: File, to: File): Boolean {
        FelsongFiles.fs(context).move(from, to)
        return true
    }

    override fun replace(context: Context, temp: File, file: File): Boolean {
        FelsongFiles.fs(context).replace(temp, file)
        return true
    }

    override fun noClientMessage(context: Context): String = context.getString(R.string.felsong_addons_no_client)
}

/** Legion's add-on store (the 3.3.5a game's is com.logie.warcraft.AddonStore). */
object FelsongAddonStore : AddonStoreOf(FelsongAddonGame)
