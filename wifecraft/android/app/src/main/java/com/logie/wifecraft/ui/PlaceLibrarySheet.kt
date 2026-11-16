package com.logie.wifecraft.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/** What the grid shows: the grouped search, or a page of one tab. */
private sealed interface Shown {
    data class Groups(val groups: List<SearchGroup>) : Shown
    data class One(val page: Page) : Shown
}

/**
 * The library: picture cards (engine/Thumbs, asked for as a card comes on screen; the name until the picture is drawn), a
 * star on each for Favourites, the project's Recent picks, the category tree with counts (Favourites, Recent, Starter pack,
 * Props, Buildings, Creatures with their categories and styles, My NPCs: a rail on a wide sheet, two rows of chips on a
 * narrow one) and a search that narrows as you type (a moment after the last letter), grouped by Props, Buildings,
 * Creatures and My NPCs with their counts and the matched words lit. Tapping a card arms it ([onPick]: the placing mode).
 * The sheet is laid out by [spec] (LibraryModel.sheet): a side panel beside the stage, or a bottom sheet over its lower part;
 * it never grows past the screen (the grid scrolls inside it).
 */
@Composable
fun PlaceLibrarySheet(
    library: Library?,
    own: List<Asset>,
    query: LibraryQuery,
    onQuery: (LibraryQuery) -> Unit,
    prefs: LibraryPrefs,
    onFavourite: (Asset) -> Unit,
    thumbs: ThumbSource?,
    thumbError: String?,
    spec: SheetSpec,
    armed: String?,
    onPick: (Asset) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MetalPanel(modifier.pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Library", style = morpheusStyle(22.sp), modifier = Modifier.weight(1f))
                if (query.text.isNotEmpty()) PButton("Clear", { onQuery(query.withText("")) }, width = 84.dp)
                PButton("Close", onClose, width = 90.dp)
            }
            EditorField(query.text, { onQuery(query.withText(it.take(60))) }, "Search everything (chair, lamp, wolf…)")
            if (library == null) {
                Text("Reading the library (catalog.tsv and the game's creatures)…", style = frizStyle(14.sp, WowColors.grey, false))
                return@Column
            }
            val starterCount = remember(library, query.style) { library.starterPack(query.style).size }
            val tree = remember(library, own.size, prefs, starterCount) { LibraryModel.tree(library, own.size, prefs, starterCount) }
            val favourites = remember(prefs.favourites, library, own) { LibraryModel.resolve(prefs.favourites, library, own) }
            val recent = remember(prefs.recent, library, own) { LibraryModel.resolve(prefs.recent, library, own) }

            val content: @Composable (Modifier) -> Unit = { m ->
                LibraryContent(library, own, query, onQuery, prefs, favourites, recent, onFavourite, thumbs, thumbError, armed, onPick, m)
            }
            if (spec.rail) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LibraryRail(tree, query, onQuery, Modifier.width(156.dp).fillMaxHeight())
                    content(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                LibraryChips(tree, query, onQuery)
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The tree as a rail: the kinds with their counts, the open one's categories and styles under it. */
@Composable
private fun LibraryRail(tree: List<RailNode>, query: LibraryQuery, onQuery: (LibraryQuery) -> Unit, modifier: Modifier) {
    val sel = LibraryModel.selectedId(query)
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (n in tree) {
            item(key = n.id) { RailRow(n, depth = 0, on = sel == n.id || (query.tab == n.tab && sel.startsWith(n.id + ":"))) { onQuery(LibraryModel.apply(query, n)) } }
            if (query.tab == n.tab) items(n.children, key = { it.id }) { c ->
                RailRow(c, depth = 1, on = sel == c.id) { onQuery(LibraryModel.apply(query, c)) }
            }
        }
    }
}

@Composable
private fun RailRow(n: RailNode, depth: Int, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(if (on) Color(0x55FFD100) else if (depth == 0) Color(0x44000000) else Color.Transparent)
            .clickable(onClick = onClick).padding(start = (8 + depth * 14).dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(n.label, style = frizStyle(if (depth == 0) 14.sp else 13.sp, if (on) Color.White else if (depth == 0) WowColors.gold else Color(0xFFDDDDDD), depth == 0),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(count(n.count), style = frizStyle(11.sp, WowColors.grey, false), maxLines = 1)
    }
}

/** The tree as two rows of chips (a narrow sheet): the kinds, then the open kind's categories and styles. */
@Composable
private fun LibraryChips(tree: List<RailNode>, query: LibraryQuery, onQuery: (LibraryQuery) -> Unit) {
    val sel = LibraryModel.selectedId(query)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (n in tree) PToggle("${n.label} ${count(n.count)}", query.tab == n.tab, { onQuery(LibraryModel.apply(query, n)) }, width = 150.dp)
    }
    val open = tree.firstOrNull { it.tab == query.tab }?.children.orEmpty()
    if (open.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (c in open.take(40)) PToggle("${c.label} ${count(c.count)}", sel == c.id, { onQuery(LibraryModel.apply(query, c)) }, width = 170.dp)
    }
}

@Composable
private fun LibraryContent(
    library: Library,
    own: List<Asset>,
    query: LibraryQuery,
    onQuery: (LibraryQuery) -> Unit,
    prefs: LibraryPrefs,
    favourites: List<Asset>,
    recent: List<Asset>,
    onFavourite: (Asset) -> Unit,
    thumbs: ThumbSource?,
    thumbError: String?,
    armed: String?,
    onPick: (Asset) -> Unit,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (query.tab == LibraryTab.STARTER && !query.grouped) {
            val styles = remember(library) { library.styles() }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (s in styles) PToggle(s, query.style == s, { onQuery(query.withStyle(s)) }, width = 112.dp)
            }
        }
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
        val grid = rememberLazyGridState()
        LaunchedEffect(searched) { grid.scrollToItem(0) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cols = LibraryModel.columns(maxWidth.value)
            val cellPx = with(LocalDensity.current) { (maxWidth / cols).toPx() }
            val px = LibraryModel.thumbPx(cellPx)
            val card: @Composable (Asset) -> Unit = { a ->
                AssetCard(a, searched.text, prefs.isFavourite(a.key), a.key == armed, thumbs, px, onPick, onFavourite)
            }
            when (val s = shown) {
                null -> Text("Searching…", style = frizStyle(14.sp, WowColors.grey, false))
                is Shown.Groups -> if (s.groups.isEmpty()) Empty("Nothing matches \"${searched.text}\".") else
                    LazyVerticalGrid(GridCells.Fixed(cols), Modifier.fillMaxSize(), grid, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (g in s.groups) {
                            item(key = "h:${g.tab}", span = { GridItemSpan(maxLineSpan) }) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${g.label} · ${count(g.total)}", style = frizStyle(15.sp), modifier = Modifier.weight(1f))
                                    if (g.total > g.items.size) PButton("All ${count(g.total)}", { onQuery(query.showAll(g.tab)) }, width = 120.dp)
                                }
                            }
                            items(g.items, key = { "${g.tab}:${it.key}" }) { a -> card(a) }
                        }
                    }
                is Shown.One -> if (s.page.items.isEmpty()) Empty(emptyLine(query, own.isEmpty())) else
                    LazyVerticalGrid(GridCells.Fixed(cols), Modifier.fillMaxSize(), grid, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(s.page.items, key = { it.key }) { a -> card(a) }
                    }
            }
        }
        val page = (shown as? Shown.One)?.page
        if (page != null && page.total > LibraryQuery.PAGE) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(48.dp)) {
            PButton("Back", { onQuery(query.prev()) }, enabled = query.page > 0, width = 90.dp)
            Text("Page ${query.page + 1} of ${LibraryQuery.pages(page.total)} · ${count(page.total)}", style = frizStyle(13.sp, Color.White), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            PButton("Next", { onQuery(query.next(page.total)) }, enabled = query.page + 1 < LibraryQuery.pages(page.total), width = 90.dp)
        }
        if (query.scoped && query.text.isNotBlank()) PButton("Back to everything", { onQuery(query.copy(scoped = false, page = 0)) }, width = 200.dp)
        thumbError?.let { Text(it, style = frizStyle(12.sp, Color(0xFFFF6A5A), false), maxLines = 2) }
    }
}

