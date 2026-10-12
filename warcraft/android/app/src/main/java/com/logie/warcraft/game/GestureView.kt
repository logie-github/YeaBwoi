// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
 * The game's touch controls ([TouchGestures]) and keyboard over Winlator's game screen, in place
 * of its touchpad: Winlator's XServerDisplayActivity puts it on top (patches/winlator/0004). It
 * turns the gestures into the X server's pointer and keys, where Wine passes them to the game,
 * draws the stick under the left thumb, and opens the app's menu ([GameMenu]) for four fingers.
 *
 * It's also the game's text box for the phone's keyboard, which comes up by itself where the
 * game wants text: the login screen when the game appears, character creation right after the
 * login when the account has no characters yet (the launcher says so: [EXTRA_NEW_ACCOUNT]), and
 * the chat (two fingers swiped up press Enter, which opens it). The keyboard's Enter is the game's;
 * closing the keyboard without sending closes the chat. While the keyboard is up, the game shrinks
 * into the space above it.
 */
@SuppressLint("ViewConstructor")
class GestureView(
    private val activity: AppCompatActivity,
    private val xServer: XServer,
    /** Leaves the game for the launcher. */
    private val onLeave: Runnable,
) : View(activity), GestureOutput {
    private val density = resources.displayMetrics.density
    private val gestures = TouchGestures(this, density)
    /** From the view's pixels to the game screen's. */
    private val xform = XForm.getInstance()
    /** The game screen's pixels per view pixel. */
    private var scale = 1f
    private var restX = 0f
    private var restY = 0f
    private val hold = Runnable { gestures.onHold(SystemClock.uptimeMillis()) }

    private var stickShown = false
    private var baseX = 0f
    private var baseY = 0f
    private var knobX = 0f
    private var knobY = 0f
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40061320 }
    private val ringLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = 0x997FDDF8.toInt()
    }
    private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x667FDDF8 }
    private val knobLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = 0xCCE8B34A.toInt()
    }

    /** What the keyboard is up for, or null when it's down. */
    private enum class Typing { LOGIN, NAME, CHAT, FREE }

    private var typing: Typing? = null
    /** The text went to the game with Enter: closing the keyboard after it doesn't close the chat. */
    private var sent = false
    private var keyboardHeight = 0
    private var keyboardSeen = false
    private var keyboardAskedAt = 0L
    private var loginShown = false
    private val newAccount = activity.intent.getBooleanExtra(EXTRA_NEW_ACCOUNT, false)
    private val inputMethods = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val watchKeyboard = object : Runnable {
        override fun run() = followKeyboard(this)
    }

    init {
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        setWillNotDraw(false)
        // The keyboard over the game, not the game pushed around: the view shrinks it (followKeyboard).
        activity.window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        updateXform(AppUtils.getScreenWidth(), AppUtils.getScreenHeight())
        gestures.setSize(AppUtils.getScreenWidth().toFloat(), AppUtils.getScreenHeight().toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateXform(w, h)
        gestures.setSize(w.toFloat(), h.toFloat())
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
                postDelayed(hold, TouchGestures.HOLD_MS)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!stickShown) return
        val radius = TouchGestures.STICK_RADIUS_DP * density
        canvas.drawCircle(baseX, baseY, radius, ringFill)
        canvas.drawCircle(baseX, baseY, radius, ringLine)
        canvas.drawCircle(knobX, knobY, radius * 0.42f, knobFill)
        canvas.drawCircle(knobX, knobY, radius * 0.42f, knobLine)
    }

    /** Back in the game: Escape (closes a window, or opens the game menu). */
    fun escape() = tap(Key.ESCAPE)

    /** The game's first window is up (the loading screen closed): the login screen wants the password. */
    fun onGameWindowShown() {
        if (loginShown) return
        loginShown = true
        postDelayed({ showKeyboard(Typing.LOGIN) }, LOGIN_KEYBOARD_DELAY_MS)
    }

    // Gestures ---------------------------------------------------------------------------------

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

    override fun stick(baseX: Float, baseY: Float, knobX: Float, knobY: Float) {
        stickShown = true
        this.baseX = baseX
        this.baseY = baseY
        this.knobX = knobX
        this.knobY = knobY
        invalidate()
    }

    override fun stickGone() {
        stickShown = false
        invalidate()
    }

    /** Walking or turning with the chat open would type into it: the chat closes first. */
    override fun playing() {
        if (typing == Typing.CHAT || typing == Typing.FREE) {
            hideKeyboard()
            tap(Key.ESCAPE)
        }
    }

    override fun openChat() {
        if (typing == Typing.CHAT) return
        tap(Key.ENTER)
        showKeyboard(Typing.CHAT)
    }

    override fun showKeyboard() = showKeyboard(Typing.FREE)

    override fun showMenu() = GameMenu.show(activity, onKeyboard = { showKeyboard() }, onLeave = { onLeave.run() })

    // The keyboard -----------------------------------------------------------------------------

    private fun showKeyboard(why: Typing) {
        typing = why
        sent = false
        keyboardAskedAt = SystemClock.uptimeMillis()
        requestFocus()
        inputMethods.restartInput(this)
        inputMethods.showSoftInput(this, 0)
        removeCallbacks(watchKeyboard)
        post(watchKeyboard)
    }

    private fun hideKeyboard() {
        typing = null
        inputMethods.hideSoftInputFromWindow(windowToken, 0)
    }

    /**
     * Follows the keyboard while it's wanted or up: the game shrinks into the space above it, and
     * when it goes down without the text sent, a chat that was open closes (Escape).
     */
    private fun followKeyboard(again: Runnable) {
        val height = keyboardHeight()
        if (height != keyboardHeight) {
            keyboardHeight = height
            (parent as? ViewGroup)?.setPadding(0, 0, 0, height)
        }
        if (height > 0) {
            keyboardSeen = true
        } else if (!keyboardSeen && typing != null && SystemClock.uptimeMillis() - keyboardAskedAt > KEYBOARD_WAIT_MS) {
            // The keyboard didn't come (no keyboard app, or the screen wasn't ready).
            typing = null
        } else if (keyboardSeen) {
            keyboardSeen = false
            val was = typing
            typing = null
            if ((was == Typing.CHAT || was == Typing.FREE) && !sent) tap(Key.ESCAPE)
        }
        if (typing != null || height > 0) postDelayed(again, KEYBOARD_POLL_MS)
    }

    private fun keyboardHeight(): Int {
        val root = rootView ?: return 0
        val ime = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        if (ime > 0) return ime
        // Older Android: what the window can still see.
        val visible = Rect()
        root.getWindowVisibleDisplayFrame(visible)
        val hidden = root.height - visible.bottom
        return if (hidden > root.height * 0.15f) hidden else 0
    }

    /** Enter from the keyboard: the game's, then the keyboard goes, or stays for character creation. */
    private fun enter() {
        key(KeyEvent.KEYCODE_ENTER)
        when (typing) {
            Typing.LOGIN -> if (newAccount) {
                // The game goes on to character creation, which wants a name.
                typing = Typing.NAME
                inputMethods.restartInput(this)
            } else {
                hideKeyboard()
            }
            Typing.CHAT, Typing.FREE -> {
                sent = true
                hideKeyboard()
            }
            Typing.NAME, null -> hideKeyboard()
        }
    }

    private val characters: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

    /** Text from the keyboard, as the key presses that make it (Winlator's keyboard maps any other character). */
    private fun type(text: String) {
        for (c in text) {
            if (c == '\n') {
                enter()
                continue
            }
            val events = characters.getEvents(charArrayOf(c))
            if (events != null) {
                for (event in events) xServer.keyboard.onKeyEvent(event)
            } else {
                xServer.keyboard.onKeyEvent(KeyEvent(SystemClock.uptimeMillis(), c.toString(), KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
            }
        }
    }

    private fun key(code: Int) {
        val time = SystemClock.uptimeMillis()
        xServer.keyboard.onKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
        xServer.keyboard.onKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Plain characters as they're typed: no suggestions or corrections, which games can't take
        // back; and the keyboard beside the game, never a full-screen text box over it.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or when (typing) {
            Typing.CHAT -> EditorInfo.IME_ACTION_SEND
            Typing.LOGIN -> EditorInfo.IME_ACTION_GO
            else -> EditorInfo.IME_ACTION_DONE
        }
        return GameInput()
    }

    /** What the keyboard types, into the game. */
    private inner class GameInput : BaseInputConnection(this, false) {
        /** Text the keyboard is still composing, already typed into the game. */
        private var composing = ""

        private fun replaceComposing(text: String) {
            val same = composing.commonPrefixWith(text).length
            repeat(composing.length - same) { key(KeyEvent.KEYCODE_DEL) }
            type(text.substring(same))
            composing = text
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            composing = ""
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            replaceComposing(text.toString())
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { key(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength) { key(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) enter()
                return true
            }
            xServer.keyboard.onKeyEvent(event)
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            enter()
            return true
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hold)
        removeCallbacks(watchKeyboard)
        gestures.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        /** The launcher's word that the account has no characters yet: the game will go to character creation. */
        const val EXTRA_NEW_ACCOUNT = "new_account"

        private const val CLICK_MS = 30L
        private const val KEY_MS = 60L
        private const val KEYBOARD_POLL_MS = 120L
        private const val KEYBOARD_WAIT_MS = 4000L
        /** The login screen takes a moment to draw after the window appears. */
        private const val LOGIN_KEYBOARD_DELAY_MS = 1500L
        /** The camera turns a little faster than the thumb moves. */
        private const val CAMERA_SPEED = 1.5f

        private val Button.x: Pointer.Button
            get() = when (this) {
                Button.LEFT -> Pointer.Button.BUTTON_LEFT
                Button.RIGHT -> Pointer.Button.BUTTON_RIGHT
                Button.WHEEL_UP -> Pointer.Button.BUTTON_SCROLL_UP
                Button.WHEEL_DOWN -> Pointer.Button.BUTTON_SCROLL_DOWN
            }

        private val Key.x: XKeycode
            get() = when (this) {
                Key.W -> XKeycode.KEY_W
                Key.S -> XKeycode.KEY_S
                Key.Q -> XKeycode.KEY_Q
                Key.E -> XKeycode.KEY_E
                Key.B -> XKeycode.KEY_B
                Key.M -> XKeycode.KEY_M
                Key.SPACE -> XKeycode.KEY_SPACE
                Key.ESCAPE -> XKeycode.KEY_ESC
                Key.ENTER -> XKeycode.KEY_ENTER
            }
    }
}
