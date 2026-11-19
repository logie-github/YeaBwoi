package com.logie.wifecraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

// The WoW-like menus (W40): the game's own window (its metal frame, its title, the red close X, its tabs along the bottom),
// its red buttons stacked as the Game Menu has them, its dropdown lists (the tooltip's dark box, plain rows with small icons,
// check boxes), its settings rows (gold labels, the minimal check box and slider, the dropdown button), and the action bar's
// framed slots. Pieces: WowArt's (the metal frame, the red button: LocalArt) and the kit's (LocalWar: ui_art.json). Every one
// draws plainly in the same colours until its picture is here. Touch targets are 48 dp or more.

private val Ink = Color.White
private val Dim = Color(0xFF9D9D9D)
private val Red = Color(0xFFFF5A46)
private val Edge = Color(0xFF96784A)
private val Tip = Color(0xEB08080C)

/** The window's body: the dark stone of the game's frames, or the spellbook's parchment (the Library). */
enum class WindowBody { ROCK, PARCHMENT, TRANSLUCENT }

/** The metal frame over the whole draw area: its corners [c] px, edges stretched; plain when WowArt isn't here. */
fun DrawScope.drawMetalFrame(art: WowArt?, c: Float) {
    if (art?.cornerTL == null) {
        drawRect(Color(0xFF8A7A5A), style = Stroke(3.dp.toPx())); return
    }
    piece(art.edgeTop, c, 0f, size.width - 2 * c, c)
    piece(art.edgeBottom, c, size.height - c, size.width - 2 * c, c)
    piece(art.edgeLeft, 0f, c, c, size.height - 2 * c)
    piece(art.edgeRight, size.width - c, c, c, size.height - 2 * c)
    piece(art.cornerTL, 0f, 0f, c, c); piece(art.cornerTR, size.width - c, 0f, c, c)
    piece(art.cornerBL, 0f, size.height - c, c, c); piece(art.cornerBR, size.width - c, size.height - c, c, c)
}

/** The red close X (the game's), 36 dp drawn in a 48 dp touch box. */
@Composable
fun WowCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val war = LocalWar.current
    Box(modifier.size(48.dp).semantics { contentDescription = "Close" }.warPress(sound = "close", onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(36.dp)) {
            val p = war?.piece("redbutton-exit")
            if (p != null) drawWar(p, 0f, 0f, size.width, size.height)
            else {
                drawRoundRect(Color(0xFF8A1A10), cornerRadius = CornerRadius(6.dp.toPx()))
                drawRoundRect(Color(0xFFB8A27A), cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                drawGlyph(WarGlyph.CLOSE, center.x, center.y, size.minDimension * 0.6f, War.gold)
            }
        }
    }
}

/**
 * A WoW window: the metal frame over dark stone (or the spellbook's parchment), the [title] in gold on its head, the red close
 * X at its top right ([onClose] null: none), [tabs] hanging under its bottom edge. Its size is the caller's ([modifier]):
 * never taller than the screen; what is inside scrolls.
 */
@Composable
fun WowWindow(
    title: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    body: WindowBody = WindowBody.ROCK,
    tabs: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val art = LocalArt.current
    val war = LocalWar.current
    Column(modifier) {
        Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            Box(Modifier.matchParentSize().padding(10.dp).shadow(10.dp, RoundedCornerShape(4.dp)).drawBehind {
                when (body) {
                    WindowBody.PARCHMENT -> {
                        val page = war?.piece("spellbook-background-evergreen-left")
                        if (page != null) drawNine(page, "spellbook-background-evergreen-left", 0.42f * density) else drawRect(War.parchment)
                    }
                    WindowBody.ROCK -> {
                        val rock = art?.rock
                        if (rock != null) piece(rock, 0f, 0f, size.width, size.height)
                        drawRect(if (rock != null) Color(0xB0000000) else Color(0xEE15110C))
                    }
                    WindowBody.TRANSLUCENT -> drawRect(Color(0xAE100D0B))
                }
                // the head: a darker band behind the title
                drawRect(Color(if (body == WindowBody.PARCHMENT) 0x46000000 else 0x78000000), Offset(2.dp.toPx(), 0f), Size(size.width - 4.dp.toPx(), 38.dp.toPx()))
            })
            Canvas(Modifier.matchParentSize()) { drawMetalFrame(art, 36.dp.toPx()) }
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp)) {
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                    Text(title, style = frizStyle(17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 52.dp))
                    if (onClose != null) WowCloseButton(onClose, Modifier.align(Alignment.CenterEnd).offset(x = 6.dp))
                }
                Column(Modifier.padding(horizontal = 10.dp).padding(top = 6.dp), content = content)
            }
        }
        if (tabs != null) Row(Modifier.padding(start = 24.dp).offset(y = (-6).dp), horizontalArrangement = Arrangement.spacedBy(2.dp), content = tabs)
    }
}

