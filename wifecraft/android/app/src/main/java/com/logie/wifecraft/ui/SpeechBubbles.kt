package com.logie.wifecraft.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        SpeechLayout.choose(count, speaking, buf)
        front = buf
    }

    /** The screen: the last frame's layout (read it, don't keep it). */
    fun read(): FloatArray = front
}

/**
 * Over the 3D view: a speech bubble above each speaker the layout shows (the game's chat bubble: a dark rounded box with a
 * light rim and a tail, the words in Friz in the front menus' pale ink, the speaker's name small and gold above them), faded
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
            Bubble(names.getOrElse(i) { "" }, held, Modifier.align(Alignment.TopStart).graphicsLayer {
                tick   // read each frame: the layer moves with the head
                val buf = spots.read()
                val o = i * SpeechLayout.STRIDE
                val shown = buf[o + 3] == 1f
                val lift = if (marked == i) MARK_LIFT.toPx() else 0f
                val maxX = (spots.width - size.width).coerceAtLeast(0f)
                translationX = (buf[o] - size.width / 2f).coerceIn(0f, maxX)
                translationY = buf[o + 1] - size.height - lift
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

/** One bubble: [name] small and gold over [text], on the dark box with its rim and a tail down to the head. */
@Composable
private fun Bubble(name: String, text: String, modifier: Modifier) {
    Box(modifier.widthIn(max = 240.dp).drawBehind {
        val tail = TAIL.toPx()
        val radius = 12.dp.toPx()
        val rim = 1.2.dp.toPx()
        val bodyH = this.size.height - tail
        val bodyW = this.size.width
        drawRoundRect(BubbleFill, Offset.Zero, Size(bodyW, bodyH), CornerRadius(radius))
        // the light from above, as the front menus' panels have
        drawRoundRect(Brush.verticalGradient(listOf(Color(0x1AFFFFFF), Color.Transparent), endY = bodyH * 0.6f), Offset.Zero, Size(bodyW, bodyH), CornerRadius(radius))
        drawRoundRect(BubbleRim, Offset(rim / 2, rim / 2), Size(bodyW - rim, bodyH - rim), CornerRadius(radius), style = Stroke(rim))
        val mid = bodyW / 2f
        val spike = Path().apply {
            moveTo(mid - tail * 0.8f, bodyH - rim)
            lineTo(mid + tail * 0.5f, bodyH - rim)
            lineTo(mid - tail * 0.2f, bodyH + tail - rim)
            close()
        }
        drawPath(spike, BubbleFill)
        drawLine(BubbleRim, Offset(mid - tail * 0.8f, bodyH - rim / 2), Offset(mid - tail * 0.2f, bodyH + tail - rim), rim)
        drawLine(BubbleRim, Offset(mid + tail * 0.5f, bodyH - rim / 2), Offset(mid - tail * 0.2f, bodyH + tail - rim), rim)
    }.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 7.dp + TAIL)) {
        Column {
            if (name.isNotEmpty()) Text(name, style = frizStyle(WifeTheme.caption, WifeTheme.gold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text, style = frizStyle(13.sp, WifeTheme.text).copy(lineHeight = 17.sp), textAlign = TextAlign.Start, maxLines = 4,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

private val TAIL = 9.dp
/** How much higher a bubble sits over the partner, above its marker. */
private val MARK_LIFT = 18.dp
/** The game's chat bubble: near-black, a little see-through, its rim the menus' gold trim softened. */
private val BubbleFill = Color(0xE00B0E13)
private val BubbleRim = WifeTheme.trim.copy(alpha = 0.75f)
private val PartnerMark = Color(0xFFE88AA8)
