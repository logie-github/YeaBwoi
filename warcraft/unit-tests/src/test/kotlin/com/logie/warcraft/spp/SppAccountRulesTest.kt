// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp

import com.logie.warcraft.spp.core.SppAccountRules
import java.util.Locale
import kotlin.test.*

class SppAccountRulesTest {
    @Test fun bnetHashUsesTheAshamaneAccountMgrFormat() {
        assertEquals("6C0A8C31680259D32E35EF1BE9C368A38E926D7AA984AEB22F300F64502B79CC", SppAccountRules.hash("single@single", "single"))
        assertEquals(SppAccountRules.hash("SINGLE@SINGLE", "SINGLE"), SppAccountRules.hash("single@single", "single"))
    }
    @Test fun accountSqlLinksTheBnetAndGameAccountsWithoutInterpolatingCredentials() {
        val password = "q'\\x;--"
        val sql = SppAccountRules.createSql("single@single", password)
        assertFalse(sql.contains(password))
        assertTrue(sql.contains("INSERT INTO battlenet_accounts"))
        assertTrue(sql.contains("battlenet_account,battlenet_index"))
        assertTrue(sql.contains("CONCAT(@spp_bnet,'#1')"))
        assertTrue(sql.contains("INSERT INTO realmcharacters"))
        assertFalse(sql.contains("account_access"))
        assertTrue(SppAccountRules.createSql("single@single", "test", true).contains("@spp_game,3,-1"))
    }
    @Test fun rejectsUnsupportedNamesPasswordsAndLocaleDependentCaseFolding() {
        assertNull(SppAccountRules.normalizeEmail("x' OR 1=1@invalid"))
        assertFalse(SppAccountRules.validPassword("line\nbreak"))
        assertFalse(SppAccountRules.validPassword("x".repeat(17)))
        val previous = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals("SINGLE@SINGLE", SppAccountRules.normalizeEmail("single@single")) }
        finally { Locale.setDefault(previous) }
    }
}
