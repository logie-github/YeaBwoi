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
