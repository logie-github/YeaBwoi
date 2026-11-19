package com.logie.wifecraft.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.Choice
import com.logie.wifecraft.creator.Race
import com.logie.wifecraft.creator.CreatorModel
import com.logie.wifecraft.creator.ResolvedLook
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.creator.Tables
import com.logie.wifecraft.engine.Placement
import com.logie.wifecraft.engine.SceneView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The character creator, Wrath++'s screen (WrathppCreate.lua) in Compose: its race and sex step and its Customize step,
 * drawn from Shadowlands' own pieces (ArtAtlas) at Shadowlands' own sizes and places (CreatorLayout.kt, every number with
 * its Lua line). Behaviour is the Lua's: eased and flick-carried lists, the tap-for-list popout in as many columns as fit,
 * a drag that turns the character, a pinch that brings it nearer, the view buttons, the zoom slider.
 *
 * It draws only the interface: the 3D character is the [SceneView] it is given (placed behind it by the host), whose camera
 * and the character's facing it moves each frame. Not run on a phone, nor on a desktop: written against the pinned
 * CreatorModel / Catalog contracts and checked only through CreatorLayoutTest.
 */

/** @param onLook the look to draw whenever the appearance changes (resolved off the main thread); the host dresses its actor with it. */
@Composable
fun CreatorScreen(
    model: CreatorModel,
    art: WowArt,
    scene: SceneView,
    tables: Tables,
    onFinish: (name: String, Appearance) -> Unit,
    onBack: () -> Unit,
    atlas: ArtAtlas? = null,
    initialName: String = "",
    actorKey: String = "creator",
    randomName: (() -> String)? = null,
    onPreviewRace: (Int) -> Unit = {},
    transmog: com.logie.wifecraft.creator.TransmogCatalog? = null,
    transmogStatus: String? = null,
    onLoadTransmog: () -> Unit = {},
    onLook: (ResolvedLook) -> Unit = {},
) {
    val context = LocalContext.current
    val art2 = atlas ?: remember { ArtAtlas.open(context) }
    LaunchedEffect(art2) { art2.load() }

    var transmogOpen by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(1) }
    var rev by remember { mutableIntStateOf(0) }                 // bumped after every change to the model (it is not observable)
    var category by remember { mutableIntStateOf(-1) }
    var name by remember { mutableStateOf(initialName) }
    var open by remember { mutableStateOf<OpenList?>(null) }
    var tick by remember { mutableIntStateOf(0) }                 // read by what moves every frame
    val scroll = remember { ListScroll(CL.OPT_H, CL.OPT_H) }
    val view = remember { ViewState() }

    val previewRace = remember(rev) { model.appearance.raceId }
    LaunchedEffect(previewRace) { onPreviewRace(previewRace) }
    androidx.activity.compose.BackHandler(transmogOpen) { transmogOpen = false }

    // the look, resolved where the table work is cheap to wait for
    LaunchedEffect(rev, step) {
        val look = withContext(Dispatchers.Default) { runCatching { model.resolved(tables) }.getOrNull() }
        if (look != null) onLook(look)
    }

    // every frame: the lists easing, the character turning, the view coming nearer, and the scene's camera
    LaunchedEffect(Unit) {
        var last = 0L
        var lastZoom = -1f; var lastFocus = -1f; var lastFacing = Float.NaN
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0f else min((t - last) / 1e9f, 0.1f)
                last = t
                scroll.update(dt)
                view.update(dt)
                if (view.zoom != lastZoom || view.focusY != lastFocus || view.facing != lastFacing) {
                    lastZoom = view.zoom; lastFocus = view.focusY; lastFacing = view.facing
                    scene.frame = CreatorCamera.pose(view.zoom, view.focusY) to
                        mapOf(actorKey to Placement(0f, 0f, 0f, Math.toRadians(view.facing.toDouble()).toFloat()))
                }
                tick++
            }
        }
    }

    val words = remember(tables) { CreatorStrings(tables) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val k = constraints.maxHeight / CL.SCREEN_H           // pixels in one of the screen's units
        val width = constraints.maxWidth / k
        val ui = Ui(k, density, art2, art.friz, width, words)
        val female = model.appearance.sex == Sex.FEMALE
        val cats = bodyLast(model.categories()) { it.name }
        val cat = cats.firstOrNull { it.id == category } ?: cats.firstOrNull()

        fun changed() { rev++ }
        fun closePopout() { open = null }

        // the middle of the screen: a finger across it turns the character, two fingers bring it nearer
        ui.SpinArea(spinRect(width), view)

        ui.Vignette()

        if (step == 1) {
            ui.RaceStep(model, female, onChosen = { changed() }, onLeave = onBack, onCustomize = {
                step = 2; closePopout(); scroll.scrollTo(0f)
            })
        } else {
            val rows = remember(rev, cat?.id, model.appearance.raceId, model.appearance.sex) { if (cat != null) model.rows(cat.id) else emptyList() }
            LaunchedEffect(rows.size) { scroll.setContent(contentHeight(rows.size)) }

            // the name
            ui.NameBox(width, name, { name = it }, randomName?.let { r -> { name = r() } })

            // the dice and the category buttons
            val dice = diceRect(cats.size, width)
            ui.SmallButton(dice, "charactercreate-icon-dice", onTap = { model.randomize(); closePopout(); changed() })
            cats.forEachIndexed { i, c ->
                ui.RoundButton(categoryRect(i, cats.size, width), CL.CATEGORY, categoryPiece(c.name, female, art2.has("charactercreate-icon-customize-figure"), c.id == cat?.id),
                    on = c.id == cat?.id, off = false, flip = false) {
                    category = c.id; closePopout(); scroll.scrollTo(0f)
                    val (z, f) = categoryView(c.zoom); view.setZoom(z, f)
                    changed()
                }
            }

            ui.ViewPanel(view) { tick }

            // a tap anywhere but the list closes an open popout (the Lua's catcher, 1243-1247); the list itself stays under a finger
            if (open != null) ui.PopoutCatcher { closePopout() }
            ui.OptionList(width, rows, scroll, { tick }, open, onOpen = { open = it }, onChange = { closeIt: Boolean ->
                if (closeIt) closePopout()
                changed()
            }, model = model)

            open?.let { o ->
                val row = rows.getOrNull(o.row)
                if (row != null && !row.option.isSwitch) {
                    ui.Popout(row.option.choices, row.chosen, o.box, shift = { tick; (o.scrollAtOpen - scroll.offset) * k }, onPick = { c ->
                        model.choose(row.option.id, c.id); closePopout(); changed()
                    })
                }
            }
        }

        // the foot (Customize's Back and Finish; the first step's Back and Customize are in RaceStep)
        if (step == 2) {
            ui.RedButton(navRect(false, width), ui.words.back, arrow = "back") { step = 1; closePopout(); view.setZoom(1f, CL.RESET_FOCUS) }
            ui.RedButton(navRect(true, width), ui.words.finish, arrow = null) { onFinish(name, model.appearance) }
            if (!transmogOpen) {
                androidx.compose.material3.Button(
                    onClick = { closePopout(); transmogOpen = true; onLoadTransmog() },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                ) { Text("Transmog") }
            }
            if (transmogOpen) {
                val wide = maxWidth >= 820.dp
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { transmogOpen = false } })
                val panelModifier = if (wide) Modifier.align(Alignment.CenterEnd).width(350.dp).fillMaxHeight(0.92f)
                    else Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f)
                androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    TransmogPanel(
                        transmog, model.appearance.clothing.takeIf { model.appearance.gear }.orEmpty(),
                        transmogStatus, onLoadTransmog,
                        onChange = { model.setClothing(it); changed() },
                        onClose = { transmogOpen = false },
                        modifier = panelModifier,
                    )
                }
            }
        }
    }
}

