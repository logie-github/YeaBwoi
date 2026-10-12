// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.game

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.logie.warcraft.R
import com.logie.warcraft.ui.WowButton
import com.logie.warcraft.ui.WowColors
import com.logie.warcraft.ui.WowSnow
import com.logie.warcraft.ui.WowSpinner
import com.logie.warcraft.ui.WowTheme
import com.logie.warcraft.ui.WowTitle
import com.logie.warcraft.ui.WowWindow

/**
 * The app's own screens over the game, in the launcher's look: the loading screen while Windows
 * and the game start ([GameLoading]), and the menu that four fingers open ([GameMenu]).
 */
private fun fullScreenDialog(activity: Activity, dim: Boolean, content: @Composable () -> Unit): ComponentDialog {
    val dialog = ComponentDialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
    dialog.setContentView(
        ComposeView(activity).apply { setContent { WowTheme(content) } },
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    dialog.window?.let { window ->
        window.setBackgroundDrawable(ColorDrawable(if (dim) 0x99000000.toInt() else AndroidColor.TRANSPARENT))
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        // Full screen like the game under it, the bars a swipe away.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    return dialog
}

/**
 * The loading screen until the game's first window shows: the logo over the dark, snow, a spinner
 * and what's happening. The same calls as the game screen's own loading box, which it replaces
 * (patches/winlator/0004).
 */
class GameLoading(private val activity: Activity) {
    private var dialog: ComponentDialog? = null
    private val text = mutableStateOf("")

    @Synchronized
    fun show(textResId: Int) {
        text.value = activity.getString(textResId)
        if (isShowing()) return
        val created = dialog ?: fullScreenDialog(activity, dim = false) { Loading(text.value) }.also {
            it.setCancelable(false)
            dialog = it
        }
        created.show()
    }

    fun showOnUiThread(textResId: Int) = activity.runOnUiThread { show(textResId) }

    @Synchronized
    fun close() {
        runCatching { dialog?.dismiss() }
    }

    fun closeOnUiThread() = activity.runOnUiThread { close() }

    fun isShowing(): Boolean = dialog?.isShowing == true
}

@Composable
private fun Loading(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(WowColors.Abyss),
        contentAlignment = Alignment.Center,
    ) {
        WowSnow(Modifier.fillMaxSize(), alpha = 0.6f)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Image(
                painterResource(R.drawable.wow_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(320.dp),
            )
            WowSpinner(48.dp)
            Text(text, style = MaterialTheme.typography.titleMedium, color = WowColors.Mist, textAlign = TextAlign.Center)
        }
    }
}

/** The game's menu (four fingers): the keyboard, back to the game, or leave it for the launcher. */
object GameMenu {
    fun show(activity: Activity, onKeyboard: () -> Unit, onLeave: () -> Unit) {
        var dialog: ComponentDialog? = null
        dialog = fullScreenDialog(activity, dim = true) {
            Menu(
                onKeyboard = { dialog?.dismiss(); onKeyboard() },
                onBack = { dialog?.dismiss() },
                onLeave = { dialog?.dismiss(); onLeave() },
            )
        }
        dialog.show()
    }
}

@Composable
private fun Menu(onKeyboard: () -> Unit, onBack: () -> Unit, onLeave: () -> Unit) {
    // A tap beside the window closes the menu.
    val outside = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = outside, indication = null, onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        WowWindow(
            Modifier
                .widthIn(max = 380.dp)
                .padding(24.dp)
                // Taps inside the window stay there.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            opaque = true,
            padding = PaddingValues(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WowTitle(stringResource(R.string.wow_game_menu))
                WowButton(stringResource(R.string.wow_game_menu_back), Modifier.fillMaxWidth(), onClick = onBack)
                WowButton(stringResource(R.string.wow_game_menu_keyboard), Modifier.fillMaxWidth(), primary = false, onClick = onKeyboard)
                WowButton(stringResource(R.string.wow_game_menu_leave), Modifier.fillMaxWidth(), primary = false, onClick = onLeave)
                Text(
                    stringResource(R.string.wow_game_menu_leave_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = WowColors.Mist,
                )
            }
        }
    }
}
