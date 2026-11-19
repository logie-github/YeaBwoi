package com.logie.wifecraft.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// The editor's build-mode widgets: the game's own pieces (WarArt) softened and warmed, in WarKit's palette and sizes.
// Every widget draws plainly in the same colours when a piece isn't here yet. Touch targets are 48 dp or more; a press
// sinks the widget a little and plays the interface's click (LocalSounds).

/** The palette as Compose colours. */
object War {
    val cream = Color(WarPalette.CREAM)
    val parchment = Color(WarPalette.PARCHMENT)
    val parchmentDeep = Color(WarPalette.PARCHMENT_DEEP)
    val amber = Color(WarPalette.AMBER)
    val gold = Color(WarPalette.GOLD)
    val goldSoft = Color(WarPalette.GOLD_SOFT)
    val brown = Color(WarPalette.BROWN)
    val brownSoft = Color(WarPalette.BROWN_SOFT)
    val wood = Color(WarPalette.WOOD)
    val woodDark = Color(WarPalette.WOOD_DARK)
    val glass = Color(WarPalette.GLASS)
    val glassLight = Color(WarPalette.GLASS_LIGHT)
    val ink = Color(WarPalette.INK_ON_DARK)
    val muted = Color(WarPalette.MUTED)
    val danger = Color(WarPalette.DANGER)
    val edge = Color(0xFFB08850)
}

/** Friz on parchment (no shadow) or on the dark ground (a soft shadow). */
@Composable
fun warText(size: TextUnit, color: Color = War.brown, dark: Boolean = false): TextStyle {
    val art = LocalArt.current
    return TextStyle(fontFamily = art?.friz ?: FontFamily.Serif, fontSize = size, color = color, shadow = if (dark) WowColors.shadow else null).steadyLines()
}

/** Morpheus for names and titles. */
@Composable
fun warTitle(size: TextUnit, color: Color = War.brown, dark: Boolean = false): TextStyle {
    val art = LocalArt.current
    return TextStyle(fontFamily = art?.morpheus ?: FontFamily.Serif, fontSize = size, color = color, shadow = if (dark) WowColors.shadow else null).steadyLines()
}

// ------------------------------------------------------------------------------------------------ drawing the pieces

/** [img] stretched over x, y, w, h. */
fun DrawScope.drawWar(img: ImageBitmap?, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f) {
    img ?: return
    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(x.roundToInt(), y.roundToInt()),
        IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)), alpha = alpha, filterQuality = FilterQuality.Medium)
}

/**
 * One path for every clip the kit draws (an oval, a rounded square, the dropdown's arrow), filled again for each: no Path is
 * allocated on every draw. Compose draws on the main thread only, and the canvas copies a clip's path as it is given, so the
 * next fill can't change one already used.
 */
private val scratch = Path()

internal fun ovalPath(l: Float, t: Float, r: Float, b: Float): Path = scratch.apply { reset(); addOval(androidx.compose.ui.geometry.Rect(l, t, r, b)) }

internal fun roundPath(l: Float, t: Float, r: Float, b: Float, radius: Float): Path =
    scratch.apply { reset(); addRoundRect(androidx.compose.ui.geometry.RoundRect(l, t, r, b, CornerRadius(radius))) }

/** A triangle pointing down (the dropdown's arrow, the talking head's More). */
internal fun downArrowPath(l: Float, t: Float, r: Float, b: Float): Path =
    scratch.apply { reset(); moveTo(l, t); lineTo(r, t); lineTo((l + r) / 2, b); close() }

/** [img] in a circle of [d] px at [cx], [cy] (an icon in a round button). */
fun DrawScope.drawCircled(img: ImageBitmap?, cx: Float, cy: Float, d: Float, alpha: Float = 1f) {
    img ?: return
    clipPath(ovalPath(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2)) { drawWar(img, cx - d / 2, cy - d / 2, d, d, alpha) }
}

/** [img] centred at its own aspect, fitting [d] px (a glyph). */
fun DrawScope.drawFitted(img: ImageBitmap?, cx: Float, cy: Float, d: Float, alpha: Float = 1f) {
    img ?: return
    val k = d / maxOf(img.width, img.height)
    drawWar(img, cx - img.width * k / 2, cy - img.height * k / 2, img.width * k, img.height * k, alpha)
}

/**
 * A nine-slice of the piece [name] over the whole draw area: its corners kept (WarSlices' insets, drawn at [pxPerPiecePx]
 * screen pixels per piece pixel, never more than a third of a side), its edges and middle stretched.
 */
