package com.logie.warcrafts.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Wc3KeysTest {
    @Test
    fun keysAreTakenWithDashesSpacesAndLowerCase() {
        assertEquals("246789BCDEFGHJKMNPRTVWXYZ2", Wc3Keys.normalize("2467-89bc-defg hjkm-nprt-vwxy-z2"))
    }

    @Test
    fun notAKey() {
        assertNull(Wc3Keys.normalize("246789BCDEFGHJKMNPRTVWXYZ"))       // 25
        assertNull(Wc3Keys.normalize("246789BCDEFGHJKMNPRTVWXYZ22"))     // 27
        assertNull(Wc3Keys.normalize("A46789BCDEFGHJKMNPRTVWXYZ2"))      // A is never in a key
        assertNull(Wc3Keys.normalize("046789BCDEFGHJKMNPRTVWXYZ2"))
    }

    @Test
    fun whatTheInstallerWrites() {
        val writes = Wc3Keys.writes("R".repeat(26), "T".repeat(26))
        assertEquals(
            listOf("War3.mpq" to "font\\font.gid", "War3.mpq" to "font\\font.clh", "War3.mpq" to "font\\font.ccd", "War3x.mpq" to "font\\font.exp", "War3x.mpq" to "font\\font.ccd"),
            writes.map { it.archive to it.name },
        )
        assertEquals("R".repeat(26), writes[0].text)
        assertEquals("T".repeat(26), writes[3].text)
        assertEquals("sgubon", writes[2].text)
    }
}