/** A tab hanging under a window: gold words, white on the chosen one, which stands a little taller. */
@Composable
fun WowTab(label: String, chosen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val war = LocalWar.current
    Box(
        modifier.widthIn(min = 112.dp).height(48.dp).semantics { contentDescription = label }.warPress(onClick = onClick),
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.matchParentSize().padding(bottom = if (chosen) 6.dp else 12.dp)) {
            val l = war?.piece(if (chosen) "uiframe-activetab-left" else "uiframe-tab-left")
            val m = war?.piece(if (chosen) "_uiframe-activetab-center" else "_uiframe-tab-center")
            val r = war?.piece(if (chosen) "uiframe-activetab-right" else "uiframe-tab-right")
            if (l != null && m != null && r != null) {
                val k = size.height / l.height
                val lw = l.width * k; val rw = r.width * k
                drawWar(m, lw - 1, 0f, size.width - lw - rw + 2, size.height)
                drawWar(l, 0f, 0f, lw, size.height); drawWar(r, size.width - rw, 0f, rw, size.height)
            } else {
                drawRoundRect(Color(0xFF2A2018), cornerRadius = CornerRadius(6.dp.toPx()))
                drawRoundRect(if (chosen) War.gold else Edge, cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            }
        }
        Text(label, style = frizStyle(14.sp, if (chosen) Ink else WowColors.gold), maxLines = 1,
            modifier = Modifier.padding(horizontal = 18.dp).padding(top = if (chosen) 12.dp else 9.dp))
    }
}

