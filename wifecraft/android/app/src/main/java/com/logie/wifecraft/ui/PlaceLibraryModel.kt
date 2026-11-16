package com.logie.wifecraft.ui

import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.catalog.AssetKind
import com.logie.wifecraft.catalog.Catalog
import com.logie.wifecraft.editor.LibraryPrefs

// Pure Kotlin, no android.* and no Compose: what the library sheet (PlaceLibrarySheet) shows, JVM-tested
// (unit-tests/.../ui/PlaceLibraryModelTest.kt): the category tree with counts (the rail on a wide screen, two rows of
// chips on a narrow one), the search-as-you-type grouped by Props, Buildings, Creatures and My NPCs, the words to
// highlight in a name, favourites and recent picks as entries, and the sheet's size from the screen's.

/**
 * A row of the library's tree: Favourites, Recent, Starter pack, Props, Buildings, Creatures, My NPCs (each with how many
 * entries), and under Props, Buildings and Creatures their categories ([category]) and styles ([style]). [id] names it.
 */
data class RailNode(
    val id: String,
    val label: String,
    val count: Int,
    val tab: LibraryTab,
    val category: String? = null,
    val style: String? = null,
    val children: List<RailNode> = emptyList(),
)

/** One group of the grouped search: its tab, its label with [total] matches, its best [items]. */
data class SearchGroup(val tab: LibraryTab, val label: String, val total: Int, val items: List<Asset>)

/** How the sheet is laid out: a side panel [widthDp] wide (the stage stays visible beside it) or a bottom sheet [heightDp] high. */
data class SheetSpec(val side: Boolean, val widthDp: Float, val heightDp: Float, val rail: Boolean)

object LibraryModel {
    /** The tree's rows. [own]: the cast's count; [prefs]: the project's lists; [starter]: the starter pack's size. */
    fun tree(library: Catalog, own: Int, prefs: LibraryPrefs, starter: Int): List<RailNode> {
        val kinds = library.tree.map { k ->
            val tab = when (k.kinds) { Catalog.PROP_KINDS -> LibraryTab.PROPS; Catalog.BUILDING_KINDS -> LibraryTab.BUILDINGS; else -> LibraryTab.CREATURES }
            RailNode(tab.name, k.label, k.count, tab, children = k.children.map { c ->
                RailNode(
                    tab.name + (c.category?.let { ":c:$it" } ?: ":s:${c.style}"),
                    if (c.style != null) "${c.label} style" else c.label, c.count, tab, c.category, c.style,
                )
            })
        }
        return listOf(
            RailNode(LibraryTab.FAVOURITES.name, LibraryTab.FAVOURITES.label, prefs.favourites.size, LibraryTab.FAVOURITES),
            RailNode(LibraryTab.RECENT.name, LibraryTab.RECENT.label, prefs.recent.size, LibraryTab.RECENT),
            RailNode(LibraryTab.STARTER.name, LibraryTab.STARTER.label, starter, LibraryTab.STARTER),
        ) + kinds + RailNode(LibraryTab.MY_NPCS.name, LibraryTab.MY_NPCS.label, own, LibraryTab.MY_NPCS)
    }

    /** The row a query stands on (a category's or a style's under its kind, else the kind's). */
    fun selectedId(q: LibraryQuery): String = when {
        q.category != null && q.tab in KIND_TABS -> "${q.tab.name}:c:${q.category}"
        q.styleFilter != null -> "${q.tab.name}:s:${q.styleFilter}"
        else -> q.tab.name
    }

    /** The query once a row is tapped: a kind row opens it (all of it); a category or style row narrows to it, or back. */
    fun apply(q: LibraryQuery, n: RailNode): LibraryQuery {
        val base = q.withTab(n.tab)
        return when {
            n.category != null -> if (q.tab == n.tab && q.category == n.category) base.copy(category = null, page = 0) else base.copy(category = n.category, styleFilter = null, page = 0)
            n.style != null -> if (q.tab == n.tab && q.styleFilter == n.style) base.copy(styleFilter = null, page = 0) else base.copy(styleFilter = n.style, category = null, page = 0)
            else -> base.copy(category = null, styleFilter = null, page = 0)
        }
    }

    private val KIND_TABS = setOf(LibraryTab.PROPS, LibraryTab.BUILDINGS, LibraryTab.CREATURES)