@Composable
private fun Empty(line: String) = Text(line, style = frizStyle(14.sp, WowColors.grey, false), modifier = Modifier.padding(6.dp))

private fun emptyLine(q: LibraryQuery, noCast: Boolean): String = when {
    q.tab == LibraryTab.MY_NPCS && noCast -> "No NPCs in the cast yet: make one in Cast."
    q.tab == LibraryTab.FAVOURITES && q.text.isBlank() -> "No favourites yet: tap the star on a card."
    q.tab == LibraryTab.RECENT && q.text.isBlank() -> "Nothing placed yet: what you place shows here."
    else -> "Nothing matches."
}

private fun count(n: Int): String = "%,d".format(n)

/** A name with the searched words lit. */
private fun lit(name: String, words: String): AnnotatedString = buildAnnotatedString {
    append(name)
    for (r in LibraryModel.highlight(name, words)) addStyle(SpanStyle(color = WowColors.gold, fontWeight = FontWeight.Bold), r.first, r.last + 1)
}

private fun kindLabel(a: Asset): String = when (a.kind) {
    AssetKind.PROP -> "Prop"; AssetKind.BUILDING -> "Building"; AssetKind.CREATURE_MODEL -> "Creature model"
    AssetKind.CREATURE -> "Creature"; AssetKind.OWN_NPC -> "Your NPC"
}

