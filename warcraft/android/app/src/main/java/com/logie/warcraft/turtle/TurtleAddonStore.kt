// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle

import android.content.Context
import com.logie.warcraft.AddonGame
import com.logie.warcraft.AddonStoreOf
import com.logie.warcraft.R
import com.logie.warcraft.addons.Warperia
import java.io.File

/**
 * Turtle WoW's add-ons, managed the way the 3.3.5a game's are (the same add-on manager): from
 * warperia.com's 1.12 section, into Turtle's own copy of the client, with their records, downloads
 * and every change to their files inside `files/turtle`, through its folder guard (SafeFs). The
 * bots' control addon is the app's (put in at each start), not the user's to manage.
 */
object TurtleAddonGame : AddonGame {
    /** Turtle WoW's client is 1.12's: its add-ons carry `## Interface: 11200`. */
    const val INTERFACE = "11200"

    override val flavor = Warperia.Flavor.VANILLA
    override val interfaceVersion = INTERFACE
    override val ownFolders = setOf("tortoisebotsmanager")

    override fun hasClient(context: Context) = TurtleFiles.hasClient(context)
    override fun client(context: Context): File = TurtleFiles.layout(context).client
    override fun recordsFile(context: Context): File = TurtleFiles.layout(context).addonRecords
    override fun temp(context: Context): File = TurtleFiles.layout(context).downloads
    override fun check(context: Context, file: File): File = TurtleFiles.fs(context).check(file)

    override fun mkdirs(context: Context, folder: File) {
        TurtleFiles.fs(context).mkdirs(folder)
    }

    override fun delete(context: Context, target: File): Boolean {
        TurtleFiles.fs(context).delete(target)
        return true
    }

    override fun move(context: Context, from: File, to: File): Boolean {
        TurtleFiles.fs(context).move(from, to)
        return true
    }

    override fun replace(context: Context, temp: File, file: File): Boolean {
        TurtleFiles.fs(context).replace(temp, file)
        return true
    }

    override fun noClientMessage(context: Context): String = context.getString(R.string.turtle_addons_no_client)
}

/** Turtle WoW's add-on store (the 3.3.5a game's is com.logie.warcraft.AddonStore). */
object TurtleAddonStore : AddonStoreOf(TurtleAddonGame)