fun DrawScope.drawNine(img: ImageBitmap?, name: String, pxPerPiecePx: Float, alpha: Float = 1f) {
    img ?: return
    val f = WarSlices.INSETS[name] ?: floatArrayOf(0f, 0f, 0f, 0f)
    val pl = (f[0] * img.width).toInt(); val pt = (f[1] * img.height).toInt()
    val pr = (f[2] * img.width).toInt(); val pb = (f[3] * img.height).toInt()
    val c = WarSlices.cornerDp(name, img.width, img.height, pxPerPiecePx, size.width, size.height)
    val xs = listOf(Triple(0 to pl, 0f, c[0]), Triple(pl to img.width - pr, c[0], size.width - c[0] - c[2]), Triple(img.width - pr to img.width, size.width - c[2], c[2]))
    val ys = listOf(Triple(0 to pt, 0f, c[1]), Triple(pt to img.height - pb, c[1], size.height - c[1] - c[3]), Triple(img.height - pb to img.height, size.height - c[3], c[3]))
    for ((sx, dx, dw) in xs) for ((sy, dy, dh) in ys) {
        if (sx.second <= sx.first || sy.second <= sy.first || dw < 1f || dh < 1f) continue
        drawImage(img, IntOffset(sx.first, sy.first), IntSize(sx.second - sx.first, sy.second - sy.first),
            IntOffset(dx.roundToInt(), dy.roundToInt()), IntSize(dw.roundToInt(), dh.roundToInt()), alpha = alpha, filterQuality = FilterQuality.Medium)
    }
}

// ------------------------------------------------------------------------------------------------ pressing

/**
 * Taps and long presses with the press's sink ([MenuMotion.PRESS_SCALE], none under reduced motion) and the interface's click.
 * [onLongPress] null: a long press is a tap. A second tap within [MenuMotion.DEBOUNCE_MS] of the last does nothing (a window
 * is never opened twice, Back never goes back twice) unless [repeatable] (Zoom, Undo: pressed again and again on purpose).
 */
@Composable
fun Modifier.warPress(enabled: Boolean = true, sound: String = "click", onLongPress: (() -> Unit)? = null, repeatable: Boolean = false, onClick: () -> Unit): Modifier {
    val click = LocalSounds.current
    val reduced = reduceMotion()
    var down by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(MenuMotion.pressScale(down, reduced), tween(MenuMotion.duration(MenuMotion.PRESS_MS, reduced)), label = "press")
    val gate = rememberClickGate(if (repeatable) 0L else MenuMotion.DEBOUNCE_MS)
    val tap by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongPress)
    val on by rememberUpdatedState(enabled)
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .semantics {
            role = Role.Button
            if (enabled) onClick { if (gate.pass()) tap(); true } else disabled()
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    if (on) down = true
                    tryAwaitRelease()
                    down = false
                },
                onLongPress = { if (on && gate.pass()) { click(sound); (long ?: tap)() } },
                onTap = { if (on && gate.pass()) { click(sound); tap() } },
            )
        }
}

// ------------------------------------------------------------------------------------------------ round buttons

/** What a round button shows: a gold glyph (an atlas piece), an item icon (a key), or a housing tool button's own picture. */
data class Face(val glyph: String? = null, val icon: String? = null, val decor: String? = null)

/**
 * A round button, [size] across (56 dp): the radio button's disc warmed to bronze, its glyph, icon or tool picture, the
 * talent tree's gold ring while [lit]; [primary] is the amber one (Play). A [badge] (a count) sits on its rim; [label]
 * under it. Disabled: dimmed, still 48 dp to touch.
 */
