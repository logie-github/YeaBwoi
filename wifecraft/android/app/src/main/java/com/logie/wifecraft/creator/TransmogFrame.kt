package com.logie.wifecraft.creator

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A rectangle in the Transmogrify window's own units (y down). */
data class TBox(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    val centerX: Float get() = x + w / 2f
    val centerY: Float get() = y + h / 2f
    fun at(dx: Float, dy: Float) = TBox(x + dx, y + dy, w, h)
    /** [inner] placed in this box (its x, y measured from this box's corner). */
    fun inside(inner: TBox) = inner.at(x, y)
    fun contains(px: Float, py: Float) = px >= x && px < right && py >= y && py < bottom
}

/**
 * Where the window is on the screen: its corner ([left], [top], pixels) and [scale] (pixels per unit of the window). The
 * window is fitted as the game fits it (its UIPanel "checkFit": scaled down until it is on the screen), here to the whole
 * screen, and slid sideways so that its character column is in the screen's middle, where the creator draws the character.
 */
data class TransmogFit(val left: Float, val top: Float, val scale: Float) {
    fun x(u: Float) = left + u * scale
    fun y(u: Float) = top + u * scale
    fun px(u: Float) = u * scale
    /** A box of the window in pixels. */
    fun box(b: TBox) = TBox(x(b.x), y(b.y), px(b.w), px(b.h))
}

/** A grid of cards: how many across and down a page has, and where card [i] of a page is. */
data class CardGrid(val area: TBox, val cardW: Float, val cardH: Float, val padX: Float, val padY: Float, val cols: Int, val rows: Int) {
    val perPage: Int get() = cols * rows
    fun pages(count: Int): Int = max(1, ceil(count / perPage.toFloat()).toInt())
    /** Card [i] of its page: the cards are centred across the area, from its top. */
    fun card(i: Int): TBox {
        val used = cols * cardW + (cols - 1) * padX
        val x0 = area.x + (area.w - used) / 2f
        val c = i % cols; val r = i / cols
        return TBox(x0 + c * (cardW + padX), area.y + r * (cardH + padY), cardW, cardH)
    }
    /** The page controls ("Page 1/9", previous, next): PagingControlsHorizontalTemplate at the bottom of the paged area. */
    val pager: TBox get() = TBox(area.x, area.bottom + TransmogFrame.PAGER_BOTTOM_PAD, area.w, TransmogFrame.PAGER_H)
}

/**
 * Midnight's Transmogrify window (Interface/AddOns/Blizzard_Transmog, 12.1.5: Blizzard_Transmog.xml and
 * Blizzard_TransmogTemplates.xml; every number says where it is from), in its own units (the game's UI units, y down here),
 * as far as Wifecraft's creator can have it: the outfit column on the left, the character in the middle with its slot
 * buttons, the wardrobe on the right with its Items, Sets and Custom Sets tabs. Weapons, illusions, the second shoulder,
 * Situations, the outfit slots' price and the item cards' 3D models are not here (see README "Transmog").
 *
 * Pure: the screen (ui/TransmogPanel.kt) draws these boxes with the game's own atlas pieces (assets/transmog_art.json).
 */
object TransmogFrame {
    // TransmogFrame: Size 1618 x 883 (xml 5); PortraitFrameTemplate's metal reaches 13 left, 16 up, 4 right, 3 down
    // (NineSliceLayouts.lua PortraitFrameTemplate)
    const val W = 1618f
    const val H = 883f
    val OUTER = TBox(-13f, -16f, W + 13f + 4f, H + 16f + 3f)

    // the three columns (xml 15-19, 195-199, 456-460): each 860 high from 21 down
    val OUTFITS = TBox(2f, 21f, 312f, 860f)
    val PREVIEW = TBox(OUTFITS.right, 21f, 658f, 860f)
    val WARDROBE = TBox(PREVIEW.right, 21f, 644f, 860f)

