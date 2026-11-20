package com.logie.wifecraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.CardGrid
import com.logie.wifecraft.creator.CardOrder
import com.logie.wifecraft.creator.ClothingPiece
import com.logie.wifecraft.creator.ClothingSet
import com.logie.wifecraft.creator.ClothingSlot
import com.logie.wifecraft.creator.TBox
import com.logie.wifecraft.creator.TransmogCards
import com.logie.wifecraft.creator.TransmogCatalog
import com.logie.wifecraft.creator.TransmogFit
import com.logie.wifecraft.creator.TransmogFrame as F
import com.logie.wifecraft.creator.TransmogLists
import com.logie.wifecraft.creator.TransmogState
import com.logie.wifecraft.creator.TransmogTab
import com.logie.wifecraft.creator.Worn
import com.logie.wifecraft.engine.Thumbs
import com.logie.wifecraft.engine.TransmogPictures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The creator's Transmog window: Midnight's Transmogrify window (Blizzard_Transmog) as the game lays it out
 * (creator/TransmogFrame.kt, every box with the line of the game's XML it is from) and draws it (its own atlas pieces,
 * cut on the phone: TransmogArt), fitted to the screen as the game's "checkFit" fits it, its character column over the
 * creator's character. Left: the outfit (Clear Current Transmogrifications, the outfit's card, Save Outfit). Middle: the
 * character (turned and brought nearer by the creator's own gestures, under this window) between her slot buttons. Right:
 * the wardrobe's Items (the selected slot's appearances, Ignore Slot, Show Equipped Gear), Sets (the game's item sets) and
 * Custom Sets (the named characters' outfits: Chromie's), with search, filter and pages.
 *
 * A pick shows on her at once and is pending (purple) until Save Outfit; closing the window without saving puts back what
 * she wore (the host does it: [onClose]); a mouse or stylus resting on an item card shows it on her for as long as it rests.
 * The Items and Sets cards show her wearing each appearance, as the game's do (creator/TransmogCards: the framing per slot,
 * the cache's names, the queue; engine/TransmogPictures draws them off-screen for the page on show), the piece's icon until
 * its picture is ready. The logic is creator/TransmogState.kt and TransmogCards.kt (unit-tested); this file only draws it
 * and is compiled by CI only, never run on a phone.
 */

@Immutable
private class ItemResults(val slot: ClothingSlot, val search: String, val expansions: Set<Int>, val list: List<ClothingPiece>)

@Immutable
private class SetResults(val search: String, val expansions: Set<Int>, val list: List<ClothingSet>)

/** What a long press shows: the lines of a GameTooltip (text and colour) beside a box (pixels). */
@Immutable
private class Tip(val lines: List<Pair<String, Color>>, val by: TBox)

private val Gold = Color(0xFFFFD100)
private val White = Color.White
private val Grey = Color(0xFF808080)
private val Hint = Color(0xFF595959)          // the search box's instructions (0.35, 0.35, 0.35)
private fun rgb(v: Int) = Color(0xFF000000.toInt() or (v and 0xFFFFFF))

/**
 * @param saved what she wears as the window opens (slot id to ItemDisplayInfo id; empty when her gear is off)
 * @param onPreview a pick, put on her at once (not saved)
 * @param onPreset a named outfit with a look (Chromie's): its pieces and her look put on at once
 * @param onSave Save Outfit: what she now wears is kept
 * @param onClose the window closes (the host puts back what was saved, if anything is pending)
 * @param fit where the window is on the screen ([F.fit])
 * @param thumbs the creator's off-screen pictures (null: the cards show the pieces' icons)
 * @param look the character the cards are drawn on (her race, sex and choices; her clothes don't matter)
 */
@Composable
fun TransmogPanel(
    catalog: TransmogCatalog?,
    saved: Map<Int, Int>,
    status: String?,
    onRetry: () -> Unit,
    onPreview: (Map<Int, Int>) -> Unit,
    onPreset: (ClothingSet) -> Unit,
    onSave: (Map<Int, Int>) -> Unit,
    onClose: () -> Unit,
    icons: GameIcons?,
    art: TransmogArt?,
    fit: TransmogFit,
    font: FontFamily?,
    outfitName: String,
    thumbs: Thumbs? = null,
    look: Appearance? = null,
) {
    val density = LocalDensity.current
    val play = LocalSounds.current
    val kit = remember(fit, art, font, density) { Kit(fit, art, font, density) }
    // made again when the catalog arrives (nothing can be picked before: what she wears is known only with it)
    var state by remember(catalog == null) { mutableStateOf(TransmogState(saved)) }
    var tip by remember { mutableStateOf<Tip?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    val previewNow by rememberUpdatedState(onPreview)

    LaunchedEffect(Unit) { play("open") }

    // the cards' pictures: made for the page on show (TransmogPictures), held here while the page shows them
    val pictures = remember(thumbs) { thumbs?.let { TransmogPictures(it) } }
    DisposableEffect(pictures) { onDispose { pictures?.close() } }
    val lookHash = look?.let { TransmogCards.lookHash(it) }
    val cardLook = remember(lookHash) { look }
    val shownPictures = remember { mutableStateMapOf<String, ImageBitmap>() }
    fun pictureOf(key: String?): ImageBitmap? =
        key?.let { k -> shownPictures[k] ?: pictures?.cached(k)?.asImageBitmap() }

    // a mouse or stylus resting on an item card: it shows on her after a moment, and what is pending comes back as it leaves
    var hovered by remember { mutableStateOf<Int?>(null) }
    var peeking by remember { mutableStateOf(false) }

    fun update(next: TransmogState) {
        val before = state
        state = next
        tip = null
        if (next.pending != before.pending || peeking) previewNow(next.pending)
        peeking = false
    }
    LaunchedEffect(hovered) {
        val display = hovered
        if (display == null) {
            if (peeking) { peeking = false; previewNow(state.pending) }
        } else {
            delay(HOVER_MS)
            if (state.piece(state.slot) != display) { peeking = true; previewNow(state.pending + (state.slot.id to display)) }
        }
    }

    // what each look is called and drawn with, by slot and display (the slot buttons' icons and tooltips)
    val pieceOf = remember(catalog) {
        catalog?.pieces?.groupBy { it.slot.id to it.display }?.mapValues { it.value.first() }.orEmpty()
    }

    // the lists, made off the main thread: a new slot or filter at once, typing 250 ms after the last key
    var items by remember(catalog) { mutableStateOf<ItemResults?>(null) }
    var sets by remember(catalog) { mutableStateOf<SetResults?>(null) }
    val itemSearch = state.searchFor(TransmogTab.ITEMS)
    val setSearch = state.searchFor(TransmogTab.SETS)
    LaunchedEffect(catalog, state.slot, itemSearch, state.expansions) {
        val c = catalog ?: return@LaunchedEffect
        val before = items
        if (before != null && before.slot == state.slot && before.search != itemSearch) delay(250)
        val slot = state.slot; val ex = state.expansions
        val made = withContext(Dispatchers.Default) { TransmogLists.items(c, slot, itemSearch, ex) }
        items = ItemResults(slot, itemSearch, ex, made)
    }
    LaunchedEffect(catalog, setSearch, state.expansions) {
        val c = catalog ?: return@LaunchedEffect
        if (sets != null && sets?.search != setSearch) delay(250)
        val ex = state.expansions
        val made = withContext(Dispatchers.Default) { TransmogLists.sets(c, setSearch, ex) }
        sets = SetResults(setSearch, ex, made)
    }
    val customSets = remember(catalog) { catalog?.let { TransmogLists.customSets(it) }.orEmpty() }
    val expansions = remember(catalog) { catalog?.let { TransmogLists.expansions(it) }.orEmpty() }

    Box(Modifier.fillMaxSize()) {
        // ---- the art that doesn't move: the columns, the wardrobe's frame, the metal, the portrait ----
        Canvas(Modifier.fillMaxSize()) { kit.drawStaticOn(this) }
        kit.Words(F.TITLE_TEXT, fit.box(F.TITLE), F.FONT, Gold, TextAlign.Center)
        kit.CloseButton { play("close"); onClose() }

        // ---- the outfit column ----
        kit.ClearAll { update(state.clearAll()) }
        kit.OutfitCard(outfitName, icon = state.pending[ClothingSlot.TORSO.id]?.let { pieceOf[ClothingSlot.TORSO.id to it]?.icon } ?: 0,
            pending = state.anyPending, saves = state.saves, icons = icons) { update(state.undoAll()) }
        kit.RedButton(fit.box(F.OUTFITS.inside(F.SAVE)), "Save Outfit", enabled = state.anyPending) {
            play("ok"); onSave(state.pending); state = state.save()
        }

        // ---- the character column: the toggle, the slots, undo ----
        kit.Check(fit.box(F.PREVIEW.inside(F.HIDE_IGNORED)), "Hide Ignored Slots", state.hideIgnored) {
            play(if (it) "check_on" else "check_off"); update(state.hideIgnoredSlots(it))
        }
        val shown = state.shownSlots()
        for (s in shown) {
            val box = F.slotBox(s, shown) ?: continue
            val piece = state.piece(s)?.let { pieceOf[s.id to it] }
            kit.SlotButton(fit.box(F.PREVIEW.inside(box)), s, state.worn(s), selected = s == state.slot, hasPiece = state.piece(s) != null, icon = piece?.icon ?: 0, icons = icons,
                onTap = { play("click"); update(state.select(s)) },
                onHold = { b -> tip = Tip(listOfNotNull(F.caption(s) to White, piece?.let { it.name to rgb(F.qualityColour(it.quality)) }), b) })
        }
        if (state.anyPending) kit.UndoAll { play("click"); update(state.undoAll()) }

        // ---- the wardrobe ----
        kit.Tabs(state.tab) { t -> if (t != state.tab) { play("tab"); filterOpen = false; update(state.openTab(t)) } }
        val content = F.WARDROBE.inside(F.CONTENT)
        if (catalog == null) {
            kit.Words(status ?: "Loading clothing catalog…", fit.box(content.inside(TBox(30f, 120f, 584f, 60f))), F.FONT_MED, Gold, TextAlign.Center, lines = 3)
            if (status != null) kit.RedButton(fit.box(content.inside(TBox(644f / 2f - 64f, 200f, 128f, 28f))), "Retry", enabled = true) { onRetry() }
        } else when (state.tab) {
            TransmogTab.ITEMS -> {
                kit.Words(F.caption(state.slot), fit.box(content.inside(F.SLOT_TITLE)), F.FONT_HUGE, White, TextAlign.Start)
                kit.SearchBox(fit.box(content.inside(F.SEARCH)), itemSearch) { update(state.typed(it)) }
                kit.FilterButton(fit.box(content.inside(F.FILTER)), filterOpen, state.expansions.isNotEmpty()) { filterOpen = !filterOpen }
                val savedPiece = state.savedPiece(state.slot)?.let { pieceOf[state.slot.id to it] }
                kit.DisplayType(fit.box(content.inside(F.displayType(0))), "Ignore Slot", state.ignoring, state.isPending(state.slot) && state.ignoring,
                    unassigned = F.unassignedAppearanceAtlas(state.slot), icon = 0, icons = icons,
                    onHold = { b -> tip = Tip(listOf("When this outfit is saved, this slot shows nothing." to Gold), b) }) {
                    play("click"); update(state.ignore())
                }
                kit.DisplayType(fit.box(content.inside(F.displayType(1))), "Show Equipped Gear", state.showingEquipped, false,
                    unassigned = F.unassignedAppearanceAtlas(state.slot), icon = savedPiece?.icon ?: 0, icons = icons,
                    onHold = { b -> tip = Tip(listOf("What she wore in this slot when the window opened." to Gold) +
                        listOfNotNull(savedPiece?.let { it.name to rgb(F.qualityColour(it.quality)) }), b) }) {
                    play("click"); update(state.showEquipped())
                }
                val list = items?.takeIf { it.slot == state.slot }?.list
                if (list != null) {
                    val key = state.pageKey()
                    val grid = F.ITEMS
                    val page = state.page(key, list.size, grid.perPage, TransmogLists.pageOf(list, state.piece(state.slot), grid.perPage))
                    val from = page * grid.perPage
                    val cards = list.subList(from, min(list.size, from + grid.perPage))
                    val orders = cardLook?.let { a -> cards.map { TransmogCards.itemOrder(a, it.slot, it.display) } }.orEmpty()
                    CardFeed(pictures, orders, shownPictures)
                    cards.forEachIndexed { i, p ->
                        kit.AppearanceCard(fit.box(content.inside(grid.card(i))), p, state.wornCard(p.display), icons,
                            picture = pictureOf(orders.getOrNull(i)?.key),
                            onTap = { play("click"); hovered = null; update(state.choose(p.display)) },
                            onHold = { b -> tip = Tip(listOf(p.name to rgb(F.qualityColour(p.quality)), F.caption(p.slot) to White,
                                F.expansionName(p.expansion) to Grey), b) },
                            onHover = { on -> hovered = if (on) p.display else if (hovered == p.display) null else hovered })
                    }
                    kit.Pager(grid, content, page, grid.pages(list.size)) { play("page"); update(state.turn(key, it)) }
                }
            }
            TransmogTab.SETS -> {
                kit.SearchBox(fit.box(content.inside(F.SEARCH)), setSearch) { update(state.typed(it)) }
                kit.FilterButton(fit.box(content.inside(F.FILTER)), filterOpen, state.expansions.isNotEmpty()) { filterOpen = !filterOpen }
                val list = sets?.list
                if (list != null) kit.SetGrid(list, content, state, icons, "You don't have any Set appearances", cardLook, pictures, shownPictures,
                    onTurn = { key, to -> play("page"); update(state.turn(key, to)) },
                    onWear = { set -> play("click"); update(state.wear(set)) },
                    onHold = { set, b -> tip = Tip(listOf(set.name to White) + listOfNotNull(
                        set.expansion.takeIf { it >= 0 }?.let { F.expansionName(it) to Grey }), b) })
            }
            TransmogTab.CUSTOM_SETS -> kit.SetGrid(customSets, content, state, icons, "You don't have any Custom Sets", cardLook, pictures, shownPictures,
                onTurn = { key, to -> play("page"); update(state.turn(key, to)) },
                onWear = { set ->
                    play("click")
                    val next = state.wear(set)
                    state = next; tip = null
                    onPreset(set)
                },
                onHold = { set, b -> tip = Tip(listOf(set.name to White) +
                    listOfNotNull(set.look?.let { "Also sets her face, hair and markings (her race only)." to Gold }), b) })
        }

        // ---- over everything: the filter's menu, the tooltip ----
        if (filterOpen && catalog != null && state.tab != TransmogTab.CUSTOM_SETS) {
            kit.FilterMenu(fit.box(content.inside(F.FILTER)), expansions, state.expansions, onDismiss = { filterOpen = false }) {
                play("click"); update(state.filter(it))
            }
        }
        tip?.let { t -> kit.Tooltip(t) { tip = null } }
    }
}

// ---- the drawing kit ---------------------------------------------------------------------------------------------------

private class Kit(val fit: TransmogFit, val art: TransmogArt?, val font: FontFamily?, val density: Density) {
    fun piece(name: String): TransmogPiece? = art?.piece(name)
    /** Pixels of [u] window units. */
    fun u(units: Float): Float = fit.px(units)
    fun style(units: Float, colour: Color, align: TextAlign = TextAlign.Start): TextStyle = with(density) {
        TextStyle(fontFamily = font, fontSize = u(units).toSp(), color = colour, textAlign = align,
            shadow = Shadow(Color.Black, Offset(u(1f), u(1f)), 0f))
    }
}

/** A box of the screen, in pixels. */
@Composable
private fun Kit.At(b: TBox, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val w = with(density) { b.w.toDp() }; val h = with(density) { b.h.toDp() }
    Box(Modifier.offset { IntOffset(b.x.roundToInt(), b.y.roundToInt()) }.size(w, h).then(modifier)) { content() }
}

/** A FontString: Friz Quadrata at the game's size, a shadow one unit down and right. */
@Composable
private fun Kit.Words(text: String, b: TBox, units: Float, colour: Color, align: TextAlign = TextAlign.Start, lines: Int = 1) {
    At(b) {
        Box(Modifier.fillMaxSize(), contentAlignment = when (align) {
            TextAlign.Center -> Alignment.Center; TextAlign.End, TextAlign.Right -> Alignment.CenterEnd; else -> Alignment.CenterStart
        }) {
            Text(text, style = style(units, colour, align), maxLines = lines, overflow = TextOverflow.Ellipsis, softWrap = lines > 1)
        }
    }
}

/** A press: [onTap] on release, [onHold] (the box, pixels) on a long press, [down] while held. */
@Composable
private fun press(onTap: () -> Unit, onHold: (() -> Unit)? = null, down: (Boolean) -> Unit = {}): Modifier {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val held by rememberUpdatedState(down)
    return Modifier.pointerInput(Unit) {
        detectTapGestures(
            onPress = { held(true); tryAwaitRelease(); held(false) },
            onLongPress = { (hold ?: tap)() },
            onTap = { tap() },
        )
    }
}

/** SetAtlas: [p] stretched over x, y, w, h (pixels). */
private fun DrawScope.img(p: TransmogPiece?, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f, flipX: Boolean = false, add: Boolean = false) {
    p ?: return
    val draw = {
        drawImage(p.image, IntOffset.Zero, IntSize(p.image.width, p.image.height),
            IntOffset(x.roundToInt(), y.roundToInt()), IntSize(max(1, w.roundToInt()), max(1, h.roundToInt())),
            alpha = alpha, blendMode = if (add) BlendMode.Plus else BlendMode.SrcOver, filterQuality = FilterQuality.Medium)
    }
    if (flipX) withTransform({ scale(-1f, 1f, Offset(x + w / 2f, y + h / 2f)) }) { draw() } else draw()
}

private fun DrawScope.img(p: TransmogPiece?, b: TBox, alpha: Float = 1f, flipX: Boolean = false, add: Boolean = false) =
    img(p, b.x, b.y, b.w, b.h, alpha, flipX, add)

/** A piece drawn at its own size (useAtlasSize), centred on [b]'s middle. */
private fun DrawScope.centred(p: TransmogPiece?, b: TBox, unit: Float, alpha: Float = 1f, add: Boolean = false) {
    p ?: return
    val w = p.w * unit; val h = p.h * unit
    img(p, b.centerX - w / 2f, b.centerY - h / 2f, w, h, alpha, add = add)
}

/** A nine-sliced piece (UiTextureAtlasElementSliceData) over [b]; a piece with no margins is stretched. [unit]: pixels per window unit. */
private fun DrawScope.nine(p: TransmogPiece?, b: TBox, unit: Float, alpha: Float = 1f, add: Boolean = false) {
    p ?: return
    val n = p.nine ?: return img(p, b, alpha, add = add)
    val img = p.image
    val kx = img.width / p.w; val ky = img.height / p.h          // the picture's pixels per unit
    val sl = (n[0] * kx).roundToInt(); val st = (n[1] * ky).roundToInt()
    val sr = (n[2] * kx).roundToInt(); val sb = (n[3] * ky).roundToInt()
    val dl = min(n[0] * unit, b.w / 2f); val dt = min(n[1] * unit, b.h / 2f)
    val dr = min(n[2] * unit, b.w / 2f); val db = min(n[3] * unit, b.h / 2f)
    val sx = intArrayOf(0, sl, img.width - sr, img.width)
    val sy = intArrayOf(0, st, img.height - sb, img.height)
    val dx = floatArrayOf(b.x, b.x + dl, b.right - dr, b.right)
    val dy = floatArrayOf(b.y, b.y + dt, b.bottom - db, b.bottom)
    for (r in 0..2) for (c in 0..2) {
        val sw = sx[c + 1] - sx[c]; val sh = sy[r + 1] - sy[r]
        val dw = (dx[c + 1] - dx[c]).roundToInt(); val dh = (dy[r + 1] - dy[r]).roundToInt()
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) continue
        drawImage(img, IntOffset(sx[c], sy[r]), IntSize(sw, sh), IntOffset(dx[c].roundToInt(), dy[r].roundToInt()), IntSize(dw, dh),
            alpha = alpha, blendMode = if (add) BlendMode.Plus else BlendMode.SrcOver, filterQuality = FilterQuality.Medium)
    }
}

/** A tiled edge ("_" across, "!" down): the piece repeated at its own size along [b], the last one cut. */
private fun DrawScope.tiled(p: TransmogPiece?, b: TBox, unit: Float, across: Boolean) {
    p ?: return
    clipRect(b.x, b.y, b.right, b.bottom) {
        if (across) {
            val step = max(1f, p.w * unit)
            var x = b.x
            while (x < b.right) { img(p, x, b.y, step, b.h); x += step }
        } else {
            val step = max(1f, p.h * unit)
            var y = b.y
            while (y < b.bottom) { img(p, b.x, y, b.w, step); y += step }
        }
    }
}

/** The window's art that doesn't move, under everything else. */
private fun Kit.drawStaticOn(scope: DrawScope): Unit = with(scope) {
    val unit = fit.scale
    fun o(b: TBox) = fit.box(F.OUTFITS.inside(b))
    fun p(b: TBox) = fit.box(F.PREVIEW.inside(b))
    // the outfit column: its dark ground, its top and bottom light, the line down its right side, the list's lines
    val dark = piece("transmog-outfit-darkbg")
    if (dark != null) nine(dark, fit.box(F.OUTFITS), unit) else drawRect(Color(0xF00B0A10), Offset(fit.x(F.OUTFITS.x), fit.y(F.OUTFITS.y)),
        androidx.compose.ui.geometry.Size(u(F.OUTFITS.w), u(F.OUTFITS.h)))
    img(piece("transmog-outfit-toptexture"), o(F.OUTFIT_TOP))
    img(piece("transmog-outfit-bottomtexture"), o(F.OUTFIT_BOTTOM))
    nine(piece("transmog-outfit-darkbg-cornerline"), o(F.OUTFIT_DIVIDER), unit)
    val line = piece("transmog-outfit-dividerline")
    if (line != null) {
        val lw = line.w * unit; val lh = line.h * unit
        val list = o(F.OUTFIT_LIST)
        img(line, list.centerX - lw / 2f, list.y, lw, lh)
        img(line, list.centerX - lw / 2f, list.bottom - lh, lw, lh)
    }
    // the character column: no ground (the creator's own backdrop and character show through), its dark sides
    val grad = piece("transmog-outfit-darkbg-gradient")
    img(grad, p(TBox(0f, 0f, F.GRADIENT_W, F.PREVIEW.h)))
    img(grad, p(TBox(F.PREVIEW.w - F.GRADIENT_W, 0f, F.GRADIENT_W, F.PREVIEW.h)), flipX = true)
    // the wardrobe: dark ground, the tab content's ground and border
    if (dark != null) nine(dark, fit.box(F.WARDROBE), unit) else drawRect(Color(0xF00B0A10), Offset(fit.x(F.WARDROBE.x), fit.y(F.WARDROBE.y)),
        androidx.compose.ui.geometry.Size(u(F.WARDROBE.w), u(F.WARDROBE.h)))
    val content = F.WARDROBE.inside(F.CONTENT)
    img(piece("transmog-tabs-frame-bg"), fit.box(content.inside(F.CONTENT_BG)))
    nine(piece("transmog-tabs-frame"), fit.box(content.inside(F.CONTENT_BORDER)), unit)
    // the portrait, then the metal round it
    val portrait = fit.box(F.PORTRAIT)
    piece("transmog-icon-ui")?.let { icon ->
        clipPath(androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(portrait.x + u(2f), portrait.y, portrait.right - u(2f), portrait.bottom - u(4f))) }) {
            img(icon, portrait)
        }
    }
    val sizeOf = { name: String -> piece(name)?.let { it.w to it.h } ?: (32f to 32f) }
    for ((name, b) in F.metal(sizeOf)) {
        val px = fit.box(b)
        when {
            name.startsWith("_") -> tiled(piece(name), px, unit, across = true)
            name.startsWith("!") -> tiled(piece(name), px, unit, across = false)
            else -> img(piece(name), px)
        }
    }
    if (piece(F.TL) == null) {
        // before the art: a thin gold edge round the window
        val outer = fit.box(TBox(0f, 0f, F.W, F.H))
        drawRect(Gold.copy(alpha = 0.6f), Offset(outer.x, outer.y), androidx.compose.ui.geometry.Size(outer.w, outer.h),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = max(1f, u(2f))))
    }
}

