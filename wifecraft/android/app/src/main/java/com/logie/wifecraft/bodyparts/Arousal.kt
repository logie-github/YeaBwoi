package com.logie.wifecraft.bodyparts

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Automatic arousal for the men's body parts (README, "Body parts add-on"): a level 0 (flaccid) .. 1 (erect) per wearer,
 * drawn by the engine as a bend between the set's DEFAULT and ON meshes (cpp engine/bodypart.h levelBend), and the partner
 * entry the erect shaft is aimed at during a partner stage (bodypart.h measureEntries, insertionDepth). Pure JVM.
 *
 *  - [ArousalMap] (assets/arousal_map.json): what a stage is ([StageClass]: its level, its entry, its rest depth) from the
 *    pack's own category (WickedWhims' animation_category, kept on import as PairAnim.kind) and the stage's names.
 *  - [ArousalTracker]: one wearer's level over time: up while an intimate stage plays (following the scene's intensity),
 *    down after it ends (held a moment first, so the gap between two stages doesn't flicker it).
 *  - [ArousalMode] (Options → Mods → Body parts) and [PartOverride] (per NPC, the scene's switch).
 */
enum class PartEntryKind(val engine: Int) {
    NONE(0), VAGINAL(1), ANAL(2), ORAL(3), MANUAL(4), THIGH(5), CHEST(6),
    /** A climax stage: the entry of the stage before it. */
    KEEP(-1);

    companion object {
        fun of(name: String?): PartEntryKind = values().firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: NONE
    }
}

/** What a stage does to a wearer: his [level] (0..1), the [entry] the shaft goes into, the [rest] depth (Sims metres). */
data class StageClass(val category: String, val level: Float, val entry: PartEntryKind, val rest: Float) {
    val intimate: Boolean get() = level > 0f

    companion object {
        val NONE = StageClass("NONE", 0f, PartEntryKind.NONE, 0f)
    }
}

/** The names and tuning a stage is told by: its own and its clips' names, the pack's category (may be empty), how many act. */
data class StageHints(val names: List<String>, val category: String = "", val actors: Int = 2)

class ArousalMap(
    val categories: Map<String, StageClass>,
    private val aliases: Map<String, String>,
    private val keywords: List<Pair<List<String>, String>>,
    private val calm: List<String>,
    private val sexualHints: List<String>,
    val unknownSexual: Float,
    private val strongCategories: Set<String>,
) {
    /** The category name a pack writes, its aliases (typos the packs have: ORAJOB, VAGNAL...) resolved; null: unknown. */
    fun canonical(category: String): String? {
        val c = category.trim().uppercase()
        if (c.isEmpty()) return null
        val a = aliases[c] ?: c
        return a.takeIf { it in categories }
    }

    /**
     * The stage's class: a specific keyword in its names first (a blowjob, a doggy, a cunnilingus), then a calm word (a
     * kiss, a dance, a hug: flaccid) unless the pack's category is a strong one (oral, vaginal, anal, manual, climax), then
     * the pack's category, then: a two-person stage with a sexual hint in its names is [unknownSexual] with no entry, else 0.
     */
    fun classify(h: StageHints): StageClass {
        val text = normalize(h.names.joinToString(" "))
        for ((words, cat) in keywords) if (words.any { matches(text, it) }) categories[cat]?.let { return it }
        val packCat = canonical(h.category)
        if (packCat != null && packCat in strongCategories) return categories.getValue(packCat)
        if (calm.any { matches(text, it) }) return categories["CALM"] ?: StageClass.NONE
        if (packCat != null) return categories.getValue(packCat)
        if (h.actors >= 2 && sexualHints.any { matches(text, it) }) return StageClass("UNKNOWN", unknownSexual, PartEntryKind.NONE, 0f)
        return StageClass.NONE
    }

    companion object {
        /** Letters and digits, lower case, words split on everything else (camel case too), padded with spaces. */
        fun normalize(s: String): String {
            val split = s.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            return " " + split.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
        }

        /** "=word": the whole word; otherwise anywhere (words joined by spaces). */
        fun matches(text: String, key: String): Boolean =
            if (key.startsWith("=")) text.contains(" " + key.drop(1) + " ") else text.contains(key)

        fun parse(json: String): ArousalMap {
            val o = JSONObject(json)
            val cats = LinkedHashMap<String, StageClass>()
            val co = o.getJSONObject("categories")
            for (k in co.keys()) {
                val c = co.getJSONObject(k)
                cats[k.uppercase()] = StageClass(k.uppercase(), c.optDouble("level", 0.0).toFloat().coerceIn(0f, 1f),
                    PartEntryKind.of(c.optString("entry", "none")), c.optDouble("rest", 0.0).toFloat())
            }
            val aliases = HashMap<String, String>()
            o.optJSONObject("aliases")?.let { a -> for (k in a.keys()) aliases[k.uppercase()] = a.getString(k).uppercase() }
            val kws = ArrayList<Pair<List<String>, String>>()
            o.optJSONArray("keywords")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    val words = e.getJSONArray("words")
                    kws += List(words.length()) { words.getString(it).lowercase() } to e.getString("category").uppercase()
                }
            }
            fun list(name: String): List<String> = o.optJSONArray(name)?.let { a -> List(a.length()) { a.getString(it).lowercase() } }.orEmpty()
            return ArousalMap(cats, aliases, kws, list("calm"), list("sexualHints"), o.optDouble("unknownSexual", 0.6).toFloat(),
                list("strongCategories").map { it.uppercase() }.toSet())
        }
    }
}

/** Options → Mods → Body parts → Arousal. */
enum class ArousalMode(val label: String) {
    AUTOMATIC("Automatic"), ALWAYS_FLACCID("Always flaccid"), ALWAYS_ERECT("Always erect");