/** One entry: its picture (or its name until the picture is drawn, an NPC's always), its star, its name and what it is. */
@Composable
private fun AssetCard(
    a: Asset,
    words: String,
    favourite: Boolean,
    armed: Boolean,
    thumbs: ThumbSource?,
    px: Int,
    onPick: (Asset) -> Unit,
    onFavourite: (Asset) -> Unit,
) {
    val pictured = thumbs != null && LibraryModel.thumbOf(a) != null
    val bmp by produceState<Bitmap?>(if (pictured) thumbs?.cached(a, px) else null, a.key, px, pictured) {
        if (value == null && pictured) thumbs?.request(a, px) { value = it }
    }
    Column(
        Modifier.fillMaxWidth().background(if (armed) Color(0x55FFD100) else Color(0x66000000))
            .border(if (armed) 2.dp else 1.dp, if (armed) WowColors.gold else Color(0x55FFD100))
            .clickable { onPick(a) }.padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF14100A))) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(
                if (pictured) a.name else a.name.take(1).uppercase(),
                style = frizStyle(if (pictured) 11.sp else 28.sp, WowColors.grey, false), textAlign = TextAlign.Center, maxLines = 4,
                modifier = Modifier.align(Alignment.Center).padding(4.dp),
            )
            Box(
                Modifier.align(Alignment.TopEnd).size(48.dp).clickable { onFavourite(a) },
                contentAlignment = Alignment.TopEnd,
            ) {
                Text(if (favourite) "★" else "☆", style = frizStyle(22.sp, if (favourite) WowColors.gold else Color(0xAAFFFFFF)), modifier = Modifier.padding(4.dp))
            }
        }
        Text(lit(a.name, words), style = frizStyle(12.sp, Color.White, false), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(kindLabel(a), a.category.takeIf { it.isNotBlank() && a.kind != AssetKind.OWN_NPC }).joinToString(" · "),
            style = frizStyle(10.sp, WowColors.grey, false), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
