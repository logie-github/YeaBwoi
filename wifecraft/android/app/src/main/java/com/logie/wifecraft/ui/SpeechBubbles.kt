package com.logie.wifecraft.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.simulation.SpeechBubbleModel
import com.logie.wifecraft.simulation.SpeechLayout

/**
 * The speech bubbles' meeting point between the GL thread and the screen. The GL thread, each frame, writes every person's
 * head (stage coordinates: [headAt]) and who is speaking ([speaking]), then [layout]s them with the frame's camera into a
 * back buffer and [publish]es it (screen x, y, depth, shown: [SpeechLayout.STRIDE] a person); the screen reads the front
 * one ([read]) in its draw phase (everyone is projected, speaking or not: the partner's marker uses it too). The screen
 * writes the view's size ([width], [height], pixels). Nothing allocated per frame.
 */
class SpeechSpots(val count: Int) {
    private val a = FloatArray(count * SpeechLayout.STRIDE)
    private val b = FloatArray(count * SpeechLayout.STRIDE)
    @Volatile private var front = a
    private val hx = FloatArray(count)
    private val hy = FloatArray(count)
    private val hz = FloatArray(count)
    /** GL thread: who has a line up now. */
    val speaking = BooleanArray(count)
    /** The screen: who has a bubble the screen itself raised (Chat mode's reply), besides [speaking]. */
    val extra = BooleanArray(count)
    private val mix = BooleanArray(count)
    @Volatile var width = 0f
    @Volatile var height = 0f

    /** GL thread: person [index]'s head is at x, y, z (stage) this frame. */
    fun headAt(index: Int, x: Float, y: Float, z: Float) {
        if (index !in 0 until count) return
        hx[index] = x; hy[index] = y; hz[index] = z
    }

    /** GL thread: everyone projected with this frame's camera and the bubbles chosen, then shown to the screen. */
    fun layout(ex: Float, ey: Float, ez: Float, ax: Float, ay: Float, az: Float, fov: Float) {
        val buf = if (front === a) b else a
        val w = width; val h = height
        for (i in 0 until count) SpeechLayout.project(ex, ey, ez, ax, ay, az, fov, w, h, hx[i], hy[i], hz[i], buf, i * SpeechLayout.STRIDE)
        for (i in 0 until count) mix[i] = speaking[i] || extra[i]
        SpeechLayout.choose(count, mix, buf)
        front = buf
    }

    /** The screen: the last frame's layout (read it, don't keep it). */
    fun read(): FloatArray = front
}

/**
 * Over the 3D view: a speech bubble above each speaker the layout shows (the game's chat bubble: a cream-white rounded box with a thin dark
 * border and a tail aimed at the head, the words black in Friz, the speaker's name small and brown above them; [SpeechBubbleModel]), faded
 * in and out; and a small rose marker over the controlled NPC's partner ([marked]: their index, -1 none; rose as the cast
 * frames mark the partner). [lines] holds each person's line now (null: none). Moved every frame in the
 * draw phase only (no recomposition).
 * Touches pass through to the view.
 */
