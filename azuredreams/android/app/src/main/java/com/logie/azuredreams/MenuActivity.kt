// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.logie.azuredreams.ui.AdMenuScreen
import com.logie.azuredreams.ui.AdTheme

/**
 * The in-game menu, over the game, which pauses while it's open. What the player picks goes back
 * to [GameActivity] as an [Action]; settings (speed, picture) are saved right away and the game
 * applies them when it carries on.
 */
class MenuActivity : ComponentActivity() {
    sealed interface Action {
        data class Save(val slot: Int) : Action
        data class Load(val slot: Int) : Action
        data object Reset : Action
        data object Quit : Action
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            AdTheme {
                AdMenuScreen(
                    onResume = { close(null) },
                    onAction = { close(it) },
                    onUpdates = { AdUpdateActivity.launch(this) },
                )
            }
        }
    }

    private fun close(action: Action?) {
        if (action != null) {
            val data = Intent()
            when (action) {
                is Action.Save -> data.putExtra(EXTRA_ACTION, ACTION_SAVE).putExtra(EXTRA_SLOT, action.slot)
                is Action.Load -> data.putExtra(EXTRA_ACTION, ACTION_LOAD).putExtra(EXTRA_SLOT, action.slot)
                Action.Reset -> data.putExtra(EXTRA_ACTION, ACTION_RESET)
                Action.Quit -> data.putExtra(EXTRA_ACTION, ACTION_QUIT)
            }
            setResult(RESULT_OK, data)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val EXTRA_ACTION = "com.logie.azuredreams.ACTION"
        private const val EXTRA_SLOT = "com.logie.azuredreams.SLOT"
        private const val ACTION_SAVE = "save"
        private const val ACTION_LOAD = "load"
        private const val ACTION_RESET = "reset"
        private const val ACTION_QUIT = "quit"

        fun actionOf(resultCode: Int, data: Intent?): Action? {
            if (resultCode != RESULT_OK || data == null) return null
            val slot = data.getIntExtra(EXTRA_SLOT, 1)
            return when (data.getStringExtra(EXTRA_ACTION)) {
                ACTION_SAVE -> Action.Save(slot)
                ACTION_LOAD -> Action.Load(slot)
                ACTION_RESET -> Action.Reset
                ACTION_QUIT -> Action.Quit
                else -> null
            }
        }
    }
}
