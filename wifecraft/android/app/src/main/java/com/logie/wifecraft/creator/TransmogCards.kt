package com.logie.wifecraft.creator

/**
 * How a Transmog card frames her (Native.thumbCard, Scene::ThumbFrame): the band of her body from [lo] to [hi] (fractions of
 * her drawn height, feet 0, crown 1) fills the card's height, seen from azimuth [az] (radians about the vertical; 0 is
 * straight in front, the cards' -0.4 a little from her right) and elevation [el]; [fitWidth] also keeps the band's whole width
 * in (her arms, a cape's spread). As the game's wardrobe frames its item models per slot (head and shoulders close, chest and
 * arms at the torso, legs and feet low; sets whole).
 */
data class CardShot(val lo: Float, val hi: Float, val az: Float, val el: Float, val fitWidth: Boolean)

/**
 * One picture to draw: the character [look] (her race, sex and choices wearing only the card's piece, or the set's pieces) framed
 * by [shot] at [width] x [height] pixels, named [key] (the memory and disk cache's name).
 */
data class CardOrder(val key: String, val look: Appearance, val shot: CardShot, val width: Int, val height: Int)

/**
 * The Transmog window's card pictures (TransmogPanel's Items and Sets grids): the character wearing each appearance, as the
 * game's wardrobe shows them (TransmogItemModelTemplate, TransmogSetBaseModelTemplate: the player's model undressed, then the
 * piece tried on). What each card draws, how it is framed, what it is called in the caches and how the disk cache is kept
 * small. Pure JVM (unit-tested: TransmogCardsTest); the drawing is engine/TransmogPictures.kt.
 */
object TransmogCards {
    /** An item card's picture in pixels (the card is 100 x 132 window units: TransmogFrame.ITEMS). */
    const val ITEM_W = 120
    const val ITEM_H = 158
    /** A set card's picture (178 x 218 units: TransmogFrame.SETS). */
    const val SET_W = 168
    const val SET_H = 206

    private const val FRONT = -0.40f          // a little from her right, as the wardrobe's models stand
    private const val BEHIND = (Math.PI + 0.40).toFloat()

    /** Each slot's framing (the band is of the body alone: a tall helm or a high pauldron may reach past the top). */
    fun shot(slot: ClothingSlot): CardShot = when (slot) {
        ClothingSlot.HEAD -> CardShot(0.80f, 1.03f, FRONT, 0.06f, false)
        ClothingSlot.SHOULDERS -> CardShot(0.68f, 1.01f, FRONT, 0.08f, false)
        ClothingSlot.BACK -> CardShot(0.30f, 0.98f, BEHIND, 0.10f, true)
        ClothingSlot.TORSO, ClothingSlot.SHIRT, ClothingSlot.TABARD -> CardShot(0.46f, 0.90f, FRONT, 0.06f, false)
        ClothingSlot.WRISTS, ClothingSlot.GLOVES -> CardShot(0.40f, 0.78f, FRONT, 0.08f, true)
        ClothingSlot.WAIST -> CardShot(0.48f, 0.72f, FRONT, 0.06f, false)
        ClothingSlot.LEGS -> CardShot(0.00f, 0.60f, FRONT, 0.10f, false)
        ClothingSlot.FEET -> CardShot(-0.02f, 0.30f, FRONT, 0.22f, false)
    }

    /** A set: all of her. */
    val SET_SHOT = CardShot(-0.02f, 1.04f, FRONT, 0.08f, true)

    /**
     * What a card's picture depends on of the character: race, sex, every choice, the body options and the undead skin (not her
     * clothes: each card puts on its own). Stable from run to run (the disk cache's names), eight hex digits.
     */
    fun lookHash(a: Appearance): String {
        val text = buildString {
            append(a.raceId).append('/').append(a.sex.ordinal).append('/')
            for ((k, v) in a.picks.toSortedMap()) append(k).append('=').append(v).append(',')
            append('/').append(a.body.shape).append(',').append(a.body.style).append(',').append(a.body.density)
            append('/').append(if (a.undead) a.undeadSkin else -1)
        }
        var h = 0x811C9DC5.toInt()                  // FNV-1a: the same on every run and every phone
        for (c in text) { h = h xor c.code; h *= 0x01000193 }
        return "%08x".format(h)
    }