    // PortraitFrameBaseTemplate (SharedUIPanelTemplates.xml 544-590): the portrait 62 x 62 at -5, +7; the title between 58 and
    // 24 from the right, 20 high, one down; UIPanelCloseButtonDefaultAnchors: 24 x 24 at the top right, one out
    val PORTRAIT = TBox(-5f, -7f, 62f, 62f)
    val TITLE = TBox(58f, 1f, W - 58f - 24f, 20f)
    val CLOSE = TBox(W + 1f - 24f, 0f, 24f, 24f)
    const val TITLE_TEXT = "Transmogrify"          // GlobalStrings TRANSMOGRIFY

    /** The metal border's nine pieces (NineSliceLayouts.lua PortraitFrameTemplate): name to its box. */
    fun metal(size: (String) -> Pair<Float, Float>): List<Pair<String, TBox>> {
        val (tlW, tlH) = size(TL); val (trW, trH) = size(TR); val (blW, blH) = size(BL); val (brW, brH) = size(BR)
        val tl = TBox(-13f, -16f, tlW, tlH)
        val tr = TBox(W + 4f - trW, -16f, trW, trH)
        val bl = TBox(-13f, H + 3f - blH, blW, blH)
        val br = TBox(W + 4f - brW, H + 3f - brH, brW, brH)
        val top = size(EDGE_TOP).second; val bottom = size(EDGE_BOTTOM).second
        val left = size(EDGE_LEFT).first; val right = size(EDGE_RIGHT).first
        return listOf(
            EDGE_TOP to TBox(tl.right, tl.y, tr.x - tl.right, top),
            EDGE_BOTTOM to TBox(bl.right, H + 3f - bottom, br.x - bl.right, bottom),
            EDGE_LEFT to TBox(tl.x, tl.bottom, left, bl.y - tl.bottom),
            EDGE_RIGHT to TBox(tr.right - right, tr.bottom, right, br.y - tr.bottom),
            TL to tl, TR to tr, BL to bl, BR to br,
        )
    }
    const val TL = "ui-frame-portraitmetal-cornertopleft"; const val TR = "ui-frame-metal-cornertopright"
    const val BL = "ui-frame-metal-cornerbottomleft"; const val BR = "ui-frame-metal-cornerbottomright"
    const val EDGE_TOP = "_ui-frame-metal-edgetop"; const val EDGE_BOTTOM = "_ui-frame-metal-edgebottom"
    const val EDGE_LEFT = "!ui-frame-metal-edgeleft"; const val EDGE_RIGHT = "!ui-frame-metal-edgeright"

    /**
     * The window on a [width] x [height] pixel screen: as large as fits (its metal too), its character column's middle on the
     * screen's middle when the window still fits so, else as near as it can be; centred up and down.
     */
    fun fit(width: Float, height: Float): TransmogFit {
        val s = min(width / OUTER.w, height / OUTER.h)
        val wanted = width / 2f - PREVIEW.centerX * s
        val lo = -OUTER.x * s                       // the metal's left edge on the screen's left
        val hi = width - OUTER.right * s            // the metal's right edge on the screen's right
        val left = if (hi < lo) (lo + hi) / 2f else wanted.coerceIn(lo, hi)
        val top = (height - OUTER.h * s) / 2f - OUTER.y * s
        return TransmogFit(left, top, s)
    }

    // ---- the outfit column (OutfitCollection, xml 15-188), from its corner -----------------------------------------------
    val OUTFIT_TOP = TBox(0f, 0f, 312f, 102f)                  // transmog-outfit-toptexture (useAtlasSize), TOP
    val OUTFIT_BOTTOM = TBox(0f, 860f - 90f, 312f, 90f)        // transmog-outfit-bottomtexture, BOTTOM
    val OUTFIT_DIVIDER = TBox(312f - 6f + 2f, 0f, 6f, 860f)    // transmog-outfit-darkbg-cornerline 6 x 860 at TOPRIGHT +2
    val CLEAR_ALL = TBox(14f, 44f, 280f, 45f)                  // ShowEquippedGearSpellFrame 280 x 45 at 14, -44
    val CLEAR_ALL_ICON = TBox(14f, 44f, 45f, 45f)              // its button (UIPanelSpellButtonFrameTemplate), 45 square
    val OUTFIT_LIST = TBox(5f, 102f, 300f, 650f)               // OutfitList 300 x 650 at 5, -102
    const val OUTFIT_ROW_H = 45f                               // TransmogOutfitEntryTemplate 45 high
    val OUTFIT_ROW_ICON = TBox(5f, 0f, 45f, 45f)               // OutfitIcon 45 x 45 at LEFT 5; its Icon 36
    val OUTFIT_ROW_CARD = TBox(5f + 45f + 3f, 1f, 208f, 43f)   // OutfitButton 208 x 43, 3 right of the icon
    const val OUTFIT_NAME_X = 12f + 10f                        // TextContent at 12, Name at 10 in it
    val SAVE = TBox(9f, 860f - 14f - 28f, 128f, 28f)           // SaveOutfitButton 128 x 28 at BOTTOMLEFT 9, 14

