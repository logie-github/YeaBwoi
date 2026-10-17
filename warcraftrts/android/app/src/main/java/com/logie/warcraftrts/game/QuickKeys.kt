// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraftrts.game

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.logie.warcraftrts.R
import com.logie.warcraftrts.library.Install
import com.logie.warcraftrts.library.Title
import com.logie.warcraftrts.ui.LocalLook
import com.logie.warcraftrts.ui.RtsDisplayStyle
import com.logie.warcraftrts.ui.RtsLook
import com.logie.warcraftrts.ui.RtsTheme
import com.logie.warcraftrts.ui.drawFrame
import com.winlator.xserver.XKeycode

/**
 * The quick keys: what these games do with a keyboard, in a strip at the top of the screen (or the
 * bottom) that a three-finger tap shows and hides. The game goes on under it and the rest of the
 * screen keeps its gestures. On it:
 *
 * - **Shift**, held until tapped again: orders queue up (Shift-click), clicks add to the selection.
 *   **Ctrl** and **Alt** the same (Alt shows the units' health bars in Warcraft III).
 * - **1 to 0**: a tap selects that control group, a hold makes the selection that group (Ctrl+1...).
 * - Warcraft III's heroes (**F1 F2 F3**: a double tap centres on the hero), **Tab** (the next kind
 *   of unit in the selection), **Space** (the last event), **F10** (the game's menu), **Esc**.
 * - **Chat** (Enter, then a line or a cheat code typed on the phone's keyboard) and **Type**.
 */
class QuickKeys(
    private val activity: AppCompatActivity,
    private val title: Title,
    private val view: GestureView,
) {
    private var dialog: ComponentDialog? = null
    private val atTop = mutableStateOf(true)
    /** Bumped when a latched key changes, so the strip redraws it. */
    private val latchTick = mutableStateOf(0)

    fun toggle() {
        if (dialog?.isShowing == true) hide() else show()
    }

    fun hide() {
        runCatching { dialog?.dismiss() }
        dialog = null
    }

    private fun show() {
        val created = ComponentDialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        created.setContentView(
            ComposeView(activity).apply {
                setContent { RtsTheme(RtsLook.of(title)) { Strip() } }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        created.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(if (atTop.value) Gravity.TOP else Gravity.BOTTOM)
            // Touches beside the strip go to the game; the strip never takes the keyboard.
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        created.setCanceledOnTouchOutside(false)
        created.show()
        dialog = created
    }

    private fun move() {
        atTop.value = !atTop.value
        dialog?.window?.setGravity(if (atTop.value) Gravity.TOP else Gravity.BOTTOM)
    }

    @Composable
    private fun Strip() {
        val look = LocalLook.current
        latchTick.value
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .drawBehind {
                        drawRect(look.night.copy(alpha = 0.82f))
                        drawFrame(look.gold, look.steel, alpha = 0.8f, arm = 6.dp.toPx())
                    }
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Latch(stringResource(R.string.rts_keys_shift), XKeycode.KEY_SHIFT_L)
                Latch(stringResource(R.string.rts_keys_ctrl), XKeycode.KEY_CTRL_L)
                Latch(stringResource(R.string.rts_keys_alt), XKeycode.KEY_ALT_L)
                Gap()
                for ((label, key) in GROUPS) {
                    KeyButton(label, onHold = { view.tapKeyWith(XKeycode.KEY_CTRL_L, key) }) { view.tapKey(key) }
                }
                Gap()
                if (title.install == Install.WC3) {
                    KeyButton("F1") { view.tapKey(XKeycode.KEY_F1) }
                    KeyButton("F2") { view.tapKey(XKeycode.KEY_F2) }
                    KeyButton("F3") { view.tapKey(XKeycode.KEY_F3) }
                    KeyButton(stringResource(R.string.rts_keys_tab)) { view.tapKey(XKeycode.KEY_TAB) }
                    KeyButton(stringResource(R.string.rts_keys_space)) { view.tapKey(XKeycode.KEY_SPACE) }
                    KeyButton("F10") { view.tapKey(XKeycode.KEY_F10) }
                    Gap()
                }
                KeyButton(stringResource(R.string.rts_keys_esc)) { view.tapKey(XKeycode.KEY_ESC) }
                KeyButton(stringResource(R.string.rts_keys_chat)) { view.askText(chat = true) }
                KeyButton(stringResource(R.string.rts_keys_type)) { view.askText(chat = false) }
                Gap()
                KeyButton(if (atTop.value) "▼" else "▲") { move() }
                KeyButton("✕") { hide() }
            }
        }
    }

    @Composable
    private fun Gap() = Spacer(Modifier.width(6.dp))

    @Composable
    private fun Latch(label: String, key: XKeycode) {
        val on = view.isLatched(key)
        KeyButton(label, lit = on) {
            view.setLatched(key, !on)
            latchTick.value++
        }
    }

    @Composable
    private fun KeyButton(label: String, lit: Boolean = false, onHold: (() -> Unit)? = null, onTap: () -> Unit) {
        val look = LocalLook.current
        val pressed = remember { mutableStateOf(false) }
        Box(
            Modifier
                .height(40.dp)
                .widthIn(min = 40.dp)
                .drawBehind {
                    drawRect(if (lit) look.frost else look.deep.copy(alpha = 0.7f))
                    if (pressed.value || lit) drawRect(look.accent.copy(alpha = if (lit) 0.25f else 0.18f))
                    drawFrame(if (lit) look.gold else look.mist, look.steel, alpha = 0.75f, arm = 5.dp.toPx(), stroke = 1.dp.toPx())
                }
                .pointerInput(onHold, onTap) {
                    detectTapGestures(
                        onPress = {
                            pressed.value = true
                            tryAwaitRelease()
                            pressed.value = false
                        },
                        onLongPress = onHold?.let { hold -> { _ -> view.feedback(); hold() } },
                        onTap = { onTap() },
                    )
                }
                .padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = RtsDisplayStyle.copy(fontSize = 13.sp, color = if (lit) look.glow else look.text),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }

    private companion object {
        val GROUPS = listOf(
            "1" to XKeycode.KEY_1, "2" to XKeycode.KEY_2, "3" to XKeycode.KEY_3, "4" to XKeycode.KEY_4,
            "5" to XKeycode.KEY_5, "6" to XKeycode.KEY_6, "7" to XKeycode.KEY_7, "8" to XKeycode.KEY_8,
            "9" to XKeycode.KEY_9, "0" to XKeycode.KEY_0,
        )
    }
}
