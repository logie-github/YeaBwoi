// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.logie.azuredreams.gesture.Cancellable
import com.logie.azuredreams.gesture.GestureConfig
import com.logie.azuredreams.gesture.GestureOutput
import com.logie.azuredreams.gesture.GestureRecognizer
import com.logie.azuredreams.gesture.GestureScheduler
import com.logie.azuredreams.gesture.PsButton
import com.swordfish.libretrodroid.GLRetroView

/**
 * A transparent layer over the game that takes every touch and turns it into controller input
 * through [GestureRecognizer].
 */
@SuppressLint("ViewConstructor")
class GestureView(
    context: Context,
    private val retroView: GLRetroView,
    private val onMenu: () -> Unit,
) : View(context) {
    private val handler = Handler(Looper.getMainLooper())

    private val output = object : GestureOutput {
        override fun setButton(button: PsButton, pressed: Boolean) {
            retroView.sendKeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode(button))
        }

        override fun openMenu() = onMenu()

        override fun onHoldRecognized() {
            if (AdConfig(context).haptics) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    private val recognizer: GestureRecognizer

    init {
        val density = resources.displayMetrics.density
        val scheduler = GestureScheduler { delayMs, action ->
            val runnable = Runnable(action)
            handler.postDelayed(runnable, delayMs)
            Cancellable { handler.removeCallbacks(runnable) }
        }
        recognizer = GestureRecognizer(
            GestureConfig(
                touchSlopPx = 14 * density,
                dpadRadiusPx = 40 * density,
                twoFingerSwipePx = 56 * density,
            ),
            scheduler,
            output,
        )
    }

    /** Lets go of every button. */
    fun releaseAll() = recognizer.cancelAll()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recognizer.width = w.toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Keep sideways swipes that start near the screen edges from turning into the system
            // back gesture. Android caps this at 200dp of height per edge.
            val density = resources.displayMetrics.density
            val edgeWidth = (32 * density).toInt()
            val exclusionHeight = (200 * density).toInt().coerceAtMost(h)
            val top = (h - exclusionHeight) / 2
            systemGestureExclusionRects = listOf(
                Rect(0, top, edgeWidth, top + exclusionHeight),
                Rect(w - edgeWidth, top, w, top + exclusionHeight),
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    recognizer.dropStaleTouches()
                }
                val i = event.actionIndex
                recognizer.pointerDown(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    recognizer.pointerMove(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
                }
                recognizer.movesFinished()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                recognizer.pointerUp(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }

            MotionEvent.ACTION_CANCEL -> recognizer.cancelAll()
        }
        return true
    }

    /** The Android gamepad key LibretroDroid maps to each PlayStation button. */
    private fun keyCode(button: PsButton): Int = when (button) {
        PsButton.CROSS -> KeyEvent.KEYCODE_BUTTON_B
        PsButton.CIRCLE -> KeyEvent.KEYCODE_BUTTON_A
        PsButton.SQUARE -> KeyEvent.KEYCODE_BUTTON_Y
        PsButton.TRIANGLE -> KeyEvent.KEYCODE_BUTTON_X
        PsButton.START -> KeyEvent.KEYCODE_BUTTON_START
        PsButton.SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
        PsButton.L1 -> KeyEvent.KEYCODE_BUTTON_L1
        PsButton.R1 -> KeyEvent.KEYCODE_BUTTON_R1
        PsButton.DPAD_UP -> KeyEvent.KEYCODE_DPAD_UP
        PsButton.DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
        PsButton.DPAD_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
        PsButton.DPAD_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
    }
}
