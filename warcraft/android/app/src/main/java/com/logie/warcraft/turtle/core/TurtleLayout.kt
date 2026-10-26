// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

import java.io.File

/**
 * Where OctoWoW lives in the app's storage: everything under one folder, `files/turtle` (the name
 * from when this game was Turtle WoW, kept so an install carries on), and
 * nothing anywhere else. The ChromieCraft realm (and Wrath Remix, which shares its server) keeps
 * `files/server`, `files/etc`, `files/mysql`, `files/data`, `files/client`... as before; nothing
 * here is ever one of those or inside one ([PROTECTED_NAMES], checked by [SafeFs] and the tests).
 *
 * [files] is the app's own files folder (`Context.filesDir`); [dataDir] the app's data folder,
 * where the game's Windows runtime is (`rootfs`, shared program files, never written by Turtle).
 */
class TurtleLayout(val files: File, val dataDir: File = files.parentFile ?: files) {
    val root = File(files, ROOT_NAME)

    /** Tortoise-WoW's programs, libraries, SQL and default configs (the downloaded package). */
    val server = File(root, "server")
    val serverStaging = File(root, "server.new")
    val serverOld = File(root, "server.old")
    val ctl = File(server, "ctl.sh")

    /** The live configs (mangosd.conf, realmd.conf). */
    val etc = File(root, "etc")

    /** Turtle's own MariaDB data folder: tw_logon, tw_char, tw_world, tw_logs. */
    val databases = File(root, "mysql")

    /** The server's data made from the client: dbc, maps, vmaps, mmaps. */
    val data = File(root, "data")
    val dataWork = File(root, "data.work")
    /** Server data being imported from a zip made on a computer. */
    val dataImport = File(root, "data.import")

    /** OctoWoW's client (1.18.1, build 7272), the game's drive D:. */
    val client = File(root, "client")
    /** A copy of the user's client being taken in, before it's checked against OctoWoW's. */
    val clientStaging = File(root, "client.import")
    /**
     * The client while it's downloaded, checked or updated (ClientSync): kept between tries, so a
     * download carries on; put in place of `client/` once every piece matches.
     */
    val clientDownload = File(root, "client.download")
    /** The client's files the game mustn't see (OctoWoW's own DXVK, its realm list), kept for their checksums. */
    val clientKept = File(root, "client-kept")
    /** The torrent the client was last made to match (OctoWoW's), for the next update's removals. */
    val clientTorrent = File(root, "client.torrent")

    val logs = File(root, "logs")
    val run = File(root, "run")

    /** Downloads in progress, kept between tries (`*.part`). */
    val downloads = File(root, "downloads")
    /** What came from warperia.com into Turtle's client (the add-on manager's records). */
    val addonRecords = File(root, "addons/warperia.tsv")

    /** Copies of the databases made before a repair or an update changes them. */
    val backups = File(root, "backups")

    /** What's installed, and how far an install got ([InstallState]). */
    val stateFile = File(root, "install.json")

    /** Everything an uninstall removes: the whole Turtle folder. */
    val all: List<File> get() = listOf(root)

    /** The other environments' folders in `files/` (and the shared runtime): never Turtle's. */
    val protectedFolders: List<File>
        get() = PROTECTED_NAMES.map { File(files, it) } + PROTECTED_DATA_NAMES.map { File(dataDir, it) }

    companion object {
        const val ROOT_NAME = "turtle"

        /** As the server's programs were built for (scripts/package-tortoise.sh). */
        const val STANDARD_ROOT = "/data/data/com.logie.warcraft/files/turtle"

        /**
         * The ChromieCraft realm's folders in `files/` (WowFiles, ModuleStore, AddonStore,
         * ClientMods; Wrath Remix's `etc-remix`; `client-realm`, the realm the 3.3.5a client last
         * started on; `zones`, the Worgen's and Goblins' starting areas' package), and their staging
         * and leftovers.
         */
        val PROTECTED_NAMES = listOf(
            "server", "server.new", "server.old", "etc", "etc-remix", "mysql", "mysql.deleting",
            "data", "data.work", "data.download", "data.import", "data-races", "data-modules", "data-modules.new", "races",
            "client", "client.import", "client-realm", "logs", "run", "addons", "mods", "modules",
            "zones", "zones.new", "zones.old", "zones.download",
        )

        /** In the app's data folder: the game's Windows runtime (Wine, Box64), shared, read-only here. */
        val PROTECTED_DATA_NAMES = listOf("rootfs", "imagefs", "shared_prefs", "databases")
    }
}
