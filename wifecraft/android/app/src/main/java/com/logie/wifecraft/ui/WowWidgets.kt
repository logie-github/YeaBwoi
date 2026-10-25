package com.logie.wifecraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** WoW's colours: the gold of names and buttons, white words, the parchment's ink. */
object WowColors {
    val gold = Color(0xFFFFD100)
    val white = Color.White
    val grey = Color(0xFF9D9D9D)
    val ink = Color(0xFF2A1505)
    val inkLight = Color(0xFF5A2A00)
    val shadow = Shadow(Color.Black, Offset(1.5f, 1.5f), 0f)
}

val LocalArt = staticCompositionLocalOf<WowArt?> { null }

/** Plays one of the interface's sounds by name (click, open, close...); silent until set. */
val LocalSounds = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun frizStyle(size: TextUnit, color: Color = WowColors.gold, shadow: Boolean = true): TextStyle {
    val art = LocalArt.current
    return TextStyle(
        fontFamily = art?.friz ?: FontFamily.Serif, fontSize = size, color = color,
        shadow = if (shadow) WowColors.shadow else null,
    )
}

@Composable
fun morpheusStyle(size: TextUnit, color: Color = WowColors.gold): TextStyle {
    val art = LocalArt.current
    return TextStyle(fontFamily = art?.morpheus ?: FontFamily.Serif, fontSize = size, color = color, shadow = WowColors.shadow)
}

fun DrawScope.piece(p: Piece?, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f) {
    p ?: return
    drawImage(
        p.image, srcOffset = p.offset, srcSize = p.size,
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
        alpha = alpha, filterQuality = FilterQuality.Medium,
    )
}