/** The Lua's catcher (1243-1247): while a popout is open, a tap on anything but it and the option list only closes it. */
@Composable
private fun Ui.PopoutCatcher(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } })
}

/**
 * A list of choices opened under (or over) a row's box (screen units, y down): the box as it was on the screen when it opened and the
 * option list's scroll then, so that the popout moves with the row as the list scrolls (the Lua anchors it to the box, 1343).
 */
data class OpenList(val row: Int, val box: Rect, val scrollAtOpen: Float = 0f)

// ---- the drawing kit ---------------------------------------------------------------------------------------------------------

private class Ui(val k: Float, val density: Density, val atlas: ArtAtlas, val font: FontFamily?, val widthU: Float, val words: CreatorStrings) {
    fun px(u: Float): Int = (u * k).roundToInt()
    fun dp(u: Float): Dp = with(density) { (u * k).toDp() }
    fun sp(size: Float): TextUnit = with(density) { (CL.u(size) * k).toSp() }
    fun piece(name: String): ArtPiece? = atlas.piece(name)
}

private val goldColour = Color(CL.GOLD_R, CL.GOLD_G, CL.GOLD_B)
private val greyColour = Color(CL.GREY, CL.GREY, CL.GREY)

private fun rgb(v: Int) = Color(0xFF000000.toInt() or (v and 0xFFFFFF))

/** SetAtlas + SetPoint: [p] drawn at (x, y) w x h px; [tint] as a vertex colour; [add] additive; [flip] mirrored (the Horde's races). */
private fun DrawScope.art(p: ArtPiece?, x: Float, y: Float, w: Float, h: Float, tint: Color? = null, alpha: Float = 1f, flip: Boolean = false, add: Boolean = false) {
    p ?: return
    val draw = {
        drawImage(
            p.image, IntOffset.Zero, IntSize(p.image.width, p.image.height),
            IntOffset(x.roundToInt(), y.roundToInt()), IntSize(max(1, w.roundToInt()), max(1, h.roundToInt())),
            alpha = alpha, colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.Modulate) },
            blendMode = if (add) BlendMode.Plus else BlendMode.SrcOver, filterQuality = FilterQuality.Medium,
        )
    }
    if (flip) withTransform({ scale(-1f, 1f, Offset(x + w / 2f, y + h / 2f)) }) { draw() } else draw()
}

/** A box at a rectangle of the screen (units). */
@Composable
private fun Ui.At(r: Rect, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Box(Modifier.offset { IntOffset(px(r.x), px(r.y)) }.size(dp(r.w), dp(r.h)).then(modifier)) { content() }
}

