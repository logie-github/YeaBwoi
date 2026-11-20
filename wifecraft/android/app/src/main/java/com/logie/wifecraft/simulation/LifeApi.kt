package com.logie.wifecraft.simulation

/*
 * The life simulation's API for the screen (pinned: the UI is built against exactly this).
 *
 * SETUP
 *   val life = LifeSim(castIds, profiles = cast.associate { it.id to LifeProfile(name, raceId, female) }, seed = sceneSeed)
 *   LiveScene(..., lifeSim = life)            // attached to the scene's simulation; null: no life (as before)
 *   life.load(savedJson)                      // before the scene runs, or later through live.life.load(json)
 *   The Settings switch "Life simulation" (data/Settings.lifeSimulation, on by default) decides whether to pass one at all,
 *   or LiveScene.life.setEnabled(false) turns it off in a running scene (needs stand still, NPCs choose as before).
 *
 * READING (SceneSnapshot.life: LifeView?, null without life; a new snapshot comes when it changed, at most every 0.5 s)
 *   LifeView.enabled, speed (LifeSpeed), day (1, 2, ...), hourOfDay (0 until 24), lastResult (the player's last
 *   interaction's answer), events (the feed, newest first: LifeEvent(hours, text, icon)).
 *   LifeView.people: per person (LifePersonView):
 *     id; needs: 7 values 0..1 in Need order (ENERGY, HUNGER, THIRST, SOCIAL, FUN, HYGIENE, COMFORT; Need.wow is the bar's
 *     title "Rested", Need.icon its icon key); mood (Mood: label, icon); moodScore (-100..100); thought (LifeThought:
 *     iconKey, text; null: no bubble now); traits (Trait: label, description, icon); wish (a short goal, "wants a hot
 *     meal"); relationships (RelationshipView: other, friendship and romance -100..100, friendTier "Friendly",
 *     romanceTier "Sweethearts"); frozen; doing (the InteractionId life counts them doing, or null); moodlets (labels of
 *     what colours their mood: "Well Rested", "Had an Argument").
 *
 * COMMANDS (LiveScene.life: LifeControl; any thread; run at the start of the next frame on the GL thread)
 *   interact(a, b, id, result)   the player's radial menu. b null: a solo one (InteractionId.SOLO_MENU: Rest, Eat, Drink,
 *                                Read, Dance, Wash, Wander); b set: a social one (InteractionId.SOCIAL_MENU: Chat, Tell a
 *                                joke, Compliment, Hug, Flirt, Kiss, Argue, Invite to sit, Dance together, Give a gift).
 *                                The other accepts or refuses by their standing, mood and traits; the answer (LifeResult:
 *                                accepted, message) becomes the scene's notice, comes back in LifeView.lastResult and, when
 *                                given, to [result] (called on the GL thread). It is the player's order: what both were
 *                                doing stops, and life does not cut it short. The player's own Play animation and Send to
 *                                partner are never held back by relationships.
 *   menu(a, b, result)           which of the menu's interactions can be had here now (Wash needs a basin...), to grey them.
 *   setSpeed(LifeSpeed)          PAUSE, NORMAL (x1: a game minute a real second, a day in 24 minutes), FAST (x3), FASTEST
 *                                (x6). With a world clock driving the time (setTimeSource), LifeSim.onSpeed passes it on.
 *   setEnabled(on), freeze(id, on) (that NPC's needs stand still), setProps(list) (fireplaces, basins, bookshelves a location
 *   names), setTimeSource(LifeTime?) (the world's day-and-night clock drives the hours; null: life's own clock).
 *   save(out)                    the whole life as JSON (to [out], GL thread): keep it per cast in LifeSim.fileName(ids).
 *   load(json)                   takes a saved life back.
 */

/** Everything the screen shows of the life simulation ([LifeSim.view]). */
data class LifeView(
    val enabled: Boolean,
    val speed: LifeSpeed,
    val day: Int,
    val hourOfDay: Double,
    val people: List<LifePersonView>,
    /** The feed, newest first. */
    val events: List<LifeEvent>,
    val lastResult: LifeResult?,
) {
    fun person(id: String): LifePersonView? = people.firstOrNull { it.id == id }
}

/** One person's life: see the header. [needs] in [Need] order. */
data class LifePersonView(
    val id: String,
    val needs: List<Float>,
    val mood: Mood,
    val moodScore: Int,
    val thought: LifeThought?,
    val traits: List<Trait>,
    val wish: String?,
    val relationships: List<RelationshipView>,
    val frozen: Boolean,
    val doing: InteractionId?,
    val moodlets: List<String>,
) {
    fun need(need: Need): Float = needs.getOrElse(need.ordinal) { 0f }
}

/** What this person is to [other]. */
data class RelationshipView(val other: String, val friendship: Int, val romance: Int, val friendTier: String, val romanceTier: String)

/** The life commands of a [LiveScene] (see the header). Any thread. */
class LifeControl internal constructor(private val scene: LiveScene) {
    fun interact(a: String, b: String?, id: InteractionId, result: ((LifeResult) -> Unit)? = null) {
        scene.order { sim ->
            val r = sim.lifeInteract(a, b, id)
            result?.invoke(r)
            r.message
        }
    }

    fun menu(a: String, b: String?, result: (List<Pair<InteractionId, Boolean>>) -> Unit) {
        scene.command { sim -> result(sim.lifeMenu(a, b)) }
    }

    fun setSpeed(speed: LifeSpeed) = scene.command { it.life?.setSpeed(speed) }
    fun setEnabled(on: Boolean) = scene.command { it.life?.enabled = on }
    fun freeze(id: String, frozen: Boolean) = scene.command { it.life?.freeze(id, frozen) }
    fun setProps(props: List<LifeProp>) = scene.command { it.life?.props = props }
    fun setTimeSource(source: LifeTime?) = scene.command { it.life?.clock?.source = source }
    fun save(out: (String) -> Unit) = scene.command { sim -> sim.life?.let { out(it.toJson()) } }
    fun load(json: String) = scene.command { it.life?.load(json) }
}
