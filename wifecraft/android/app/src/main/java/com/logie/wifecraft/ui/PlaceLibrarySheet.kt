package com.logie.wifecraft.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.catalog.Asset
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
 * The Library: a WoW window (the spellbook's parchment in the metal frame, its title, the red close X) with four tabs along
 * its bottom edge ([LibraryWindowTab]: Props, Buildings, People, Favourites), one search box, one category dropdown with its
 * counts ([LibraryWindow.dropdown]) and a grid of picture cards (engine/Thumbs: drawn as a card comes on screen; the name
 * until it is). Props starts with "Suggested for this place" (the place's starter pack); Favourites starts with the recent
 * picks. Words search everything, grouped (Props, Buildings, Creatures, My NPCs; **Show all** on a group pages through it),
 * and inside the chosen category when one is picked. Tapping a card arms it for placing ([onPick]: the window closes so the
 * scene is visible); the star keeps it in Favourites. Its size is [width] x [height] (ChromeLayout: never taller than the
 * screen); the grid scrolls inside.
 */
@Composable
fun LibraryWindowView(
    library: Library?,
    own: List<Asset>,
    query: LibraryQuery,
    onQuery: (LibraryQuery) -> Unit,
    prefs: LibraryPrefs,
    onFavourite: (Asset) -> Unit,
    thumbs: ThumbSource?,
    thumbError: String?,
    armed: String?,
    onPick: (Asset) -> Unit,
    onClose: () -> Unit,
    width: Dp,
    height: Dp,
    card: Dp,
) {
    val tab = LibraryWindowTab.of(query.tab)
    Overlay(onClose) {
        WowWindow(
            "Library", onClose, Modifier.width(width).height(height + ChromeLayout.TABS.dp), body = WindowBody.PARCHMENT,
            tabs = {
                for (t in LibraryWindowTab.values()) WowTab(t.label, t == tab, { onQuery(LibraryWindow.select(query, t, own.size)) })
            },
        ) {
            if (library == null) {
                Text("Reading the library (catalog.tsv and the game's creatures)…", style = warText(14.sp), modifier = Modifier.padding(8.dp))
                return@WowWindow
            }
            val starterCount = remember(library, query.style) { library.starterPack(query.style).size }
            val tree = remember(library, own.size, prefs, starterCount) { LibraryModel.tree(library, own.size, prefs, starterCount) }
            val rows = remember(tree, query.tab) { LibraryWindow.dropdown(tree, query) }

            // ---- the search box and the category dropdown
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                WowSearchBox(query.text, { onQuery(query.withText(it.take(60))) }, LibraryWindow.hint(tab), Modifier.weight(1f))
                if (rows.isNotEmpty()) {
                    val chosen = LibraryWindow.chosen(rows, query)
                    WowDropdown(
                        LibraryWindow.buttonLabel(chosen),
                        rows.map { r -> r to if (r.header) r.label else "${r.label}  (${"%,d".format(r.count)})" },
                        { r -> onQuery(LibraryWindow.apply(query, r)) },
                        Modifier.weight(0.75f),
                        headers = rows.indices.filter { rows[it].header }.toSet(),
                    )
                }
            }

            // ---- the grid
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
            val suggested = remember(library, query.style) { library.starterPack(query.style).take(LibraryWindow.TOP_ROW) }
            val grid = rememberLazyGridState()
            LaunchedEffect(searched) { grid.scrollToItem(0) }
            val px = with(LocalDensity.current) { LibraryModel.thumbPx(card.toPx()) }
            val cardOf: @Composable (Asset) -> Unit = { a ->
                AssetCard(a, searched.text, prefs.isFavourite(a.key), a.key == armed, thumbs, px, card, onPick, onFavourite)
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                when (val s = shown) {
                    null -> Text("Searching…", style = warText(14.sp), modifier = Modifier.align(Alignment.Center))
                    else -> LazyVerticalGrid(
                        GridCells.Adaptive(card + 10.dp), Modifier.fillMaxWidth().padding(end = 8.dp), grid,
                        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (s) {
                            is Shown.Groups -> {
                                if (s.groups.isEmpty()) head("none", "Nothing matches \"${searched.text}\".")
                                for (g in s.groups) {
                                    head("g:${g.tab}", "${g.label}  (${"%,d".format(g.total)})", if (g.total > g.items.size) "Show all" else null) { onQuery(query.showAll(g.tab)) }
                                    items(g.items, key = { "${g.tab}:${it.key}" }) { cardOf(it) }
                                }
                            }
                            is Shown.One -> {
                                val top = when {
                                    LibraryWindow.suggests(searched) && suggested.isNotEmpty() -> "Suggested for this place" to suggested
                                    LibraryWindow.showsRecent(searched) && recent.isNotEmpty() -> "Recently placed" to recent.take(LibraryWindow.TOP_ROW)
                                    else -> null
                                }
                                if (top != null && searched.page == 0) {
                                    head("top", top.first)
                                    items(top.second, key = { "top:${it.key}" }) { cardOf(it) }
                                }
                                val pages = LibraryQuery.pages(s.page.total)
                                val what = rows.firstOrNull()?.let { LibraryWindow.chosen(rows, searched)?.label } ?: searched.tab.label
                                head("all", if (s.page.total == 0) emptyLine(searched, own.isEmpty()) else what + if (pages > 1) "  ·  page ${searched.page + 1} of ${"%,d".format(pages)}" else "")
                                items(s.page.items, key = { "p:${it.key}" }) { cardOf(it) }
                                if (pages > 1) item(key = "pager", span = { GridItemSpan(maxLineSpan) }) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        RedButton("Previous", { onQuery(query.prev()) }, Modifier.weight(1f), enabled = query.page > 0)
                                        RedButton("Next", { onQuery(query.next(s.page.total)) }, Modifier.weight(1f), enabled = query.page + 1 < pages)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val line = listOfNotNull(thumbError, if (query.scoped && query.text.isNotBlank()) "Showing ${query.tab.label} only" else null)
            if (line.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(line.joinToString(" · "), style = warText(12.sp, War.brownSoft), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (query.scoped && query.text.isNotBlank()) TextAction("Back to everything", { onQuery(query.copy(scoped = false, page = 0)) })
            }
        }
    }
}

/** A heading across the grid (deep brown on the parchment), with a text button at its end. */
private fun LazyGridScope.head(key: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    item(key = "h:$key", span = { GridItemSpan(maxLineSpan) }) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = warText(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (action != null) Box(Modifier.height(48.dp).warPress(onClick = onAction).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(action, style = warText(14.sp, War.wood))
            }
        }
    }
}

private fun emptyLine(q: LibraryQuery, noCast: Boolean): String = when {
    q.tab == LibraryTab.MY_NPCS && noCast -> "No NPCs in the cast yet: make one in Cast (the Game Menu)."
    q.tab == LibraryTab.FAVOURITES && q.text.isBlank() -> "No favourites yet: tap the star on a card."
    q.tab == LibraryTab.RECENT && q.text.isBlank() -> "Nothing placed yet: what you place shows here."
    else -> "Nothing matches."
}

/** A name with the searched words lit. */
private fun lit(name: String, words: String): AnnotatedString = buildAnnotatedString {
    append(name)
    for (r in LibraryModel.highlight(name, words)) addStyle(SpanStyle(color = War.wood, fontWeight = FontWeight.Bold), r.first, r.last + 1)
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
    size: Dp,
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
        onLight = true,
    )
}
