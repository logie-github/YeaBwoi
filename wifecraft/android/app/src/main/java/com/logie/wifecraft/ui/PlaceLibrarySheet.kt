package com.logie.wifecraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.catalog.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.logie.wifecraft.catalog.Catalog as Library

/**
 * The library: Starter pack (the stage's everyday things, by style), Props, Buildings, Creatures (the game's named
 * creatures and its creature models) and My NPCs (the project's cast); a search box, category chips, pages of
 * [LibraryQuery.PAGE]. Entries are names only (no pictures: a model is fetched once it is placed). A pick is added in the
 * middle of the view. The sheet never grows past the screen: the list scrolls inside it.
 */
@Composable
fun PlaceLibrarySheet(
    library: Library?,
    own: List<Asset>,
    query: LibraryQuery,
    onQuery: (LibraryQuery) -> Unit,
    onPick: (Asset) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(onClick = onClose))
    MetalPanel(modifier.pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Library", style = morpheusStyle(22.sp), modifier = Modifier.weight(1f))
                PButton("Close", onClose, width = 100.dp)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in LibraryTab.values()) PToggle(t.label, query.tab == t, { onQuery(query.withTab(t)) }, width = 136.dp)
            }
            EditorField(query.text, { onQuery(query.withText(it.take(60))) }, "Search by name")
            if (library == null) {
                Text("Reading the library (catalog.tsv and the game's creatures)…", style = frizStyle(14.sp, WowColors.grey, false))
                return@Column
            }
            val chips = remember(library, query.tab, query.style) { LibraryPager.chips(library, query) }
            if (query.tab == LibraryTab.STARTER) {
                val styles = remember(library) { library.styles() }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (s in styles) PToggle(s, query.style == s, { onQuery(query.withStyle(s)) }, width = 112.dp)
                }
            }
            if (chips.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in chips) PToggle(c, query.category == c, { onQuery(query.withCategory(c)) }, width = 150.dp)
            }
            // a page is searched off the main thread (the whole library is some 70,000 entries)
            val page by produceState<Page?>(null, library, own, query) {
                value = withContext(Dispatchers.Default) { LibraryPager.page(library, own, query) }
            }
            val p = page
            val list = rememberLazyListState()
            LaunchedEffect(query) { list.scrollToItem(0) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    p == null -> Text("Searching…", style = frizStyle(14.sp, WowColors.grey, false))
                    p.items.isEmpty() -> Text(
                        if (query.tab == LibraryTab.MY_NPCS && own.isEmpty()) "No NPCs in the cast yet: make one in Cast." else "Nothing matches.",
                        style = frizStyle(14.sp, WowColors.grey, false), modifier = Modifier.padding(6.dp),
                    )
                    else -> LazyColumn(Modifier.fillMaxSize(), list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(p.items, key = { it.key }) { a ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Color(0x66000000)).border(1.dp, Color(0x55FFD100))
                                    .clickable { onPick(a) }.padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.name, style = frizStyle(15.sp, Color.White, false), maxLines = 1)
                                    Text(LibraryPager.detail(a), style = frizStyle(11.sp, WowColors.grey, false), maxLines = 1)
                                }
                                Text("Add", style = frizStyle(14.sp))
                            }
                        }
                    }
                }
            }
            if (p != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.height(48.dp)) {
                PButton("Back", { onQuery(query.prev()) }, enabled = query.page > 0, width = 96.dp)
                Text(
                    "Page ${query.page + 1} of ${LibraryQuery.pages(p.total)} · ${p.total} found",
                    style = frizStyle(13.sp, Color.White), modifier = Modifier.weight(1f),
                )
                PButton("Next", { onQuery(query.next(p.total)) }, enabled = query.page + 1 < LibraryQuery.pages(p.total), width = 96.dp)
            }
        }
    }
}
