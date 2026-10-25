// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.core

import com.logie.warcraft.turtle.core.TurtleLayout
import java.io.File

/**
 * Where Felsong lives in the app's storage: everything under one folder, `files/felsong`, and
 * nothing anywhere else. The ChromieCraft realm keeps `files/server`, `files/mysql`, `files/client`...
 * and Turtle WoW keeps `files/turtle`; nothing here is ever one of those or inside one
 * ([PROTECTED_NAMES], checked by the folder guard and the tests).
 *
 * [files] is the app's own files folder (`Context.filesDir`); [dataDir] the app's data folder,
 * where the game's Windows runtime is (`rootfs`, shared program files, never written by Felsong).
 */
class FelsongLayout(val files: File, val dataDir: File = files.parentFile ?: files) {
    val root = File(files, ROOT_NAME)

    /** LegionCore's programs, libraries, SQL and default configs (the downloaded package). */
    val server = File(root, "server")
    val serverStaging = File(root, "server.new")
    val serverOld = File(root, "server.old")
    val ctl = File(server, "ctl.sh")

    /** The live configs (bnetserver.conf, worldserver.conf). */
    val etc = File(root, "etc")

    /** Felsong's own MariaDB data folder: auth, characters, world, hotfixes. */
    val databases = File(root, "mysql")

    /** The server's data made from the Legion client: dbc (db2), maps, vmaps, mmaps, cameras, gt. */
    val data = File(root, "data")
    val dataWork = File(root, "data.work")
    /** Server data being imported from a zip made on a computer. */
    val dataImport = File(root, "data.import")

    /** The user's 7.3.5 client, the game's drive D:. */
    val client = File(root, "client")
    val clientStaging = File(root, "client.import")

    val logs = File(root, "logs")
    val run = File(root, "run")

    /**
     * The connection patcher's home while it runs (ctl.sh sets HOME to Felsong's folder): it writes
     * the Battle.net certificate bundle the patched client trusts under `.wine/...` there, from where
     * the game's start copies it into the game's own Windows ([CERT_BUNDLE_IN_HOME]).
     */
    val patcherHome = root
    val certBundle = File(root, CERT_BUNDLE_IN_HOME)

    /** What came from warperia.com's add-ons (the add-on manager's records). */
    val addonRecords = File(root, "addons/warperia.tsv")

    /** Downloads in progress, kept between tries (`*.part`). */
    val downloads = File(root, "downloads")

    /** Copies of the databases made before a repair or an update changes them. */
    val backups = File(root, "backups")

    /** What's installed, and how far an install got ([FelsongInstallState]). */
    val stateFile = File(root, "install.json")

    /** Everything an uninstall removes: the whole Felsong folder. */
    val all: List<File> get() = listOf(root)

    /** The other games' folders in `files/` (and the shared runtime): never Felsong's. */
    val protectedFolders: List<File>
        get() = PROTECTED_NAMES.map { File(files, it) } + PROTECTED_DATA_NAMES.map { File(dataDir, it) }

    companion object {
        const val ROOT_NAME = "felsong"

        /** As the server's programs were built for (scripts/package-legioncore.sh). */
        const val STANDARD_ROOT = "/data/data/com.logie.warcraft/files/felsong"

        /**
         * Where connection_patcher writes the certificate bundle, under its HOME (its Unix build:
         * `~/.wine/drive_c/users/Public/Application Data/Blizzard Entertainment/Battle.net/Cache/web_cert_bundle`).
         */
        const val CERT_BUNDLE_IN_HOME = ".wine/drive_c/users/Public/Application Data/Blizzard Entertainment/Battle.net/Cache/web_cert_bundle"

        /**
         * The ChromieCraft realm's folders in `files/` (as Turtle WoW lists them), Turtle WoW's own
         * folder, and their staging and leftovers.
         */
        val PROTECTED_NAMES: List<String> = TurtleLayout.PROTECTED_NAMES + listOf(TurtleLayout.ROOT_NAME, "turtle.old", "turtle.new")

        /** In the app's data folder: the game's Windows runtime (Wine, Box64), shared, read-only here. */
        val PROTECTED_DATA_NAMES: List<String> = TurtleLayout.PROTECTED_DATA_NAMES
    }
}
