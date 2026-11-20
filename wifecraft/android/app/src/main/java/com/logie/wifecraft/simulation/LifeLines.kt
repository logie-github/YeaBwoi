package com.logie.wifecraft.simulation

import kotlin.random.Random

/**
 * The life simulation's words ([LifeSim]): the thoughts over an NPC's head (one for each need running low, for moods, for
 * nothing to be had: no bed free) and the short exchanges of the social interactions the small talk ([Chatter]) does not
 * cover: compliments, flirting, a quarrel, a gift, a hug with no animation for it, an invitation to sit, and refusals.
 * Original lines in a Warcraft voice; `{you}` is the other's first name, `{me}` the speaker's. Pure JVM.
 */
object LifeLines {
    /** Thoughts when [need] runs low, by need (the icon is the need's). */
    fun needThoughts(need: Need): List<String> = when (need) {
        Need.ENERGY -> listOf("Is it nap time?", "My eyelids weigh more than my pack.", "I could sleep on a gryphon in flight.",
            "Just a short rest... just a short one.")
        Need.HUNGER -> listOf("Something smells good. Is it me? It isn't me.", "I'd trade my hearthstone for a hot meal.",
            "A loaf of bread and a wedge of cheese. That's all I ask.", "My stomach's louder than a murloc.")
        Need.THIRST -> listOf("I could use a drink.", "A cup of something cold. Anything.", "Dry as the Barrens in here.",
            "Is there a fountain in this city that isn't magic?")
        Need.SOCIAL -> listOf("Wouldn't mind a bit of company.", "Somebody talk to me. Anybody.", "Too quiet in here.",
            "I wonder what everyone's up to.")
        Need.FUN -> listOf("Bored. So very bored.", "Is there nothing to do in this city?", "I need a song, or a dance, or a fight.",
            "If I count the floorboards one more time...")
        Need.HYGIENE -> listOf("I smell like the road.", "A basin of warm water would be bliss.", "Is that me, or the stables?")
        Need.COMFORT -> listOf("My feet are killing me.", "I need to sit down.", "Could do with a soft chair.")
    }

    /** Thoughts when the NPC wants something the room hasn't got (or someone else holds). */
    fun missing(id: InteractionId): String = when (id) {
        InteractionId.SLEEP, InteractionId.NAP, InteractionId.REST -> "Not a free bed in sight."
        InteractionId.WASH -> "No basin here. Later, then."
        InteractionId.WARM -> "If only there were a fire."
        InteractionId.READ -> "Not a book to be had."
        InteractionId.RELAX, InteractionId.INVITE_SIT -> "Every seat's taken."
        else -> "Nothing for it here."
    }

    /** A thought for a good mood. */
    fun moodThoughts(mood: Mood): List<String> = when (mood) {
        Mood.HAPPY -> listOf("What a fine day.", "Light, but I'm in a good mood.", "Everything's going my way.")
        Mood.CONTENT -> listOf("Not bad. Not bad at all.", "This is nice.", "A quiet moment. I'll take it.")
        Mood.INSPIRED -> listOf("I have an idea!", "That book gave me a thought or two.", "I could write a ballad about this.")
        Mood.PLAYFUL -> listOf("Who wants to dance?", "I know a joke about a gnome and a goblin...", "Let's have some fun!")
        Mood.FLIRTY -> listOf("{you} looks lovely today.", "Was that a wink? I think that was a wink.", "My heart's all aflutter.")
        Mood.IRRITABLE -> listOf("Don't. Just don't.", "Everyone's getting on my nerves.", "Hmph.")
        Mood.SAD -> listOf("Nothing's going right.", "I miss home.", "What a miserable day.")
        Mood.FINE -> listOf("Hm.", "Where was I?", "What to do, what to do...")
        else -> emptyList()
    }

    /** Thoughts while doing [id] (the bubble shows what they enjoy). */
    fun doing(id: InteractionId): String? = when (id) {
        InteractionId.SLEEP -> "Zzz..."
        InteractionId.NAP -> "Just resting my eyes."
        InteractionId.EAT -> "Mm. Good bread."
        InteractionId.DRINK -> "Ahh. That hits the spot."
        InteractionId.READ -> "One more chapter."
        InteractionId.DANCE -> "Light on my feet!"
        InteractionId.WARM -> "Nothing like a fire."
        InteractionId.WASH -> "Much better."
        else -> null
    }

    /** The two-line exchange for a social interaction: the asker's line and the answer (accepted or not). */
    fun exchange(id: InteractionId, accepted: Boolean, random: Random): Pair<String, String> {
        val asks = asks(id)
        val answers = if (accepted) yes(id) else no(id)
        return asks[random.nextInt(asks.size)] to answers[random.nextInt(answers.size)]
    }

