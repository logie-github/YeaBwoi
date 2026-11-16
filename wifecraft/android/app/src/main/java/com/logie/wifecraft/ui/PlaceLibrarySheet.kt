package com.logie.wifecraft.ui

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.catalog.AssetKind
import com.logie.wifecraft.catalog.Page
import com.logie.wifecraft.editor.LibraryPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.logie.wifecraft.catalog.Catalog as Library

/** What the card row shows: the grouped search, or a page of one tab. */
private sealed interface Shown {
    data class Groups(val groups: List<SearchGroup>) : Shown
    data class One(val page: Page) : Shown
}

/** One thing in the card row: a group's label (the grouped search), an entry, or a page turn at an end. */
private sealed interface Slot {
    data class Head(val group: SearchGroup) : Slot
    data class Item(val asset: Asset, val group: LibraryTab?) : Slot
    data class Turn(val forward: Boolean) : Slot
}

/**
 * The catalogue bar along the bottom of the placement editor (the Sims' build-mode catalogue in the game's own pieces):
 *
 * - **Round tabs** (CatalogueBar.tabs): Starter pack, Props, Buildings, Creatures, My NPCs, Favourites, Recent; the chosen one
 *   raised in a gold ring, the counts on their rims. The magnifying glass opens the search box in their place (the words
 *   search everything, grouped by Props, Buildings, Creatures and My NPCs a moment after the last letter, the matches lit).
 * - **Category chips** (CatalogueBar.chips): All, the tab's biggest categories with their icons and counts, More… (the
 *   spellbook-page flyout, [PlaceCategoryFlyout]); the Starter pack's chips are its styles.
 * - **Picture cards** in a sideways row (engine/Thumbs: asked for as a card comes on screen; the name until it is drawn),
 *   the star for Favourites, the armed one gold; the page turns at the row's ends (40 a page).
 * - The bar folds to its tabs ([CatalogueBar.open]); it is never taller than [layout] allows.
 */
