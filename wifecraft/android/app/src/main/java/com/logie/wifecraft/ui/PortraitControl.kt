package com.logie.wifecraft.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    // the engine: the rig on her while she is the person of the mode (GL thread); off again when the mode ends or she changes
    DisposableEffect(view, key, ctl.settings.lighting) {
        val k = key
        if (k != null) view.queueEvent { view.glPortrait(k, ctl.settings.lighting.engineId) }
        onDispose { if (k != null) view.queueEvent { view.glPortrait(k, 0) } }
    }
    // her life and gaze (GL thread), and off again when the mode ends
    DisposableEffect(view, key, ctl.settings.life, ctl.settings.gaze, ctl.settings.lookAngle) {
        val k = key
        val st = ctl.settings
        if (k != null) view.queueEvent { view.glLife(k, st.life.engineId, st.gaze.engineId, st.lookAngle.toFloat(), (seed xor k.hashCode().toLong()).toInt()) }
        onDispose { if (k != null) view.queueEvent { view.glLife(k, 0, 0, 70f, 0) } }
    }
    return ctl
}

/** A touch on her reaches the engine's life layer (her head and eyes turn toward the place, a small start follows). */
internal fun SceneView.lifeTouch(key: String, hit: com.logie.wifecraft.simulation.TouchHit, kind: com.logie.wifecraft.simulation.TouchKind, reaction: com.logie.wifecraft.simulation.Reaction?) {
    val ev = reaction?.let { com.logie.wifecraft.simulation.LifeTouch.event(it.kind) } ?: com.logie.wifecraft.simulation.LifeTouch.ATTEND
    val bone = com.logie.wifecraft.simulation.LifeTouch.bone(hit.region)
    val strength = com.logie.wifecraft.simulation.LifeTouch.strength(hit.region, kind)
    queueEvent { glLifeEvent(key, ev, bone, strength) }
}

/** Everything of [s] on person [key] (GL thread); [seed] makes her life her own. */
internal fun SceneView.portraitOn(key: String, s: PortraitSettings, seed: Long) {
    queueEvent {
        glPortrait(key, s.lighting.engineId)
        glLife(key, s.life.engineId, s.gaze.engineId, s.lookAngle.toFloat(), (seed xor key.hashCode().toLong()).toInt())
    }
}

/** Takes it all off person [key] again. */
internal fun SceneView.portraitOff(key: String) {
    queueEvent { glPortrait(key, 0); glLife(key, 0, 0, 70f, 0) }
}
