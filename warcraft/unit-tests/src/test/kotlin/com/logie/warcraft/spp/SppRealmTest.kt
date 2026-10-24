package com.logie.warcraft.spp

import com.logie.warcraft.config.ConfFile
import com.logie.warcraft.spp.core.SppAccountRules
import com.logie.warcraft.spp.core.SppRealm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SppRealmTest {
    @Test
    fun alliedRacesWithoutAnAchievementFirst() {
        val sql = SppRealm.WORLD_STARTUP_SQL
        assertTrue(sql.startsWith("INSERT IGNORE INTO race_unlock_requirement (raceID, expansion, achievementId) VALUES (27,7,0),(28,7,0),(29,7,0),(30,7,0);\n"))
        assertTrue(sql.contains("UPDATE race_unlock_requirement SET achievementId = 0 WHERE raceID IN (27,28,29,30) AND achievementId <> 0;"))
    }

    @Test
    fun everyAccountHasTheAlliedRacesExpansion() {
        assertEquals(7, SppAccountRules.ACCOUNT_EXPANSION)
        assertEquals("UPDATE account SET expansion = 7 WHERE expansion < 7;\n", SppRealm.LOGIN_STARTUP_SQL)
        assertTrue(SppAccountRules.createSql("player@spp.local", "Secret123").contains(",7,@spp_bnet,1);"))
    }

    @Test
    fun demonHuntersWithoutALevel70Character() {
        // As the repack's world.conf has them, set as the app sets them at every start.
        val conf = ConfFile("CharacterCreating.MinLevelForDemonHunter = 70\r\nDemonHuntersPerRealm = 1\r\nCharactersPerRealm = 16\r\n")
        for ((key, value) in SppRealm.WORLD_SETTINGS) conf[key] = value
        val out = ConfFile(conf.toString())
        assertEquals("0", out["CharacterCreating.MinLevelForDemonHunter"]?.trim())
        assertEquals("16", out["DemonHuntersPerRealm"]?.trim())
        assertEquals("16", out["CharactersPerRealm"]?.trim())
        for (key in listOf("CharacterCreating.Disabled", "CharacterCreating.Disabled.RaceMask", "CharacterCreating.Disabled.ClassMask", "Force.Starting.Level", "Force.Teleport"))
            assertEquals("0", out[key]?.trim(), key)
    }
}
