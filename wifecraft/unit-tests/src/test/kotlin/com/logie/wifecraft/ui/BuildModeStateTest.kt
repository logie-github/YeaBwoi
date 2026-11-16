package com.logie.wifecraft.ui

import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.catalog.AssetKind
import com.logie.wifecraft.catalog.Catalog
import com.logie.wifecraft.editor.LibraryPrefs
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The build mode's buttons: the tool cluster, the Eyedropper, the catalogue's tabs, chips and flyout, the spellbook pages. */
class BuildModeStateTest {
    private val prop = Placement("barrel", PlaceKind.PROP, 101, null, "Barrel (copy) (copy)", 0f, 0f, 0f, 0f, 1f)
    private val wolf = Placement("wolf", PlaceKind.CREATURE, 900, null, "Wolf", 1f, 0f, 0f, 0f, 1f)
    private val her = Placement("her", PlaceKind.NPC, 0, "alleria", "Alleria", 2f, 0f, 0f, 0f, 1f)

    @Test fun modesStayAndActionsActOnce() {
        var c = ToolCluster()
        assertEquals(BuildTool.POINTER, c.mode); assertEquals(GizmoTool.MOVE, c.gizmo)
        c = c.tap(BuildTool.ROTATE, prop).let { (n, a) -> assertEquals(ToolAction.NONE, a); n }
        assertEquals(BuildTool.ROTATE, c.mode); assertEquals(GizmoTool.ROTATE, c.gizmo); assertTrue(c.lit(BuildTool.ROTATE, prop))
        val (afterDelete, del) = c.tap(BuildTool.DELETE, prop)
        assertEquals(ToolAction.DELETE, del); assertEquals(BuildTool.ROTATE, afterDelete.mode, "an action leaves the mode")
        assertEquals(ToolAction.DUPLICATE, c.tap(BuildTool.DUPLICATE, prop).second)
        assertEquals(ToolAction.TOGGLE_LOCK, c.tap(BuildTool.LOCK, prop).second)
        assertEquals(ToolAction.TOGGLE_HIDE, c.tap(BuildTool.HIDE, prop).second)
        assertEquals(ToolAction.FOCUS, c.tap(BuildTool.FOCUS, prop).second)
        assertEquals(ToolAction.OPEN_EMOTES, c.tap(BuildTool.EMOTE, wolf).second)
        assertEquals(BuildTool.SCALE, BuildTool.of(GizmoTool.SCALE))
    }

    @Test fun whatNeedsASelectionIsOffWithoutOne() {
        val c = ToolCluster()
        for (t in listOf(BuildTool.DELETE, BuildTool.DUPLICATE, BuildTool.LOCK, BuildTool.HIDE, BuildTool.FOCUS, BuildTool.EMOTE)) {
            assertFalse(c.enabled(t, null), t.name)
            val (n, a) = c.tap(t, null)
            assertEquals(ToolAction.NONE, a); assertEquals(t, n.hint, "the tooltip says why")
        }
        assertTrue(c.copy(hint = BuildTool.DELETE).tip(null).contains("Select something first"))
        assertTrue(c.enabled(BuildTool.POINTER, null) && c.enabled(BuildTool.EYEDROPPER, null) && c.enabled(BuildTool.ROTATE, null))
        // a prop has no emotes; a locked thing can't be turned or resized but can be unlocked
        assertFalse(c.enabled(BuildTool.EMOTE, prop)); assertTrue(c.enabled(BuildTool.EMOTE, her))
        val locked = prop.copy(locked = true, hidden = true)
        assertFalse(c.enabled(BuildTool.ROTATE, locked)); assertFalse(c.enabled(BuildTool.SCALE, locked))
        assertTrue(c.enabled(BuildTool.LOCK, locked)); assertTrue(c.lit(BuildTool.LOCK, locked)); assertTrue(c.lit(BuildTool.HIDE, locked))
        assertTrue(c.copy(hint = BuildTool.ROTATE).tip(locked).contains("Unlock it first"))
        assertTrue(c.copy(hint = BuildTool.EMOTE).tip(prop).contains("people and creatures"))
    }

