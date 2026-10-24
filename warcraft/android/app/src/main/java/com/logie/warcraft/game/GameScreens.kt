// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.game

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.logie.warcraft.R
import com.logie.warcraft.felsong.ui.FelsongColors
import com.logie.warcraft.felsong.ui.FelsongMotes
import com.logie.warcraft.felsong.ui.FelsongSpinner
import com.logie.warcraft.felsong.ui.FelsongTheme
import com.logie.warcraft.turtle.ui.TurtleColors
import com.logie.warcraft.turtle.ui.TurtleMotes
import com.logie.warcraft.turtle.ui.TurtleSpinner
import com.logie.warcraft.turtle.ui.TurtleTheme
import com.logie.warcraft.ui.WowButton
import com.logie.warcraft.ui.WowColors
import com.logie.warcraft.ui.WowSnow
import com.logie.warcraft.ui.WowSpinner
import com.logie.warcraft.ui.WowSwitchRow
import com.logie.warcraft.ui.WowTheme
import com.logie.warcraft.ui.WowTitle
import com.logie.warcraft.ui.WowWindow
import kotlin.math.PI
import kotlin.math.cos

/**
 * The app's own screens over the game, in the launcher's look: the loading screen while Windows
 * and the game start ([GameLoading]), the menu that four fingers open ([GameMenu]), and the text
 * prompt with the phone's keyboard ([GamePrompt]).
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
 * The loading screen until the game's first window shows ("Entering Azeroth"): the game's picture,
 * its logo, a spinner and what's happening. The 3.3.5a game's: the Lich King before Icecrown
 * Citadel, snow falling. Turtle WoW's ([GestureView.ENVIRONMENT_TURTLE]): the old map of Kalimdor
 * and the Eastern Kingdoms with warm motes rising, its logo, in its own colours. Felsong's
 * ([GestureView.ENVIRONMENT_FELSONG]): Illidan before the Black Temple with fel motes rising, its
 * logo breathing as on felsong.gg's own loading screen. The same calls as the game screen's own
 * loading box, which it replaces (patches/winlator/0004).
 */
class GameLoading(private val activity: Activity) {
    private val environment = activity.intent?.getStringExtra(GestureView.EXTRA_ENVIRONMENT)

    private var dialog: ComponentDialog? = null
    private val text = mutableStateOf("")

