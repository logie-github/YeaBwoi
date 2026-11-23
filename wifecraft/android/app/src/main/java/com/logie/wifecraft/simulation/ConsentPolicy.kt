package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.AnimationChoice
import com.logie.wifecraft.anim.PairAnim
import com.logie.wifecraft.dialogue.Disposition
import com.logie.wifecraft.dialogue.WillingnessModel
import org.json.JSONObject

// Pure Kotlin (no Android): the Consent and limits screen's rules. Each NPC has a stance for every act (a two-person animation or
// stage): Will (starts without asking), Ask (goes through the willingness and hesitation flow as before) or Won't (never, in any
// role). A stance is set for a whole category, and an act can override its category ("go act by act"). One policy object answers
// for every place an act can start (the NPCs' own picks, the player's invitations, auto-play, Switch positions, the animation
// browser). JVM-tested (unit-tests/.../simulation/ConsentPolicyTest.kt).

/** What an NPC does about an act: [WILL] starts it unasked, [ASK] goes through willingness as it always did, [WONT] never. */
enum class Stance(val label: String) {
    WILL("Will"), ASK("Ask"), WONT("Won't");

    /** The more restrictive of the two (Won't over Ask over Will). */
    fun strictest(other: Stance): Stance = if (ordinal >= other.ordinal) this else other

    companion object {
        fun of(name: String?): Stance? = entries.firstOrNull { it.name == name }
    }
}

/**
 * One act: [id] (a pair's id without the browser's prefix, or `emote:<anim>`), its [name], the pack's [kind] (WickedWhims'
 * animation_category and the like), the pack's own [pack] category (a library pair's category: the pack's name), and how many
 * [people] take part. Its [categories] are read from these ([ActCategories]), never from a fixed list of packs.
 */
data class ConsentAct(val id: String, val name: String, val kind: String = "", val pack: String = "", val people: Int = 2) {
    /** Every category the act belongs to, the first the one it is listed under first (never empty: "Other" at the least). */
    val categories: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { ActCategories.of(this) }
    val category: String get() = categories.first()

    companion object {
        fun idOf(id: String): String = id.removePrefix(PairAnim.CHOICE_PREFIX)
        fun of(p: PartnerPair) = ConsentAct(idOf(p.id), p.name, p.kind, p.category)
        fun of(c: AnimationChoice) = ConsentAct(idOf(c.id), c.name, c.kind, if (c.id.startsWith(PairAnim.CHOICE_PREFIX)) c.group else "", c.participantCount)
        fun emote(e: PartnerEmote) = ConsentAct("emote:${e.anim}", e.name)
    }
}

/**
 * The categories of an act: read from its name and the pack's category by words, so an act can belong to several (a rough
 * doggy is Vaginal and Rough: a Won't on either keeps it from being played). The gentle ones (kissing, dancing, friendly) apply
 * only to an act with no explicit one. An act none of the words place takes the pack's own category when it names one (so a
 * pack that adds its own kinds shows up by itself), else "Other".
 */
object ActCategories {
    const val KISSING = "Kissing and cuddling"
    const val DANCING = "Dancing and teasing"
    const val FRIENDLY = "Friendly and play"
    const val HANDS = "Hands"
    const val ORAL = "Oral"
    const val VAGINAL = "Vaginal"
    const val ANAL = "Anal"
    const val TOYS = "Toys"
    const val BONDAGE = "Bondage and restraint"
    const val FEMDOM = "Femdom and domination"
    const val CUCKOLD = "Cuckold and humiliation"
    const val FOOT = "Foot fetish"
    const val PUBLIC = "Public and exhibition"
    const val ROUGH = "Rough"
    const val GROUP = "Group and three-person"
    const val CLIMAX = "Climax and finishing"
    const val OTHER = "Other"

    /** The known categories in the order they are listed. */
    val ORDER = listOf(KISSING, DANCING, FRIENDLY, HANDS, ORAL, VAGINAL, ANAL, TOYS, BONDAGE, FEMDOM, CUCKOLD, FOOT, PUBLIC, ROUGH, GROUP, CLIMAX)

