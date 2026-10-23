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

    @Synchronized internal fun canOpen(server: WindowsRealmServer): Boolean {
        val current = active.get()?.takeUnless { it.isDestroyed || it.isFinishing }
        return (current == null || owner(current) === server) && realms.values.none { it !== server && it.isBusy }
    }

    @Synchronized fun attached(runtime: XServerDisplayActivity) {
        active = WeakReference(runtime)
        owner(runtime)?.onRuntimeAttached(runtime)
    }

    @Synchronized fun detached(runtime: XServerDisplayActivity) {
        if (active.get() === runtime) active.clear()
        realms.values.forEach { it.onRuntimeDetached(runtime) }
    }
}
