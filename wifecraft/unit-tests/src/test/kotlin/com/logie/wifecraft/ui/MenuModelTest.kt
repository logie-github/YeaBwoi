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

/** The WoW-like menus (W40): the action bar, More's dropdown, the context menu, the windows, the Game Menu, the title, Options. */
class MenuModelTest {
    private val prop = Placement("barrel", PlaceKind.PROP, 101, null, "Barrel", 0f, 0f, 0f, 0f, 1f)
    private val wolf = Placement("wolf", PlaceKind.CREATURE, 900, null, "Wolf", 1f, 0f, 0f, 0f, 1f)
    private val her = Placement("her", PlaceKind.NPC, 0, "alleria", "Alleria", 2f, 0f, 0f, 0f, 1f)

    /** The Fold 8's inner screen either way and its cover screen either way, in dp (2.625 px a dp). */
    private val screens = listOf(2184 to 1968, 1968 to 2184, 2520 to 1080, 1080 to 2520).map { (w, h) -> w / 2.625f to h / 2.625f }

    @Test fun theBarHasSixSlotsAndTheScreenTwelveThingsAtMost() {
        assertEquals(6, ActionSlot.values().size)
        assertEquals(ActionSlot.MAX, ActionSlot.values().size)
        assertEquals(listOf("Select", "Rotate", "Scale", "Delete", "Add", "More"), ActionSlot.values().map { it.label })
        val c = EditorChrome()
        // the default state: Menu, Undo, Redo, Play, six slots, a tip's Got it
        assertEquals(11, EditorChrome.tappables(c, null, tip = true, placing = false))
        assertTrue(EditorChrome.tappables(c, null, tip = true, placing = false) <= 12)
        // a person selected: her tooltip's Emote and Details (the tip makes way)
        assertEquals(12, EditorChrome.tappables(c, her, tip = false, placing = false))
        assertEquals(11, EditorChrome.tappables(c, prop, tip = false, placing = false), "a prop has no Emote")
        // under a covering window the bar and the top buttons are put away
        assertEquals(0, EditorChrome.tappables(c.open(EditorWindow.LIBRARY), her, tip = true, placing = false))
    }

    @Test fun theSlotsFollowTheClusterAndTheSelection() {
        val cl = ToolCluster()
        assertTrue(ActionBarModel.lit(ActionSlot.SELECT, cl, EditorChrome(), false))
        assertFalse(ActionBarModel.enabled(ActionSlot.DELETE, cl, null), "Delete needs a selection")
        assertTrue(ActionBarModel.enabled(ActionSlot.DELETE, cl, prop))
        assertTrue(ActionBarModel.enabled(ActionSlot.ADD, cl, null) && ActionBarModel.enabled(ActionSlot.MORE, cl, null))
        assertFalse(ActionBarModel.enabled(ActionSlot.ROTATE, cl, prop.copy(locked = true)))
        val rot = cl.tap(BuildTool.ROTATE, prop).first
        assertTrue(ActionBarModel.lit(ActionSlot.ROTATE, rot, EditorChrome(), false))
        assertFalse(ActionBarModel.lit(ActionSlot.SELECT, rot, EditorChrome(), false))
        assertFalse(ActionBarModel.lit(ActionSlot.DELETE, rot, EditorChrome(), false), "an action is never lit")
        assertTrue(ActionBarModel.lit(ActionSlot.ADD, cl, EditorChrome(), placing = true))
        assertTrue(ActionBarModel.lit(ActionSlot.ADD, cl, EditorChrome(EditorWindow.LIBRARY), false))
        assertTrue(ActionBarModel.lit(ActionSlot.MORE, cl, EditorChrome(EditorWindow.MORE), false))
        assertTrue(ActionBarModel.lit(ActionSlot.MORE, cl.tap(BuildTool.EYEDROPPER, null).first, EditorChrome(), false), "the Eyedropper lives in More")
        for (s in ActionSlot.values()) s.tool?.let { assertEquals(s.label, it.label) }
    }

    @Test fun backClosesWhatIsOpenThenOpensTheGameMenu() {
        var c = EditorChrome()
        c = c.back(); assertEquals(EditorWindow.GAME_MENU, c.window)
        c = c.back(); assertEquals(EditorWindow.NONE, c.window, "Back on the Game Menu is Resume")
        for (w in EditorWindow.values().filter { it != EditorWindow.NONE }) assertEquals(EditorWindow.NONE, EditorChrome(w).back().window)
        assertEquals(EditorWindow.MORE, c.toggle(EditorWindow.MORE).window)
        assertEquals(EditorWindow.NONE, c.toggle(EditorWindow.MORE).toggle(EditorWindow.MORE).window)
        assertEquals(EditorWindow.LIBRARY, c.toggle(EditorWindow.MORE).toggle(EditorWindow.LIBRARY).window, "one at a time")
        assertTrue(EditorChrome().inspectorShown(true, false))
        assertFalse(EditorChrome().inspectorShown(true, true), "not while placing")
        assertFalse(EditorChrome(EditorWindow.MORE).inspectorShown(true, false))
        assertTrue(EditorChrome().toggleDetails().details)
        assertTrue(EditorChrome(EditorWindow.MORE).barShown && EditorChrome(EditorWindow.CONTEXT).barShown)
        assertFalse(EditorChrome(EditorWindow.GAME_MENU).barShown)
    }