@Composable
fun BoxScope.SpeechBubbles(spots: SpeechSpots, names: List<String>, lines: List<String?>, marked: Int) {
    var tick by remember { mutableLongStateOf(0L) }
    val active = lines.any { it != null } || marked >= 0
    LaunchedEffect(active) { if (active) while (true) withFrameNanos { tick = it } }
    for (i in 0 until spots.count) key(i) {
        val text = lines.getOrNull(i)
        // the last line, kept while the bubble fades out
        val held = rememberHeld(text) ?: ""
        val fade by animateFloatAsState(if (text != null) 1f else 0f, tween(if (text != null) 160 else 420), label = "bubble")
        if (fade > 0f) {
            Bubble(names.getOrElse(i) { "" }, held, { tick; spots.read()[i * SpeechLayout.STRIDE] }, { spots.width }, Modifier.align(Alignment.TopStart).graphicsLayer {
                tick   // read each frame: the layer moves with the head
                val buf = spots.read()
                val o = i * SpeechLayout.STRIDE
                val shown = SpeechBubbleModel.visible(buf[o + 2], buf[o + 3] == 1f)
                val lift = if (marked == i) MARK_LIFT.toPx() else 0f
                val pl = SpeechBubbleModel.place(buf[o], size.width, spots.width)
                translationX = pl.left
                translationY = buf[o + 1] - size.height - lift
                // gently larger near, smaller far, about the tail's tip
                val k = SpeechBubbleModel.scale(buf[o + 2])
                scaleX = k; scaleY = k
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin((pl.tailX / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f), 1f)
                alpha = if (shown) fade else 0f
            })
        }
    }
    val mark = marked
    if (mark in 0 until spots.count) key("mark") {
        Text("▼", Modifier.align(Alignment.TopStart).graphicsLayer {
            tick
            val buf = spots.read()
            val o = mark * SpeechLayout.STRIDE
            translationX = buf[o] - size.width / 2f
            translationY = buf[o + 1] - size.height
            alpha = if (buf[o + 2] > 0f) 1f else 0f
        }, style = frizStyle(18.sp, PartnerMark))
    }
}

/**
 * One bubble in the game's own chat-bubble look: a cream-white rounded box with a thin dark border, the words black in Friz, a small
 * pointed tail aimed at the speaker ([SpeechBubbleModel.place]), the speaker's name small and brown above. Its shape (width, lines)
 * is [SpeechBubbleModel.shape]; [anchorX] is the head's screen x, read each frame.
 */
@Composable
private fun Bubble(name: String, text: String, anchorX: () -> Float, screenW: () -> Float, modifier: Modifier) {
    val shape = SpeechBubbleModel.shape(text)
    val ink = androidx.compose.ui.graphics.Color(SpeechBubbleModel.colour(SpeechBubbleModel.kind(text)))
    Box(modifier.width(shape.widthDp.dp).drawBehind {
        val tail = TAIL.toPx()
        val radius = 9.dp.toPx()
        val rim = 1.2.dp.toPx()
        val bodyH = this.size.height - tail
        val bodyW = this.size.width
        drawRoundRect(BubbleFill, Offset.Zero, Size(bodyW, bodyH), CornerRadius(radius))
        drawRoundRect(BubbleRim, Offset(rim / 2, rim / 2), Size(bodyW - rim, bodyH - rim), CornerRadius(radius), style = Stroke(rim))
        // the tail points at the head, wherever the box had to be moved to stay on the screen
        val tx = SpeechBubbleModel.place(anchorX(), bodyW, screenW(), 6f, radius + tail).tailX
        val spike = Path().apply {
            moveTo(tx - tail * 0.7f, bodyH - rim)
            lineTo(tx + tail * 0.7f, bodyH - rim)
            lineTo(tx, bodyH + tail)
            close()
        }
        drawPath(spike, BubbleFill)
        drawLine(BubbleRim, Offset(tx - tail * 0.7f, bodyH - rim / 2), Offset(tx, bodyH + tail), rim)
        drawLine(BubbleRim, Offset(tx + tail * 0.7f, bodyH - rim / 2), Offset(tx, bodyH + tail), rim)
    }.padding(start = SpeechBubbleModel.PAD_DP.dp, end = SpeechBubbleModel.PAD_DP.dp, top = 6.dp, bottom = 6.dp + TAIL)) {
        Column {
            if (name.isNotEmpty()) Text(name, style = frizStyle(WifeTheme.caption, BubbleName, shadow = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, style = frizStyle(13.sp, ink, shadow = false).copy(lineHeight = 16.sp), textAlign = TextAlign.Start, maxLines = shape.maxLines,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

private val TAIL = 9.dp
/** How much higher a bubble sits over the partner, above its marker. */
private val MARK_LIFT = 18.dp
/** The game's chat bubble: cream white, a thin dark border, the speaker's name in a dark brown. */
private val BubbleFill = Color(0xF5FFF6DC)
private val BubbleRim = Color(0xFF2A2218)
private val BubbleName = Color(0xFF6B5A38)
private val PartnerMark = Color(0xFFE88AA8)