@Composable
fun RoundIconButton(
    face: Face,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = WarSize.BUTTON.dp,
    lit: Boolean = false,
    enabled: Boolean = true,
    primary: Boolean = false,
    badge: String? = null,
    label: String? = null,
    description: String? = label,
    onLongPress: (() -> Unit)? = null,
    /** On parchment: the label in deep brown (on the dark ground it is cream with a shadow). */
    onLight: Boolean = false,
    /** Pressed again and again on purpose (zoom, undo): every tap counts; other buttons ignore a second tap at once. */
    repeatable: Boolean = face.glyph in RepeatGlyphs,
) {
    val war = LocalWar.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).semantics { description?.let { contentDescription = it } }
                .warPress(enabled, onLongPress = onLongPress, repeatable = repeatable, onClick = onClick),
        ) {
            Canvas(Modifier.matchParentSize()) {
                val r = this.size.minDimension / 2; val c = center
                drawCircle(Color(WarPalette.SHADOW), r, c + Offset(0f, 2.dp.toPx()))
                if (primary) {
                    drawCircle(War.amber, r, c)
                    drawCircle(Color(0xFFB8741F), r * 0.86f, c)
                } else {
                    val disc = war?.piece("common-radiobutton-circle")
                    if (disc != null) drawWar(disc, c.x - r, c.y - r, 2 * r, 2 * r)
                    else { drawCircle(War.woodDark, r, c); drawCircle(Color(0xFF9C7A4C), r, c, style = Stroke(r * 0.14f)) }
                }
                val a = if (enabled) 1f else 0.42f
                // what the button shows, or (its picture not here yet, or failed) the same thing as a vector glyph
                var drawn = false
                face.decor?.let { name ->
                    val p = war?.piece(name)
                    if (p != null) {
                        drawn = true
                        // the housing button's picture without its square frame: its middle, round
                        val k = (p.width * 0.17f).toInt()
                        val path = ovalPath(c.x - r * 0.74f, c.y - r * 0.74f, c.x + r * 0.74f, c.y + r * 0.74f)
                        clipPath(path) {
                            drawImage(p, IntOffset(k, k), IntSize(p.width - 2 * k, p.height - 2 * k),
                                IntOffset((c.x - r * 0.74f).roundToInt(), (c.y - r * 0.74f).roundToInt()), IntSize((r * 1.48f).roundToInt(), (r * 1.48f).roundToInt()),
                                alpha = a, filterQuality = FilterQuality.Medium)
                        }
                    }
                }
                if (!drawn) face.icon?.let { k -> war?.icon(k)?.let { drawCircled(it, c.x, c.y, 2 * r * WarSize.ICON_IN_ROUND, a); drawn = true } }
                if (!drawn) face.glyph?.let { g ->
                    val img = war?.piece(g)
                    if (img != null) { drawFitted(img, c.x + if (g == WarIcons.PLAY) r * 0.08f else 0f, c.y, 2 * r * (if (primary) 0.46f else 0.5f), a); drawn = true }
                }
                if (!drawn) drawGlyphFor(face.glyph ?: face.icon ?: face.decor, c.x, c.y, 2 * r * 0.52f, if (primary) War.brown else War.gold, a)
                if (!enabled) drawCircle(Color(0x781E140C), r, c)
                if (lit) {
                    val ring = war?.piece("talents-node-circle-yellow")
                    val rr = r * 1.16f
                    if (ring != null) drawWar(ring, c.x - rr, c.y - rr, 2 * rr, 2 * rr)
                    else drawCircle(War.gold, r * 1.02f, c, style = Stroke(3.dp.toPx()))
                }
            }
            if (badge != null) Badge(badge, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
        }
        if (label != null) Text(label, style = if (onLight) warText(11.sp, War.brown) else warText(11.sp, War.ink, true), maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

/** The round buttons pressed again and again on purpose. */
private val RepeatGlyphs = setOf(WarIcons.PLUS, WarIcons.MINUS, WarIcons.UNDO, WarIcons.REDO)

/** A count on a rim: an amber pill with brown figures. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 18.dp).widthIn(min = 22.dp).clip(RoundedCornerShape(50)).background(War.amber)
            .border(1.5.dp, War.woodDark, RoundedCornerShape(50)).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = warText(10.sp, War.brown), maxLines = 1) }
}

/**
 * A category tab of the catalogue bar: a round item icon in the talent tree's ring (grey; gold and raised, a little larger
 * and glowing when [chosen]), its name under it, its count on the rim.
 */
@Composable
fun CategoryTab(tab: CatalogueTab, chosen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, showCount: Boolean = true) {
    val war = LocalWar.current
    val rise by animateFloatAsState(if (chosen) WarSize.TAB_RAISE else 0f, tween(160), label = "rise")
    val grow by animateFloatAsState(if (chosen) WarSize.TAB_CHOSEN_SCALE else 1f, tween(160), label = "grow")
    Column(modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.padding(top = WarSize.TAB_RAISE.dp).offset(y = (-rise).dp).size(WarSize.TAB.dp)
                .graphicsLayer { scaleX = grow; scaleY = grow }
                .semantics { contentDescription = tab.label }
                .warPress(onClick = onClick),
        ) {
            Canvas(Modifier.matchParentSize()) {
                val r = size.minDimension / 2; val c = center
                if (chosen) war?.piece("housing-layout-room-orb-ring-active")?.let { g -> val gr = r * 1.45f; drawWar(g, c.x - gr, c.y - gr + 3.dp.toPx(), 2 * gr, 2 * gr, 0.9f) }
                drawCircle(Color(WarPalette.SHADOW), r, c + Offset(0f, (if (chosen) 5 else 2).dp.toPx()))
                val disc = war?.piece("common-radiobutton-circle")
                if (disc != null) drawWar(disc, c.x - r, c.y - r, 2 * r, 2 * r) else drawCircle(War.woodDark, r, c)
                val img = war?.icon(tab.icon)
                if (img != null) drawCircled(img, c.x, c.y, 2 * r * 0.74f) else drawGlyphFor(tab.icon, c.x, c.y, 2 * r * 0.52f)
                val ring = war?.piece(if (chosen) "talents-node-circle-yellow" else "talents-node-circle-gray")
                val rr = r * 1.12f
                if (ring != null) drawWar(ring, c.x - rr, c.y - rr, 2 * rr, 2 * rr)
                else drawCircle(if (chosen) War.gold else War.muted, r, c, style = Stroke(2.5.dp.toPx()))
            }
            if (showCount && tab.count > 0) Badge(compactCount(tab.count), Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp))
        }
        Text(tab.label, style = warText(11.sp, if (chosen) War.gold else War.ink, true), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
    }
}

/** 48,212 -> "48k"; under 10,000 as it is. */
fun compactCount(n: Int): String = if (n >= 10_000) "${n / 1000}k" else "%,d".format(n)

/** A chip of the category row: a cream pill (deep parchment with a gold edge when [on]), its icon, its name and count. */
@Composable
fun Chip(label: String, count: Int?, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null) {
    val war = LocalWar.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier.height(WarSize.CHIP_H.dp).padding(vertical = 4.dp).shadow(2.dp, shape).clip(shape)
            .background(if (on) War.parchmentDeep else War.cream).border(if (on) 2.dp else 1.dp, if (on) War.gold else War.edge, shape)
            .warPress(onClick = onClick).padding(start = if (icon != null) 6.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Canvas(Modifier.size(28.dp)) {
            val img = war?.icon(icon)
            if (img != null) drawCircled(img, center.x, center.y, size.minDimension)
            else { drawCircle(War.woodDark, size.minDimension / 2); drawGlyphFor(icon, center.x, center.y, size.minDimension * 0.62f) }
        }
        Text(label, style = warText(13.sp), maxLines = 1)
        if (count != null) Text("%,d".format(count), style = warText(11.sp, War.brownSoft), maxLines = 1)
    }
}

/** A big friendly button: a rounded wood pill with gold words (red-brown for [danger]), an optional glyph before them. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, glyph: String? = null, height: Dp = 48.dp) {
    val war = LocalWar.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier.heightIn(min = height).shadow(3.dp, shape).clip(shape)
            .background(Brush.verticalGradient(if (danger) listOf(Color(0xFF8E4630), Color(0xFF6A2E1C)) else listOf(Color(0xFF7E5332), War.woodDark)))
            .border(1.5.dp, if (enabled) War.goldSoft else War.muted, shape)
            .warPress(enabled, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            Canvas(Modifier.size(22.dp)) {
                val img = war?.piece(glyph)
                if (img != null) drawFitted(img, center.x, center.y, size.minDimension, if (enabled) 1f else 0.45f)
                else drawGlyphFor(glyph, center.x, center.y, size.minDimension, if (danger) War.cream else War.gold, if (enabled) 1f else 0.45f)
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = warText(14.sp, if (!enabled) War.muted else if (danger) War.cream else War.gold, true), maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** An on/off switch: an amber track with the radio button's gold dot (a grey disc when off), its label after it. */
@Composable
fun WarToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, dark: Boolean = false) {
    val war = LocalWar.current
    val at by animateFloatAsState(if (on) 1f else 0f, tween(140), label = "toggle")
    Row(
        modifier.heightIn(min = 48.dp).warPress(enabled) { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.width(56.dp).height(32.dp)) {
            val h = size.height
            drawRoundRect(if (on) War.amber else Color(0xFFBFA06A), cornerRadius = CornerRadius(h / 2), alpha = if (enabled) 1f else 0.5f)
            drawRoundRect(War.woodDark, cornerRadius = CornerRadius(h / 2), style = Stroke(1.2.dp.toPx()))
            val k = 28.dp.toPx(); val x = k / 2 + 2.dp.toPx() + (size.width - k - 4.dp.toPx()) * at
            val knob = war?.piece(if (on) "common-radiobutton-dot" else "common-radiobutton-circle")
            if (knob != null) drawWar(knob, x - k / 2, h / 2 - k / 2, k, k) else drawCircle(if (on) War.gold else War.muted, k / 2, Offset(x, h / 2))
        }
        Text(label, style = warText(14.sp, if (dark) War.ink else War.brown, dark), maxLines = 2)
    }
}