/** Text as Shadowlands' fonts have it: Friz Quadrata, gold or white, a shadow one unit down and right; [outline] draws it outlined too. */
@Composable
private fun Ui.Label(text: String, size: Float, colour: Color, modifier: Modifier = Modifier, outline: Boolean = false, align: TextAlign = TextAlign.Center) {
    val shadow = Shadow(Color.Black, Offset(CL.u(CL.SHADOW) * k, CL.u(CL.SHADOW) * k), 0f)
    val base = TextStyle(fontFamily = font, fontSize = sp(size), color = colour, shadow = shadow, textAlign = align)
    Box(modifier, contentAlignment = when (align) { TextAlign.Left, TextAlign.Start -> Alignment.CenterStart; TextAlign.Right, TextAlign.End -> Alignment.CenterEnd; else -> Alignment.Center }) {
        if (outline) androidx.compose.material3.Text(text, style = base.copy(color = Color.Black, drawStyle = Stroke(width = CL.u(2f) * k), shadow = null), maxLines = 1, softWrap = false)
        androidx.compose.material3.Text(text, style = base, maxLines = 1, softWrap = false)
    }
}

/** A press, held while down, tapped on release. */
@Composable
private fun press(onHeld: (Boolean) -> Unit = {}, onTap: () -> Unit = {}): Modifier {
    val held by rememberUpdatedState(onHeld)
    val tapped by rememberUpdatedState(onTap)
    return Modifier.pointerInput(Unit) {
        detectTapGestures(onPress = { held(true); tryAwaitRelease(); held(false) }, onTap = { tapped() })
    }
}

/** CharCustomizeSmallButtonTemplate (Lua 204-220): a grey square with a gold picture in its middle. */
@Composable
private fun Ui.SmallButton(r: Rect, icon: String, onHeld: (Boolean) -> Unit = {}, onTap: () -> Unit = {}) {
    var held by remember { mutableStateOf(false) }
    At(r, press({ held = it; onHeld(it) }, onTap)) {
        Canvas(Modifier.fillMaxSize()) {
            art(piece(if (held) "common-button-square-gray-down" else "common-button-square-gray-up"), 0f, 0f, size.width, size.height)
            val w = CL.u(CL.SMALL_ICON_W) * k; val h = CL.u(CL.SMALL_ICON_H) * k
            val nudge = if (held) CL.u(CL.SMALL_HELD_NUDGE) * k else 0f
            val x = (size.width - w) / 2f + nudge; val y = (size.height - h) / 2f + nudge
            art(piece(icon), x, y, w, h)
            if (held) art(piece(icon), x, y, w, h, alpha = 0.4f, add = true)
        }
    }
}

/** CharCustomizeMaskedButtonTemplate (Lua 226-261): the round picture (already trimmed: "<piece>.<kind>") in its ring, the gold ring when chosen. */
@Composable
private fun Ui.RoundButton(
    r: Rect, spec: CL.Round, icon: String?, on: Boolean, off: Boolean, flip: Boolean,
    interactive: Boolean = true, pressed: Boolean = false, ringScale: Float = 1f, onTap: () -> Unit = {},
) {
    var own by remember { mutableStateOf(false) }
    val held = own || pressed
    // (a button in a scrolling column is touched by the column's own gesture: it only shows the press)
    At(r, if (interactive) press({ own = it }, { if (!off) onTap() }) else Modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val dim = if (off) CL.DIM_OFF else 1f
            if (icon != null) art(piece("$icon.${spec.kind}"), 0f, 0f, size.width, size.height, tint = Color(dim, dim, dim), flip = flip)
            val ringName = if (off && atlas.has(spec.ring + "-disabled")) spec.ring + "-disabled" else spec.ring
            val rw = CL.u(spec.ringW) * k * ringScale; val rh = CL.u(spec.ringH) * k * ringScale
            val rx = (size.width - rw) / 2f; val ry = (size.height - rh) / 2f
            art(piece(ringName), rx, ry, rw, rh)
            if (on) {
                val c = CL.u(spec.chosen) * k * ringScale
                art(piece(CL.RING_SELECT), (size.width - c) / 2f, (size.height - c) / 2f, c, c)
            }
            if (held) art(piece(ringName), rx, ry, rw, rh, alpha = 0.5f, add = true)
        }
    }
}

