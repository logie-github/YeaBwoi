package com.logie.wifecraft.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.simulation.ClockSpeed
import com.logie.wifecraft.simulation.LifeEvent
import com.logie.wifecraft.simulation.LifePersonView
import com.logie.wifecraft.simulation.LifeResult
import com.logie.wifecraft.simulation.Need
import kotlin.math.roundToInt

// The life simulation's panels in the front menus' look (WifeTheme): the icons (the game's, a drawn glyph until then), the
// party frame's ring of needs, the Life window (Needs, Relationships, Traits), the world clock's face and speed buttons, the
// feed, the radial menu and the answer's toast. The decisions are LifeHud's (pure, JVM-tested).

/**
 * A life icon: the game's icon for [key] (LifeIcons: fetched and decoded off the main thread by GameIcons), its drawn glyph
 * in gold until then or when it can't be had (never blank); greyed when not [enabled].
 */
@Composable
fun LifeIconView(key: String?, side: Dp, icons: GameIcons?, modifier: Modifier = Modifier, enabled: Boolean = true, round: Boolean = false) {
    val icon = LifeIcons.of(key)
    val image = gameIcon(icons, icon.fileId)
    val shape = if (round) CircleShape else RoundedCornerShape(side * 0.16f)
    Box(
        modifier.size(side).clip(shape).background(Brush.verticalGradient(listOf(WifeTheme.slateRaised, WifeTheme.slateDeep)))
            .border(1.dp, WifeTheme.trim.copy(alpha = if (enabled) 0.8f else 0.35f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            // the game's icons carry a dark border of their own: a little of it is cropped away
            Image(image, null, Modifier.matchParentSize().graphicsLayer { scaleX = 1.12f; scaleY = 1.12f }, contentScale = ContentScale.Crop,
                alpha = if (enabled) 1f else 0.45f, colorFilter = if (enabled) null else GreyIcon)
        } else {
            Canvas(Modifier.matchParentSize()) {
                drawLifeGlyph(icon.glyph, center.x, center.y, this.size.minDimension * 0.62f, if (enabled) WifeTheme.gold else WowColors.grey)
            }
        }
    }
}

private val GreyIcon = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** [g] drawn [d] px across centred on [cx], [cy] in [color]: plain shapes, read at a glance. */
fun DrawScope.drawLifeGlyph(g: LifeGlyph, cx: Float, cy: Float, d: Float, color: Color) {
    val u = d
    val sw = u * 0.09f
    val stroke = Stroke(sw, cap = StrokeCap.Round)
    fun p(x: Float, y: Float) = Offset(cx + x * u, cy + y * u)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(color, p(x0, y0), p(x1, y1), sw, StrokeCap.Round)
    fun path(fill: Boolean, block: Path.() -> Unit) = drawPath(Path().apply(block), color, style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
    fun Path.m(x: Float, y: Float) = moveTo(cx + x * u, cy + y * u)
    fun Path.l(x: Float, y: Float) = lineTo(cx + x * u, cy + y * u)
    fun heart(s: Float, fill: Boolean) = path(fill) {
        m(0f, 0.32f * s); cubicTo(cx - 0.55f * s * u, cy - 0.05f * s * u, cx - 0.3f * s * u, cy - 0.45f * s * u, cx, cy - 0.16f * s * u)
        cubicTo(cx + 0.3f * s * u, cy - 0.45f * s * u, cx + 0.55f * s * u, cy - 0.05f * s * u, cx, cy + 0.32f * s * u); close()
    }
    when (g) {
        LifeGlyph.MUG -> {
            path(false) { m(-0.26f, -0.3f); l(0.14f, -0.3f); l(0.14f, 0.32f); l(-0.26f, 0.32f); close() }
            drawArc(color, -90f, 180f, false, p(0.06f, -0.16f), Size(0.32f * u, 0.32f * u), style = stroke)
            line(-0.18f, -0.18f, 0.06f, -0.18f)
        }
        LifeGlyph.BREAD -> {
            path(false) { m(-0.38f, 0.22f); l(-0.38f, -0.02f); cubicTo(cx - 0.38f * u, cy - 0.34f * u, cx + 0.38f * u, cy - 0.34f * u, cx + 0.38f * u, cy - 0.02f * u); l(0.38f, 0.22f); close() }
            line(-0.14f, -0.12f, -0.06f, 0.04f); line(0.06f, -0.14f, 0.14f, 0.02f)
        }
        LifeGlyph.BED -> {
            // a crescent of sleep and its z's
            drawArc(color, 60f, 240f, false, p(-0.38f, -0.22f), Size(0.5f * u, 0.5f * u), style = stroke)
            path(false) { m(0.08f, -0.34f); l(0.32f, -0.34f); l(0.08f, -0.1f); l(0.32f, -0.1f) }
            path(false) { m(0.12f, 0.06f); l(0.28f, 0.06f); l(0.12f, 0.22f); l(0.28f, 0.22f) }
        }
        LifeGlyph.BOOK -> {
            path(false) { m(0f, -0.24f); l(-0.38f, -0.32f); l(-0.38f, 0.26f); l(0f, 0.34f); l(0.38f, 0.26f); l(0.38f, -0.32f); close() }
            line(0f, -0.24f, 0f, 0.34f)
        }
        LifeGlyph.NOTE -> {
            drawCircle(color, 0.11f * u, p(-0.16f, 0.24f)); drawCircle(color, 0.11f * u, p(0.22f, 0.16f))
            line(-0.06f, 0.24f, -0.06f, -0.3f); line(0.32f, 0.16f, 0.32f, -0.38f); line(-0.06f, -0.3f, 0.32f, -0.38f)
        }
        LifeGlyph.HEART -> heart(1f, true)
        LifeGlyph.SUN -> {
            drawCircle(color, 0.17f * u, p(0f, 0f))
            for (k in 0 until 8) {
                val a = k * Math.PI / 4
                val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
                line(c * 0.27f, s * 0.27f, c * 0.4f, s * 0.4f)
            }
        }
        LifeGlyph.MOON -> path(true) {
            m(0.08f, -0.38f)
            cubicTo(cx - 0.5f * u, cy - 0.38f * u, cx - 0.5f * u, cy + 0.38f * u, cx + 0.08f * u, cy + 0.38f * u)
            cubicTo(cx - 0.2f * u, cy + 0.22f * u, cx - 0.2f * u, cy - 0.22f * u, cx + 0.08f * u, cy - 0.38f * u); close()
        }
        LifeGlyph.DROP -> path(true) {
            m(0f, -0.4f); cubicTo(cx + 0.1f * u, cy - 0.2f * u, cx + 0.3f * u, cy, cx + 0.3f * u, cy + 0.12f * u)
            cubicTo(cx + 0.3f * u, cy + 0.32f * u, cx - 0.3f * u, cy + 0.32f * u, cx - 0.3f * u, cy + 0.12f * u)
            cubicTo(cx - 0.3f * u, cy, cx - 0.1f * u, cy - 0.2f * u, cx, cy - 0.4f * u); close()
        }
        LifeGlyph.PEOPLE -> {
            drawCircle(color, 0.11f * u, p(-0.16f, -0.16f)); drawCircle(color, 0.11f * u, p(0.18f, -0.12f))
            drawArc(color, 180f, 180f, false, p(-0.38f, 0.04f), Size(0.44f * u, 0.5f * u), style = stroke)
            drawArc(color, 180f, 180f, false, p(-0.02f, 0.08f), Size(0.4f * u, 0.46f * u), style = stroke)
        }
        LifeGlyph.SMILE, LifeGlyph.FROWN -> {
            drawCircle(color, 0.36f * u, p(0f, 0f), style = stroke)
            drawCircle(color, 0.05f * u, p(-0.12f, -0.1f)); drawCircle(color, 0.05f * u, p(0.12f, -0.1f))
            if (g == LifeGlyph.SMILE) drawArc(color, 20f, 140f, false, p(-0.2f, -0.1f), Size(0.4f * u, 0.36f * u), style = stroke)
            else drawArc(color, 200f, 140f, false, p(-0.18f, 0.1f), Size(0.36f * u, 0.3f * u), style = stroke)
        }
        LifeGlyph.STAR -> path(true) {
            for (k in 0 until 10) {
                val a = -Math.PI / 2 + k * Math.PI / 5
                val r = if (k % 2 == 0) 0.4f else 0.17f
                val x = kotlin.math.cos(a).toFloat() * r; val y = kotlin.math.sin(a).toFloat() * r
                if (k == 0) m(x, y) else l(x, y)
            }
            close()
        }
        LifeGlyph.FLAME -> path(true) {
            m(0f, -0.4f); cubicTo(cx + 0.36f * u, cy - 0.1f * u, cx + 0.3f * u, cy + 0.38f * u, cx, cy + 0.38f * u)
            cubicTo(cx - 0.3f * u, cy + 0.38f * u, cx - 0.32f * u, cy, cx - 0.08f * u, cy - 0.12f * u)
            cubicTo(cx - 0.06f * u, cy + 0.02f * u, cx + 0.04f * u, cy - 0.16f * u, cx, cy - 0.4f * u); close()
        }
        LifeGlyph.GIFT -> {
            path(false) { m(-0.32f, -0.08f); l(0.32f, -0.08f); l(0.32f, 0.34f); l(-0.32f, 0.34f); close() }
            line(-0.38f, -0.08f, 0.38f, -0.08f); line(0f, -0.08f, 0f, 0.34f)
            drawArc(color, 180f, 180f, false, p(-0.22f, -0.3f), Size(0.22f * u, 0.3f * u), style = stroke)
            drawArc(color, 180f, 180f, false, p(0f, -0.3f), Size(0.22f * u, 0.3f * u), style = stroke)
        }
        LifeGlyph.QUESTION -> {
            drawArc(color, 180f, 270f, false, p(-0.2f, -0.38f), Size(0.4f * u, 0.36f * u), style = stroke)
            line(0f, -0.02f, 0f, 0.12f); drawCircle(color, 0.05f * u, p(0f, 0.3f))
        }
        LifeGlyph.SEAT -> {
            path(false) { m(-0.36f, 0.04f); l(0.36f, 0.04f); l(0.36f, 0.2f); l(-0.36f, 0.2f); close() }
            line(-0.28f, 0.04f, -0.28f, -0.32f); line(-0.28f, -0.32f, 0.04f, -0.32f); line(-0.3f, 0.2f, -0.3f, 0.36f); line(0.3f, 0.2f, 0.3f, 0.36f)
        }
        LifeGlyph.FOOT -> {
            path(true) { m(-0.06f, -0.04f); cubicTo(cx - 0.3f * u, cy - 0.1f * u, cx - 0.26f * u, cy + 0.4f * u, cx - 0.04f * u, cy + 0.36f * u); cubicTo(cx + 0.16f * u, cy + 0.34f * u, cx + 0.14f * u, cy, cx - 0.06f * u, cy - 0.04f * u); close() }
            drawCircle(color, 0.06f * u, p(-0.1f, -0.2f)); drawCircle(color, 0.05f * u, p(0.06f, -0.18f)); drawCircle(color, 0.045f * u, p(0.18f, -0.08f))
        }
        LifeGlyph.STORM -> {
            drawArc(color, 160f, 220f, false, p(-0.36f, -0.36f), Size(0.72f * u, 0.4f * u), style = stroke)
            line(-0.32f, -0.04f, 0.32f, -0.04f)
            path(false) { m(0.04f, 0f); l(-0.1f, 0.2f); l(0.06f, 0.2f); l(-0.08f, 0.4f) }
        }
    }
}

/**
 * The party frame's ring of needs round the NPC's plate: seven arcs from the top, clockwise in [Need] order, each in its
 * bar's colour and as long as it is full (a dim track behind), [dim] fainter.
 */
fun DrawScope.drawNeedRing(needs: List<Float>, cx: Float, cy: Float, radius: Float, width: Float, alpha: Float = 1f) {
    val n = Need.COUNT
    val gap = 7f
    val sweep = 360f / n - gap
    val tl = Offset(cx - radius, cy - radius)
    val sz = Size(radius * 2, radius * 2)
    for (k in 0 until n) {
        val start = -90f + k * (360f / n) + gap / 2
        val v = needs.getOrElse(k) { 0f }.coerceIn(0f, 1f)
        drawArc(Color(0xFF0A0D12), start, sweep, false, tl, sz, alpha = 0.85f * alpha, style = Stroke(width, cap = StrokeCap.Butt))
        if (v > 0f) drawArc(Color(LifeHud.needArgb(v)), start, sweep * v.coerceAtLeast(0.08f), false, tl, sz, alpha = alpha,
            style = Stroke(width, cap = StrokeCap.Butt))
    }
}

/** A party frame's relation mark at [cx], [cy], [d] across: a rose heart, a gold linked pair (friends), red crossed blades. */
fun DrawScope.drawRelation(glyph: RelationGlyph, cx: Float, cy: Float, d: Float) {
    if (glyph == RelationGlyph.NONE) return
    drawCircle(Color(0xF00B0E13), d * 0.55f, Offset(cx, cy))
    drawCircle(WifeTheme.trim.copy(alpha = 0.8f), d * 0.55f, Offset(cx, cy), style = Stroke(1.dp.toPx()))
    when (glyph) {
        RelationGlyph.ROMANCE -> drawLifeGlyph(LifeGlyph.HEART, cx, cy + d * 0.04f, d * 0.9f, Color(LifeHud.ROMANCE_ARGB))
        RelationGlyph.FRIEND -> {
            val w = d * 0.09f
            drawCircle(WifeTheme.gold, d * 0.2f, Offset(cx - d * 0.11f, cy), style = Stroke(w))
            drawCircle(WifeTheme.gold, d * 0.2f, Offset(cx + d * 0.11f, cy), style = Stroke(w))
        }
        RelationGlyph.RIVAL -> {
            val k = d * 0.24f
            drawLine(WifeTheme.danger, Offset(cx - k, cy - k), Offset(cx + k, cy + k), d * 0.1f, StrokeCap.Round)
            drawLine(WifeTheme.danger, Offset(cx + k, cy - k), Offset(cx - k, cy + k), d * 0.1f, StrokeCap.Round)
        }
        RelationGlyph.NONE -> Unit
    }
}

// ------------------------------------------------------------------------------------------------ the Life window

/** The Life window's tabs. */
enum class LifeTab(val label: String) { NEEDS("Needs"), RELATIONSHIPS("Relationships"), TRAITS("Traits") }

/**
 * One NPC's life in a [WifeWindow] (sized as Options, on either screen): Needs (the mood, the wish, what coloured the mood
 * lately, the seven bars in reputation-bar style, a long press on one says what fills it; the freeze switch), Relationships
 * (each other NPC's friendship and romance as the game's reputation bars, closest first) and Traits.
 */
@Composable
fun LifeWindow(
    person: LifePersonView,
    name: String,
    names: Map<String, String>,
    icons: GameIcons?,
    tab: LifeTab,
    onTab: (LifeTab) -> Unit,
    onFreeze: (Boolean) -> Unit,
    onClose: () -> Unit,
    visible: Boolean,
) {
    val reduced = reduceMotion()
    Overlay(onClose, visible) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
            val wide = layout.optionsW >= 560f
            WifeWindow(
                name, onClose, Modifier.width(layout.optionsW.dp).height(layout.optionsH.dp),
                subtitle = "${person.mood.label} · mood ${if (person.moodScore > 0) "+" else ""}${person.moodScore}" + if (person.frozen) " · needs frozen" else "",
                tabs = LifeTab.entries.map { it.label }, tab = tab.ordinal, onTab = { onTab(LifeTab.entries[it]) },
            ) {
                androidx.compose.animation.Crossfade(tab, Modifier.fillMaxWidth().weight(1f), animationSpec = tween(MenuMotion.duration(MenuMotion.PAGE_MS, reduced)), label = "lifeTab") { shown ->
                    when (shown) {
                        LifeTab.NEEDS -> NeedsPage(person, icons, wide, onFreeze)
                        LifeTab.RELATIONSHIPS -> RelationshipsPage(person, names, icons)
                        LifeTab.TRAITS -> TraitsPage(person, icons)
                    }
                }
            }
        }
    }
}

