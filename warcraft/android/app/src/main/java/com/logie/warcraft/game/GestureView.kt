// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.game

import android.annotation.SuppressLint
import android.content.Context
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
import com.logie.warcraft.controls.CursorKinds
import com.logie.warcraft.controls.GestureOutput
import com.logie.warcraft.controls.Key
import com.logie.warcraft.controls.TouchGestures
import com.winlator.core.AppUtils
import com.winlator.math.XForm
import com.winlator.renderer.ViewTransformation
import com.winlator.winhandler.MouseEventFlags
import com.winlator.xserver.Cursor
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * The game's touch controls ([TouchGestures]) and keyboard over Winlator's game screen, in place
 * of its touchpad: Winlator's XServerDisplayActivity puts it on top (patches/winlator/0004). It
 * turns the gestures into the X server's pointer and keys, where Wine passes them to the game,
 * with nothing drawn over the game, and opens the app's menu ([GameMenu]) for four fingers. Taps
 * read the game's own cursor to talk, loot and gather like a PC's right click, and select enemies,
 * which a double tap attacks ([tapAt], [doubleTapAt]).
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

    private val cursors = CursorKinds()
    /** Fingerprints of the game's cursors, by cursor (the X server's, for as long as they live). */
    private val fingerprints = java.util.WeakHashMap<Cursor, Long>()
    /** Counts the pointer's moves, so a tap's delayed click can tell another gesture took over. */
    private var pointerMoves = 0
    private var lastTapAt = Long.MIN_VALUE / 2
    private var lastTap: TapResult? = null
    private val selectionFile = java.io.File(com.logie.warcraft.WowFiles.run(activity), "selection")
    private var lastTapX = 0f
    private var lastTapY = 0f

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

    /** Back in the game: Escape (closes a window, or opens the game menu). */
    fun escape() = tap(Key.ESCAPE)

    /** The game's first window is up (the loading screen closed): the login screen wants the password. */
    fun onGameWindowShown() {
        if (loginShown) return
        loginShown = true
        postDelayed({ showKeyboard(Typing.LOGIN) }, LOGIN_KEYBOARD_DELAY_MS)
        // The login screen only has the plain arrow: what the cursor looks like when it means a click.
        for (delay in ARROW_SAMPLES_MS) postDelayed({ cursors.learnArrow(cursorHere()) }, delay)
    }

    // Gestures ---------------------------------------------------------------------------------

    override fun pointerTo(x: Float, y: Float) {
        pointerMoves++
        val point = XForm.transformPoint(xform, x, y)
        xServer.injectPointerMove(point[0].toInt(), point[1].toInt())
    }

    /**
     * The pointer goes there; once the game has drawn its cursor for that spot, a left click; and
     * when the cursor offered more (talk, attack, loot, gather) and still does after the click, a
     * right click, which does it, as on a PC.
     */
    override fun tapAt(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        // The same spot tapped again, not quick enough for a double tap: see below.
        val again = now - lastTapAt in TouchGestures.DOUBLE_TAP_MS..AGAIN_MS &&
            Math.hypot((x - lastTapX).toDouble(), (y - lastTapY).toDouble()) <= AGAIN_SLOP_DP * density
        lastTapAt = now
        lastTapX = x
        lastTapY = y
        val tap = TapResult(x, y)
        lastTap = tap
        pointerTo(x, y)
        val moves = pointerMoves
        postDelayed({
            if (moves != pointerMoves) return@postDelayed tap.done(Outcome.PLAIN)
            val before = cursorHere()
            val special = cursors.isSpecial(before)
            val selected = selection()?.first
            click(Button.LEFT)
            when {
                // An enemy: the click selected it; a second tap attacks.
                special && cursors.isEnemy(before) == true -> tap.done(Outcome.ENEMY)
                special && cursors.isEnemy(before) == false -> postDelayed({ interactIfStill(before, moves, tap) }, CURSOR_SETTLE_MS)
                // Not learnt yet: the realm says what the click selected.
                special -> waitForSelection(selected, before, moves, tap, SystemClock.uptimeMillis())
                // Without a cursor to read: the first tap selects, the second talks (or attacks, loots).
                again && !cursors.readable -> postDelayed({
                    if (moves == pointerMoves) click(Button.RIGHT)
                    tap.done(Outcome.INTERACTED)
                }, CURSOR_SETTLE_MS)
                else -> tap.done(Outcome.PLAIN)
            }
        }, CURSOR_SETTLE_MS)
    }

    /**
     * After a tap's click on a special cursor: the realm writes what was selected (an enemy or
     * not) as soon as the game tells it. An enemy stays selected; anything else is used, as a right
     * click does. No word in time: something that isn't selected (a chest, a herb, a mailbox) or
     * that was already selected, which is used too.
     */
    private fun waitForSelection(before: Int?, cursor: Long?, moves: Int, tap: TapResult, since: Long) {
        val now = selection()
        if (now != null && now.first != before) {
            if (cursor != null) cursors.learn(cursor, now.second)
            if (now.second) tap.done(Outcome.ENEMY) else interactIfStill(cursor, moves, tap)
            return
        }
        if (SystemClock.uptimeMillis() - since >= SELECTION_WAIT_MS) return interactIfStill(cursor, moves, tap)
        postDelayed({ waitForSelection(before, cursor, moves, tap, since) }, SELECTION_POLL_MS)
    }

    /** The right click, when the cursor that offered it is still there after the left click. */
    private fun interactIfStill(cursor: Long?, moves: Int, tap: TapResult) {
        if (moves == pointerMoves && cursors.confirms(cursor, cursorHere())) {
            click(Button.RIGHT)
            tap.done(Outcome.INTERACTED)
        } else {
            tap.done(Outcome.PLAIN)
        }
    }

    /** The second tap of a double tap: attack the enemy the first one selected; otherwise jump. */
    override fun doubleTapAt(x: Float, y: Float) {
        val first = lastTap
        if (first == null || Math.hypot((first.x - x).toDouble(), (first.y - y).toDouble()) > AGAIN_SLOP_DP * density) {
            tap(Key.SPACE)
            return
        }
        first.whenDone { outcome ->
            when (outcome) {
                Outcome.ENEMY -> interactAt(x, y)
                // The first tap already talked, looted or used: the second is the same tap again.
                Outcome.INTERACTED -> {}
                Outcome.PLAIN -> tap(Key.SPACE)
            }
        }
    }

    /** The realm's latest word on a selection: its count, and whether it was an enemy. */
    private fun selection(): Pair<Int, Boolean>? = runCatching {
        val parts = selectionFile.readText().trim().split(' ')
        parts[0].toInt() to (parts[1] == "1")
    }.getOrNull()

    private enum class Outcome { PLAIN, ENEMY, INTERACTED }

    /** What a tap did, once it's known (its clicks wait for the game and the realm). */
    private class TapResult(val x: Float, val y: Float) {
        private var outcome: Outcome? = null
        private var waiting: ((Outcome) -> Unit)? = null

        fun done(result: Outcome) {
            outcome = result
            waiting?.invoke(result)
            waiting = null
        }

        fun whenDone(then: (Outcome) -> Unit) {
            val known = outcome
            if (known != null) then(known) else waiting = then
        }
    }

    /** Once the game knows the pointer is there (what's under it), the right click. */
    override fun interactAt(x: Float, y: Float) {
        pointerTo(x, y)
        val moves = pointerMoves
        postDelayed({ if (moves == pointerMoves) click(Button.RIGHT) }, CURSOR_SETTLE_MS)
    }

    /** A fingerprint of the game's cursor where the pointer is, or null when there's none to see. */
    private fun cursorHere(): Long? = runCatching {
        xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE).use {
            var window = xServer.inputDeviceManager.pointWindow
            var cursor: Cursor? = null
            while (window != null && cursor == null) {
                cursor = window.attributes.cursor
                window = window.parent
            }
            cursor?.let { fingerprints.getOrPut(it) { fingerprint(it) } }
        }
    }.getOrNull()

    private fun fingerprint(cursor: Cursor): Long {
        var hash = 1469598103934665603L
        fun add(value: Int) {
            hash = (hash xor value.toLong()) * 1099511628211L
        }
        add(cursor.hotSpotX)
        add(cursor.hotSpotY)
        val image = cursor.cursorImage ?: return hash
        add(image.width.toInt())
        add(image.height.toInt())
        val data = image.data?.duplicate() ?: return hash
        data.rewind()
        while (data.remaining() >= 4) add(data.int)
        return hash
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
        pointerMoves++
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
        /** How long the game takes to show its cursor for where the pointer went (a frame or two at a phone's rate). */
        private const val CURSOR_SETTLE_MS = 90L
        /** A second tap on the same spot within this talks to what the first one selected (without a cursor to read). */
        private const val AGAIN_MS = 1500L
        /** How long a tap waits for the realm to say what it selected, and how often it looks. */
        private const val SELECTION_WAIT_MS = 400L
        private const val SELECTION_POLL_MS = 25L
        private const val AGAIN_SLOP_DP = 40f
        /** When the login screen's cursor is looked at, after the game appears. */
        private val ARROW_SAMPLES_MS = longArrayOf(4000L, 8000L, 12000L)
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
