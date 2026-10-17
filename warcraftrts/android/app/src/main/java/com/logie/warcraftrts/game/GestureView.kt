// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraftrts.game

import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.logie.warcraftrts.RtsFiles
import com.logie.warcraftrts.controls.Button
import com.logie.warcraftrts.controls.GestureOutput
import com.logie.warcraftrts.controls.Key
import com.logie.warcraftrts.controls.RtsGestures
import com.logie.warcraftrts.library.Title
import com.winlator.core.AppUtils
import com.winlator.math.XForm
import com.winlator.renderer.ViewTransformation
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * The games' touch controls ([RtsGestures]) over Winlator's game screen, in place of its touchpad:
 * Winlator's XServerDisplayActivity puts it on top (patches/winlator/0004). It turns the gestures
 * into the X server's pointer, buttons and keys, where Wine passes them to the game, with nothing
 * drawn over the game. Three fingers show the quick keys ([QuickKeys]: control groups, Shift,
 * heroes, chat), four fingers the app's menu ([GameMenu]); Back is the game's Escape.
 */
@SuppressLint("ViewConstructor")
class GestureView(
    private val activity: AppCompatActivity,
    private val xServer: XServer,
    /** Leaves the game for the launcher. */
    private val onLeave: Runnable,
) : View(activity), GestureOutput {
    private val density = resources.displayMetrics.density
    private val gestures = RtsGestures(this, density)
    private val title = Title.of(activity.intent.getStringExtra(EXTRA_TITLE)) ?: Title.TFT
    private val vibrate = GameSettings.vibration(activity)
    private val quickKeys = QuickKeys(activity, title, this)

    /** From the view's pixels to the game screen's. */
    private val xform = XForm.getInstance()
    private val hold = Runnable { gestures.onHold(SystemClock.uptimeMillis()) }

    /** Keys held down by the quick keys (Shift, Ctrl, Alt) until let go there. */
    private val latched = mutableSetOf<XKeycode>()

    /** Key presses still to send for text from the prompt, a few milliseconds apart. */
    private val typing = ArrayDeque<() -> Unit>()
    private var typingRuns = false
    private val typeNext = object : Runnable {
        override fun run() {
            val next = typing.removeFirstOrNull()
            if (next == null) {
                typingRuns = false
                return
            }
            next()
            postDelayed(this, TYPE_STEP_MS)
        }
    }

    init {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isClickable = true
        isFocusable = false
        // The game's screen never moves or changes size for a keyboard: text goes through a prompt.
        activity.window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        // The whole screen, edge to edge, the camera's edge too.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window.attributes = activity.window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        // A game window smaller than the screen is scaled up to fill it (the pointer follows).
        xServer.renderer.isForceWindowsFullscreen = true
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
        val scale = 1f / view.aspect
        if (!xServer.renderer.isFullscreen) {
            XForm.makeTranslation(xform, -view.viewOffsetX.toFloat(), -view.viewOffsetY.toFloat())
            XForm.scale(xform, scale, scale)
        } else {
            XForm.makeScale(xform, scale, scale)
        }
    }

    /** A mouse connected to the phone: its pointer is drawn again, as on a PC. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) xServer.renderer.isCursorVisible = true
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A mouse or a trackpad goes to the game as it is (Winlator's own handling, under this view).
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            xServer.renderer.isCursorVisible = true
            return false
        }
        val index = event.actionIndex
        val id = event.getPointerId(index)
        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestures.down(id, event.getX(index), event.getY(index), now)
                postDelayed(hold, RtsGestures.HOLD_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(hold)
                gestures.down(id, event.getX(index), event.getY(index), now)
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

    /** Back in the game: Escape (cancels an order, closes a menu). */
    fun escape() = tapKey(XKeycode.KEY_ESC)

    /** The game's first window is up: on a touch screen the finger is the pointer, never drawn. */
    fun onGameWindowShown() {
        xServer.renderer.isCursorVisible = false
    }

    // Gestures ---------------------------------------------------------------------------------

    override fun pointerTo(x: Float, y: Float) {
        val point = XForm.transformPoint(xform, x, y)
        xServer.injectPointerMove(point[0].toInt(), point[1].toInt())
    }

    override fun press(button: Button) = xServer.injectPointerButtonPress(button.x)

    override fun release(button: Button) = xServer.injectPointerButtonRelease(button.x)

    override fun click(button: Button) {
        xServer.injectPointerButtonPress(button.x)
        postDelayed({ xServer.injectPointerButtonRelease(button.x) }, CLICK_MS)
    }

    override fun wheel(up: Boolean) {
        val button = if (up) Pointer.Button.BUTTON_SCROLL_UP else Pointer.Button.BUTTON_SCROLL_DOWN
        xServer.injectPointerButtonPress(button)
        xServer.injectPointerButtonRelease(button)
    }

    override fun keyDown(key: Key) = xServer.injectKeyPress(key.x)

    override fun keyUp(key: Key) = xServer.injectKeyRelease(key.x)

    override fun toggleQuickKeys() = quickKeys.toggle()

    override fun showMenu() = GameMenu.show(
        activity,
        title,
        keys = RtsFiles.cdKeys(activity, title.install),
        onKeyboard = { askText(chat = false) },
        onChat = { askText(chat = true) },
        onTypeKey = { key -> type(key, enter = false) },
        onLeave = { leave() },
    )

    override fun feedback() {
        if (!vibrate) return
        if (!performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)) {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // The quick keys ---------------------------------------------------------------------------

    /** A key pressed and let go. */
    fun tapKey(key: XKeycode) {
        xServer.injectKeyPress(key)
        postDelayed({ xServer.injectKeyRelease(key) }, KEY_MS)
    }

    /** A key pressed with [modifier] held (Ctrl+1 makes control group 1). */
    fun tapKeyWith(modifier: XKeycode, key: XKeycode) {
        xServer.injectKeyPress(modifier)
        xServer.injectKeyPress(key)
        postDelayed({
            xServer.injectKeyRelease(key)
            if (modifier !in latched) xServer.injectKeyRelease(modifier)
        }, KEY_MS)
    }

    /** [key] held down until [setLatched] lets go of it (Shift to queue orders). */
    fun setLatched(key: XKeycode, down: Boolean) {
        if (down && latched.add(key)) xServer.injectKeyPress(key)
        if (!down && latched.remove(key)) xServer.injectKeyRelease(key)
    }

    fun isLatched(key: XKeycode): Boolean = key in latched

    /** Enter, then the prompt; its text is typed into the game with Enter (a chat line, a cheat code). */
    fun askText(chat: Boolean) {
        if (chat) tapKey(XKeycode.KEY_ENTER)
        GamePrompt.show(
            activity,
            title,
            chat = chat,
            onSend = { text -> type(text, enter = chat) },
            onCancel = { if (chat) tapKey(XKeycode.KEY_ESC) },
            onBackspace = { tapKey(XKeycode.KEY_BKSP) },
        )
    }

    /** Types [text] into the game, a key at a time, then Enter if [enter]. */
    private fun type(text: String, enter: Boolean) {
        val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        for (c in text) {
            typing += {
                val events = map.getEvents(charArrayOf(c))
                if (events != null) {
                    for (event in events) xServer.keyboard.onKeyEvent(event)
                } else {
                    xServer.keyboard.onKeyEvent(KeyEvent(SystemClock.uptimeMillis(), c.toString(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
                }
            }
        }
        if (enter) typing += { tapKey(XKeycode.KEY_ENTER) }
        if (!typingRuns) {
            typingRuns = true
            post(typeNext)
        }
    }

    private fun leave() {
        for (key in latched.toList()) setLatched(key, false)
        quickKeys.hide()
        onLeave.run()
    }

    override fun onDetachedFromWindow() {
        quickKeys.hide()
        removeCallbacks(typeNext)
        super.onDetachedFromWindow()
    }

    companion object {
        /** The game the launcher started ([Title.key]): the loading screen's and menus' look. */
        const val EXTRA_TITLE = "com.logie.warcraftrts.TITLE"

        private const val CLICK_MS = 40L
        private const val KEY_MS = 50L
        private const val TYPE_STEP_MS = 15L

        private val Button.x: Pointer.Button
            get() = when (this) {
                Button.LEFT -> Pointer.Button.BUTTON_LEFT
                Button.RIGHT -> Pointer.Button.BUTTON_RIGHT
            }

        private val Key.x: XKeycode
            get() = when (this) {
                Key.LEFT -> XKeycode.KEY_LEFT
                Key.RIGHT -> XKeycode.KEY_RIGHT
                Key.UP -> XKeycode.KEY_UP
                Key.DOWN -> XKeycode.KEY_DOWN
                Key.ESCAPE -> XKeycode.KEY_ESC
                Key.ENTER -> XKeycode.KEY_ENTER
            }
    }
}