/** UIPanelCloseButton: the red X at the window's top right. */
@Composable
private fun Kit.CloseButton(onTap: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    At(fit.box(F.CLOSE), press(onTap, down = { held = it }).semantics { role = Role.Button; contentDescription = "Close" }) {
        Canvas(Modifier.fillMaxSize()) {
            val p = piece(if (held) "redbutton-exit-pressed" else "redbutton-exit")
            if (p != null) img(p, 0f, 0f, size.width, size.height)
            else drawCircle(Color(0xFF8C1A10), size.minDimension / 2f)
        }
    }
}

/** ShowEquippedGearSpellFrame: the round-cornered button and its words, "Clear Current Transmogrifications". */
@Composable
private fun Kit.ClearAll(onTap: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    val icon = fit.box(F.OUTFITS.inside(F.CLEAR_ALL_ICON))
    At(fit.box(F.OUTFITS.inside(F.CLEAR_ALL)), press(onTap, down = { held = it }).semantics { role = Role.Button })
    Canvas(Modifier.fillMaxSize()) {
        val inner = u(36f)
        img(piece("transmog-icon-ui"), icon.centerX - inner / 2f, icon.centerY - inner / 2f, inner, inner, alpha = if (held) 0.7f else 1f)
        centred(piece("transmog-outfit-spellframe"), icon, fit.scale)
    }
    Words("Clear Current Transmogrifications", TBox(icon.right + u(8f), icon.y, u(280f - 45f - 8f), icon.h), F.FONT, Gold, lines = 2)
}