/** ThreeSliceButtonTemplate "128-RedButton" (Lua 265-301): ends at the button's height, the middle between, pressed and disabled pieces, a caption 22 high with its arrow. */
@Composable
private fun Ui.RedButton(r: Rect, caption: String, arrow: String?, enabled: Boolean = true, onTap: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    // the caption's width as this font sets it, to put the arrow its gap beside the text (the Lua anchors it to the text itself)
    val measurer = rememberTextMeasurer()
    val textPx = remember(caption, font, k, measurer) {
        measurer.measure(caption, TextStyle(fontFamily = font, fontSize = sp(CL.FONT_BUTTON)), maxLines = 1, softWrap = false).size.width.toFloat()
    }
    At(r, press({ held = it }, { if (enabled) onTap() })) {
        val state = if (!enabled) "-disabled" else if (held) "-pressed" else ""
        val lean = if (arrow != null) (CL.u(CL.ARROW_W) + CL.u(CL.ARROW_GAP)) / 2f * (if (arrow == "forward") -1f else 1f) else 0f
        val nudge = if (held) CL.u(CL.HELD_NUDGE) else 0f
        Canvas(Modifier.fillMaxSize()) {
            val scale = r.h / CL.RED_PIECE_H
            val lw = CL.u(CL.RED_LEFT * scale) * k; val rw = CL.u(CL.RED_RIGHT * scale) * k
            art(piece("_128-redbutton-center$state"), lw, 0f, size.width - lw - rw, size.height)
            art(piece("128-redbutton-left$state"), 0f, 0f, lw, size.height)
            art(piece("128-redbutton-right$state"), size.width - rw, 0f, rw, size.height)
            if (held) art(piece("128-redbutton-highlight"), 0f, 0f, size.width, size.height, add = true)
            if (arrow != null) {
                val aw = CL.u(CL.ARROW_W) * k; val ah = CL.u(CL.ARROW_H) * k
                // the arrow after the text of the button that goes on, before the text of the one that goes back
                val textHalf = textPx / 2f
                val gap = CL.u(CL.ARROW_GAP) * k
                val cx = size.width / 2f + (lean + nudge) * k
                val x = if (arrow == "forward") cx + textHalf + gap else cx - textHalf - gap - aw
                art(piece("common-icon-${arrow}arrow" + if (enabled) "" else "-disable"), x, (size.height - ah) / 2f + nudge * k, aw, ah)
            }
        }
        Label(caption, CL.FONT_BUTTON, goldColour, Modifier.fillMaxSize().offset { IntOffset(px(lean + nudge), px(nudge)) }, outline = true)
    }
}

/** Shadowlands darkens the screen's edges (Lua 791-808). */
@Composable
private fun Ui.Vignette() {
    Canvas(Modifier.fillMaxSize()) {
        val h = CL.VIGNETTE_TOP * k; val b = CL.VIGNETTE_BOTTOM * k; val s = CL.vignetteSide(widthU) * k
        art(piece("charactercreate-vignette-top"), 0f, 0f, size.width, h)
        art(piece("charactercreate-vignette-bottom"), 0f, size.height - b, size.width, b)
        art(piece("charactercreate-vignette-sides"), 0f, 0f, s, size.height)
        art(piece("charactercreate-vignette-sides"), size.width - s, 0f, s, size.height, flip = true)
    }
}

// ---- the first step: races and sexes --------------------------------------------------------------------------------------------

@Composable
private fun Ui.RaceStep(model: CreatorModel, female: Boolean, onChosen: () -> Unit, onLeave: () -> Unit, onCustomize: () -> Unit) {
    val catalog = model.catalog
    val me = model.appearance
    // the factions' crests and names in the top corners (Lua 813-827)
    for (alliance in listOf(true, false)) {
        val logo = Rect(if (alliance) CL.u(CL.HEADER_X) else widthU - CL.u(CL.HEADER_X) - CL.u(CL.HEADER_W), CL.u(CL.HEADER_Y), CL.u(CL.HEADER_W), CL.u(CL.HEADER_H))
        At(logo) { Canvas(Modifier.fillMaxSize()) { art(piece(if (alliance) "charactercreate-icon-alliance" else "charactercreate-icon-horde"), 0f, 0f, size.width, size.height) } }
        val textW = CL.u(180f)
        val tx = if (alliance) logo.right - CL.u(CL.HEADER_NAME_DX) else logo.x + CL.u(CL.HEADER_NAME_DX) - textW
        At(Rect(tx, logo.y + (logo.h - CL.u(24f)) / 2f + CL.u(CL.HEADER_NAME_DY), textW, CL.u(24f))) {
            Label(if (alliance) words.alliance else words.horde, CL.FONT_LARGE, goldColour, Modifier.fillMaxSize(), align = if (alliance) TextAlign.Left else TextAlign.Right)
        }
    }
    // the races down the sides: the classic ones first, the allied after them on the same row grid, as many to a column as the screen's
    // height holds, scaled down (never below 7 mm) before a column is added, nothing scrolls (RaceLayout, from the real width and height)
    val sides = listOf(true, false).map { al -> raceOrder(catalog.races.filter { isAllianceSide(it.key, it.alliance) == al }) { it.key } }
    RaceButtons(sides[0], sides[1], model, female, onChosen)
    // the sexes at the top (Lua 1405-1413)
    for ((i, sex) in listOf(Sex.MALE, Sex.FEMALE).withIndex()) {
        val chosen = me.sex == sex
        val has = catalog.model(me.raceId, sex) != null
        RoundButton(sexRect(i, widthU), CL.SEX, sexPiece(sex == Sex.FEMALE, chosen), on = chosen, off = !has, flip = false) { model.setSex(sex); onChosen() }
    }
    RedButton(navRect(false, widthU), words.back, arrow = "back") { onLeave() }
    RedButton(navRect(true, widthU), words.customize, arrow = "forward") { onCustomize() }
}

