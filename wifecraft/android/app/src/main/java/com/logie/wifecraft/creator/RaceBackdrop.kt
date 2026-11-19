package com.logie.wifecraft.creator

/** FileDataIDs of the original Interface/Glues/Models race-creation M2s in the WoW community listfile. */
object RaceBackdrop {
    private val models = mapOf(
        1 to 131948, 2 to 132003, 3 to 131940, 4 to 131993, 5 to 132035,
        6 to 132046, 7 to 430171, 8 to 429097, 9 to 319097, 10 to 131921,
        11 to 131934, 22 to 313254, 24 to 574549, 25 to 574549, 26 to 574549,
        27 to 1662240, 28 to 1662239, 29 to 1662242, 30 to 1662241,
        31 to 2743642, 32 to 2575493, 34 to 2005930, 35 to 3188841,
        36 to 2005932, 37 to 3188839, 52 to 4620425, 70 to 4620425,
        84 to 5797525, 85 to 5797525,
    )

    fun file(tables: Tables, raceId: Int): Int? =
        models[raceId] ?: tables.rowBy("ChrRaces", "ID", raceId)
            ?.int("CreateScreenFileDataID")?.takeIf { it > 0 }
}
