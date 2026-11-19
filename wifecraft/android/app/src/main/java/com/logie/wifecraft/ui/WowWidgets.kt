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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow

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
    ).steadyLines()
}

/**
 * A line as tall as the words' size says, whatever font draws them: the stand-in serif until the game's Friz and Morpheus
 * arrive, then those. Without it every label, row and window grows or shrinks a little the moment the fonts load.
 */
fun TextStyle.steadyLines(): TextStyle = copy(
    lineHeight = 1.25.em,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

@Composable
fun morpheusStyle(size: TextUnit, color: Color = WowColors.gold): TextStyle {
    val art = LocalArt.current
    return TextStyle(fontFamily = art?.morpheus ?: FontFamily.Serif, fontSize = size, color = color, shadow = WowColors.shadow).steadyLines()
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
    val reduced = reduceMotion()
    val sink by animateFloatAsState(MenuMotion.pressScale(pressed && enabled, reduced), tween(MenuMotion.duration(MenuMotion.PRESS_MS, reduced)), label = "press")
    val gate = rememberClickGate()
    Box(
        modifier
            .width(width)
            .height(height)
            .graphicsLayer { scaleX = sink; scaleY = sink }
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = { if (gate.pass()) { click("click"); onClick() } })
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
        Text(text, style = frizStyle(15.sp, if (enabled) WowColors.gold else WowColors.grey), textAlign = TextAlign.Center, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
drawPath(downArrowPath(0f, 0f, size.width, size.height), WowColors.gold)
            }
        }
    }
}

/** The achievement toast, as a story's ending. */
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

/** The Dalaran loading plate from the game's interface pack. */
@Composable
fun WarcraftLoadingArt(modifier: Modifier = Modifier) {
    val art = LocalArt.current?.dalaranArt ?: return
    Canvas(modifier) {
        val scale = maxOf(size.width / art.size.width, size.height / art.size.height)
        val width = art.size.width * scale
        val height = art.size.height * scale
        piece(art, (size.width - width) / 2, (size.height - height) / 2, width, height)
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
        modifier.fillMaxWidth().heightIn(min = 48.dp).warPress(onClick = onClick).padding(vertical = 6.dp, horizontal = 6.dp),
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

// ================================================================================================ the front menus' kit
//
// The title, the scene's setup, Options and Updates in the current game's look (Dragonflight and The War Within's menus):
// dark slate panels with a thin gold trim, rounded buttons that glow when hovered or pressed, gold Friz headings over pale
// words, one set of spacing and type sizes ([WifeTheme]). They draw with the game's fonts (LocalArt) and play the game's
// interface sounds (LocalSounds: open, close, click, tab, check_on/off); every touch target is 48 dp or more and every motion
// is MenuMotion's / FrontMotion's (none under reduced motion). The creator keeps its own look (WarWidgets, RedButton).

/** The front menus' colours, spacing, corners and type, in one place. */
object WifeTheme {
    // colours
    val slate = Color(0xF2171C24)
    val slateDeep = Color(0xF20B0E13)
    val slateRaised = Color(0xFF232A35)
    val trim = Color(0xFFC9A55C)
    val trimSoft = Color(0x59C9A55C)
    val glow = Color(0xFFFFD37A)
    val gold = WowColors.gold
    val text = Color(0xFFF1E9DA)
    val textDim = Color(0xFFA8A49C)
    val danger = Color(0xFFE5574A)
    val good = Color(0xFF86D98A)
    val primaryTop = Color(0xFF9A2416)
    val primaryBottom = Color(0xFF5C0F08)
    val secondaryTop = Color(0xFF2C3440)
    val secondaryBottom = Color(0xFF181D25)
    val track = Color(0xFF0A0D12)

    // spacing, sizes, corners
    val gapXs = 4.dp
    val gapS = 8.dp
    val gapM = 12.dp
    val gapL = 16.dp
    val gapXl = 24.dp
    val touch = 48.dp
    val radius = 14.dp
    val buttonRadius = 10.dp
    val trimWidth = 1.dp

    // type sizes
    val display = 50.sp
    val title = 20.sp
    val heading = 16.sp
    val label = 15.sp
    val body = 14.sp
    val caption = 12.sp

    @Composable fun displayStyle(size: TextUnit = display): TextStyle = morpheusStyle(size)
    @Composable fun titleStyle(): TextStyle = frizStyle(title, gold)
    @Composable fun headingStyle(): TextStyle = frizStyle(heading, gold)
    @Composable fun labelStyle(color: Color = text): TextStyle = frizStyle(label, color)
    @Composable fun bodyStyle(color: Color = text): TextStyle = frizStyle(body, color)
    @Composable fun captionStyle(color: Color = textDim): TextStyle = frizStyle(caption, color, shadow = false)
}

/** How a [WifeButton] looks: the game's red (the main action), slate (the others), or words alone. */
enum class WifeButtonKind { PRIMARY, SECONDARY, QUIET }

/** The slate body with its gold trim and a faint light along its top edge, in [shape]'s corners. */
fun Modifier.wifeSurface(radius: Dp = WifeTheme.radius, raised: Boolean = false): Modifier = this
    .shadow(if (raised) 14.dp else 8.dp, RoundedCornerShape(radius), clip = false)
    .clip(RoundedCornerShape(radius))
    .drawBehind {
        val r = CornerRadius(radius.toPx())
        drawRoundRect(Brush.verticalGradient(listOf(WifeTheme.slate, WifeTheme.slateDeep)), cornerRadius = r)
        // the light from above: a thin pale band under the top edge
        drawRect(Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color.Transparent), endY = 36.dp.toPx()))
        val w = WifeTheme.trimWidth.toPx()
        drawRoundRect(WifeTheme.trim.copy(alpha = 0.85f), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), r, style = Stroke(w))
        // the inner hairline, as the game's frames have
        val i = 4.dp.toPx()
        drawRoundRect(WifeTheme.trimSoft, Offset(i, i), Size(size.width - 2 * i, size.height - 2 * i), CornerRadius((radius.toPx() - i).coerceAtLeast(0f)), style = Stroke(w))
    }

