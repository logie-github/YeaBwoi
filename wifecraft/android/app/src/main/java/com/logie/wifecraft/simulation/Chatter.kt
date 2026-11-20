package com.logie.wifecraft.simulation

import kotlin.random.Random

/** An NPC as the talk knows them: [name] (the first word is used), [race] (ChrRaces id; 0 unknown), [female] (null unknown). */
data class Speaker(val name: String, val race: Int = 0, val female: Boolean? = null) {
    val first: String get() = name.trim().substringBefore(' ').ifEmpty { "friend" }
}

/**
 * The NPCs' small talk: original short lines in a Warcraft voice, put together into conversations ([conversation]: a
 * greeting and its answer, two to six exchanges on a topic or two, a farewell and its answer). Lines are templates whose
 * slots are filled from the two speakers (names, race, home city, sister or brother) and the scene's [place]; a template
 * whose slot is unknown is not used. Seeded: the same [Random] gives the same talk. It remembers what was said lately
 * (greetings, exchanges, farewells: [RECENT] of them) and never says one of those again until they fall out, nor stays on
 * the last conversation's topic. Pure JVM.
 */
class Chatter(private val speakers: Map<String, Speaker>, place: String) {
    private val place = placeRef(place)
    private val recent = ArrayDeque<String>()
    private var lastTopic = -1

    /** The topic of the last conversation made ("weather", "jokes"...: the life simulation weighs it), or null. */
    val lastTopicName: String? get() = TOPICS.getOrNull(lastTopic)?.name

    /**
     * What [a] (who began it) and [b] say, in order (ChatLine.speaker 0 is [a]). [opening]: the topic it starts on (the life
     * simulation's Tell a joke: "jokes"); null, or one there is no such topic of, as before: one picked at random.
     */
    fun conversation(a: String, b: String, random: Random, opening: String? = null): List<ChatLine> {
        val sa = speakers[a] ?: Speaker("friend")
        val sb = speakers[b] ?: Speaker("friend")
        val who = arrayOf(sa, sb)
        val out = ArrayList<ChatLine>()
        val used = HashSet<String>()

        fun line(role: Int, beats: List<Beat>, keyPrefix: String): Boolean {
            val me = who[role]; val you = who[1 - role]
            val options = beats.indices.filter { "$keyPrefix$it" !in recent && fill(beats[it].text, me, you) != null }
                .ifEmpty { beats.indices.filter { fill(beats[it].text, me, you) != null } }
            if (options.isEmpty()) return false
            val i = options[random.nextInt(options.size)]
            remember("$keyPrefix$i")
            out += ChatLine(role, fill(beats[i].text, me, you)!!, beats[i].gesture)
            return true
        }

        line(0, GREETINGS, "g")
        line(1, ANSWERS, "a")

        val count = EXCHANGES[random.nextInt(EXCHANGES.size)]
        val forced = if (opening == null) -1 else TOPICS.indexOfFirst { it.name == opening }
        var topic = if (forced >= 0) forced else pickTopic(random, -1)
        // whoever didn't speak last speaks next: the one who came over brings up the first topic, and so on in turn
        var leader = 0
        var said = 0
        var guard = 0
        while (said < count && guard++ < 40) {
            if (said > 0 && random.nextFloat() < SWITCH_TOPIC) topic = pickTopic(random, topic)
            var exchange = pickExchange(topic, used, random, who, leader, fresh = true)
            if (exchange == null) {
                // this topic's said lately: another, and only when every one is, something said before
                val next = pickTopic(random, topic)
                if (next != topic) { topic = next; exchange = pickExchange(topic, used, random, who, leader, fresh = true) }
                if (exchange == null) exchange = pickExchange(topic, used, random, who, leader, fresh = false) ?: break
            }
            used += exchange.key
            remember(exchange.key)
            var role = leader
            for (part in exchange.parts) {
                val me = who[role]; val you = who[1 - role]
                val fits = part.filter { fill(it.text, me, you) != null }
                if (fits.isEmpty()) break
                val beat = fits[random.nextInt(fits.size)]
                out += ChatLine(role, fill(beat.text, me, you)!!, beat.gesture)
                role = 1 - role
            }
            said++
            leader = 1 - out.last().speaker
        }
        lastTopic = topic

        val closer = 1 - out.last().speaker
        line(closer, FAREWELLS, "f")
        line(1 - closer, PARTINGS, "p")

        // the listener nods at the end of some plain statements (never a question, a laugh or a farewell's answer)
        for (i in 0 until out.size - 1) {
            val l = out[i]
            if (l.gesture == Gestures.TALK && out[i + 1].speaker != l.speaker && random.nextFloat() < NOD_CHANCE) out[i] = l.copy(nod = true)
        }
        return out
    }