/**
 * A slider on parchment: an amber filled track, the radio button's gold dot as its knob. [value] in [range]; drags report
 * [onChange], letting go [onFinished]. 48 dp high to touch.
 */
@Composable
fun WarSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier, enabled: Boolean = true, onFinished: () -> Unit = {}) {
    val war = LocalWar.current
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinished)
    val on by rememberUpdatedState(enabled)
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    fun at(x: Float, w: Float, knob: Float) = range.start + ((x - knob / 2) / (w - knob)).coerceIn(0f, 1f) * span
    Canvas(
        modifier.fillMaxWidth().height(48.dp)
            .pointerInput(range) {
                detectTapGestures { o -> if (on) { change(at(o.x, size.width.toFloat(), 26.dp.toPx())); finish() } }
            }
            .pointerInput(range) {
                detectHorizontalDragGestures(onDragEnd = { if (on) finish() }, onDragCancel = { if (on) finish() }) { ch, _ ->
                    if (on) { ch.consume(); change(at(ch.position.x, size.width.toFloat(), 26.dp.toPx())) }
                }
            },
    ) {
        val k = 26.dp.toPx(); val th = 8.dp.toPx(); val y = size.height / 2
        val x0 = k / 2; val w = size.width - k
        drawRoundRect(Color(0xFFBFA06A), Offset(x0, y - th / 2), Size(w, th), CornerRadius(th / 2))
        drawRoundRect(War.amber, Offset(x0, y - th / 2), Size(w * frac, th), CornerRadius(th / 2), alpha = if (enabled) 1f else 0.5f)
        val kx = x0 + w * frac
        drawCircle(Color(WarPalette.SHADOW), k / 2, Offset(kx, y + 2.dp.toPx()))
        val dot = war?.piece("common-radiobutton-dot")
        if (dot != null) drawWar(dot, kx - k / 2, y - k / 2, k, k, if (enabled) 1f else 0.5f) else drawCircle(War.gold, k / 2, Offset(kx, y))
    }
}

