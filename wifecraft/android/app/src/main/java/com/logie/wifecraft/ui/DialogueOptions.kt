package com.logie.wifecraft.ui

import androidx.compose.runtime.Composable
import com.logie.wifecraft.dialogue.DialogueSettings
import com.logie.wifecraft.dialogue.DirtyLevel
import com.logie.wifecraft.dialogue.Talkativeness

/**
 * Options' Dialogue page: whether NPCs speak with the on-device model (off: they stay as they were, with the scripted small
 * talk), how talkative they are, and [extra] (the model's picker and its download, added by the runtime). Every change applies
 * at once, running scenes included.
 */
@Composable
fun DialoguePage(settings: DialogueSettings, onChange: (DialogueSettings) -> Unit, extra: @Composable () -> Unit = {}) {
    WifeSectionHeader("Talking")
    WifeSwitchRow("NPCs talk with the model", settings.enabled, { onChange(settings.copy(enabled = it)) },
        detail = if (settings.enabled) "Every spoken line comes from the model; with none loaded the NPCs keep their scripted small talk" else "Off: the scripted small talk, as before")
    WifeSectionHeader("Talkativeness")
    WifeTabs(Talkativeness.entries.map { it.label }, settings.talk.ordinal, { onChange(settings.copy(talk = Talkativeness.entries[it])) })
    WifeNote("How often they speak up. Quiet leaves long silences, Chatty answers each other quickly. Nothing is said in cinematic mode.")
    WifeSectionHeader("Dirty talk level")
    val levels = DirtyLevel.entries
    WifeSliderRow("Dirty talk level", settings.dirty.ordinal, { onChange(settings.copy(dirty = levels[it.coerceIn(0, levels.lastIndex)])) },
        valueText = settings.dirty.label, range = 0..levels.lastIndex)
    WifeNote("${settings.dirty.blurb}. Cute, Mild, Spicy, Hot, Nasty, Depraved, WTF??!: how filthy the words are in JOI mode, in sex-stage banter and when Chat turns to flirting. JOI mode has its own slider for each session, starting here. Adults only; every character is a full-grown adult.")
    WifeSectionHeader("Default era")
    EraPickerRow("Default era", settings.era, settings.customYear) { id, year -> onChange(settings.copy(era = id, customYear = year)) }
    WifeNote("The age NPCs live in: what the world is like, who is alive and what everyone knows (nothing later). A place or a scene can have its own era; the scene menu changes it for one scene. Named characters keep their own personality and are played as they were in that age.")
    extra()
}