/** A slate panel holding [content] in a column with the theme's padding. */
@Composable
fun WifePanel(modifier: Modifier = Modifier, padding: Dp = WifeTheme.gapL, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.wifeSurface().padding(padding), content = content)
}

/** A gold rule with a small diamond in its middle (under a window's title). */
@Composable
fun WifeDivider(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(9.dp)) {
        val y = size.height / 2; val cx = size.width / 2; val d = 4.dp.toPx()
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, WifeTheme.trim, Color.Transparent)), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        val p = androidx.compose.ui.graphics.Path().apply { moveTo(cx, y - d); lineTo(cx + d, y); lineTo(cx, y + d); lineTo(cx - d, y); close() }
        drawPath(p, WifeTheme.trim)
    }
}

/** A small gold heading over a group of rows, with a hairline after it. */
@Composable
fun WifeSectionHeader(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = WifeTheme.gapS, bottom = WifeTheme.gapXs), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = frizStyle(WifeTheme.caption, WifeTheme.gold).copy(letterSpacing = 1.2.sp), maxLines = 1)
        Box(Modifier.padding(start = WifeTheme.gapS).weight(1f).height(1.dp).background(WifeTheme.trimSoft))
    }
}

/**
 * A rounded button, 48 dp high at least: [kind] the game's red, slate, or words alone; a soft gold glow while hovered (a mouse
 * or a stylus) or pressed, the press's sink (MenuMotion), the interface's [sound], a second tap in the quiet time ignored.
 */