@Composable
fun PlaceCatalogueBar(
    library: Library?,
    own: List<Asset>,
    query: LibraryQuery,
    onQuery: (LibraryQuery) -> Unit,
    prefs: LibraryPrefs,
    onFavourite: (Asset) -> Unit,
    thumbs: ThumbSource?,
    thumbError: String?,
    layout: BuildLayout,
    bar: CatalogueBar,
    onBar: (CatalogueBar) -> Unit,
    armed: String?,
    onPick: (Asset) -> Unit,
    modifier: Modifier = Modifier,
) {
    WoodBar(modifier.pointerInput(Unit) { detectTapGestures { } }) {
        val starterCount = remember(library, query.style) { library?.starterPack(query.style)?.size ?: 0 }
        val tree = remember(library, own.size, prefs, starterCount) { library?.let { LibraryModel.tree(it, own.size, prefs, starterCount) }.orEmpty() }
        val chips = remember(tree, query) { CatalogueBar.chips(tree, query) }
        val styleChips = remember(library, query.tab) { if (query.tab == LibraryTab.STARTER) library?.styles().orEmpty() else emptyList() }

        val chipRow: @Composable () -> Unit = {
            if (styleChips.isNotEmpty()) for (s in styleChips) Chip(s, null, query.style == s, { onQuery(query.withStyle(s)) })
            for (c in chips) Chip(c.label, c.count.takeIf { !c.more }, c.on, {
                if (c.more) onBar(bar.openFlyout()) else c.node?.let { onQuery(LibraryModel.apply(query, it)) }
            }, icon = c.icon)
        }

        // ---- row 1: search, the round tabs (or the search box), the fold
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoundIconButton(Face(glyph = WarIcons.SEARCH), { onBar(bar.search(!bar.searching)); if (bar.searching) onQuery(query.withText("")) },
                size = 48.dp, lit = bar.searching, description = "Search")
            if (bar.searching) {
                WarSearch(query.text, { onQuery(query.withText(it.take(60))) }, "Search everything (chair, lamp, wolf…)", { onQuery(query.withText("")) },
                    Modifier.weight(1f))
            } else LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                items(CatalogueBar.tabs(tree), key = { it.tab.name }) { t ->
                    CategoryTab(t, query.tab == t.tab && !query.grouped, { onQuery(query.withTab(t.tab)); if (!bar.open) onBar(bar.toggle()) },
                        showCount = t.tab in COUNTED)
                }
                if (!layout.chipRow && bar.open) item(key = "chips") { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { chipRow() } }
            }
            RoundIconButton(Face(glyph = if (bar.open) WarIcons.MINUS else WarIcons.PLUS), { onBar(bar.toggle()) }, size = 48.dp,
                description = if (bar.open) "Fold the catalogue" else "Open the catalogue")
        }
        if (!bar.open) return@WoodBar
        if (library == null) {
            Text("Reading the library (catalog.tsv and the game's creatures)…", style = warText(14.sp, War.ink, true), modifier = Modifier.padding(8.dp))
            return@WoodBar
        }

        // ---- row 2: the chips
        if (layout.chipRow && !query.grouped && (chips.isNotEmpty() || styleChips.isNotEmpty())) {
            LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chipRow() } }
            }
        }

        // ---- row 3: the cards
        val favourites = remember(prefs.favourites, library, own) { LibraryModel.resolve(prefs.favourites, library, own) }
        val recent = remember(prefs.recent, library, own) { LibraryModel.resolve(prefs.recent, library, own) }
        // the words searched a moment after the last letter (the whole library is some 70,000 entries); the rest at once
        var searched by remember { mutableStateOf(query) }
        LaunchedEffect(query) {
            if (query.text != searched.text) delay(180)
            searched = query
        }
        val shown by produceState<Shown?>(null, library, own, searched, favourites, recent) {
            val q = searched
            value = withContext(Dispatchers.Default) {
                if (q.grouped) Shown.Groups(LibraryModel.grouped(library, own, q.text))
                else Shown.One(LibraryPager.page(library, own, q, favourites, recent))
            }
        }
        val slots = remember(shown, query.page) {
            when (val s = shown) {
                null -> emptyList()
                is Shown.Groups -> s.groups.flatMap { g -> listOf<Slot>(Slot.Head(g)) + g.items.map { Slot.Item(it, g.tab) } }
                is Shown.One -> buildList {
                    if (query.page > 0) add(Slot.Turn(false))
                    s.page.items.forEach { add(Slot.Item(it, null)) }
                    if (query.page + 1 < LibraryQuery.pages(s.page.total)) add(Slot.Turn(true))
                }
            }
        }
        val row = rememberLazyListState()
        LaunchedEffect(searched) { row.scrollToItem(0) }
        val cardDp = layout.card.dp
        val px = with(LocalDensity.current) { LibraryModel.thumbPx(cardDp.toPx()) }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(cardDp + WarSize.CARD_NAME.dp)) {
            when {
                shown == null -> Text("Searching…", style = warText(14.sp, War.ink, true), modifier = Modifier.align(Alignment.CenterStart))
                slots.isEmpty() -> Text(
                    if (shown is Shown.Groups) "Nothing matches \"${searched.text}\"." else emptyLine(query, own.isEmpty()),
                    style = warText(14.sp, War.ink, true), modifier = Modifier.align(Alignment.CenterStart).padding(8.dp),
                )
                else -> LazyRow(Modifier.fillMaxWidth().warScrollbarRow(), row, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(slots, key = { s -> when (s) { is Slot.Head -> "h:${s.group.tab}"; is Slot.Item -> "${s.group}:${s.asset.key}"; is Slot.Turn -> "t:${s.forward}" } }) { s ->
                        when (s) {
                            is Slot.Head -> GroupTag(s.group, cardDp) { onQuery(query.showAll(s.group.tab)) }
                            is Slot.Item -> AssetCard(s.asset, searched.text, prefs.isFavourite(s.asset.key), s.asset.key == armed, thumbs, px, cardDp, onPick, onFavourite)
                            is Slot.Turn -> {
                                val total = (shown as? Shown.One)?.page?.total ?: 0
                                Column(Modifier.width(cardDp).height(cardDp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    RoundIconButton(Face(glyph = if (s.forward) WarIcons.PLAY else WarIcons.BACK),
                                        { onQuery(if (s.forward) query.next(total) else query.prev()) },
                                        label = if (s.forward) "Page ${query.page + 2}" else "Page ${query.page}")
                                }
                            }
                        }
                    }
                }
            }
        }
        val line = listOfNotNull(
            thumbError,
            if (query.scoped && query.text.isNotBlank()) "Showing ${query.tab.label} only" else null,
        )
        if (line.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line.joinToString(" · "), style = warText(12.sp, War.ink, true), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (query.scoped && query.text.isNotBlank()) Chip("Back to everything", null, false, { onQuery(query.copy(scoped = false, page = 0)) })
        }
    }
}