/** The outfit's line in the list (TransmogOutfitEntryTemplate): its icon, its card (selected), its name; purple while it has changes. */
@Composable
private fun Kit.OutfitCard(name: String, icon: Int, pending: Boolean, saves: Int, icons: GameIcons?, onTap: () -> Unit) {
    val row = F.OUTFITS.inside(F.outfitRow(0))
    val iconBox = fit.box(row.inside(F.OUTFIT_ROW_ICON))
    val card = fit.box(row.inside(F.OUTFIT_ROW_CARD))
    val image = gameIcon(icons, icon)
    // AnimSaved: the purple selection held 1.2 s after a save, then gone in 0.3 s
    var flash by remember { mutableStateOf(0f) }
    LaunchedEffect(saves) {
        if (saves == 0) return@LaunchedEffect
        flash = 1f; delay(1200)
        for (i in 1..6) { flash = 1f - i / 6f; delay(50) }
    }
    At(TBox(iconBox.x, card.y, card.right - iconBox.x, card.h), press(onTap).semantics { role = Role.Button; selected = true; contentDescription = name })
    Canvas(Modifier.fillMaxSize()) {
        val inner = u(36f)
        if (image != null) drawImage(image, IntOffset.Zero, IntSize(image.width, image.height),
            IntOffset((iconBox.centerX - inner / 2f).roundToInt(), (iconBox.centerY - inner / 2f).roundToInt()), IntSize(inner.roundToInt(), inner.roundToInt()))
        else img(piece("transmog-icon-ui"), iconBox.centerX - inner / 2f, iconBox.centerY - inner / 2f, inner, inner)
        centred(piece("transmog-outfit-spellframe"), iconBox, fit.scale)
        if (pending || flash > 0f) centred(piece("transmog-outfit-spellframe-active"), iconBox, fit.scale)
        nine(piece("transmog-outfit-card"), card, fit.scale)
        val sel = piece("transmog-outfit-card-selected")
        if (sel != null) nine(sel, TBox(card.x, card.y, sel.w * fit.scale, sel.h * fit.scale), fit.scale, alpha = 1f - flash)
        val purple = piece("transmog-outfit-card-selected-purple")
        if (purple != null && (pending || flash > 0f)) centred(purple, card, fit.scale, alpha = if (pending) 1f else flash)
    }
    Words(name, TBox(card.x + u(F.OUTFIT_NAME_X), card.y, card.w - u(F.OUTFIT_NAME_X + 8f), card.h), F.FONT, Gold)
}