/** WoW's red panel button: its rounded ends kept, its middle stretched. */
@Composable
fun WowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 200.dp,
    height: Dp = 44.dp,
) {
    val click = LocalSounds.current
    val art = LocalArt.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pieces = when {
        !enabled -> art?.redDisabled
        pressed -> art?.redPressed
        else -> art?.red
    }
    Box(
        modifier
            .width(width)
            .height(height)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = { click("click"); onClick() })
            .drawBehind {
                if (pieces?.left == null || pieces.center == null || pieces.right == null) {
                    drawRect(if (enabled) Color(0xFF7A0E0A) else Color(0xFF3A3A3A))
                    drawRect(Color(0xFF2A2A2A), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    return@drawBehind
                }
                val k = size.height / pieces.left.size.height
                val lw = pieces.left.size.width * k
                val rw = (pieces.right.size.width * k).coerceAtMost(size.width - lw)
                piece(pieces.center, lw - 1, 0f, size.width - lw - rw + 2, size.height)
                piece(pieces.left, 0f, 0f, lw, size.height)
                // The right end is wide: draw only as much of it as fits, from its far edge.
                val rp = pieces.right
                val srcW = (rw / k).roundToInt().coerceIn(1, rp.size.width)
                drawImage(rp.image, IntOffset(rp.offset.x + rp.size.width - srcW, rp.offset.y), IntSize(srcW, rp.size.height),
                    IntOffset((size.width - rw).roundToInt(), 0), IntSize(rw.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = frizStyle(15.sp, if (enabled) WowColors.gold else WowColors.grey), textAlign = TextAlign.Center)
    }
}

/** WoW's metal window frame over dark stone (or [inside] when given, like the gossip parchment). */
@Composable
fun MetalPanel(
    modifier: Modifier = Modifier,
    parchment: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val art = LocalArt.current
    Box(
        modifier.drawBehind {
            val c = 22.dp.toPx()
            val inset = 6.dp.toPx()
            val inside = if (parchment) art?.parchment else null
            if (inside != null) {
                piece(inside, inset, inset, size.width - 2 * inset, size.height - 2 * inset)
            } else if (art?.rock != null) {
                piece(art.rock, inset, inset, size.width - 2 * inset, size.height - 2 * inset)
                drawRect(Color(0xB0000000), Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset))
            } else {
                drawRect(if (parchment) Color(0xFFE8D5A8) else Color(0xEE15110C))
            }
            if (art?.cornerTL == null) {
                drawRect(Color(0xFF8A7A5A), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
                return@drawBehind
            }
            piece(art.edgeTop, c, 0f, size.width - 2 * c, c)
            piece(art.edgeBottom, c, size.height - c, size.width - 2 * c, c)
            piece(art.edgeLeft, 0f, c, c, size.height - 2 * c)
            piece(art.edgeRight, size.width - c, c, c, size.height - 2 * c)
            piece(art.cornerTL, 0f, 0f, c, c)
            piece(art.cornerTR, size.width - c, 0f, c, c)
            piece(art.cornerBL, 0f, size.height - c, c, c)
            piece(art.cornerBR, size.width - c, size.height - c, c, c)
        }.padding(horizontal = 22.dp, vertical = 18.dp),
        content = content,
    )
}

/** Legion's talking-head box: the name in gold above the words. */
@Composable
fun TalkingHead(name: String, text: String, revealed: Int, parchment: Boolean, modifier: Modifier = Modifier, more: Boolean = false) {
    val art = LocalArt.current
    val bg = if (parchment) art?.talkParchment else art?.talkDark
    val ink = if (parchment) WowColors.ink else WowColors.white
    Box(
        modifier.drawBehind {
            // A dark ground under Legion's see-through box, so the words read over any scene.
            if (!parchment) drawRoundRect(Color(0x99000000), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.12f))
            if (bg != null) piece(bg, 0f, 0f, size.width, size.height)
            else drawRect(if (parchment) Color(0xF0E8D5A8) else Color(0xE0101010))
        }.padding(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 18.dp),
    ) {
        androidx.compose.foundation.layout.Column {
            if (name.isNotEmpty()) Text(name, style = frizStyle(19.sp, if (parchment) WowColors.inkLight else WowColors.gold, !parchment))
            // The whole line is laid out from the start, the part still to come invisible, so words
            // don't jump to the next row as they're typed.
            val shown = revealed.coerceIn(0, text.length)
            val words = androidx.compose.ui.text.buildAnnotatedString {
                append(text.substring(0, shown))
                withStyle(androidx.compose.ui.text.SpanStyle(color = Color.Transparent, shadow = Shadow(Color.Transparent))) { append(text.substring(shown)) }
            }
            Text(
                words,
                style = frizStyle(19.sp, ink, !parchment).copy(lineHeight = 26.sp, fontStyle = if (name.isEmpty()) androidx.compose.ui.text.font.FontStyle.Italic else null),
                modifier = Modifier.padding(top = 4.dp, end = 12.dp),
            )
        }
        if (more) {
            Canvas(Modifier.align(Alignment.BottomEnd).padding(4.dp).width(14.dp).height(10.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close()
                }
                drawPath(path, WowColors.gold)
            }
        }
    }
}

/** A reputation bar: its border, its fill, and the standing written on it. */
@Composable
fun RepBar(fraction: Float, standing: String, modifier: Modifier = Modifier) {
    val art = LocalArt.current
    val fill = when (standing) {
        "Neutral" -> art?.barYellow
        "Exalted", "Revered" -> art?.barBlue
        else -> art?.barGreen
    }
    Box(
        modifier.drawBehind {
            val h = size.height
            val inner = h * 7f / 31f
            val ih = h * 18f / 31f
            if (art?.barBorderL == null) {
                drawRect(Color.Black)
                drawRect(Color(0xFF1FA01F), size = Size(size.width * fraction, h))
                return@drawBehind
            }
            val cap = h * 35f / 31f
            piece(art.barBg, cap * 0.35f, inner, size.width - cap * 0.7f, ih)
            piece(fill, cap * 0.35f, inner, (size.width - cap * 0.7f) * fraction.coerceIn(0f, 1f), ih * 17f / 18f)
            piece(art.barBorderC, cap, 0f, size.width - 2 * cap, h)
            piece(art.barBorderL, 0f, 0f, cap, h)
            piece(art.barBorderR, size.width - cap, 0f, cap, h)
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(standing, style = frizStyle(12.sp, WowColors.white))
    }
}

/** The achievement toast, as an ending's announcement. */
@Composable
fun AchievementToast(heading: String, name: String, detail: String, count: String, modifier: Modifier = Modifier) {
    val art = LocalArt.current
    Box(
        modifier.drawBehind {
            if (art?.achievement != null) piece(art.achievement, 0f, 0f, size.width, size.height)
            else drawRect(Color(0xE0201408))
            val s = size.height * 0.62f
            val inset = s * 0.14f
            piece(art?.heart, size.width * 0.045f + inset, (size.height - s) / 2 + inset, s - 2 * inset, s - 2 * inset)
            piece(art?.achievementIcon, size.width * 0.045f, (size.height - s) / 2, s, s)
        },
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.align(Alignment.Center).padding(start = 70.dp, end = 50.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(heading, style = frizStyle(12.sp, WowColors.white))
            Text(name, style = frizStyle(20.sp, WowColors.gold))
            Text(detail, style = frizStyle(11.sp, WowColors.white))
        }
        Text(count, style = frizStyle(14.sp, WowColors.white), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 26.dp))
    }
}

/** The loading screen's bar. */
@Composable
fun LoadingBar(progress: Float, modifier: Modifier = Modifier) {
    val art = LocalArt.current
    Canvas(modifier) {
        val border = art?.loadingBorder
        val fill = art?.loadingFill
        val padX = size.width * 0.05f
        val padY = size.height * 0.3f
        if (fill != null) piece(fill, padX, padY, (size.width - 2 * padX) * progress.coerceIn(0f, 1f), size.height - 2 * padY)
        else drawRect(Color(0xFFB08000), Offset(padX, padY), Size((size.width - 2 * padX) * progress.coerceIn(0f, 1f), size.height - 2 * padY))
        if (border != null) piece(border, 0f, 0f, size.width, size.height)
        else drawRect(Color(0xFF6A5A3A), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
    }
}

/** A gossip option: the speech-bubble icon and the words, in the parchment's ink. */
@Composable
fun GossipOption(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val art = LocalArt.current
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(22.dp).height(22.dp)) {
            if (art?.gossipIcon != null) piece(art.gossipIcon, 0f, 0f, size.width, size.height)
            else drawCircle(Color(0xFFE0C060))
        }
        Text(text, style = frizStyle(16.sp, Color(0xFF1A0F05), false), modifier = Modifier.padding(start = 10.dp))
    }
}

/** Black, to fade through. */
@Composable
fun Black(alpha: Float) {
    if (alpha > 0.001f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha.coerceIn(0f, 1f))))
}
