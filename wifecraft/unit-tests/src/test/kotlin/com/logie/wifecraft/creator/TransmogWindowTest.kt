package com.logie.wifecraft.creator

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The creator's Transmog window as Midnight's Transmogrify window: its layout (TransmogFrame: the game's XML numbers), its
 * state (TransmogState: pending until saved, Ignore Slot, Show Equipped Gear, sets, paging, search, filter) and its art
 * (every atlas piece the window names is in assets/transmog_art.json, every piece on a sheet listed there).
 */
class TransmogWindowTest {
    private val F = TransmogFrame

    // ---- the layout --------------------------------------------------------------------------------------------------------

    @Test fun theColumnsAreTheGamesAndFillTheWindow() {
        assertEquals(1618f, F.W); assertEquals(883f, F.H)
        assertEquals(312f, F.OUTFITS.w); assertEquals(658f, F.PREVIEW.w); assertEquals(644f, F.WARDROBE.w)
        assertEquals(F.OUTFITS.right, F.PREVIEW.x)
        assertEquals(F.PREVIEW.right, F.WARDROBE.x)
        assertEquals(F.W - 2f, F.WARDROBE.right)                // 2 in from each side
        for (c in listOf(F.OUTFITS, F.PREVIEW, F.WARDROBE)) { assertEquals(21f, c.y); assertEquals(860f, c.h) }
    }

    @Test fun aWidePhoneHasTheCharacterColumnInTheMiddleAndTheWindowOnTheScreen() {
        // a 20:9 phone, 2400 x 1080 pixels
        val fit = F.fit(2400f, 1080f)
        assertEquals(1080f / F.OUTER.h, fit.scale, 1e-4f)
        assertEquals(1200f, fit.x(F.PREVIEW.centerX), 0.5f)
        assertTrue(fit.x(F.OUTER.x) >= -0.5f && fit.x(F.OUTER.right) <= 2400.5f)
        assertEquals(0f, fit.y(F.OUTER.y), 0.5f); assertEquals(1080f, fit.y(F.OUTER.bottom), 0.5f)
    }

    @Test fun aNarrowScreenFitsTheWholeWindowAcross() {
        // 4:3: the width decides; the window is as far towards the middle column as it can be, still whole on the screen
        val fit = F.fit(1600f, 1200f)
        assertEquals(1600f / F.OUTER.w, fit.scale, 1e-4f)
        assertEquals(0f, fit.x(F.OUTER.x), 0.5f); assertEquals(1600f, fit.x(F.OUTER.right), 0.5f)
        assertTrue(fit.y(F.OUTER.y) > 0f)
    }

    @Test fun theSlotsStandDownEachSideOfTheCharacterInTheGamesOrder() {
        assertEquals(listOf("Head", "Shoulders", "Back", "Chest", "Tabard", "Shirt", "Wrist"), F.LEFT.map { F.caption(it) })
        assertEquals(listOf("Hands", "Waist", "Legs", "Feet"), F.RIGHT.map { F.caption(it) })
        assertEquals(ClothingSlot.entries.toSet(), F.SLOTS.toSet())   // every slot the creator dresses, once
        val boxes = F.SLOTS.map { assertNotNull(F.slotBox(it)) }
        for (b in boxes) { assertTrue(b.x >= 0f && b.right <= F.PREVIEW.w && b.y >= 0f && b.bottom <= F.PREVIEW.h) }
        for ((i, a) in boxes.withIndex()) for (b in boxes.drop(i + 1)) assertFalse(a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom)
        assertEquals(28f, F.slotBox(ClothingSlot.HEAD)!!.x)
        assertEquals(F.PREVIEW.w - 28f - 59f, F.slotBox(ClothingSlot.FEET)!!.x)
        // centred up and down, 5 apart
        val head = F.slotBox(ClothingSlot.HEAD)!!; val wrist = F.slotBox(ClothingSlot.WRISTS)!!
        assertEquals(F.PREVIEW.h / 2f, (head.y + wrist.bottom) / 2f, 0.01f)
        assertEquals(64f, F.slotBox(ClothingSlot.SHOULDERS)!!.y - head.y)
        // a hidden slot leaves no gap
        val shown = F.SLOTS - ClothingSlot.SHOULDERS
        assertNull(F.slotBox(ClothingSlot.SHOULDERS, shown))
        assertEquals(64f, F.slotBox(ClothingSlot.BACK, shown)!!.y - F.slotBox(ClothingSlot.HEAD, shown)!!.y)
    }

    @Test fun theGridsHoldWhatTheirAreasHold() {
        assertEquals(5, F.ITEMS.cols); assertEquals(4, F.ITEMS.rows); assertEquals(20, F.ITEMS.perPage)
        assertEquals(3, F.SETS.cols); assertEquals(3, F.SETS.rows)
        for (g in listOf(F.ITEMS, F.SETS)) {
            for (i in 0 until g.perPage) {
                val c = g.card(i)
                assertTrue(c.x >= g.area.x - 0.01f && c.right <= g.area.right + 0.01f && c.bottom <= g.area.bottom + 0.01f, "card $i at $c")
            }
            assertTrue(g.pager.bottom <= F.CONTENT.h)
            assertEquals(1, g.pages(0)); assertEquals(1, g.pages(g.perPage)); assertEquals(2, g.pages(g.perPage + 1))
        }
        // the search box is left of the filter, the slot's title over the display type buttons, the grid under them
        assertTrue(F.SEARCH.right < F.FILTER.x)
        assertTrue(F.displayType(0).y > F.SLOT_TITLE.bottom && F.displayType(1).x > F.displayType(0).right)
        assertTrue(F.ITEMS.area.y > F.displayType(0).bottom)
        assertEquals(95f, F.tabWidth(10f)); assertEquals(170f, F.tabWidth(400f)); assertEquals(134f, F.tabWidth(100f))
    }

    @Test fun theMetalMeetsAtItsCorners() {
        val sizes = mapOf(F.TL to (75f to 75f), F.TR to (75f to 75f), F.BL to (32f to 32f), F.BR to (32f to 32f),
            F.EDGE_TOP to (32f to 75f), F.EDGE_BOTTOM to (16f to 32f), F.EDGE_LEFT to (75f to 16f), F.EDGE_RIGHT to (75f to 16f))
        val m = F.metal { sizes.getValue(it) }.toMap()
        assertEquals(F.OUTER.x, m.getValue(F.TL).x); assertEquals(F.OUTER.y, m.getValue(F.TL).y)
        assertEquals(F.OUTER.right, m.getValue(F.BR).right); assertEquals(F.OUTER.bottom, m.getValue(F.BR).bottom)
        assertEquals(m.getValue(F.TL).right, m.getValue(F.EDGE_TOP).x); assertEquals(m.getValue(F.TR).x, m.getValue(F.EDGE_TOP).right)
        assertEquals(m.getValue(F.TL).bottom, m.getValue(F.EDGE_LEFT).y); assertEquals(m.getValue(F.BL).y, m.getValue(F.EDGE_LEFT).bottom)
    }

    // ---- the state ---------------------------------------------------------------------------------------------------------

    private val wearing = mapOf(ClothingSlot.SHIRT.id to 101, ClothingSlot.LEGS.id to 102)

    @Test fun aPickIsPendingUntilSavedAndUndoPutsBackWhatIsSaved() {
        var s = TransmogState(wearing)
        assertFalse(s.anyPending)
        assertEquals(ClothingSlot.HEAD, s.slot)                         // the first slot, as the game selects it
        s = s.select(ClothingSlot.SHIRT).choose(201)
        assertTrue(s.anyPending); assertTrue(s.isPending(ClothingSlot.SHIRT)); assertFalse(s.isPending(ClothingSlot.LEGS))
        assertEquals(Worn.PENDING, s.worn(ClothingSlot.SHIRT)); assertEquals(Worn.SAVED, s.worn(ClothingSlot.LEGS))
        assertEquals(Worn.NO, s.worn(ClothingSlot.HEAD))
        assertEquals(Worn.PENDING, s.wornCard(201)); assertEquals(Worn.NO, s.wornCard(101))
        assertEquals(wearing, s.undoAll().pending)
        val saved = s.save()
        assertFalse(saved.anyPending); assertEquals(1, saved.saves)
        assertEquals(Worn.SAVED, saved.wornCard(201))
        assertEquals(mapOf(ClothingSlot.SHIRT.id to 201, ClothingSlot.LEGS.id to 102), saved.saved)
    }

    @Test fun ignoreSlotEmptiesItAndShowEquippedGearPutsBackWhatSheWore() {
        var s = TransmogState(wearing).select(ClothingSlot.LEGS)
        assertTrue(s.showingEquipped); assertFalse(s.ignoring)
        s = s.ignore()
        assertTrue(s.ignoring); assertFalse(s.showingEquipped)
        assertNull(s.pending[ClothingSlot.LEGS.id]); assertEquals(Worn.PENDING, s.worn(ClothingSlot.LEGS))
        s = s.showEquipped()
        assertEquals(102, s.pending[ClothingSlot.LEGS.id]); assertFalse(s.anyPending)
        // a slot she wore nothing in: Show Equipped Gear leaves it empty, and isn't "on"
        val head = TransmogState(wearing).select(ClothingSlot.HEAD).choose(5).showEquipped()
        assertNull(head.pending[ClothingSlot.HEAD.id]); assertFalse(head.showingEquipped); assertTrue(head.ignoring)
        // Clear Current Transmogrifications: everything empty
        assertEquals(emptyMap(), TransmogState(wearing).clearAll().pending)
    }

    @Test fun aSetIsWornWholeAndIsMarkedWhileSheWearsExactlyIt() {
        val set = ClothingSet(7, "Silk Outfit", mapOf(ClothingSlot.TORSO.id to 31, ClothingSlot.GLOVES.id to 41, 16 to 99))
        var s = TransmogState(wearing).wear(set)
        assertEquals(mapOf(ClothingSlot.TORSO.id to 31, ClothingSlot.GLOVES.id to 41), s.pending)   // no weapon slot
        val clean = set.copy(displays = s.pending)
        assertEquals(Worn.PENDING, s.wornSet(clean))
        s = s.save()
        assertEquals(Worn.SAVED, s.wornSet(clean))
        assertEquals(Worn.NO, s.select(ClothingSlot.HEAD).choose(1).wornSet(clean))
    }

    @Test fun hideIgnoredSlotsShowsOnlyFilledSlotsAndMovesTheSelection() {
        val s = TransmogState(wearing).hideIgnoredSlots(true)
        assertEquals(listOf(ClothingSlot.SHIRT, ClothingSlot.LEGS), s.shownSlots())
        assertEquals(ClothingSlot.SHIRT, s.slot)
        assertEquals(F.SLOTS, s.hideIgnoredSlots(false).shownSlots())
    }

    @Test fun pagesSearchAndFilter() {
        val pieces = (1..45).map { ClothingPiece(it, 1000 + it, "Hat %02d".format(it), ClothingSlot.HEAD, expansion = it % 3) } +
            ClothingPiece(99, 2000, "Pants", ClothingSlot.LEGS)
        val catalog = TransmogCatalog(pieces, listOf(ClothingSet(3, "Set A", mapOf(1 to 1001), expansion = 2),
            ClothingSet(-1, "Chromie", mapOf(1 to 1002))))
        assertEquals(45, TransmogLists.items(catalog, ClothingSlot.HEAD, "", emptySet()).size)
        assertEquals(listOf("Hat 07"), TransmogLists.items(catalog, ClothingSlot.HEAD, " hat 07", emptySet()).map { it.name })
        assertEquals(15, TransmogLists.items(catalog, ClothingSlot.HEAD, "", setOf(0)).size)
        assertTrue(TransmogLists.items(catalog, ClothingSlot.HEAD, "", setOf(-1)).isEmpty())   // Uncheck All
        assertEquals(listOf(0, 1, 2), TransmogLists.expansions(catalog))
        assertEquals(listOf("Set A"), TransmogLists.sets(catalog, "", emptySet()).map { it.name })
        assertEquals(listOf("Chromie"), TransmogLists.customSets(catalog).map { it.name })
        assertTrue(TransmogLists.sets(catalog, "", setOf(1)).isEmpty())

        val list = TransmogLists.items(catalog, ClothingSlot.HEAD, "", emptySet())
        // the Items tab opens on the page of what she wears
        assertEquals(1, TransmogLists.pageOf(list, 1030, 20)); assertEquals(0, TransmogLists.pageOf(list, null, 20))
        var s = TransmogState(mapOf(1 to 1030))
        val key = s.pageKey()
        assertEquals("items:1", key)
        assertEquals(1, s.page(key, list.size, 20, TransmogLists.pageOf(list, 1030, 20)))
        s = s.turn(key, 2)
        assertEquals(2, s.page(key, list.size, 20))
        assertEquals(2, s.turn(key, 9).page(key, list.size, 20))           // held to the last page
        s = s.typed("hat")
        assertEquals(0, s.page(key, list.size, 20))                         // a search starts at the first page
        assertEquals("hat", s.searchFor(TransmogTab.ITEMS)); assertEquals("", s.searchFor(TransmogTab.SETS))
        assertEquals(TransmogState.SEARCH_MAX, s.typed("x".repeat(99)).searchFor(TransmogTab.ITEMS).length)
        assertEquals("Page 2/3", TransmogLists.pageText(1, 3))
        assertEquals(setOf(1), s.filter(setOf(1)).expansions)
        assertTrue(s.filter(setOf(1)).pages.isEmpty())
    }

    // ---- the art -----------------------------------------------------------------------------------------------------------

    @Test fun everyPieceTheWindowNamesIsInItsArtTable() {
        val doc = JSONObject(File(System.getProperty("wifecraft.assets"), "transmog_art.json").readText())
        val pieces = doc.getJSONObject("pieces")
        val sheets = doc.getJSONObject("sheets")
        val files = doc.getJSONObject("files")
        for (name in pieces.keys()) {
            val p = pieces.getJSONObject(name)
            val sheet = sheets.getJSONArray(p.getInt("f").toString())
            val b = p.getJSONArray("b")
            assertTrue(b.getInt(0) >= 0 && b.getInt(2) <= sheet.getInt(0) && b.getInt(1) >= 0 && b.getInt(3) <= sheet.getInt(1), "$name's box is in its sheet")
            assertTrue(p.getJSONArray("s").getInt(0) > 0, name)
        }
        // the names in the window's code: quoted atlas names, and the slot pictures made from each slot's key
        val ui = File(System.getProperty("wifecraft.assets"), "../java/com/logie/wifecraft/ui/TransmogPanel.kt").readText()
        val named = Regex("\"([!_]?(?:transmog|common|ui|uiframe|redbutton|128|checkbox|checkmark|tooltip|_uiframe|_128|_tooltip|!tooltip)[a-z0-9!_-]*)\"")
            .findAll(ui + File(System.getProperty("wifecraft.assets"), "../java/com/logie/wifecraft/creator/TransmogFrame.kt").readText())
            .map { it.groupValues[1] }.filter { !it.endsWith("-") }.toSet()
        val withStates = named.flatMap { n -> if (n.startsWith("128-redbutton") || n.startsWith("_128-redbutton")) listOf(n, "$n-pressed", "$n-disabled") else listOf(n) }
        val slotPictures = F.SLOTS.flatMap { listOf(F.unassignedSlotAtlas(it), F.unassignedAppearanceAtlas(it)) }
        val missing = (withStates + slotPictures).filter { !pieces.has(it) && !files.has(it) }
        assertEquals(emptyList(), missing)
        for (n in listOf("page-prev", "page-next")) for (state in listOf("", "-down", "-disabled")) assertTrue(files.has(n + state))
        // the card borders are nine-sliced as the game's are
        assertEquals(listOf(27, 66, 27, 27), pieces.getJSONObject("transmog-itemcard-default").getJSONArray("n").let { a -> (0..3).map { a.getInt(it) } })
    }

    @Test fun piecesCarryQualityAndExpansionFromTheTables() {
        val t = object : Tables {
            private val parsed = mapOf(
                "ItemAppearance" to "ID,ItemDisplayInfoID,DefaultIconFileDataID\n3,31,900",
                "ItemModifiedAppearance" to "ItemID,ItemAppearanceID,ItemAppearanceModifierID\n1,3,0",
                "ItemSparse" to "ID,InventoryType,Display_lang,OverallQualityID,ExpansionID\n1,1,Crown,4,2",
                "ItemSet" to "ID,Name_lang,ItemID_0",
            ).mapValues { CsvTables.parse(it.value) }
            override fun rows(name: String) = parsed[name].orEmpty()
        }
        val p = TransmogCatalog.load(t).pieces.single()
        assertEquals(4, p.quality); assertEquals(2, p.expansion); assertEquals(900, p.icon)
        assertEquals(0xA335EE, F.qualityColour(p.quality))
        assertEquals("Wrath of the Lich King", F.expansionName(p.expansion))
    }
}
