// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export.core

/**
 * What "Export everything to Blizzard" copies out of the app's `files/` folder, and what it leaves
 * behind. Pure Kotlin (no android.*): unit-tested in warcraft/unit-tests.
 *
 * The rule is "everything except": a folder this list doesn't know (a future game, a module's data)
 * is exported rather than lost. Only what the new app makes again by itself, and what isn't the
 * user's data, is left out, and every exclusion is listed in export.json (`excluded` for the
 * top-level entries, `excludedInside` for things inside a folder that is exported) so nothing is
 * left out silently.
 */
object ExportRules {
    /** The folder the export is written to, inside the folder the user picked. */
    const val ROOT_FOLDER = "WoW-export"

    /** Why an entry is left out: its exact [name], or the [suffix] its name ends in, and the reason. */
    class Exclusion(val name: String?, val suffix: String?, val reason: String) {
        fun matches(entry: String) = (name != null && entry == name) || (suffix != null && entry.endsWith(suffix))
    }

    /**
     * Top-level entries of `files/` never exported.
     *
     * - `logs`, `run`: the servers' logs, and their pid files, sockets and status files. Not data;
     *   a stale pid file would even make a restored app think a server runs.
     * - `rootfs`, `imagefs`, `winlator`, `containers`: Winlator's runtime installs (Wine, Box64,
     *   the game's Windows containers). The Blizzard app brings and unpacks its own; they are
     *   gigabytes and rebuilt from the APK. (In this app they live in the app's data folder next
     *   to `files/`, not in it; listed in case a build keeps them here.)
     * - `cache`, `tmp`, `temp`, `updates`: caches and the app updater's downloaded APKs.
     * - `.path-check`: the marker WowFiles.isStandardPath writes and deletes at once.
     * - `*.new`, `*.work`, `*.import`, `*.download`, `*.deleting`: the staging folders of jobs in
     *   progress (the client being imported, the server data being made or downloaded, a folder
     *   being deleted). Half-made; every job removes them itself or starts them again.
     * - `*.old`: the previous copy a job keeps while it replaces `server`/`zones`/`turtle`; the
     *   current one is exported.
     */
    val TOP_LEVEL: List<Exclusion> = listOf(
        Exclusion("logs", null, "the servers' logs"),
        Exclusion("run", null, "pid files, sockets and status files of running servers"),
        Exclusion("rootfs", null, "Winlator runtime install (the new app unpacks its own)"),
        Exclusion("imagefs", null, "Winlator runtime install (the new app unpacks its own)"),
        Exclusion("winlator", null, "Winlator runtime install (the new app unpacks its own)"),
        Exclusion("containers", null, "Winlator Windows containers (made again at the first start)"),
        Exclusion("cache", null, "cache"),
        Exclusion("tmp", null, "temporary files"),
        Exclusion("temp", null, "temporary files"),
        Exclusion("updates", null, "downloaded app updates"),
        Exclusion(".path-check", null, "marker written and deleted at once"),
        Exclusion(null, ".new", "folder of a job in progress (half made)"),
        Exclusion(null, ".work", "folder of a job in progress (half made)"),
        Exclusion(null, ".import", "folder of a job in progress (half made)"),
        Exclusion(null, ".download", "partial download"),
        Exclusion(null, ".deleting", "folder being deleted"),
        Exclusion(null, ".old", "previous copy kept while a job replaces the current one"),
    )

    /**
     * Folders inside the environments' own folders (`turtle/`, `felsong/`, `spp-legion/`, `tww/`)
     * that aren't exported either, for the same reasons: logs and run files, partial downloads
     * (`downloads`, `client.download`...), staging, and the Wine prefix the connection patcher
     * makes in Felsong's folder (`.wine`, made again at the next start).
     */
    val ENVIRONMENT_FOLDERS = setOf("turtle", "felsong", "spp-legion", "tww")
    val INSIDE_ENVIRONMENT: List<Exclusion> = listOf(
        Exclusion("logs", null, "the servers' logs"),
        Exclusion("run", null, "pid files, sockets and status files of running servers"),
        Exclusion("downloads", null, "partial downloads"),
        Exclusion("staging", null, "staging of a job in progress"),
        Exclusion(".wine", null, "Wine prefix made again at the next start"),
        Exclusion(null, ".new", "folder of a job in progress (half made)"),
        Exclusion(null, ".work", "folder of a job in progress (half made)"),
        Exclusion(null, ".import", "folder of a job in progress (half made)"),
        Exclusion(null, ".download", "partial download"),
        Exclusion(null, ".deleting", "folder being deleted"),
        Exclusion(null, ".old", "previous copy kept while a job replaces the current one"),
    )