    /** The outfit list's row [i] (from the column's corner): the ScrollBox is set in 5 from the list's left, 2 from its top. */
    fun outfitRow(i: Int) = TBox(OUTFIT_LIST.x + 5f, OUTFIT_LIST.y + 2f + i * OUTFIT_ROW_H, OUTFIT_LIST.w - 5f - 25f, OUTFIT_ROW_H)

    // ---- the character column (CharacterPreview, xml 195-453), from its corner --------------------------------------------
    const val GRADIENT_W = 123f                                // transmog-outfit-darkbg-gradient 123 x 860, LEFT and RIGHT
    val HIDE_IGNORED = TBox(25f, 25f, 30f, 29f)                // ToggleOptions at 25, -25: HideIgnoredToggle 30 x 29
    val UNDO_ALL = TBox(658f - 21f - 36f, 134f, 36f, 36f)      // ClearAllPendingButton 36 x 36 at TOPRIGHT -21, -134
    const val SLOT = 59f                                       // TransmogAppearanceSlotTemplate 59 x 59, its Icon 45
    const val SLOT_ICON = 45f
    const val SLOT_GAP = 5f                                    // LeftSlots / RightSlots: spacing 5
    const val SLOT_SIDE = 28f                                  // LeftSlots LEFT 28, RightSlots RIGHT -28

    /**
     * The slots the window shows, as TransmogOutfitSlotInfo (12.1.7) orders them by TransmogOutfitSlotEnum: head 0, shoulder 1
     * (the second shoulder 2 is not here: one shoulder piece), back 3, chest 4, tabard 5, shirt 6, wrist 7 down the left;
     * hands 8, waist 9, legs 10, feet 11 down the right; the weapons (12, 13: the bottom row) are not here.
     */
    val LEFT = listOf(ClothingSlot.HEAD, ClothingSlot.SHOULDERS, ClothingSlot.BACK, ClothingSlot.TORSO, ClothingSlot.TABARD,
        ClothingSlot.SHIRT, ClothingSlot.WRISTS)
    val RIGHT = listOf(ClothingSlot.GLOVES, ClothingSlot.WAIST, ClothingSlot.LEGS, ClothingSlot.FEET)
    val SLOTS = LEFT + RIGHT

    /** The game's name of a slot (GlobalStrings HEADSLOT ... FEETSLOT). */
    fun caption(slot: ClothingSlot): String = when (slot) {
        ClothingSlot.HEAD -> "Head"; ClothingSlot.SHOULDERS -> "Shoulders"; ClothingSlot.BACK -> "Back"
        ClothingSlot.TORSO -> "Chest"; ClothingSlot.TABARD -> "Tabard"; ClothingSlot.SHIRT -> "Shirt"
        ClothingSlot.WRISTS -> "Wrist"; ClothingSlot.GLOVES -> "Hands"; ClothingSlot.WAIST -> "Waist"
        ClothingSlot.LEGS -> "Legs"; ClothingSlot.FEET -> "Feet"
    }