/** SharedButtonTemplate (BigRedThreeSliceButtonTemplate): the red button, its words gold (white held), grey disabled. */
@Composable
private fun Kit.RedButton(b: TBox, text: String, enabled: Boolean, onTap: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    At(b, press({ if (enabled) onTap() }, down = { held = it && enabled }).semantics { role = Role.Button; contentDescription = text })
    Canvas(Modifier.fillMaxSize()) {
        val state = when { !enabled -> "-disabled"; held -> "-pressed"; else -> "" }
        val l = piece("128-redbutton-left$state"); val c = piece("_128-redbutton-center$state"); val r = piece("128-redbutton-right$state")
        if (l == null || c == null || r == null) {
            drawRect(Color(0xFF6B0F0A), Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h))
            return@Canvas
        }
        // the three pieces at the button's height: the left and right keep their shape, the middle stretches
        val k = b.h / l.h
        val lw = min(l.w * k, b.w / 2f); val rw = min(r.w * k, b.w / 2f)
        img(l, b.x, b.y, lw, b.h)
        img(r, b.right - rw, b.y, rw, b.h)
        if (b.w > lw + rw) img(c, b.x + lw, b.y, b.w - lw - rw, b.h)
    }
    Words(text, b.at(0f, if (held) u(1f) else 0f), F.FONT, if (!enabled) Grey else if (held) White else Gold, TextAlign.Center)
}

