// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A frame like the games' own windows: a hairline, and at each corner a short gold bracket with a
 * small diamond, like the rivets on the logos' stone.
 */
fun DrawScope.drawFrame(
    corner: Color,
    line: Color?,
    alpha: Float = 1f,
    arm: Float = 10.dp.toPx(),
    stroke: Float = 1.3.dp.toPx(),
) {
    val w = size.width
    val h = size.height
    if (line != null) drawRect(line, style = Stroke(1.dp.toPx()), alpha = 0.75f * alpha)
    val corners = listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(w, 0f) to Offset(-1f, 1f), Offset(0f, h) to Offset(1f, -1f), Offset(w, h) to Offset(-1f, -1f))
    val d = 3.dp.toPx()
    for ((point, dir) in corners) {
        drawLine(corner, point, Offset(point.x + dir.x * arm, point.y), stroke, alpha = alpha)
        drawLine(corner, point, Offset(point.x, point.y + dir.y * arm), stroke, alpha = alpha)
        val c = Offset(point.x + dir.x * d * 1.6f, point.y + dir.y * d * 1.6f)
        val diamond = Path().apply {
            moveTo(c.x, c.y - d)
            lineTo(c.x + d, c.y)
            lineTo(c.x, c.y + d)
            lineTo(c.x - d, c.y)
            close()
        }
        drawPath(diamond, corner, alpha = alpha)
    }
}

/** A panel in the game's colours, see-through over the picture, lit while pressed. */
@Composable
fun RtsWindow(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    padding: PaddingValues = PaddingValues(16.dp),
    opaque: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val look = LocalLook.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lit by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 900f), label = "lit")
    var clickable: Modifier = Modifier
    if (onClick != null) {
        clickable = Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
    }
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .drawBehind {
                val see = if (opaque) 1f else 0.84f
                drawRect(Brush.verticalGradient(listOf(look.deep.copy(alpha = see), look.night.copy(alpha = maxOf(see, 0.92f)))))
                if (lit > 0f) drawRect(Brush.radialGradient(listOf(look.accent.copy(alpha = 0.22f * lit), Color.Transparent)))
                drawFrame(look.gold, look.steel, alpha = 0.7f + 0.3f * lit)
            }
            .then(clickable)
            .padding(padding),
        content = content,
    )
}

/** A button: the [primary] one gold-lettered on the game's colour, the others plain. */
@Composable
fun RtsButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val look = LocalLook.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 900f), label = "press")
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .heightIn(min = 44.dp)
            .drawBehind {
                if (primary) {
                    drawRect(Brush.verticalGradient(listOf(look.frost, look.deep)))
                    drawRect(look.accent.copy(alpha = 0.18f * press))
                    drawFrame(look.gold, look.steel, alpha = 0.85f + 0.15f * press, arm = 7.dp.toPx())
                } else {
                    drawRect(look.accent.copy(alpha = 0.10f * press))
                    drawFrame(look.mist, look.steel, alpha = 0.7f + 0.3f * press, arm = 7.dp.toPx())
                }
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = RtsDisplayStyle.copy(
                fontSize = 14.sp,
                color = if (primary) look.gold else look.text,
                shadow = if (primary) RtsGoldShadow else null,
            ),
            maxLines = 1,
        )
    }
}

/** A setting that's on or off, with a line of explanation under its name. */
@Composable
fun RtsSwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val look = LocalLook.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = look.glow,
                checkedTrackColor = look.frost,
                checkedBorderColor = look.accent,
                uncheckedThumbColor = look.mist,
                uncheckedTrackColor = Color(0x66000000),
                uncheckedBorderColor = look.steel,
            ),
        )
    }
}

/** One choice out of several: the chosen one in a gold frame. */
@Composable
fun RtsChoiceRow(title: String, summary: String?, selected: Boolean, onClick: () -> Unit) {
    val look = LocalLook.current
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (selected) {
                    drawRect(look.accent.copy(alpha = 0.08f))
                    drawFrame(look.gold, null, arm = 6.dp.toPx())
                }
            }
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) look.glow else look.text)
        if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall)
    }
}

/** A title in Cinzel with a hairline running off to its right. */
@Composable
fun RtsTitle(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.headlineSmall) {
    val look = LocalLook.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = style, maxLines = 1)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(look.steel, Color.Transparent)))
        )
    }
}