    @Test fun moreHoldsEverythingTheBarLeftOut() {
        val rows = EditorMenus.more(null, ToolCluster(), snap = false, showHidden = false, hasStages = true)
        assertEquals(
            listOf("Duplicate", "Lock", "Hide", "Emote…", "Look at it", "Eyedropper", "Snap to grid", "Show hidden things", "Home view",
                "Objects in this scene…", "Stage…", "Tips again"),
            rows.map { it.label },
        )
        // nothing selected: the selected thing's rows are dimmed; the rest work
        assertTrue(rows.take(5).none { it.enabled })
        assertTrue(rows.drop(5).all { it.enabled })
        // three groups
        assertEquals(listOf(MenuAction.EYEDROPPER, MenuAction.HOME_VIEW), rows.filter { it.ruleBefore }.map { it.action })
        // the switches are check boxes, the rest are not
        assertEquals(listOf(MenuAction.EYEDROPPER, MenuAction.SNAP, MenuAction.SHOW_HIDDEN), rows.filter { it.checked != null }.map { it.action })
        val on = EditorMenus.more(prop.copy(locked = true, hidden = true), ToolCluster().tap(BuildTool.EYEDROPPER, null).first, true, true, false)
        assertEquals(listOf("Unlock", "Show"), on.filter { it.action == MenuAction.LOCK || it.action == MenuAction.HIDE }.map { it.label })
        assertFalse(on.first { it.action == MenuAction.EMOTE }.enabled, "a prop has no emotes")
        assertTrue(on.filter { it.checked != null }.all { it.checked == true })
        assertTrue(on.none { it.action == MenuAction.STAGE }, "no stages to pick: no Stage row")
        assertTrue(EditorMenus.more(her, ToolCluster(), false, false, true).first { it.action == MenuAction.EMOTE }.enabled)
    }

    @Test fun theContextMenuIsAPlainList() {
        assertEquals(listOf("Duplicate", "Lock", "Hide", "Look at it", "Details…", "Delete"), EditorMenus.context(prop).map { it.label })
        assertEquals(listOf("Duplicate", "Lock", "Hide", "Emote…", "Look at it", "Details…", "Delete"), EditorMenus.context(wolf).map { it.label })
        val del = EditorMenus.context(her).last()
        assertTrue(del.danger && del.ruleBefore && del.action == MenuAction.DELETE)
        assertTrue(EditorMenus.context(her).all { it.enabled })
        // its height: title, seven rows of 48 dp, one rule
        assertEquals(EditorMenus.TITLE + 7 * 48f + 13f + 8f, EditorMenus.height(EditorMenus.context(her), true))
    }

    private val lib = Catalog(listOf(
        Asset("p:1", AssetKind.PROP, 1, "Chair", "Furniture", "Human"),
        Asset("p:2", AssetKind.PROP, 2, "Lamp", "Lighting", "Human"),
        Asset("p:3", AssetKind.PROP, 3, "Lamp 2", "Lighting", "Forsaken"),
        Asset("b:5", AssetKind.BUILDING, 5, "Chapel", "Buildings", "Human"),
        Asset("c:7", AssetKind.CREATURE, 55, "Grey wolf", "Beast", display = 900),
    ))
    private val tree = LibraryModel.tree(lib, 2, LibraryPrefs(listOf("p:1"), listOf("p:2")), 3)

    @Test fun theLibraryHasFourTabs() {
        assertEquals(listOf("Props", "Buildings", "People", "Favourites"), LibraryWindowTab.values().map { it.label })
        // every old tab lands on one of the four (no Starter pack tab: it is Props' suggestions)
        assertEquals(LibraryWindowTab.PROPS, LibraryWindowTab.of(LibraryTab.STARTER))
        assertEquals(LibraryWindowTab.PEOPLE, LibraryWindowTab.of(LibraryTab.MY_NPCS))
        assertEquals(LibraryWindowTab.PEOPLE, LibraryWindowTab.of(LibraryTab.CREATURES))
        assertEquals(LibraryWindowTab.FAVOURITES, LibraryWindowTab.of(LibraryTab.RECENT))
        val q = LibraryQuery(text = "lamp")
        assertEquals(LibraryTab.PROPS, LibraryWindow.select(q, LibraryWindowTab.PROPS, 0).tab)
        assertEquals("lamp", LibraryWindow.select(q, LibraryWindowTab.PROPS, 0).text, "the words stay")
        assertEquals(LibraryTab.MY_NPCS, LibraryWindow.select(q, LibraryWindowTab.PEOPLE, 2).tab, "your NPCs first when you have some")
        assertEquals(LibraryTab.CREATURES, LibraryWindow.select(q, LibraryWindowTab.PEOPLE, 0).tab)
        val creatures = LibraryQuery(tab = LibraryTab.CREATURES)
        assertEquals(LibraryTab.CREATURES, LibraryWindow.select(creatures, LibraryWindowTab.PEOPLE, 2).tab, "People again keeps what it showed")
        assertEquals(LibraryTab.FAVOURITES, LibraryWindow.select(q, LibraryWindowTab.FAVOURITES, 0).tab)
    }

