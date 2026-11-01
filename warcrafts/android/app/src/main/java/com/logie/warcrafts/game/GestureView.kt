// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.logie.warcrafts.RtsFiles
import com.logie.warcrafts.controls.AimWatch
import com.logie.warcrafts.controls.Button
import com.logie.warcrafts.controls.CommandCard
import com.logie.warcrafts.controls.GestureOutput
import com.logie.warcrafts.controls.Key
import com.logie.warcrafts.controls.RtsGestures
import com.logie.warcrafts.library.Title
import com.winlator.XServerDisplayActivity
import com.winlator.core.AppUtils
import com.winlator.math.XForm
import com.winlator.renderer.ViewTransformation
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * The games' touch controls ([RtsGestures]) over Winlator's game screen, in place of its touchpad:
 * Winlator's XServerDisplayActivity puts it on top (patches/winlator/0004). It turns the gestures
 * into the X server's pointer, buttons and keys, where Wine passes them to the game: swipes pan the
 * map, taps right-click, holds left-click, hold-and-drag draws a selection box, a double tap
 * attack-moves, a resting finger is Shift, two fingers open the game's menu, three the phone's
 * keyboard, four the app's menu ([GameMenu]). Back is the game's Escape; the volume buttons are Tab
 * (up) and the idle worker, F8, or health bars, Alt, while held (down) (patches/winlator/0005).
 *
 * Over the game it draws only a ring for a moment where a touch clicked (green: right click, gold:
 * left click, blue: Shift-click, red: attack-move) and, during a hold, a magnifier above the finger
 * (copied from the game's screen with PixelCopy). The same copies of the command card tell when the
 * game waits for an order's target ([AimWatch]): a tap is then the left click that gives it.
 */
@SuppressLint("ViewConstructor")
class GestureView(
    private val activity: AppCompatActivity,
    private val xServer: XServer,
    /** Leaves the game for the launcher. */
    private val onLeave: Runnable,
) : View(activity), GestureOutput {
    private val density = resources.displayMetrics.density
    private val gestures = GameSettings.gestures(activity, this)
    private val title = Title.of(activity.intent.getStringExtra(EXTRA_TITLE)) ?: Title.TFT
    private val vibrate = GameSettings.vibration(activity)
    private val showMarkers = GameSettings.markers(activity)
    private val showMagnifier = GameSettings.magnifier(activity)

    /** Winlator's game screen (a GLSurfaceView), copied for the magnifier and the command card. */
    private val surface = (activity as? XServerDisplayActivity)?.xServerView
    private val main = Handler(Looper.getMainLooper())

    /** From the view's pixels to the game screen's. */
    private val xform = XForm.getInstance()

    /** From the game screen's pixels back to the view's: the inverse of [xform]. */
    private var viewScale = 1f
    private var viewOffsetX = 0f
    private var viewOffsetY = 0f

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
        setWillNotDraw(false)
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
        viewScale = view.aspect
        viewOffsetX = if (xServer.renderer.isFullscreen) 0f else view.viewOffsetX.toFloat()
        viewOffsetY = if (xServer.renderer.isFullscreen) 0f else view.viewOffsetY.toFloat()
        if (!xServer.renderer.isFullscreen) {
            XForm.makeTranslation(xform, -view.viewOffsetX.toFloat(), -view.viewOffsetY.toFloat())
            XForm.scale(xform, scale, scale)
        } else {
            XForm.makeScale(xform, scale, scale)
        }
    }

    private fun gameToViewX(x: Float) = x * viewScale + viewOffsetX
    private fun gameToViewY(y: Float) = y * viewScale + viewOffsetY

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
                postDelayed(hold, gestures.holdMs)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Each finger can be a hold (the one tapping while another rests).
                removeCallbacks(hold)
                gestures.down(id, event.getX(index), event.getY(index), now)
                postDelayed(hold, gestures.holdMs)
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
        aim.done()
        tapKey(XKeycode.KEY_ESC)
    }

    /**
     * The phone's volume buttons in the game (patches/winlator/0005), in place of the volume: up is
     * Tab (the selection's next kind of unit), down is F8 (the next idle worker) or, held, Alt (every
     * unit's health bar, while held). True: the key was the game's.
     */
    fun onVolumeKey(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    tapKey(XKeycode.KEY_TAB)
                    haptic(TICK)
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) postDelayed(holdAlt, VOLUME_HOLD_MS)
                KeyEvent.ACTION_UP -> {
                    removeCallbacks(holdAlt)
                    if (altHeld) {
                        altHeld = false
                        xServer.injectKeyRelease(XKeycode.KEY_ALT_L)
                    } else {
                        tapKey(XKeycode.KEY_F8)
                        haptic(TICK)
                    }
                }
            }
            else -> return false
        }
        return true
    }

    private var altHeld = false
    private val holdAlt = Runnable {
        altHeld = true
        xServer.injectKeyPress(XKeycode.KEY_ALT_L)
        haptic(TICK)
    }

    /** The game's first window is up: on a touch screen the finger is the pointer, never drawn. */
    fun onGameWindowShown() {
        xServer.renderer.isCursorVisible = false
    }

    // Gestures ---------------------------------------------------------------------------------

    override fun pointerTo(x: Float, y: Float) {
        val point = XForm.transformPoint(xform, x, y)
        // Never on the screen's very edge, where Warcraft III scrolls the map toward the pointer.
        val width = xServer.screenInfo.width.toFloat()
        val height = xServer.screenInfo.height.toFloat()
        pointerX = point[0].coerceIn(EDGE_PX, maxOf(EDGE_PX, width - 1 - EDGE_PX))
        pointerY = point[1].coerceIn(EDGE_PX, maxOf(EDGE_PX, height - 1 - EDGE_PX))
        touchX = x
        touchY = y
        xServer.injectPointerMove(pointerX.toInt(), pointerY.toInt())
    }

    /** Where the pointer is on the game's screen (its pixels). */
    private var pointerX = 0f
    private var pointerY = 0f
    /** Where the finger that put it there was (the view's pixels), for the markers. */
    private var touchX = 0f
    private var touchY = 0f

    /**
     * Warcraft III's interface, where a right click does nothing useful and a tap is the left
     * click that presses what's under it: the panel along the bottom (minimap, portrait,
     * inventory, command buttons), the menu bar along the top, and down the left edge the heroes'
     * portraits and the idle worker's button. On the battlefield a tap stays the right click.
     * The left edge's parts are sized by the screen's height (the game keeps them so on wide
     * screens) and approximate.
     */
    private fun onInterface(): Boolean {
        val height = xServer.screenInfo.height.toFloat()
        if (height <= 0f) return false
        val y = pointerY / height
        if (y >= WC3_PANEL_TOP || y <= WC3_BAR_BOTTOM) return true
        val x = pointerX / height
        return x <= WC3_LEFT_COLUMN && (y <= WC3_HEROES_BOTTOM || y >= WC3_IDLE_TOP)
    }

    private fun onCommandCard(): Boolean = CommandCard.contains(
        xServer.screenInfo.width.toFloat(), xServer.screenInfo.height.toFloat(), pointerX, pointerY,
    )

    override fun click(button: Button) {
        val onPanel = onInterface()
        val pressed = if (button == Button.RIGHT && onPanel) Button.LEFT else button
        mark(
            when {
                shiftHeld -> SHIFT_COLOUR
                pressed == Button.RIGHT -> RIGHT_COLOUR
                else -> LEFT_COLOUR
            }
        )
        haptic(if (shiftHeld) SHIFT_PULSES else if (pressed == Button.RIGHT) TICK else null)
        if (pressed == Button.LEFT && onCommandCard()) {
            // A command button: the card before and after it tells whether the game now waits for a target.
            pressCommand()
            return
        }
        if (pressed == Button.LEFT && aim.aiming) aim.done()
        sendClick(pressed)
    }

    private fun sendClick(button: Button) {
        xServer.injectPointerButtonPress(button.x)
        postDelayed({ xServer.injectPointerButtonRelease(button.x) }, CLICK_MS)
    }

    override fun doubleClick(button: Button) {
        mark(LEFT_COLOUR)
        xServer.injectPointerButtonPress(button.x)
        postDelayed({ xServer.injectPointerButtonRelease(button.x) }, CLICK_MS)
        postDelayed({ xServer.injectPointerButtonPress(button.x) }, CLICK_MS * 2)
        postDelayed({ xServer.injectPointerButtonRelease(button.x) }, CLICK_MS * 3)
    }

    override fun press(button: Button) {
        if (aim.aiming) aim.done()
        xServer.injectPointerButtonPress(button.x)
    }

    override fun release(button: Button) {
        xServer.injectPointerButtonRelease(button.x)
        mark(if (shiftHeld) SHIFT_COLOUR else LEFT_COLOUR)
    }

    override fun aiming(): Boolean = aim.aiming

    /** A on the battlefield (the Attack order's key), then the left click: attack-move there. */
    override fun attackMove(): Boolean {
        if (onInterface()) return false
        tapKey(XKeycode.KEY_A)
        postDelayed({ sendClick(Button.LEFT) }, KEY_MS + CLICK_MS)
        mark(ATTACK_COLOUR)
        haptic(ATTACK_PULSE)
        return true
    }

    override fun nextSubgroup() {
        tapKey(XKeycode.KEY_TAB)
        haptic(TICK)
    }

    // Waiting for a target: the command card, read off the screen -------------------------------

    private val aim = AimWatch()
    private val card = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
    private val cardPixels = IntArray(CARD_W * CARD_H)

    /** The command card as it is on the screen now, or null if it can't be copied. */
    private fun copyCard(done: (CommandCard.Look?) -> Unit) {
        val view = surface
        val width = xServer.screenInfo.width.toFloat()
        val height = xServer.screenInfo.height.toFloat()
        if (view == null || width <= 0f || height <= 0f || view.width <= 0) {
            done(null)
            return
        }
        val (left, top, right, bottom) = CommandCard.area(width, height)
        val rect = Rect(
            gameToViewX(left).toInt(), gameToViewY(top).toInt(),
            gameToViewX(right).toInt().coerceAtMost(view.width), gameToViewY(bottom).toInt().coerceAtMost(view.height),
        )
        if (rect.width() <= 0 || rect.height() <= 0) {
            done(null)
            return
        }
        try {
            PixelCopy.request(view, rect, card, { result ->
                if (result == PixelCopy.SUCCESS) {
                    card.getPixels(cardPixels, 0, CARD_W, 0, 0, CARD_W, CARD_H)
                    done(CommandCard.Look.of(cardPixels, CARD_W, CARD_H))
                } else {
                    done(null)
                }
            }, main)
        } catch (e: IllegalArgumentException) {
            done(null)
        }
    }

    /** The card before the press, the press, the card after it: waiting for a target or not. */
    private fun pressCommand() {
        copyCard { before ->
            sendClick(Button.LEFT)
            if (before == null) {
                aim.done()
                return@copyCard
            }
            postDelayed({
                copyCard { after ->
                    if (after == null) aim.done() else aim.pressed(before, after, SystemClock.uptimeMillis())
                    if (aim.aiming) watchAim()
                }
            }, CARD_SETTLE_MS)
        }
    }

    /** While the game waits for a target, the card is looked at again every so often. */
    private fun watchAim() {
        removeCallbacks(lookAgain)
        postDelayed(lookAgain, CARD_LOOK_MS)
    }

    private val lookAgain: Runnable = Runnable {
        if (!aim.aiming) return@Runnable
        copyCard { look ->
            if (look == null) aim.done() else aim.seen(look, SystemClock.uptimeMillis())
            if (aim.aiming) watchAim()
        }
    }

    // What's drawn: markers and the magnifier --------------------------------------------------

    private class Marker(val x: Float, val y: Float, val colour: Int, val at: Long)

    private val markers = ArrayList<Marker>()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** A ring for a moment where the last touch clicked. */
    private fun mark(colour: Int) {
        if (!showMarkers) return
        markers += Marker(touchX, touchY, colour, SystemClock.uptimeMillis())
        postInvalidateOnAnimation()
    }

    private var magnifying = false
    private var magnifierX = 0f
    private var magnifierY = 0f
    private var magnifierFresh = false
    private var copying = false
    private val magnifierSize = (MAGNIFIER_DP * 2 * density).toInt()
    private val magnified = Bitmap.createBitmap(magnifierSize, magnifierSize, Bitmap.Config.ARGB_8888)
    private val magnifierClip = Path()
    private val magnifierPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val magnifierEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = LEFT_COLOUR
    }
    private val crosshair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * density
        color = 0xCCFFFFFF.toInt()
    }

    override fun magnify(x: Float, y: Float) {
        if (!showMagnifier || surface == null) return
        magnifierX = x
        magnifierY = y
        if (!magnifying) {
            magnifying = true
            magnifierFresh = false
        }
        copyMagnified()
    }

    override fun magnifyOff() {
        magnifying = false
        magnifierFresh = false
        invalidate()
    }

    /** What's under the finger, [MAGNIFIER_ZOOM] times bigger, copied from the game's screen. */
    private fun copyMagnified() {
        val view = surface ?: return
        if (copying || !magnifying) return
        val half = MAGNIFIER_DP * density / MAGNIFIER_ZOOM
        val left = (magnifierX - half).coerceIn(0f, maxOf(0f, view.width - 2 * half))
        val top = (magnifierY - half).coerceIn(0f, maxOf(0f, view.height - 2 * half))
        val rect = Rect(left.toInt(), top.toInt(), (left + 2 * half).toInt(), (top + 2 * half).toInt())
        if (rect.width() <= 0 || rect.height() <= 0) return
        copying = true
        try {
            PixelCopy.request(view, rect, magnified, { result ->
                copying = false
                if (result == PixelCopy.SUCCESS && magnifying) {
                    magnifierFresh = true
                    invalidate()
                    // While the finger stays, the game moves on: copied again a frame or two later.
                    postDelayed({ copyMagnified() }, MAGNIFIER_FRAME_MS)
                }
            }, main)
        } catch (e: IllegalArgumentException) {
            copying = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        markers.removeAll { now - it.at > MARKER_MS }
        for (marker in markers) {
            val t = (now - marker.at).toFloat() / MARKER_MS
            ring.color = marker.colour
            ring.alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
            ring.strokeWidth = 3f * density
            canvas.drawCircle(marker.x, marker.y, (10f + 18f * t) * density, ring)
        }
        if (magnifying && magnifierFresh) {
            val radius = magnifierSize / 2f
            val gap = MAGNIFIER_GAP_DP * density
            // Above the finger; below it when the finger is near the top.
            val cx = magnifierX.coerceIn(radius, maxOf(radius, width - radius))
            val cy = if (magnifierY - gap - radius >= 0f) magnifierY - gap else magnifierY + gap
            magnifierClip.reset()
            magnifierClip.addCircle(cx, cy, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(magnifierClip)
            canvas.drawBitmap(magnified, cx - radius, cy - radius, magnifierPaint)
            canvas.restore()
            canvas.drawCircle(cx, cy, radius, magnifierEdge)
            // Where the finger is, in the magnified picture (off-centre at the screen's edges).
            val half = MAGNIFIER_DP * density / MAGNIFIER_ZOOM
            val left = (magnifierX - half).coerceIn(0f, maxOf(0f, width - 2 * half))
            val top = (magnifierY - half).coerceIn(0f, maxOf(0f, height - 2 * half))
            val px = cx - radius + (magnifierX - left) * MAGNIFIER_ZOOM
            val py = cy - radius + (magnifierY - top) * MAGNIFIER_ZOOM
            val arm = 6f * density
            canvas.drawLine(px - arm, py, px + arm, py, crosshair)
            canvas.drawLine(px, py - arm, px, py + arm, crosshair)
        }
        if (markers.isNotEmpty()) postInvalidateOnAnimation()
    }

    // Vibrations ---------------------------------------------------------------------------------

    /** A vibration's pattern (off, on, off, on… in milliseconds), if the launcher's vibration is on. */
    private fun haptic(pattern: LongArray?) {
        if (!vibrate || pattern == null) return
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    override fun wheel(up: Boolean) {
        val button = if (up) Pointer.Button.BUTTON_SCROLL_UP else Pointer.Button.BUTTON_SCROLL_DOWN
        xServer.injectPointerButtonPress(button)
        xServer.injectPointerButtonRelease(button)
    }

    override fun keyDown(key: Key) {
        if (key == Key.SHIFT) shiftHeld = true
        xServer.injectKeyPress(key.x)
    }

    override fun keyUp(key: Key) {
        if (key == Key.SHIFT) shiftHeld = false
        xServer.injectKeyRelease(key.x)
    }

    /** A finger rests: clicks are Shift-clicks (their marker blue, their vibration two pulses). */
    private var shiftHeld = false

    override fun gameMenu() = tapKey(XKeycode.KEY_F10)

    override fun openKeyboard() = askText(chat = true)

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
        removeCallbacks(typeNext)
        removeCallbacks(lookAgain)
        removeCallbacks(holdAlt)
        super.onDetachedFromWindow()
    }

    companion object {
        /** The game the launcher started ([Title.key]): the loading screen's and menus' look. */
        const val EXTRA_TITLE = "com.logie.warcrafts.TITLE"

        private const val CLICK_MS = 40L
        /** Warcraft III's bottom panel starts about three quarters down; its top bar ends 5% down. */
        private const val WC3_PANEL_TOP = 0.76f
        private const val WC3_BAR_BOTTOM = 0.05f
        /** The heroes' portraits and the idle worker's button: this wide, as a part of the screen's height. */
        private const val WC3_LEFT_COLUMN = 0.06f
        /** The heroes' portraits end this far down, the idle worker's button starts this far down. */
        private const val WC3_HEROES_BOTTOM = 0.45f
        private const val WC3_IDLE_TOP = 0.66f
        /** The pointer stays this many of the game's pixels from the screen's edge (no edge scrolling). */
        private const val EDGE_PX = 3f

        /** The command card copied at this size (4×3 slots of 12×10). */
        private const val CARD_W = 48
        private const val CARD_H = 30
        /** After a command button's press, the card is looked at again this much later. */
        private const val CARD_SETTLE_MS = 250L
        /** While the game waits for a target, the card is looked at this often. */
        private const val CARD_LOOK_MS = 400L

        /** The magnifier: its radius (dp), how much bigger, how far above the finger (dp), how often. */
        private const val MAGNIFIER_DP = 64f
        private const val MAGNIFIER_ZOOM = 2f
        private const val MAGNIFIER_GAP_DP = 110f
        private const val MAGNIFIER_FRAME_MS = 33L

        private const val MARKER_MS = 380L
        private const val RIGHT_COLOUR = 0xFF4CD964.toInt()
        private const val LEFT_COLOUR = 0xFFFFD34D.toInt()
        private const val SHIFT_COLOUR = 0xFF4DA6FF.toInt()
        private const val ATTACK_COLOUR = 0xFFFF4D4D.toInt()

        /** Volume down held this long: Alt (health bars) instead of F8. */
        private const val VOLUME_HOLD_MS = 300L

        private val TICK = longArrayOf(0, 12)
        private val SHIFT_PULSES = longArrayOf(0, 14, 60, 14)
        private val ATTACK_PULSE = longArrayOf(0, 40)
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