/** The tabs whose counts are worth a badge (the small ones; Props' 48,000 would only crowd the rim). */
private val COUNTED = setOf(LibraryTab.STARTER, LibraryTab.MY_NPCS, LibraryTab.FAVOURITES, LibraryTab.RECENT)

/** No scroll thumb for the sideways row (its last card half out of the bar says there is more). */
private fun Modifier.warScrollbarRow(): Modifier = this

/** A group's tag in the grouped search: its name and count on parchment, All to page through all of it. */
@Composable
private fun GroupTag(g: SearchGroup, size: androidx.compose.ui.unit.Dp, onAll: () -> Unit) {
    Column(Modifier.width(size).height(size), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(g.label, style = warTitle(18.sp, War.gold, true), maxLines = 1)
        Text("%,d".format(g.total), style = warText(12.sp, War.ink, true))
        if (g.total > g.items.size) Chip("All", null, false, onAll)
    }
}

/**
 * The whole category tree of the chosen tab on a spellbook page over the bar (More…): its categories with their icons and
 * counts, then its styles; a tap narrows the catalogue to it (again: back to all) and closes the page.
 */
@Composable
fun PlaceCategoryFlyout(
    library: Library,
    ownCount: Int,
    prefs: LibraryPrefs,
    query: LibraryQuery,
    onPick: (LibraryQuery) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val starter = remember(library, query.style) { library.starterPack(query.style).size }
    val tree = remember(library, ownCount, prefs, starter) { LibraryModel.tree(library, ownCount, prefs, starter) }
    val sections = remember(tree, query.tab) { CatalogueBar.flyout(tree, query) }
    val sel = LibraryModel.selectedId(query)
    BookPage(modifier) {
        Column {
            BookHeader(null, "${query.tab.label}: every category") {
                RoundIconButton(Face(glyph = WarIcons.CLOSE), onClose, size = 48.dp, description = "Close")
            }
            LazyVerticalGrid(GridCells.Adaptive(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((title, rows) in sections) {
                    item(key = "h:$title", span = { GridItemSpan(maxLineSpan) }) { Text(title, style = warText(13.sp, War.brownSoft), modifier = Modifier.padding(top = 4.dp)) }
                    items(rows, key = { it.id }) { n ->
                        WarRow({ onPick(LibraryModel.apply(query, n)) }, selected = n.id == sel, icon = n.category?.let(WarIcons::category)) {
                            Text(n.label, style = warText(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("%,d".format(n.count), style = warText(12.sp, War.brownSoft))
                        }
                    }
                }
            }
        }
    }
}

private fun emptyLine(q: LibraryQuery, noCast: Boolean): String = when {
    q.tab == LibraryTab.MY_NPCS && noCast -> "No NPCs in the cast yet: make one in Cast."
    q.tab == LibraryTab.FAVOURITES && q.text.isBlank() -> "No favourites yet: tap the star on a card."
    q.tab == LibraryTab.RECENT && q.text.isBlank() -> "Nothing placed yet: what you place shows here."
    else -> "Nothing matches."
}

/** A name with the searched words lit. */
private fun lit(name: String, words: String): AnnotatedString = buildAnnotatedString {
    append(name)
    for (r in LibraryModel.highlight(name, words)) addStyle(SpanStyle(color = War.gold, fontWeight = FontWeight.Bold), r.first, r.last + 1)
}

/** One entry as a picture card: its picture (or its name until the picture is drawn; an NPC's initial), its star, its name. */
@Composable
private fun AssetCard(
    a: Asset,
    words: String,
    favourite: Boolean,
    armed: Boolean,
    thumbs: ThumbSource?,
    px: Int,
    size: androidx.compose.ui.unit.Dp,
    onPick: (Asset) -> Unit,
    onFavourite: (Asset) -> Unit,
) {
    val pictured = thumbs != null && LibraryModel.thumbOf(a) != null
    val bmp by produceState<Bitmap?>(if (pictured) thumbs?.cached(a, px) else null, a.key, px, pictured) {
        if (value == null && pictured) thumbs?.request(a, px) { value = it }
    }
    val img = remember(bmp) { bmp?.asImageBitmap() }
    ItemCard(
        lit(a.name, words), img, size, armed, favourite,
        onClick = { onPick(a) }, onFavourite = { onFavourite(a) },
        placeholder = if (pictured) a.name else a.name.take(1).uppercase(),
    )
}