    @Test fun oneDropdownWithCountsInsteadOfChips() {
        val props = LibraryWindow.dropdown(tree, LibraryQuery(tab = LibraryTab.PROPS))
        assertEquals("All props", props.first().label)
        assertEquals(3, props.first().count)
        assertTrue(props.any { it.label == "Lighting" && it.count == 2 })
        assertTrue(props.any { it.header && it.label == "Styles" })
        assertTrue(props.any { it.label.contains("Forsaken") })
        // People: your NPCs, then every creature and its types
        val people = LibraryWindow.dropdown(tree, LibraryQuery(tab = LibraryTab.MY_NPCS))
        assertEquals(listOf("Your NPCs", "All creatures"), people.take(2).map { it.label })
        assertEquals(2, people.first().count)
        assertTrue(people.any { it.label == "Beast" && it.tab == LibraryTab.CREATURES })
        assertTrue(LibraryWindow.dropdown(tree, LibraryQuery(tab = LibraryTab.FAVOURITES)).isEmpty())
        // picking narrows; picking the same again keeps it; the whole kind clears it
        val lighting = props.first { it.label == "Lighting" }
        var q = LibraryWindow.apply(LibraryQuery(tab = LibraryTab.PROPS), lighting)
        assertEquals("Lighting", q.category)
        assertEquals("Lighting", LibraryWindow.apply(q, lighting).category)
        assertEquals(lighting, LibraryWindow.chosen(props, q))
        assertEquals("Lighting  (2)", LibraryWindow.buttonLabel(LibraryWindow.chosen(props, q)))
        q = LibraryWindow.apply(q, props.first())
        assertNull(q.category)
        assertEquals(props.first(), LibraryWindow.chosen(props, q))
        val forsaken = props.first { it.node?.style == "Forsaken" }
        assertEquals("Forsaken", LibraryWindow.apply(q, forsaken).styleFilter)
        assertEquals(q, LibraryWindow.apply(q, props.first { it.header }), "a heading picks nothing")
        // from People's dropdown to the creatures
        val beasts = LibraryWindow.apply(LibraryQuery(tab = LibraryTab.MY_NPCS), people.first { it.label == "Beast" })
        assertEquals(LibraryTab.CREATURES, beasts.tab); assertEquals("Beast", beasts.category)
        assertEquals(LibraryTab.CREATURES, LibraryWindow.apply(LibraryQuery(tab = LibraryTab.MY_NPCS), people[1]).tab)
    }

    @Test fun suggestionsTopPropsAndRecentTopsFavourites() {
        assertTrue(LibraryWindow.suggests(LibraryQuery(tab = LibraryTab.PROPS)))
        assertFalse(LibraryWindow.suggests(LibraryQuery(tab = LibraryTab.PROPS, category = "Lighting")))
        assertFalse(LibraryWindow.suggests(LibraryQuery(tab = LibraryTab.PROPS, text = "lamp")))
        assertFalse(LibraryWindow.suggests(LibraryQuery(tab = LibraryTab.BUILDINGS)))
        assertTrue(LibraryWindow.showsRecent(LibraryQuery(tab = LibraryTab.FAVOURITES)))
        assertFalse(LibraryWindow.showsRecent(LibraryQuery(tab = LibraryTab.PROPS)))
        for (t in LibraryWindowTab.values()) assertTrue(LibraryWindow.hint(t).startsWith("Search"))
    }