/** The game's red button (128-RedButton, WowArt): gold words, 48 dp high, as wide as [modifier] makes it. */
@Composable
fun RedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, textSize: TextUnit = 15.sp) {
    val click = LocalSounds.current
    val art = LocalArt.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pieces = when { !enabled -> art?.redDisabled; pressed -> art?.redPressed; else -> art?.red }
    Box(
        modifier.heightIn(min = 48.dp).clickable(source, null, enabled = enabled, role = Role.Button, onClick = { click("click"); onClick() })
            .drawBehind {
                if (pieces?.left == null || pieces.center == null || pieces.right == null) {
                    drawRoundRect(if (enabled) Color(0xFF7A0E0A) else Color(0xFF3A3A3A), cornerRadius = CornerRadius(4.dp.toPx()))
                    drawRoundRect(Color(0xFF5A5A5A), cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
                    return@drawBehind
                }
                val k = size.height / pieces.left.size.height
                val lw = pieces.left.size.width * k
                val rw = (pieces.right.size.width * k).coerceAtMost(size.width - lw)
                piece(pieces.center, lw - 1, 0f, size.width - lw - rw + 2, size.height)
                piece(pieces.left, 0f, 0f, lw, size.height)
                val rp = pieces.right
                val srcW = (rw / k).roundToInt().coerceIn(1, rp.size.width)
                drawImage(rp.image, IntOffset(rp.offset.x + rp.size.width - srcW, rp.offset.y), IntSize(srcW, rp.size.height),
                    IntOffset((size.width - rw).roundToInt(), 0), IntSize(rw.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
            }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = frizStyle(textSize, if (enabled) WowColors.gold else WowColors.grey), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Buttons stacked as the Game Menu has them: one column, or [cols] side by side when the screen is short (the cover screen on
 * its side), each [width] wide.
 */
@Composable
fun <T> RedStack(buttons: List<StackButton<T>>, label: (T) -> String, cols: Int, width: Dp, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    val rows = ChromeLayout.rows(buttons.size, cols)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (c in 0 until cols) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in buttons.drop(c * rows).take(rows)) RedButton(label(b.item), { onPick(b.item) }, Modifier.width(width), enabled = b.enabled)
        }
    }
}

/** The Game Menu: the metal frame, its title, the red buttons stacked ([Menus.game]), over the dimmed screen. */
@Composable
fun GameMenuPanel(buttons: List<StackButton<GameMenuItem>>, cols: Int, onPick: (GameMenuItem) -> Unit, onDismiss: () -> Unit, title: String = "Game Menu", subtitle: String? = null) {
    Overlay(onDismiss) {
        WowWindow(title, onClose = null, modifier = Modifier.width((cols * 240 + (cols - 1) * 10 + 48).dp)) {
            if (subtitle != null) Text(subtitle, style = frizStyle(12.sp, WowColors.grey), maxLines = 1, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp))
            RedStack(buttons, { it.label }, cols, 240.dp, onPick, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 4.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------------ dropdowns and tooltips

/** The game's tooltip box: near black, a thin grey edge, its corner pieces. */
@Composable
fun WowTooltipBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val war = LocalWar.current
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier.shadow(6.dp, shape).clip(shape).background(Tip).border(1.4.dp, Color(0xFF78787D), shape).drawBehindCorners(war),
        content = content,
    )
}

private fun Modifier.drawBehindCorners(war: WarArt?): Modifier = this.then(Modifier.drawBehind {
    val s = 10.dp.toPx()
    war?.piece("tooltip-nineslice-cornertopleft")?.let { drawWar(it, 0f, 0f, s, s) }
    war?.piece("tooltip-nineslice-cornertopright")?.let { drawWar(it, size.width - s, 0f, s, s) }
    war?.piece("tooltip-nineslice-cornerbottomleft")?.let { drawWar(it, 0f, size.height - s, s, s) }
    war?.piece("tooltip-nineslice-cornerbottomright")?.let { drawWar(it, size.width - s, size.height - s, s, s) }
})

/** A small icon in a dropdown row: an item icon (squared, rounded) or a gold glyph; its drawn glyph until it is here. */
@Composable
fun MenuIcon(key: String?, enabled: Boolean = true, size: Dp = 26.dp) {
    val war = LocalWar.current
    Canvas(Modifier.size(size)) {
        key ?: return@Canvas
        val a = if (enabled) 1f else 0.4f
        val icon = war?.icon(key)
        if (icon != null) clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, this@Canvas.size.width, this@Canvas.size.height, CornerRadius(4.dp.toPx()))) }) {
            drawWar(icon, 0f, 0f, this@Canvas.size.width, this@Canvas.size.height, a)
        } else {
            val p = war?.piece(key)
            if (p != null) drawFitted(p, center.x, center.y, this.size.minDimension, a) else drawGlyphFor(key, center.x, center.y, this.size.minDimension * 0.8f, War.gold, a)
        }
    }
}

/** The settings' check box (the game's minimal one) with its gold tick. */
@Composable
fun WowCheckBox(checked: Boolean, enabled: Boolean = true, size: Dp = 26.dp) {
    val war = LocalWar.current
    Canvas(Modifier.size(size)) {
        val a = if (enabled) 1f else 0.45f
        val box = war?.piece("checkbox-minimal")
        if (box != null) drawWar(box, 0f, 0f, this.size.width, this.size.height, a)
        else { drawRoundRect(Color(0xFF15110C), cornerRadius = CornerRadius(4.dp.toPx())); drawRoundRect(Edge, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx())) }
        if (checked) {
            val t = war?.piece("checkmark-minimal")
            if (t != null) drawWar(t, 0f, 0f, this.size.width, this.size.height, a) else drawGlyph(WarGlyph.CHECK, center.x, center.y, this.size.minDimension * 0.8f, War.gold, a)
        }
    }
}