    // "=word": the whole word; otherwise anywhere (also with the spaces left out: "blow job" is "blowjob")
    private val EXPLICIT: List<Pair<String, List<String>>> = listOf(
        GROUP to listOf("threesome", "3some", "=group", "=orgy", "foursome", "gangbang", "=dp", "=mfm", "=ffm", "=fmf", "=trio", "threeway", "three way"),
        BONDAGE to listOf("bondage", "=bound", "=tied", "=rope", "restrain", "handcuff", "=cuffs", "=gag", "=gagged", "blindfold", "shibari", "=leash", "=collar", "=spreader", "shackle", "=stocks"),
        FEMDOM to listOf("femdom", "dominat", "=mistress", "facesit", "face sit", "pegging", "strapon", "strap on", "smother", "=worship", "submissive", "=domme", "=trample"),
        CUCKOLD to listOf("cuck", "humiliat", "hotwife", "=cheat", "=cheating", "=ntr", "=bull"),
        FOOT to listOf("=foot", "=feet", "footjob", "=toes", "=toe", "=sole", "=soles"),
        PUBLIC to listOf("=public", "exhibition", "exhib", "=flashing", "voyeur", "=outdoor", "=outdoors"),
        TOYS to listOf("=dildo", "=vibrator", "=toy", "=toys", "=plug", "=vibe", "=wand"),
        ROUGH to listOf("=rough", "=spank", "=spanking", "=slap", "=slapping", "=choke", "=choking", "hair pull", "hairpull", "hardcore", "=brutal", "=bdsm", "=whip", "=flog", "facefuck", "throatfuck", "=slam", "=pounding"),
        ANAL to listOf("=anal", "=ass", "rimjob", "=rimming", "buttfuck"),
        ORAL to listOf("blowjob", "oraljob", "orajob", "=oral", "cunnilingus", "deepthroat", "fellatio", "=69", "sixty nine", "=suck", "=sucking", "=licking", "=lick", "=bj"),
        HANDS to listOf("handjob", "=fingering", "=fingered", "=finger", "manual", "=grope", "=groping", "=rubbing", "massage", "=handy"),
        CLIMAX to listOf("climax", "=cum", "cumshot", "=facial", "=orgasm", "=finish", "creampie", "bukkake", "ejaculat"),
        VAGINAL to listOf("vaginal", "vagnal", "missionary", "cowgirl", "doggy", "=sex", "=fuck", "=fucking", "=pussy", "=lotus", "scissoring", "mating press", "=riding"),
    )
    private val GENTLE: List<Pair<String, List<String>>> = listOf(
        KISSING to listOf("=kiss", "=kissing", "=cuddle", "=cuddling", "=hug", "=hugging", "=embrace", "snuggl", "=spoon", "=spooning", "hold hands", "holding hands", "=caress", "nuzzle", "=smooch", "=romance", "romantic", "=carry"),
        DANCING to listOf("=dance", "=dancing", "waltz", "=tease", "=teasing", "=strip", "striptease", "lap dance", "lapdance", "=flirt", "=twirl", "=spin", "=grind", "=grinding", "seduct"),
        FRIENDLY to listOf("=wave", "=cheer", "=laugh", "=bow", "=applaud", "=salute", "high five", "handshake", "=clap", "=toast", "=tickle", "=play", "=pillow", "piggyback", "=pat", "=talk", "=chat", "greet"),
    )
    /** What a pack's category may say without meaning a category of its own. */
    private val GENERIC = setOf("", "partner", "calm", "none", "other", "default", "general", "misc", "miscellaneous", "all", "partner animations")

    private fun norm(s: String) = " " + s.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
    private fun hit(text: String, squashed: String, key: String): Boolean =
        if (key.startsWith("=")) text.contains(" ${key.drop(1)} ") else text.contains(key) || squashed.contains(key.replace(" ", ""))

    /** Every category of [act], the most specific first. */
    fun of(act: ConsentAct): List<String> {
        val text = norm(act.name + " " + act.kind)
        val squashed = text.replace(" ", "")
        val found = EXPLICIT.filter { (_, keys) -> keys.any { hit(text, squashed, it) } }.map { it.first }
        if (found.isNotEmpty()) return found
        val gentle = GENTLE.firstOrNull { (_, keys) -> keys.any { hit(text, squashed, it) } }?.first
        if (gentle != null) return listOf(gentle)
        readable(act.kind)?.let { return listOf(it) }
        readable(act.pack)?.let { return listOf(it) }
        return listOf(OTHER)
    }

    /** A pack's own category as a title ("KINKY_STUFF" is "Kinky stuff"); null when it says nothing (empty, "Partner", "CALM"). */
    fun readable(raw: String): String? {
        val words = raw.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace(Regex("[^A-Za-z0-9]+"), " ").trim().lowercase()
        if (words in GENERIC) return null
        return words.replaceFirstChar { it.uppercase() }
    }

    /** [categories] in listing order: the known ones, the packs' own alphabetically, then "Other". */
    fun sorted(categories: Collection<String>): List<String> {
        val known = ORDER.filter { it in categories }
        val extra = categories.filter { it !in ORDER && it != OTHER }.sortedBy { it.lowercase() }
        return known + extra + listOfNotNull(OTHER.takeIf { it in categories })
    }
}