    /**
     * The search as it is typed: [text] in Props, Buildings, Creatures (one pass over the library) and the cast ([own]),
     * each group's [perGroup] best and its count; groups with no match are left out.
     */
    fun grouped(library: Catalog, own: List<Asset>, text: String, perGroup: Int = 12): List<SearchGroup> {
        val pages = library.searchGrouped(text, Catalog.TREE_KINDS.map { it.second }, perGroup)
        val tabs = listOf(LibraryTab.PROPS, LibraryTab.BUILDINGS, LibraryTab.CREATURES)
        val out = tabs.indices.map { i -> SearchGroup(tabs[i], tabs[i].label, pages[i].total, pages[i].items) }.toMutableList()
        val mine = Catalog(own).search(text, null, null, null, 0, perGroup)
        out += SearchGroup(LibraryTab.MY_NPCS, LibraryTab.MY_NPCS.label, mine.total, mine.items)
        return out.filter { it.total > 0 }
    }

    /** The project's favourites or recent picks ([keys]) as entries, in their order; a key no longer in the library is left out. */
    fun resolve(keys: List<String>, library: Catalog?, own: List<Asset>): List<Asset> {
        val mine = own.associateBy { it.key }
        return keys.mapNotNull { k -> mine[k] ?: library?.get(k) }
    }

    /**
     * The parts of [text] that the typed [query] matched, to draw highlighted: each word of the query where a word of the
     * text begins with it, or (three letters or more) wherever it is inside the text (as Catalog.search matches).
     * Sorted, joined where they touch.
     */
    fun highlight(text: String, query: String): List<IntRange> {
        val lower = text.lowercase()
        val words = WORD.findAll(query.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return emptyList()
        val starts = WORD.findAll(lower).map { it.range.first }.toList()
        val found = ArrayList<IntRange>()
        for (w in words) {
            var any = false
            for (s in starts) if (lower.startsWith(w, s)) { found += s until s + w.length; any = true }
            if (!any && w.length >= 3) {
                var i = lower.indexOf(w)
                while (i >= 0) { found += i until i + w.length; i = lower.indexOf(w, i + 1) }
            }
        }
        val sorted = found.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out += r
        }
        return out
    }

    /**
     * The sheet for a screen [wDp] x [hDp]: a side panel on a wide screen (at least 600 dp and not much taller than wide:
     * the Fold's inner screen, a tablet, a phone on its side), half the width within 340 to 560 dp (at least 280 dp of the
     * stage left beside it) and the whole height, with the tree as a rail inside it when it is 400 dp or more; a bottom sheet the whole width and at most 55% of the
     * height (and never under 240 dp unless the screen is smaller) otherwise, the tree as chips.
     */
    fun sheet(wDp: Float, hDp: Float): SheetSpec {
        val side = wDp >= 600f && wDp >= hDp * 0.9f
        return if (side) {
            val w = (wDp * 0.5f).coerceIn(340f, 560f).coerceAtMost(wDp - 280f)
            SheetSpec(true, w, hDp, rail = w >= 400f)
        } else {
            SheetSpec(false, wDp, (hDp * 0.55f).coerceAtLeast(minOf(240f, hDp)).coerceAtMost(hDp), rail = false)
        }
    }

    /** Picture cards across [widthDp]: as many as fit at [minCellDp] or more each, at least two. */
    fun columns(widthDp: Float, minCellDp: Float = 104f): Int = maxOf(2, (widthDp / minCellDp).toInt())

    /** The size of a thumbnail to ask for (pixels): the card's, rounded up to 32, within 96 to 256. */
    fun thumbPx(cellPx: Float): Int = (((cellPx.toInt() + 31) / 32) * 32).coerceIn(96, 256)

    /** What a thumbnail is asked for by: a prop's or a building's file, a creature's display; null for an own NPC (no picture). */
    fun thumbOf(a: Asset): Pair<Boolean, Int>? = when (a.kind) {
        AssetKind.PROP, AssetKind.BUILDING -> a.fileId.takeIf { it > 0 }?.let { false to it }
        AssetKind.CREATURE, AssetKind.CREATURE_MODEL -> a.display.takeIf { it > 0 }?.let { true to it }
        AssetKind.OWN_NPC -> null
    }

    private val WORD = Regex("[\\p{L}\\p{N}']+")
}