    /** Her, wearing only [display] in [slot] (the wardrobe's undressed model with the piece tried on). */
    fun itemLook(a: Appearance, slot: ClothingSlot, display: Int): Appearance =
        a.copy(clothing = mapOf(slot.id to display), gear = true, starterClothing = false)

    /** Her in the set's pieces (those of the window's slots), nothing else. */
    fun setLook(a: Appearance, set: ClothingSet): Appearance =
        a.copy(clothing = set.displays.filterKeys { k -> TransmogFrame.SLOTS.any { it.id == k } }, gear = true, starterClothing = false)

    fun itemKey(a: Appearance, slot: ClothingSlot, display: Int): String =
        "tm_i_${a.raceId}_${a.sex.ordinal}_${slot.id}_${display}_${lookHash(a)}_${ITEM_W}x$ITEM_H"

    /** A set's name in the caches: its pieces, not its id (two sets with the same pieces share a picture). */
    fun setKey(a: Appearance, set: ClothingSet): String {
        var h = 0x811C9DC5.toInt()
        for ((k, v) in setLook(a, set).clothing.toSortedMap()) { h = (h xor k) * 0x01000193; h = (h xor v) * 0x01000193 }
        return "tm_s_${a.raceId}_${a.sex.ordinal}_${"%08x".format(h)}_${lookHash(a)}_${SET_W}x$SET_H"
    }

    fun itemOrder(a: Appearance, slot: ClothingSlot, display: Int) =
        CardOrder(itemKey(a, slot, display), itemLook(a, slot, display), shot(slot), ITEM_W, ITEM_H)

    fun setOrder(a: Appearance, set: ClothingSet) = CardOrder(setKey(a, set), setLook(a, set), SET_SHOT, SET_W, SET_H)

    /** A file of the disk cache: its name, bytes and when it was last used (ms). */
    data class Cached(val name: String, val bytes: Long, val used: Long)

    /** The disk cache's limits: a page of cards is about 0.6 MB of PNGs, so this keeps some hundred pages. */
    const val DISK_BYTES = 48L * 1024 * 1024
    const val DISK_FILES = 4000

    /** What to delete so that the cache is within [maxBytes] and [maxFiles]: the least recently used first. */
    fun prune(files: List<Cached>, maxBytes: Long = DISK_BYTES, maxFiles: Int = DISK_FILES): List<String> {
        var bytes = files.sumOf { it.bytes }
        var count = files.size
        val out = ArrayList<String>()
        for (f in files.sortedWith(compareBy({ it.used }, { it.name }))) {
            if (bytes <= maxBytes && count <= maxFiles) break
            out += f.name
            bytes -= f.bytes
            count--
        }
        return out
    }
}

/**
 * The cards' drawing queue (engine/TransmogPictures runs it on the main thread): only the page on show is drawn, in the grid's
 * order (top left first), at most [parallel] at once; a new page (another page, slot, tab, search or filter) drops what the
 * last one still had waiting, and a picture already made, being made or that failed is not asked for again. A picture being
 * made when its page goes finishes (it is kept for when the page comes back). Not thread-safe: one thread. Pure.
 */
class CardQueue<T>(private val parallel: Int = 1, private val keyOf: (T) -> String) {
    private val waiting = ArrayDeque<T>()
    private val running = LinkedHashSet<String>()

    /** The keys being made now. */
    val busy: Set<String> get() = running
    /** The keys waiting, in the order they will be made. */
    val queued: List<String> get() = waiting.map(keyOf)

    /**
     * The page on show is [page] (grid order); [have] says whether a key needs no drawing (in memory or on disk, or failed).
     * Returns the keys that were waiting and are no longer (dropped).
     */
    fun show(page: List<T>, have: (String) -> Boolean): List<String> {
        val before = waiting.map(keyOf)
        waiting.clear()
        val seen = HashSet<String>()
        for (o in page) {
            val k = keyOf(o)
            if (!seen.add(k) || k in running || have(k)) continue
            waiting.addLast(o)
        }
        val now = waiting.map(keyOf).toSet()
        return before.filter { it !in now }
    }

    /** The next to make (it counts as running until [finished]), or null: nothing waits or [parallel] are being made. */
    fun next(): T? {
        if (running.size >= parallel || waiting.isEmpty()) return null
        val o = waiting.removeFirst()
        running += keyOf(o)
        return o
    }

    fun finished(key: String) { running -= key }

    /** Nothing waits any more (the window closed); those being made finish. */
    fun clear() = waiting.clear()
}