    /**
     * Files left out wherever they are: `.part`/`.tmp` (partial downloads and temporary files),
     * `.pid` and `.sock` (a running server's; the database is stopped before the copy, a stale
     * one would only mislead).
     */
    val FILE_SUFFIXES: List<Exclusion> = listOf(
        Exclusion(null, ".part", "partial download"),
        Exclusion(null, ".tmp", "temporary file"),
        Exclusion(null, ".pid", "process id of a server that has stopped"),
        Exclusion(null, ".sock", "socket of a server that has stopped"),
    )

    /** The reason the directory at [path] (relative to `files/`, '/' separators) is left out, or null. */
    fun directoryReason(path: String): String? {
        val parts = path.split('/')
        if (parts.size == 1) return TOP_LEVEL.firstOrNull { it.matches(parts[0]) }?.reason
        if (parts.size == 2 && parts[0] in ENVIRONMENT_FOLDERS) {
            return INSIDE_ENVIRONMENT.firstOrNull { it.matches(parts[1]) }?.reason
        }
        return null
    }

    /** The reason the regular file at [path] is left out, or null. */
    fun fileReason(path: String): String? {
        val parts = path.split('/')
        if (parts.size == 1) TOP_LEVEL.firstOrNull { it.matches(parts[0]) }?.let { return it.reason }
        return FILE_SUFFIXES.firstOrNull { it.matches(parts.last()) }?.reason
    }

    /**
     * The environment a top-level folder belongs to, for the sizes the screen shows. Anything not
     * named here is [Group.OTHER] and still exported.
     */
    enum class Group(val key: String, val title: String) {
        WRATH("wrath", "Wrath of the Lich King (ChromieCraft and Wrath Remix)"),
        TURTLE("turtle", "OctoWoW (Tortoise-WoW)"),
        LEGION("legion", "Legion (LegionCore)"),
        TWW("tww", "The War Within"),
        OTHER("other", "Other files"),
    }

    /**
     * The game modes the Blizzard app imports, each from a zip of its own ([ZipExporter], `mode.txt` first): Wrath+ is the
     * 3.3.5a game (ChromieCraft inside) and also takes [Group.OTHER], so every file goes into one zip or another.
     */
    enum class Mode(val key: String, val title: String, val fileName: String, val groups: Set<Group>) {
        WRATH_PLUS("wrath-plus", "Wrath+", "WoW-Wrath+.zip", setOf(Group.WRATH, Group.OTHER)),
        LEGION("legion", "Legion", "WoW-Legion.zip", setOf(Group.LEGION)),
        OCTOWOW("octowow", "OctoWoW", "WoW-OctoWoW.zip", setOf(Group.TURTLE)),
        TWW("tww", "The War Within", "WoW-TWW.zip", setOf(Group.TWW)),
    }

    private val WRATH_FOLDERS = setOf(
        "client", "server", "mysql", "etc", "etc-remix", "data", "data-modules", "data-races", "races", "mods",
        "modules", "addons", "ralia", "alternate-female", "base-model", "client-realm", "zones",
    )

    fun groupOf(topLevel: String): Group = when {
        topLevel in WRATH_FOLDERS -> Group.WRATH
        topLevel == "turtle" -> Group.TURTLE
        topLevel == "felsong" || topLevel == "spp-legion" -> Group.LEGION
        topLevel == "tww" -> Group.TWW
        else -> Group.OTHER
    }

    /**
     * The SharedPreferences files that hold the user's choices and are exported to `prefs/`: the
     * selected game, the realm, game options, saved logins and realm links, module and update state.
     * (Others, such as the Winlator runtime's own, are regenerated and not the user's.)
     */
    val PREFERENCES = listOf(
        "environments", "realms", "login", "game", "mods", "music", "github",
        "felsong_settings", "felsong_login", "turtle_settings", "turtle_login",
        "spp", "spp-installation", "spp-login", "tww-installation",
    )
}