/**
 * A WoW dropdown list on the tooltip's dark box: an optional gold [title], then plain rows of white words with small icons
 * (or check boxes), dimmed when they can't be used, Delete in red, thin rules between the groups. Never taller than
 * [maxHeight]: its rows scroll.
 */
@Composable
fun WowDropdownList(title: String?, rows: List<MenuRow>, onPick: (MenuRow) -> Unit, modifier: Modifier = Modifier, maxHeight: Dp = 600.dp) {
    WowTooltipBox(modifier) {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (title != null) Text(title, style = frizStyle(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp).height(EditorMenus.TITLE.dp).padding(top = 8.dp))
            val list = rememberLazyListState()
            LazyColumn(Modifier.heightIn(max = maxHeight).warScrollbar(list), list) {
                itemsIndexed(rows, key = { _, r -> r.action.name }) { _, r ->
                    Column {
                        if (r.ruleBefore) Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(Color(0xFF5A5A5F)))
                        MenuRowView(r) { onPick(r) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRowView(r: MenuRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(EditorMenus.ROW.dp).warPress(r.enabled, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (r.checked != null) WowCheckBox(r.checked, r.enabled, 24.dp) else MenuIcon(r.icon, r.enabled)
        Text(r.label, style = frizStyle(15.sp, when { !r.enabled -> Dim; r.danger -> Red; else -> Ink }), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A WoW-style text button: gold words (red for [danger]), 48 dp to touch, no frame (a context row's Rename, Delete...). */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).warPress(enabled, onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = frizStyle(15.sp, when { !enabled -> Dim; danger -> Red; else -> WowColors.gold }), maxLines = 1)
    }
}

// ------------------------------------------------------------------------------------------------ settings rows

/** A check box row: the box, the gold label after it; the whole row is 48 dp to touch. */
@Composable
fun WowCheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, detail: String? = null) {
    Row(modifier.heightIn(min = 48.dp).warPress(enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WowCheckBox(checked, enabled)
        Column {
            Text(label, style = frizStyle(15.sp, if (enabled) WowColors.gold else Dim), maxLines = 2)
            if (detail != null) Text(detail, style = frizStyle(11.sp, Dim), maxLines = 2)
        }
    }
}

/** The game's minimal slider: its track and its button, [value] in [range]; drags report [onChange]. */
@Composable
fun WowSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier) {
    val war = LocalWar.current
    val change by rememberUpdatedState(onChange)
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    fun at(x: Float, w: Float, k: Float) = range.start + ((x - k / 2) / (w - k)).coerceIn(0f, 1f) * span
    Canvas(
        modifier.height(48.dp)
            .pointerInput(range) { detectTapGestures { o -> change(at(o.x, size.width.toFloat(), 30.dp.toPx())) } }
            .pointerInput(range) { detectHorizontalDragGestures { ch, _ -> ch.consume(); change(at(ch.position.x, size.width.toFloat(), 30.dp.toPx())) } },
    ) {
        val k = 30.dp.toPx(); val th = 14.dp.toPx(); val y = size.height / 2; val x0 = k / 2; val w = size.width - k
        val l = war?.piece("minimal_sliderbar_left"); val m = war?.piece("_minimal_sliderbar_middle"); val r = war?.piece("minimal_sliderbar_right")
        if (l != null && m != null && r != null) {
            val lw = l.width * th / l.height
            drawWar(l, x0, y - th / 2, lw, th); drawWar(m, x0 + lw, y - th / 2, w - 2 * lw, th); drawWar(r, x0 + w - lw, y - th / 2, lw, th)
        } else {
            drawRoundRect(Color(0xFF15110C), Offset(x0, y - th / 3), Size(w, th * 2 / 3), CornerRadius(th / 3))
            drawRoundRect(Edge, Offset(x0, y - th / 3), Size(w, th * 2 / 3), CornerRadius(th / 3), style = Stroke(1.dp.toPx()))
        }
        val kx = x0 + w * frac
        val b = war?.piece("minimal_sliderbar_button")
        if (b != null) drawWar(b, kx - k / 2, y - k * b.height / b.width / 2, k, k * b.height / b.width) else drawCircle(Color(0xFFB0B0B0), k / 3, Offset(kx, y))
    }
}

/** A slider row: the gold label, the slider, the value in white. */
@Composable
fun WowSliderRow(label: String, value: Int, onChange: (Int) -> Unit, valueText: String = if (value == 0) "Off" else "$value%", range: IntRange = 0..100, labelW: Dp = 140.dp) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = frizStyle(15.sp), maxLines = 1, modifier = Modifier.width(labelW))
        WowSlider(value.toFloat(), { onChange(it.roundToInt()) }, range.first.toFloat()..range.last.toFloat(), Modifier.weight(1f))
        Text(valueText, style = frizStyle(14.sp, Ink), maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
    }
}

/** The dropdown's button: the chosen words on a dark field, the game's arrow button at its end. */
@Composable
fun WowDropdownButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, open: Boolean = false) {
    val war = LocalWar.current
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier.height(48.dp).clip(shape).background(Color(0xEB0E0A08)).border(1.3.dp, Edge, shape).warPress(onClick = onClick).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = frizStyle(14.sp, Ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Canvas(Modifier.size(38.dp)) {
            val a = war?.piece(if (open) "common-dropdown-a-button-open" else "common-dropdown-a-button")
            if (a != null) drawWar(a, 0f, 0f, size.width, size.height)
            else {
                val p = Path().apply { moveTo(size.width * 0.3f, size.height * 0.4f); lineTo(size.width * 0.7f, size.height * 0.4f); lineTo(size.width * 0.5f, size.height * 0.64f); close() }
                drawPath(p, War.gold)
            }
        }
    }
}

/**
 * A dropdown: its button showing [current], and when tapped the list of [options] (label, count or null) under it in the
 * tooltip's box; picking one closes it.
 */
@Composable
fun <T> WowDropdown(current: String, options: List<Pair<T, String>>, onPick: (T) -> Unit, modifier: Modifier = Modifier, headers: Set<Int> = emptySet(), maxHeight: Dp = 360.dp) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        WowDropdownButton(current, { open = !open }, Modifier.fillMaxWidth(), open)
        if (open) Popup(alignment = Alignment.TopStart, offset = IntOffset(0, 0), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
            WowTooltipBox(Modifier.widthIn(min = 220.dp, max = 420.dp).padding(top = 50.dp)) {
                val list = rememberLazyListState()
                LazyColumn(Modifier.heightIn(max = maxHeight).padding(vertical = 4.dp).warScrollbar(list), list) {
                    itemsIndexed(options) { i, (value, label) ->
                        if (i in headers) Text(label, style = frizStyle(13.sp, WowColors.gold), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        else Box(Modifier.fillMaxWidth().height(48.dp).warPress { open = false; onPick(value) }.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                            Text(label, style = frizStyle(15.sp, if (label == current) WowColors.gold else Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** The search box: rounded dark field, the magnifying glass, the words in white, Clear. */
@Composable
fun WowSearchBox(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val war = LocalWar.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier.height(48.dp).clip(shape).background(Color(0xEB0E0A08)).border(1.3.dp, Edge, shape).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val img = war?.piece(WarIcons.SEARCH)
            if (img != null) drawFitted(img, center.x, center.y, size.minDimension) else drawGlyph(WarGlyph.SEARCH, center.x, center.y, size.minDimension, War.gold)
        }
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (value.isEmpty()) Text(hint, style = frizStyle(14.sp, Dim, false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onChange, singleLine = true, textStyle = frizStyle(15.sp, Ink, false), cursorBrush = SolidColor(War.gold), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) TextAction("Clear", { onChange("") })
    }
}

/** A line to type on a window's dark body: white words on black, a gold edge while typing. */
@Composable
fun WowTextField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, singleLine: Boolean = true) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier.heightIn(min = 48.dp).clip(shape).background(Color.Black).border(if (focused) 1.5.dp else 1.dp, if (focused) War.gold else Color(0xFF6E6E6E), shape)
            .padding(horizontal = 12.dp, vertical = 13.dp),
    ) {
        if (value.isEmpty()) Text(hint, style = frizStyle(15.sp, Dim, false), maxLines = 1)
        BasicTextField(value, onChange, singleLine = singleLine, textStyle = frizStyle(15.sp, Ink, false), cursorBrush = SolidColor(War.gold),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
    }
}

// ------------------------------------------------------------------------------------------------ the action bar

/** What a slot shows: the housing editor's tool picture ([decor]), an item icon, a gold glyph, or the three dots of More. */
data class SlotFace(val decor: String? = null, val icon: String? = null, val glyph: String? = null, val dots: Boolean = false)

/** The face of each slot. */
fun slotFace(s: ActionSlot): SlotFace = when (s) {
    ActionSlot.SELECT -> SlotFace(decor = "decor-ability-move-default")
    ActionSlot.ROTATE -> SlotFace(decor = "decor-ability-expert-rotate-default")
    ActionSlot.SCALE -> SlotFace(decor = "decor-ability-expert-scale-default")
    ActionSlot.DELETE -> SlotFace(glyph = WarIcons.DELETE)
    ActionSlot.ADD -> SlotFace(icon = "tab.props")
    ActionSlot.MORE -> SlotFace(dots = true)
}

/**
 * An action bar slot (the game's): the icon in the action bar's frame, a gold edge while [lit], dimmed when it can't be used,
 * its name under it. [size] square; a long press says what it does.
 */
@Composable
fun ActionSlotButton(face: SlotFace, label: String, onClick: () -> Unit, size: Dp, modifier: Modifier = Modifier, lit: Boolean = false, enabled: Boolean = true, onLongPress: (() -> Unit)? = null) {
    val war = LocalWar.current
    Column(modifier.width(size), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).semantics { contentDescription = label }.warPress(enabled, onLongPress = onLongPress, onClick = onClick)) {
            Canvas(Modifier.matchParentSize()) {
                val s = this.size.width; val ins = s * 0.1f; val inner = s - 2 * ins
                val a = if (enabled) 1f else 0.42f
                drawRoundRect(Color(WarPalette.SHADOW), Offset(0f, 2.dp.toPx()), this.size, CornerRadius(4.dp.toPx()))
                drawRoundRect(Color(0xFF18120C), Offset(ins, ins), Size(inner, inner), CornerRadius(4.dp.toPx()))
                var drawn = false
                face.decor?.let { n ->
                    war?.piece(n)?.let { p ->
                        val k = (p.width * 0.17f).toInt()
                        clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(ins, ins, ins + inner, ins + inner, CornerRadius(4.dp.toPx()))) }) {
                            drawImage(p, IntOffset(k, k), IntSize(p.width - 2 * k, p.height - 2 * k), IntOffset(ins.roundToInt(), ins.roundToInt()),
                                IntSize(inner.roundToInt(), inner.roundToInt()), alpha = a, filterQuality = FilterQuality.Medium)
                        }
                        drawn = true
                    }
                }
                if (!drawn) face.icon?.let { k -> war?.icon(k)?.let {
                    clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(ins, ins, ins + inner, ins + inner, CornerRadius(4.dp.toPx()))) }) { drawWar(it, ins, ins, inner, inner, a) }
                    drawn = true
                } }
                if (!drawn) face.glyph?.let { g -> war?.piece(g)?.let { drawFitted(it, center.x, center.y, s * 0.5f, a); drawn = true } }
                if (!drawn && face.dots) {
                    for (i in -1..1) drawCircle(War.gold, s * 0.06f, Offset(center.x + i * s * 0.18f, center.y), alpha = a)
                    drawn = true
                }
                if (!drawn) drawGlyphFor(face.glyph ?: face.icon ?: face.decor, center.x, center.y, s * 0.5f, War.gold, a)
                val frame = war?.piece("ui-hud-actionbar-iconframe")
                if (frame != null) drawWar(frame, 0f, 0f, s, s) else drawRoundRect(Edge, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
                if (!enabled) drawRoundRect(Color(0x9614100A), Offset(ins, ins), Size(inner, inner), CornerRadius(4.dp.toPx()))
                if (lit) drawRoundRect(War.gold, Offset(ins * 0.6f, ins * 0.6f), Size(s - 1.2f * ins, s - 1.2f * ins), CornerRadius(5.dp.toPx()), style = Stroke(2.5.dp.toPx()))
            }
        }
        Text(label, style = frizStyle(11.sp, if (enabled) Ink else Dim), maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * The action bar along the bottom: six framed slots ([ActionSlot]) on the housing editor's ability bar, its wooden corbels at
 * the ends. [slot] from ChromeLayout.
 */
@Composable
fun ActionBarView(slot: Dp, content: @Composable RowScope.() -> Unit, modifier: Modifier = Modifier) {
    val war = LocalWar.current
    val shape = RoundedCornerShape(14.dp)
    Box(modifier) {
        Box(Modifier.matchParentSize().shadow(6.dp, shape).clip(shape).background(Color(0xD718110B)).border(1.5.dp, Color(0xFF8A6A3A), shape))
        Canvas(Modifier.matchParentSize()) {
            val b = 46.dp.toPx()
            war?.piece("decor-abilitybar-bookend-left")?.let { drawWar(it, -b * 0.55f, -b * 0.45f, b, b) }
            war?.piece("decor-abilitybar-bookend-right")?.let { drawWar(it, size.width - b * 0.45f, -b * 0.45f, b, b) }
        }
        Row(Modifier.padding(horizontal = 18.dp).padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(ChromeLayout.GAP.dp), content = content)
    }
}

/** The Menu button (top left): the action bar's frame with a gold cog, as the game's micro menu sits in a corner. */
@Composable
fun MenuCogButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val war = LocalWar.current
    Box(modifier.size(52.dp).semantics { contentDescription = "Menu" }.warPress(sound = "open", onClick = onClick)) {
        Canvas(Modifier.matchParentSize()) {
            val s = size.width; val ins = s * 0.1f
            drawRoundRect(Color(WarPalette.SHADOW), Offset(0f, 2.dp.toPx()), size, CornerRadius(4.dp.toPx()))
            drawRoundRect(Color(0xFF281C12), Offset(ins, ins), Size(s - 2 * ins, s - 2 * ins), CornerRadius(4.dp.toPx()))
            drawGlyph(WarGlyph.COG, center.x, center.y, s * 0.52f, War.gold)
            val frame = war?.piece("ui-hud-actionbar-iconframe")
            if (frame != null) drawWar(frame, 0f, 0f, s, s) else drawRoundRect(Edge, cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
    }
}

// ------------------------------------------------------------------------------------------------ lists

/**
 * A row of a list window (Projects, Objects, Cast, Scenes): an icon, the name in gold, a line in white; the chosen row is lit
 * gold and shows its [context] actions (text buttons) under it.
 */
@Composable
fun WowListRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    dim: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    context: (@Composable RowScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(3.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(if (selected) Color(0x24FFD100) else Color(0x0AFFFFFF))
            .border(1.dp, if (selected) Color(0x96FFD100) else Color(0xFF50463A), shape),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).warPress(onLongPress = onLongPress, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) MenuIcon(icon, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = frizStyle(17.sp, if (dim) Dim else WowColors.gold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = frizStyle(12.sp, Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke(this)
        }
        if (selected && context != null) Row(
            Modifier.fillMaxWidth().padding(start = if (icon != null) 54.dp else 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = context,
        )
    }
}

/** A line of words on a window's dark body. */
@Composable
fun WowNote(text: String, modifier: Modifier = Modifier, color: Color = Dim) =
    Text(text, style = frizStyle(13.sp, color), modifier = modifier)

/** Space that pushes what follows to the end of a row or column. */
@Composable
fun RowScope.Fill() = Spacer(Modifier.weight(1f))