    @Test fun theGameMenuAndTheTitleAreShortStacks() {
        assertEquals(listOf("Resume", "Scenes", "Cast", "Animations", "Options", "Play", "Save and Exit"), Menus.game(true).map { it.item.label })
        assertTrue(Menus.game(true).size <= GameMenuItem.MAX)
        assertFalse(Menus.game(false).first { it.item == GameMenuItem.PLAY }.enabled)
        val ready = Menus.title(filesReady = true, hasLast = true)
        assertEquals(listOf("Continue", "New Project", "Projects", "Options", "Import Animations", "Updates"), ready.map { it.item.label })
        assertTrue(ready.size <= TitleItem.MAX && ready.all { it.enabled })
        assertFalse(Menus.title(true, hasLast = false).first().enabled, "nothing to continue")
        val first = Menus.title(filesReady = false, hasLast = true)
        assertEquals(TitleItem.GATHER, first.first().item)
        assertEquals(6, first.size)
        assertFalse(first.first { it.item == TitleItem.NEW_PROJECT }.enabled)
        // one column where it fits, two on the cover screen on its side
        assertEquals(1, Menus.columns(750f, 7, Menus.GAME_MENU_CHROME))
        assertEquals(2, Menus.columns(411f, 7, Menus.GAME_MENU_CHROME))
    }

    @Test fun optionsAreShortAndEveryOptionHasOnePlace() {
        assertEquals(listOf("Game", "Sound", "Graphics", "Interface", "Advanced"), OptionsPage.CATEGORIES.map { it.label })
        assertEquals(OptionsPage.ADVANCED, OptionsPage.CATEGORIES.last())
        assertEquals(OptionsPage.GAME, OptionsPage.IMPORTS.parent)
        val all = OptionsPage.values().flatMap { OptionsModel.rows(it) }
        assertEquals(OptionKey.values().toSet(), all.toSet(), "every option is somewhere")
        assertEquals(all.size, all.toSet().size, "and only once")
        assertEquals(listOf(OptionKey.MUSIC, OptionKey.AMBIENCE, OptionKey.EFFECTS, OptionKey.VOICES), OptionsModel.rows(OptionsPage.SOUND))
        assertEquals(listOf(OptionKey.RALIA, OptionKey.ALTERNATE_FEMALE, OptionKey.BODY, OptionKey.UNDEAD, OptionKey.SIMS_SKELETON), OptionsModel.rows(OptionsPage.IMPORTS))
        for (p in OptionsPage.values()) assertTrue(OptionsModel.rows(p).size <= 5, "$p is short")
        assertEquals(4, OptionsModel.SOFT_BODY.size)
        assertEquals(listOf(24, 48, 96), OptionsModel.DOWNLOAD.map { it.first })
        assertTrue(OptionsModel.sideList(720f)); assertFalse(OptionsModel.sideList(387f))
    }

    @Test fun everythingFitsTheFoldsScreens() {
        for ((w, h) in screens) {
            val l = ChromeLayout.of(w, h)
            val at = "${w.toInt()} x ${h.toInt()} dp"
            assertTrue(l.slot >= 48f && l.slot <= 60f, at)
            assertTrue(l.barW <= w - 2 * ChromeLayout.G + 0.01f, "$at: the bar fits across")
            assertTrue(l.nameW == 0f || l.nameW >= 96f, at)
            assertTrue(l.inspectorW <= w - 2 * ChromeLayout.G + 0.01f, at)
            assertTrue(l.libraryW <= w - 2 * ChromeLayout.G && l.libraryH + ChromeLayout.TABS <= h - 2 * ChromeLayout.G + 0.01f, "$at: the Library fits")
            assertTrue(l.optionsH <= h - 2 * ChromeLayout.G && l.listH <= h - 2 * ChromeLayout.G, at)
            // the Game Menu fits in its columns
            val rows = ChromeLayout.rows(GameMenuItem.MAX, l.gameMenuCols)
            assertTrue(52f + rows * 48f + (rows - 1) * 6f + 28f <= h, "$at: the Game Menu fits")
            assertTrue(ChromeLayout.cardColumns(l.libraryW - 60f, l.card) >= 2, at)
            // the bar and the top buttons leave most of the screen to the scene
            assertTrue(l.barTop + 76f < h * 0.5f, "$at: the scene keeps most of the height")
        }
        // the cover screen on its side: two columns for the Game Menu and the title, smaller cards
        val cover = ChromeLayout.of(2520 / 2.625f, 1080 / 2.625f)
        assertEquals(2, cover.gameMenuCols); assertEquals(2, cover.titleCols); assertEquals(76f, cover.card)
        val inner = ChromeLayout.of(2184 / 2.625f, 1968 / 2.625f)
        assertEquals(1, inner.gameMenuCols); assertEquals(1, inner.titleCols); assertEquals(92f, inner.card); assertEquals(60f, inner.slot)
        // the cover screen upright: slots shrink, the name gives way to Undo, Redo and Play, the tooltip spans the width
        val upright = ChromeLayout.of(1080 / 2.625f, 2520 / 2.625f)
        assertTrue(upright.slot < 60f)
        assertEquals(1080 / 2.625f - 24f, upright.inspectorW, 0.01f)
        assertEquals(120f, ChromeLayout.dropdownH(300f, 120f))
        assertEquals(96f, ChromeLayout.dropdownH(300f, 40f), "never smaller than two rows")
    }
}
