package com.logie.wifecraft.catalog

import com.logie.wifecraft.creator.Sex
import org.json.JSONObject

/**
 * What an emote entry is: one of the game's text emotes (/laugh, /sit: EmotesText), one of its NPC emotes no text emote
 * uses (Emotes: work, mining, fishing, ready stances, spell casts...), or any other animation the model has.
 */
enum class EmoteKind { TEXT, NPC, ANIM }

/**
 * One emote a model can play. [anim] is the AnimationData id (-1: a text emote with a voice and no animation on this
 * model), [variation] the one to play (0; [variations] says how many the model has), [soundFiles] the voice's recordings
 * for the model's race and sex (one is played at random; empty for none), [loop] whether it is held (a state: sit, sleep,
 * kneel, dance) rather than played once. Ids: a text emote's EmotesText id; an NPC emote's 10000 + Emotes id; an
 * animation's 100000 + its id.
 */
data class Emote(
    val id: Int,
    val name: String,
    val anim: Int,
    val variation: Int,
    val soundFiles: List<Int>,
    val kind: EmoteKind = EmoteKind.TEXT,
    val category: String = EmoteCatalog.EMOTES,
    val loop: Boolean = false,
    val variations: Int = 1,
)

/**
 * Every emote and animation of each of the character creator's models, with the emote's voice by race and sex:
 * assets/emotes.json (tools/emotes.py: the game's tables and each model's own sequences, ids and names only). Pure JVM.
 *
 * [forModel] lists, for a race and sex: the text emotes the model can show (its animation is among the model's own
 * sequences, or the race and sex have a voice for it; a text emote with neither shows nothing and is left out), by name;
 * then the NPC emotes whose animation the model has and no text emote already plays the same way; then every other
 * animation the model has, by [categoryOf] and name.
 */