    /** The player's Say list for [id] (short lines the controlled NPC can say), filled in. */
    fun sayings(id: String): List<String> {
        val me = speakers[id] ?: Speaker("friend")
        val crowd = Speaker("friends")
        return SAYINGS.mapNotNull { fill(it, me, crowd) }
    }

    private fun remember(key: String) {
        recent.remove(key)
        recent.addLast(key)
        while (recent.size > RECENT) recent.removeFirst()
    }

    private fun pickTopic(random: Random, not: Int): Int {
        val options = TOPICS.indices.filter { it != not && it != lastTopic && TOPICS[it].exchanges.any { e -> e.key !in recent } }
            .ifEmpty { TOPICS.indices.filter { it != not } }
        return if (options.isEmpty()) not else options[random.nextInt(options.size)]
    }

    private fun pickExchange(topic: Int, used: Set<String>, random: Random, who: Array<Speaker>, leader: Int, fresh: Boolean): Exchange? {
        val pool = TOPICS[topic].exchanges.filter { it.key !in used && (!fresh || it.key !in recent) && fits(it, who, leader) }
        return if (pool.isEmpty()) null else pool[random.nextInt(pool.size)]
    }

    private fun fits(e: Exchange, who: Array<Speaker>, leader: Int): Boolean {
        val first = e.parts.firstOrNull() ?: return false
        return first.any { fill(it.text, who[leader], who[1 - leader]) != null }
    }