/** MinimalCheckboxArtTemplate and its words (GameFontNormal), as the window's toggles. */
@Composable
private fun Kit.Check(b: TBox, text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val label = TBox(b.right + u(4f), b.y, u(220f), b.h)
    At(TBox(b.x, b.y, label.right - b.x, b.h), press({ onChange(!on) }).semantics { role = Role.Checkbox; selected = on; contentDescription = text })
    Canvas(Modifier.fillMaxSize()) {
        val box = piece("checkbox-minimal")
        if (box != null) img(box, b) else drawRect(Gold, Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h), style = androidx.compose.ui.graphics.drawscope.Stroke(u(1.5f)))
        if (on) {
            val mark = piece("checkmark-minimal")
            if (mark != null) img(mark, b) else drawRect(Gold, Offset(b.x + b.w * 0.25f, b.y + b.h * 0.25f), androidx.compose.ui.geometry.Size(b.w / 2f, b.h / 2f))
        }
    }
    Words(text, label, F.FONT, Gold)
}

/**
 * TransmogAppearanceSlotTemplate: the piece's icon (or the slot's own "unassigned" picture), its border (default, or
 * transmogrified while a piece is on), the purple pending frame and glow while it differs from what is saved, the gold
 * selected frame.
 */
@Composable
private fun Kit.SlotButton(b: TBox, slot: ClothingSlot, worn: Worn, selected: Boolean, hasPiece: Boolean, icon: Int, icons: GameIcons?, onTap: () -> Unit, onHold: (TBox) -> Unit) {
    val image = gameIcon(icons, icon)
    var held by remember { mutableStateOf(false) }
    At(b, press(onTap, { onHold(b) }, { held = it }).semantics { role = Role.Button; this.selected = selected; contentDescription = F.caption(slot) })
    Canvas(Modifier.fillMaxSize()) {
        val unit = fit.scale
        if (worn == Worn.PENDING) centred(piece("transmog-gearslot-transmogrified-glw"), b, unit, add = true)
        val iconSize = u(F.SLOT_ICON)
        if (image != null) {
            drawImage(image, IntOffset.Zero, IntSize(image.width, image.height),
                IntOffset((b.centerX - iconSize / 2f).roundToInt(), (b.centerY - iconSize / 2f).roundToInt()), IntSize(iconSize.roundToInt(), iconSize.roundToInt()))
        } else {
            val un = piece(F.unassignedSlotAtlas(slot))
            if (un != null) centred(un, b, unit)
            else drawRect(Color(0xFF15131C), Offset(b.centerX - iconSize / 2f, b.centerY - iconSize / 2f), androidx.compose.ui.geometry.Size(iconSize, iconSize))
        }
        val border = piece(if (hasPiece) "transmog-gearslot-transmogrified" else "transmog-gearslot-default")
        if (border != null) centred(border, b, unit) else drawRect(Gold.copy(alpha = 0.5f), Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h), style = androidx.compose.ui.graphics.drawscope.Stroke(u(1f)))
        if (held) centred(border, b, unit, alpha = 0.5f, add = true)
        if (worn == Worn.PENDING) {
            centred(piece("transmog-gearslot-pending"), b, unit)
            centred(piece("transmog-gearslot-transmogrified-hl"), b, unit, alpha = 0.6f, add = true)
        }
        if (selected) {
            val sel = piece("transmog-gearslot-selected")
            if (sel != null) centred(sel, b, unit) else drawRect(Gold, Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h), style = androidx.compose.ui.graphics.drawscope.Stroke(u(2f)))
        }
    }
}

/** ClearAllPendingButton: the square tertiary button with the undo arrow, over the character's top right while something is pending. */
@Composable
private fun Kit.UndoAll(onTap: () -> Unit) {
    val b = fit.box(F.PREVIEW.inside(F.UNDO_ALL))
    var held by remember { mutableStateOf(false) }
    At(b, press(onTap, down = { held = it }).semantics { role = Role.Button; contentDescription = "Undo all changes" })
    Canvas(Modifier.fillMaxSize()) {
        val bg = piece(if (held) "common-button-tertiary-square-pressed" else "common-button-tertiary-square-normal")
        if (bg != null) img(bg, b) else drawRect(Color(0xFF2A2430), Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h))
        val icon = u(18f)
        img(piece("common-icon-undo"), b.centerX - icon / 2f, b.centerY - icon / 2f, icon, icon)
    }
}

/**
 * The wardrobe's tabs (TabSystemTopButtonTemplate: the frame's tab art turned over, the chosen one taller and white, the
 * pink line under its words): Items, Sets, Custom Sets.
 */