class EmoteCatalog private constructor(
    private val text: List<Row>,
    private val npc: List<Row>,
    private val anims: Map<Int, String>,
    private val models: Map<String, ModelRow>,
) {
    private class Row(val id: Int, val name: String, val anim: Int, val kind: Int)
    private class ModelRow(val chrModel: Int, val file: Int, val variations: Map<Int, Int>, val voices: Map<Int, List<Int>>)

    private val cache = HashMap<String, List<Emote>>()

    /** The race and sex pairs the file has ("race:sex", sex 0 man, 1 woman). */
    val keys: Set<String> get() = models.keys

    /** An animation's name (wow.export's, from wow.tools: AnimationData has none), or null. */
    fun animName(anim: Int): String? = anims[anim]

    /** The model's M2 file id for a race and sex (0: not in the file). */
    fun modelFile(raceId: Int, sex: Sex): Int = models[key(raceId, sex)]?.file ?: 0

    /** How many emotes and animations [forModel] gives ("all emotes" for the race and sex). */
    fun count(raceId: Int, sex: Sex): Int = forModel(raceId, sex).size

    @Synchronized
    fun forModel(raceId: Int, sex: Sex): List<Emote> = cache.getOrPut(key(raceId, sex)) {
        val m = models[key(raceId, sex)] ?: return@getOrPut emptyList()
        val out = ArrayList<Emote>()
        val played = HashSet<Pair<Int, Boolean>>()
        val covered = HashSet<Int>()
        for (r in text) {
            val voice = m.voices[r.id].orEmpty()
            val has = r.anim >= 0 && r.anim in m.variations
            if (!has && voice.isEmpty()) continue
            val loop = r.kind != 0
            if (has) { played += r.anim to loop; covered += r.anim }
            out += Emote(r.id, r.name, if (has) r.anim else -1, 0, voice, EmoteKind.TEXT, EMOTES, loop, if (has) m.variations.getValue(r.anim) else 1)
        }
        out.sortWith(compareBy({ it.name.lowercase() }, { it.id }))
        val npcOut = ArrayList<Emote>()
        for (r in npc) {
            val loop = r.kind != 0
            if (r.anim !in m.variations || (r.anim to loop) in played) continue
            played += r.anim to loop
            covered += r.anim
            npcOut += Emote(r.id, r.name, r.anim, 0, emptyList(), EmoteKind.NPC, categoryOf(anims[r.anim] ?: ""), loop, m.variations.getValue(r.anim))
        }
        npcOut.sortWith(compareBy({ it.category }, { it.name.lowercase() }, { it.id }))
        out += npcOut
        val rest = ArrayList<Emote>()
        for ((anim, n) in m.variations) {
            if (anim in covered || anim in LAYERS) continue
            val name = anims[anim] ?: "Anim$anim"
            rest += Emote(ANIM_BASE + anim, name, anim, 0, emptyList(), EmoteKind.ANIM, categoryOf(name), false, n)
        }
        rest.sortWith(compareBy({ CATEGORIES.indexOf(it.category) }, { it.name.lowercase() }))
        out += rest
        out
    }

    companion object {
        const val NPC_BASE = 10000
        const val ANIM_BASE = 100000
        const val EMOTES = "Emotes"

        /**
         * Animations that are a layer over another, never played alone: FacePose (808) keys the face only, and played on its
         * own leaves the body in the bind pose, arms out (measured on all seven test models).
         */
        val LAYERS = setOf(808)

        /** The categories of [categoryOf], in the order the list shows them. */
        val CATEGORIES = listOf(EMOTES, "Dance", "Sit, sleep and kneel", "Work and life", "Combat", "Spells", "Death and injury",
            "Movement", "Flying", "Other")

        fun key(raceId: Int, sex: Sex) = "$raceId:${if (sex == Sex.MALE) 0 else 1}"

        /** An animation's category, by its name's words (wow.export's names: EmoteDance, SitChairMed, Attack1H...). */
        fun categoryOf(name: String): String {
            val n = name.lowercase()
            fun any(vararg w: String) = w.any { it in n }
            return when {
                n.startsWith("fly") -> "Flying"
                any("dance") -> "Dance"
                any("sit", "sleep", "kneel", "lean", "crouch") -> "Sit, sleep and kneel"
                any("death", "dead", "drown", "knockdown", "wound", "stun", "cower", "rise", "critical") -> "Death and injury"
                any("work", "fishing", "loot", "prof", "cooking", "herbalism", "hammer", "smith", "craft", "barpatron",
                    "barserver", "barsweep", "bartender", "carry", "usestanding", "eating", "emoteeat", "drink", "emoteread",
                    "chop", "mining") ||
                    (name.length > 2 && name.startsWith("WA") && name[2].isUpperCase()) -> "Work and life"
                n.startsWith("emote") || any("talk", "salute") -> EMOTES
                any("attack", "parry", "ready", "block", "dodge", "combat", "special", "shieldbash", "battleroar", "kick",
                    "whirlwind", "charge", "sheath", "load", "hold", "fire", "throw", "bow", "rifle", "shield") -> "Combat"
                any("spell", "cast", "channel") -> "Spells"
                any("walk", "run", "swim", "jump", "fall", "shuffle", "sprint", "mount", "stealth", "turn", "strafe", "land",
                    "liftoff", "hover", "stand") -> "Movement"
                else -> "Other"
            }
        }

        fun parse(json: String): EmoteCatalog {
            val o = JSONObject(json)
            fun rows(name: String): List<Row> {
                val a = o.optJSONArray(name) ?: return emptyList()
                return List(a.length()) { i -> a.getJSONArray(i).let { Row(it.getInt(0), it.getString(1), it.getInt(2), it.getInt(3)) } }
            }
            val an = o.optJSONObject("anims") ?: JSONObject()
            val anims = an.keys().asSequence().associate { it.toInt() to an.getString(it) }
            val ms = o.optJSONObject("models") ?: JSONObject()
            val models = ms.keys().asSequence().associateWith { k ->
                val m = ms.getJSONObject(k)
                val a = m.getJSONArray("a")
                val vars = LinkedHashMap<Int, Int>()
                var i = 0
                while (i + 1 < a.length()) { vars[a.getInt(i)] = a.getInt(i + 1); i += 2 }
                val v = m.optJSONObject("v") ?: JSONObject()
                val voices = v.keys().asSequence().associate { t -> t.toInt() to v.getJSONArray(t).let { f -> List(f.length()) { f.getInt(it) } } }
                ModelRow(m.optInt("m"), m.optInt("f"), vars, voices)
            }
            return EmoteCatalog(rows("text"), rows("npc"), anims, models)
        }
    }
}
