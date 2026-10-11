// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.logie.warcraft.ui.WowHomeRoute

/** The launcher ([WowHomeRoute]), with its music ([TitleMusic]) while it's on screen. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Only a fresh start goes straight on (update check, then the server), not coming back.
        val autoplay = savedInstanceState == null
        TitleMusic.load(this)
        setContent { WowHomeRoute(activity = this, autoplayOnOpen = autoplay) }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        TitleMusic.onShown(this)
    }

    override fun onStop() {
        visible = false
        TitleMusic.onHidden()
        super.onStop()
    }

    companion object {
        /** The launcher is on screen: finished imports show there instead of in a notification. */
        @Volatile var visible = false
    }
}
