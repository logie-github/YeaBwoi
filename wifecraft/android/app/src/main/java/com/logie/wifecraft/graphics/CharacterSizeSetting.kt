package com.logie.wifecraft.graphics

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.logie.wifecraft.anim.CharacterSize

/**
 * Options' Character size (Graphics page): the percent kept on the phone ("wifecraft" / "characterSize"; [CharacterSize.MATCH_ROOM]
 * until the player moves it, 80 to 180). A scene takes it when it starts ([beginScene]) and keeps it until it ends: a change in
 * Options while a scene runs is for the next one (the bodies, the pair frame and the furniture fits are made for one factor).
 * Outside a scene a change is in force at once (the placement editor's ghost).
 */
object CharacterSizeSetting {
    private const val PREFS = "wifecraft"
    private const val KEY = "characterSize"

    var percent by mutableStateOf(CharacterSize.MATCH_ROOM)
        private set
    private var loaded = false
    @Volatile private var sceneRunning = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        percent = CharacterSize.parse(try { prefs(context).getString(KEY, null) } catch (e: Exception) { null })
        if (!sceneRunning) CharacterSize.apply(percent)
    }

    fun set(context: Context, value: Int) {
        loaded = true
        percent = CharacterSize.clamp(value)
        try { prefs(context).edit().putString(KEY, CharacterSize.format(percent)).apply() } catch (e: Exception) { }
        if (!sceneRunning) CharacterSize.apply(percent)
    }

    /** A scene starts: the setting is put in force and held until [endScene]. */
    fun beginScene(context: Context) {
        load(context)
        CharacterSize.apply(percent)
        sceneRunning = true
    }

    fun endScene() { sceneRunning = false; CharacterSize.apply(percent) }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
