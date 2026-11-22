package com.logie.wifecraft.dialogue

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/*
 * Chat mode's setup: who the player's character is (race, sex, class, name) before the talk starts. The NPC takes them for an
 * ordinary person of Azeroth, so all of this is what she is told about the person standing in front of her ([ChatPrompt]).
 * Pure Kotlin (org.json), JVM-tested (ChatModeTest). The last choice is remembered through a [ChatStore] (SharedPreferences on the phone).
 */

/** The player's character as the NPC meets them. */
data class ChatCharacter(val name: String, val raceId: Int, val female: Boolean, val classId: Int) {
    /** The character as the dialogue's own person type: the one the comfort ledger and the willingness model know ([ID] is the pseudo NPC id). */
    fun profile(): NpcProfile = NpcProfile(ID, name, raceId, female, classId, emptyList(), "", Disposition())

    companion object {
        /** The pseudo NPC id of the player's character in the comfort ledger and the steer board. */
        const val ID = "chat:you"
    }
}

data class ChatRaceOption(val id: Int, val name: String, val faction: Faction)
data class ChatClassOption(val id: Int, val name: String)

/** Where the last choice is kept (null: nowhere). */
interface ChatStore {
    fun load(): String?
    fun save(text: String)
}

/** A store in memory, for tests and for a scene with no preferences. */
class MemoryChatStore(var text: String? = null) : ChatStore {
    override fun load(): String? = text
    override fun save(text: String) { this.text = text }
}

/** The per-race name lists (assets/dialogue/chat_names.json): a random name for the character. Data, never a line anyone says. */
class ChatNames(json: String?) {
    private val female = HashMap<String, List<String>>()
    private val male = HashMap<String, List<String>>()
    private var genericF: List<String> = FALLBACK_F
    private var genericM: List<String> = FALLBACK_M

    init {
        runCatching {
            val root = JSONObject(json ?: "{}")
            fun list(a: JSONArray?): List<String> = a?.let { x -> (0 until x.length()).map { x.getString(it) }.filter { it.isNotBlank() } }.orEmpty()
            for (k in root.keys()) {
                val o = root.optJSONObject(k) ?: continue
                val f = list(o.optJSONArray("f")); val m = list(o.optJSONArray("m"))
                if (k == "generic") { if (f.isNotEmpty()) genericF = f; if (m.isNotEmpty()) genericM = m }
                else { if (f.isNotEmpty()) female[k] = f; if (m.isNotEmpty()) male[k] = m }
            }
        }
    }

    /** A name for a [raceKey] ([Lore.raceKey]) person of the sex given (null: either), never [not]; the race's list, else the generic one. */
    fun random(raceKey: String?, female: Boolean?, rnd: Random = Random.Default, not: String? = null): String {
        val f = female ?: rnd.nextBoolean()
        val pool = if (f) raceKey?.let { this.female[it] } ?: genericF else raceKey?.let { this.male[it] } ?: genericM
        val choices = pool.filter { it != not }.ifEmpty { pool }
        return choices[rnd.nextInt(choices.size)]
    }

    companion object {
        val FALLBACK_F = listOf("Mara Vale", "Elin Ash", "Sera Dunn", "Tilda Reeve")
        val FALLBACK_M = listOf("Garrick Dunn", "Tomas Reeve", "Alden Holt", "Corin Vale")
    }
}

enum class ChatSetupPhase { CLOSED, OPEN, STARTED, CANCELLED }

/**
 * The setup sheet's state: Race, Sex, Class and Name. Sex starts empty (it is asked: the persona needs it), the others start from
 * the remembered choice, else empty; [start] is possible when all four are there and saves them; [cancel] returns without saving.
 * The faction follows the race. Races and classes are the lore pack's (every race of the creator: the eleven, the worgen, goblins,
 * pandaren and the allied races; every class).
 */