@Composable
fun WifeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    kind: WifeButtonKind = WifeButtonKind.SECONDARY,
    sound: String = "click",
) {
    val play = LocalSounds.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val reduced = reduceMotion()
    val glow by animateFloatAsState(
        when { !enabled -> 0f; pressed -> 1f; hovered -> 0.6f; else -> 0f },
        tween(MenuMotion.duration(FrontMotion.GLOW_MS, reduced)), label = "glow",
    )
    val sink by animateFloatAsState(MenuMotion.pressScale(pressed && enabled, reduced), tween(MenuMotion.duration(MenuMotion.PRESS_MS, reduced)), label = "press")
    val gate = rememberClickGate()
    val shape = RoundedCornerShape(WifeTheme.buttonRadius)
    Box(
        modifier
            .heightIn(min = WifeTheme.touch)
            .graphicsLayer { scaleX = sink; scaleY = sink }
            .hoverable(source, enabled)
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = { if (gate.pass()) { play(sound); onClick() } })
            .drawBehind {
                val r = CornerRadius(WifeTheme.buttonRadius.toPx())
                val a = if (enabled) 1f else 0.45f
                when (kind) {
                    WifeButtonKind.PRIMARY -> drawRoundRect(Brush.verticalGradient(listOf(WifeTheme.primaryTop, WifeTheme.primaryBottom)), cornerRadius = r, alpha = a)
                    WifeButtonKind.SECONDARY -> drawRoundRect(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)), cornerRadius = r, alpha = a)
                    WifeButtonKind.QUIET -> if (glow > 0f) drawRoundRect(Color(0x14FFFFFF), cornerRadius = r)
                }
                if (glow > 0f) drawRoundRect(
                    Brush.radialGradient(listOf(WifeTheme.glow.copy(alpha = 0.32f * glow), Color.Transparent), center = Offset(size.width / 2, size.height), radius = (size.width * 0.6f).coerceAtLeast(1f)),
                    cornerRadius = r,
                )
                if (kind != WifeButtonKind.QUIET) {
                    // the top's sheen and the gold edge (brighter as it glows)
                    drawRoundRect(Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color.Transparent), endY = size.height * 0.5f), cornerRadius = r)
                    val w = (1f + glow).dp.toPx()
                    val edge = if (enabled) WifeTheme.trim.copy(alpha = 0.7f + 0.3f * glow) else WifeTheme.trimSoft
                    drawRoundRect(edge, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), r, style = Stroke(w))
                }
            }
            .clip(shape)
            .padding(horizontal = WifeTheme.gapL, vertical = WifeTheme.gapS),
        contentAlignment = Alignment.Center,
    ) {
        val color = when {
            !enabled -> WowColors.grey
            kind == WifeButtonKind.QUIET -> WifeTheme.gold
            else -> if (glow > 0.5f) Color.White else WifeTheme.gold
        }
        Text(text, style = frizStyle(WifeTheme.label, color), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A window in the front menus' look: a slate panel, its [title] in gold with the rule under it, a round close button ([onClose]
 * null: none), optional [tabs] under the title, the [content] (the caller scrolls it), and a [footer] row of buttons at its foot.
 * It plays the interface's open sound as it first shows; it fades and grows in unless an [Overlay] moves it already.
 */
@Composable
fun WifeWindow(
    title: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tabs: List<String> = emptyList(),
    tab: Int = 0,
    onTab: (Int) -> Unit = {},
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val play = LocalSounds.current
    LaunchedEffect(Unit) { play("open") }
    val enter = if (LocalInMovingOverlay.current) null else rememberEnter()
    Column(
        (if (enter != null) modifier.windowMotion { enter.value } else modifier)
            .wifeSurface(raised = true)
            .padding(start = WifeTheme.gapL, end = WifeTheme.gapS, top = WifeTheme.gapXs, bottom = WifeTheme.gapL),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = WifeTheme.touch), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = WifeTheme.gapS)) {
                Text(title, style = WifeTheme.titleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = WifeTheme.captionStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onClose != null) WifeCloseButton(onClose)
        }
        Column(Modifier.padding(end = WifeTheme.gapS)) {
            WifeDivider(Modifier.padding(bottom = WifeTheme.gapS))
            if (tabs.isNotEmpty()) WifeTabs(tabs, tab, onTab, Modifier.padding(bottom = WifeTheme.gapM))
        }
        Column(Modifier.weight(1f, fill = false).padding(end = WifeTheme.gapS), content = content)
        if (footer != null) Row(
            Modifier.fillMaxWidth().padding(top = WifeTheme.gapM, end = WifeTheme.gapS),
            horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = footer,
        )
    }
}

