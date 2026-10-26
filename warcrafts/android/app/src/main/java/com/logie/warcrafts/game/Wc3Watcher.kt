// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.logie.warcrafts.library.Wc3Screen
import com.winlator.renderer.RenderTarget
import com.winlator.renderer.VertexAttribute
import com.winlator.renderer.material.ScreenMaterial
import com.winlator.xserver.Window
import com.winlator.xserver.XServer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads Warcraft III's picture a few times a second ([Wc3Screen]): whether a match is on (its
 * console, and its race) and whether the battlefield is still (the game paused under a dialog).
 * On Winlator's drawing thread it draws the game's window at half its size into a texture of its
 * own (each pixel the average of four) and reads it back, then works it out on the main thread.
 */
class Wc3Watcher(private val xServer: XServer) {
    /** The console's race while a match is on, or null; meaningful once [known]. */
    @Volatile var race: String? = null
        private set
    /** Whether the picture has been read at least once (until then the old rules apply). */
    @Volatile var known = false
        private set
    /** Since when (uptime ms) the battlefield hasn't moved, or 0. */
    @Volatile var stillSince = 0L
        private set
    /** Since when (uptime ms) the whole picture has been black, or 0. */
    @Volatile var blackSince = 0L
        private set
    /** Whether the game has shown anything but black since it started (a menu, a loading screen). */
    @Volatile var shownSomething = false
        private set
    /** The command card's colours at [Wc3Screen.ORDER_POINTS] while a match is on, or null. */
    @Volatile var orders: List<Int>? = null
        private set
    /** When (uptime ms) the picture [orders] came from was read. */
    @Volatile var ordersAt = 0L
        private set

    // For the report ([summary]).
    private var readings = 0
    private var blackReadings = 0
    private var stillReadings = 0
    private var pictureSize = ""
    private val racesSeen = HashMap<String, Int>()
    private var lastField: List<Int>? = null

    private val main = Handler(Looper.getMainLooper())
    private var running = false
    /** Which way up the read picture is: found from the first console seen. */
    @Volatile private var flipped: Boolean? = null

