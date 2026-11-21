package com.logie.wifecraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.bodyparts.ArousalMode

/**
 * Options → Mods → Body parts → Arousal (bodyparts/Arousal): Automatic (the default: flaccid unless a man plays an intimate
 * partner stage, erect while he does, the stage's entry then aimed at), Always flaccid, Always erect. [mode] is the chosen one;
 * [onMode] with the new one. Each NPC's own switch in a scene (Auto, On, Off) wins over it. NOT run on a phone.
 */
@Composable
fun PartArousalSection(mode: ArousalMode, onMode: (ArousalMode) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Arousal: ${mode.label}", style = frizStyle(13.sp, WowColors.white))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in ArousalMode.values()) {
                WowButton(if (m == mode) "• ${m.label}" else m.label, { onMode(m) }, enabled = m != mode, width = 150.dp, height = 36.dp)
            }
        }
        Text("Automatic: flaccid while a man is idle, walks, talks or plays anything but an intimate partner stage; erect over a few " +
            "seconds while he plays one, and back after it ends. A scene's NPC switch (Auto, On, Off) overrides it for that NPC.",
            style = frizStyle(11.sp, WowColors.grey))
    }
}