/** The round close button: a thin gold ring with an X, 48 dp to touch; plays the close sound. */
@Composable
fun WifeCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        modifier.size(WifeTheme.touch).semantics { contentDescription = "Close" }.hoverable(source).warPress(sound = "close", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            val c = center; val r = size.minDimension / 2
            drawCircle(if (hovered) Color(0x33FFD37A) else Color(0x1AFFFFFF), r)
            drawCircle(WifeTheme.trim, r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            val k = r * 0.38f; val w = 1.8.dp.toPx()
            drawLine(WifeTheme.gold, Offset(c.x - k, c.y - k), Offset(c.x + k, c.y + k), w)
            drawLine(WifeTheme.gold, Offset(c.x + k, c.y - k), Offset(c.x - k, c.y + k), w)
        }
    }
}

/**
 * Tabs as the current game's: words side by side, the chosen one white with a gold bar under it that slides between them.
 * Equal parts when they fit, else a strip that scrolls ([FrontLayout.tabsScroll]). Each is 48 dp high.
 */
@Composable
fun WifeTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val reduced = reduceMotion()
    val at by animateFloatAsState(selected.toFloat(), tween(MenuMotion.duration(FrontMotion.TAB_MS, reduced)), label = "tab")
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scroll = FrontLayout.tabsScroll(maxWidth.value, labels.size)
        val row = if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()
        Box(Modifier.drawBehind { drawRect(WifeTheme.trimSoft, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }) {
            Row(row) {
                labels.forEachIndexed { i, label ->
                    val on = i == selected
                    Box(
                        (if (scroll) Modifier.widthIn(min = 96.dp) else Modifier.weight(1f)).height(WifeTheme.touch)
                            .semantics { this.selected = on }
                            .warPress(sound = "tab") { if (!on) onSelect(i) }
                            .drawBehind {
                                // the bar under the chosen tab (sliding across while the choice changes)
                                val d = 1f - kotlin.math.abs(at - i).coerceAtMost(1f)
                                if (d > 0f) {
                                    val w = size.width * 0.7f * d
                                    drawRoundRect(WifeTheme.gold, Offset((size.width - w) / 2, size.height - 3.dp.toPx()), Size(w, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
                                }
                            }
                            .padding(horizontal = WifeTheme.gapS),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = frizStyle(WifeTheme.label, if (on) Color.White else WifeTheme.gold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A slider: a dark track, its gold fill, a round thumb that glows while held; taps and drags set [value] (0..1). 48 dp high. */
@Composable
fun WifeSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, onFinished: () -> Unit = {}) {
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    var held by remember { mutableStateOf(false) }
    val reduced = reduceMotion()
    val glow by animateFloatAsState(if (held) 1f else 0f, tween(MenuMotion.duration(FrontMotion.GLOW_MS, reduced)), label = "thumb")
    val v = value.coerceIn(0f, 1f)
    Canvas(
        modifier.height(WifeTheme.touch)
            .then(if (!enabled) Modifier else Modifier
                .pointerInput(Unit) {
                    val k = 22.dp.toPx()
                    detectTapGestures(onPress = { held = true; tryAwaitRelease(); held = false }) { o ->
                        change(((o.x - k / 2) / (size.width - k)).coerceIn(0f, 1f)); finished()
                    }
                }
                .pointerInput(Unit) {
                    val k = 22.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { held = true },
                        onDragEnd = { held = false; finished() },
                        onDragCancel = { held = false },
                    ) { ch, _ -> ch.consume(); change(((ch.position.x - k / 2) / (size.width - k)).coerceIn(0f, 1f)) }
                }),
    ) {
        val k = 22.dp.toPx(); val th = 6.dp.toPx(); val y = size.height / 2; val x0 = k / 2; val w = size.width - k
        val a = if (enabled) 1f else 0.45f
        drawRoundRect(WifeTheme.track, Offset(x0, y - th / 2), Size(w, th), CornerRadius(th / 2), alpha = a)
        drawRoundRect(WifeTheme.trimSoft, Offset(x0, y - th / 2), Size(w, th), CornerRadius(th / 2), style = Stroke(1.dp.toPx()), alpha = a)
        drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF8A6A2A), WifeTheme.gold)), Offset(x0, y - th / 2), Size(w * v, th), CornerRadius(th / 2), alpha = a)
        val kx = x0 + w * v
        if (glow > 0f) drawCircle(WifeTheme.glow.copy(alpha = 0.3f * glow), k * 0.95f, Offset(kx, y))
        drawCircle(Brush.verticalGradient(listOf(Color(0xFFF6E2B0), Color(0xFFB08A3A)), startY = y - k / 2, endY = y + k / 2), k / 2, Offset(kx, y), alpha = a)
        drawCircle(Color(0xFF3A2A10), k / 2, Offset(kx, y), style = Stroke(1.dp.toPx()), alpha = a)
    }
}

/**
 * A slider row (the Sound page's): the label, the slider, the value. Side by side, or the label over the slider on a narrow
 * row ([FrontLayout.sliderStacked]); the row's height never changes as the value does.
 */
@Composable
fun WifeSliderRow(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    valueText: String = if (value <= 0) "Off" else "$value%",
    range: IntRange = 0..100,
    enabled: Boolean = true,
) {
    val span = (range.last - range.first).coerceAtLeast(1)
    val frac = (value - range.first).toFloat() / span
    val set: (Float) -> Unit = { f -> onChange(range.first + (f * span).roundToInt()) }
    BoxWithConstraints(modifier.fillMaxWidth().semantics { stateDescription = valueText }) {
        val labelStyle = WifeTheme.labelStyle(if (enabled) WifeTheme.text else WifeTheme.textDim)
        if (FrontLayout.sliderStacked(maxWidth.value)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = labelStyle, maxLines = 2, modifier = Modifier.weight(1f))
                    Text(valueText, style = WifeTheme.bodyStyle(WifeTheme.gold), maxLines = 1)
                }
                WifeSlider(frac, set, Modifier.fillMaxWidth(), enabled)
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapM)) {
                Text(label, style = labelStyle, maxLines = 2, modifier = Modifier.weight(0.4f))
                WifeSlider(frac, set, Modifier.weight(0.6f), enabled)
                Text(valueText, style = WifeTheme.bodyStyle(WifeTheme.gold), maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
            }
        }
    }
}