/**
 * One NPC's explicit choices: a stance per category ([categories]), per act ([acts], overriding its category) and per act in
 * one role ([roles], "<act id>#<role>", overriding the act). Nothing set here means the defaults ([ConsentDefaults]).
 */
data class NpcLimits(
    val categories: Map<String, Stance> = emptyMap(),
    val acts: Map<String, Stance> = emptyMap(),
    val roles: Map<String, Stance> = emptyMap(),
) {
    val isEmpty: Boolean get() = categories.isEmpty() && acts.isEmpty() && roles.isEmpty()
    /** How many categories and acts are set to Won't (the Limits line). */
    val wonts: Int get() = categories.count { it.value == Stance.WONT } + acts.count { it.value == Stance.WONT } + roles.count { it.value == Stance.WONT }

    fun withCategory(category: String, stance: Stance?) = copy(categories = put(categories, category, stance))
    fun withAct(id: String, stance: Stance?) = copy(acts = put(acts, id, stance))
    fun withRole(id: String, role: Int, stance: Stance?) = copy(roles = put(roles, roleKey(id, role), stance))

    private fun put(m: Map<String, Stance>, key: String, s: Stance?): Map<String, Stance> = if (s == null) m - key else m + (key to s)

    fun toJson(): JSONObject {
        fun obj(m: Map<String, Stance>) = JSONObject().also { o -> for ((k, v) in m.toSortedMap()) o.put(k, v.name) }
        return JSONObject().put("categories", obj(categories)).put("acts", obj(acts)).put("roles", obj(roles))
    }

    companion object {
        val EMPTY = NpcLimits()
        fun roleKey(id: String, role: Int) = "$id#$role"

        fun fromJson(o: JSONObject?): NpcLimits {
            if (o == null) return EMPTY
            fun read(name: String): Map<String, Stance> {
                val m = o.optJSONObject(name) ?: return emptyMap()
                val out = LinkedHashMap<String, Stance>()
                for (k in m.keys()) Stance.of(m.optString(k))?.let { out[k] = it }
                return out
            }
            return NpcLimits(read("categories"), read("acts"), read("roles"))
        }
    }
}

/**
 * What an NPC does about a category when the player has not said: from their nature through the willingness model's own terms
 * (caution and devotion raise the resistance; boldness, warmth and curiosity lower it): a resistance under
 * [WillingnessModel.AGREE_BELOW] is Will, any more is Ask. Never Won't: only the player sets that.
 */
object ConsentDefaults {
    private val WEIGHT = mapOf(
        ActCategories.KISSING to 0f, ActCategories.DANCING to 0f, ActCategories.FRIENDLY to 0f, ActCategories.HANDS to 0.1f,
        ActCategories.ORAL to 0.2f, ActCategories.VAGINAL to 0.2f, ActCategories.CLIMAX to 0.2f, ActCategories.FOOT to 0.25f,
        ActCategories.TOYS to 0.28f, ActCategories.ANAL to 0.32f, ActCategories.ROUGH to 0.35f, ActCategories.PUBLIC to 0.35f,
        ActCategories.FEMDOM to 0.35f, ActCategories.BONDAGE to 0.38f, ActCategories.GROUP to 0.4f, ActCategories.CUCKOLD to 0.45f,
    )
    /** How much a category asks of anyone before their nature (a pack's own category: as the most ordinary intimate one). */
    fun weight(category: String): Float = WEIGHT[category] ?: 0.2f

    /** Their nature's shift of the resistance (the willingness prior's own terms, without the people and the relationship). */
    fun shift(d: Disposition): Float = d.caution * 0.3f + d.devout * 0.2f - d.boldness * 0.25f - d.warmth * 0.1f - d.curiosity * 0.15f

    fun stance(d: Disposition, category: String): Stance =
        if (weight(category) + shift(d) < WillingnessModel.AGREE_BELOW) Stance.WILL else Stance.ASK
}

/** Why an act has the stance it has: the [source] ([Source.CATEGORY] and [Source.DEFAULT]: through [via], the category). */
data class Resolved(val stance: Stance, val source: Source, val via: String = "") {
    enum class Source { ROLE, ACT, CATEGORY, DEFAULT }
}

/**
 * The one answer to "will [npc] do [act]" ([resolve]: role, then act, then category, then the nature's default): used by every
 * place an act can start. An act in several categories takes the strictest of the categories the player set (their Won't over
 * a Will on a sister category), else the strictest default. Thread-safe: the screen edits ([set]) while the simulation reads;
 * [version] moves with each edit so a running scene can notice.
 */
