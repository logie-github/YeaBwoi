// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.logie.pe.ui.PeHomeRoute

/** The title screen ([PeHomeRoute]). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Only a fresh start goes straight into the game, not coming back to the app.
        val autoplay = savedInstanceState == null
        setContent { PeHomeRoute(activity = this, autoplayOnOpen = autoplay) }
    }
}
