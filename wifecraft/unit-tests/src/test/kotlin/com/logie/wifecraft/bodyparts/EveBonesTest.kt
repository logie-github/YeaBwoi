package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Keep undead bones (bodyparts/EveOptions.kt EveOptions.bones, EveBones): on by default for a Forsaken woman and shown for
 * her alone, kept through Appearance's save, an older save keeping them; the fit table's row that carries them
 * (assets/eve_fit.json "detail", read by engine/evebody.cpp).
 */
class EveBonesTest {
    private val undead = Appearance(EveBones.UNDEAD, Sex.FEMALE, gear = false, eve = EveOptions(on = true))

    @Test
    fun onByDefaultForAnUndeadWomanAndShownForHerAlone() {
        assertTrue(EveOptions().bones, "a new look keeps them")
        assertEquals(true, EveBones.shown(undead, undead.eve))
        assertEquals(true, EveBones.shown(undead, null), "no Eve options yet: on")
        assertEquals(false, EveBones.shown(undead, EveOptions(on = true, bones = false)))
        assertNull(EveBones.shown(Appearance(EveBones.UNDEAD, Sex.MALE), EveOptions(on = true)), "a man: not shown")
        for (race in listOf(1, 2, 3, 4, 10, 11)) assertNull(EveBones.shown(Appearance(race, Sex.FEMALE), EveOptions(on = true)), "race $race: not shown")
    }

    @Test
    fun keptThroughTheSaveAndOlderSavesKeepThem() {
        val off = undead.copy(eve = EveOptions(on = true, bones = false))
        val back = Appearance.fromJson(JSONObject(off.toJson().toString()))
        assertEquals(false, back.eve?.bones, "turned off: saved off")
        assertEquals(off.eve, back.eve)
        // on is the default and written as before (no key): a look saved before the switch reads back the same, bones on
        val on = undead.toJson().getJSONObject("eve")
        assertFalse(on.has("bones"), "on: nothing new in the save")
        val older = JSONObject("""{"on":true,"bottom":"plain","tone":-1,"overlaySlot":-1,"overlayShade":-1,"opacity":0.6}""")
        assertTrue(EveOptions.fromJson(older).bones, "an older save keeps them")
        assertEquals(EveOptions(on = true), EveOptions.fromJson(older))
    }

    @Test
    fun theFitTableCarriesThemForTheUndeadRowOnly() {
        val races = JSONObject(File("../android/app/src/main/assets/eve_fit.json").readText()).getJSONArray("races")
        for (i in 0 until races.length()) {
            val r = races.getJSONObject(i)
            val detail = r.optJSONObject("fit")?.optDouble("detail", 0.0) ?: 0.0
            if (r.getInt("race") == EveBones.UNDEAD) assertEquals(2.0, detail, "the Forsaken row: painted details and the bone pieces kept")
            else assertEquals(0.0, detail, "${r.optString("name")}: no details")
        }
    }
}
