// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww

import android.content.Context
import com.logie.warcraft.local.ManagedWindowsRealm
import com.logie.warcraft.local.WindowsRealmRegistry
import com.logie.warcraft.local.WindowsRealmServer
import com.logie.warcraft.tww.core.LocalRealmSpec
import com.logie.warcraft.tww.core.TwwRealm
import com.winlator.XServerDisplayActivity

/** Existing Draconic controls, backed by the shared downloaded-realm manager. */
object TwwServer : WindowsRealmServer(object : ManagedWindowsRealm {
    override val displayName = TwwRealm.DISPLAY_NAME
    override val sessionExtra = TwwGame.SESSION_EXTRA
    override val spec = LocalRealmSpec.WAR_WITHIN
    override val installationBusy get() = TwwJobs.busy
    override fun root(context: Context) = TwwFiles.layout(context).root
    override fun report(context: Context) = TwwFiles.report(context)
    override fun localClientConfigured(context: Context) = TwwGame.localConfigured(context)
    override suspend fun openSession(context: Context) = TwwGame.openSession(context)
    override fun showSession(context: Context) = TwwGame.showSession(context)
}) {
    // Kept as the Java entry points used by the pinned runtime's existing lifecycle patch.
    @JvmStatic fun runtimeAttached(runtime: XServerDisplayActivity) = WindowsRealmRegistry.attached(runtime)
    @JvmStatic fun runtimeDetached(runtime: XServerDisplayActivity) = WindowsRealmRegistry.detached(runtime)
    @JvmStatic fun allowRuntimeStart(runtime: XServerDisplayActivity) = WindowsRealmRegistry.allow(runtime)
}
