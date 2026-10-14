// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.logie.warcraft.game.Game
import com.logie.warcraft.ui.WowHomeRoute

/** The launcher ([WowHomeRoute]), with its music ([TitleMusic]) while it's on screen; [Game] starts from it. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Only a fresh start checks for an update, not coming back. Nothing starts until Press start.
        val fresh = savedInstanceState == null
        TitleMusic.load(this)
        setContent { WowHomeRoute(activity = this, checkUpdateOnOpen = fresh) }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        TitleMusic.onShown(this)
        Game.onLauncherShown(this)
        com.logie.warcraft.turtle.TurtleGame.onLauncherShown(this)
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
