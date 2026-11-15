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
    /** The first launch's gathering waits for Wi-Fi (a look's own files are fetched as it is shown, on any connection). */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(v) = prefs.edit().putBoolean("wifiOnly", v).apply()
}