    private var target: RenderTarget? = null
    private var material: ScreenMaterial? = null
    private val quad = VertexAttribute("position", 2).apply { put(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)) }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            read()
            main.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        main.post(poll)
    }

    fun stop() {
        running = false
        main.removeCallbacks(poll)
        val renderer = xServer.renderer ?: return
        renderer.xServerView.queueEvent {
            target?.destroy()
            target = null
            material?.destroy()
            material = null
            quad.destroy()
        }
    }

    /** The game's window: the biggest one shown. */
    private fun gameWindow(): Window? {
        var best: Window? = null
        var bestArea = 0
        fun visit(window: Window) {
            if (window.attributes.isMapped && window.attributes.isViewable && window.isRenderable) {
                val content = window.content
                if (content != null) {
                    val area = content.width * content.height
                    if (area > bestArea) {
                        bestArea = area
                        best = window
                    }
                }
            }
            for (child in window.children) visit(child)
        }
        visit(xServer.windowManager.rootWindow)
        return best
    }

    private fun read() {
        val renderer = xServer.renderer ?: return
        val drawable = gameWindow()?.content ?: return
        renderer.xServerView.queueEvent {
            val picture = synchronized(drawable.renderLock) {
                val texture = drawable.texture ?: return@queueEvent
                texture.updateFromDrawable()
                pictureSize = "${drawable.width}x${drawable.height}"
                draw(texture.textureId, texture.isFlipY, maxOf(1, drawable.width / 2), maxOf(1, drawable.height / 2))
            }
            // Worked out here, on the drawing thread, from the picture read into [pixels]; only the
            // answer goes to the main thread.
            val reading = understand(picture)
            main.post { apply(reading) }
        }
        renderer.xServerView.requestRender()
    }

    private var pixels: ByteBuffer? = null
    private var targetWidth = 0
    private var targetHeight = 0

    /** Draws the game's texture at [width] x [height] into this watcher's own and reads it back. */
    private fun draw(textureId: Int, flipY: Boolean, width: Int, height: Int): Picture {
        if (target == null || targetWidth != width || targetHeight != height) {
            target?.destroy()
            target = RenderTarget().apply { allocateFramebuffer(width, height) }
            targetWidth = width
            targetHeight = height
            pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        }
        val viewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
        val screen = material ?: ScreenMaterial().also { material = it }
        // The picture as it is, not blended (Winlator's drawing blends by alpha).
        val blending = GLES20.glIsEnabled(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target!!.framebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        screen.use()
        screen.setUniformBool(screen.uniforms.flipY, flipY)
        quad.bind(screen.programId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        screen.setUniformInt(screen.uniforms.screenTexture, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, quad.count())
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        quad.disable()
        val buffer = pixels!!
        buffer.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        if (blending) GLES20.glEnable(GLES20.GL_BLEND)
        return Picture(width, height, buffer)
    }

    /** The read picture: RGBA bytes, rows from the bottom of the texture. */
    private class Picture(val width: Int, val height: Int, val bytes: ByteBuffer) {
        /** The average colour of a block around (x, y) (fractions; y from the top unless [flip]). */
        fun colour(x: Float, y: Float, flip: Boolean): Int {
            val r = maxOf(1, (width * BLOCK / 2).toInt())
            val cx = (x * width).toInt().coerceIn(r, width - r - 1)
            val row = if (flip) y else 1f - y
            val cy = (row * height).toInt().coerceIn(r, height - r - 1)
            var red = 0
            var green = 0
            var blue = 0
            var n = 0
            for (py in cy - r..cy + r) for (px in cx - r..cx + r) {
                val i = (py * width + px) * 4
                red += bytes.get(i).toInt() and 0xff
                green += bytes.get(i + 1).toInt() and 0xff
                blue += bytes.get(i + 2).toInt() and 0xff
                n++
            }
            return ((red / n) shl 16) or ((green / n) shl 8) or (blue / n)
        }
    }

    private class Reading(
        val race: String?, val flipped: Boolean?, val field: List<Int>, val black: Boolean,
        val orders: List<Int>?, val at: Long,
    )

    private fun understand(picture: Picture): Reading {
        val way = flipped
        var found: String? = null
        var foundWay = way
        if (way != null) {
            found = Wc3Screen.race { x, y -> picture.colour(x, y, way) }
        } else {
            for (flip in booleanArrayOf(false, true)) {
                found = Wc3Screen.race { x, y -> picture.colour(x, y, flip) }
                if (found != null) {
                    foundWay = flip
                    break
                }
            }
        }
        val field = Wc3Screen.FIELD.map { (x, y) -> picture.colour(x, y, foundWay ?: false) }
        val black = found == null && Wc3Screen.black(field) &&
            Wc3Screen.black(listOf(0.5f to 0.85f, 0.5f to 0.03f, 0.1f to 0.9f, 0.9f to 0.9f).map { (x, y) -> picture.colour(x, y, false) })
        val orders = if (found == null) null else Wc3Screen.ORDER_POINTS.map { (x, y) -> picture.colour(x, y, foundWay ?: false) }
        return Reading(found, foundWay, field, black, orders, SystemClock.uptimeMillis())
    }

    private fun apply(reading: Reading) {
        if (reading.flipped != null) flipped = reading.flipped
        race = reading.race
        orders = reading.orders
        ordersAt = reading.at
        stillSince = when {
            reading.race == null -> 0L
            Wc3Screen.still(lastField, reading.field) -> if (stillSince == 0L) SystemClock.uptimeMillis() else stillSince
            else -> 0L
        }
        lastField = reading.field
        val now = SystemClock.uptimeMillis()
        blackSince = if (reading.black) (if (blackSince == 0L) now else blackSince) else 0L
        if (!reading.black) shownSomething = true
        readings++
        if (reading.black) blackReadings++
        if (stillSince != 0L) stillReadings++
        reading.race?.let { racesSeen[it] = (racesSeen[it] ?: 0) + 1 }
        known = true
    }

    /** Whether the picture was read a few times and was black every time: the game never drew anything. */
    val blackOnly: Boolean get() = readings > 3 && !shownSomething

    /** What the picture showed this run, for the report. */
    fun summary(): List<String> = listOf(
        "readings: $readings (picture ${pictureSize.ifEmpty { "never read" }}, " +
            "upside down: ${flipped ?: "not known"})",
        "console seen: ${if (racesSeen.isEmpty()) "never" else racesSeen.entries.joinToString { "${it.key} ${it.value}" }}",
        "all black: $blackReadings, battlefield still: $stillReadings",
        "last battlefield colours: ${lastField?.joinToString(" ") { "%06x".format(it) } ?: "none"}",
    )

    companion object {
        private const val POLL_MS = 350L
        /** The block averaged around each point: 11 pixels of a 1440-pixel-wide picture. */
        private const val BLOCK = 11f / 1440f
    }
}