/** The races of both sides (Lua 1460-1494) where [raceLayout] puts them: a round button and its name in a cell, a tap on the cell chooses the race. */
@Composable
private fun Ui.RaceButtons(alliance: List<Race>, horde: List<Race>, model: CreatorModel, female: Boolean, onChosen: () -> Unit) {
    val catalog = model.catalog
    val me = model.appearance
    val dpi = density.density * 160f
    val layout = remember(widthU, k, dpi, alliance.map { it.key }, horde.map { it.key }) {
        raceLayout(widthU, k, raceCounts(alliance) { it.key }, raceCounts(horde) { it.key }, dpi)
    }
    var held by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    val meNow by rememberUpdatedState(me)
    val safe = layout.safe
    fun raceOf(c: RaceCell): Race? = (if (c.alliance) alliance else horde).getOrNull(c.index)
    fun hit(p: Offset): RaceCell? = layout.at(p.x / k + safe.x, p.y / k + safe.y)
    At(safe, Modifier.pointerInput(layout) {
        detectTapGestures(
            onPress = { p -> held = hit(p)?.let { it.alliance to it.index }; tryAwaitRelease(); held = null },
            onTap = { p ->
                val race = hit(p)?.let { raceOf(it) }
                if (race != null && catalog.model(race.id, meNow.sex) != null) { model.setRace(race.id); onChosen() }
            },
        )
    }) {
        for (c in layout.cells) {
            val race = raceOf(c) ?: continue
            val has = catalog.model(race.id, me.sex) != null
            val piece = racePiece(race.key, female) { atlas.has(it) }
            val button = Rect(c.button.x - safe.x, c.button.y - safe.y, c.button.w, c.button.h)
            RoundButton(button, if (c.alliance) CL.RACE_ALLIANCE else CL.RACE_HORDE, piece, on = race.id == me.raceId, off = !has, flip = !c.alliance,
                interactive = false, pressed = held == (c.alliance to c.index), ringScale = layout.scale)
            At(Rect(c.name.x - safe.x, c.name.y - safe.y, c.name.w, c.name.h)) {
                Label(race.name, CL.FONT_MED * max(0.75f, layout.scale), goldColour, Modifier.fillMaxSize().offset { IntOffset(px(CL.u(CL.RACE_NAME_DX)), 0) }, outline = true)
            }
        }
    }
}

// ---- the Customize step ------------------------------------------------------------------------------------------------------------

/** "Name" and its box (Lua 857-883): the grey entry box's three pieces, the text 20 high in its middle, a dice for a name drawn at random. */
@Composable
private fun Ui.NameBox(width: Float, name: String, onName: (String) -> Unit, random: (() -> Unit)?) {
    val title = nameTitleRect(width); val box = nameBoxRect(width)
    At(title) { Label(words.name, CL.FONT_LARGE, Color.White, Modifier.fillMaxSize()) }
    At(box) {
        Canvas(Modifier.fillMaxSize()) {
            val l = CL.u(CL.NAME_LEFT_PIECE) * k; val r = CL.u(CL.NAME_RIGHT_PIECE) * k
            art(piece("common-gray-button-entrybox-center"), l, 0f, size.width - l - r, size.height)
            art(piece("common-gray-button-entrybox-left"), 0f, 0f, l, size.height)
            art(piece("common-gray-button-entrybox-right"), size.width - r, 0f, r, size.height)
        }
        Box(Modifier.size(dp(CL.NAME_BOX_W.toFloat() - CL.NAME_TEXT_INSET), dp(CL.NAME_BOX_H.toFloat())).offset { IntOffset(px(CL.u(CL.NAME_TEXT_INSET / 2f)), 0) }, contentAlignment = Alignment.Center) {
            BasicTextField(
                value = name, onValueChange = onName, singleLine = true,
                textStyle = TextStyle(fontFamily = font, fontSize = sp(CL.NAME_TEXT_H.toFloat()), color = Color.White, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(goldColour),
            )
        }
    }
    if (random != null) SmallButton(Rect(box.x - CL.u(CL.SMALL_BUTTON), box.y + (box.h - CL.u(CL.SMALL_BUTTON)) / 2f + CL.u(1f), CL.u(CL.SMALL_BUTTON), CL.u(CL.SMALL_BUTTON)), "charactercreate-icon-dice", onTap = random)
}

/** The view's five buttons, the zoom slider and the four views under them (Lua 974-1059). */
@Composable
private fun Ui.ViewPanel(view: ViewState, tick: () -> Int) {
    SmallButton(viewSmallRect(0), "common-icon-undo", onTap = { view.reset() })
    SmallButton(viewSmallRect(1), "common-icon-zoomout", onTap = { view.nudgeZoom(-CL.ZOOM_STEP) })
    SmallButton(viewSmallRect(2), "common-icon-zoomin", onTap = { view.nudgeZoom(CL.ZOOM_STEP) })
    SmallButton(viewSmallRect(3), "common-icon-rotateleft", onHeld = { view.heldTurn = if (it) -1 else 0 })
    SmallButton(viewSmallRect(4), "common-icon-rotateright", onHeld = { view.heldTurn = if (it) 1 else 0 })
    // the slider: a track and its gold edge in plain colours, the chosen ring as the thumb
    val s = sliderRect()
    At(s, Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fun set(x: Float) { view.view = null; view.setZoom(1f + (CL.ZOOM_MAX - 1f) * (x / size.width).coerceIn(0f, 1f)) }
            set(down.position.x); down.consume()
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                if (c.changedToUp()) break
                set(c.position.x); c.consume()
            }
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            tick()
            val th = CL.u(CL.SLIDER_TRACK_H) * k; val eh = CL.u(CL.SLIDER_EDGE_H) * k
            drawRect(Color(0.06f, 0.06f, 0.08f, 0.9f), Offset(0f, (size.height - th) / 2f), androidx.compose.ui.geometry.Size(size.width, th))
            drawRect(Color(0.55f, 0.45f, 0.2f, 0.9f), Offset(0f, (size.height - eh) / 2f), androidx.compose.ui.geometry.Size(size.width, eh))
            val thumb = CL.u(CL.SLIDER_THUMB) * k
            val at = ((view.zoomTarget - 1f) / (CL.ZOOM_MAX - 1f)).coerceIn(0f, 1f)
            art(piece(CL.RING_SELECT), at * (size.width - thumb), (size.height - thumb) / 2f, thumb, thumb)
        }
    }
    for ((i, v) in VIEWS.withIndex()) {
        var held by remember { mutableStateOf(false) }
        At(viewButtonRect(i), press({ held = it }, { view.showView(v.name) })) {
            Canvas(Modifier.fillMaxSize()) {
                tick()
                art(piece("charactercreate-customize-dropdownbox" + if (view.view == v.name) "-open" else ""), 0f, 0f, size.width, size.height)
                if (held) art(piece("charactercreate-customize-dropdownbox-hover"), 0f, 0f, size.width, size.height, alpha = 0.3f, add = true)
            }
            Label(v.name, CL.FONT_MED, goldColour, Modifier.fillMaxSize())
        }
    }
}

