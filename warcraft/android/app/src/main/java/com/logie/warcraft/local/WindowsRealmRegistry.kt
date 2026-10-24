// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.local

import com.winlator.XServerDisplayActivity
import java.lang.ref.WeakReference

/**
 * Winlator has one shared Wine home (its runtime folder, the active container): one local realm's
 * Wine ([WindowsRealmHost], reserved by [reserve]) or one game's screen at a time, except the game's
 * own session of the realm that holds it, which joins the realm's running Wine.
 */
object WindowsRealmRegistry {
    private val realms = linkedMapOf<String, WindowsRealmServer>()
    private var holder: WindowsRealmServer? = null
    private var display = WeakReference<XServerDisplayActivity>(null)

    @Synchronized internal fun register(server: WindowsRealmServer, extra: String) { realms[extra] = server }

    /** The local realms known so far (each registers as it's first used). */
    @Synchronized fun servers(): List<WindowsRealmServer> = realms.values.toList()

    private fun owner(runtime: XServerDisplayActivity) = realms.entries.firstOrNull {
        runtime.intent.getBooleanExtra(it.key, false)
    }?.value

    private fun liveDisplay() = display.get()?.takeUnless { it.isDestroyed || it.isFinishing }

    /**
     * A Winlator screen may open: a realm's game session while that realm's Wine runs and is ready, a
     * game's screen while no realm holds the Wine home; never a second screen.
     */
    @Synchronized fun allow(runtime: XServerDisplayActivity): Boolean {
        val current = liveDisplay()
        if (current != null && current !== runtime) return false
        val target = owner(runtime)
        val allowed = if (target != null) holder === target && target.allowsSession()
            else holder == null && realms.values.none { it.isBusy }
        if (allowed) display = WeakReference(runtime)
        return allowed
    }

    /** A local realm (The War Within) is running or holds the Windows runtime. */
    @Synchronized fun anyBusy(): Boolean = holder != null || realms.values.any { it.isBusy }

    /** [server]'s Wine may start: no game's screen open, no other realm holding or running. */
    @Synchronized internal fun reserve(server: WindowsRealmServer): Boolean {
        if (liveDisplay() != null) return false
        if (holder != null && holder !== server) return false
        if (realms.values.any { it !== server && it.isBusy }) return false
        holder = server
        return true
    }

    @Synchronized internal fun release(server: WindowsRealmServer) {
        if (holder === server) holder = null
    }

    /** [runtime] is a realm's game session joining that realm's running Wine (Winlator patch 0011). */
    @Synchronized fun sharedRuntime(runtime: XServerDisplayActivity): Boolean =
        owner(runtime)?.let { holder === it && it.hostRunning } == true

    @Synchronized fun attached(runtime: XServerDisplayActivity) {
        display = WeakReference(runtime)
        owner(runtime)?.onSessionAttached(runtime)
    }

    /** [runtime] was refused ([allow]): what its screen says (null: Winlator's own words). */
    @Synchronized fun rejected(runtime: XServerDisplayActivity): String? {
        val other = liveDisplay()?.let { it !== runtime } == true
        return owner(runtime)?.onSessionRejected(other)
    }

    /**
     * "Leave the game" in a local realm's session: its game closed with the session, the realm left
     * running (Stop Server ends it). False for a session that isn't a local realm's.
     */
    @Synchronized fun leave(runtime: XServerDisplayActivity): Boolean {
        val server = owner(runtime) ?: return false
        server.leaveClient(runtime)
        return true
    }

    @Synchronized fun detached(runtime: XServerDisplayActivity) {
        if (display.get() === runtime) display.clear()
        realms.values.forEach { it.onSessionDetached(runtime) }
    }
}
