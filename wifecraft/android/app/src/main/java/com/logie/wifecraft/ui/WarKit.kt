package com.logie.wifecraft.ui

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Pure Kotlin, no android.* and no Compose: the editor's "build mode" look as data (JVM-tested: unit-tests/.../ui/WarKitTest).
// The game's own interface pieces (WarArt cuts them from wago.tools on the phone: assets/ui_art.json) softened and warmed:
// a cream, amber and parchment palette with deep brown words and the game's gold for names, rounder corners, 56 dp round
// buttons that sink a little when pressed. WarWidgets draws with these numbers; BuildLayout places the editor's parts from
// the screen's real width and height (the Fold's inner screen about 832 x 750 dp, its cover about 960 x 411 dp on its side).

/** The palette, as 0xAARRGGBB (Compose's Color(Long)). Softened from the game's: no pure black, no pure white. */
object WarPalette {
    const val CREAM = 0xFFF6EBCFL          // light parchment: page grounds, chips
    const val PARCHMENT = 0xFFE9D3A0L      // the page itself
    const val PARCHMENT_DEEP = 0xFFD6B77AL // a parchment row lit, a pressed chip
    const val AMBER = 0xFFE3A33BL          // warm highlight: the chosen chip, the badge
    const val GOLD = 0xFFFFD100L           // the game's gold: names, rings, the chosen tab
    const val GOLD_SOFT = 0xFFF2C960L      // gold on parchment (reads better than the bright one)
    const val BROWN = 0xFF3B2312L          // words on parchment
    const val BROWN_SOFT = 0xFF6E4A2CL     // second line on parchment
    const val WOOD = 0xFF6A4325L           // the bar's planks, a fallback for the wood pieces
    const val WOOD_DARK = 0xFF3A2414L
    const val GLASS = 0xE01E140CL          // a dark warm ground over the scene (the bar, the cluster)
    const val GLASS_LIGHT = 0x991E140CL
    const val INK_ON_DARK = 0xFFF6EBCFL    // words on the dark ground: cream, not white
    const val MUTED = 0xFFB59B78L          // greyed words (warm grey)
    const val DANGER = 0xFFC2452DL         // Delete
    const val OK = 0xFF7BAA3CL             // a quiet green (Saved)
    const val SHADOW = 0x66000000L
}

/** Sizes in dp, corner radii, and the press. */
object WarSize {
    const val BUTTON = 56f            // a round button
    const val MIN_TOUCH = 48f         // nothing smaller to touch
    const val SMALL_BUTTON = 48f
    const val TAB = 56f               // a category tab
    const val TAB_RAISE = 8f          // the chosen tab stands up by this
    const val TAB_CHOSEN_SCALE = 1.12f
    const val CARD = 104f             // a picture card's picture (the name goes under it)
    const val CARD_COMPACT = 80f
    const val CARD_NAME = 30f         // the name under a card (two short lines at 11 sp)
    const val CHIP_H = 48f
    const val TOP_BAR = 64f
    const val GUTTER = 12f
    const val GAP = 8f
    const val R_SMALL = 10f           // a field, a row
    const val R_CARD = 16f            // a card, a chip's ends are round anyway
    const val R_PANEL = 22f           // the bar, a page, a flyout
    const val PRESS_SCALE = 0.92f
    const val PRESS_MS = 90
    const val SLIDE_MS = 220          // a page sliding in
    const val ICON_INSET = 0.08f      // an item icon's own black edge, cut off (the game draws them at 0.07 .. 0.93)
    const val GLYPH_IN_ROUND = 0.56f  // a glyph's size in its round button
    const val ICON_IN_ROUND = 0.70f   // an item icon's size in its round button
}

/**
 * The warm, soft colour filter laid over every cut piece (WarArt): a little less saturation, red up, blue down, the darks
 * lifted: the game's steel greys turn bronze and its black turns a warm deep brown. Android's ColorMatrix order (4 x 5,
 * row major: R' = m0*R + m1*G + m2*B + m3*A + m4 ...), values 0..255.
 */
