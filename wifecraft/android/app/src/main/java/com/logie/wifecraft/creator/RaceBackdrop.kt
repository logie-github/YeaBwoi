package com.logie.wifecraft.creator

import org.json.JSONArray
import org.json.JSONObject

/** Original sets with palettes based on the races' home regions. The preview camera faces the back wall. */
object RaceBackdrop {
    // Small scenery models from the app's wago.tools catalog, with original stage layouts.
    private val scenery = mapOf(
        1 to 198067, 2 to 922102, 3 to 197295, 4 to 1096738, 5 to 2341263, 6 to 1313217,
        7 to 2316620, 8 to 1967911, 9 to 307162, 10 to 6017294, 11 to 937235,
        22 to 198316, 24 to 528526, 25 to 528526, 26 to 528526, 27 to 4238231, 28 to 1253406,
        29 to 7361277, 30 to 942495, 31 to 1592979, 32 to 362638, 34 to 197295, 35 to 5657267,
        36 to 850526, 37 to 1841735, 52 to 4906427, 70 to 4906427, 80 to 1096746,
        84 to 4906428, 85 to 4906428,
    )

    val sceneryFiles: Set<Int> get() = scenery.values.toSet()
    fun sceneryModel(raceId: Int): Int? = scenery[raceId]

    fun json(raceId: Int): String {
        val colors = when (raceId) {
            1, 22, 32 -> arrayOf(0x433423, 0x647585, 0x9b7543) // Human kingdoms
            2, 36 -> arrayOf(0x64402d, 0x85452b, 0x33262a) // Durotar
            3, 34, 84, 85 -> arrayOf(0x5b5850, 0x7d746a, 0xb16e39) // Mountain halls
            4, 80 -> arrayOf(0x443b52, 0x4d587f, 0x927ca4) // Kaldorei woodland
            5 -> arrayOf(0x3b3c38, 0x51664c, 0x665f49) // Tirisfal
            6, 28 -> arrayOf(0x664934, 0xa88651, 0x60453c) // Mulgore and Highmountain
            7, 37 -> arrayOf(0x57575c, 0x7d6d50, 0x98633e) // Gnomish workshops
            8, 31 -> arrayOf(0x4d563b, 0x637044, 0x8b5b35) // Troll homelands
            9, 35 -> arrayOf(0x63583c, 0x797c49, 0x5c4930) // Kezan and Vol'dun
            10 -> arrayOf(0x574132, 0x86554c, 0xc48b57) // Silvermoon
            11, 30 -> arrayOf(0x434460, 0x6b6793, 0xb59169) // Draenei sanctuaries
            24, 25, 26 -> arrayOf(0x3d4934, 0x738467, 0xae6447) // Pandaria
            27, 29 -> arrayOf(0x393550, 0x534b76, 0x896cb0) // Nightborne and Void elf
            52, 70 -> arrayOf(0x5d4c41, 0x4a6a65, 0x9e855a) // Dragon Isles
            else -> arrayOf(0x414b48, 0x657570, 0x8b7760)
        }
        fun rgb(value: Int) = JSONArray(listOf((value shr 16 and 255) / 255.0, (value shr 8 and 255) / 255.0, (value and 255) / 255.0))
        return JSONObject().put("id", "creator-$raceId")
            .put("room", JSONObject().put("size", JSONArray(listOf(12, 9, 4)))
                .put("floorColor", rgb(colors[0])).put("wallColor", rgb(colors[1])).put("trimColor", rgb(colors[2])))
            .put("ground", false).put("fog", JSONObject().put("color", rgb(colors[1])).put("start", 12).put("end", 32))
            .toString()
    }
}