    /** [text] with its slots filled for [me] speaking to [you], or null when a slot can't be. */
    internal fun fill(text: String, me: Speaker, you: Speaker): String? {
        if ('{' !in text) return text
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '{') { sb.append(c); i++; continue }
            val end = text.indexOf('}', i)
            if (end < 0) return null
            val value = when (text.substring(i + 1, end)) {
                "me" -> me.first
                "you" -> you.first
                "place" -> place
                "Place" -> place.replaceFirstChar { it.uppercase() }
                "myhome" -> Races.home(me.race)
                "yourhome" -> Races.home(you.race)
                "yourkind" -> Races.plural(you.race)
                "kin" -> when (you.female) { true -> "sister"; false -> "brother"; null -> null }
                else -> null
            } ?: return null
            sb.append(value)
            i = end + 1
        }
        return sb.toString()
    }

    internal class Beat(val gesture: Int, val text: String)
    /** One exchange: [parts] spoken in turn, the first by its leader; each part is a choice of lines. */
    internal class Exchange(val key: String, val parts: List<List<Beat>>)
    internal class Topic(val name: String, val exchanges: List<Exchange>)

    companion object {
        /** Keys remembered: none of them is said again until it falls out. */
        const val RECENT = 24
        const val SWITCH_TOPIC = 0.3f
        const val NOD_CHANCE = 0.45f
        /** Exchanges in a conversation (two to six, the shorter likelier). */
        private val EXCHANGES = intArrayOf(2, 2, 3, 3, 3, 4, 4, 5, 6)

        /** "the Stormwind Embassy", "Dalaran's streets", "the plaza below...": a place's name as said in a sentence. */
        fun placeRef(name: String): String {
            val n = name.trim()
            if (n.isEmpty()) return "this place"
            val firstWord = n.substringBefore(' ')
            return when {
                firstWord == "The" || firstWord == "A" || firstWord == "An" -> firstWord.lowercase() + n.substring(firstWord.length)
                firstWord.endsWith("'") || firstWord.endsWith("'s") || firstWord.endsWith("’s") -> n
                else -> "the $n"
            }
        }

        private fun t(text: String) = Beat(Gestures.TALK, text)
        private fun q(text: String) = Beat(Gestures.QUESTION, text)
        private fun x(text: String) = Beat(Gestures.EXCLAIM, text)
        private fun l(text: String) = Beat(Gestures.LAUGH, text)
        private fun w(text: String) = Beat(Gestures.WAVE, text)
        private fun ex(key: String, vararg parts: List<Beat>) = Exchange(key, parts.toList())

        internal val GREETINGS = listOf(
            w("{you}! There you are."),
            w("Well, if it isn't {you}."),
            t("Evening, {you}. Have you a moment?"),
            w("{you}, over here!"),
            t("Ah, {you}. Just the face I hoped to see."),
            q("Mind if I join you, {you}?"),
            t("{you}, you look like you've been on your feet all day."),
            w("Hail, {kin}."),
            t("There's a familiar face. How are you keeping, {you}?"),
            x("{you}! I've been looking all over for you."),
        )
        internal val ANSWERS = listOf(
            t("{you}! Good to see you."),
            w("{you}! Come, come."),
            t("For you? Always."),
            t("Barely standing, {you}. But standing."),
            l("Ha! I was wondering when you'd turn up."),
            t("{you}. I was just thinking about you."),
            q("Oh? What brings you over?"),
            t("Can't complain. Well, I can. I won't."),
            w("Hail, {kin}. Pull up a bit of wall."),
        )
        internal val FAREWELLS = listOf(
            t("Well, I'd best be off."),
            w("Take care of yourself, {you}."),
            t("I've kept you long enough."),
            t("Back to it, I suppose."),
            w("Until next time, {you}."),
            x("Is that the bell? I'm late!"),
            t("I should let you get on."),
        )
        internal val PARTINGS = listOf(
            w("Safe roads, {you}."),
            w("And you. Don't be a stranger."),
            t("Never too long. Go on, then."),
            l("Back to it. Same time tomorrow?"),
            w("Until then."),
            l("You're always late. Go!"),
            t("Mind how you go, {kin}."),
        )

        internal val TOPICS = listOf(
            Topic("weather", listOf(
                ex("weather.wind",
                    listOf(q("Did you feel that wind this morning?")),
                    listOf(t("Cut right through my cloak. Spring, they call it."), l("I stopped feeling my ears by noon."))),
                ex("weather.rain",
                    listOf(t("The rain's finally let up.")),
                    listOf(t("For an hour, maybe. Look at those clouds."), t("Good. The gardens needed it."))),
                ex("weather.storm",
                    listOf(q("Think it'll storm tonight?")),
                    listOf(t("The old sailors by the docks swear it will."), t("My knee says yes. My knee is rarely wrong."))),
                ex("weather.sun",
                    listOf(x("Sun's out! Someone mark the calendar.")),
                    listOf(l("Don't say it so loud. It'll hear you."), t("Enjoy it while it lasts. It never does."))),
                ex("weather.fog",
                    listOf(t("The fog's thick enough to lose a gryphon in.")),
                    listOf(t("Someone did, last week. Took two days to find her.")),
                    listOf(l("Was she lost, or just hiding from her rider?"))),
            )),
            Topic("work", listOf(
                ex("work.how",
                    listOf(q("How's the work treating you?")),
                    listOf(t("Long hours, short pay. The usual."), t("Better, now the new quartermaster has the ledgers in order."),
                        t("Don't ask. Please. Don't ask."))),
                ex("work.letter",
                    listOf(t("I've been copying the same letter all morning.")),
                    listOf(q("The same letter? Whatever for?")),
                    listOf(t("Someone keeps spilling ink on the last page. I suspect it's me."))),
                ex("work.watch",
                    listOf(t("They've put me on the night watch again.")),
                    listOf(t("Bring a flask. And a second cloak."), q("Again? Who did you upset this time?"))),
                ex("work.report",
                    listOf(x("I finished my report at last!")),
                    listOf(l("Then the next one's already on your desk, I'd wager."), t("Well done. Now nobody will read it."))),
                ex("work.merchant",
                    listOf(q("Still working for that dwarven merchant?")),
                    listOf(t("Still. He pays in coin and complaints, in equal measure."))),
                ex("work.boots",
                    listOf(t("I've walked this hall so often I could do it asleep.")),
                    listOf(l("I think you have, once or twice."))),
            )),
            Topic("tavern", listOf(
                ex("tavern.drink",
                    listOf(q("Fancy a drink once the bells ring?")),
                    listOf(t("Only if you're buying."), x("I thought you'd never ask."), t("Just the one. I mean it this time."))),
                ex("tavern.ale",
                    listOf(t("The innkeeper's watered the ale again.")),
                    listOf(l("He swears he hasn't. The ale disagrees."), t("Then we drink twice as much. Simple."))),
                ex("tavern.bard",
                    listOf(q("Did you hear the bard last night?")),
                    listOf(l("Hear him? Half the district heard him."), t("I've heard worse. Not often, but I have."))),
                ex("tavern.arm",
                    listOf(t("Someone challenged a tauren to an arm-wrestle at the inn last night.")),
                    listOf(q("Let me guess how that ended.")),
                    listOf(l("With a broken table and a round on the house."))),
                ex("tavern.cards",
                    listOf(q("Are you still owed for that card game?")),
                    listOf(t("Six silver and an apology. I'll settle for the silver."))),
            )),
            Topic("embassy", listOf(
                ex("embassy.busy",
                    listOf(t("Busy day at {place}.")),
                    listOf(t("Every envoy in the kingdom wants a signature before supper."), t("Busy? I've seen quieter battlefields."))),
                ex("embassy.word",
                    listOf(q("Any word from upstairs?")),
                    listOf(t("Only that we're to keep our voices down and our boots clean."), t("Still in talks. Still talking. Always talking."))),
                ex("embassy.peace",
                    listOf(t("Strange times, when old enemies share a hallway.")),
                    listOf(t("Stranger still that nobody's drawn steel yet."), t("Peace suits us all. Mostly."))),
                ex("embassy.archive",
                    listOf(q("Have you been up to the archives yet?")),
                    listOf(t("Twice. I still can't find the door I came in by."))),
                ex("embassy.seal",
                    listOf(t("Someone's misplaced the wax seal again.")),
                    listOf(l("Check the steward's pockets. It's always the steward's pockets."))),
            )),
            Topic("home", listOf(
                ex("home.miss",
                    listOf(q("Do you miss {yourhome}?")),
                    listOf(t("Every morning. Then I remember the stairs."), t("Some days. The food, mostly."))),
                ex("home.letter",
                    listOf(t("I've a letter from {myhome}. My sister's getting married.")),
                    listOf(x("Married! Give her my best."), q("To whom? Not that fisherman?"))),
                ex("home.kind",
                    listOf(q("Is it true what they say about {yourkind}?")),
                    listOf(q("Depends. What do they say?")),
                    listOf(l("That you never lose an argument."), t("That you cook better than anyone."))),
                ex("home.canals",
                    listOf(t("They say the canals in Stormwind froze over this winter.")),
                    listOf(t("Children skating all the way to the cathedral, I heard."))),
                ex("home.visit",
                    listOf(q("Will you go back to {myhome} when this is done?")),
                    listOf(t("If there's still a bed there for me."))),
            )),
            Topic("gossip", listOf(
                ex("gossip.captain",
                    listOf(t("You didn't hear this from me, but the captain's courting someone.")),
                    listOf(q("The captain? Who'd have him?")),
                    listOf(l("Light help them both."))),
                ex("gossip.saddle",
                    listOf(q("Did you hear about the stable hand and the missing saddle?")),
                    listOf(t("Heard it three ways already. Each one worse."))),
                ex("gossip.sheep",
                    listOf(t("Word is a mage turned his apprentice into a sheep. Again.")),
                    listOf(l("Third time this month. The poor lad bleats in his sleep."))),
                ex("gossip.flowers",
                    listOf(t("Someone's been leaving flowers on the steps every morning.")),
                    listOf(q("Flowers? For whom?")),
                    listOf(t("That's the mystery, isn't it."))),
                ex("gossip.cook",
                    listOf(t("The cook and the quartermaster aren't speaking.")),
                    listOf(q("Over what?")),
                    listOf(l("A turnip. Don't ask me which one."))),
            )),
            Topic("jokes", listOf(
                ex("jokes.gnome",
                    listOf(q("How many gnomes does it take to light a lamp?")),
                    listOf(q("How many?")),
                    listOf(l("Six. One to light it, five to improve it until it explodes."))),
                ex("jokes.dwarf",
                    listOf(t("A dwarf walks into a tavern...")),
                    listOf(q("And?")),
                    listOf(l("And walks out three days later, singing."))),
                ex("jokes.kobold",
                    listOf(q("What's the difference between a kobold and a candle?")),
                    listOf(q("Go on.")),
                    listOf(l("One of them knows where it's going."))),
                ex("jokes.murloc",
                    listOf(q("Why don't murlocs keep secrets?")),
                    listOf(q("Why?")),
                    listOf(l("Everyone can hear them coming."))),
                ex("jokes.ogre",
                    listOf(t("I tried to teach an ogre to count.")),
                    listOf(q("How far did he get?")),
                    listOf(l("Two. Then he ate the abacus."))),
            )),
            Topic("food", listOf(
                ex("food.bread",
                    listOf(q("Have you tried the new baker's bread?")),
                    listOf(t("Tried it. Nearly lost a tooth on it."), t("Every morning. I'm not proud."))),
                ex("food.stew",
                    listOf(t("I'd trade my boots for a proper stew right now.")),
                    listOf(l("You'd walk home barefoot, then.")),
                    listOf(t("Worth it."))),
                ex("food.pie",
                    listOf(t("There's a pie cart by the gate today.")),
                    listOf(x("Then why are we still standing here?"))),
                ex("food.cheese",
                    listOf(q("Who left the cheese on the windowsill?")),
                    listOf(t("Not me. Though I did have a look at it."))),
            )),
            Topic("road", listOf(
                ex("road.out",
                    listOf(q("Still thinking of heading out on the road?")),
                    listOf(t("As soon as the passes clear."), t("Thinking, yes. Doing, not yet."))),
                ex("road.patrol",
                    listOf(t("A patrol came in this morning, mud to the eyebrows.")),
                    listOf(q("Murlocs?")),
                    listOf(t("Worse. Swamp."))),
                ex("road.dragon",
                    listOf(t("My cousin swears she saw a dragon over the hills.")),
                    listOf(l("Your cousin swears she saw a lot of things."))),
                ex("road.map",
                    listOf(t("I bought a map off a goblin yesterday.")),
                    listOf(q("Does it show anything real?")),
                    listOf(t("The price, at least. That was very real."))),
            )),
            Topic("magic", listOf(
                ex("magic.tea",
                    listOf(t("I tried a cantrip to warm my tea this morning.")),
                    listOf(q("And?")),
                    listOf(l("Now I need a new cup. And a new table."))),
                ex("magic.portal",
                    listOf(q("Ever taken a portal on a full stomach?")),
                    listOf(t("Once. Never again."))),
                ex("magic.book",
                    listOf(t("The book I borrowed keeps whispering at night.")),
                    listOf(t("Return it, then. Quickly."), l("Mine only hums. You got the better one."))),
            )),
        )

        /** What the controlled NPC can say (the cast bar's Say). */
        internal val SAYINGS = listOf(
            "Hello there!",
            "Good to see you all.",
            "Lovely weather today.",
            "Anyone for a drink?",
            "I could use a rest.",
            "Busy day at {place}.",
            "Has anyone seen my book?",
            "Ha! That's a good one.",
            "Watch your step out there.",
            "Not now, I'm busy.",
            "Farewell, friends.",
        )
    }
}

