package com.logie.wifecraft.ui

import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.catalog.AssetKind
import com.logie.wifecraft.catalog.Catalog
import com.logie.wifecraft.editor.LibraryPrefs
import com.logie.wifecraft.editor.ProjectStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The library sheet's model: the tree with counts, grouped search, highlights, favourites and recents, the sheet's size. */
class PlaceLibraryModelTest {
    private val lib = Catalog(
        listOf(
            Asset("p:1", AssetKind.PROP, 1, "Wooden chair", "Furniture", "Human", 1),
            Asset("p:2", AssetKind.PROP, 2, "Forsaken chair", "Furniture", "Forsaken", 1),
            Asset("p:3", AssetKind.PROP, 3, "Stormwind lamp", "Lighting", "Human", 2),
            Asset("p:4", AssetKind.PROP, 4, "Duskwood lamppost", "Lighting", Catalog.ANY, 3),
            Asset("p:5", AssetKind.PROP, 5, "Rowboat", "Ships and docks", Catalog.ANY, 3),
            Asset("b:6", AssetKind.BUILDING, 6, "Chapel", "Buildings", "Human", 4),
            Asset("b:7", AssetKind.BUILDING, 7, "Chair shop", "Buildings", "Human", 4),
            Asset("m:8", AssetKind.CREATURE_MODEL, 8, "Wolf", "Beast", display = 80),
            Asset("c:9", AssetKind.CREATURE, 9, "Chairman Wolfe", "Humanoid", display = 90),
        ),
    )
    private val own = listOf(Catalog.ownNpc("alleria", "Alleria"), Catalog.ownNpc("chairy", "Chairy"))

    @Test fun theTreeCountsKindsCategoriesAndStyles() {
        val t = lib.tree
        assertEquals(listOf("Props" to 5, "Buildings" to 2, "Creatures" to 2), t.map { it.label to it.count })
        val props = t[0]
        // categories, most first (then by name), then the styles
        assertEquals(listOf("Furniture" to 2, "Lighting" to 2, "Ships and docks" to 1, "Human" to 2, "Forsaken" to 1), props.children.map { it.label to it.count })
        assertEquals("Furniture", props.children[0].category); assertEquals("Human", props.children[3].style)
        val rail = LibraryModel.tree(lib, own.size, LibraryPrefs(listOf("p:3"), listOf("p:1", "b:6")), 7)
        assertEquals(listOf("Favourites" to 1, "Recent" to 2, "Starter pack" to 7, "Props" to 5, "Buildings" to 2, "Creatures" to 2, "My NPCs" to 2), rail.map { it.label to it.count })
        assertEquals("Human style", rail[3].children[3].label)
    }

    @Test fun tappingARowNarrowsAndTappingAgainWidens() {
        val rail = LibraryModel.tree(lib, 0, LibraryPrefs(), 0)
        val furniture = rail.first { it.tab == LibraryTab.PROPS }.children.first { it.category == "Furniture" }
        val forsaken = rail.first { it.tab == LibraryTab.PROPS }.children.first { it.style == "Forsaken" }
        var q = LibraryModel.apply(LibraryQuery(), furniture)
        assertEquals(LibraryTab.PROPS, q.tab); assertEquals("Furniture", q.category)
        assertEquals("PROPS:c:Furniture", LibraryModel.selectedId(q))
        assertEquals(2, LibraryPager.page(lib, own, q).total)
        q = LibraryModel.apply(q, forsaken)
        assertNull(q.category); assertEquals("Forsaken", q.styleFilter)
        assertEquals(listOf("Forsaken chair"), LibraryPager.page(lib, own, q).items.map { it.name })
        assertEquals("PROPS:s:Forsaken", LibraryModel.selectedId(q))
        q = LibraryModel.apply(q, forsaken)
        assertNull(q.styleFilter); assertEquals("PROPS", LibraryModel.selectedId(q))
        // the kind row: all of it
        q = LibraryModel.apply(q.withCategory("Lighting"), rail.first { it.tab == LibraryTab.BUILDINGS })
        assertEquals(LibraryTab.BUILDINGS, q.tab); assertNull(q.category)
    }

    @Test fun searchAsYouTypeIsGroupedWithCounts() {
        val g = LibraryModel.grouped(lib, own, "chair", perGroup = 1)
        assertEquals(listOf("Props" to 2, "Buildings" to 1, "Creatures" to 1, "My NPCs" to 1), g.map { it.label to it.total })
        assertEquals(1, g[0].items.size)
        assertEquals(listOf(LibraryTab.PROPS, LibraryTab.BUILDINGS, LibraryTab.CREATURES, LibraryTab.MY_NPCS), g.map { it.tab })
        // groups with nothing are left out
        assertEquals(listOf("Creatures"), LibraryModel.grouped(lib, own, "wolf").map { it.label }.filter { it == "Creatures" })
        assertTrue(LibraryModel.grouped(lib, own, "zzz").isEmpty())
        // grouped while words are typed and nothing narrows them; "Show all" scopes them to its tab
        var q = LibraryQuery(LibraryTab.STARTER).withText("chair")
        assertTrue(q.grouped)
        q = q.showAll(LibraryTab.PROPS)
        assertFalse(q.grouped); assertEquals(LibraryTab.PROPS, q.tab)
        assertEquals(2, LibraryPager.page(lib, own, q).total)
        assertFalse(LibraryQuery(LibraryTab.PROPS, "chair", category = "Furniture").grouped)
        assertFalse(q.withText("").grouped); assertFalse(q.withText("").scoped)
    }

