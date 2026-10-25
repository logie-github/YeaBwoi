// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

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
import com.logie.warcrafts.controls.Button
import com.logie.warcrafts.controls.GestureOutput
import com.logie.warcrafts.controls.HeldDrag
import com.logie.warcrafts.controls.Key
import com.logie.warcrafts.controls.RtsGestures
import com.logie.warcrafts.library.Title
import com.logie.warcrafts.library.Wc3Screen
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
 * drawn over the game: swipes pan the map, taps right-click, holds left-click, a resting finger is
 * Shift, two fingers open the game's menu, three the phone's keyboard, four the app's menu
 * ([GameMenu]). Back is the game's Escape.
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
    /** What the game shows: a match (its console) or its menus, a paused battlefield ([Wc3Watcher]). */
    private val watcher = Wc3Watcher(xServer)
    /** Until when (uptime ms) taps are left clicks: just after the game's menu was asked for. */
    private var dialogUntil = 0L
    /**
     * Until when (uptime ms) the next tap on the battlefield is a left click: after a command
     * button or an item was pressed, the game may be waiting for its target (a spell, Attack,
     * Patrol, where to build), which only a left click gives; a right click would cancel it.
     */
    private var aimUntil = 0L
    /** The game's mouse pointer shapes seen (its normal one is the shape at plain right clicks). */
    private val cursorShapes = HashMap<com.winlator.xserver.Cursor, Long>()
    private val shapesSeen = HashSet<Long>()
    private var normalShape: Long? = null
    /** A two-finger tap waiting to see whether a second one follows (then it's Space, not the menu). */
    private var pendingMenu: Runnable? = null

    /** From the view's pixels to the game screen's. */
    private val xform = XForm.getInstance()
    private val hold = Runnable { gestures.onHold(SystemClock.uptimeMillis()) }

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
                // Each finger can be a hold (the one tapping while another rests).
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

    /** Back in the game: Escape (cancels an order, closes a menu). */
    fun escape() {
        aimUntil = 0L
        dialogUntil = 0L
        tapKey(XKeycode.KEY_ESC)
    }

    /** The game's first window is up: on a touch screen the finger is the pointer, never drawn. */
    fun onGameWindowShown() {
        xServer.renderer.isCursorVisible = false
        watcher.start()
        Wc3Report.note("the game's window is up")
        removeCallbacks(skipBlack)
        postDelayed(skipBlack, 1000)
    }

    // Gestures ---------------------------------------------------------------------------------

    override fun pointerTo(x: Float, y: Float) {
        val point = XForm.transformPoint(xform, x, y)
        pointerX = point[0]
        pointerY = point[1]
        // While a drag's button waits to go down where it started, the pointer waits too.
        if (pressPending) return
        xServer.injectPointerMove(point[0].toInt(), point[1].toInt())
    }

    /** A drag's button is about to go down (after [SETTLE_MS]): pointer moves wait for it. */
    private var pressPending = false
    private var releaseAfterPress = false

    /**
     * A button press where the pointer is now, after the game has had time to see the pointer there:
     * the pointer moved there with the touch, but the game reads it once a frame and may take the
     * press first, at the pointer's old place. So: [SETTLE_MS], a one-pixel nudge and back (a fresh
     * movement for the game to read), then the press.
     */
    private fun pressHere(button: Pointer.Button, then: () -> Unit) {
        val x = pointerX.toInt()
        val y = pointerY.toInt()
        postDelayed({
            xServer.injectPointerMove(x + 1, y)
            xServer.injectPointerMove(x, y)
            xServer.injectPointerButtonPress(button)
            then()
        }, SETTLE_MS)
    }

    /** Where the pointer is on the game's screen, in its pixels. */
    private var pointerX = 0f
    private var pointerY = 0f

    /**
     * Whether a tap here is the left click that presses what's under it, rather than the right
     * click (move, attack, gather) it is on the battlefield. Read from the game's picture
     * ([Wc3Watcher]): its menus, loading and score screens and cinematics (no console on screen);
     * in a match, its console (the panel along the bottom, the bar along the top) and a dialog
     * over a paused battlefield (the F10 menu, the quest log), or just after the game's menu was
     * asked for. Until the picture has been read: the panel and the bar only, as before.
     */
    private fun leftClickHere(): Boolean {
        val width = xServer.screenInfo.width.toFloat()
        val height = xServer.screenInfo.height.toFloat()
        if (width <= 0f || height <= 0f) return false
        val x = pointerX / width
        val y = pointerY / height
        // No console seen (the menus, or the picture not read yet): the game's menus take left clicks.
        val race = watcher.race ?: return true
        val now = SystemClock.uptimeMillis()
        if (Wc3Screen.onHeroButtons(x, y)) return true
        if (Wc3Screen.onConsole(race, x, y)) {
            // The bar's buttons on the left (Quests, Menu, Allies, Log) open dialogs.
            if (y <= Wc3Screen.TOP_BAR && x < MENU_BUTTONS_END) dialogUntil = now + DIALOG_MS
            return true
        }
        if (now < dialogUntil) return true
        val still = watcher.stillSince
        return still != 0L && now - still >= STILL_MS
    }

    /** The pointer's place as fractions of the game's screen, or null before the screen is known. */
    private fun pointerAt(): Pair<Float, Float>? {
        val width = xServer.screenInfo.width.toFloat()
        val height = xServer.screenInfo.height.toFloat()
        if (width <= 0f || height <= 0f) return null
        return pointerX / width to pointerY / height
    }

    /** A point of the view as fractions of the game's screen. */
    private fun screenAt(viewX: Float, viewY: Float): Pair<Float, Float>? {
        val width = xServer.screenInfo.width.toFloat()
        val height = xServer.screenInfo.height.toFloat()
        if (width <= 0f || height <= 0f) return null
        val point = XForm.transformPoint(xform, viewX, viewY)
        return point[0] / width to point[1] / height
    }

    /** On a command button or an item: what may wait for a target. */
    private fun onOrders(x: Float, y: Float): Boolean = watcher.race != null && Wc3Screen.onOrders(x, y)

    /** The shape of the game's mouse pointer now (its picture, hot spot and size), or null. */
    private fun cursorShape(): Long? {
        val cursor = xServer.inputDeviceManager.pointWindow?.attributes?.cursor ?: return null
        return cursorShapes.getOrPut(cursor) {
            val data = cursor.cursorImage.data
            var hash = 17L
            if (data != null) {
                val copy = data.duplicate()
                copy.rewind()
                while (copy.hasRemaining()) hash = hash * 31 + copy.get()
            }
            (hash * 31 + cursor.hotSpotX) * 31 + cursor.hotSpotY
        }
    }

    /**
     * Whether the game's pointer says it isn't waiting for a target: its shape is the one it has at
     * plain right clicks. Known only once the game has shown more than one shape (a game drawing its
     * own pointer shows one); until then, unknown (null).
     */
    private fun pointerIsNormal(): Boolean? {
        val shape = cursorShape() ?: return null
        shapesSeen += shape
        val normal = normalShape ?: return null
        if (shapesSeen.size < 2) return null
        return shape == normal
    }

    override fun click(button: Button) {
        val now = SystemClock.uptimeMillis()
        val (x, y) = pointerAt() ?: (0f to 0f)
        var why: String
        val pressed = when {
            button == Button.LEFT -> {
                // A hold: on a command button it may start aiming; elsewhere it's the aim's target, or a selection.
                aimUntil = if (onOrders(x, y)) now + AIM_MS else 0L
                why = "hold"
                Button.LEFT
            }
            leftClickHere() -> {
                if (onOrders(x, y)) aimUntil = now + AIM_MS
                why = when {
                    watcher.race == null -> "tap, no console (menus)"
                    Wc3Screen.onHeroButtons(x, y) -> "tap, hero buttons"
                    Wc3Screen.onConsole(watcher.race!!, x, y) -> "tap, console"
                    else -> "tap, dialog"
                }
                Button.LEFT
            }
            // On the battlefield, just after a command button: its target, unless the pointer says otherwise.
            now < aimUntil -> {
                aimUntil = 0L
                val normal = pointerIsNormal()
                why = "tap after a command button, pointer normal: ${normal ?: "not known"}"
                if (normal == true) Button.RIGHT else Button.LEFT
            }
            else -> {
                cursorShape()?.let {
                    shapesSeen += it
                    normalShape = it
                }
                why = "tap, battlefield"
                Button.RIGHT
            }
        }
        Wc3Report.tap("%.3f,%.3f %s → %s".format(x, y, why, pressed))
        pressHere(pressed.x) { postDelayed({ xServer.injectPointerButtonRelease(pressed.x) }, CLICK_MS) }
    }

    override fun doubleClick(button: Button) {
        val (x, y) = pointerAt() ?: (0f to 0f)
        Wc3Report.tap("%.3f,%.3f double hold → double %s".format(x, y, button))
        pressHere(button.x) {
            postDelayed({ xServer.injectPointerButtonRelease(button.x) }, CLICK_MS)
            postDelayed({ xServer.injectPointerButtonPress(button.x) }, CLICK_MS + DOUBLE_GAP_MS)
            postDelayed({ xServer.injectPointerButtonRelease(button.x) }, CLICK_MS * 2 + DOUBLE_GAP_MS)
        }
    }

    override fun press(button: Button) {
        val (x, y) = pointerAt() ?: (0f to 0f)
        Wc3Report.tap("%.3f,%.3f drag starts → %s down".format(x, y, button))
        pressPending = true
        pressHere(button.x) {
            pressPending = false
            // Where the finger went meanwhile.
            xServer.injectPointerMove(pointerX.toInt(), pointerY.toInt())
            if (releaseAfterPress) {
                releaseAfterPress = false
                postDelayed({ xServer.injectPointerButtonRelease(button.x) }, CLICK_MS)
            }
        }
    }

    override fun release(button: Button) {
        if (pressPending) {
            releaseAfterPress = true
            return
        }
        xServer.injectPointerButtonRelease(button.x)
    }

    /** A film the game shows black: the picture all black a while after the game had shown something. Escape skips it. */
    private val skipBlack = object : Runnable {
        private var skippedFor = 0L
        override fun run() {
            val since = watcher.blackSince
            if (since != 0L && since != skippedFor && watcher.shownSomething &&
                SystemClock.uptimeMillis() - since >= BLACK_SKIP_MS
            ) {
                skippedFor = since
                Wc3Report.note("the picture stayed black ${BLACK_SKIP_MS / 1000} s: Escape (a film shown black)")
                tapKey(XKeycode.KEY_ESC)
            }
            postDelayed(this, 1000)
        }
    }

    /** Held, then moved: a selection box on the battlefield; elsewhere the hold is taken back (slid off a button). */
    override fun heldDrag(x: Float, y: Float): HeldDrag {
        val race = watcher.race ?: return HeldDrag.CANCEL
        val (sx, sy) = screenAt(x, y) ?: return HeldDrag.CANCEL
        if (Wc3Screen.onHeroButtons(sx, sy) || Wc3Screen.onConsole(race, sx, sy)) return HeldDrag.CANCEL
        return HeldDrag.SELECT
    }

    /** A swipe from the minimap moves the camera with it (the left button held), as a mouse would. */
    override fun dragZone(x: Float, y: Float): Boolean {
        if (watcher.race == null) return false
        val (sx, sy) = screenAt(x, y) ?: return false
        return Wc3Screen.onMinimap(sx, sy)
    }

    override fun wheel(up: Boolean) {
        val button = if (up) Pointer.Button.BUTTON_SCROLL_UP else Pointer.Button.BUTTON_SCROLL_DOWN
        xServer.injectPointerButtonPress(button)
        xServer.injectPointerButtonRelease(button)
    }

    override fun keyDown(key: Key) = xServer.injectKeyPress(key.x)

    override fun keyUp(key: Key) = xServer.injectKeyRelease(key.x)

    /** Two fingers: the game's menu (F10); twice quickly: Space, to the last alert (base under attack). */
    override fun gameMenu() {
        pendingMenu?.let {
            removeCallbacks(it)
            pendingMenu = null
            tapKey(XKeycode.KEY_SPACE)
            return
        }
        val menu = Runnable {
            pendingMenu = null
            dialogUntil = SystemClock.uptimeMillis() + DIALOG_MS
            tapKey(XKeycode.KEY_F10)
        }
        pendingMenu = menu
        postDelayed(menu, SECOND_TAP_MS)
    }

    override fun openKeyboard() = askText(chat = true)

    override fun showMenu() = GameMenu.show(
        activity,
        title,
        onKeyboard = { askText(chat = false) },
        onChat = { askText(chat = true) },
        onLeave = { leave() },
    )

    override fun feedback() {
        if (!vibrate) return
        if (!performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)) {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** A key pressed and let go. */
    private fun tapKey(key: XKeycode) {
        xServer.injectKeyPress(key)
        postDelayed({ xServer.injectKeyRelease(key) }, KEY_MS)
    }

    /** Enter, then the prompt; its text is typed into the game with Enter (a chat line, a cheat code). */
    private fun askText(chat: Boolean) {
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
        gestures.cancel()
        onLeave.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(skipBlack)
        watcher.stop()
        Wc3Report.finish(watcher.summary())
        // A run that showed only black: the launcher says so, with the report ([GameStops]).
        if (watcher.blackOnly) runCatching { java.io.File(context.filesDir, GameStops.BLACK_RUN).writeText("") }
        removeCallbacks(typeNext)
        super.onDetachedFromWindow()
    }

    companion object {
        /** The game the launcher started ([Title.key]): the loading screen's and menus' look. */
        const val EXTRA_TITLE = "com.logie.warcrafts.TITLE"

        /**
         * How long a click holds the button: Warcraft III reads the mouse once a frame, and at the
         * phone's frame rate a shorter click can fall between two readings and never be seen.
         */
        private const val CLICK_MS = 110L
        private const val DOUBLE_GAP_MS = 60L
        /** From the pointer reaching a spot to the button going down there: two frames at 30 a second. */
        private const val SETTLE_MS = 70L
        /** A picture black this long, after the game had shown something: a film shown black, skipped. */
        private const val BLACK_SKIP_MS = 5000L
        /** How long after a command button the next battlefield tap gives its target. */
        private const val AIM_MS = 8000L
        /** A second two-finger tap within this long is Space. */
        private const val SECOND_TAP_MS = 320L
        /** Before the picture is read: Warcraft III's bottom panel starts about three quarters down; its top bar ends 5% down. */
        private const val WC3_PANEL_TOP = 0.76f
        private const val WC3_BAR_BOTTOM = 0.05f
        /** The top bar's menu buttons end at 0.343 of its 0.8 width (ConsoleUI.fdf's first two pieces). */
        private const val MENU_BUTTONS_END = 0.343f / 0.8f
        /** Taps stay left clicks this long after a dialog was asked for, until the paused battlefield shows. */
        private const val DIALOG_MS = 1500L
        /** A battlefield unchanged for this long: the game paused, a dialog over it. */
        private const val STILL_MS = 400L
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
                Key.SHIFT -> XKeycode.KEY_SHIFT_L
            }
    }
}