/** Middle of the screen: a finger turns the character, two fingers pinch it nearer (the Lua's wheel, 963-972). */
@Composable
private fun Ui.SpinArea(r: Rect, view: ViewState) {
    At(r, Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            view.fingerDown()
            var lastX = down.position.x; var lastY = down.position.y
            var lastT = down.uptimeMillis; var lastSpan = 0f
            while (true) {
                val e = awaitPointerEvent()
                val live = e.changes.filter { it.pressed }
                if (live.isEmpty() || e.changes.all { it.changedToUp() }) break
                if (live.size >= 2) {
                    val span = hypot(live[0].position.x - live[1].position.x, live[0].position.y - live[1].position.y)
                    if (lastSpan > 0f && span > 0f) view.pinch(span / lastSpan)
                    lastSpan = span
                    view.spin = 0f
                    lastX = live[0].position.x; lastY = live[0].position.y; lastT = live[0].uptimeMillis
                } else {
                    lastSpan = 0f
                    val c = live[0]
                    val dt = max(0f, (c.uptimeMillis - lastT) / 1000f)
                    view.fingerMoved(c.position.x - lastX, -(c.position.y - lastY) / k, dt)
                    lastX = c.position.x; lastY = c.position.y; lastT = c.uptimeMillis
                }
                e.changes.forEach { it.consume() }
            }
            view.fingerUp()
        }
    })
}

/**
 * The options of a category (Lua 1099-1228): one line each in Shadowlands' order, its name, then the choice on between a
 * previous and a next arrow (a tap on the choice opens the list of them all), or a check box for a switch. The list scrolls
 * under a finger, eased, a tap counting only if the finger didn't drag.
 */