/** A cream field on parchment: its label above it, the words in deep brown, a gold edge while typing. */
@Composable
fun WarField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
    onFocus: (Boolean) -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(WarSize.R_SMALL.dp)
    Column(modifier) {
        if (label != null) Text(label, style = warText(12.sp, War.brownSoft), modifier = Modifier.padding(bottom = 3.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(if (enabled) War.cream else War.parchment)
                .border(if (focused) 2.dp else 1.dp, if (focused) War.gold else War.edge, shape).padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            if (value.isEmpty()) Text(hint, style = warText(15.sp, War.muted), maxLines = 1)
            BasicTextField(
                value, onChange, singleLine = singleLine, enabled = enabled,
                textStyle = warText(15.sp), cursorBrush = SolidColor(War.brown),
                keyboardOptions = keyboard, keyboardActions = actions,
                modifier = Modifier.fillMaxWidth().onFocusChanged { f -> if (focused != f.isFocused) { focused = f.isFocused; onFocus(f.isFocused) } },
            )
        }
    }
}

/** A number typed on parchment: used when it reads as one and Done is pressed or the field is left. */
@Composable
fun WarNumberField(label: String, value: Float, modifier: Modifier = Modifier, decimals: Int = 2, enabled: Boolean = true, onValue: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(showNumber(value, decimals)) }
    fun use() { readNumber(text)?.let { if (it != value) onValue(it) } ?: run { text = showNumber(value, decimals) } }
    WarField(
        text, { text = it.take(12) }, "", modifier, label = label, enabled = enabled,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        actions = KeyboardActions(onDone = { use() }),
        onFocus = { f -> if (!f) use() },
    )
}

/** The search box: the magnifying glass, the words, Clear. On the dark bar: cream on warm glass. */
@Composable
fun WarSearch(value: String, onChange: (String) -> Unit, hint: String, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val war = LocalWar.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier.height(48.dp).clip(shape).background(War.cream).border(1.5.dp, War.goldSoft, shape).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val img = war?.piece(WarIcons.SEARCH)
            if (img != null) drawFitted(img, center.x, center.y, size.minDimension) else drawGlyph(WarGlyph.SEARCH, center.x, center.y, size.minDimension, War.brown)
        }
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (value.isEmpty()) Text(hint, style = warText(14.sp, War.muted), maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = warText(15.sp), cursorBrush = SolidColor(War.brown), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) RoundIconButton(Face(glyph = WarIcons.CLOSE), onClear, size = 48.dp, description = "Clear")
    }
}

// ------------------------------------------------------------------------------------------------ cards

/**
 * A picture card (the Sims' catalogue card): the decor chest's frame (gold when [armed]), the picture rounded inside it
 * (or the name, or an initial, until it is drawn), the star for favourites at its corner (48 dp to touch), the name under it.
 */
