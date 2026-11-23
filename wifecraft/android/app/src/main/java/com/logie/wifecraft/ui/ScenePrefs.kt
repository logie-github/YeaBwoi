package com.logie.wifecraft.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The live scene's own switches, kept in the preferences the scene reads (the same file as the animation window's play options), so the
 * window's switch and Options > Scene are one setting: [askBeforePairPlays] ("Ask before partner animations play": off, a partner
 * animation plays at once in the roles last kept; on, the "Ready to play" panel comes first).
 */
object ScenePrefs {
    const val FILE = "scene_play_options"
    const val ASK_PAIR = "ask_pair"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun askBeforePairPlays(context: Context): Boolean = runCatching { prefs(context).getBoolean(ASK_PAIR, false) }.getOrDefault(false)
    fun setAskBeforePairPlays(context: Context, on: Boolean) { runCatching { prefs(context).edit().putBoolean(ASK_PAIR, on).apply() } }
}

/** Options > Scene. */
@Composable
fun ScenePage() {
    val context = LocalContext.current
    var ask by remember { mutableStateOf(ScenePrefs.askBeforePairPlays(context)) }
    WifeSwitchRow("Ask before partner animations play", ask, { ask = it; ScenePrefs.setAskBeforePairPlays(context, it) },
        detail = "Off: a partner animation plays at once, in the roles last kept for it. On: the Ready to play panel comes first. The animation window has the same switch.")
}