@Composable
private fun Ui.OptionList(
    width: Float, rows: List<CreatorModel.Row>, scroll: ListScroll, tick: () -> Int, open: OpenList?,
    onOpen: (OpenList?) -> Unit, onChange: (closePopout: Boolean) -> Unit, model: CreatorModel,
) {
    val panel = optionPanel(width)
    var held by remember { mutableStateOf<Pair<Int, RowPart>?>(null) }
    val geometry = remember(rows.size) { List(rows.size) { RowGeometry(it) } }
    // the gesture below lives across recompositions: it reads these as they are now
    val rowsNow by rememberUpdatedState(rows)
    val geometryNow by rememberUpdatedState(geometry)
    val openNow by rememberUpdatedState(open)

    fun tap(rowIndex: Int, part: RowPart) {
        val rows = rowsNow; val geometry = geometryNow; val open = openNow
        val row = rows.getOrNull(rowIndex) ?: return
        val o = row.option
        // a greyed row (the Lua's "later" kind: the body options not imported, none for this race) is shown, not chosen from
        if (!row.enabled) { if (open != null) onOpen(null); return }
        when (part) {
            RowPart.PREVIOUS -> { model.step(o.id, -1); onChange(true) }
            RowPart.NEXT -> { model.step(o.id, 1); onChange(true) }
            RowPart.CHOICE -> {
                if (open?.row == rowIndex) onOpen(null)
                else {
                    val g = geometry[rowIndex]
                    // where the box is on the screen now
                    onOpen(OpenList(rowIndex, Rect(panel.x + g.choice.x, panel.y + g.choice.y - scroll.offset, g.choice.w, g.choice.h), scroll.offset))
                }
            }
            RowPart.CHECK -> {
                val on = o.choices.indexOfFirst { it.id == row.chosen.id } == 1
                val pick = o.choices.getOrNull(if (on) 0 else 1) ?: return
                model.choose(o.id, pick.id); onChange(true)
            }
            RowPart.NONE -> if (open != null) onOpen(null)
        }
    }

    val tapNow by rememberUpdatedState<(Int, RowPart) -> Unit> { i, p -> tap(i, p) }

    At(panel, Modifier.clipToBounds().pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startOffset = scroll.offset
            scroll.press(-down.position.y / k)
            val x = down.position.x / k; val y = down.position.y / k + startOffset
            val ri = rowAt(y, rowsNow.size)
            val part = if (ri >= 0) geometryNow[ri].hit(x, y, rowsNow[ri].option.isSwitch) else RowPart.NONE
            held = if (part != RowPart.NONE) ri to part else null
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                if (c.changedToUp()) { scroll.release(c.uptimeMillis / 1000f); break }
                scroll.drag(-c.position.y / k, c.uptimeMillis / 1000f)
                // (a list opened stays open and moves with its row)
                if (scroll.moved) { held = null; c.consume() }
            }
            if (scroll.tapped()) { if (ri >= 0) tapNow(ri, part) else if (openNow != null) onOpen(null) }
            held = null
        }
    }) {
        // the content, moved by the list's offset every frame
        Box(Modifier.offset { tick(); IntOffset(0, -px(scroll.offset)) }) {
            rows.forEachIndexed { i, row ->
              // greyed as the Lua's showRow does it: the whole line at 0.45, its arrows disabled, the box saying why
              Box(Modifier.graphicsLayer { alpha = if (row.enabled) 1f else 0.45f }) {
                val g = geometry[i]
                val o = row.option
                val at = o.choices.indexOfFirst { it.id == row.chosen.id } + 1
                // the option's name, white, ending 34 before the previous arrow
                At(g.name) { Label(o.name, CL.FONT_MED, Color.White, Modifier.fillMaxSize(), align = TextAlign.Right) }
                if (!o.isSwitch) {
                    At(g.choice) {
                        Canvas(Modifier.fillMaxSize()) {
                            val dropdown = piece("charactercreate-customize-dropdownbox" + if (open?.row == i) "-open" else "")
                            if (dropdown != null) art(dropdown, 0f, 0f, size.width, size.height)
                            else drawRect(Color(0.08f, 0.08f, 0.1f, 0.95f))
                        }
                        val c = row.chosen
                        if (!row.enabled && row.why != null) Label(row.why, CL.FONT_SMALL, goldColour, Modifier.fillMaxSize())
                        else {
                            if (c.swatch1 != null) Swatches(c.swatch1, c.swatch2, CL.SWATCH_W.toFloat(), CL.SWATCH_H.toFloat(), 1f)
                            Label(choiceLabel(c.name, c.swatch1 != null, at), CL.FONT_SMALL, goldColour, Modifier.fillMaxSize())
                        }
                    }
                    for ((r, nameOf) in listOf(g.previous to "backbutton", g.next to "nextbutton")) {
                        val isHeld = row.enabled && held == (i to (if (nameOf == "backbutton") RowPart.PREVIOUS else RowPart.NEXT))
                        val state = if (!row.enabled && atlas.has("charactercreate-customize-$nameOf-disabled")) "-disabled" else if (isHeld) "-down" else ""
                        At(r) { Canvas(Modifier.fillMaxSize()) { art(piece("charactercreate-customize-$nameOf$state"), 0f, 0f, size.width, size.height) } }
                    }
                } else {
                    val on = at == 2
                    At(g.check) { Canvas(Modifier.fillMaxSize()) { checkBox(this@OptionList, on, held == (i to RowPart.CHECK), k) } }
                    At(Rect(g.check.right + CL.u(CL.CHECK_TEXT_GAP), g.check.y, CL.u(160f), g.check.h)) {
                        Label(row.chosen.name.ifEmpty { if (on) "On" else "Off" }, CL.FONT_SMALL, goldColour, Modifier.fillMaxSize(), align = TextAlign.Left)
                    }
                }
              }
            }
        }
        // the scroll bar's gold stroke
        Canvas(Modifier.fillMaxSize()) {
            tick()
            val (length, top) = scroll.bar() ?: return@Canvas
            drawRect(Color(CL.GOLD_R, CL.GOLD_G, CL.GOLD_B, 0.7f), Offset(size.width - 4f, top * k), androidx.compose.ui.geometry.Size(4f, length * k))
        }
    }
}

/**
 * The switch's check box (Lua 1144-1151): the game's own pictures, Interface\Buttons\UI-CheckBox-Up, -Down while pressed and -Check over it
 * when on (the 9.2.7 atlas tables have no piece for it: creator_art.json's "files"). Plain drawing only until they are fetched.
 */
private fun DrawScope.checkBox(ui: Ui, on: Boolean, held: Boolean, k: Float) {
    val up = ui.piece("checkbox-up"); val down = ui.piece("checkbox-down")
    if (up != null && down != null) {
        art(if (held) down else up, 0f, 0f, size.width, size.height)
        if (on) art(ui.piece("checkbox-check"), 0f, 0f, size.width, size.height)
        return
    }
    drawRect(if (held) Color(0.17f, 0.16f, 0.12f, 0.95f) else Color(0.08f, 0.08f, 0.1f, 0.95f))
    drawRect(goldColour.copy(alpha = 0.8f), style = Stroke(CL.u(1.5f) * k))
    if (on) {
        val w = size.width; val h = size.height
        val s = CL.u(2.5f) * k
        drawLine(goldColour, Offset(w * 0.22f, h * 0.52f), Offset(w * 0.42f, h * 0.74f), s)
        drawLine(goldColour, Offset(w * 0.42f, h * 0.74f), Offset(w * 0.80f, h * 0.26f), s)
    }
}