    /** The slot's part of its atlas names (TransmogOutfitSlotInfo's UnassignedAtlasID / UnassignedDisplayAtlasID). */
    fun atlasKey(slot: ClothingSlot): String = when (slot) {
        ClothingSlot.HEAD -> "head"; ClothingSlot.SHOULDERS -> "shoulders"; ClothingSlot.BACK -> "back"
        ClothingSlot.TORSO -> "chest"; ClothingSlot.TABARD -> "tabard"; ClothingSlot.SHIRT -> "shirt"
        ClothingSlot.WRISTS -> "wrist"; ClothingSlot.GLOVES -> "hands"; ClothingSlot.WAIST -> "waist"
        ClothingSlot.LEGS -> "legs"; ClothingSlot.FEET -> "feet"
    }
    fun unassignedSlotAtlas(slot: ClothingSlot) = "transmog-gearslot-unassigned-" + atlasKey(slot)
    fun unassignedAppearanceAtlas(slot: ClothingSlot) = "transmog-appearance-unassigned-" + atlasKey(slot)

    /**
     * Where [slot]'s button is (from the character column's corner), among the slots [shown] (Hide Ignored Slots takes some
     * away: the rest close up, as the layout frames do): a column of them down each side, centred up and down; null when
     * the slot isn't shown.
     */
    fun slotBox(slot: ClothingSlot, shown: List<ClothingSlot> = SLOTS): TBox? {
        val left = slot in LEFT
        val column = (if (left) LEFT else RIGHT).filter { it in shown }
        val i = column.indexOf(slot)
        if (i < 0) return null
        val height = column.size * SLOT + (column.size - 1) * SLOT_GAP
        val y0 = PREVIEW.h / 2f - height / 2f
        val x = if (left) SLOT_SIDE else PREVIEW.w - SLOT_SIDE - SLOT
        return TBox(x, y0 + i * (SLOT + SLOT_GAP), SLOT, SLOT)
    }

    // ---- the wardrobe column (WardrobeCollection, xml 456-820), from its corner -------------------------------------------
    val TABS_AT = TBox(32f, 12f, 0f, 32f)                       // TabHeaders at 32, -12; TabSystemTopButtonTemplate 32 high
    const val TAB_MIN = 95f; const val TAB_MAX = 170f           // minTabWidth, maxTabWidth
    const val TAB_TEXT_PAD = 17f; const val TAB_GAP = 1f        // the tab's textPadding; TabSystemTemplate spacing 1
    val CONTENT = TBox(0f, 35f, 644f, 823f)                     // TabContent 644 x 823 at 0, -35
    val CONTENT_BG = TBox(4f, 4f, 631f, 811f)                   // transmog-tabs-frame-bg (useAtlasSize) at 4, -4
    val CONTENT_BORDER = TBox(-11f, -12f, 661f, 841f)           // transmog-tabs-frame 661 x 841 at -11, 12

    /** A tab's width for a caption [textWidth] wide (TabSystemButtonMixin: the text and its padding, held to min..max). */
    fun tabWidth(textWidth: Float) = (textWidth + 2 * TAB_TEXT_PAD).coerceIn(TAB_MIN, TAB_MAX)

    // the Items tab (ItemsFrame), from the tab content's corner
    val SLOT_TITLE = TBox(23f, 58f, 400f, 20f)                  // ActiveSlotTitle, GameFontHighlightHuge, TOPLEFT 23, -58
    val FILTER = TBox(644f - 29f - 135f, 24f, 135f, 18f)        // FilterButton (WowStyle1FilterDropdownTemplate 135 x 18) at TOPRIGHT -29, -24
    val SEARCH = TBox(FILTER.x - 10f - 142f, FILTER.y - 1f, 142f, 19f)  // SearchBox 142 x 19, 10 left of the filter, 1 up
    const val DISPLAY_W = 188f; const val DISPLAY_H = 36f       // DisplayTypeButtonTemplate 188 x 36
    const val DISPLAY_GAP = 25f                                 // DisplayTypes: spacing 25
    /** The display type buttons ("Ignore Slot", "Show Equipped Gear"): 16 right of the title and 21 under it. */
    fun displayType(i: Int) = TBox(SLOT_TITLE.x + 16f + i * (DISPLAY_W + DISPLAY_GAP), SLOT_TITLE.bottom + 21f, DISPLAY_W, DISPLAY_H)
    val DISPLAY_ICON = TBox(-15f, -2f, 40f, 40f)                // IconFrame 40 x 40 at LEFT -15, +1
    const val DISPLAY_TEXT_X = 30f                              // the button's text at LEFT 30, 150 wide