@Composable
private fun NeedsPage(person: LifePersonView, icons: GameIcons?, wide: Boolean, onFreeze: (Boolean) -> Unit) {
    var held by remember { mutableStateOf<Need?>(null) }
    val bars = LifeHud.needBars(person.needs)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        Row(Modifier.fillMaxWidth().heightIn(min = WifeTheme.touch), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapM)) {
            val gem = LifeHud.gem(person.mood)
            Canvas(Modifier.size(24.dp, 40.dp).semantics { contentDescription = "Mood: ${person.mood.label}" }) {
                drawMoodGem(gem, this.size.width / 2, this.size.height - 4.dp.toPx(), 1.2f)
            }
            LifeIconView(person.mood.icon, 36.dp, icons)
            Column(Modifier.weight(1f)) {
                Text(person.mood.label, style = WifeTheme.headingStyle(), maxLines = 1)
                // the wish: one line, always there (no line jumps when it comes and goes)
                Text(person.wish?.let { "Wish: $it" } ?: "No wish just now", style = WifeTheme.captionStyle(if (person.wish != null) WifeTheme.text else WifeTheme.textDim),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // what coloured the mood lately, as small chips (one line; it scrolls when long)
        Row(Modifier.fillMaxWidth().height(30.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (person.moodlets.isEmpty()) Text("Nothing much lately.", style = WifeTheme.captionStyle())
            for (m in person.moodlets) SmallChip(m)
        }
        WifeSectionHeader("Needs")
        if (wide) {
            for (row in bars.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapL)) {
                for (bar in row) NeedBarRow(bar, icons, held == bar.need, { held = it }, Modifier.weight(1f))
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        } else {
            for (bar in bars) NeedBarRow(bar, icons, held == bar.need, { held = it }, Modifier.fillMaxWidth())
        }
        // the held bar's words, in a box of fixed height (nothing moves when it changes)
        Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(WifeTheme.buttonRadius)).background(WifeTheme.slateDeep.copy(alpha = 0.6f))
            .padding(horizontal = WifeTheme.gapM), contentAlignment = Alignment.CenterStart) {
            Text(held?.let { h -> bars.first { it.need == h }.tooltip } ?: "Hold a bar to see what fills it.",
                style = WifeTheme.captionStyle(if (held != null) WifeTheme.text else WifeTheme.textDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        WifeSwitchRow("Freeze needs", person.frozen, onFreeze, detail = "Their needs stand still; they still go about their day")
    }
}

/** One need's bar in the reputation bar's style: its icon, its title and percent, the bar filling red to green. */
@Composable
private fun NeedBarRow(bar: NeedBar, icons: GameIcons?, held: Boolean, onHold: (Need?) -> Unit, modifier: Modifier) {
    val reduced = reduceMotion()
    val fill by animateFloatAsState(bar.value, tween(MenuMotion.duration(400, reduced)), label = "need")
    val tint by animateColorAsState(Color(bar.argb), tween(MenuMotion.duration(400, reduced)), label = "needColor")
    val hold by rememberUpdatedState(onHold)
    val shownNow by rememberUpdatedState(held)
    val need = bar.need
    Row(
        modifier.heightIn(min = WifeTheme.touch)
            .semantics {
                contentDescription = bar.tooltip
                progressBarRangeInfo = ProgressBarRangeInfo(bar.value, 0f..1f)
            }
            .pointerInput(need) {
                // a long press (or a tap) shows what fills it under the bars; a tap on the shown one hides it again
                detectTapGestures(
                    onLongPress = { hold(need) },
                    onTap = { hold(if (shownNow) null else need) },
                )
            },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS),
    ) {
        LifeIconView(need.icon, 30.dp, icons)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(need.wow, Modifier.weight(1f), style = frizStyle(WifeTheme.body, if (held) Color.White else WifeTheme.gold), maxLines = 1)
                Text(bar.percent, style = WifeTheme.captionStyle(WifeTheme.text), maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
            }
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                val r = CornerRadius(3.dp.toPx())
                drawRoundRect(WifeTheme.track, cornerRadius = r)
                val w = this.size.width * fill.coerceIn(0f, 1f)
                if (w > 0f) {
                    drawRoundRect(tint, size = Size(w, this.size.height), cornerRadius = r)
                    drawRoundRect(Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color.Transparent), endY = this.size.height * 0.6f), size = Size(w, this.size.height), cornerRadius = r)
                }
                // the reputation bar's notches: quarters
                for (k in 1..3) {
                    val x = this.size.width * k / 4f
                    drawLine(Color(0x66000000), Offset(x, 0f), Offset(x, this.size.height), 1.dp.toPx())
                }
                drawRoundRect(if (held) WifeTheme.gold else WifeTheme.trimSoft, cornerRadius = r, style = Stroke(1.dp.toPx()))
            }
        }
    }
}

