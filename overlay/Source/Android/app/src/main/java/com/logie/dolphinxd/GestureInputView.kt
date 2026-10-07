// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.logie.dolphinxd.gesture.Cancellable
import com.logie.dolphinxd.gesture.GestureConfig
import com.logie.dolphinxd.gesture.GestureOutput
import com.logie.dolphinxd.gesture.GestureRecognizer
import com.logie.dolphinxd.gesture.GestureScheduler
import com.logie.dolphinxd.gesture.PadButton
import org.dolphinemu.dolphinemu.features.input.model.InputOverrider
import org.dolphinemu.dolphinemu.features.input.model.InputOverrider.ControlId
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Invisible full-screen view that replaces Dolphin's on-screen controller. Touches are turned into
 * GameCube controller input for player 1 by [GestureRecognizer]; nothing is drawn.
 */
class GestureInputView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    /** Called on the two-finger tap, tap and hold gesture. */
    var onMenuGesture: (() -> Unit)? = null

    private val gestureHandler = Handler(Looper.getMainLooper())
    private var registered = false

    private val output = object : GestureOutput {
        override fun setButton(button: PadButton, pressed: Boolean) {
            val state = if (pressed) 1.0 else 0.0
            when (button) {
                PadButton.A -> setControl(ControlId.GCPAD_A_BUTTON, state)
                PadButton.B -> setControl(ControlId.GCPAD_B_BUTTON, state)
                PadButton.X -> setControl(ControlId.GCPAD_X_BUTTON, state)
                PadButton.START -> setControl(ControlId.GCPAD_START_BUTTON, state)
                PadButton.L -> {
                    setControl(ControlId.GCPAD_L_DIGITAL, state)
                    setControl(ControlId.GCPAD_L_ANALOG, state)
                }
            }
        }

        override fun setStick(x: Float, y: Float) {
            var stickX = x.toDouble()
            var stickY = y.toDouble()
            val radius = hypot(stickX, stickY)
            if (radius > 0 && isInputReady()) {
                // Keep the stick inside the GameCube controller's octagonal gate.
                val angle = atan2(stickY, stickX) + 2 * PI
                val gate = InputOverrider.getGateRadiusAtAngle(
                    CONTROLLER_INDEX,
                    ControlId.GCPAD_MAIN_STICK_X,
                    angle
                )
                if (radius > gate) {
                    stickX *= gate / radius
                    stickY *= gate / radius
                }
            }
            setControl(ControlId.GCPAD_MAIN_STICK_X, stickX)
            setControl(ControlId.GCPAD_MAIN_STICK_Y, stickY)
        }

        override fun openMenu() {
            onMenuGesture?.invoke()
        }

        override fun onHoldRecognized() {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private val recognizer: GestureRecognizer

    init {
        val density = resources.displayMetrics.density
        val scheduler = GestureScheduler { delayMs, action ->
            val runnable = Runnable(action)
            gestureHandler.postDelayed(runnable, delayMs)
            Cancellable { gestureHandler.removeCallbacks(runnable) }
        }
        recognizer = GestureRecognizer(
            GestureConfig(touchSlopPx = 14 * density, stickRadiusPx = 56 * density),
            scheduler,
            output
        )
        isHapticFeedbackEnabled = true
    }

    /** Takes over player 1's GameCube controller. Call once a game has booted. */
    fun refresh() {
        if (!isInputReady()) {
            return
        }
        if (registered) {
            InputOverrider.unregisterGameCube(CONTROLLER_INDEX)
        }
        InputOverrider.registerGameCube(CONTROLLER_INDEX)
        registered = true
    }

    /** Lets go of every button, e.g. when the game is paused or the menu opens. */
    fun releaseAll() = recognizer.cancelAll()

    fun onDestroy() {
        recognizer.cancelAll()
        gestureHandler.removeCallbacksAndMessages(null)
        if (registered) {
            InputOverrider.unregisterGameCube(CONTROLLER_INDEX)
            registered = false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recognizer.setViewWidth(w.toFloat())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Keep sideways swipes that start near the screen edges from turning into the system
            // back gesture. Android caps this at 200dp of height per edge.
            val density = resources.displayMetrics.density
            val edgeWidth = (32 * density).toInt()
            val height = (200 * density).toInt().coerceAtMost(h)
            val top = (h - height) / 2
            systemGestureExclusionRects = listOf(
                Rect(0, top, edgeWidth, top + height),
                Rect(w - edgeWidth, top, w, top + height)
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInputReady()) {
            return true
        }
        if (!registered) {
            refresh()
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                recognizer.pointerDown(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    recognizer.pointerMove(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                recognizer.pointerUp(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }

            MotionEvent.ACTION_CANCEL -> recognizer.cancelAll()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        recognizer.cancelAll()
        super.onDetachedFromWindow()
    }

    private fun setControl(control: Int, state: Double) {
        if (isInputReady()) {
            InputOverrider.setControlState(CONTROLLER_INDEX, control, state)
        }
    }

    private fun isInputReady() = DirectoryInitialization.areDolphinDirectoriesReady()

    companion object {
        /** GameCube port 1. */
        private const val CONTROLLER_INDEX = 0
    }
}
