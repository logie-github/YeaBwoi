// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.logie.azuredreams.ui.AdHomeRoute

/** The title screen ([AdHomeRoute]). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Only a fresh start goes straight into the game, not coming back to the app.
        val autoplay = savedInstanceState == null
        setContent { AdHomeRoute(activity = this, autoplayOnOpen = autoplay) }
    }
}
