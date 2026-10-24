// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.local

import android.content.Context
import com.logie.warcraft.local.core.HeadlessWine
import com.winlator.WinePrefixSetup
import com.winlator.container.Container
import com.winlator.core.DefaultVersion
import com.winlator.xenvironment.RootFS
import java.io.File
import java.io.IOException

/**
 * A local realm's Wine with no screen ([HeadlessWine]): its container's prefix made ready as a
 * game's session makes it, then its programs (the database, the servers, their tools) started by the
 * app itself. Nothing of it is an Activity, so Android closing, trimming or refusing a screen never
 * ends the realm; the game's session (RealmSessionActivity) joins its Wine server and ends only its
 * own programs. Box64 is the one the game's session asks for (DefaultVersion.BOX64_LEGION), so a
 * session never puts another over the running programs'.
 */
class WindowsRealmHost(private val name: String) {
    @Volatile private var wine: HeadlessWine? = null
    @Volatile var container: Container? = null
        private set

    /** The realm's Wine is up (prepared, its server started) and not yet stopped. */
    val running: Boolean get() = wine != null

    /**
     * Prepares [container] (none of the app's Wine programs may be running: the registry and the
     * runtime's files are changed) and starts its Wine server. [log] receives what Wine prints.
     */
    fun start(context: Context, container: Container, log: File) {
        if (wine != null) throw IOException("The realm's Windows runtime is already running.")
        val app = context.applicationContext
        val rootDir = RootFS.find(app).rootDir
        val box64 = File(rootDir, "usr/local/bin/box64").path
        WinePrefixSetup.prepareHeadless(app, container, DefaultVersion.BOX64_LEGION)
        val environment = WinePrefixSetup.headlessEnvironment(app, container)
        val bin = WinePrefixSetup.wineBinDir(app, container)
        val host = HeadlessWine(
            launcher = listOf(box64),
            wine = "$bin/wine",
            wineserver = "$bin/wineserver",
            environment = environment.toStringArray().associate { it.substringBefore('=') to it.substringAfter('=', "") },
            workingDirectory = rootDir,
            log = log,
            marker = MARKER to name,
            kill = { pid -> android.os.Process.killProcess(pid) },
        )
        if (WinePrefixSetup.needsPrefixUpdate(container)) {
            // (after a runtime update: as Winlator does before a session, wineboot -u first)
            WinePrefixSetup.prefixUpdateStarting(app)
            val status = host.run(listOf("$bin/wine", "wineboot", "-u"), 600, mapOf("WINEDLLOVERRIDES" to "mscoree,mshtml=d"))
            // (its Wine server gone, having saved the registry, before the registry is changed again)
            host.run(listOf("$bin/wineserver", "-w"), 60)
            WinePrefixSetup.prefixUpdateEnded(app, container, status)
            if (status != 0) throw IOException("The realm's Windows prefix could not be updated (wineboot exited with $status).")
            // (what the update reset: made again before the server starts)
            WinePrefixSetup.prepareHeadless(app, container, DefaultVersion.BOX64_LEGION)
        }
        // (which Box64 the realm's programs run on, first in its log)
        runCatching { log.appendText("[app] " + com.winlator.xenvironment.components.GuestProgramLauncherComponent.box64Identity(app) + "\n") }
        // (0: started; 2: one runs already for the prefix, which the realm's programs join)
        val server = host.startServer()
        runCatching { log.appendText("[app] wineserver -p: $server\n") }
        if (server != 0 && server != 2) throw IOException("The realm's Wine server didn't start (status $server). See runtime/host.log.")
        this.container = container
        wine = host
    }

    /** Runs R:\runtime\[script].cmd (or [script] if it is a Windows path) as [name]. */
    fun run(name: String, script: String = "R:\\runtime\\$name.cmd") {
        val host = wine ?: throw IOException("The realm's Windows runtime is not running.")
        host.startScript(name, script)
    }

    /** [name]'s process ended without its script's exit file: its Unix status (128 + n: signal n). */
    fun exitStatus(name: String): Int? = wine?.exitStatus(name)

    /** The realm's Wine processes still running. */
    fun processes(): List<Int> = wine?.processes() ?: HeadlessWine.processesWith(File("/proc"), MARKER, name)

    /**
     * Ends the realm's Wine: its server told to end every program (`wineserver -k`), then whatever of
     * the realm is still there after [seconds] ended by force. False when something stayed.
     */
    fun stop(seconds: Int = 15): Boolean {
        val host = wine
        if (host != null) runCatching { host.stopServer() }
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (processes().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(250)
        if (processes().isNotEmpty()) {
            host?.killAll() ?: processes().forEach { android.os.Process.killProcess(it) }
            Thread.sleep(500)
        }
        val left = processes().isEmpty()
        if (left) {
            wine = null
            container = null
        }
        return left
    }

    /** Every process of the realm ended at once (Force Stop: nothing is saved). */
    fun kill() {
        wine?.killAll() ?: processes().forEach { android.os.Process.killProcess(it) }
    }

    companion object {
        /** The variable every program of a realm carries (its value: the realm's name). */
        const val MARKER = "WINLATOR_REALM"
    }
}