    @Test fun theGroupedSearchRanksAsTheSearchDoes() {
        val pages = lib.searchGrouped("chair", listOf(Catalog.PROP_KINDS))
        assertEquals(lib.search("chair", kinds = Catalog.PROP_KINDS).items, pages[0].items)
    }

    @Test fun highlightsTheMatchedWords() {
        assertEquals(listOf(0..3, 7..9), LibraryModel.highlight("Wooden chair", "wood cha"))
        // three letters inside a word
        assertEquals(listOf(4..7), LibraryModel.highlight("Duskwoodtree 05", "wood"))
        assertEquals(listOf(0..8, 10..13), LibraryModel.highlight("Stormwind lamp", "stormwind lamp"))
        assertTrue(LibraryModel.highlight("Chapel", "").isEmpty())
        assertTrue(LibraryModel.highlight("Chapel", "zz").isEmpty())
    }

    @Test fun favouritesAndRecentsArePerProjectAndKept() {
        var p = LibraryPrefs()
        p = p.toggleFavourite("p:3").toggleFavourite("b:6")
        assertTrue(p.isFavourite("p:3"))
        p = p.toggleFavourite("p:3")
        assertEquals(listOf("b:6"), p.favourites)
        for (i in 1..40) p = p.used("p:$i")
        p = p.used("p:5")
        assertEquals(LibraryPrefs.RECENT_MAX, p.recent.size)
        assertEquals(listOf("p:5", "p:40", "p:39"), p.recent.take(3))
        assertEquals(1, p.recent.count { it == "p:5" })
        // Favourites and Recent pages keep their order; a key no longer in the library is left out
        val rec = LibraryModel.resolve(listOf("b:6", "gone", "n:alleria", "p:1"), lib, own)
        assertEquals(listOf("Chapel", "Alleria", "Wooden chair"), rec.map { it.name })
        assertEquals(listOf("Chapel", "Alleria", "Wooden chair"), LibraryPager.page(lib, own, LibraryQuery(LibraryTab.RECENT), recent = rec).items.map { it.name })
        assertEquals(listOf("Wooden chair"), LibraryPager.page(lib, own, LibraryQuery(LibraryTab.RECENT, "wood"), recent = rec).items.map { it.name })
        // on disk, beside the project
        val dir = Files.createTempDirectory("prefs").toFile()
        try {
            val store = ProjectStore(dir)
            val proj = store.newProject("T")
            assertEquals(LibraryPrefs(), store.prefs(proj.id))
            store.savePrefs(proj.id, p)
            assertEquals(p, ProjectStore(dir).prefs(proj.id))
            assertTrue(java.io.File(dir, "${proj.id}/prefs.json").isFile)
            assertEquals(listOf(proj.id), store.list().map { it.id })
            java.io.File(dir, "${proj.id}/prefs.json").writeText("{ not json")
            assertEquals(LibraryPrefs(), store.prefs(proj.id))
        } finally { dir.deleteRecursively() }
    }

    @Test fun theSheetFitsTheScreen() {
        // the Fold 8's inner screen (2184 x 1968 px at about 2.6 px a dp): a side panel with the tree as a rail
        val inner = LibraryModel.sheet(840f, 757f)
        assertTrue(inner.side); assertTrue(inner.rail); assertEquals(757f, inner.heightDp); assertEquals(420f, inner.widthDp); assertTrue(inner.widthDp <= 840f - 280f)
        // its cover screen upright (1080 x 2520 px): a bottom sheet of 55% at most
        val cover = LibraryModel.sheet(415f, 969f)
        assertFalse(cover.side); assertFalse(cover.rail); assertTrue(cover.heightDp <= 969f * 0.55f + 0.5f)
        // the cover screen on its side (2520 x 1080 px): wide, a side panel within the height
        val coverWide = LibraryModel.sheet(969f, 415f)
        assertTrue(coverWide.side); assertTrue(coverWide.rail); assertEquals(415f, coverWide.heightDp)
        // a tiny window: never taller than the screen
        assertTrue(LibraryModel.sheet(300f, 200f).heightDp <= 200f)
        assertEquals(4, LibraryModel.columns(420f)); assertEquals(2, LibraryModel.columns(150f))
        assertEquals(160, LibraryModel.thumbPx(150f)); assertEquals(96, LibraryModel.thumbPx(10f)); assertEquals(256, LibraryModel.thumbPx(900f))
        assertEquals(false to 1, LibraryModel.thumbOf(lib["p:1"]!!)); assertEquals(true to 80, LibraryModel.thumbOf(lib["m:8"]!!))
        assertNull(LibraryModel.thumbOf(own[0]))
    }
}
