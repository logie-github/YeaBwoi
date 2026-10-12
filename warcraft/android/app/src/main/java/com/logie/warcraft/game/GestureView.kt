// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.game

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.logie.warcraft.controls.Button
import com.logie.warcraft.controls.GestureOutput
import com.logie.warcraft.controls.Key
import com.logie.warcraft.controls.TouchGestures
import com.winlator.core.AppUtils
import com.winlator.math.XForm
import com.winlator.renderer.ViewTransformation
import com.winlator.winhandler.MouseEventFlags
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * The game's touch controls ([TouchGestures]) over Winlator's game screen, in place of its
 * touchpad: Winlator's XServerDisplayActivity puts it on top (patches/winlator/0004). It turns the
 * gestures into the X server's pointer and keys, where Wine passes them to the game.
 */
@SuppressLint("ViewConstructor")
class GestureView(
    private val activity: AppCompatActivity,
    private val xServer: XServer,
    private val onMenu: Runnable,
) : View(activity), GestureOutput {
    private val gestures = TouchGestures(this, resources.displayMetrics.density)
    /** From the view's pixels to the game screen's. */
    private val xform = XForm.getInstance()
    /** The game screen's pixels per view pixel. */
    private var scale = 1f
    private var restX = 0f
    private var restY = 0f
    private val hold = Runnable { gestures.onHold(SystemClock.uptimeMillis()) }

    init {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isClickable = true
        isFocusable = false
        updateXform(AppUtils.getScreenWidth(), AppUtils.getScreenHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateXform(w, h)
    }

    /** Like Winlator's touchpad: the game screen is letterboxed in the view unless stretched to fill it. */
    private fun updateXform(width: Int, height: Int) {
        val view = ViewTransformation()
        view.update(width, height, xServer.screenInfo.width.toInt(), xServer.screenInfo.height.toInt())
        scale = 1f / view.aspect
        if (!xServer.renderer.isFullscreen) {
            XForm.makeTranslation(xform, -view.viewOffsetX.toFloat(), -view.viewOffsetY.toFloat())
            XForm.scale(xform, scale, scale)
        } else {
            XForm.makeScale(xform, scale, scale)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A mouse or a trackpad goes to the game as it is (Winlator's own handling, under this view).
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        val index = event.actionIndex
        val id = event.getPointerId(index)
        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                gestures.down(id, event.getX(index), event.getY(index), now)
                if (event.pointerCount == 1) {
                    removeCallbacks(hold)
                    postDelayed(hold, TouchGestures.HOLD_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                gestures.move(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                gestures.up(id, event.getX(index), event.getY(index), now)
                if (event.actionMasked == MotionEvent.ACTION_UP) removeCallbacks(hold)
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                gestures.cancel()
            }
        }
        return true
    }

    /** Back in the game: Escape (closes a window, or opens the game menu). */
    fun escape() = tap(Key.ESCAPE)

    override fun pointerTo(x: Float, y: Float) {
        val point = XForm.transformPoint(xform, x, y)
        xServer.injectPointerMove(point[0].toInt(), point[1].toInt())
    }

    override fun pointerBy(dx: Float, dy: Float) {
        // Whole pixels, keeping what's left over for the next move.
        restX += dx * scale * CAMERA_SPEED
        restY += dy * scale * CAMERA_SPEED
        val mx = restX.toInt()
        val my = restY.toInt()
        if (mx == 0 && my == 0) return
        restX -= mx
        restY -= my
        if (xServer.isRelativeMouseMovement) {
            xServer.winHandler.mouseEvent(MouseEventFlags.MOVE, mx, my, 0)
        } else {
            xServer.injectPointerMoveDelta(mx, my)
        }
    }

    override fun press(button: Button) {
        restX = 0f
        restY = 0f
        xServer.injectPointerButtonPress(button.x)
    }

    override fun release(button: Button) = xServer.injectPointerButtonRelease(button.x)

    override fun click(button: Button) {
        xServer.injectPointerButtonPress(button.x)
        postDelayed({ xServer.injectPointerButtonRelease(button.x) }, CLICK_MS)
    }

    override fun keyDown(key: Key) = xServer.injectKeyPress(key.x)

    override fun keyUp(key: Key) = xServer.injectKeyRelease(key.x)

    override fun tap(key: Key) {
        xServer.injectKeyPress(key.x)
        postDelayed({ xServer.injectKeyRelease(key.x) }, KEY_MS)
    }

    override fun showKeyboard() = AppUtils.showKeyboard(activity)

    override fun showMenu() = onMenu.run()

    override fun onDetachedFromWindow() {
        removeCallbacks(hold)
        gestures.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val CLICK_MS = 30L
        const val KEY_MS = 60L
        /** The camera turns a little faster than the fingers move. */
        const val CAMERA_SPEED = 1.5f

        val Button.x: Pointer.Button
            get() = when (this) {
                Button.LEFT -> Pointer.Button.BUTTON_LEFT
                Button.RIGHT -> Pointer.Button.BUTTON_RIGHT
            }

        val Key.x: XKeycode
            get() = when (this) {
                Key.W -> XKeycode.KEY_W
                Key.A -> XKeycode.KEY_A
                Key.S -> XKeycode.KEY_S
                Key.D -> XKeycode.KEY_D
                Key.B -> XKeycode.KEY_B
                Key.M -> XKeycode.KEY_M
                Key.SPACE -> XKeycode.KEY_SPACE
                Key.ESCAPE -> XKeycode.KEY_ESC
            }
    }
}
