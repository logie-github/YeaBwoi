package com.logie.warcrafts.library

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Wc3ScreenTest {
    /** A picture that is exactly a console's reference blocks, and [other] everywhere else. */
    private fun console(index: Int, other: Int = 0x204020) = Wc3Screen.Sampler { x, y ->
        Wc3ScreenData.CONSOLES[index].second.firstOrNull { it.first == x && it.second == y }?.third ?: other
    }

    @Test
    fun eachConsoleIsFoundWithItsRace() {
        for ((i, entry) in Wc3ScreenData.CONSOLES.withIndex()) assertEquals(entry.first, Wc3Screen.race(console(i)))
    }

    @Test
    fun plainScreensAreNoConsole() {
        for (grey in 0..255 step 5) {
            val colour = (grey shl 16) or (grey shl 8) or grey
            assertNull(Wc3Screen.race { _, _ -> colour }, "grey $grey")
        }
        assertNull(Wc3Screen.race { _, _ -> null })
    }

    @Test
    fun theConsoleAndTheBattlefield() {
        assertTrue(Wc3Screen.onConsole("Human", 0.5f, 0.02f))   // the top bar
        assertTrue(Wc3Screen.onConsole("Human", 0.1f, 0.9f))    // the minimap
        assertTrue(Wc3Screen.onConsole("Orc", 0.9f, 0.95f))     // the command buttons
        assertFalse(Wc3Screen.onConsole("Human", 0.5f, 0.5f))   // the battlefield
        assertFalse(Wc3Screen.onConsole("Undead", 0.1f, 0.6f))
    }

    @Test
    fun heroButtonsOrdersAndMinimap() {
        assertTrue(Wc3Screen.onHeroButtons(0.02f, 0.15f))      // a hero's portrait
        assertTrue(Wc3Screen.onHeroButtons(0.02f, 0.75f))      // the idle worker
        assertFalse(Wc3Screen.onHeroButtons(0.02f, 0.02f))     // the top bar's menu button
        assertFalse(Wc3Screen.onHeroButtons(0.3f, 0.3f))
        assertTrue(Wc3Screen.onOrders(0.8f, 0.85f))            // a command button
        assertTrue(Wc3Screen.onOrders(0.7f, 0.9f))             // an item
        assertFalse(Wc3Screen.onOrders(0.1f, 0.9f))            // the minimap
        assertTrue(Wc3Screen.onMinimap(0.1f, 0.9f))
        assertFalse(Wc3Screen.onMinimap(0.5f, 0.9f))
    }

    @Test
    fun aStillBattlefieldIsAPausedGame() {
        val field = List(Wc3Screen.FIELD.size) { 0x406030 + it }
        assertTrue(Wc3Screen.still(field, field))
        assertFalse(Wc3Screen.still(field, field.mapIndexed { i, c -> if (i == 3) c + 0x101010 else c }))
        assertFalse(Wc3Screen.still(List(field.size) { 0x050505 }, List(field.size) { 0x050505 }))   // all black
        assertFalse(Wc3Screen.still(null, field))
    }

    /**
     * Real pictures, when given: W3_SCREENS=<folder> with console_*.png (the console drawn over the
     * battlefield: a match) and menu*.png (the game's menus), at any size.
     */
    @Test
    fun realPictures() {
        val dir = System.getenv("W3_SCREENS")?.let(::File) ?: return
        fun sampler(file: File): Wc3Screen.Sampler {
            val image = ImageIO.read(file)
            return Wc3Screen.Sampler { x, y ->
                val r = maxOf(1, (image.width * 11f / 1440f / 2).toInt())
                val cx = (x * image.width).toInt().coerceIn(r, image.width - r - 1)
                val cy = (y * image.height).toInt().coerceIn(r, image.height - r - 1)
                var red = 0; var green = 0; var blue = 0; var n = 0
                for (py in cy - r..cy + r) for (px in cx - r..cx + r) {
                    val c = image.getRGB(px, py)
                    red += (c shr 16) and 0xff; green += (c shr 8) and 0xff; blue += c and 0xff; n++
                }
                ((red / n) shl 16) or ((green / n) shl 8) or (blue / n)
            }
        }
        val files = dir.listFiles().orEmpty().sortedBy { it.name }
        val matches = files.filter { it.name.startsWith("console_") }
        val menus = files.filter { it.name.startsWith("menu") }
        assertTrue(matches.isNotEmpty() && menus.isNotEmpty())
        for (file in matches) {
            val race = file.nameWithoutExtension.substringAfterLast('_')
            assertEquals(race, Wc3Screen.race(sampler(file)), file.name)
        }
        for (file in menus) assertNull(Wc3Screen.race(sampler(file)), file.name)
    }
}
