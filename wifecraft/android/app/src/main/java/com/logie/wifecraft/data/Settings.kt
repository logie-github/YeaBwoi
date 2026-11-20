package com.logie.wifecraft.data

import android.content.Context

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
    /** Files fetched at once by the first launch's gathering: Gentle 24, Normal 48, Fast 96 (the ceiling; it backs off by itself). */
    var downloadSpeed: Int
        get() = prefs.getInt("downloadSpeed", FAST).let { if (it in CHOICES) it else FAST }
        set(v) = prefs.edit().putInt("downloadSpeed", v).apply()
    /** The first launch's gathering waits for Wi-Fi (a look's own files are fetched as it is shown, on any connection). */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(v) = prefs.edit().putBoolean("wifiOnly", v).apply()

    /** Options' Soft body: 0 Off, 1 Subtle, 2 Natural (the default), 3 Strong (engine/Native.SOFT_*). */
    var softBody: Int
        get() = prefs.getInt("softBody", SOFT_NATURAL).let { if (it in 0..3) it else SOFT_NATURAL }
        set(v) = prefs.edit().putInt("softBody", v).apply()

    /** The imported body-part set the men wear (files/bodyparts/<set>, Options' Body parts); null: none imported. */
    var partSet: String?
        get() = prefs.getString("partSet", null)?.takeIf { it.isNotEmpty() }
        set(v) = prefs.edit().putString("partSet", v ?: "").apply()

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
