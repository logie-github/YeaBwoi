package com.logie.wifecraft.audio

import android.content.Context

/**
 * The Sound page's levels kept between runs, in the app's own preferences ("wifecraft", the same file and keys as
 * data/Settings: musicVolume, ambienceVolume, soundVolume, voiceVolume), with Master and Enable Sound added here.
 */
class MixSettings(context: Context) {
    private val prefs = context.getSharedPreferences("wifecraft", Context.MODE_PRIVATE)

    fun load(): MixLevels {
        val sound = prefs.getInt("soundVolume", 85)
        return MixLevels(
            enabled = prefs.getBoolean("soundEnabled", true),
            master = prefs.getInt("masterVolume", 100).coerceIn(0, 100),
            music = prefs.getInt("musicVolume", 70).coerceIn(0, 100),
            ambience = prefs.getInt("ambienceVolume", sound).coerceIn(0, 100),
            effects = sound.coerceIn(0, 100),
            dialog = prefs.getInt("voiceVolume", sound).coerceIn(0, 100),
        )
    }

    fun save(m: MixLevels) {
        prefs.edit()
            .putBoolean("soundEnabled", m.enabled)
            .putInt("masterVolume", m.master)
            .putInt("musicVolume", m.music)
            .putInt("ambienceVolume", m.ambience)
            .putInt("soundVolume", m.effects)
            .putInt("voiceVolume", m.dialog)
            .apply()
    }
}
