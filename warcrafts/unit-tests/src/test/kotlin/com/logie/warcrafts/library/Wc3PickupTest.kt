package com.logie.warcrafts.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Wc3PickupTest {
    /** Laid out as the game's Blizzard.j is: comments, one globals block, functions, InitBlizzard, more functions; CRLF. */
    private val blizzard = listOf(
        "//===========================================================================",
        "// Blizzard.j",
        "globals",
        "    integer            bj_MAX_PLAYERS              = 12",
        "endglobals",
        "",
        "function ConfigureNeutralVictim takes nothing returns nothing",
        "endfunction",
        "",
        "function InitBlizzard takes nothing returns nothing",
        "    call ConfigureNeutralVictim()",
        "endfunction",
        "",
        "function RandomDistReset takes nothing returns nothing",
        "endfunction",
        "",
    ).joinToString("\r\n")

    private val source = Wc3Pickup.source("War3Patch.mpq", 1234L, 5678L)

    @Test
    fun `the addition goes into the globals, before InitBlizzard and at its end, the rest as it was`() {
        val patched = assertNotNull(Wc3Pickup.patch(blizzard, source))
        val lines = patched.split("\r\n")
        assertEquals(Wc3Pickup.header(source), lines[0])
        assertTrue(Wc3Pickup.isOurs(lines[0]))
        val globalsEnd = lines.indexOf("endglobals")
        val init = lines.indexOf("function InitBlizzard takes nothing returns nothing")
        assertTrue(lines.indexOf("    integer            logiePickupLeftCount        = 0") in 0 until globalsEnd)
        assertTrue(lines.indexOf("function LogiePickupInit takes nothing returns nothing") in globalsEnd until init)
        // The call is InitBlizzard's last line, after what it did already.
        assertEquals(listOf("    call ConfigureNeutralVictim()", "    call LogiePickupInit()", "endfunction"), lines.subList(init + 1, init + 4))
        // Taking the addition out again gives the game's own, byte for byte.
        val restored = patched.substringAfter("\r\n")
            .replace(Wc3Pickup.GLOBALS.trimEnd('\n').replace("\n", "\r\n") + "\r\n", "")
            .replace(Wc3Pickup.FUNCTIONS.trimEnd('\n').replace("\n", "\r\n") + "\r\n\r\n", "")
            .replace(Wc3Pickup.CALL + "\r\n", "")
        assertEquals(blizzard, restored)
    }

    @Test
    fun `the file keeps its own line endings`() {
        val crlf = assertNotNull(Wc3Pickup.patch(blizzard, source))
        assertFalse(Regex("[^\r]\n").containsMatchIn(crlf))
        val lf = assertNotNull(Wc3Pickup.patch(blizzard.replace("\r\n", "\n"), source))
        assertFalse(lf.contains('\r'))
    }

    @Test
    fun `nothing is made from a file laid out otherwise or patched already`() {
        assertNull(Wc3Pickup.patch(blizzard.replace("endglobals", "end"), source))
        assertNull(Wc3Pickup.patch(blizzard.replace("function InitBlizzard", "function InitOther"), source))
        assertNull(Wc3Pickup.patch(assertNotNull(Wc3Pickup.patch(blizzard, source)), source))
    }

    @Test
    fun `the app's file is known by its first line, whatever its version and source`() {
        assertTrue(Wc3Pickup.isOurs(Wc3Pickup.header("War3Patch.mpq 1 2") + "\r"))
        assertFalse(Wc3Pickup.isOurs("//==========================================================================="))
        assertFalse(Wc3Pickup.isOurs(""))
    }

    @Test
    fun `each of the addition's functions is defined before it is used (JASS has no forward references)`() {
        val defined = mutableSetOf<String>()
        for (line in Wc3Pickup.FUNCTIONS.lines()) {
            Regex("^function (\\w+) takes").find(line)?.let { defined += it.groupValues[1] }
            for (use in Regex("(?:call |function |= )(Logie\\w+)").findAll(line)) {
                if (line.startsWith("function ${use.groupValues[1]} ")) continue
                assertTrue(use.groupValues[1] in defined, "${use.groupValues[1]} used before it is defined: $line")
            }
        }
        assertTrue("LogiePickupInit" in defined)
        // And every global it uses is declared in its globals.
        for (name in Regex("\\b(logiePickup\\w+|LOGIE_\\w+)").findAll(Wc3Pickup.FUNCTIONS).map { it.value }.toSet()) {
            assertTrue(Regex("\\b$name\\b").containsMatchIn(Wc3Pickup.GLOBALS), "$name isn't declared")
        }
    }
}