/** A colour's brush strokes (Lua 303-314, 1210): the first, and where there is a second, a half of it and the second 36 x 8 on it. */
@Composable
private fun Ui.Swatches(c1: Int, c2: Int?, w: Float, h: Float, scale: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val sw = CL.u(w) * k * scale; val sh = CL.u(h) * k * scale
        val x = (size.width - sw) / 2f; val y = (size.height - sh) / 2f
        art(piece(if (c2 != null) "charactercreate-customize-palette-half" else "charactercreate-customize-palette"), x, y, sw, sh, tint = rgb(c1))
        if (c2 != null) art(piece("charactercreate-customize-palette"), x + CL.u(CL.SWATCH2_DX) * k * scale, y + CL.u(CL.SWATCH2_DY) * k * scale, CL.u(CL.SWATCH2_W) * k * scale, CL.u(CL.SWATCH2_H) * k * scale, tint = rgb(c2))
    }
}

/**
 * The list of an option's choices (Lua 1232-1347, SelectionPopoutTemplate): one column to 10, two to 24, three to 36, four
 * beyond (and more where the screen has not the lines), filled downwards column by column; each entry its number, then its
 * name or its colour, the one on in gold. Under the box, or over it where there is more room.
 */
@Composable
private fun Ui.Popout(choices: List<Choice>, chosen: Choice, box: Rect, shift: () -> Float, onPick: (Choice) -> Unit) {
    val (under, room) = popoutSide(box.y, box.bottom, CL.SCREEN_H, choices.size)
    val lay = fillPopout(choices.size, named = choices.any { it.name.isNotEmpty() }, coloured = choices.any { it.swatch1 != null }, room = room)
    val where = popoutPlace(under, box.y, box.bottom, box.right, lay.width, lay.height)
    val frame = Rect(where.left, where.top, lay.width, lay.height)
    // all of it moves with the list as it scrolls: the popout hangs from its row's box (the Lua anchors it there)
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = shift() }) {
    // a tap on its frame between the entries does nothing, as on the Lua's popout
    At(Rect(frame.x - CL.u(CL.NS_OUT_X), frame.y - CL.u(CL.NS_OUT_Y), frame.w + 2 * CL.u(CL.NS_OUT_X), frame.h + 2 * CL.u(CL.NS_OUT_Y)),
        Modifier.pointerInput(Unit) { detectTapGestures { } })
    // the frame: nine pieces set out from it, the middle inside
    Canvas(Modifier.fillMaxSize()) {
        for (s in nineSlice(frame)) {
            val p = piece(s.piece)
            if (p != null) art(p, s.rect.x * k, s.rect.y * k, s.rect.w * k, s.rect.h * k)
            else if (s.piece.endsWith("center")) drawRect(Color(0.04f, 0.04f, 0.06f, 0.96f), Offset(frame.x * k, frame.y * k), androidx.compose.ui.geometry.Size(frame.w * k, frame.h * k))
        }
    }
    for (cell in lay.cells) {
        val c = choices[cell.index]
        val on = c.id == chosen.id
        val colour = if (on) goldColour else greyColour
        At(Rect(frame.x + cell.x, frame.y + cell.y, cell.w, cell.h), press(onTap = { onPick(c) })) {
            if (c.swatch1 != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val sx = (CL.p(CL.POP_NUMBER_X) + CL.p(CL.POP_NUMBER_W)) * k
                    if (on) art(piece("charactercreate-customize-palette-selected"), sx - CL.p(CL.POP_MARK_DX) * k, (size.height - CL.p(CL.POP_MARK_H) * k) / 2f, CL.p(CL.POP_MARK_W) * k, CL.p(CL.POP_MARK_H) * k)
                    val sw = CL.p(CL.SWATCH_W) * k; val sh = CL.p(CL.SWATCH_H) * k
                    art(piece(if (c.swatch2 != null) "charactercreate-customize-palette-half" else "charactercreate-customize-palette"), sx, (size.height - sh) / 2f, sw, sh, tint = rgb(c.swatch1))
                    if (c.swatch2 != null) art(piece("charactercreate-customize-palette"), sx + CL.p(CL.POP_SWATCH2_DX) * k, (size.height - sh) / 2f + CL.p(CL.POP_SWATCH2_DY) * k, CL.p(CL.SWATCH2_W) * k, CL.p(CL.SWATCH2_H) * k, tint = rgb(c.swatch2))
                }
            }
            At(Rect(CL.p(CL.POP_NUMBER_X), 0f, CL.p(CL.POP_NUMBER_W), cell.h)) {
                Label((cell.index + 1).toString(), CL.FONT_SMALL * CL.POP, colour, Modifier.fillMaxSize(), align = TextAlign.Left)
            }
            if (c.swatch1 == null && c.name.isNotEmpty()) {
                At(Rect(CL.p(CL.POP_NUMBER_X) + CL.p(CL.POP_NUMBER_W), 0f, CL.p(lay.details - CL.POP_NUMBER_W), cell.h)) {
                    Label(c.name, CL.FONT_SMALL * CL.POP, colour, Modifier.fillMaxSize(), align = TextAlign.Left)
                }
            }
        }
    }
    }
}