@Composable
private fun Kit.Tabs(chosen: TransmogTab, onPick: (TransmogTab) -> Unit) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    var x = F.WARDROBE.x + F.TABS_AT.x
    for (t in TransmogTab.entries) {
        val textW = measurer.measure(t.caption, style(F.FONT, Gold)).size.width / fit.scale
        val w = F.tabWidth(textW)
        val b = fit.box(TBox(x, F.WARDROBE.y + F.TABS_AT.y, w, F.TABS_AT.h))
        x += w + F.TAB_GAP
        val on = t == chosen
        At(b, press({ onPick(t) }).semantics { role = Role.Tab; selected = on; contentDescription = t.caption })
        Canvas(Modifier.fillMaxSize()) {
            val unit = fit.scale
            val l = piece(if (on) "uiframe-activetab-left" else "uiframe-tab-left")
            val c = piece(if (on) "_uiframe-activetab-center" else "_uiframe-tab-center")
            val r = piece(if (on) "uiframe-activetab-right" else "uiframe-tab-right")
            if (l != null && c != null && r != null) {
                // turned over (rotation 180): the open side down, onto the wardrobe's frame
                val h = l.h * unit
                val top = b.bottom - h + (if (on) u(6f) else u(2f))
                withTransform({ rotate(180f, Offset(b.centerX, top + h / 2f)) }) {
                    val lw = l.w * unit; val rw = r.w * unit
                    img(l, b.x, top, lw, h)
                    img(r, b.right - rw, top, rw, h)
                    img(c, b.x + lw, top, max(0f, b.w - lw - rw), h)
                }
            } else {
                drawRect(if (on) Color(0xFF2B2233) else Color(0xFF17141C), Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h))
            }
            if (on) img(piece("transmog-tab-hl"), b.x + u(2f), b.bottom - u(5f) - u(1f), b.w - u(5f), max(1f, u(1f)), add = true)
        }
        Words(t.caption, b.at(0f, -u(3f)), F.FONT, if (on) White else Gold, TextAlign.Center)
    }
}

/** SearchBoxNineSliceTemplate: the dark bar, the magnifying glass, "Search" until something is typed, the clear button. */
@Composable
private fun Kit.SearchBox(b: TBox, value: String, onChange: (String) -> Unit) {
    Canvas(Modifier.fillMaxSize()) {
        val bg = piece("common-searchbar-a")
        if (bg != null) nine(bg, b, fit.scale) else drawRect(Color(0xFF0E0C12), Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h))
        val icon = piece("common-searchbar-icon-a")
        if (icon != null) img(icon, b.x + u(4f), b.centerY - u(icon.h) / 2f + u(1f), u(icon.w), u(icon.h))
        if (value.isNotEmpty()) {
            val clear = u(10f)
            img(piece("common-search-clearbutton"), b.right - u(3f) - u(17f) + u(3f), b.centerY - clear / 2f, clear, clear, alpha = 0.5f)
        }
    }
    val field = TBox(b.x + u(20f), b.y, b.w - u(20f) - u(20f), b.h)
    if (value.isEmpty()) Words("Search", field, F.FONT, Hint)
    At(field) {
        BasicTextField(value, { onChange(it) }, singleLine = true, textStyle = style(F.FONT, White).copy(shadow = null),
            cursorBrush = SolidColor(White), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Search" })
    }
    if (value.isNotEmpty()) At(TBox(b.right - u(20f), b.y, u(20f), b.h), press({ onChange("") }).semantics { role = Role.Button; contentDescription = "Clear search" })
}

/** WowStyle1FilterDropdownTemplate: the dropdown bar with "Filter"; open while its menu is. */
@Composable
private fun Kit.FilterButton(b: TBox, open: Boolean, active: Boolean, onTap: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    val art = TBox(b.x - u(4f), b.y - u(4f), b.w + u(8f), b.h + u(8f))
    At(b, press(onTap, down = { held = it }).semantics { role = Role.Button; contentDescription = "Filter" })
    Canvas(Modifier.fillMaxSize()) {
        val bg = piece(when { held -> "common-dropdown-b-button-pressed"; open -> "common-dropdown-b-button-open"; else -> "common-dropdown-b-button" })
        if (bg != null) nine(bg, art, fit.scale) else drawRect(Color(0xFF1C1820), Offset(art.x, art.y), androidx.compose.ui.geometry.Size(art.w, art.h))
    }
    Words(if (active) "Filter *" else "Filter", TBox(b.x + u(12f), b.y, b.w - u(30f), b.h), F.FONT, Gold)
}

/** The filter's menu: Check All, Uncheck All, then each expansion with its check box (the game's Menu, on a tooltip's ground). */
@Composable
private fun Kit.FilterMenu(button: TBox, all: List<Int>, chosen: Set<Int>, onDismiss: () -> Unit, onChange: (Set<Int>) -> Unit) {
    // a tap anywhere else closes it
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
    val line = u(20f)
    val lines = 2 + all.size
    val w = u(220f)
    val b = TBox(button.x + u(6f), button.bottom + u(2f), w, lines * line + u(16f))
    At(b, Modifier.pointerInput(Unit) { detectTapGestures { } })
    Canvas(Modifier.fillMaxSize()) { drawTooltipGroundOn(this, b) }
    val showAll = chosen.isEmpty()
    At(TBox(b.x, b.y + u(8f), w, line), press({ onChange(emptySet()) }).semantics { role = Role.Button })
    Words("Check All", TBox(b.x + u(12f), b.y + u(8f), w - u(24f), line), F.FONT, White)
    At(TBox(b.x, b.y + u(8f) + line, w, line), press({ onChange(setOf(-1)) }).semantics { role = Role.Button })
    Words("Uncheck All", TBox(b.x + u(12f), b.y + u(8f) + line, w - u(24f), line), F.FONT, White)
    all.forEachIndexed { i, e ->
        val y = b.y + u(8f) + (i + 2) * line
        val on = showAll || e in chosen
        val box = TBox(b.x + u(10f), y + u(2f), line - u(4f), line - u(4f))
        At(TBox(b.x, y, w, line), press({
            val now = if (showAll) all.toSet() else chosen - (-1)
            val next = if (e in now) now - e else now + e
            onChange(if (next.containsAll(all)) emptySet() else if (next.isEmpty()) setOf(-1) else next)
        }).semantics { role = Role.Checkbox; selected = on })
        Canvas(Modifier.fillMaxSize()) {
            img(piece("checkbox-minimal"), box)
            if (on) img(piece("checkmark-minimal"), box)
        }
        Words(F.expansionName(e), TBox(box.right + u(6f), y, w - u(40f), line), F.FONT, White)
    }
}

/** GameTooltip's ground (NineSlice TooltipDefaultLayout): dark blue middle, the grey edges and corners. */
private fun Kit.drawTooltipGroundOn(scope: DrawScope, b: TBox): Unit = with(scope) {
    val unit = fit.scale
    val c = piece("tooltip-nineslice-center")
    if (c == null) {
        drawRect(Color(0xE6000000), Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h))
        drawRect(Color(0xFF5A5A5A), Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h), style = androidx.compose.ui.graphics.drawscope.Stroke(u(1.5f)))
        return@with
    }
    val k = 7f * unit
    // the middle is a white piece the tooltip colours (TOOLTIP_DEFAULT_BACKGROUND_COLOR 0.09, 0.09, 0.19)
    drawRect(Color(0.09f, 0.09f, 0.19f, 0.92f), Offset(b.x + k / 2f, b.y + k / 2f), androidx.compose.ui.geometry.Size(b.w - k, b.h - k))
    tiled(piece("_tooltip-nineslice-edgetop"), TBox(b.x + k, b.y, b.w - 2 * k, k), unit, true)
    tiled(piece("_tooltip-nineslice-edgebottom"), TBox(b.x + k, b.bottom - k, b.w - 2 * k, k), unit, true)
    tiled(piece("!tooltip-nineslice-edgeleft"), TBox(b.x, b.y + k, k, b.h - 2 * k), unit, false)
    tiled(piece("!tooltip-nineslice-edgeright"), TBox(b.right - k, b.y + k, k, b.h - 2 * k), unit, false)
    img(piece("tooltip-nineslice-cornertopleft"), b.x, b.y, k, k)
    img(piece("tooltip-nineslice-cornertopright"), b.right - k, b.y, k, k)
    img(piece("tooltip-nineslice-cornerbottomleft"), b.x, b.bottom - k, k, k)
    img(piece("tooltip-nineslice-cornerbottomright"), b.right - k, b.bottom - k, k, k)
}