@Composable
fun ItemCard(
    name: AnnotatedString,
    picture: ImageBitmap?,
    size: Dp,
    armed: Boolean,
    favourite: Boolean,
    onClick: () -> Unit,
    onFavourite: (() -> Unit)?,
    modifier: Modifier = Modifier,
    placeholder: String = name.text.take(1).uppercase(),
    badge: String? = null,
    /** On parchment (the Library window): the name in deep brown instead of cream. */
    onLight: Boolean = false,
    enabled: Boolean = true,
    selection: Boolean? = null,
) {
    val war = LocalWar.current
    Column(modifier.width(size), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).semantics {
            contentDescription = name.text
            if (selection != null) selected = selection
            if (enabled) onClick { onClick(); true } else disabled()
        }.warPress(enabled, onClick = onClick)) {
            Canvas(Modifier.matchParentSize()) {
                val s = this.size.width
                drawRoundRect(Color(WarPalette.SHADOW), Offset(0f, 3.dp.toPx()), this.size, CornerRadius(16.dp.toPx()))
                val frame = war?.piece(if (armed) "house-chest-list-item-active" else "house-chest-list-item-default")
                if (frame != null) drawWar(frame, 0f, 0f, s, s)
                else drawRoundRect(War.woodDark, cornerRadius = CornerRadius(16.dp.toPx()))
                val inset = s * 0.085f; val r = 11.dp.toPx()
                if (picture != null) {
                    val p = roundPath(inset, inset, s - inset, s - inset, r)
                    clipPath(p) {
                        drawRect(Color(0xFF2C1E14), Offset(inset, inset), Size(s - 2 * inset, s - 2 * inset))
                        // the picture at its own aspect, centred
                        val k = minOf((s - 2 * inset) / picture.width, (s - 2 * inset) / picture.height)
                        drawWar(picture, (s - picture.width * k) / 2, (s - picture.height * k) / 2, picture.width * k, picture.height * k)
                    }
                }
                drawRoundRect(if (armed) War.gold else Color(0xC8785A32), Offset(inset, inset), Size(s - 2 * inset, s - 2 * inset), CornerRadius(r),
                    style = Stroke((if (armed) 2 else 1).dp.toPx()))
            }
            if (picture == null) Text(placeholder, style = warText(if (placeholder.length > 2) 11.sp else 28.sp, War.muted, true), textAlign = TextAlign.Center, maxLines = 4,
                modifier = Modifier.align(Alignment.Center).padding(10.dp))
            if (onFavourite != null) Box(Modifier.align(Alignment.TopEnd).size(48.dp).warPress(onClick = onFavourite), contentAlignment = Alignment.TopEnd) {
                Canvas(Modifier.padding(7.dp).size(22.dp)) {
                    val star = war?.piece(if (favourite) WarIcons.STAR else WarIcons.STAR_OFF)
                    if (star != null) drawFitted(star, center.x, center.y, this.size.minDimension)
                    else drawGlyph(WarGlyph.STAR, center.x, center.y, this.size.minDimension, if (favourite) War.gold else War.muted)
                }
            }
            if (badge != null) Badge(badge, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        Text(name, style = if (onLight) warText(12.sp, if (armed) War.wood else War.brown) else warText(11.5.sp, if (armed) War.gold else War.ink, true), maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

// ------------------------------------------------------------------------------------------------ panels

/**
 * The spellbook's page: its parchment and ornate border as a nine-slice (rounded off), the spellbook's ribbon at its top
 * right, the content inside its margins. Plain cream with a brown edge until the piece is here.
 */
@Composable
fun BookPage(modifier: Modifier = Modifier, ribbon: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val war = LocalWar.current
    val shape = RoundedCornerShape(WarSize.R_PANEL.dp)
    Box(
        modifier.shadow(10.dp, shape).clip(shape).drawBehind {
            val page = war?.piece("spellbook-background-evergreen-left")
            if (page != null) drawNine(page, "spellbook-background-evergreen-left", 0.42f * density)
            else { drawRect(War.parchment); drawRoundRect(War.wood, cornerRadius = CornerRadius(WarSize.R_PANEL.dp.toPx()), style = Stroke(4.dp.toPx())) }
        }.drawWithContent {
            drawContent()
            if (ribbon) war?.piece("spellbook-background-evergreen-ribbon")?.let { r ->
                val w = 28.dp.toPx(); val h = w * r.height / r.width * 0.45f
                drawImage(r, IntOffset.Zero, IntSize(r.width, (r.height * 0.45f).toInt()), IntOffset((size.width - 64.dp.toPx()).roundToInt(), 0), IntSize(w.roundToInt(), h.roundToInt()))
            }
        }.padding(start = 28.dp, end = 28.dp, top = 26.dp, bottom = 22.dp),
        content = content,
    )
}

/** A page's header: a small grey line over a name in Morpheus, an optional line under it, a thin rule. */
@Composable
fun BookHeader(over: String?, title: String, under: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (over != null) Text(over, style = warText(12.sp, War.brownSoft), maxLines = 1)
                Text(title, style = warTitle(22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (under != null) Text(under, style = warText(12.sp, War.brownSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
        }
        Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(2.dp).background(Color(0xA0966E3C), RoundedCornerShape(1.dp)))
    }
}

/** The spellbook's side tab with an item icon in it (a page folded to its tab), lit gold when its page is open. */
@Composable
fun SideTab(icon: String, label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val war = LocalWar.current
    Box(modifier.width(52.dp).height(60.dp).semantics { contentDescription = label }.warPress(sound = if (on) "close" else "open", onClick = onClick)) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(Color(WarPalette.SHADOW), Offset(0f, 2.dp.toPx()), size, CornerRadius(8.dp.toPx()))
            val t = war?.piece("spellbook-skilllinetab")
            if (t != null) drawWar(t, 0f, 0f, size.width, size.height) else drawRoundRect(War.woodDark, cornerRadius = CornerRadius(8.dp.toPx()))
            val img = war?.icon(icon)
            val x = 9.dp.toPx(); val y = 12.dp.toPx(); val s = 32.dp.toPx()
            if (img != null) clipPath(roundPath(x, y, x + s, y + s, 6.dp.toPx())) { drawWar(img, x, y, s, s) }
            else drawGlyphFor(icon, x + s / 2, y + s / 2, s * 0.8f)
            if (on) drawRoundRect(War.gold, Offset(5.dp.toPx(), 8.dp.toPx()), Size(40.dp.toPx(), 40.dp.toPx()), CornerRadius(8.dp.toPx()), style = Stroke(2.5.dp.toPx()))
        }
    }
}

/** A dark rounded strip over the scene (the tooltip, the placing bar, the loading line): warm glass with a soft gold edge. */
@Composable
fun GlassStrip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.shadow(4.dp, shape).clip(shape).background(War.glass).border(1.5.dp, War.goldSoft, shape).padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

/** The tooltip strip: one line on the dark glass. */
@Composable
fun Tooltip(text: String, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null, dismissLabel: String = "Got it") {
    GlassStrip(modifier.heightIn(min = 40.dp)) {
        Text(text, style = warText(13.sp, War.ink, true), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(vertical = 6.dp))
        if (onDismiss != null) Box(Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp).warPress(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Text(dismissLabel, style = warText(13.sp, War.gold, true))
        }
    }
}

/** A dark rounded panel over the scene (the tool cluster): warm glass, a gold edge, the wooden corbel at its top corner. */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, corbel: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val war = LocalWar.current
    val shape = RoundedCornerShape(WarSize.R_PANEL.dp)
    Box(
        modifier.drawWithContent {
            drawContent()
            if (corbel) war?.piece("house-chest-corbel-topright")?.let { c ->
                val w = 46.dp.toPx(); drawWar(c, size.width - w * 0.78f, -8.dp.toPx(), w, w * c.height / c.width)
            }
        },
    ) {
        Box(Modifier.matchParentSize().shadow(6.dp, shape).clip(shape).background(War.glass).border(1.5.dp, Color(0xFF8A6A3A), shape))
        Box(Modifier.padding(7.dp), content = content)
    }
}

/**
 * The catalogue bar's ground: the housing panel's stone warmed and darkened, rounded at the top, the wooden header along its
 * top edge and the housing editor's corbels with leaves at its two top corners.
 */
@Composable
fun WoodBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val war = LocalWar.current
    val shape = RoundedCornerShape(topStart = WarSize.R_PANEL.dp, topEnd = WarSize.R_PANEL.dp)
    Box(modifier) {
        Box(Modifier.matchParentSize().shadow(10.dp, shape).clip(shape).drawBehind {
            val stone = war?.piece("housing-basic-panel--stone-background")
            if (stone != null) drawWar(stone, 0f, 0f, size.width, size.height)
            drawRect(if (stone != null) Color(0xAA22170E) else War.woodDark)
        })
        Canvas(Modifier.matchParentSize()) {
            val head = war?.piece("housing-basic-container-woodheader")
            val hh = 18.dp.toPx()
            if (head != null) {
                // only the header's own strip, over the bar's top edge
                drawImage(head, IntOffset.Zero, IntSize(head.width, head.height), IntOffset(0, (-6).dp.toPx().roundToInt()), IntSize(size.width.roundToInt(), hh.roundToInt()), filterQuality = FilterQuality.Medium)
            } else drawRect(War.wood, Offset(0f, -4.dp.toPx()), Size(size.width, 8.dp.toPx()))
            val b = 58.dp.toPx()
            war?.piece("decor-abilitybar-bookend-left")?.let { drawWar(it, 2.dp.toPx(), -b + 14.dp.toPx(), b, b) }
            war?.piece("decor-abilitybar-bookend-right")?.let { drawWar(it, size.width - b - 2.dp.toPx(), -b + 14.dp.toPx(), b, b) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = WarSize.GUTTER.dp, vertical = WarSize.GUTTER.dp), content = content)
    }
}