@Composable
private fun SmallChip(text: String) {
    val shape = RoundedCornerShape(50)
    Text(text, Modifier.clip(shape).background(WifeTheme.slateRaised).border(1.dp, WifeTheme.trimSoft, shape).padding(horizontal = 10.dp, vertical = 3.dp),
        style = WifeTheme.captionStyle(WifeTheme.text), maxLines = 1)
}

@Composable
private fun RelationshipsPage(person: LifePersonView, names: Map<String, String>, icons: GameIcons?) {
    val list = remember(person.relationships) { LifeHud.sorted(person.relationships) }
    val state = rememberLazyListState()
    if (list.isEmpty()) {
        WifeNote("No one else is here yet.", Modifier.padding(WifeTheme.gapS))
        return
    }
    LazyColumn(Modifier.fillMaxSize().warScrollbar(state), state, verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
        items(list.size, key = { list[it].other }) { i ->
            val r = list[i]
            val friend = LifeHud.friendBar(r.friendship)
            val romance = LifeHud.romanceBar(r.romance)
            val glyph = LifeHud.relation(r)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(WifeTheme.buttonRadius)).background(WifeTheme.slateRaised.copy(alpha = 0.5f))
                    .border(WifeTheme.trimWidth, WifeTheme.trimSoft, RoundedCornerShape(WifeTheme.buttonRadius)).padding(WifeTheme.gapS)
                    .semantics(mergeDescendants = true) { contentDescription = "${names[r.other] ?: r.other}: ${friend.description}. ${romance.description}." },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                    Text(names[r.other] ?: r.other, Modifier.weight(1f), style = WifeTheme.labelStyle(WifeTheme.gold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (glyph != RelationGlyph.NONE) {
                        Canvas(Modifier.size(22.dp)) { drawRelation(glyph, center.x, center.y, this.size.minDimension) }
                        Text(glyph.label, style = WifeTheme.captionStyle(WifeTheme.text), maxLines = 1)
                    }
                }
                RepBarView(friend, 18.dp, null, icons)
                RepBarView(romance, 14.dp, "event_romance", icons)
            }
        }
    }
}

