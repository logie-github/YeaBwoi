package com.logie.wifecraft.creator

import org.json.JSONObject

// Pure Kotlin (JVM-tested: StockingsTest): the stockings outfit's model. A style (ankle, knee-high, thigh-high, pantyhose), a denier (8 D very
// sheer to 80 D opaque), a colour (presets, the nude and tan ones matched to the skin tone, or any colour), the back seam, the lace top
// band; kept per NPC (StockingBook, saved with the character) and handed to the engine as seven numbers (engine/stockings.h StockSpec).
// The shell itself is made in C++ from the body's own leg triangles (engine/stockings.cpp), so it fits every body.

enum class StockingStyle(val label: String, val code: Int, val words: List<String>) {
    ANKLE("Ankle", 0, listOf("ankle")),
    KNEE("Knee-high", 1, listOf("knee high", "knee-high", "kneehigh", "knee")),
    THIGH("Thigh-high", 2, listOf("thigh high", "thigh-high", "thighhigh", "thigh", "stockings", "stocking")),
    TIGHTS("Pantyhose", 3, listOf("pantyhose", "tights", "hosiery", "nylons", "panty hose")),
    /** Thigh-highs held up by thin elastic straps from a waist belt (the belt and straps are made with the shell: engine/stockings.cpp). */
    GARTER("Garter", 4, listOf("garter", "suspender", "stockings with a belt"));

    companion object { fun ofCode(c: Int) = entries.firstOrNull { it.code == c } ?: THIGH }
}

/** A colour as 0xRRGGBB. */
data class StockingColour(val name: String, val rgb: Int) {
    val r: Float get() = ((rgb shr 16) and 0xFF) / 255f
    val g: Float get() = ((rgb shr 8) and 0xFF) / 255f
    val b: Float get() = (rgb and 0xFF) / 255f
}

object StockingColours {
    val INK_BLACK = StockingColour("Ink black", 0x0A0A0D)
    val NUDE = StockingColour("Nude", 0xDBA88C)
    val TAN = StockingColour("Tan", 0xB78059)
    val DEEP_TAN = StockingColour("Deep tan", 0x805438)
    val WHITE = StockingColour("White", 0xF2F2F7)
    val RED = StockingColour("Red", 0xC70F1A)
    val NAVY = StockingColour("Navy", 0x0D174D)
    val PRESETS = listOf(INK_BLACK, NUDE, TAN, DEEP_TAN, WHITE, RED, NAVY)

    /** The nude shade for a skin of [luma] (0 dark .. 1 pale): a little darker and warmer than the skin, so it reads as a sheer layer over it. */
    fun nudeFor(luma: Float): StockingColour {
        val l = luma.coerceIn(0f, 1f)
        fun ch(dark: Int, pale: Int) = (dark + (pale - dark) * l).toInt().coerceIn(0, 255)
        return StockingColour("Nude (skin)", (ch(0x55, 0xE0) shl 16) or (ch(0x37, 0xAA) shl 8) or ch(0x26, 0x90))
    }

    /** A colour named in [text] ("red", "navy", "black", "nude", "tan", "white"), else null. */
    fun ofWords(text: String): StockingColour? {
        val t = " ${text.lowercase()} "
        return when {
            t.contains(" black") || t.contains(" ink") -> INK_BLACK
            t.contains(" deep tan") -> DEEP_TAN
            t.contains(" tan") -> TAN
            t.contains(" nude") || t.contains(" skin") -> NUDE
            t.contains(" white") -> WHITE
            t.contains(" red") -> RED
            t.contains(" navy") || t.contains(" blue") -> NAVY
            else -> null
        }
    }
}