    // the card grids (PagedNaturalSizeGridContentFrameTemplate): the paged area less the page controls at its bottom
    const val PAGER_H = 32f                                     // PagingControlsHorizontalTemplate 32 high,
    const val PAGER_BOTTOM_PAD = 3f                             // bottomPadding 3, BOTTOM 5
    private const val PAGER_ROOM = PAGER_H + 5f + PAGER_BOTTOM_PAD

    private fun grid(area: TBox, w: Float, h: Float, padX: Float, padY: Float): CardGrid {
        val cards = TBox(area.x, area.y, area.w, area.h - PAGER_ROOM)
        val cols = max(1, floor((cards.w + padX) / (w + padX)).toInt())
        val rows = max(1, floor((cards.h + padY) / (h + padY)).toInt())
        return CardGrid(cards, w, h, padX, padY, cols, rows)
    }
    /** Items: TransmogItemModelTemplate 100 x 132, x and y padding 20, from 30, -163 to -30, 10. */
    val ITEMS = grid(TBox(30f, 163f, 644f - 60f, 823f - 163f - 10f), 100f, 132f, 20f, 20f)
    /** Sets and Custom Sets: TransmogSetBaseModelTemplate 178 x 218, x padding 27, y 19, from 26, -72 to -26, 10. */
    val SETS = grid(TBox(26f, 72f, 644f - 52f, 823f - 72f - 10f), 178f, 218f, 27f, 19f)
    const val ITEM_BORDER_W = 114f; const val ITEM_BORDER_H = 146f   // transmog-itemcard-* (useAtlasSize), CENTER
    const val SET_BORDER_W = 194f; const val SET_BORDER_H = 234f     // transmog-setcard-* 194 x 234, CENTER
    val HIDDEN_ICON = TBox(-3f, 2f, 29f, 24f)                   // HideVisual's transmog-icon-hidden at TOPLEFT -3, -2

    // the fonts (FontStyles: Friz Quadrata): GameFontNormal / Highlight 12, GameFontNormalMed2 14, GameFontHighlightHuge 20
    const val FONT = 12f; const val FONT_MED = 14f; const val FONT_HUGE = 20f
    const val GOLD = 0xFFD100          // NORMAL_FONT_COLOR (1, 0.82, 0)
    const val WHITE = 0xFFFFFF         // HIGHLIGHT_FONT_COLOR
    const val GREY = 0x808080          // GRAY_FONT_COLOR / DISABLED (0.5)

    /** ITEM_QUALITY_COLORS by OverallQualityID (poor, common, uncommon, rare, epic, legendary, artifact, heirloom, WoW Token). */
    fun qualityColour(q: Int): Int = when (q) {
        0 -> 0x9D9D9D; 1 -> 0xFFFFFF; 2 -> 0x1EFF00; 3 -> 0x0070DD; 4 -> 0xA335EE; 5 -> 0xFF8000; 6 -> 0xE6CC80
        7, 8 -> 0x00CCFF; else -> 0xFFFFFF
    }

    /** EXPANSION_NAME0 ... 11 (GlobalStrings): the filter's lines. */
    val EXPANSIONS = listOf("Classic", "The Burning Crusade", "Wrath of the Lich King", "Cataclysm", "Mists of Pandaria",
        "Warlords of Draenor", "Legion", "Battle for Azeroth", "Shadowlands", "Dragonflight", "The War Within", "Midnight")
    fun expansionName(e: Int): String = EXPANSIONS.getOrNull(e) ?: "Expansion $e"
}

/** The wardrobe's tabs (TransmogWardrobeMixin:OnLoad: TRANSMOG_TAB_ITEMS, _SETS, _CUSTOM_SETS; Situations is not here). */
enum class TransmogTab(val caption: String) { ITEMS("Items"), SETS("Sets"), CUSTOM_SETS("Custom Sets") }