object WarTint {
    private const val SAT = 0.88f
    private const val LIFT = 10f
    private val LR = 0.2126f; private val LG = 0.7152f; private val LB = 0.0722f

    val MATRIX: FloatArray = run {
        val s = SAT; val i = 1f - s
        // saturation, then warm gains per channel, then a lift of the darks
        val gain = floatArrayOf(1.06f, 1.0f, 0.86f)
        val lift = floatArrayOf(LIFT * 1.1f, LIFT * 0.8f, LIFT * 0.4f)
        val m = FloatArray(20)
        val rows = arrayOf(
            floatArrayOf(i * LR + s, i * LG, i * LB),
            floatArrayOf(i * LR, i * LG + s, i * LB),
            floatArrayOf(i * LR, i * LG, i * LB + s),
        )
        for (r in 0..2) {
            for (c in 0..2) m[r * 5 + c] = rows[r][c] * gain[r]
            m[r * 5 + 4] = lift[r]
        }
        m[18] = 1f
        m
    }

    /** [argb] through [MATRIX] (what the filter does to one pixel; the tests check it warms and keeps alpha). */
    fun apply(argb: Long): Long {
        val a = (argb shr 24) and 0xFF; val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        fun ch(row: Int): Long {
            val v = MATRIX[row * 5] * r + MATRIX[row * 5 + 1] * g + MATRIX[row * 5 + 2] * b + MATRIX[row * 5 + 3] * a + MATRIX[row * 5 + 4]
            return v.roundToInt().coerceIn(0, 255).toLong()
        }
        return (a shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
    }
}

/** Which icon (assets/ui_art.json "icons") or glyph (an atlas piece) stands for what. */
object WarIcons {
    fun tab(t: LibraryTab): String = when (t) {
        LibraryTab.STARTER -> "tab.starter"; LibraryTab.PROPS -> "tab.props"; LibraryTab.BUILDINGS -> "tab.buildings"
        LibraryTab.CREATURES -> "tab.creatures"; LibraryTab.MY_NPCS -> "tab.npcs"; LibraryTab.FAVOURITES -> "tab.favourites"
        LibraryTab.RECENT -> "tab.recent"
    }

    /** A category of the library ("Food and drink", "Beast"...): its icon key; a style or a category without one: null. */
    fun category(name: String): String? = ("cat." + name.lowercase()).takeIf { it in CATEGORIES }

    /** An emote group of EmoteGroups ("Emotes", "Dance", "Sit, sleep and kneel", "Work and life", "Combat", "Spells"...). */
    fun emoteGroup(name: String): String {
        val n = name.lowercase()
        return when {
            n.startsWith("dance") -> "emote.dance"
            n.startsWith("sit") || "sleep" in n || "kneel" in n -> "emote.sit"
            n.startsWith("work") -> "emote.work"
            n.startsWith("combat") -> "emote.combat"
            n.startsWith("spell") -> "emote.spells"
            n.startsWith("emote") -> "emote.emotes"
            else -> "emote.other"
        }
    }

    /** The categories ui_art.py gives icons (catalog.tsv's #cats, lower case). */
    val CATEGORIES: Set<String> = setOf(
        "lighting", "furniture", "food and drink", "containers", "books and papers", "tools and crafts", "carts and wagons",
        "weapons and armor", "banners and flags", "tents", "statues", "ships and docks", "water", "nature", "bones and remains",
        "architecture", "magic", "buildings", "other", "beast", "dragonkin", "demon", "elemental", "giant", "undead", "humanoid",
        "critter", "mechanical", "totem", "non-combat pet", "gas cloud", "wild pet", "aberration", "creatures", "not specified",
        "my people",
    ).mapTo(HashSet()) { "cat.$it" }