/** What an NPC wears on her legs. */
data class StockingSpec(
    val style: StockingStyle = StockingStyle.THIGH,
    val denier: Int = 20,
    val colour: StockingColour = StockingColours.INK_BLACK,
    val seam: Boolean = false,
    val lace: Boolean = true,
) {
    init { require(denier in MIN_DENIER..MAX_DENIER) { "denier $denier" } }

    /** The seven numbers the engine takes (Native.setStockings): style, denier, r, g, b, seam, lace. */
    fun toFloats(): FloatArray = floatArrayOf(style.code.toFloat(), denier.toFloat(), colour.r, colour.g, colour.b, if (seam) 1f else 0f, if (lace) 1f else 0f)

    /** How see-through it is, 0 (nothing) to 1 (opaque): the engine's denier curve (stockings.cpp denierAlpha). */
    val opacity: Float get() = (0.10f + 0.88f * kotlin.math.sqrt(((denier - 5f) / 75f).coerceAtLeast(0f))).coerceAtMost(0.98f)

    fun toJson(): JSONObject = JSONObject().put("style", style.name).put("denier", denier).put("rgb", colour.rgb).put("cname", colour.name).put("seam", seam).put("lace", lace)

    companion object {
        const val MIN_DENIER = 8
        const val MAX_DENIER = 80
        fun clampDenier(d: Int) = d.coerceIn(MIN_DENIER, MAX_DENIER)

        fun fromJson(o: JSONObject?): StockingSpec? {
            o ?: return null
            val style = StockingStyle.entries.firstOrNull { it.name == o.optString("style") } ?: return null
            return StockingSpec(style, clampDenier(o.optInt("denier", 20)), StockingColour(o.optString("cname", "Custom"), o.optInt("rgb", 0x0A0A0D) and 0xFFFFFF),
                o.optBoolean("seam", false), o.optBoolean("lace", true))
        }

        /** A spec read from what the player typed ("put on red thigh-high stockings", "sheer 15 denier tights"), starting from [base]. */
        fun fromWords(text: String, base: StockingSpec = StockingSpec()): StockingSpec {
            val t = " ${text.lowercase().replace(Regex("[^a-z0-9 -]"), " ")} "
            // the most specific style word wins: pantyhose and tights before "thigh", knee before the generic "stockings"
            val style = when {
                StockingStyle.TIGHTS.words.any { it in t } -> StockingStyle.TIGHTS
                StockingStyle.GARTER.words.any { it in t } -> StockingStyle.GARTER
                t.contains("ankle") -> StockingStyle.ANKLE
                t.contains("knee") -> StockingStyle.KNEE
                t.contains("thigh") || t.contains("stocking") -> StockingStyle.THIGH
                else -> base.style
            }
            val denier = Regex("(\\d{1,2})\\s*(?:d\\b|den)").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { clampDenier(it) }
                ?: when { t.contains("sheer") -> 12; t.contains("opaque") || t.contains("thick") -> 70; else -> base.denier }
            return base.copy(style = style, denier = denier, colour = StockingColours.ofWords(t) ?: base.colour,
                seam = if (t.contains("seam")) true else base.seam, lace = if (t.contains("elastic") || t.contains("plain")) false else base.lace)
        }
    }
}

/** Every NPC's stockings, kept as JSON (the app saves [toJson] with the character); an NPC without an entry wears none. */
class StockingBook(val worn: Map<String, StockingSpec> = emptyMap()) {
    fun of(npc: String): StockingSpec? = worn[npc]
    fun with(npc: String, spec: StockingSpec?): StockingBook = StockingBook(if (spec == null) worn - npc else worn + (npc to spec))

    fun toJson(): JSONObject = JSONObject().put("v", 1).put("worn", JSONObject().also { o -> for ((k, v) in worn.toSortedMap()) o.put(k, v.toJson()) })

    companion object {
        fun fromJson(text: String?): StockingBook {
            if (text.isNullOrBlank()) return StockingBook()
            return runCatching {
                val w = JSONObject(text).optJSONObject("worn") ?: return StockingBook()
                StockingBook(w.keys().asSequence().mapNotNull { k -> StockingSpec.fromJson(w.optJSONObject(k))?.let { k to it } }.toMap())
            }.getOrDefault(StockingBook())
        }
    }
}
