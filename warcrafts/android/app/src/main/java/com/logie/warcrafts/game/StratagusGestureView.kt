// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.logie.warcrafts.controls.Button
import com.logie.warcrafts.controls.GestureOutput
import com.logie.warcrafts.controls.Key
import com.logie.warcrafts.controls.RtsGestures
import com.logie.warcrafts.library.Title
import org.libsdl.app.SdlBridge

/**
 * The user's gestures ([RtsGestures]) over the native games' screen ([StratagusActivity]), turned
 * into SDL's mouse and keys: swipes pan the map (the arrow keys), taps right-click, holds left-click,
 * a resting finger is Shift, two fingers open the game's menu (F10), three the phone's keyboard,
 * four the app's menu. SDL's own touch handling never sees the fingers. A mouse on the phone goes
 * to SDL as it is.
 */
@SuppressLint("ViewConstructor")
class StratagusGestureView(
    private val activity: Activity,
    private val title: Title,
    private val onLeave: () -> Unit,
) : View(activity), GestureOutput {
    private val gestures = RtsGestures(this, resources.displayMetrics.density)
    private val vibrate = GameSettings.vibration(activity)
    private val hold = Runnable { gestures.onHold(SystemClock.uptimeMillis()) }
    private var x = 0f
    private var y = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        val index = event.actionIndex
        val id = event.getPointerId(index)
        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(hold)
                gestures.down(id, event.getX(index), event.getY(index), now)
                postDelayed(hold, RtsGestures.HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> gestures.move(
                List(event.pointerCount) { i -> RtsGestures.Touch(event.getPointerId(i), event.getX(i), event.getY(i)) }
            )
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) removeCallbacks(hold)
                gestures.up(id, event.getX(index), event.getY(index), now)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                gestures.cancel()
            }
        }
        return true
    }

    // Gestures ---------------------------------------------------------------------------------

    override fun pointerTo(x: Float, y: Float) {
        this.x = x
        this.y = y
        SdlBridge.mouse(0, SdlBridge.ACTION_HOVER_MOVE, x, y)
    }

    override fun click(button: Button) {
        val (bx, by) = x to y
        SdlBridge.mouse(button.bit, SdlBridge.ACTION_DOWN, bx, by)
        postDelayed({ SdlBridge.mouse(0, SdlBridge.ACTION_UP, bx, by) }, CLICK_MS)
    }

    override fun doubleClick(button: Button) {
        val (bx, by) = x to y
        SdlBridge.mouse(button.bit, SdlBridge.ACTION_DOWN, bx, by)
        postDelayed({ SdlBridge.mouse(0, SdlBridge.ACTION_UP, bx, by) }, CLICK_MS)
        postDelayed({ SdlBridge.mouse(button.bit, SdlBridge.ACTION_DOWN, bx, by) }, CLICK_MS * 2)
        postDelayed({ SdlBridge.mouse(0, SdlBridge.ACTION_UP, bx, by) }, CLICK_MS * 3)
    }

    override fun wheel(up: Boolean) = SdlBridge.mouse(0, SdlBridge.ACTION_SCROLL, 0f, if (up) 1f else -1f)

    override fun keyDown(key: Key) = SdlBridge.keyDown(key.code)

    override fun keyUp(key: Key) = SdlBridge.keyUp(key.code)

    override fun gameMenu() = tap(KeyEvent.KEYCODE_F10)

    override fun openKeyboard() {
        tap(KeyEvent.KEYCODE_ENTER)
        GamePrompt.show(
            activity,
            title,
            chat = true,
            onSend = { text ->
                SdlBridge.text(text)
                postDelayed({ tap(KeyEvent.KEYCODE_ENTER) }, KEY_MS * 2)
            },
            onCancel = { tap(KeyEvent.KEYCODE_ESCAPE) },
            onBackspace = { tap(KeyEvent.KEYCODE_DEL) },
        )
    }

    override fun showMenu() = GameMenu.show(
        activity,
        title,
        keys = emptyList(),
        onKeyboard = { typeText() },
        onChat = { openKeyboard() },
        onTypeKey = {},
        onLeave = onLeave,
    )

    override fun feedback() {
        if (!vibrate) return
        if (!performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)) {
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** Text into whatever box the game has open (a save's name), without Enter. */
    private fun typeText() {
        GamePrompt.show(
            activity,
            title,
            chat = false,
            onSend = { text -> SdlBridge.text(text) },
            onCancel = {},
            onBackspace = { tap(KeyEvent.KEYCODE_DEL) },
        )
    }

    private fun tap(code: Int) {
        SdlBridge.keyDown(code)
        postDelayed({ SdlBridge.keyUp(code) }, KEY_MS)
    }

    init {
        current = java.lang.ref.WeakReference(this)
    }

    companion object {
        private const val CLICK_MS = 40L
        private const val KEY_MS = 50L
        private var current: java.lang.ref.WeakReference<StratagusGestureView>? = null

        /** Back on the game's screen: Escape. */
        fun escape() {
            val view = current?.get() ?: return
            Handler(Looper.getMainLooper()).post { view.tap(KeyEvent.KEYCODE_ESCAPE) }
        }

        private val Button.bit: Int
            get() = when (this) {
                Button.LEFT -> SdlBridge.BUTTON_PRIMARY
                Button.RIGHT -> SdlBridge.BUTTON_SECONDARY
            }

        private val Key.code: Int
            get() = when (this) {
                Key.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                Key.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                Key.UP -> KeyEvent.KEYCODE_DPAD_UP
                Key.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                Key.SHIFT -> KeyEvent.KEYCODE_SHIFT_LEFT
            }
    }
}
