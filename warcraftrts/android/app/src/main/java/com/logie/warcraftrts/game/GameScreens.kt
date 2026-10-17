// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraftrts.game

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.logie.warcraftrts.R
import com.logie.warcraftrts.library.CdKeys
import com.logie.warcraftrts.library.Title
import com.logie.warcraftrts.ui.LocalLook
import com.logie.warcraftrts.ui.RtsButton
import com.logie.warcraftrts.ui.RtsLook
import com.logie.warcraftrts.ui.RtsSpinner
import com.logie.warcraftrts.ui.RtsTheme
import com.logie.warcraftrts.ui.RtsTitle
import com.logie.warcraftrts.ui.RtsWeather
import com.logie.warcraftrts.ui.RtsWindow

/**
 * The app's own screens over the game, in the game's look: the loading screen while Windows and
 * the game start ([GameLoading]), the menu that four fingers open ([GameMenu]), and the text prompt
 * with the phone's keyboard ([GamePrompt]).
 */
private fun fullScreenDialog(activity: Activity, title: Title, dim: Boolean, content: @Composable () -> Unit): ComponentDialog {
    val dialog = ComponentDialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
    dialog.setContentView(
        ComposeView(activity).apply { setContent { RtsTheme(RtsLook.of(title), content) } },
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    dialog.window?.let { window ->
        window.setBackgroundDrawable(ColorDrawable(if (dim) 0x99000000.toInt() else AndroidColor.TRANSPARENT))
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    return dialog
}

private fun Activity.title(): Title = Title.of(intent?.getStringExtra(GestureView.EXTRA_TITLE)) ?: Title.TFT

/**
 * The loading screen until the game's first window shows: the game's loading picture, its logo, a
 * spinner and what's happening, its weather over it. The same calls as the game screen's own
 * loading box, which it replaces (patches/winlator/0004).
 */
class GameLoading(private val activity: Activity) {
    private val title = activity.title()
    private var dialog: ComponentDialog? = null
    private val text = mutableStateOf("")

    @Synchronized
    fun show(textResId: Int) {
        text.value = activity.getString(textResId)
        if (dialog?.isShowing == true) return
        val created = dialog ?: fullScreenDialog(activity, title, dim = false) { Loading(text.value) }.also {
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
internal fun Loading(text: String) {
    val look = LocalLook.current
    Box(
        Modifier
            .fillMaxSize()
            .background(look.abyss),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(look.loading),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = look.loadingAlignment,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(look.abyss.copy(alpha = 0.35f), look.abyss.copy(alpha = 0.8f)))))
        RtsWeather(Modifier.fillMaxSize(), alpha = 0.7f)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Image(
                painterResource(look.logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(340.dp),
            )
            RtsSpinner(48.dp)
            Text(text, style = MaterialTheme.typography.titleMedium, color = look.mist, textAlign = TextAlign.Center)
        }
    }
}

/**
 * The game's menu (four fingers): back to the game, the keyboard, chat, the CD keys that came with
 * the game (typed into the game's key box), or leave the game for the launcher.
 */
object GameMenu {
    fun show(
        activity: Activity,
        title: Title,
        keys: List<CdKeys.Found>,
        onKeyboard: () -> Unit,
        onChat: () -> Unit,
        onTypeKey: (String) -> Unit,
        onLeave: () -> Unit,
    ) {
        var dialog: ComponentDialog? = null
        fun close(then: () -> Unit) = { dialog?.dismiss(); then() }
        dialog = fullScreenDialog(activity, title, dim = true) {
            Menu(
                keys = keys,
                onBack = close {},
                onKeyboard = close(onKeyboard),
                onChat = close(onChat),
                onTypeKey = { key -> dialog?.dismiss(); onTypeKey(key) },
                onLeave = close(onLeave),
            )
        }
        dialog.show()
    }
}

@Composable
private fun Menu(
    keys: List<CdKeys.Found>,
    onBack: () -> Unit,
    onKeyboard: () -> Unit,
    onChat: () -> Unit,
    onTypeKey: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val look = LocalLook.current
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        RtsWindow(
            Modifier
                .widthIn(max = 420.dp)
                .padding(24.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            opaque = true,
            padding = PaddingValues(20.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RtsTitle(stringResource(R.string.rts_game_menu))
                RtsButton(stringResource(R.string.rts_game_menu_back), Modifier.fillMaxWidth(), onClick = onBack)
                RtsButton(stringResource(R.string.rts_game_menu_keyboard), Modifier.fillMaxWidth(), primary = false, onClick = onKeyboard)
                RtsButton(stringResource(R.string.rts_game_menu_chat), Modifier.fillMaxWidth(), primary = false, onClick = onChat)
                if (keys.isNotEmpty()) {
                    Text(stringResource(R.string.rts_game_menu_keys), style = MaterialTheme.typography.bodySmall, color = look.mist)
                    for (key in keys) {
                        RtsButton(
                            if (key.label.isNotEmpty()) "${key.label}: ${key.key}" else key.key,
                            Modifier.fillMaxWidth(),
                            primary = false,
                        ) { onTypeKey(key.key) }
                    }
                }
                RtsButton(stringResource(R.string.rts_game_menu_leave), Modifier.fillMaxWidth(), primary = false, onClick = onLeave)
                Text(stringResource(R.string.rts_game_menu_leave_note), style = MaterialTheme.typography.bodySmall, color = look.mist)
            }
        }
    }
}

/**
 * The text prompt: a box at the top of the screen with the phone's keyboard under it, for a chat
 * line, a cheat code, a name or a CD key. The game behind stays as it is; what's typed goes to the
 * game when it's sent ([GestureView]).
 */
object GamePrompt {
    fun show(
        activity: Activity,
        title: Title,
        chat: Boolean,
        onSend: (String) -> Unit,
        onCancel: () -> Unit,
        onBackspace: () -> Unit,
    ): ComponentDialog {
        var dialog: ComponentDialog? = null
        var finished = false
        fun finish(text: String?) {
            if (finished) return
            finished = true
            dialog?.dismiss()
            if (text != null) onSend(text) else onCancel()
        }
        dialog = fullScreenDialog(activity, title, dim = false) {
            Prompt(chat, onSend = { finish(it) }, onCancel = { finish(null) }, onBackspace = onBackspace)
        }
        dialog.setOnCancelListener { finish(null) }
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        dialog.show()
        return dialog
    }
}

@Composable
private fun Prompt(chat: Boolean, onSend: (String) -> Unit, onCancel: () -> Unit, onBackspace: () -> Unit) {
    val look = LocalLook.current
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.TopCenter,
    ) {
        RtsWindow(
            Modifier
                .padding(top = 18.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            opaque = true,
            padding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RtsTitle(stringResource(if (chat) R.string.rts_prompt_chat else R.string.rts_prompt_text))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(look.abyss)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (text.isEmpty()) {
                        Text(
                            stringResource(if (chat) R.string.rts_prompt_chat_hint else R.string.rts_prompt_text_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = look.mist,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.replace("\n", "") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = look.text),
                        cursorBrush = SolidColor(look.accent),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            autoCorrectEnabled = false,
                            imeAction = if (chat) ImeAction.Send else ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onAny = { onSend(text) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            // With nothing left here, Backspace goes on into the game's box.
                            .onPreviewKeyEvent { event ->
                                if (event.key != Key.Backspace || text.isNotEmpty()) return@onPreviewKeyEvent false
                                if (event.type == KeyEventType.KeyDown) onBackspace()
                                true
                            },
                    )
                }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RtsButton(stringResource(R.string.rts_prompt_backspace), primary = false, onClick = onBackspace)
                    RtsButton(stringResource(R.string.rts_prompt_cancel), primary = false, onClick = onCancel)
                    RtsButton(stringResource(if (chat) R.string.rts_prompt_send else R.string.rts_prompt_type)) { onSend(text) }
                }
            }
        }
    }
}