/** GameTooltip beside [Tip.by] (ANCHOR_RIGHT, or left where the screen ends): its lines, the first larger. A tap closes it. */
@Composable
private fun Kit.Tooltip(t: Tip, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().pointerInput(t) { detectTapGestures { onDismiss() } })
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val pad = u(10f)
    val sizes = t.lines.mapIndexed { i, (s, c) -> measurer.measure(s, style(if (i == 0) F.FONT_MED else F.FONT, c)).size }
    val w = (sizes.maxOfOrNull { it.width } ?: 0) + 2 * pad
    val h = sizes.sumOf { it.height } + 2 * pad + u(2f) * (sizes.size - 1)
    val right = fit.x(F.W)
    var x = t.by.right + u(4f)
    if (x + w > right) x = t.by.x - u(4f) - w
    val y = max(0f, t.by.y)
    val b = TBox(max(0f, x), y, w.toFloat(), h.toFloat())
    Canvas(Modifier.fillMaxSize()) { drawTooltipGroundOn(this, b) }
    var ty = b.y + pad
    t.lines.forEachIndexed { i, (s, c) ->
        val lh = sizes[i].height.toFloat()
        Words(s, TBox(b.x + pad, ty, b.w - 2 * pad, lh), if (i == 0) F.FONT_MED else F.FONT, c)
        ty += lh + u(2f)
    }
}

/**
 * DisplayTypeButtonTemplate: the tertiary button (pressed in and purple-lit while chosen), its round picture on the left
 * (the slot's "unassigned" picture, or the worn piece's icon in its ring), its words.
 */
@Composable
private fun Kit.DisplayType(b: TBox, text: String, on: Boolean, pending: Boolean, unassigned: String, icon: Int, icons: GameIcons?, onHold: (TBox) -> Unit, onTap: () -> Unit) {
    val image = gameIcon(icons, icon)
    var held by remember { mutableStateOf(false) }
    At(b, press(onTap, { onHold(b) }, { held = it }).semantics { role = Role.Button; selected = on; contentDescription = text })
    Canvas(Modifier.fillMaxSize()) {
        val unit = fit.scale
        val bg = piece(when { on -> "common-button-tertiary-depressed-normal"; held -> "common-button-tertiary-pressed"; else -> "common-button-tertiary-normal" })
        if (bg != null) nine(bg, b, unit) else drawRect(if (on) Color(0xFF2B2233) else Color(0xFF17141C), Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h))
        if (on && pending) nine(piece("common-button-tertiary-depressed-normal-purple"), TBox(b.x - u(2f), b.y - u(3f), u(192f), u(40f)), unit)
        val ring = TBox(b.x + u(F.DISPLAY_ICON.x), b.y + u(F.DISPLAY_ICON.y), u(F.DISPLAY_ICON.w), u(F.DISPLAY_ICON.h))
        val d = u(30f)
        if (image != null) {
            clipPath(androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(ring.centerX - d / 2f, ring.centerY - d / 2f, ring.centerX + d / 2f, ring.centerY + d / 2f)) }) {
                drawImage(image, IntOffset.Zero, IntSize(image.width, image.height),
                    IntOffset((ring.centerX - d / 2f).roundToInt(), (ring.centerY - d / 2f).roundToInt()), IntSize(d.roundToInt(), d.roundToInt()))
            }
        } else centred(piece(unassigned), ring, unit)
        centred(piece(if (on) "transmog-appearance-circframe-active" else "transmog-appearance-circframe"), ring, unit)
    }
    Words(text, TBox(b.x + u(F.DISPLAY_TEXT_X), b.y, u(150f), b.h), F.FONT, if (on) White else Gold, lines = 2)
}

/**
 * TransmogItemModelTemplate: the black card, the character wearing the piece ([picture]; its icon, dimmed, until the picture
 * is made), the card's border, pink while she wears it, purple while that is pending.
 */
@Composable
private fun Kit.AppearanceCard(
    b: TBox, p: ClothingPiece, worn: Worn, icons: GameIcons?, picture: ImageBitmap?, onTap: () -> Unit, onHold: (TBox) -> Unit,
    onHover: (Boolean) -> Unit,
) {
    val image = gameIcon(icons, p.icon)
    var held by remember { mutableStateOf(false) }
    At(b, press(onTap, { onHold(b) }, { held = it }).then(hover(onHover)).semantics { role = Role.Button; selected = worn != Worn.NO; contentDescription = p.name })
    Canvas(Modifier.fillMaxSize()) {
        val unit = fit.scale
        drawRect(Color.Black, Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h))
        if (picture != null) {
            // her, wearing it (the picture has the card's shape)
            drawImage(picture, IntOffset.Zero, IntSize(picture.width, picture.height),
                IntOffset(b.x.roundToInt(), b.y.roundToInt()), IntSize(b.w.roundToInt(), b.h.roundToInt()), filterQuality = FilterQuality.Medium)
        } else {
            val d = u(64f)
            if (image != null) drawImage(image, IntOffset.Zero, IntSize(image.width, image.height),
                IntOffset((b.centerX - d / 2f).roundToInt(), (b.centerY - d / 2f).roundToInt()), IntSize(d.roundToInt(), d.roundToInt()), alpha = 0.6f)
        }
        val frame = TBox(b.centerX - u(F.ITEM_BORDER_W) / 2f, b.centerY - u(F.ITEM_BORDER_H) / 2f, u(F.ITEM_BORDER_W), u(F.ITEM_BORDER_H))
        val border = piece("transmog-itemcard-default")
        if (border != null) nine(border, frame, unit) else drawRect(Color(0xFF5A4E3A), Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h), style = androidx.compose.ui.graphics.drawscope.Stroke(u(1.5f)))
        when (worn) {
            Worn.SAVED -> nine(piece("transmog-itemcard-transmogrified"), frame, unit)
            Worn.PENDING -> nine(piece("transmog-itemcard-transmogrified-pending"), frame, unit)
            Worn.NO -> {}
        }
        if (held) nine(border, frame, unit, alpha = 0.6f, add = true)
    }
}