    @Test fun longPressNamesTheToolAndTheHintClears() {
        val c = ToolCluster().longPress(BuildTool.EYEDROPPER)
        assertEquals("Eyedropper: Tap a placed thing to pick it up as the next to place", c.tip(null))
        assertEquals(BuildTool.POINTER, c.mode, "a long press doesn't choose it")
        assertNull(c.clearHint().hint)
        assertTrue(ToolCluster().tip(null).startsWith("Select:"))
    }

    @Test fun theEyedropperArmsWhatItTouchedAndGoesBackToSelect() {
        val lib = Catalog(listOf(
            Asset("p:101", AssetKind.PROP, 101, "Barrel", "Containers"),
            Asset("b:202", AssetKind.BUILDING, 202, "Chapel", "Buildings"),
            Asset("c:7", AssetKind.CREATURE, 55, "Grey wolf", "Beast", display = 900),
        ))
        val own = listOf(Catalog.ownNpc("alleria", "Alleria"))
        val find: (String) -> Asset? = { k -> own.firstOrNull { it.key == k } ?: lib[k] }
        val byDisplay: (Int) -> Asset? = { d -> lib.search("", null, null, null, 0, 100).items.firstOrNull { it.display == d } }
        var c = ToolCluster().tap(BuildTool.EYEDROPPER, null).first
        assertTrue(c.eyedropping); assertTrue(c.lit(BuildTool.EYEDROPPER, null)); assertEquals(GizmoTool.MOVE, c.gizmo)
        assertEquals("p:101", Eyedropper.assetFor(prop, find, byDisplay).key)
        assertEquals("b:202", Eyedropper.assetFor(prop.copy(asset = 202), find, byDisplay).key)
        assertEquals("c:7", Eyedropper.assetFor(wolf, find, byDisplay).key)
        assertEquals("n:alleria", Eyedropper.assetFor(her, find, byDisplay).key)
        // gone from the library: made from the placement itself, still placeable as the same thing
        val lost = Eyedropper.assetFor(prop.copy(asset = 999), find, byDisplay)
        assertEquals(AssetKind.PROP, lost.kind); assertEquals(999, lost.fileId); assertEquals("Barrel", lost.name)
        val lostWolf = Eyedropper.assetFor(wolf.copy(asset = 901), find, byDisplay)
        assertEquals(901, lostWolf.display)
        assertEquals(PlaceKind.CREATURE, placementOf(lostWolf, com.logie.wifecraft.story.Vec3(0f, 0f, 0f), 0f, emptyList()).kind)
        assertEquals(901, placementOf(lostWolf, com.logie.wifecraft.story.Vec3(0f, 0f, 0f), 0f, emptyList()).asset)
        c = c.picked()
        assertEquals(BuildTool.POINTER, c.mode); assertFalse(c.eyedropping)
        assertEquals("Barrel", Eyedropper.baseName("Barrel (copy) (copy)"))
    }

    private val lib = Catalog(listOf(
        Asset("p:1", AssetKind.PROP, 1, "Chair", "Furniture", "Human"),
        Asset("p:2", AssetKind.PROP, 2, "Lamp", "Lighting", "Human"),
        Asset("p:3", AssetKind.PROP, 3, "Lamp 2", "Lighting", "Forsaken"),
        Asset("p:4", AssetKind.PROP, 4, "Boat", "Ships and docks"),
        Asset("b:5", AssetKind.BUILDING, 5, "Chapel", "Buildings", "Human"),
    ))
    private val tree = LibraryModel.tree(lib, 1, LibraryPrefs(listOf("p:1"), emptyList()), 3)

    @Test fun theTabsAreInTheSimsOrderWithCountsAndIcons() {
        val tabs = CatalogueBar.tabs(tree)
        assertEquals(listOf(LibraryTab.STARTER, LibraryTab.PROPS, LibraryTab.BUILDINGS, LibraryTab.CREATURES, LibraryTab.MY_NPCS, LibraryTab.FAVOURITES, LibraryTab.RECENT), tabs.map { it.tab })
        assertEquals(listOf(3, 4, 1, 0, 1, 1, 0), tabs.map { it.count })
        assertEquals("tab.props", tabs[1].icon)
    }