class ConsentPolicy(initial: Map<String, NpcLimits> = emptyMap(), private val disposition: (String) -> Disposition = { Disposition() }) {
    @Volatile private var limits: Map<String, NpcLimits> = initial.filterValues { !it.isEmpty }
    @Volatile var version = 0
        private set

    /** Every NPC's explicit limits now. */
    val all: Map<String, NpcLimits> get() = limits

    fun limitsOf(npc: String): NpcLimits = limits[npc] ?: NpcLimits.EMPTY

    @Synchronized fun set(npc: String, l: NpcLimits) {
        limits = if (l.isEmpty) limits - npc else limits + (npc to l)
        version++
    }

    /** Several NPCs' limits in one edit (copy to all). */
    @Synchronized fun setAll(changes: Map<String, NpcLimits>) {
        var next = limits
        for ((npc, l) in changes) next = if (l.isEmpty) next - npc else next + (npc to l)
        limits = next
        version++
    }

    /** [role] -1: any role (the act's and category's stance only). */
    fun resolve(npc: String, act: ConsentAct, role: Int = -1): Resolved {
        val l = limitsOf(npc)
        if (role >= 0) l.roles[NpcLimits.roleKey(act.id, role)]?.let { return Resolved(it, Resolved.Source.ROLE) }
        l.acts[act.id]?.let { return Resolved(it, Resolved.Source.ACT) }
        val cats = act.categories
        var best: Stance? = null
        var via = ""
        for (c in cats) l.categories[c]?.let { s -> if (best == null || s.ordinal > best!!.ordinal) { best = s; via = c } }
        best?.let { return Resolved(it, Resolved.Source.CATEGORY, via) }
        val d = disposition(npc)
        var stance = Stance.WILL
        via = cats.first()
        for (c in cats) { val s = ConsentDefaults.stance(d, c); if (s.ordinal > stance.ordinal) { stance = s; via = c } }
        return Resolved(stance, Resolved.Source.DEFAULT, via)
    }

    fun stance(npc: String, act: ConsentAct, role: Int = -1): Stance = resolve(npc, act, role).stance

    /** What [npc] does about [category] when nothing is set for it: their nature's default. */
    fun defaultStance(npc: String, category: String): Stance = ConsentDefaults.stance(disposition(npc), category)

    /** Whether [npc] may do [act] in [role] (-1: in any role). Everything but a Won't. */
    fun allows(npc: String, act: ConsentAct, role: Int = -1): Boolean = stance(npc, act, role) != Stance.WONT

    /**
     * How [a] and [b] may take [act] (role 0 and role 1 of a stage): 0 with [a] in role 0, 1 with [a] in role 1, -1 not at
     * all (a stage needs both of them to allow it, each in the role they would play). [symmetric]: one both play alike (an emote).
     */
    fun orientation(a: String, b: String, act: ConsentAct, symmetric: Boolean = false): Int {
        if (symmetric) return if (allows(a, act) && allows(b, act)) 0 else -1
        if (allows(a, act, 0) && allows(b, act, 1)) return 0
        if (allows(a, act, 1) && allows(b, act, 0)) return 1
        return -1
    }

    fun allowsPair(a: String, b: String, act: ConsentAct, symmetric: Boolean = false): Boolean = orientation(a, b, act, symmetric) >= 0

    /** Whether [a] and [b] may play [act] with [a] in [roleA] and [b] in the other role ([roleA] -1: either). */
    fun allowsRoles(a: String, b: String, act: ConsentAct, roleA: Int): Boolean =
        if (roleA < 0) allowsPair(a, b, act) else allows(a, act, roleA) && allows(b, act, 1 - roleA)

    /** The one of [a] and [b] who will not do [act] (in any role they could take), else null: who declines. */
    fun decliner(a: String, b: String, act: ConsentAct, roleA: Int = -1): String? {
        fun no(npc: String, role: Int) = !allows(npc, act, role)
        return when {
            roleA >= 0 -> if (no(a, roleA)) a else if (no(b, 1 - roleA)) b else null
            !allows(a, act) -> a
            !allows(b, act) -> b
            orientation(a, b, act) >= 0 -> null
            else -> if (no(a, 0) && no(a, 1)) a else b
        }
    }

    /**
     * Whether the stage may start without the willingness flow asking: the invitee is Will, and so is the initiator when the
     * player ordered the stage. Any Ask goes through the flow as before.
     */
    fun skipsAsking(initiator: String, invitee: String, act: ConsentAct, directed: Boolean): Boolean =
        stance(invitee, act) == Stance.WILL && (!directed || stance(initiator, act) == Stance.WILL)

    companion object {
        /** The speech bubble an NPC shows when the dialogue layer has no words for a refusal. */
        const val DECLINE_BUBBLE = "💬 declines"
    }
}