/** A switch row: the label (and a line under it), a rounded switch at its end; the whole row toggles, 48 dp or more. */
@Composable
fun WifeSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, detail: String? = null, enabled: Boolean = true) {
    val reduced = reduceMotion()
    val t by animateFloatAsState(if (checked) 1f else 0f, tween(MenuMotion.duration(FrontMotion.GLOW_MS, reduced)), label = "switch")
    val knob by animateColorAsState(if (checked) WifeTheme.gold else Color(0xFF9A958C), tween(MenuMotion.duration(FrontMotion.GLOW_MS, reduced)), label = "knob")
    Row(
        modifier.fillMaxWidth().heightIn(min = WifeTheme.touch)
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .warPress(enabled, sound = if (checked) "check_off" else "check_on") { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapM),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = WifeTheme.labelStyle(if (enabled) WifeTheme.text else WifeTheme.textDim), maxLines = 2)
            if (detail != null) Text(detail, style = WifeTheme.captionStyle(), maxLines = 3)
        }
        Canvas(Modifier.width(46.dp).height(26.dp)) {
            val a = if (enabled) 1f else 0.45f
            val r = CornerRadius(size.height / 2)
            drawRoundRect(lerpColor(WifeTheme.track, Color(0xFF5A4318), t), cornerRadius = r, alpha = a)
            drawRoundRect(WifeTheme.trim.copy(alpha = 0.5f + 0.5f * t), cornerRadius = r, style = Stroke(1.dp.toPx()), alpha = a)
            val d = size.height - 6.dp.toPx()
            val x = 3.dp.toPx() + d / 2 + (size.width - d - 6.dp.toPx()) * t
            drawCircle(knob, d / 2, Offset(x, size.height / 2), alpha = a)
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

/** A slim progress bar: a dark track, a gold fill, a light running along it while [busy] and nothing can be counted. */
@Composable
fun WifeProgress(fraction: Float, modifier: Modifier = Modifier) {
    val f = fraction.coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(WifeTheme.track, cornerRadius = r)
        if (f > 0f) drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF8A6A2A), WifeTheme.gold)), size = Size(size.width * f, size.height), cornerRadius = r)
        drawRoundRect(WifeTheme.trimSoft, cornerRadius = r, style = Stroke(1.dp.toPx()))
    }
}

