package com.logie.wifecraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Options → Mods → HD creature textures (creator/HdTextures): on, a creature's skin textures (Sindragosa's dragon and every
 * other creature) are drawn twice as large, enlarged on the phone from the game's own files the first time and kept per
 * texture; off, the game's own. Takes effect the next time a creature is dressed. NOT run on a phone.
 */
@Composable
fun HdTexturesSection(on: Boolean, onSwitch: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("HD creature textures: ${if (on) "On" else "Off"}", style = frizStyle(13.sp, WowColors.white))
        WowButton(if (on) "Turn off" else "Turn on", { onSwitch(!on) }, width = 150.dp, height = 36.dp)
        Text("Creature skins (a dragon's scales, a beast's hide) are enlarged twice as large when they are loaded, sharpened a " +
            "little, and kept: once per texture, from the files the game gave, nothing is downloaded. Off gives the game's own back.",
            style = frizStyle(11.sp, WowColors.grey))
    }
}