/** A reputation bar: the standing's colour filling how far through it, its name over it in the middle; a small icon before it. */
@Composable
private fun RepBarView(bar: RepBar, height: Dp, iconKey: String?, icons: GameIcons?) {
    val reduced = reduceMotion()
    val fill by animateFloatAsState(bar.fraction, tween(MenuMotion.duration(400, reduced)), label = "rep")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (iconKey != null) LifeIconView(iconKey, height + 4.dp, icons)
        Box(Modifier.weight(1f).height(height), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val r = CornerRadius(3.dp.toPx())
                drawRoundRect(WifeTheme.track, cornerRadius = r)
                val w = this.size.width * fill.coerceIn(0f, 1f)
                if (w > 0f) {
                    drawRoundRect(Color(bar.argb), size = Size(w, this.size.height), cornerRadius = r)
                    drawRoundRect(Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color.Transparent), endY = this.size.height * 0.6f), size = Size(w, this.size.height), cornerRadius = r)
                }
                drawRoundRect(WifeTheme.trim.copy(alpha = 0.6f), cornerRadius = r, style = Stroke(1.dp.toPx()))
            }
            Text(bar.label, style = frizStyle(if (height >= 18.dp) 12.sp else 10.sp, Color.White), maxLines = 1)
        }
    }
}

