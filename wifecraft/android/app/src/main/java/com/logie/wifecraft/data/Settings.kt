package com.logie.wifecraft.data

import android.content.Context
import com.logie.wifecraft.simulation.CinematicSettings
import com.logie.wifecraft.simulation.ClockSettings
import com.logie.wifecraft.simulation.ImmersiveSettings

/** The reader's settings, kept between runs. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("wifecraft", Context.MODE_PRIVATE)

    /** Characters per second the words appear at; 0 shows a line at once. */
    var textSpeed: Int
        get() = prefs.getInt("textSpeed", 60)
        set(v) = prefs.edit().putInt("textSpeed", v).apply()
    /** "dark" (Legion's talking head) or "parchment". */
    var boxStyle: String
        get() = prefs.getString("boxStyle", "dark") ?: "dark"
        set(v) = prefs.edit().putString("boxStyle", v).apply()
    /** The music's and the sounds' loudness, 0 to 100. */
    var musicVolume: Int
        get() = prefs.getInt("musicVolume", 70)
        set(v) = prefs.edit().putInt("musicVolume", v).apply()
    var soundVolume: Int
        get() = prefs.getInt("soundVolume", 85)
        set(v) = prefs.edit().putInt("soundVolume", v).apply()
    /** The emotes' voices in a scene (SceneAudio's voices), 0 to 100; the Sounds level until it is set. */
    var voiceVolume: Int
        get() = prefs.getInt("voiceVolume", soundVolume)
        set(v) = prefs.edit().putInt("voiceVolume", v).apply()
    /** The places' ambience in a scene (SceneAudio's ambience), 0 to 100; the Sounds level until it is set. */
    var ambienceVolume: Int
        get() = prefs.getInt("ambienceVolume", soundVolume)
        set(v) = prefs.edit().putInt("ambienceVolume", v).apply()
    /** Partner animations' sounds (the user's own sound files, PartnerSounds): on or off, and their loudness 0 to 100. */
    var partnerSoundsOn: Boolean
        get() = prefs.getBoolean("partnerSoundsOn", true)
        set(v) = prefs.edit().putBoolean("partnerSoundsOn", v).apply()
    var partnerSoundVolume: Int
        get() = prefs.getInt("partnerSoundVolume", 85).coerceIn(0, 100)
        set(v) = prefs.edit().putInt("partnerSoundVolume", v.coerceIn(0, 100)).apply()
    /** How often idle NPCs go to a partner animation: a PartnerFrequency name (read back with PartnerFrequency.of; Sometimes by default). */
    var partnerFrequency: String
        get() = prefs.getString("partnerFrequency", "SOMETIMES") ?: "SOMETIMES"
        set(v) = prefs.edit().putString("partnerFrequency", v).apply()
    /** How often idle NPCs go and talk to each other (speech bubbles): a ChatterFrequency name (ChatterFrequency.of; Sometimes by default). */
    var chatterFrequency: String
        get() = prefs.getString("chatterFrequency", "SOMETIMES") ?: "SOMETIMES"
        set(v) = prefs.edit().putString("chatterFrequency", v).apply()
    /** The live scene's life simulation (needs, moods, relationships: simulation/LifeSim): on by default. */
    var lifeSimulation: Boolean
        get() = prefs.getBoolean("lifeSimulation", true)
        set(v) = prefs.edit().putBoolean("lifeSimulation", v).apply()
    /** The world clock's settings (Options' Time page: speed, start hour, day/night cycle), kept as ClockSettings' JSON. */
    var clock: ClockSettings
        get() = ClockSettings.parse(prefs.getString("clock", null))
        set(v) = prefs.edit().putString("clock", v.toJson().toString()).apply()
    /** Cinematic mode's settings (Options' Cinematic page: lighting, camera style and speed, shot lengths, letterbox, grain, words), as JSON. */
    var cinematic: CinematicSettings
        get() = CinematicSettings.parse(prefs.getString("cinematic", null))
        set(v) = prefs.edit().putString("cinematic", v.toJson().toString()).apply()
    /**
     * Immersive mode's settings (Options' Immersion page: the life HUD, the HUD's time, Auto flow, undressing, body language,
     * motes, the camera's sway, atmosphere and voice), as JSON. The first read after the update moves everyone to immersive
     * mode once (ImmersiveSettings.migrate: the life HUD hidden); every other option of theirs is kept as it was.
     */
    var immersive: ImmersiveSettings
        get() {
            val stored = prefs.getString("immersive", null)
            ImmersiveSettings.migrate(stored)?.let { prefs.edit().putString("immersive", it).apply(); return ImmersiveSettings.parse(it) }
            return ImmersiveSettings.parse(stored)
        }
        set(v) = prefs.edit().putString("immersive", v.toJson().toString()).apply()
    /** What each NPC keeps on when the scene's flow undresses them (equipment slots, by NPC id); none kept by default. */
    fun keepOn(npcId: String): Set<Int> =
        prefs.getString("keepOn.$npcId", null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: keepOnAll
    fun setKeepOn(npcId: String, slots: Set<Int>) = prefs.edit().putString("keepOn.$npcId", slots.sorted().joinToString(",")).apply()
    /** What every NPC without a list of their own keeps on (Options' Immersion page's Keep on chips). */
    var keepOnAll: Set<Int>
        get() = prefs.getString("keepOnAll", "")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet().orEmpty()
        set(v) = prefs.edit().putString("keepOnAll", v.sorted().joinToString(",")).apply()
    /** Files fetched at once by the first launch's gathering: Gentle 24, Normal 48, Fast 96 (the ceiling; it backs off by itself). */
    var downloadSpeed: Int
        get() = prefs.getInt("downloadSpeed", FAST).let { if (it in CHOICES) it else FAST }
        set(v) = prefs.edit().putInt("downloadSpeed", v).apply()
    /** The first launch's gathering waits for Wi-Fi (a look's own files are fetched as it is shown, on any connection). */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(v) = prefs.edit().putBoolean("wifiOnly", v).apply()

    /** Options → Mods → Dialogue model: the on-device language model that drives the NPCs' talk (dialogue/DialogueModels DialogueConfig), as JSON. */
    var dialogue: com.logie.wifecraft.dialogue.DialogueConfig
        get() = com.logie.wifecraft.dialogue.DialogueConfig.parse(prefs.getString("dialogue", null))
        set(v) = prefs.edit().putString("dialogue", v.toJson().toString()).apply()

    /** Options' Soft body: 0 Off, 1 Subtle, 2 Natural (the default), 3 Strong (engine/Native.SOFT_*). */
    var softBody: Int
        get() = prefs.getInt("softBody", SOFT_NATURAL).let { if (it in 0..3) it else SOFT_NATURAL }
        set(v) = prefs.edit().putInt("softBody", v).apply()

    /** Options' Body parts → Arousal (bodyparts/Arousal ArousalMode): Automatic (the default), Always flaccid, Always erect. */
    var partArousal: com.logie.wifecraft.bodyparts.ArousalMode
        get() = com.logie.wifecraft.bodyparts.ArousalMode.of(prefs.getString("partArousal", null))
        set(v) = prefs.edit().putString("partArousal", v.name).apply()
    /** Each NPC's arousal override (the scene's switch: Auto, On, Off; by NPC id; Auto when never set). */
    fun partOverride(npcId: String): com.logie.wifecraft.bodyparts.PartOverride =
        com.logie.wifecraft.bodyparts.PartOverride.of(prefs.getString("partOverride.$npcId", null))
    fun setPartOverride(npcId: String, o: com.logie.wifecraft.bodyparts.PartOverride) =
        if (o == com.logie.wifecraft.bodyparts.PartOverride.AUTO) prefs.edit().remove("partOverride.$npcId").apply()
        else prefs.edit().putString("partOverride.$npcId", o.name).apply()

    /** The imported body-part set the men wear (files/bodyparts/<set>, Options' Body parts); null: none imported. */
    var partSet: String?
        get() = prefs.getString("partSet", null)?.takeIf { it.isNotEmpty() }
        set(v) = prefs.edit().putString("partSet", v ?: "").apply()

    /**
     * Options' "Use the Eve body for new women" (bodyparts/EveDress): a woman with no Eve choice of her own (a new NPC, a named
     * character, a starter, an NPC saved before) is drawn with the imported Eve body. Off by default. The set itself is
     * files/bodyparts/eve (EveStore: imported, its status read from its set.json).
     */
    var eveDefault: Boolean
        get() = prefs.getBoolean("eveDefault", false)
        set(v) = prefs.edit().putBoolean("eveDefault", v).apply()

    /**
     * Options' HD Models switch (bodyparts/HdModels): with it on, every race and sex with an imported HD set is drawn as its
     * HD figure instead of the game's model. On by default: importing a set is the choice; the switch turns them all off.
     */
    var hdModels: Boolean
        get() = prefs.getBoolean("hdModels", true)
        set(v) = prefs.edit().putBoolean("hdModels", v).apply()

    /** Options' HD creature textures switch (creator/HdTextures): a creature's skins enlarged twice, made once per texture. Off by default. */
    var hdCreatureTextures: Boolean
        get() = prefs.getBoolean("hdCreatureTextures", false)
        set(v) = prefs.edit().putBoolean("hdCreatureTextures", v).apply()

    /**
     * The placement editor's own switches, kept for the user ([name]: "place.panel" the side panel open, "place.tip.<id>"
     * a first-use tip dismissed). Only names starting with "place." are kept.
     */
    fun placeFlag(name: String, default: Boolean): Boolean =
        if (!name.startsWith("place.")) default else prefs.getBoolean(name, default)

    fun setPlaceFlag(name: String, on: Boolean) {
        if (name.startsWith("place.")) prefs.edit().putBoolean(name, on).apply()
    }

    companion object {
        const val SOFT_NATURAL = 2
        val SOFT_NAMES = listOf("Off", "Subtle", "Natural", "Strong")
        const val GENTLE = 24
        const val NORMAL = 48
        const val FAST = 96
        val CHOICES = listOf(GENTLE, NORMAL, FAST)
    }
}
