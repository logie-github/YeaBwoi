package com.logie.warcraft.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealmCacheTest {
    @Test
    fun sameRealmKeepsTheCache() {
        assertFalse(RealmCache.mustClear("main", "main"))
        assertFalse(RealmCache.mustClear("remix", "remix"))
        assertFalse(RealmCache.mustClear("remix\n", "remix"))
    }

    @Test
    fun anotherRealmClearsIt() {
        assertTrue(RealmCache.mustClear("main", "remix"))
        assertTrue(RealmCache.mustClear("remix", "main"))
    }

    @Test
    fun noRecordMeansChromieCraft() {
        // A phone from before the realms: its cache is ChromieCraft's.
        assertFalse(RealmCache.mustClear(null, "main"))
        assertFalse(RealmCache.mustClear("", "main"))
        assertTrue(RealmCache.mustClear(null, "remix"))
    }
}