    @Test fun theChipRowAndTheFlyout() {
        val q = LibraryQuery(tab = LibraryTab.PROPS)
        val chips = CatalogueBar.chips(tree, q, most = 2)
        assertEquals(listOf("All", "Lighting", "Furniture", "More…"), chips.map { it.label })
        assertTrue(chips[0].on); assertEquals("cat.lighting", chips[1].icon); assertTrue(chips.last().more)
        assertEquals(3, chips.last().count, "Ships and docks and the two styles are in the flyout")
        // a chosen category beyond the row is brought into it
        val boat = tree.first { it.tab == LibraryTab.PROPS }.children.first { it.category == "Ships and docks" }
        val q2 = LibraryModel.apply(q, boat)
        val chips2 = CatalogueBar.chips(tree, q2, most = 2)
        assertEquals("Ships and docks", chips2[1].label); assertTrue(chips2[1].on); assertFalse(chips2[0].on)
        // a chip's node narrows the query as the rail did
        assertEquals("Lighting", LibraryModel.apply(q, chips[1].node!!).category)
        assertEquals(null, LibraryModel.apply(q2, chips2[0].node!!).category)
        // tabs without a tree have no chips
        assertTrue(CatalogueBar.chips(tree, LibraryQuery(tab = LibraryTab.STARTER)).isEmpty())
        val fly = CatalogueBar.flyout(tree, q)
        assertEquals(listOf("Categories", "Styles"), fly.map { it.first })
        assertEquals(listOf("Human", "Forsaken"), fly[1].second.map { it.style })
        // the bar's own state
        var bar = CatalogueBar()
        bar = bar.openFlyout(); assertTrue(bar.flyout && bar.open)
        bar = bar.toggle(); assertFalse(bar.open); assertFalse(bar.flyout, "folding the bar closes the flyout")
        bar = bar.search(true); assertTrue(bar.open && bar.searching)
        assertFalse(bar.openFlyout().closeFlyout().flyout)
    }

    @Test fun thePagesFoldToTabs() {
        var b = BookPages()
        assertEquals(listOf(BookTab.THINGS, BookTab.SELECTED), b.tabs(prop))
        assertEquals(listOf(BookTab.THINGS, BookTab.SELECTED, BookTab.ANIMATION), b.tabs(her))
        b = b.selected(prop); assertEquals(BookTab.SELECTED, b.tab)
        b = b.tap(BookTab.SELECTED); assertFalse(b.open, "the open page's tab folds it")
        b = b.tap(BookTab.ANIMATION); assertTrue(b.open); assertEquals(BookTab.ANIMATION, b.tab)
        assertEquals(BookTab.SELECTED, b.shown(prop), "a prop has no Animation page")
        assertEquals(BookTab.THINGS, b.shown(null))
        assertEquals(BookTab.ANIMATION, b.shown(wolf))
        assertTrue(b.visible(flyout = false, placing = false))
        assertFalse(b.visible(flyout = true, placing = false)); assertFalse(b.visible(flyout = false, placing = true))
        assertFalse(b.fold().visible(false, false))
    }

    @Test fun theEmoteSearchMatchesTheLabelAndTheOldName() {
        val e = listOf(
            com.logie.wifecraft.catalog.Emote(1, "Talkex", 60, 0, emptyList(), label = "Talk (Excited)"),
            com.logie.wifecraft.catalog.Emote(2, "Forthealliance", 61, 0, emptyList(), label = "For the Alliance!"),
        )
        assertEquals(listOf(1), EmoteGroups.of(e, "excited").flatMap { g -> g.second.map { it.id } })
        assertEquals(listOf(1), EmoteGroups.of(e, "talkex").flatMap { g -> g.second.map { it.id } })
        assertEquals(listOf(2), EmoteGroups.of(e, "the alliance").flatMap { g -> g.second.map { it.id } })
        assertEquals("emote.dance", WarIcons.emoteGroup("Dance")); assertEquals("emote.sit", WarIcons.emoteGroup("Sit, sleep and kneel"))
    }
}