@Composable
private fun TraitsPage(person: LifePersonView, icons: GameIcons?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
        for (t in person.traits) Row(
            Modifier.fillMaxWidth().heightIn(min = WifeTheme.touch).semantics(mergeDescendants = true) { contentDescription = "${t.label}: ${t.description}" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapM),
        ) {
            LifeIconView(t.icon, 40.dp, icons)
            Column(Modifier.weight(1f)) {
                Text(t.label, style = WifeTheme.labelStyle(WifeTheme.gold), maxLines = 1)
                Text(t.description, style = WifeTheme.captionStyle(WifeTheme.text), maxLines = 2)
            }
        }
        if (person.traits.isEmpty()) WifeNote("No traits yet.")
        WifeSectionHeader("Lately")
        WifeNote(if (person.moodlets.isEmpty()) "Nothing much has happened." else person.moodlets.joinToString(", "))
    }
}

// ------------------------------------------------------------------------------------------------ the clock

/** The world clock's face in the top bar: the sun or the moon, the time over the part of the day and the day. Fixed width. */
@Composable
fun WorldClockFace(face: ClockFace, modifier: Modifier = Modifier) {
    Row(modifier.width(CLOCK_W), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(18.dp)) {
            drawLifeGlyph(if (face.night) LifeGlyph.MOON else LifeGlyph.SUN, center.x, center.y, this.size.minDimension,
                if (face.night) Color(0xFFBFD3F2) else WifeTheme.gold)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(face.time, style = WifeTheme.bodyStyle(WifeTheme.text), maxLines = 1)
                if (face.speed != ClockSpeed.X1) Text(
                    if (face.speed == ClockSpeed.PAUSED) "  ❚❚" else "  ${face.speed.label}",
                    style = frizStyle(WifeTheme.caption, WifeTheme.gold), maxLines = 1,
                )
            }
            Text("${face.day} · ${face.phase}", style = WifeTheme.captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The clock face's width ("12:59 PM x6" and "Day 12 · Afternoon" fit): the top bar never shifts as it ticks. */
val CLOCK_W = 132.dp

/** The speed buttons under the clock: pause, x1, x3, x6 (48 dp each), the current one lit; drops in and out (POP_MS). */
@Composable
fun SpeedStrip(visible: Boolean, current: ClockSpeed, onPick: (ClockSpeed) -> Unit, modifier: Modifier = Modifier) {
    val reduced = reduceMotion()
    val ms = MenuMotion.duration(MenuMotion.POP_MS, reduced)
    val slide = with(LocalDensity.current) { MenuMotion.POP_SLIDE_DP.dp.roundToPx() }
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(ms)) + slideInVertically(tween(ms)) { -slide },
        exit = fadeOut(tween(ms)) + slideOutVertically(tween(ms)) { -slide }) {
        Row(Modifier.wifeSurface(radius = 26.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (s in LifeHud.SPEEDS) {
                val on = s == current
                Box(
                    Modifier.size(WifeTheme.touch)
                        .semantics { contentDescription = if (s == ClockSpeed.PAUSED) "Pause time" else "Speed ${s.label}"; stateDescription = if (on) "On" else "Off" }
                        .warPress(sound = "tab") { onPick(s) },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(38.dp)) {
                        val r = this.size.minDimension / 2
                        drawCircle(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)), r)
                        if (on) drawCircle(WifeTheme.glow.copy(alpha = 0.35f), r)
                        drawCircle(if (on) WifeTheme.gold else WifeTheme.trim.copy(alpha = 0.6f), r - 0.75.dp.toPx(), style = Stroke(if (on) 2.dp.toPx() else 1.dp.toPx()))
                        val ink = if (on) Color.White else WifeTheme.gold
                        val c = center
                        val k = r * 0.34f
                        when (s) {
                            ClockSpeed.PAUSED -> {
                                drawLine(ink, Offset(c.x - k * 0.45f, c.y - k), Offset(c.x - k * 0.45f, c.y + k), 3.dp.toPx())
                                drawLine(ink, Offset(c.x + k * 0.45f, c.y - k), Offset(c.x + k * 0.45f, c.y + k), 3.dp.toPx())
                            }
                            else -> {
                                // one, two or three play triangles: x1, x3, x6
                                val count = when (s) { ClockSpeed.X1 -> 1; ClockSpeed.X3 -> 2; else -> 3 }
                                val tw = k * 0.9f
                                val total = tw * count
                                for (j in 0 until count) {
                                    val x0 = c.x - total / 2 + j * tw
                                    drawPath(Path().apply { moveTo(x0, c.y - k * 0.8f); lineTo(x0 + tw, c.y); lineTo(x0, c.y + k * 0.8f); close() }, ink)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ the feed

/** The feed's button in the top bar: a book on a round slate button, a gold dot while there is something new. 48 dp. */
@Composable
fun FeedButton(unseen: Int, open: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(WifeTheme.touch).semantics {
            contentDescription = if (unseen > 0) "Recent events, $unseen new" else "Recent events"
            stateDescription = if (open) "Open" else "Closed"
        }.warPress(sound = if (open) "close" else "open", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp)) {
            val r = this.size.minDimension / 2
            drawCircle(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)), r)
            drawCircle(if (open) WifeTheme.gold else WifeTheme.trim, r - 0.5.dp.toPx(), style = Stroke(if (open) 2.dp.toPx() else 1.dp.toPx()))
            drawLifeGlyph(LifeGlyph.BOOK, center.x, center.y, r * 1.2f, WifeTheme.gold)
            if (unseen > 0) {
                val dot = Offset(this.size.width - r * 0.42f, r * 0.42f)
                drawCircle(Color(0xFF0B0E13), r * 0.34f, dot)
                drawCircle(WifeTheme.glow, r * 0.24f, dot)
            }
        }
    }
}

/** The feed: what happened lately (newest first, the last [LifeHud.FEED]), each with its icon and when. Collapses away. */
@Composable
fun FeedPanel(visible: Boolean, events: List<LifeEvent>, icons: GameIcons?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = reduceMotion()
    val ms = MenuMotion.duration(MenuMotion.POP_MS + 40, reduced)
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(ms)) + androidx.compose.animation.expandVertically(tween(ms)),
        exit = fadeOut(tween(ms)) + androidx.compose.animation.shrinkVertically(tween(ms))) {
        Column(Modifier.widthIn(max = 340.dp).heightIn(max = 300.dp).wifeSurface().padding(start = WifeTheme.gapM, end = 4.dp, bottom = WifeTheme.gapS)) {
            Row(Modifier.fillMaxWidth().height(WifeTheme.touch), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent events", Modifier.weight(1f), style = WifeTheme.headingStyle(), maxLines = 1)
                WifeCloseButton(onClose)
            }
            val shown = events.take(LifeHud.FEED)
            if (shown.isEmpty()) WifeNote("Nothing has happened yet.", Modifier.padding(bottom = WifeTheme.gapS))
            val state = rememberLazyListState()
            val keys = remember(shown) { ListKeys.unique(shown.map { "${it.hours}|${it.text}" }) }
            LazyColumn(Modifier.weight(1f, fill = false).warScrollbar(state), state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown.size, key = { keys[it] }) { i ->
                    val e = shown[i]
                    Row(Modifier.fillMaxWidth().padding(end = WifeTheme.gapS), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                        LifeIconView(e.icon, 24.dp, icons)
                        Column(Modifier.weight(1f)) {
                            Text(e.text, style = WifeTheme.bodyStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(LifeHud.eventTime(e.hours), style = WifeTheme.captionStyle(), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The time sheet (the place and clock pill opens it): the clock's speed buttons (when the world's clock runs: [speed] null hides them)
 * and what happened lately, newest first, each with its icon and when. The feed's button and panel are folded into it.
 */
@Composable
fun TimeSheet(speed: ClockSpeed?, onSpeed: (ClockSpeed) -> Unit, events: List<LifeEvent>, icons: GameIcons?) {
    if (speed != null) {
        WifeSectionHeader("Speed")
        SpeedStrip(true, speed, onSpeed)
    }
    WifeSectionHeader("Recent events")
    val shown = events.take(LifeHud.FEED)
    if (shown.isEmpty()) WifeNote("Nothing has happened yet.", Modifier.padding(bottom = WifeTheme.gapS))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (e in shown) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
            LifeIconView(e.icon, 24.dp, icons)
            Column(Modifier.weight(1f)) {
                Text(e.text, style = WifeTheme.bodyStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(LifeHud.eventTime(e.hours), style = WifeTheme.captionStyle(), maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ the radial menu

/** A radial menu to show: whose ([title]), a line under it ([subtitle]), its items, its centre in px (NaN: the screen's). */
data class RadialMenuSpec(val title: String, val subtitle: String, val items: List<RadialItem>, val x: Float, val y: Float)

/**
 * The radial menu: a dim veil (a tap on it closes), a gold ring with the items round it (each the game's icon in a round gold
 * frame, its words under it), the hub's close button in the middle, the name above and a line below (the subtitle, or why
 * a greyed item can't be had when tapped, or a warning). The items fly out from the hub in [MenuMotion.RADIAL_MS].
 */
@Composable
fun RadialMenu(spec: RadialMenuSpec?, icons: GameIcons?, onPick: (RadialItem) -> Unit, onDismiss: () -> Unit) {
    val held = rememberHeld(spec) ?: return
    val reduced = reduceMotion()
    val t = remember { Animatable(0f) }
    LaunchedEffect(spec != null) {
        t.animateTo(if (spec != null) 1f else 0f, tween(MenuMotion.duration(MenuMotion.RADIAL_MS, reduced), easing = FastOutSlowInEasing))
    }
    if (spec == null && t.value <= 0f) return
    var message by remember(held) { mutableStateOf<String?>(null) }
    val gate = rememberClickGate()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val n = held.items.size
        val radiusDp = Radial.radius(n)
        val (cxPx, cyPx) = with(density) {
            val rx = if (held.x.isNaN()) wPx / 2 else held.x
            val ry = if (held.y.isNaN()) hPx / 2 else held.y
            val c = Radial.clampCenter(rx / density.density, ry / density.density, wPx / density.density, hPx / density.density, radiusDp + 20f)
            (c.first * density.density) to (c.second * density.density)
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = t.value }.background(Color(0x66000000))
                .clickable(remember { MutableInteractionSource() }, null) { if (spec != null && gate.pass()) onDismiss() }
                .semantics { contentDescription = "Close actions" },
        )
        // the ring
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = t.value }) {
            val r = radiusDp.dp.toPx()
            drawCircle(Color(0xB00B0E13), r + 30.dp.toPx(), Offset(cxPx, cyPx))
            drawCircle(WifeTheme.trim.copy(alpha = 0.85f), r + 30.dp.toPx(), Offset(cxPx, cyPx), style = Stroke(1.5.dp.toPx()))
            drawCircle(WifeTheme.trimSoft, r - 30.dp.toPx(), Offset(cxPx, cyPx), style = Stroke(1.dp.toPx()))
            drawCircle(Brush.radialGradient(listOf(WifeTheme.glow.copy(alpha = 0.12f), Color.Transparent), Offset(cxPx, cyPx), r + 30.dp.toPx()), r + 30.dp.toPx(), Offset(cxPx, cyPx))
        }
        // the name above the ring and the line under it
        val ringPx = with(density) { (radiusDp + 30f).dp.toPx() }
        Text(held.title, Modifier.offset { IntOffset(0, (cyPx - ringPx - 34.dp.toPx()).roundToInt().coerceAtLeast(0)) }.fillMaxWidth()
            .graphicsLayer { alpha = t.value }, style = WifeTheme.titleStyle(), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line = message ?: held.subtitle
        Box(Modifier.fillMaxWidth().offset { IntOffset(0, (cyPx + ringPx + 6.dp.toPx()).roundToInt()) }.graphicsLayer { alpha = t.value },
            contentAlignment = Alignment.TopCenter) {
            if (line.isNotEmpty()) Text(line, Modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(50)).background(WifeTheme.slate)
                .border(WifeTheme.trimWidth, WifeTheme.trimSoft, RoundedCornerShape(50)).padding(horizontal = WifeTheme.gapL, vertical = 6.dp),
                style = WifeTheme.bodyStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        // the hub: close
        val hubPx = with(density) { Radial.HUB_DP.dp.toPx() }
        Box(
            Modifier.offset { IntOffset((cxPx - hubPx / 2).roundToInt(), (cyPx - hubPx / 2).roundToInt()) }.size(Radial.HUB_DP.dp)
                .graphicsLayer { alpha = t.value }
                .semantics { contentDescription = "Close actions" }
                .warPress(sound = "close") { if (spec != null) onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(44.dp)) {
                val r = this.size.minDimension / 2
                drawCircle(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)), r)
                drawCircle(WifeTheme.trim, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                val k = r * 0.36f
                drawLine(WifeTheme.gold, Offset(center.x - k, center.y - k), Offset(center.x + k, center.y + k), 2.dp.toPx())
                drawLine(WifeTheme.gold, Offset(center.x + k, center.y - k), Offset(center.x - k, center.y + k), 2.dp.toPx())
            }
        }
        // the items, flying out from the hub
        val touchPx = with(density) { Radial.TOUCH_DP.dp.toPx() }
        val labelW = 84.dp
        val labelWPx = with(density) { labelW.toPx() }
        held.items.forEachIndexed { i, item ->
            key(item.label, i) {
                val (dx, dy) = Radial.position(i, n, radiusDp)
                Column(
                    Modifier.offset {
                        val k = t.value
                        IntOffset((cxPx + dx * density.density * k - labelWPx / 2).roundToInt(), (cyPx + dy * density.density * k - touchPx / 2).roundToInt())
                    }.width(labelW).graphicsLayer {
                        val k = t.value
                        alpha = k
                        val s = MenuMotion.RADIAL_SCALE_FROM + (1f - MenuMotion.RADIAL_SCALE_FROM) * k
                        scaleX = s; scaleY = s
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RadialItemButton(item, icons) {
                        if (spec == null) return@RadialItemButton
                        if (item.enabled) onPick(item) else message = item.reason ?: "${item.label} can't be done here now."
                    }
                    Text(item.label, style = frizStyle(11.sp, if (item.enabled) WifeTheme.text else WowColors.grey).copy(lineHeight = 13.sp),
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One radial item: the icon in a round gold frame, glowing as it is pressed; greyed (and saying why when tapped) when it can't be had. */
@Composable
private fun RadialItemButton(item: RadialItem, icons: GameIcons?, onClick: () -> Unit) {
    val description = when {
        !item.enabled -> "${item.label}, unavailable: ${item.reason ?: "not here"}"
        item.hint != null -> "${item.label}. ${item.hint}"
        else -> item.label
    }
    Box(
        Modifier.size(Radial.TOUCH_DP.dp).semantics { contentDescription = description; role = Role.Button }
            .warPress(sound = if (item.enabled) "click" else "close", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(Radial.ITEM_DP.dp)) {
            val r = this.size.minDimension / 2
            drawCircle(Color(0xFF0B0E13), r)
            drawCircle(Brush.sweepGradient(listOf(Color(0xFFF6DD9A), WifeTheme.trim, Color(0xFF7A5A22), WifeTheme.trim, Color(0xFFF6DD9A))), r, style = Stroke(3.dp.toPx()))
            if (item.hint != null && item.enabled) drawCircle(Color(LifeHud.ROMANCE_ARGB).copy(alpha = 0.7f), r + 1.dp.toPx(), style = Stroke(1.dp.toPx()))
        }
        LifeIconView(item.icon, (Radial.ITEM_DP - 12f).dp, icons, enabled = item.enabled, round = true)
    }
}

// ------------------------------------------------------------------------------------------------ the answer

/** The answer to the player's interaction, under the top bar for a moment: a green tick or a red cross and the words. */
@Composable
fun LifeToast(result: LifeResult?, modifier: Modifier = Modifier) {
    val reduced = reduceMotion()
    val ms = MenuMotion.duration(MenuMotion.WINDOW_IN_MS, reduced)
    val shown = rememberHeld(result)
    AnimatedVisibility(result != null, modifier, enter = fadeIn(tween(ms)) + slideInVertically(tween(ms)) { -it / 3 },
        exit = fadeOut(tween(MenuMotion.duration(320, reduced)))) {
        val r = shown ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(50)
        Row(
            Modifier.widthIn(max = 460.dp).clip(shape).background(WifeTheme.slate)
                .border(if (r.accepted) 1.5.dp else 1.dp, if (r.accepted) WifeTheme.good.copy(alpha = 0.8f) else WifeTheme.danger.copy(alpha = 0.8f), shape)
                .padding(horizontal = WifeTheme.gapL, vertical = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = (if (r.accepted) "Accepted: " else "Refused: ") + r.message },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS),
        ) {
            Canvas(Modifier.size(18.dp)) {
                val c = center; val k = this.size.minDimension * 0.36f; val w = 2.5.dp.toPx()
                if (r.accepted) {
                    drawLine(WifeTheme.good, Offset(c.x - k, c.y), Offset(c.x - k * 0.2f, c.y + k * 0.8f), w, StrokeCap.Round)
                    drawLine(WifeTheme.good, Offset(c.x - k * 0.2f, c.y + k * 0.8f), Offset(c.x + k, c.y - k * 0.8f), w, StrokeCap.Round)
                } else {
                    drawLine(WifeTheme.danger, Offset(c.x - k, c.y - k), Offset(c.x + k, c.y + k), w, StrokeCap.Round)
                    drawLine(WifeTheme.danger, Offset(c.x + k, c.y - k), Offset(c.x - k, c.y + k), w, StrokeCap.Round)
                }
            }
            Text(r.message, style = WifeTheme.bodyStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
