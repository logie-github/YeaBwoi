package com.logie.wifecraft.ui

import androidx.compose.runtime.Composable
import com.logie.wifecraft.dialogue.DialogueSettings
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
    extra()
}