    // the gold glyphs (atlas pieces)
    const val BACK = "common-icon-backarrow"
    const val PLAY = "common-icon-forwardarrow"
    const val UNDO = "common-icon-rotateleft"
    const val REDO = "common-icon-rotateright"
    const val DELETE = "common-icon-delete"
    const val COPY = "common-icon-copy"
    const val CHECK = "common-icon-checkmark-yellow"
    const val PLUS = "common-icon-plus"
    const val MINUS = "common-icon-minus"
    const val CLOSE = "common-icon-redx"
    const val SPEAK = "common-icon-speak"
    const val SOUND = "common-icon-sound"
    const val ZOOM = "common-icon-zoomin"
    const val CAMERA = "icon-camera-target"
    const val LOCK = "ui-charactercreate-padlock"
    const val STAR = "auctionhouse-icon-favorite"
    const val STAR_OFF = "auctionhouse-icon-favorite-off"
    const val SEARCH = "common-search-magnifyingglass"
}

/**
 * (Before W40; the editor's layout is now ChromeLayout in MenuModel.kt, this is kept with its tests.)
 * Where the editor's parts go on a screen [wDp] x [hDp] (the real size; portrait or landscape): the slim top bar, the
 * catalogue bar along the bottom (its round tabs, its chips, its row of picture cards), the tool cluster in the bottom-left
 * corner above the bar, the spellbook pages sliding in from the right (folded: their side tabs on the right edge), and
 * the category flyout over the bar. Every part fits the screen: the scene keeps at least [MIN_SCENE] dp of height while
 * the bar is open (the bar goes compact, then its chips go into the tabs' row), nothing is taller than the room it has.
 */
data class BuildLayout(
    val wDp: Float,
    val hDp: Float,
    /** Short screen (the cover screen on its side): one row of tabs and chips, smaller cards, the cluster in one column. */
    val compact: Boolean,
    val topBar: Float,
    /** The catalogue bar's height open (tabs, chips, cards, padding) and folded (the tabs only). */
    val barOpen: Float,
    val barFolded: Float,
    val tab: Float,
    val card: Float,
    /** The chips in their own row (else beside the tabs). */
    val chipRow: Boolean,
    /** The tool cluster: columns and rows of round buttons (it scrolls when [clusterScrolls]); one row along the bottom of a short scene. */
    val clusterCols: Int,
    val clusterRows: Int,
    val clusterScrolls: Boolean,
    /** A spellbook page: its width, and its height (from under the top bar to over the open bar). */
    val pageW: Float,
    val pageH: Float,
    /** The category flyout's size. */
    val flyoutW: Float,
    val flyoutH: Float,
    /** The scene's height between the top bar and the open bar. */
    val sceneH: Float,
    /** The pages' side tabs in a row at the top right (a short screen) instead of down the right edge. */
    val sideTabsRow: Boolean,
) {
    val clusterHorizontal: Boolean get() = clusterRows == 1 && clusterCols > 1
    val button: Float get() = WarSize.BUTTON

    companion object {
        const val MIN_SCENE = 150f
        const val TOOLS = 10
        /** A page's side tab (the spellbook's): its width, and its pitch down the edge. */
        const val SIDE_TAB = 52f
        const val SIDE_TAB_PITCH = 68f

        fun of(wDp: Float, hDp: Float, tools: Int = TOOLS): BuildLayout {
            val g = WarSize.GUTTER
            val top = WarSize.TOP_BAR
            // the open bar: tabs (raised) + chips + card row + padding; compact when the scene would be too short
            val tabRow = WarSize.TAB + WarSize.TAB_RAISE + 18f       // the tab, its rise, its label under it
            fun bar(card: Float, chipRow: Boolean) = g + tabRow + (if (chipRow) WarSize.CHIP_H + WarSize.GAP else 0f) + card + WarSize.CARD_NAME + g
            var card = WarSize.CARD
            var chipRow = true
            var compact = false
            if (hDp - top - bar(card, true) < MIN_SCENE) { card = WarSize.CARD_COMPACT; compact = true }
            if (hDp - top - bar(card, true) < MIN_SCENE) chipRow = false
            var barOpen = bar(card, chipRow)
            // never more than the screen leaves (a very short screen: the bar takes what is left above the top bar)
            barOpen = min(barOpen, max(hDp - top, 0f))
            val barFolded = g + tabRow + g * 0.5f
            val sceneH = max(hDp - top - barOpen, 0f)
            // the cluster: as many rows as fit the scene's height beside the bar, in as few columns as hold the tools
            val pitch = WarSize.BUTTON + WarSize.GAP
            val room = sceneH - 2 * g
            val fitRows = max(1, floor((room + WarSize.GAP) / pitch).toInt())
            var cols = ceil(tools / fitRows.toFloat()).toInt()
            var scrolls = false
            val maxCols = if (compact || wDp < 520f) 1 else 2
            var rows: Int
            if (cols > maxCols) {
                // too short for a column: one row along the bottom of the scene when the width holds it, else a scrolling column
                val across = wDp - 2 * g - SIDE_TAB - 2 * g
                if (tools * pitch <= across) { cols = tools; rows = 1 } else { cols = maxCols; rows = fitRows; scrolls = true }
            } else rows = ceil(tools / cols.toFloat()).toInt()
            // a page: under the top bar, over the open bar (on a short screen over the whole height under the top bar)
            val pageW = min(440f, max(300f, wDp * (if (wDp >= hDp) 0.42f else 0.62f))).coerceAtMost(wDp - 64f)
            val pageH = if (compact) max(hDp - top - g, 0f) else max(sceneH - g, 0f)
            val flyoutW = min(560f, wDp - 2 * g)
            // the flyout stands on the bar's top edge (on a short screen it may cover the bar's cards, never the top bar)
            val flyoutH = min(420f, max(sceneH - g, 160f)).coerceAtMost(max(hDp - top - g, 0f))
            // the side tabs: three of them down the right edge where the scene is tall enough, else in a row at the top right
            val sideTabsRow = 3 * SIDE_TAB_PITCH + g > sceneH
            return BuildLayout(wDp, hDp, compact, top, barOpen, barFolded, WarSize.TAB, card, chipRow, cols, rows, scrolls,
                pageW, pageH, flyoutW, flyoutH, sceneH, sideTabsRow)
        }
    }
}

/** A nine-slice's corner sizes in its own pixels (what is kept unstretched), for the pieces drawn as frames. */
object WarSlices {
    /** name -> left, top, right, bottom insets as fractions of the piece's width and height. */
    val INSETS: Map<String, FloatArray> = mapOf(
        "spellbook-background-evergreen-left" to floatArrayOf(0.09f, 0.08f, 0.09f, 0.12f),
        "house-chest-list-item-default" to floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f),
        "house-chest-list-item-active" to floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f),
        "house-chest-list-item-pressed" to floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f),
        "questbg-parchment" to floatArrayOf(0.1f, 0.06f, 0.1f, 0.06f),
        "parchmentpopup-background" to floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
        "ui-frame-neutral-ribbon" to floatArrayOf(0.2f, 0.0f, 0.2f, 0.0f),
        "housing-woodsign" to floatArrayOf(0.06f, 0.0f, 0.06f, 0.0f),
        "common-button-tertiary-normal" to floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f),
        "common-button-tertiary-selected" to floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f),
        "common-button-tertiary-pressed" to floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f),
        "ui-hud-actionbar-iconframe" to floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f),
        "ui-hud-actionbar-iconframe-down" to floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f),
        "housing-basic-container-woodheader" to floatArrayOf(0.05f, 0.0f, 0.05f, 0.0f),
    )

    /** How large a corner of [name] is drawn, in dp, for a frame [w] x [h] dp: never more than a third of either side. */
    fun cornerDp(name: String, pieceW: Int, pieceH: Int, scaleDpPerPx: Float, w: Float, h: Float): FloatArray {
        val f = INSETS[name] ?: return floatArrayOf(0f, 0f, 0f, 0f)
        val l = f[0] * pieceW * scaleDpPerPx; val t = f[1] * pieceH * scaleDpPerPx
        val r = f[2] * pieceW * scaleDpPerPx; val b = f[3] * pieceH * scaleDpPerPx
        val kx = if (l + r > w * 0.66f) w * 0.66f / (l + r) else 1f
        val ky = if (t + b > h * 0.66f) h * 0.66f / (t + b) else 1f
        val k = min(kx, ky)
        return floatArrayOf(l * k, t * k, r * k, b * k)
    }
}