/** The playable races as small talk names them (ChrRaces ids): a plural and a home. Unknown: null. */
object Races {
    private class Race(val plural: String, val home: String)
    private val BY_ID = mapOf(
        1 to Race("humans", "Stormwind"), 2 to Race("orcs", "Orgrimmar"), 3 to Race("dwarves", "Ironforge"),
        4 to Race("night elves", "Darnassus"), 5 to Race("the Forsaken", "the Undercity"), 6 to Race("tauren", "Thunder Bluff"),
        7 to Race("gnomes", "Gnomeregan"), 8 to Race("trolls", "the Echo Isles"), 9 to Race("goblins", "Bilgewater Harbor"),
        10 to Race("blood elves", "Silvermoon"), 11 to Race("draenei", "the Exodar"), 22 to Race("worgen", "Gilneas"),
        24 to Race("pandaren", "the Wandering Isle"), 25 to Race("pandaren", "the Wandering Isle"), 26 to Race("pandaren", "the Wandering Isle"),
        27 to Race("nightborne", "Suramar"), 28 to Race("Highmountain tauren", "Highmountain"), 29 to Race("void elves", "Telogrus"),
        30 to Race("Lightforged draenei", "the Vindicaar"), 31 to Race("Zandalari", "Zuldazar"), 32 to Race("Kul Tirans", "Boralus"),
        34 to Race("Dark Iron dwarves", "Shadowforge City"), 35 to Race("vulpera", "Vol'dun"), 36 to Race("Mag'har", "Nagrand"),
        37 to Race("mechagnomes", "Mechagon"), 52 to Race("dracthyr", "the Forbidden Reach"), 70 to Race("dracthyr", "the Forbidden Reach"),
        84 to Race("earthen", "Dornogal"), 85 to Race("earthen", "Dornogal"),
    )

    fun plural(race: Int): String? = BY_ID[race]?.plural
    fun home(race: Int): String? = BY_ID[race]?.home

    /** About where a race's head is above its feet, standing (yards): the speech bubble's anchor. */
    fun headHeight(race: Int): Float = when (race) {
        7, 9, 35, 37 -> 1.15f                 // gnomes, goblins, vulpera, mechagnomes
        3, 34, 84, 85 -> 1.5f                 // dwarves, Dark Irons, earthen
        6, 28, 11, 30, 32, 31, 36 -> 2.55f    // tauren, draenei, Kul Tirans, Zandalari, Mag'har
        22, 24, 25, 26, 2 -> 2.25f            // worgen, pandaren, orcs
        else -> 2.05f
    }
}