/**
 * A picture card (a scene, an NPC): the picture rounded in a slate frame (its initial until it is drawn), the gold trim and a
 * glow when [selected], the name under it in two lines at most. [side] square; dimmed when not [enabled].
 */
@Composable
fun WifeCard(
    name: String,
    picture: ImageBitmap?,
    side: Dp,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val reduced = reduceMotion()
    val lit by animateFloatAsState(if (selected) 1f else 0f, tween(MenuMotion.duration(FrontMotion.GLOW_MS, reduced)), label = "card")
    val shape = RoundedCornerShape(WifeTheme.buttonRadius)
    Column(modifier.width(side).graphicsLayer { alpha = if (enabled || selected) 1f else 0.5f }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(side)
                .semantics { contentDescription = name; this.selected = selected }
                .warPress(enabled || selected, sound = "select", onClick = onClick)
                .drawBehind {
                    if (lit > 0f) drawRoundRect(WifeTheme.glow.copy(alpha = 0.28f * lit), Offset(-3.dp.toPx(), -3.dp.toPx()),
                        Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()), CornerRadius((WifeTheme.buttonRadius + 3.dp).toPx()))
                }
                .clip(shape)
                .background(Brush.verticalGradient(listOf(WifeTheme.slateRaised, WifeTheme.slateDeep)))
                .border(if (selected) 2.dp else 1.dp, if (selected) WifeTheme.gold else WifeTheme.trimSoft, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (picture != null) Image(picture, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            else Text(name.take(1).uppercase(), style = WifeTheme.displayStyle(28.sp).copy(color = WifeTheme.textDim))
        }
        Text(name, style = frizStyle(WifeTheme.caption, if (selected) WifeTheme.gold else WifeTheme.text), maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = WifeTheme.gapXs))
    }
}

/** A rounded chip (a cast member): the name, an x to take it out; 48 dp to touch. */
@Composable
fun WifeChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: String? = "×") {
    val shape = RoundedCornerShape(50)
    Box(modifier.heightIn(min = WifeTheme.touch).warPress(onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(shape).background(WifeTheme.slateRaised).border(1.dp, WifeTheme.trim.copy(alpha = 0.7f), shape)
                .padding(horizontal = WifeTheme.gapM, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text, style = frizStyle(WifeTheme.body, WifeTheme.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            if (trailing != null) Text(trailing, style = frizStyle(WifeTheme.body, WifeTheme.gold), maxLines = 1)
        }
    }
}

/** A line of help on a front-menu panel: dim, wrapping, never clipped. */
@Composable
fun WifeNote(text: String, modifier: Modifier = Modifier, color: Color = WifeTheme.textDim) {
    Text(text, style = frizStyle(WifeTheme.caption, color, shadow = false), modifier = modifier)
}

/** Space between groups of rows. */
@Composable
fun WifeGap(space: Dp = WifeTheme.gapS) = Spacer(Modifier.height(space))
