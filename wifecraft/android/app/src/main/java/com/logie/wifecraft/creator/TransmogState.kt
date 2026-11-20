package com.logie.wifecraft.creator

/** How a card or slot stands to what she wears: not on her, on her and saved (transmogrified), or on her and not saved yet (pending). */
enum class Worn { NO, SAVED, PENDING }

/**
 * The Transmogrify window's state, as the game's works (Blizzard_Transmog.lua): a slot of the character is selected and the
 * Items tab shows its appearances; a pick is pending (shown on her, its slot and card glowing purple) until Save Outfit, and
 * closing the window without saving puts back what she wore ([saved]). Immutable: each change is a copy. Pure JVM.
 *
 * [saved] and [pending] are slot id (ClothingSlot.id) to ItemDisplayInfo id, as Appearance.clothing; a slot that is not in
 * them shows nothing (the window's "Ignore Slot").
 */
data class TransmogState(
    val saved: Map<Int, Int>,
    val pending: Map<Int, Int> = saved,
    val slot: ClothingSlot = TransmogFrame.SLOTS.first(),
    val tab: TransmogTab = TransmogTab.ITEMS,
    val search: Map<TransmogTab, String> = emptyMap(),
    /** The page each list is on, by [pageKey]; a list not here opens on the page of what she wears ([TransmogLists.pageOf]). */
    val pages: Map<String, Int> = emptyMap(),
    /** The filter's expansions (ExpansionID); empty: every one; {-1} (Uncheck All): none. */
    val expansions: Set<Int> = emptySet(),
    /** Hide Ignored Slots (the character column's toggle): the slots with nothing in them are not shown. */
    val hideIgnored: Boolean = false,
    /** Bumped by each Save Outfit (the saved flash plays for it). */
    val saves: Int = 0,
) {
    fun piece(slot: ClothingSlot): Int? = pending[slot.id]
    fun savedPiece(slot: ClothingSlot): Int? = saved[slot.id]
    fun isPending(slot: ClothingSlot): Boolean = pending[slot.id] != saved[slot.id]
    val anyPending: Boolean get() = pending != saved

    /** A slot's button: what it shows. */
    fun worn(slot: ClothingSlot): Worn = when {
        piece(slot) == null -> if (isPending(slot)) Worn.PENDING else Worn.NO
        isPending(slot) -> Worn.PENDING
        else -> Worn.SAVED
    }

    /** An item card of the selected slot. */
    fun wornCard(display: Int): Worn = when {
        piece(slot) != display -> Worn.NO
        isPending(slot) -> Worn.PENDING
        else -> Worn.SAVED
    }

    /** A set card: worn when she wears exactly its pieces. */
    fun wornSet(set: ClothingSet): Worn = when {
        set.displays.isEmpty() || pending != set.displays -> Worn.NO
        anyPending -> Worn.PENDING
        else -> Worn.SAVED
    }

    /** "Ignore Slot" is on: nothing in the selected slot. "Show Equipped Gear": the slot has what she wore, and she wore something. */
    val ignoring: Boolean get() = piece(slot) == null
    val showingEquipped: Boolean get() = savedPiece(slot) != null && piece(slot) == savedPiece(slot)

    fun select(s: ClothingSlot) = copy(slot = s, tab = TransmogTab.ITEMS)
    fun choose(display: Int) = copy(pending = pending + (slot.id to display))
    fun ignore() = copy(pending = pending - slot.id)
    fun showEquipped() = copy(pending = savedPiece(slot)?.let { pending + (slot.id to it) } ?: (pending - slot.id))
    /** A set or a named outfit: its pieces, nothing else (as the old panel's outfits did). */
    fun wear(set: ClothingSet) = copy(pending = set.displays.filterKeys { k -> TransmogFrame.SLOTS.any { it.id == k } })
    /** "Clear Current Transmogrifications": every slot empty. */
    fun clearAll() = copy(pending = emptyMap())
    /** The undo button over the character: back to what is saved. */
    fun undoAll() = copy(pending = saved)
    fun save() = copy(saved = pending, saves = saves + 1)

    fun openTab(t: TransmogTab) = copy(tab = t)
    fun searchFor(t: TransmogTab): String = search[t].orEmpty()
    /** Typing a search goes back to the first page of its list. */
    fun typed(text: String) = copy(search = search + (tab to text.take(SEARCH_MAX)), pages = pages - pageKey())
    fun filter(expansions: Set<Int>) = copy(expansions = expansions, pages = emptyMap())
    fun hideIgnoredSlots(on: Boolean): TransmogState {
        val next = copy(hideIgnored = on)
        // a selected slot that is now hidden: the first one shown is selected (TransmogCharacterMixin: FindValidSlotToSelect)
        if (!on || piece(slot) != null) return next
        return next.copy(slot = TransmogFrame.SLOTS.firstOrNull { piece(it) != null } ?: slot)
    }

    /** The slots the character column shows: all, or with Hide Ignored Slots those with something in them (and the selected one). */
    fun shownSlots(): List<ClothingSlot> =
        if (!hideIgnored) TransmogFrame.SLOTS else TransmogFrame.SLOTS.filter { piece(it) != null || it == slot }

    /** The page list [key] is on, given its [count] of cards and that [defaultPage] is where it opens. */
    fun page(key: String, count: Int, perPage: Int, defaultPage: Int = 0): Int {
        val pages = maxOf(1, (count + perPage - 1) / perPage)
        return (this.pages[key] ?: defaultPage).coerceIn(0, pages - 1)
    }
    fun turn(key: String, to: Int) = copy(pages = pages + (key to maxOf(0, to)))

    /** The list on show: the Items of the selected slot, or the Sets, or the Custom Sets. */
    fun pageKey(t: TransmogTab = tab): String = if (t == TransmogTab.ITEMS) "items:${slot.id}" else t.name

    companion object {
        const val SEARCH_MAX = 40       // TransmogSearchBoxTemplate letters="40"
    }
}

