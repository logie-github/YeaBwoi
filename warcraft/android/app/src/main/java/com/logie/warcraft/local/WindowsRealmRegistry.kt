// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.local

import com.winlator.XServerDisplayActivity
import java.lang.ref.WeakReference

/** Winlator has one shared active Wine home. Reserve it before any activity changes containers. */
object WindowsRealmRegistry {
    private val realms = linkedMapOf<String, WindowsRealmServer>()
    private var active = WeakReference<XServerDisplayActivity>(null)

    @Synchronized internal fun register(server: WindowsRealmServer, extra: String) { realms[extra] = server }

    private fun owner(runtime: XServerDisplayActivity) = realms.entries.firstOrNull {
        runtime.intent.getBooleanExtra(it.key, false)
    }?.value

    @Synchronized fun allow(runtime: XServerDisplayActivity): Boolean {
        val current = active.get()?.takeUnless { it.isDestroyed || it.isFinishing }
        if (current != null && current !== runtime) return false
        val target = owner(runtime)
        if (realms.values.any { it !== target && it.isBusy }) return false
        active = WeakReference(runtime)
        return true
    }

    /** A local realm (SPP Legion, The War Within) is running or holds the Windows runtime. */
    @Synchronized fun anyBusy(): Boolean = realms.values.any { it.isBusy }

    @Synchronized internal fun canOpen(server: WindowsRealmServer): Boolean {
        val current = active.get()?.takeUnless { it.isDestroyed || it.isFinishing }
        return (current == null || owner(current) === server) && realms.values.none { it !== server && it.isBusy }
    }

    @Synchronized fun attached(runtime: XServerDisplayActivity) {
        active = WeakReference(runtime)
        owner(runtime)?.onRuntimeAttached(runtime)
    }

    /**
     * "Leave the game" in a local realm's session: its client closed and the launcher in front, the
     * realm left running (Stop Server ends it). False for a session that isn't a local realm's.
     */
    @Synchronized fun leave(runtime: XServerDisplayActivity): Boolean {
        val server = owner(runtime) ?: return false
        server.leaveClient(runtime)
        return true
    }

    @Synchronized fun detached(runtime: XServerDisplayActivity) {
        if (active.get() === runtime) active.clear()
        realms.values.forEach { it.onRuntimeDetached(runtime) }
    }
}
