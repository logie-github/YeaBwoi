package com.logie.wifecraft.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.simulation.SpeechLayout
import kotlin.math.PI
import kotlin.math.sin

/**
 * Over the 3D view, with the speech bubbles: a mood gem over each NPC's head (WoW's quest-marker diamond in their mood's
 * colour, [gems] per index, null none), bobbing gently and glowing, hidden while they speak ([lines]) and when their head is
 * off screen; and the thought bubbles [thoughts] chose (a pale cloud with the thought's game icon and words, above the gem).
 * Everything follows the heads every frame in the draw phase (no recomposition); [marked] (the partner, under its rose
 * marker) sits a little higher. Touches pass through.
 */
@Composable
fun BoxScope.LifeMarkers(
    spots: SpeechSpots,
    gems: List<MoodGem?>,
    lines: List<String?>,
    thoughts: List<ShownThought?>,
    marked: Int,
    icons: GameIcons?,
) {
    val reduced = reduceMotion()
    var tick by remember { mutableLongStateOf(0L) }
    val active = gems.any { it != null } || thoughts.any { it != null }
    LaunchedEffect(active) { if (active) while (true) withFrameNanos { tick = it } }
    val n = spots.count
    // each gem fades in and out (a line spoken, a mood off), its last colour kept while it goes
    val lastGem = remember(n) { arrayOfNulls<MoodGem>(n) }
    val fades: List<State<Float>> = List(n) { i ->
        key("gem", i) {
            val on = gems.getOrNull(i) != null && lines.getOrNull(i) == null
            animateFloatAsState(if (on) 1f else 0f, tween(MenuMotion.duration(if (on) MenuMotion.WINDOW_IN_MS else 320, reduced)), label = "gem")
        }
    }
    for (i in 0 until n) gems.getOrNull(i)?.let { lastGem[i] = it }
    Canvas(Modifier.matchParentSize()) {
        val now = tick
        val buf = spots.read()
        for (i in 0 until n) {
            val a = fades[i].value
            val gem = lastGem[i]
            if (a <= 0f || gem == null) continue
            val o = i * SpeechLayout.STRIDE
            if (buf[o + 2] <= 0f) continue
            val phase = (now / 1_000_000L % LifeHud.BOB_MS).toFloat() / LifeHud.BOB_MS + i * 0.37f
            val bob = if (reduced) 0f else sin(phase * 2f * PI.toFloat()) * LifeHud.BOB_DP.dp.toPx()
            val lift = GEM_LIFT.toPx() + (if (marked == i) MARK_ROOM.toPx() else 0f)
            drawMoodGem(gem, buf[o], buf[o + 1] - lift - bob, 1f, a)
        }
    }
    for (i in 0 until n) key("thought", i) {
        val shown = thoughts.getOrNull(i)
        val held = rememberHeld(shown)
        val fade by animateFloatAsState(if (shown != null) 1f else 0f, tween(MenuMotion.duration(if (shown != null) 160 else 420, reduced)), label = "thought")
        if (held != null && fade > 0f) {
            ThoughtCloud(held, icons, Modifier.align(Alignment.TopStart).graphicsLayer {
                tick   // read each frame: the cloud moves with the head
                val buf = spots.read()
                val o = i * SpeechLayout.STRIDE
                val maxX = (spots.width - size.width).coerceAtLeast(0f)
                val lift = THOUGHT_LIFT.toPx() + (if (marked == i) MARK_ROOM.toPx() else 0f)
                translationX = (buf[o] - size.width / 2f).coerceIn(0f, maxX)
                translationY = buf[o + 1] - size.height - lift
                alpha = if (buf[o + 2] > 0f) fade else 0f
                val s = 0.9f + 0.1f * fade
                scaleX = s; scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            })
        }
    }
}

/**
 * The mood gem, its bottom tip at ([cx], [bottom]), [scale] of its full size (14 x 24 dp), at [alpha]: a soft glow in its
 * colour, the diamond lit from the upper left (the left facets lighter), a dark rim, a glint.
 */