/** A dialog on a panel: title, scrolling content, buttons at the bottom. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RtsDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit = { RtsButton("OK", onClick = onDismiss) },
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        RtsWindow(
            Modifier
                .padding(20.dp)
                .widthIn(max = 600.dp)
                .fillMaxWidth(),
            padding = PaddingValues(20.dp),
            opaque = true,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                RtsTitle(title)
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                FlowRow(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    buttons()
                }
            }
        }
    }
}

/** How far along something is: a line filling up, its leading edge glowing. */
@Composable
fun RtsProgressLine(progress: Float, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    Canvas(
        modifier
            .fillMaxWidth()
            .height(10.dp)
    ) {
        val y = size.height / 2
        drawLine(look.steel, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        val end = size.width * progress.coerceIn(0f, 1f)
        drawLine(look.accent, Offset(0f, y), Offset(end, y), 2.dp.toPx())
        drawCircle(
            Brush.radialGradient(listOf(look.glow, Color.Transparent), center = Offset(end, y), radius = 8.dp.toPx()),
            radius = 8.dp.toPx(),
            center = Offset(end, y),
        )
    }
}

/** Waiting: a ring of runes turning, a bright arc running round it. */
@Composable
fun RtsSpinner(size: Dp, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val motion = rememberInfiniteTransition(label = "spinner")
    val turn by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "turn")
    val slow by motion.animateFloat(360f, 0f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "slow")
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2 * 0.86f
        drawCircle(look.steel, r, style = Stroke(1.2.dp.toPx()))
        rotate(slow) {
            for (i in 0 until 12) {
                val a = i * PI / 6
                val inner = r * 0.72f
                val outer = r * 0.84f
                drawLine(
                    look.gold.copy(alpha = 0.7f),
                    Offset(center.x + (inner * cos(a)).toFloat(), center.y + (inner * sin(a)).toFloat()),
                    Offset(center.x + (outer * cos(a)).toFloat(), center.y + (outer * sin(a)).toFloat()),
                    1.2.dp.toPx(),
                )
            }
        }
        drawArc(
            Brush.sweepGradient(listOf(Color.Transparent, look.accent, look.glow)),
            startAngle = turn,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/**
 * The game's weather over its picture: embers rising (Warcraft I, Reign of Chaos), rain slanting
 * down (Warcraft II's storm), snow falling (The Frozen Throne).
 */
@Composable
fun RtsWeather(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val look = LocalLook.current
    val motion = rememberInfiniteTransition(label = "weather")
    val time by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(20_000, easing = LinearEasing)), label = "time")
    val count = when (look.weather) {
        Weather.RAIN -> 110
        Weather.SNOW -> 70
        Weather.EMBERS -> 45
    }
    val seeds = remember(count) {
        val random = java.util.Random(7)
        List(count) { floatArrayOf(random.nextFloat(), random.nextFloat(), 0.4f + random.nextFloat() * 0.6f, random.nextFloat()) }
    }
    Canvas(modifier.fillMaxSize()) {
        for (seed in seeds) {
            val (x0, y0, depth, phase) = seed.toList()
            when (look.weather) {
                Weather.SNOW -> {
                    val fall = (y0 + time * (1f + 2f * depth)) % 1f
                    val sway = sin((time * 6f + phase) * 2 * PI).toFloat() * 0.012f
                    val x = ((x0 + sway - time * 0.15f * depth) % 1f + 1f) % 1f
                    drawCircle(
                        look.glow.copy(alpha = alpha * (0.25f + 0.5f * depth)),
                        radius = (0.8f + 1.8f * depth).dp.toPx(),
                        center = Offset(x * size.width, fall * size.height),
                    )
                }
                Weather.RAIN -> {
                    val fall = (y0 + time * (12f + 10f * depth)) % 1f
                    val x = ((x0 - fall * 0.12f) % 1f + 1f) % 1f
                    val start = Offset(x * size.width, fall * size.height)
                    val length = (10f + 16f * depth).dp.toPx()
                    drawLine(
                        look.glow.copy(alpha = alpha * (0.12f + 0.3f * depth)),
                        start,
                        Offset(start.x - length * 0.25f, start.y + length),
                        (0.6f + 0.8f * depth).dp.toPx(),
                    )
                }
                Weather.EMBERS -> {
                    val rise = 1f - (y0 + time * (1.2f + 1.5f * depth)) % 1f
                    val sway = sin((time * 5f + phase) * 2 * PI).toFloat() * 0.02f
                    val x = ((x0 + sway + time * 0.1f * depth) % 1f + 1f) % 1f
                    // Brightest low down, fading as they rise.
                    val life = rise.coerceIn(0f, 1f)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(look.accent.copy(alpha = alpha * life * (0.5f + 0.5f * depth)), Color.Transparent),
                            center = Offset(x * size.width, rise * size.height),
                            radius = (2f + 3f * depth).dp.toPx(),
                        ),
                        radius = (2f + 3f * depth).dp.toPx(),
                        center = Offset(x * size.width, rise * size.height),
                    )
                }
            }
        }
    }
}

/** A full-screen dark backdrop under everything, the game's colour glowing faintly from above. */
@Composable
fun RtsBackdrop() {
    val look = LocalLook.current
    Canvas(Modifier.fillMaxSize()) {
        drawRect(look.abyss)
        drawRect(
            Brush.radialGradient(
                listOf(look.frost.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(size.width * 0.5f, 0f),
                radius = size.maxDimension * 0.9f,
            )
        )
    }
}