/** The Sets and Custom Sets tabs: their cards, paged, or the tab's "none" line. */
@Composable
private fun Kit.SetGrid(
    list: List<ClothingSet>, content: TBox, state: TransmogState, icons: GameIcons?, none: String,
    look: Appearance?, pictures: TransmogPictures?, shownPictures: MutableMap<String, ImageBitmap>,
    onTurn: (String, Int) -> Unit, onWear: (ClothingSet) -> Unit, onHold: (ClothingSet, TBox) -> Unit,
) {
    val grid = F.SETS
    if (list.isEmpty()) {
        CardFeed(pictures, emptyList(), shownPictures)
        Words(none, fit.box(content.inside(TBox(grid.area.centerX - 295f, grid.area.centerY - 20f + 31f, 590f, 40f))), F.FONT_MED, Gold, TextAlign.Center)
        return
    }
    val key = state.pageKey()
    val worn = list.indexOfFirst { state.wornSet(it) != Worn.NO }
    val page = state.page(key, list.size, grid.perPage, if (worn < 0) 0 else worn / grid.perPage)
    val from = page * grid.perPage
    val cards = list.subList(from, min(list.size, from + grid.perPage))
    val orders = look?.let { a -> cards.map { TransmogCards.setOrder(a, it) } }.orEmpty()
    CardFeed(pictures, orders, shownPictures)
    cards.forEachIndexed { i, set ->
        val cardKey = orders.getOrNull(i)?.key
        val picture = cardKey?.let { k -> shownPictures[k] ?: pictures?.cached(k)?.asImageBitmap() }
        SetCard(fit.box(content.inside(grid.card(i))), set, state.wornSet(set), icons, picture, { onWear(set) }, { b -> onHold(set, b) })
    }
    Pager(grid, content, page, grid.pages(list.size)) { onTurn(key, it) }
}

/**
 * TransmogSetBaseModelTemplate: the black card in its set border (pink while she wears the set, purple while that is
 * pending), the character in the set ([picture]; the set's chest icon until it is made) and the set's name.
 */
@Composable
private fun Kit.SetCard(b: TBox, set: ClothingSet, worn: Worn, icons: GameIcons?, picture: ImageBitmap?, onTap: () -> Unit, onHold: (TBox) -> Unit) {
    val image = gameIcon(icons, set.icon)
    var held by remember { mutableStateOf(false) }
    At(b, press(onTap, { onHold(b) }, { held = it }).semantics { role = Role.Button; selected = worn != Worn.NO; contentDescription = set.name })
    Canvas(Modifier.fillMaxSize()) {
        val unit = fit.scale
        drawRect(Color.Black, Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h))
        if (picture != null) {
            drawImage(picture, IntOffset.Zero, IntSize(picture.width, picture.height),
                IntOffset(b.x.roundToInt(), b.y.roundToInt()), IntSize(b.w.roundToInt(), b.h.roundToInt()), filterQuality = FilterQuality.Medium)
        } else {
            val d = u(72f)
            if (image != null) drawImage(image, IntOffset.Zero, IntSize(image.width, image.height),
                IntOffset((b.centerX - d / 2f).roundToInt(), (b.y + u(54f)).roundToInt()), IntSize(d.roundToInt(), d.roundToInt()))
        }
        val frame = TBox(b.centerX - u(F.SET_BORDER_W) / 2f, b.centerY - u(F.SET_BORDER_H) / 2f, u(F.SET_BORDER_W), u(F.SET_BORDER_H))
        val border = piece("transmog-setcard-default")
        if (border != null) nine(border, frame, unit) else drawRect(Color(0xFF5A4E3A), Offset(b.x, b.y), androidx.compose.ui.geometry.Size(b.w, b.h), style = androidx.compose.ui.graphics.drawscope.Stroke(u(1.5f)))
        when (worn) {
            Worn.SAVED -> nine(piece("transmog-setcard-transmogrified"), frame, unit)
            Worn.PENDING -> nine(piece("transmog-setcard-transmogrified-pending"), frame, unit)
            Worn.NO -> {}
        }
        if (held) nine(border, frame, unit, alpha = 0.6f, add = true)
    }
    Words(set.name, TBox(b.x + u(10f), b.bottom - u(56f), b.w - u(20f), u(40f)), F.FONT, White, TextAlign.Center, lines = 2)
}

/** PagingControlsHorizontalTemplate: "Page 1/9" and the spellbook's previous and next page buttons, centred under the cards. */
@Composable
private fun Kit.Pager(grid: CardGrid, content: TBox, page: Int, pages: Int, onTurn: (Int) -> Unit) {
    val text = TransmogLists.pageText(page, pages)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val textW = measurer.measure(text, style(F.FONT, White)).size.width / fit.scale
    val bar = content.inside(grid.pager)
    val total = textW + 5f + 32f + 5f + 32f
    val x0 = bar.centerX - total / 2f
    Words(text, fit.box(TBox(x0, bar.y, textW + 2f, 32f)), F.FONT, White)
    PageButton(fit.box(TBox(x0 + textW + 5f, bar.y, 32f, 32f)), "page-prev", page > 0, "Previous page") { onTurn(page - 1) }
    PageButton(fit.box(TBox(x0 + textW + 5f + 32f + 5f, bar.y, 32f, 32f)), "page-next", page < pages - 1, "Next page") { onTurn(page + 1) }
}

@Composable
private fun Kit.PageButton(b: TBox, name: String, enabled: Boolean, label: String, onTap: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    At(b, press({ if (enabled) onTap() }, down = { held = it && enabled }).semantics { role = Role.Button; contentDescription = label })
    Canvas(Modifier.fillMaxSize()) {
        val p = piece(when { !enabled -> "$name-disabled"; held -> "$name-down"; else -> name })
        if (p != null) img(p, b)
        else drawCircle(if (enabled) Gold else Grey, b.w / 3f, Offset(b.centerX, b.centerY), style = androidx.compose.ui.graphics.drawscope.Stroke(u(2f)))
    }
}

/** How long a mouse or stylus rests on a card before it shows on her (a sweep across the grid dresses her in nothing). */
private const val HOVER_MS = 250L

/** A mouse's or stylus's pointer coming onto (true) and leaving (false) what this is on; a finger is not a hover. */
@Composable
private fun hover(onHover: (Boolean) -> Unit): Modifier {
    val onChange by rememberUpdatedState(onHover)
    return Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                val change = e.changes.firstOrNull() ?: continue
                if (change.type != PointerType.Mouse && change.type != PointerType.Stylus) continue
                when (e.type) {
                    PointerEventType.Enter -> onChange(true)
                    PointerEventType.Exit -> onChange(false)
                    else -> {}
                }
            }
        }
    }
}

/**
 * The page's card pictures asked for (TransmogPictures: the page in grid order; what the last page still had waiting is
 * dropped) and kept in [shown] while the page shows them; when the page or its list changes, the others are let go.
 */
@Composable
private fun CardFeed(pictures: TransmogPictures?, orders: List<CardOrder>, shown: MutableMap<String, ImageBitmap>) {
    val keys = orders.map { it.key }
    val current by rememberUpdatedState(orders)
    LaunchedEffect(pictures, keys) {
        val p = pictures ?: return@LaunchedEffect
        val keep = keys.toSet()
        for (k in shown.keys.filter { it !in keep }) shown.remove(k)
        p.show(current) { key, bmp -> if (key in keys) shown[key] = bmp.asImageBitmap() }
    }
}