    private fun asks(id: InteractionId): List<String> = when (id) {
        InteractionId.COMPLIMENT -> listOf("You've a good eye for armor, {you}.", "That color suits you, {you}.",
            "You fought well out there. I noticed.", "You always know what to say, {you}.")
        InteractionId.FLIRT -> listOf("Is it warm in Dalaran, or is it just you?", "I'd brave Icecrown for a smile like that.",
            "Walk with me a while, {you}?", "You've stolen something of mine, {you}. I think it was my heart.")
        InteractionId.KISS -> listOf("Come here, you.", "Close your eyes, {you}.")
        InteractionId.HUG -> listOf("Come here, {you}!", "I'm glad you're here.")
        InteractionId.ARGUE -> listOf("You took the last of the bread, didn't you?", "That's not how it happened, {you}, and you know it.",
            "Must you hum like that?", "You owe me five silver, {you}.")
        InteractionId.GIVE_GIFT -> listOf("I found this in the market. Thought of you.", "A little trinket. For luck.",
            "Here. It's not much, but it's yours.")
        InteractionId.INVITE_SIT -> listOf("Sit with me a while, {you}?", "Come rest your feet.")
        InteractionId.DANCE_TOGETHER -> listOf("Dance with me, {you}!", "Shall we?")
        InteractionId.JOKE -> listOf("Why did the gnome cross the road? He was too short to see it.")
        InteractionId.PLAY_TOGETHER -> listOf("Come on, {you}!")
        InteractionId.CHAT -> listOf("Hey, {you}. How goes it?", "Well met, {you}.", "{you}! Got a moment?")
        else -> listOf("Hey, {you}.")
    }

    private fun yes(id: InteractionId): List<String> = when (id) {
        InteractionId.COMPLIMENT -> listOf("You're too kind.", "Why, thank you, {you}.", "Ha! Flattery will get you everywhere.")
        InteractionId.FLIRT -> listOf("Careful, {you}. I might believe you.", "Oh, you.", "Is that so?")
        InteractionId.KISS -> listOf("Mm.", "Finally.")
        InteractionId.HUG -> listOf("Oof! Good to see you too.", "Don't let go just yet.")
        InteractionId.ARGUE -> listOf("Oh, here we go.", "Says you!", "I did no such thing!")
        InteractionId.GIVE_GIFT -> listOf("For me? You shouldn't have!", "I'll treasure it.", "It's lovely, {you}.")
        InteractionId.INVITE_SIT -> listOf("Gladly.", "My feet thank you.")
        InteractionId.DANCE_TOGETHER -> listOf("Lead the way!", "Try to keep up.")
        InteractionId.CHAT -> listOf("Not bad, {you}. Not bad.", "Well met. Pull up a chair.", "Always, for you.")
        else -> listOf("Sure.")
    }

    private fun no(id: InteractionId): List<String> = when (id) {
        InteractionId.FLIRT -> listOf("Let's keep it friendly, {you}.", "I... no. Sorry.", "Not today.")
        InteractionId.KISS -> listOf("Whoa there.", "Not here, {you}.", "I'm not ready for that.")
        InteractionId.HUG -> listOf("Maybe later.", "I'm not much of a hugger.")
        InteractionId.DANCE_TOGETHER -> listOf("My feet are too sore.", "Not now, {you}.")
        InteractionId.INVITE_SIT -> listOf("I'd rather stand.", "Another time.")
        InteractionId.GIVE_GIFT -> listOf("I can't accept this.", "Keep it, {you}.")
        InteractionId.COMPLIMENT -> listOf("Hmph.", "Save it.")
        else -> listOf("Not now.", "Leave me be, {you}.", "I'm not in the mood.")
    }

    /** Where a nap was had, for the feed: "on the bed", "in the chair", "on the floor". */
    fun onWhat(item: SceneObject?): String = when (item) {
        SceneObject.BED -> "on the bed"
        SceneObject.COUCH -> "on the couch"
        SceneObject.CHAIR -> "in the chair"
        SceneObject.TABLE -> "at the table"
        null -> "on the floor"
    }

    /** What someone says leaving a conversation of their own because [need] can't wait. */
    fun excuse(need: Need, random: Random): String = pick(when (need) {
        Need.ENERGY -> listOf("I can barely keep my eyes open. Forgive me.", "I need to rest. Another time?")
        Need.HUNGER -> listOf("I should get something to eat.", "Excuse me, my stomach's calling.")
        Need.THIRST -> listOf("I need a drink. Excuse me.", "Hold that thought, I'm parched.")
        Need.HYGIENE -> listOf("I really must wash up.")
        Need.COMFORT -> listOf("I have to sit down. Excuse me.")
        else -> listOf("I should go.")
    }, random) ?: "I should go."

    /** [text] with {you} and {me} filled in. */
    fun fill(text: String, me: String, you: String): String = text.replace("{you}", you).replace("{me}", me)

    fun pick(list: List<String>, random: Random): String? = if (list.isEmpty()) null else list[random.nextInt(list.size)]
}