    @Synchronized
    fun show(textResId: Int) {
        text.value = activity.getString(textResId)
        if (isShowing()) return
        val created = dialog ?: fullScreenDialog(activity, dim = false) {
            when (environment) {
                GestureView.ENVIRONMENT_TURTLE -> TurtleTheme { TurtleLoading(text.value) }
                GestureView.ENVIRONMENT_FELSONG -> FelsongTheme { FelsongLoading(text.value) }
                else -> Loading(text.value)
            }
        }.also {
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
    Box(
        Modifier
            .fillMaxSize()
            .background(WowColors.Abyss),
        contentAlignment = Alignment.Center,
    ) {
        // The launcher's picture, the Lich King a third of the way in, darkened for the words.
        Image(
            painterResource(R.drawable.wow_header),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = BiasAlignment(-0.42f, -0.4f),
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(WowColors.Abyss.copy(alpha = 0.55f), WowColors.Abyss.copy(alpha = 0.85f)))))
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

@Composable
internal fun TurtleLoading(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(TurtleColors.Night),
        contentAlignment = Alignment.Center,
    ) {
        // The map fills the screen, its sides past the edges (it's wider than any screen), darkened
        // a little for the words.
        Image(
            painterResource(R.drawable.turtle_loading),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(TurtleColors.Night.copy(alpha = 0.35f)))
        TurtleMotes(Modifier.fillMaxSize(), alpha = 0.8f)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Image(
                painterResource(R.drawable.turtle_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(360.dp),
            )
            TurtleSpinner(48.dp)
            Text(text, style = MaterialTheme.typography.titleMedium, color = TurtleColors.Parchment, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun FelsongLoading(text: String) {
    // The logo rising a little and dimming, and back, every two seconds: felsong.gg's own loading
    // screen (loading.css, `scale`: translateY(-8px) at the half; it dims to 0.5 there, over a
    // plain veil: here over the picture, to 0.75, so it stays readable).
    val motion = rememberInfiniteTransition(label = "logo")
    val beat by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(2_000, easing = LinearEasing)), label = "beat")
    val lift = (1f - cos(beat * 2 * PI).toFloat()) / 2f
    Box(
        Modifier
            .fillMaxSize()
            .background(FelsongColors.Soot),
        contentAlignment = Alignment.Center,
    ) {
        // The picture fills the screen, its sides past the edges on a phone (it's 2.37:1), Illidan
        // kept in: his side of it is the one cropped least. Darkened a little for the words.
        Image(
            painterResource(R.drawable.felsong_loading),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = BiasAlignment(0.3f, 0f),
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(FelsongColors.Soot.copy(alpha = 0.25f), FelsongColors.Soot.copy(alpha = 0.55f)))))
        FelsongMotes(Modifier.fillMaxSize(), alpha = 0.9f)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Image(
                painterResource(R.drawable.felsong_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(380.dp).graphicsLayer {
                    translationY = -8.dp.toPx() * lift
                    alpha = 1f - 0.25f * lift
                },
            )
            FelsongSpinner(48.dp)
            Text(text, style = MaterialTheme.typography.titleMedium, color = FelsongColors.Bone, textAlign = TextAlign.Center)
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

/**
 * The game's text prompt: a box at the top of the screen with the phone's keyboard under it, for
 * the password, a character's name, a chat line or any text box. The game behind stays as it is
 * (its screen isn't resized or moved); what's typed goes to the game when it's sent ([GestureView]).
 */
object GamePrompt {
    enum class Kind(
        val title: Int,
        val hint: Int,
        val action: Int,
        val imeAction: ImeAction,
        val password: Boolean = false,
        /** Offers to keep it on the phone and log in by itself next time (the password). */
        val remember: Boolean = false,
    ) {
        PASSWORD(R.string.wow_prompt_password, R.string.wow_prompt_password_hint, R.string.wow_prompt_login, ImeAction.Go, password = true, remember = true),
        NAME(R.string.wow_prompt_name, R.string.wow_prompt_name_hint, R.string.wow_prompt_type, ImeAction.Done),
        CHAT(R.string.wow_prompt_chat, R.string.wow_prompt_chat_hint, R.string.wow_prompt_send, ImeAction.Send),
        TEXT(R.string.wow_prompt_text, R.string.wow_prompt_text_hint, R.string.wow_prompt_type, ImeAction.Done),
    }

    /**
     * Shows the prompt; [onSend] gets the text and whether to remember it ([Kind.remember]),
     * [onCancel] runs when it's closed without sending. [onBackspace] deletes a character in the
     * game's own text box: the keyboard's Backspace once the prompt's box is empty, or ⌫.
     */
    fun show(
        activity: Activity,
        kind: Kind,
        onSend: (String, Boolean) -> Unit,
        onCancel: () -> Unit,
        onBackspace: () -> Unit,
    ): ComponentDialog {
        var dialog: ComponentDialog? = null
        var finished = false
        fun finish(text: String?, remember: Boolean) {
            if (finished) return
            finished = true
            dialog?.dismiss()
            if (text != null) onSend(text, remember) else onCancel()
        }
        dialog = fullScreenDialog(activity, dim = false) {
            Prompt(kind, onSend = { text, remember -> finish(text, remember) }, onCancel = { finish(null, false) }, onBackspace = onBackspace)
        }
        dialog.setOnCancelListener { finish(null, false) }
        // The keyboard comes up over the game without moving or shrinking anything.
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        dialog.show()
        return dialog
    }
}

@Composable
private fun Prompt(kind: GamePrompt.Kind, onSend: (String, Boolean) -> Unit, onCancel: () -> Unit, onBackspace: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // A tap beside the box closes it.
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.TopCenter,
    ) {
        WowWindow(
            Modifier
                .padding(top = 18.dp, start = 16.dp, end = 16.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            opaque = true,
            padding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WowTitle(stringResource(kind.title))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(WowColors.Abyss)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (text.isEmpty()) {
                        Text(stringResource(kind.hint), style = MaterialTheme.typography.bodyLarge, color = WowColors.Mist)
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.replace("\n", "") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = WowColors.Text),
                        cursorBrush = SolidColor(WowColors.Ice),
                        visualTransformation = if (kind.password) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (kind.password) KeyboardType.Password else KeyboardType.Ascii,
                            autoCorrectEnabled = false,
                            imeAction = kind.imeAction,
                        ),
                        keyboardActions = KeyboardActions(onAny = { onSend(text, keep) }),
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
                if (kind.remember) {
                    WowSwitchRow(stringResource(R.string.wow_prompt_remember), stringResource(R.string.wow_prompt_remember_summary), keep) { keep = it }
                }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!kind.password) {
                        WowButton(stringResource(R.string.wow_prompt_backspace), primary = false, onClick = onBackspace)
                    }
                    WowButton(stringResource(R.string.wow_prompt_cancel), primary = false, onClick = onCancel)
                    WowButton(stringResource(kind.action)) { onSend(text, keep) }
                }
                if (!kind.password) {
                    Text(stringResource(R.string.wow_prompt_backspace_note), style = MaterialTheme.typography.bodySmall, color = WowColors.Mist)
                }
            }
        }
    }
}