/** The scene's name on the parchment ribbon (the game's neutral frame ribbon), in Morpheus. */
@Composable
fun NameRibbon(text: String, modifier: Modifier = Modifier) {
    val war = LocalWar.current
    Box(
        modifier.height(48.dp).drawBehind {
            val rib = war?.piece("ui-frame-neutral-ribbon")
            if (rib != null) drawNine(rib, "ui-frame-neutral-ribbon", 0.55f * density)
            else drawRoundRect(War.parchment, cornerRadius = CornerRadius(10.dp.toPx()))
        }.padding(horizontal = 30.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = warTitle(19.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/**
 * The slim top bar over the scene: a soft dark fade from the top, Back on the left, the name on its ribbon, then [actions]
 * (round buttons) on the right. The name gives way first on a narrow screen.
 */
@Composable
fun TopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Box(modifier.fillMaxWidth().height(WarSize.TOP_BAR.dp + 20.dp).background(Brush.verticalGradient(listOf(Color(0xC81E140C), Color(0x661E140C), Color.Transparent)))) {
        Row(
            Modifier.fillMaxWidth().height(WarSize.TOP_BAR.dp).padding(horizontal = WarSize.GUTTER.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundIconButton(Face(glyph = WarIcons.BACK), onBack, description = "Back")
            NameRibbon(title, Modifier.weight(1f, fill = false).widthIn(min = 120.dp, max = 380.dp))
            Spacer(Modifier.weight(0.001f))
            actions()
        }
    }
}

/**
 * A thin warm scroll thumb at a list's right edge (the game's minimal scroll bar), drawn while it can scroll. Drawn over the
 * list (it never takes room), and it glides with the scroll (ScrollThumb counts the first row's part scrolled off) instead
 * of stepping a row at a time.
 */
fun Modifier.warScrollbar(state: LazyListState): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull() ?: return@drawWithContent
    // rows fully or partly shown, by their part of the viewport (a half-shown last row counts half)
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    val shownRows = info.visibleItemsInfo.sumOf { (minOf(it.offset + it.size, info.viewportEndOffset) - maxOf(it.offset, info.viewportStartOffset)).coerceAtLeast(0).toDouble() / it.size.coerceAtLeast(1) }
    val visible = if (info.visibleItemsInfo.size >= info.totalItemsCount && shownRows >= info.totalItemsCount - 0.01) info.totalItemsCount else shownRows.toInt().coerceAtLeast(1)
    val (top, len) = ScrollThumb.of(first.index, (info.viewportStartOffset - first.offset).coerceAtLeast(0), first.size, visible, info.totalItemsCount) ?: return@drawWithContent
    if (viewport <= 0) return@drawWithContent
    val w = 6.dp.toPx(); val x = size.width - w - 1.dp.toPx()
    val h = (size.height * len).coerceAtLeast(24.dp.toPx()).coerceAtMost(size.height)
    drawRoundRect(Color(0x40785A32), Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
    drawRoundRect(War.amber, Offset(x, ((size.height - h) * (top / (1f - len).coerceAtLeast(0.0001f))).coerceIn(0f, size.height - h)), Size(w, h), CornerRadius(w / 2))
}

/** A row of a list on parchment: a warm cream card (deeper and gold-edged when [selected]), an icon at its start. */
@Composable
fun WarRow(onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, icon: String? = null, onLongPress: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val war = LocalWar.current
    val shape = RoundedCornerShape(WarSize.R_CARD.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).shadow(2.dp, shape).clip(shape)
            .background(if (selected) War.parchmentDeep else Color(0xF0F6EBCF)).border(if (selected) 2.dp else 1.dp, if (selected) War.gold else War.edge, shape)
            .warPress(onLongPress = onLongPress, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Canvas(Modifier.size(40.dp)) {
            val img = war?.icon(icon)
            if (img != null) clipPath(roundPath(0f, 0f, size.width, size.height, 10.dp.toPx())) { drawWar(img, 0f, 0f, size.width, size.height) }
            else { drawRoundRect(War.woodDark, cornerRadius = CornerRadius(10.dp.toPx())); drawGlyphFor(icon, center.x, center.y, size.minDimension * 0.6f) }
        }
        content()
    }
}

/**
 * A screen of the editor's menus in the build-mode look (the projects, the cast, the scenes): the top bar's Back and
 * name ribbon with [actions], the content on a spellbook page over the warm dark ground, never taller than the screen.
 */
@Composable
fun WarScreen(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A1C12), Color(0xFF140D08))))) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, onBack, actions = actions)
            BookPage(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), content = content)
        }
    }
}

/** A dialog on a parchment page in the middle of the screen (a name to type, a question): never wider than the screen. */
@Composable
fun WarDialog(onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Overlay(onDismiss) {
        BookPage(modifier.widthIn(max = 520.dp).padding(16.dp), ribbon = false) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** The plain round shape, for callers that clip to it. */
val WarCircle = CircleShape