fun DrawScope.drawMoodGem(gem: MoodGem, cx: Float, bottom: Float, scale: Float = 1f, alpha: Float = 1f) {
    val base = Color(gem.argb)
    val w = 14.dp.toPx() * scale
    val h = 24.dp.toPx() * scale
    val midY = bottom - h * 0.42f
    val top = bottom - h
    drawCircle(Brush.radialGradient(listOf(base.copy(alpha = 0.45f * alpha), Color.Transparent), Offset(cx, midY), h * 0.85f), h * 0.85f, Offset(cx, midY))
    val light = lighten(base, 0.45f)
    val dark = darken(base, 0.45f)
    val left = Path().apply { moveTo(cx, top); lineTo(cx - w / 2, midY); lineTo(cx, bottom); close() }
    val right = Path().apply { moveTo(cx, top); lineTo(cx + w / 2, midY); lineTo(cx, bottom); close() }
    drawPath(left, Brush.verticalGradient(listOf(light, base), startY = top, endY = bottom), alpha = alpha)
    drawPath(right, Brush.verticalGradient(listOf(base, dark), startY = top, endY = bottom), alpha = alpha)
    // the crown's facet: a lighter band across the upper part
    val crown = Path().apply { moveTo(cx, top); lineTo(cx + w * 0.28f, midY - h * 0.08f); lineTo(cx, midY); lineTo(cx - w * 0.28f, midY - h * 0.08f); close() }
    drawPath(crown, Color.White.copy(alpha = 0.28f * alpha))
    val rim = Path().apply { moveTo(cx, top); lineTo(cx + w / 2, midY); lineTo(cx, bottom); lineTo(cx - w / 2, midY); close() }
    drawPath(rim, Color(0xE0201408), alpha = alpha, style = Stroke(1.2.dp.toPx() * scale))
    drawLine(Color.White.copy(alpha = 0.8f * alpha), Offset(cx - w * 0.16f, top + h * 0.2f), Offset(cx - w * 0.28f, midY - h * 0.04f), 1.4.dp.toPx() * scale)
}

private fun lighten(c: Color, t: Float) = Color(c.red + (1f - c.red) * t, c.green + (1f - c.green) * t, c.blue + (1f - c.blue) * t, c.alpha)
private fun darken(c: Color, t: Float) = Color(c.red * (1f - t), c.green * (1f - t), c.blue * (1f - t), c.alpha)

/** A thought: the game icon for its key, its words in dark ink, in a pale cloud with two small puffs trailing down to the head. */
@Composable
private fun ThoughtCloud(shown: ShownThought, icons: GameIcons?, modifier: Modifier) {
    Box(
        modifier.widthIn(max = 230.dp)
            .semantics { contentDescription = "Thinks: ${shown.thought.text}" }
            .drawBehind {
                val puffs = CLOUD_PUFFS.toPx()
                val bodyH = size.height - puffs
                val r = bodyH / 2f
                // the cloud's bumps along its top and bottom edges, then its body
                val bump = r * 0.55f
                val n = ((size.width - 2 * r) / (bump * 1.6f)).toInt().coerceAtLeast(1)
                for (k in 0..n) {
                    val x = r + (size.width - 2 * r) * k / n
                    drawCircle(CloudEdge, bump + 1.dp.toPx(), Offset(x, bump * 0.9f))
                    drawCircle(CloudEdge, bump * 0.85f + 1.dp.toPx(), Offset(x, bodyH - bump * 0.75f))
                }
                drawRoundRect(CloudEdge, Offset(-1.dp.toPx(), bump * 0.5f - 1.dp.toPx()), Size(size.width + 2.dp.toPx(), bodyH - bump * 0.9f + 2.dp.toPx()), CornerRadius(r))
                for (k in 0..n) {
                    val x = r + (size.width - 2 * r) * k / n
                    drawCircle(CloudFill, bump, Offset(x, bump * 0.9f))
                    drawCircle(CloudFill, bump * 0.85f, Offset(x, bodyH - bump * 0.75f))
                }
                drawRoundRect(CloudFill, Offset(0f, bump * 0.5f), Size(size.width, bodyH - bump * 0.9f), CornerRadius(r))
                // the trail of puffs to the head
                val mid = size.width / 2f
                drawCircle(CloudEdge, puffs * 0.42f + 1.dp.toPx(), Offset(mid - puffs * 0.2f, bodyH + puffs * 0.25f))
                drawCircle(CloudFill, puffs * 0.42f, Offset(mid - puffs * 0.2f, bodyH + puffs * 0.25f))
                drawCircle(CloudEdge, puffs * 0.24f + 1.dp.toPx(), Offset(mid - puffs * 0.55f, bodyH + puffs * 0.8f))
                drawCircle(CloudFill, puffs * 0.24f, Offset(mid - puffs * 0.55f, bodyH + puffs * 0.8f))
            }
            .padding(start = 10.dp, end = 14.dp, top = 9.dp, bottom = 9.dp + CLOUD_PUFFS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LifeIconView(shown.thought.iconKey, 28.dp, icons, Modifier.padding(end = 8.dp))
            Text(shown.thought.text, style = frizStyle(13.sp, CloudInk, shadow = false).copy(lineHeight = 16.sp), maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** How far above the head the gem's tip is, and the thought cloud's foot (over the gem). */
private val GEM_LIFT = 14.dp
private val THOUGHT_LIFT = 44.dp
/** The partner's rose marker's room: everything over the partner sits this much higher. */
private val MARK_ROOM = 18.dp
private val CLOUD_PUFFS = 14.dp
/** The thought cloud: near-white with a cool tint, a soft grey edge, dark ink (the speech bubbles are dark: never mistaken). */
private val CloudFill = Color(0xF2F3F1EC)
private val CloudEdge = Color(0x66404650)
private val CloudInk = Color(0xFF2A2219)