/** The lists the wardrobe's tabs page through (off the main thread: the catalog holds thousands of pieces). Pure. */
object TransmogLists {
    private fun matches(name: String, search: String): Boolean {
        val q = search.trim()
        return q.isEmpty() || name.contains(q, ignoreCase = true)
    }

    /** The selected slot's appearances, by name, the search and the filter's expansions applied. */
    fun items(catalog: TransmogCatalog, slot: ClothingSlot, search: String, expansions: Set<Int>): List<ClothingPiece> =
        catalog.pieces.filter { it.slot == slot && matches(it.name, search) && (expansions.isEmpty() || it.expansion in expansions) }

    /** The game's item sets (ItemSet: positive ids). */
    fun sets(catalog: TransmogCatalog, search: String, expansions: Set<Int>): List<ClothingSet> =
        catalog.outfits.filter { it.id > 0 && matches(it.name, search) && (expansions.isEmpty() || it.expansion in expansions) }

    /** The named characters' outfits (assets/characters.json's presets: negative ids), shown as Custom Sets. */
    fun customSets(catalog: TransmogCatalog): List<ClothingSet> = catalog.outfits.filter { it.id < 0 }

    /** The expansions the catalog's pieces come from, in order (the filter's lines). */
    fun expansions(catalog: TransmogCatalog): List<Int> = catalog.pieces.map { it.expansion }.distinct().sorted()

    /** The page that shows [display] in [list] (where the Items tab opens for a slot: what she wears), else the first. */
    fun pageOf(list: List<ClothingPiece>, display: Int?, perPage: Int): Int {
        if (display == null) return 0
        val i = list.indexOfFirst { it.display == display }
        return if (i < 0) 0 else i / perPage
    }

    /** "Page 2/9" (PAGE_NUMBER_WITH_MAX). */
    fun pageText(page: Int, pages: Int) = "Page ${page + 1}/$pages"
}
