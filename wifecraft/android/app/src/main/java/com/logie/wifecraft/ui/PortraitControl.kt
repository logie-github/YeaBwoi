package com.logie.wifecraft.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.simulation.PortraitSettings

/**
 * Interact mode's portrait settings (Lighting, Life, Gaze, Look angle): kept in the mode's preferences under "portrait" and handed to the engine
 * for the person at [key] (null: nobody is in Interact mode, the engine goes back to the place's own light and the animation's own head).
 */
internal class PortraitControl(private val prefs: SharedPreferences?, initial: PortraitSettings) {
    var settings by mutableStateOf(initial)
        private set

    fun set(next: PortraitSettings) {
        settings = next
        try { prefs?.edit()?.putString(KEY, next.toJson().toString())?.apply() } catch (e: Exception) { }
    }

    companion object { const val KEY = "portrait" }
}

@Composable
internal fun rememberPortrait(view: SceneView, prefs: SharedPreferences?, key: String?, seed: Long): PortraitControl {
    val ctl = remember(prefs) { PortraitControl(prefs, PortraitSettings.parse(try { prefs?.getString(PortraitControl.KEY, null) } catch (e: Exception) { null })) }
    return ctl
}
