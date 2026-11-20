package com.logie.wifecraft.creator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransmogCardsTest {
    private val her = Appearance(1, Sex.FEMALE, picks = mapOf(10 to 100, 11 to 110), clothing = mapOf(4 to 9, 7 to 8))

    @Test fun everySlotIsFramedOnItsPartOfTheBody() {
        for (slot in ClothingSlot.entries) {
            val s = TransmogCards.shot(slot)
            assertTrue(s.lo < s.hi && s.hi - s.lo in 0.2f..1.1f, "$slot: $s")
        }
        val head = TransmogCards.shot(ClothingSlot.HEAD)
        val feet = TransmogCards.shot(ClothingSlot.FEET)
        val legs = TransmogCards.shot(ClothingSlot.LEGS)
        val chest = TransmogCards.shot(ClothingSlot.TORSO)
        // head close at the top, feet low and looked down on, legs below the chest, the back seen from behind
        assertTrue(head.lo > 0.7f && head.hi >= 1f)
        assertTrue(feet.lo <= 0f && feet.hi < 0.4f && feet.el > head.el)
        assertTrue(legs.hi < chest.hi && legs.lo < chest.lo)
        assertTrue(kotlin.math.cos(TransmogCards.shot(ClothingSlot.BACK).az) < 0f)
        assertTrue(kotlin.math.cos(chest.az) > 0f)
        // a set: all of her
        assertTrue(TransmogCards.SET_SHOT.lo <= 0f && TransmogCards.SET_SHOT.hi >= 1f && TransmogCards.SET_SHOT.fitWidth)
    }

    @Test fun aCardWearsOnlyItsPieceAndASetOnlyItsWindowSlots() {
        val item = TransmogCards.itemLook(her, ClothingSlot.HEAD, 77)
        assertEquals(mapOf(1 to 77), item.clothing)
        assertTrue(item.gear && !item.starterClothing)
        assertEquals(her.picks, item.picks)
        val set = ClothingSet(5, "Set", mapOf(5 to 50, 7 to 70, 16 to 160))   // 16: a weapon slot the window doesn't have
        assertEquals(mapOf(5 to 50, 7 to 70), TransmogCards.setLook(her, set).clothing)
    }

    @Test fun keysFollowHerLookNotHerClothes() {
        val a = TransmogCards.itemKey(her, ClothingSlot.HEAD, 77)
        assertEquals(a, TransmogCards.itemKey(her.copy(clothing = emptyMap(), gear = false), ClothingSlot.HEAD, 77))
        assertNotEquals(a, TransmogCards.itemKey(her, ClothingSlot.HEAD, 78))
        assertNotEquals(a, TransmogCards.itemKey(her.copy(picks = her.picks + (10 to 101)), ClothingSlot.HEAD, 77))
        assertNotEquals(a, TransmogCards.itemKey(her.copy(sex = Sex.MALE), ClothingSlot.HEAD, 77))
        assertNotEquals(a, TransmogCards.itemKey(her.copy(raceId = 2), ClothingSlot.HEAD, 77))
        assertNotEquals(a, TransmogCards.itemKey(her.copy(body = Body(1, 0, 0)), ClothingSlot.HEAD, 77))
        // the same on every run: no identity hash in it (a fixed value for a fixed look)
        assertEquals(TransmogCards.lookHash(Appearance(1, Sex.FEMALE)), TransmogCards.lookHash(Appearance(1, Sex.FEMALE)))
        assertTrue(Regex("[0-9a-f]{8}").matches(TransmogCards.lookHash(her)))
        assertTrue(Regex("[a-z0-9_x]+").matches(a), a)   // a file name
        // two sets with the same pieces share a picture; the set's own id doesn't matter
        val s1 = ClothingSet(1, "One", mapOf(5 to 50, 7 to 70))
        val s2 = ClothingSet(2, "Two", mapOf(7 to 70, 5 to 50))
        assertEquals(TransmogCards.setKey(her, s1), TransmogCards.setKey(her, s2))
        assertNotEquals(TransmogCards.setKey(her, s1), TransmogCards.setKey(her, ClothingSet(3, "Three", mapOf(5 to 51, 7 to 70))))
    }

    @Test fun ordersCarryTheSlotsFramingAndSize() {
        val o = TransmogCards.itemOrder(her, ClothingSlot.FEET, 3)
        assertEquals(TransmogCards.shot(ClothingSlot.FEET), o.shot)
        assertEquals(TransmogCards.ITEM_W to TransmogCards.ITEM_H, o.width to o.height)
        val s = TransmogCards.setOrder(her, ClothingSet(1, "One", mapOf(5 to 50)))
        assertEquals(TransmogCards.SET_SHOT, s.shot)
        // the pictures keep the cards' shapes (100 x 132 and 178 x 218 window units)
        assertEquals(100f / 132f, TransmogCards.ITEM_W.toFloat() / TransmogCards.ITEM_H, 0.01f)
        assertEquals(178f / 218f, TransmogCards.SET_W.toFloat() / TransmogCards.SET_H, 0.01f)
    }

    @Test fun theQueueDrawsOnlyThePageOnShowInGridOrder() {
        val q = CardQueue<String>(parallel = 1) { it }
        val have = setOf("c")
        assertEquals(emptyList(), q.show(listOf("a", "b", "c", "d", "a"), have::contains))
        assertEquals(listOf("a", "b", "d"), q.queued)     // grid order, what is cached and the repeat left out
        assertEquals("a", q.next())
        assertNull(q.next())                               // one at a time on the GL thread
        // the page turns while "a" is being made: what waited is dropped, "a" isn't asked for again
        assertEquals(listOf("b", "d"), q.show(listOf("e", "a", "f"), have::contains))
        assertEquals(listOf("e", "f"), q.queued)
        q.finished("a")
        assertEquals("e", q.next())
        q.finished("e")
        assertEquals("f", q.next())
        q.finished("f")
        assertNull(q.next())
        // the window closes: nothing waits
        q.show(listOf("x", "y"), have::contains)
        q.clear()
        assertNull(q.next())
    }

    @Test fun twoAtOnceWhenAsked() {
        val q = CardQueue<String>(parallel = 2) { it }
        q.show(listOf("a", "b", "c")) { false }
        assertEquals("a", q.next()); assertEquals("b", q.next()); assertNull(q.next())
        assertEquals(setOf("a", "b"), q.busy)
    }

    @Test fun theDiskCacheLetsTheLeastRecentlyUsedGo() {
        val files = listOf(
            TransmogCards.Cached("old", 40, 1), TransmogCards.Cached("mid", 40, 5), TransmogCards.Cached("new", 40, 9),
        )
        assertEquals(emptyList(), TransmogCards.prune(files, maxBytes = 120, maxFiles = 3))
        assertEquals(listOf("old"), TransmogCards.prune(files, maxBytes = 80, maxFiles = 3))
        assertEquals(listOf("old", "mid"), TransmogCards.prune(files, maxBytes = 1000, maxFiles = 1))
        assertEquals(listOf("old", "mid", "new"), TransmogCards.prune(files, maxBytes = 0, maxFiles = 10))
    }
}

class ClothingSlotInventoryTest {
    @Test fun cloaksAreTheBackAndBowsAreNothing() {
        assertEquals(ClothingSlot.BACK, ClothingSlot.fromInventory(16))     // INVTYPE_CLOAK
        assertNull(ClothingSlot.fromInventory(15))                          // INVTYPE_RANGED: a bow
        assertEquals(ClothingSlot.TORSO, ClothingSlot.fromInventory(20))    // a robe
        assertEquals(ClothingSlot.TORSO, ClothingSlot.fromInventory(5))
        assertEquals(ClothingSlot.TABARD, ClothingSlot.fromInventory(19))
        assertNull(ClothingSlot.fromInventory(13))                          // a one-handed weapon
    }
}
