package com.logie.wifecraft.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The build mode's look as data: the palette, the touch sizes, the warm filter, the icons' keys, the layout on real screens. */
class WarKitTest {
    // The Fold's inner screen (2184 x 1968 px) both ways and its cover on its side (2520 x 1080 px), at its density, and a small phone.
    private val density = 2.625f
    private val screens = listOf(2184 to 1968, 1968 to 2184, 2520 to 1080, 1080 to 2520, 1280 to 720)

    @Test fun touchTargetsAreBigEnough() {
        for (s in listOf(WarSize.BUTTON, WarSize.SMALL_BUTTON, WarSize.TAB, WarSize.CHIP_H, WarSize.CARD_COMPACT)) assertTrue(s >= WarSize.MIN_TOUCH, "$s")
        assertEquals(56f, WarSize.BUTTON)
        assertTrue(WarSize.PRESS_SCALE in 0.85f..0.97f)
    }

    @Test fun thePaletteIsSoftenedAndOpaque() {
        for (c in listOf(WarPalette.CREAM, WarPalette.PARCHMENT, WarPalette.BROWN, WarPalette.GOLD, WarPalette.INK_ON_DARK)) {
            assertEquals(0xFFL, c shr 24, "opaque")
            assertTrue((c and 0xFFFFFF) != 0xFFFFFFL && (c and 0xFFFFFF) != 0L, "no pure white or black")
        }
        assertEquals(0xFFFFD100L, WarPalette.GOLD, "the game's gold for names")
        // deep brown words on parchment read: a big difference in brightness
        fun lum(c: Long) = 0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)
        assertTrue(lum(WarPalette.PARCHMENT) - lum(WarPalette.BROWN) > 120)
    }

    @Test fun theTintWarmsGreysAndKeepsAlpha() {
        val grey = WarTint.apply(0x80808080L)
        assertEquals(0x80L, grey shr 24)
        val r = (grey shr 16) and 0xFF; val b = grey and 0xFF
        assertTrue(r > b + 20, "a grey turns warm: ${grey.toString(16)}")
        // black is lifted to a warm deep brown, never past it
        val black = WarTint.apply(0xFF000000L)
        assertTrue(((black shr 16) and 0xFF) in 5L..20L && (black and 0xFF) < ((black shr 16) and 0xFF))
        // gold stays gold
        val gold = WarTint.apply(WarPalette.GOLD)
        assertTrue(((gold shr 16) and 0xFF) >= 0xF0 && ((gold shr 8) and 0xFF) >= 0xC8)
    }

    @Test fun everyTabCategoryAndEmoteGroupHasAnIconKey() {
        val json = File(System.getProperty("wifecraft.assets"), "ui_art.json").readText()
        val icons = org.json.JSONObject(json).getJSONObject("icons")
        for (t in LibraryTab.values()) assertTrue(icons.has(WarIcons.tab(t)), t.name)
        for (k in WarIcons.CATEGORIES) assertTrue(icons.has(k), k)
        for (g in com.logie.wifecraft.catalog.EmoteCatalog.CATEGORIES) assertTrue(icons.has(WarIcons.emoteGroup(g)), g)
        for (b in BookTab.values()) assertTrue(icons.has(b.icon), b.name)
        for (k in listOf("tool.eyedropper", "tool.duplicate", "tool.lock", "tool.hide", "tool.emote", "tool.focus", "tool.stage", "tool.camera")) assertTrue(icons.has(k), k)
        // catalog.tsv's every category has one
        val cats = File(System.getProperty("wifecraft.assets"), "catalog.tsv").useLines { l -> l.first { it.startsWith("#cats") }.split('\t').drop(1) }
        for (c in cats) assertTrue(WarIcons.category(c) != null, c)
        assertEquals(null, WarIcons.category("Forsaken"))
        // and every glyph the kit names is a piece
        val pieces = org.json.JSONObject(json).getJSONObject("pieces")
        for (g in listOf(WarIcons.BACK, WarIcons.PLAY, WarIcons.UNDO, WarIcons.REDO, WarIcons.DELETE, WarIcons.CHECK, WarIcons.CLOSE,
                WarIcons.CAMERA, WarIcons.LOCK, WarIcons.STAR, WarIcons.STAR_OFF, WarIcons.SEARCH, WarIcons.PLUS, WarIcons.MINUS)) assertTrue(pieces.has(g), g)
        for (n in WarSlices.INSETS.keys) assertTrue(pieces.has(n), n)
    }

    @Test fun theLayoutFitsEveryScreen() {
        for ((w, h) in screens) {
            val l = BuildLayout.of(w / density, h / density)
            val name = "${w}x$h"
            assertTrue(l.topBar + l.barOpen <= l.hDp + 0.01f, "$name: the bars fit")
            assertTrue(l.sceneH >= BuildLayout.MIN_SCENE || l.compact, "$name: the scene keeps its room ${l.sceneH}")
            assertTrue(l.pageH <= l.hDp - l.topBar, "$name: a page fits under the top bar")
            assertTrue(l.pageW <= l.wDp - 64f, "$name: a page leaves the side tabs")
            assertTrue(l.flyoutH <= l.hDp - l.topBar, "$name: the flyout fits")
            assertTrue(l.flyoutW <= l.wDp, name)
            assertTrue(l.clusterCols * l.clusterRows >= BuildLayout.TOOLS || l.clusterScrolls, "$name: every tool is reachable")
            val clusterH = l.clusterRows * (WarSize.BUTTON + WarSize.GAP)
            assertTrue(clusterH <= l.sceneH + WarSize.GAP || l.clusterScrolls, "$name: the cluster fits beside the bar")
            if (l.clusterHorizontal) assertTrue(l.clusterCols * (WarSize.BUTTON + WarSize.GAP) <= l.wDp - BuildLayout.SIDE_TAB, "$name: the row fits across")
            if (!l.sideTabsRow) assertTrue(3 * BuildLayout.SIDE_TAB_PITCH <= l.sceneH, "$name: the side tabs fit down the edge")
            assertTrue(l.card >= WarSize.MIN_TOUCH, name)
        }
        // the inner screen is roomy: big cards and a chip row; the cover on its side is compact
        val inner = BuildLayout.of(2184 / density, 1968 / density)
        assertTrue(!inner.compact && inner.chipRow && inner.card == WarSize.CARD)
        val cover = BuildLayout.of(2520 / density, 1080 / density)
        assertTrue(cover.compact && cover.clusterHorizontal && cover.sideTabsRow, "the cover on its side: the tools in a row, the tabs at the top")
        assertTrue(!inner.clusterHorizontal && !inner.sideTabsRow)
    }

    @Test fun nineSliceCornersNeverOverlap() {
        val c = WarSlices.cornerDp("spellbook-background-evergreen-left", 806, 805, 0.5f, 60f, 60f)
        assertTrue(c[0] + c[2] <= 60f * 0.66f + 0.01f && c[1] + c[3] <= 60f * 0.66f + 0.01f)
        val big = WarSlices.cornerDp("spellbook-background-evergreen-left", 806, 805, 0.5f, 400f, 700f)
        assertEquals(806 * 0.09f * 0.5f, big[0], 0.01f)
        assertEquals(0f, WarSlices.cornerDp("nothing", 10, 10, 1f, 10f, 10f)[0])
    }

    /** -Dwifecraft.layoutOut=FILE: the layout of each mock screen as JSON (the mock images are drawn from it). */
    @Test fun dumpForMocks() {
        val out = System.getProperty("wifecraft.layoutOut") ?: return
        val arr = org.json.JSONArray()
        for ((w, h) in listOf(2184 to 1968, 1968 to 2184, 2520 to 1080)) {
            val l = BuildLayout.of(w / density, h / density)
            arr.put(org.json.JSONObject().put("px", org.json.JSONArray(listOf(w, h))).put("density", density.toDouble())
                .put("compact", l.compact).put("topBar", l.topBar).put("barOpen", l.barOpen).put("barFolded", l.barFolded)
                .put("tab", l.tab).put("card", l.card).put("chipRow", l.chipRow).put("clusterCols", l.clusterCols)
                .put("clusterRows", l.clusterRows).put("clusterScrolls", l.clusterScrolls).put("pageW", l.pageW).put("pageH", l.pageH)
                .put("flyoutW", l.flyoutW).put("flyoutH", l.flyoutH).put("sceneH", l.sceneH).put("sideTabsRow", l.sideTabsRow))
        }
        File(out).writeText(arr.toString(1))
    }
}