class ChatSetup(val lore: Lore, private val names: ChatNames = ChatNames(null), private val store: ChatStore? = null,
    private val random: Random = Random.Default) {
    var phase = ChatSetupPhase.CLOSED
        private set
    var raceId: Int? = null
        private set
    var female: Boolean? = null
        private set
    var classId: Int? = null
        private set
    var name: String = ""
        private set
    /** Set when a stored choice was found and used to fill the sheet. */
    var remembered = false
        private set

    /** Every race offered: the lore's ids by name (a pandaren by their faction too). */
    val races: List<ChatRaceOption> = lore.raceIdList().mapNotNull { id ->
        val r = lore.race(id) ?: return@mapNotNull null
        val f = lore.faction(id)
        val label = r.name.replaceFirstChar { it.uppercase() } + if (r.key == "pandaren") " (${f.label})" else ""
        ChatRaceOption(id, label, f)
    }.sortedWith(compareBy({ RACE_ORDER.indexOf(lore.raceKey(it.id)).let { i -> if (i < 0) 99 else i } }, { it.id }))

    /** Every class offered (all the pack's). */
    val classes: List<ChatClassOption> = lore.classIdList().mapNotNull { id -> lore.className(id)?.let { ChatClassOption(id, it.replaceFirstChar { c -> c.uppercase() }) } }

    val faction: Faction? get() = raceId?.let { lore.faction(it) }

    /** Opens the sheet: the remembered choice fills it (the sex included: it was asked once). */
    fun open() {
        phase = ChatSetupPhase.OPEN
        raceId = null; female = null; classId = null; name = ""; remembered = false
        decode(store?.load())?.let { c ->
            if (races.any { it.id == c.raceId } && classes.any { it.id == c.classId }) {
                raceId = c.raceId; female = c.female; classId = c.classId; name = c.name; remembered = true
            }
        }
    }

    fun pickRace(id: Int) { if (races.any { it.id == id }) raceId = id }
    fun pickSex(female: Boolean) { this.female = female }
    fun pickClass(id: Int) { if (classes.any { it.id == id }) classId = id }
    fun typeName(text: String) { name = text.replace(Regex("\\s+"), " ").take(MAX_NAME) }

    /** The race's name generator: fills the name with a random one for the race (and sex, once picked), another than the last. Needs a race. */
    fun randomName(): String? {
        val id = raceId ?: return null
        val n = names.random(lore.raceKey(id), female, random, not = name.takeIf { it.isNotBlank() })
        name = n
        return n
    }

    /** What is still missing, in the sheet's words ("Race", "Sex", "Class", "Name"). */
    fun missing(): List<String> = buildList {
        if (raceId == null) add("Race")
        if (female == null) add("Sex")
        if (classId == null) add("Class")
        if (name.isBlank()) add("Name")
    }

    val canStart: Boolean get() = phase == ChatSetupPhase.OPEN && missing().isEmpty()

    /** Start: the character (and the choice is remembered), or null while something is missing. */
    fun start(): ChatCharacter? {
        if (!canStart) return null
        val c = ChatCharacter(name.trim(), raceId!!, female!!, classId!!)
        store?.save(encode(c))
        phase = ChatSetupPhase.STARTED
        return c
    }

    /** Cancel: back to the scene, nothing saved. */
    fun cancel() { phase = ChatSetupPhase.CANCELLED }

    companion object {
        const val MAX_NAME = 32
        /** The order the sheet lists the races: the creator's eleven, then the others. */
        val RACE_ORDER = listOf("human", "dwarf", "nightelf", "gnome", "draenei", "worgen", "orc", "undead", "tauren", "troll", "bloodelf", "goblin", "pandaren")

        fun encode(c: ChatCharacter): String = JSONObject().put("name", c.name).put("race", c.raceId).put("female", c.female).put("class", c.classId).toString()

        fun decode(text: String?): ChatCharacter? {
            if (text.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(text)
                ChatCharacter(o.getString("name"), o.getInt("race"), o.getBoolean("female"), o.getInt("class"))
            }.getOrNull()
        }
    }
}
