// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww

import com.logie.warcraft.local.WindowsRealmRegistry
import com.logie.warcraft.local.WindowsRealmServer
import com.winlator.XServerDisplayActivity

/** Existing Draconic controls, backed by the shared downloaded-realm manager. */
object TwwServer : WindowsRealmServer(TwwIntegration) {
    // Kept as the Java entry points used by the pinned runtime's existing lifecycle patch.
    @JvmStatic fun runtimeAttached(runtime: XServerDisplayActivity) = WindowsRealmRegistry.attached(runtime)
    @JvmStatic fun runtimeDetached(runtime: XServerDisplayActivity) = WindowsRealmRegistry.detached(runtime)
    @JvmStatic fun allowRuntimeStart(runtime: XServerDisplayActivity) = WindowsRealmRegistry.allow(runtime)
    @JvmStatic fun leaveGame(runtime: XServerDisplayActivity) = WindowsRealmRegistry.leave(runtime)
    // (Winlator patch 0011: a realm's game session joining the realm's running Wine, and one refused)
    @JvmStatic fun sharedRuntime(runtime: XServerDisplayActivity) = WindowsRealmRegistry.sharedRuntime(runtime)
    @JvmStatic fun runtimeRejected(runtime: XServerDisplayActivity) = WindowsRealmRegistry.rejected(runtime)
}