    companion object {
        fun of(name: String?): ArousalMode = values().firstOrNull { it.name == name } ?: AUTOMATIC
    }
}

/** The scene's per-NPC switch: [AUTO] follows the mode; [ON] erect; [OFF] flaccid. */
enum class PartOverride(val label: String) {
    AUTO("Auto"), ON("On"), OFF("Off");

    fun next(): PartOverride = values()[(ordinal + 1) % values().size]

    companion object {
        fun of(name: String?): PartOverride = values().firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * One wearer's level. [step] with what plays now ([stage]: [StageClass.NONE] when no partner stage plays), the scene's eased
 * intensity (simulation/Immersion IntensityModel, 0..1), the mode and the override; returns the level to draw.
 *
 * The goal: forced by the override or the mode, else the stage's level times how far the intensity has come
 * ([follow]: [FOLLOW_LOW] at a stage's start, 1 from [FOLLOW_FULL] up). It rises at most 1 / [RISE_S] a second and falls at
 * most 1 / [FALL_S] a second; when the goal drops (the stage ended, or a calm one began) the last goal is held [HOLD_S] first,
 * so the moment between two stages doesn't take it down. The engine eases the drawn pitch (smoothstep) and swaps the meshes
 * with its own hysteresis (bodypart.h kPartSwapUp / kPartSwapDown).
 */
class ArousalTracker {
    var level = 0f
        private set
    private var held = 0f
    private var holdLeft = 0f
    /** The last entry a stage had (a climax stage keeps it). */
    var lastEntry = PartEntryKind.NONE
        private set

    fun goal(stage: StageClass, intensity: Float, mode: ArousalMode, override: PartOverride): Float = when {
        override == PartOverride.ON -> 1f
        override == PartOverride.OFF -> 0f
        mode == ArousalMode.ALWAYS_ERECT -> 1f
        mode == ArousalMode.ALWAYS_FLACCID -> 0f
        !stage.intimate -> 0f
        else -> (stage.level * follow(intensity)).coerceIn(0f, 1f)
    }

    fun step(stage: StageClass, intensity: Float, mode: ArousalMode, override: PartOverride, dt: Float): Float {
        val d = dt.coerceIn(0f, 0.25f)
        val forced = override != PartOverride.AUTO || mode != ArousalMode.AUTOMATIC
        var g = goal(stage, intensity, mode, override)
        if (stage.entry != PartEntryKind.NONE && stage.entry != PartEntryKind.KEEP) lastEntry = stage.entry
        if (!stage.intimate && !forced) lastEntry = if (holdLeft > 0f) lastEntry else PartEntryKind.NONE
        if (!forced) {
            if (g >= held) { held = g; holdLeft = HOLD_S }
            else if (holdLeft > 0f) { holdLeft -= d; g = held }
            else held = g
        } else { held = g; holdLeft = 0f }
        level = if (g > level) minOf(g, level + d / RISE_S) else maxOf(g, level - d / FALL_S)
        return level
    }

    /** The entry to aim at now ([StageClass.entry], a climax's kept). */
    fun entryFor(stage: StageClass): PartEntryKind = when (stage.entry) {
        PartEntryKind.KEEP -> lastEntry
        else -> stage.entry
    }

    companion object {
        const val RISE_S = 2.0f
        const val FALL_S = 4.0f
        const val HOLD_S = 2.5f
        const val FOLLOW_LOW = 0.7f
        const val FOLLOW_FULL = 0.65f

        /** How far the intensity has come: [FOLLOW_LOW] at a stage's first intensity (0.4) and below, 1 at [FOLLOW_FULL]. */
        fun follow(intensity: Float): Float {
            val t = ((intensity - 0.4f) / (FOLLOW_FULL - 0.4f)).coerceIn(0f, 1f)
            return FOLLOW_LOW + (1f - FOLLOW_LOW) * t
        }
    }
}

/**
 * The live scene's arousal settings, any thread: Options' [mode] and each NPC's [override] (by NPC id; persisted by the app
 * in its settings, data/Settings partOverride). The scene's frame reads them; the menus write them.
 */
object ArousalControl {
    @Volatile var mode: ArousalMode = ArousalMode.AUTOMATIC
    private val overrides = ConcurrentHashMap<String, PartOverride>()

    fun override(npcId: String): PartOverride = overrides[npcId] ?: PartOverride.AUTO

    fun setOverride(npcId: String, o: PartOverride) {
        if (o == PartOverride.AUTO) overrides.remove(npcId) else overrides[npcId] = o
    }
}

/**
 * The playback keys of a partner stage (SceneSimulation's PlaybackKeys.pair "pair:<id>:<role>", a choice's "<choice id>:<role>"):
 * the stage they share and the role. Null: not a role key (a single clip).
 */
object StageKeys {
    fun group(key: String): String? {
        val i = key.lastIndexOf(':')
        if (i <= 0 || key.substring(i + 1).toIntOrNull() == null) return null
        return key.substring(0, i)
    }

    fun role(key: String): Int = key.substringAfterLast(':').toIntOrNull() ?: 0

    /**
     * Each person's partner and their role in a stage: for [keys] (each person's playing key; null none), the index of the
     * other person playing the same stage, or -1.
     */
    fun partners(keys: Array<String?>): IntArray {
        val out = IntArray(keys.size) { -1 }
        for (i in keys.indices) {
            val gi = keys[i]?.let(::group) ?: continue
            for (j in keys.indices) if (j != i && keys[j]?.let(::group) == gi) { out[i] = j; break }
        }
        return out
    }
}
