package com.logie.wifecraft.creator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StockingsTest {
    @Test fun denierIsClampedAndMonotonicInOpacity() {
        assertEquals(8, StockingSpec.clampDenier(1)); assertEquals(80, StockingSpec.clampDenier(500))
        var last = 0f
        for (d in StockingSpec.MIN_DENIER..StockingSpec.MAX_DENIER) { val o = StockingSpec(denier = d).opacity; assertTrue(o >= last && o in 0f..1f); last = o }
        assertTrue(StockingSpec(denier = 8).opacity < 0.3f && StockingSpec(denier = 80).opacity > 0.9f)
    }

    @Test fun theEngineGetsSevenNumbers() {
        val f = StockingSpec(StockingStyle.TIGHTS, 40, StockingColours.RED, seam = true, lace = false).toFloats()
        assertEquals(7, f.size); assertEquals(3f, f[0]); assertEquals(40f, f[1]); assertEquals(1f, f[5]); assertEquals(0f, f[6])
        assertEquals(0xC7 / 255f, f[2], 1e-4f)
    }

    @Test fun wordsMakeASpec() {
        fun p(s: String) = StockingSpec.fromWords(s)
        assertEquals(StockingStyle.THIGH, p("put on stockings").style)
        assertEquals(StockingStyle.THIGH, p("thigh-high stockings").style)
        assertEquals(StockingStyle.KNEE, p("knee high socks").style)
        assertEquals(StockingStyle.TIGHTS, p("black pantyhose").style); assertEquals(StockingStyle.TIGHTS, p("wear tights").style)
        assertEquals(StockingStyle.ANKLE, p("ankle stockings").style)
        assertEquals(StockingColours.RED, p("red thigh high stockings").colour); assertEquals(StockingColours.NAVY, p("navy tights").colour)
        assertEquals(15, p("sheer 15 denier tights").denier); assertEquals(12, p("sheer stockings").denier); assertEquals(70, p("thick opaque tights").denier)
        assertTrue(p("seamed stockings").seam); assertFalse(p("plain elastic stockings").lace)
        // what the words leave out stays as it was
        val base = StockingSpec(StockingStyle.KNEE, 30, StockingColours.TAN)
        assertEquals(base, StockingSpec.fromWords("put them on", base))
    }

    @Test fun nudeFollowsTheSkinTone() {
        val dark = StockingColours.nudeFor(0f); val pale = StockingColours.nudeFor(1f)
        assertTrue(pale.r > dark.r && pale.g > dark.g && pale.b > dark.b)
        assertEquals(7, StockingColours.PRESETS.size)
    }

    @Test fun theBookKeepsEachNpcsOwnAndRoundTrips() {
        val book = StockingBook().with("a", StockingSpec(StockingStyle.KNEE, 12, StockingColours.WHITE, seam = true)).with("b", StockingSpec())
        assertEquals(book.of("a"), StockingBook.fromJson(book.toJson().toString()).of("a"))
        assertEquals(2, StockingBook.fromJson(book.toJson().toString()).worn.size)
        assertNull(book.with("a", null).of("a")); assertNull(StockingBook().of("x"))
        // junk and a bad entry are ignored; an out-of-range denier is clamped on reading
        assertEquals(0, StockingBook.fromJson("{broken").worn.size); assertEquals(0, StockingBook.fromJson(null).worn.size)
        val odd = """{"v":1,"worn":{"c":{"style":"THIGH","denier":999,"rgb":123},"d":{"style":"NOPE"}}}"""
        assertEquals(80, StockingBook.fromJson(odd).of("c")?.denier); assertNull(StockingBook.fromJson(odd).of("d"))
    }
}
