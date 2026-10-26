package com.logie.wifecraft.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.R
import com.logie.wifecraft.data.Endings
import com.logie.wifecraft.data.Save
import com.logie.wifecraft.story.LogEntry
import java.text.DateFormat
import java.util.Date

/** A dimmed backdrop with one panel in the middle; tapping outside closes it. */
@Composable
fun Overlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        // Taps on the panel stay on the panel.
        Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) { content() }
    }
}

/** Saving to, or loading from, the six slots and the autosave. */
@Composable
fun SavesPanel(saving: Boolean, saves: List<Save?>, onPick: (Int) -> Unit, onDelete: (Int) -> Unit, onBack: () -> Unit) {
    MetalPanel(Modifier.width(720.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (saving) "Save" else "Load", style = frizStyle(16.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
            val slots = if (saving) (1..6).toList() else (0..6).toList()
            for (row in slots.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (slot in row) {
                        val s = saves.getOrNull(slot)
                        val enabled = saving || s != null
                        Column(
                            Modifier.width(214.dp).height(78.dp)
                                .background(Color(0x55000000))
                                .border(1.dp, if (s != null) WowColors.gold.copy(alpha = 0.6f) else Color(0x44FFD100))
                                .clickable(enabled = enabled) { onPick(slot) }
                                .padding(8.dp),
                        ) {
                            Text(if (slot == 0) "Autosave" else "Slot $slot", style = frizStyle(14.sp, if (s != null) WowColors.gold else WowColors.grey))
                            if (s != null) {
                                Text(listOf(s.state.chapter, s.place).filter { it.isNotEmpty() }.joinToString(" · "), style = frizStyle(11.sp, Color.White), maxLines = 1)
                                Text("${s.playerName} · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.savedAt)), style = frizStyle(10.sp, WowColors.grey), maxLines = 1)
                            } else {
                                Text("Empty", style = frizStyle(11.sp, WowColors.grey))
                            }
                        }
                    }
                }
            }
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!saving) {
                    var deleting by remember { mutableStateOf(false) }
                    WowButton(if (deleting) "Pick one to delete" else "Delete…", { deleting = !deleting }, width = 190.dp, height = 38.dp)
                    if (deleting) {
                        for (slot in 1..6) if (saves.getOrNull(slot) != null)
                            WowButton("$slot", { onDelete(slot); deleting = false }, width = 48.dp, height = 38.dp)
                    }
                }
                WowButton("Back", onBack, width = 150.dp, height = 38.dp)
            }
        }
    }
}

/** What was said, newest at the bottom. */
@Composable
fun LogPanel(entries: List<LogEntry>, nameOf: (String) -> String, onBack: () -> Unit) {
    MetalPanel(Modifier.width(720.dp).heightIn(max = 360.dp)) {
        Column {
            Text("What Was Said", style = frizStyle(16.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
            val state = rememberLazyListState()
            LaunchedEffect(entries.size) { if (entries.isNotEmpty()) state.scrollToItem(entries.size - 1) }
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 260.dp), state = state) {
                items(entries) { e ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        val name = nameOf(e.who)
                        if (name.isNotEmpty()) Text(name, style = frizStyle(12.sp))
                        Text(e.text, style = frizStyle(14.sp, Color.White))
                    }
                }
            }
            WowButton("Back", onBack, Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp), width = 150.dp, height = 38.dp)
        }
    }
}

/** Options: how fast words appear, the talking head's style, downloads, vibration. */
@Composable
fun OptionsPanel(
    textSpeed: Int, onTextSpeed: (Int) -> Unit,
    parchment: Boolean, onParchment: (Boolean) -> Unit,
    wifiOnly: Boolean, onWifiOnly: (Boolean) -> Unit,
    musicVolume: Int, onMusicVolume: (Int) -> Unit,
    soundVolume: Int, onSoundVolume: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val gold = SliderDefaults.colors(thumbColor = WowColors.gold, activeTrackColor = WowColors.gold, inactiveTrackColor = Color(0xFF3A3020))
    MetalPanel(Modifier.width(520.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Options", style = frizStyle(16.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
            for ((label, value, set) in listOf(Triple("Music", musicVolume, onMusicVolume), Triple("Sounds", soundVolume, onSoundVolume))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$label: ${if (value == 0) "off" else "$value%"}", style = frizStyle(13.sp, Color.White), modifier = Modifier.width(110.dp))
                    Slider(value.toFloat(), { set(it.toInt()) }, valueRange = 0f..100f, colors = gold, modifier = Modifier.height(32.dp))
                }
            }
            Text("Text speed: " + if (textSpeed >= 120) "at once" else "$textSpeed letters a second", style = frizStyle(13.sp, Color.White))
            Slider(
                textSpeed.toFloat(), { onTextSpeed(it.toInt()) }, valueRange = 15f..120f,
                colors = SliderDefaults.colors(thumbColor = WowColors.gold, activeTrackColor = WowColors.gold, inactiveTrackColor = Color(0xFF3A3020)),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(parchment, onParchment, colors = goldCheckbox())
                Text("Parchment talking head (instead of Legion's dark one)", style = frizStyle(13.sp, Color.White))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(wifiOnly, onWifiOnly, colors = goldCheckbox())
                Text("Download the game's files on Wi-Fi only", style = frizStyle(13.sp, Color.White))
            }
            WowButton("Back", onBack, Modifier.align(Alignment.CenterHorizontally), width = 150.dp, height = 38.dp)
        }
    }
}

@Composable
fun goldCheckbox() = androidx.compose.material3.CheckboxDefaults.colors(
    checkedColor = WowColors.gold, uncheckedColor = WowColors.gold.copy(alpha = 0.7f), checkmarkColor = Color.Black,
)

/** The endings found, and how many are left. */
@Composable
fun EndingsPanel(found: Set<String>, onBack: () -> Unit) {
    MetalPanel(Modifier.width(560.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Endings · ${found.size} of ${Endings.all.size}", style = frizStyle(16.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
            for (e in Endings.all) {
                val got = e.id in found
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (got) e.title else "???", style = frizStyle(15.sp, if (got) WowColors.gold else WowColors.grey))
                    Text("${e.who} · ${e.kind}", style = frizStyle(12.sp, if (got) Color.White else WowColors.grey))
                }
            }
            WowButton("Back", onBack, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp), width = 150.dp, height = 38.dp)
        }
    }
}

/** Who the player is: a name, and one of two looks. */
@Composable
fun CreatorScreen(name: String, look: String, onDone: (String, String) -> Unit, onBack: () -> Unit) {
    var n by remember { mutableStateOf(name) }
    var l by remember { mutableStateOf(look) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(painterResource(R.drawable.wc_splash), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color(0x88000000)))
        MetalPanel(Modifier.align(Alignment.Center).width(560.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Who Are You?", style = morpheusStyle(26.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
                Text("Name", style = frizStyle(13.sp))
                BasicTextField(
                    n, { n = it.take(18) }, singleLine = true,
                    textStyle = frizStyle(18.sp, Color.White),
                    cursorBrush = SolidColor(WowColors.gold),
                    modifier = Modifier.fillMaxWidth().background(Color.Black).border(1.dp, Color(0xFF6B6B6B)).padding(10.dp),
                )
                Text("You arrive in Dalaran as one of the Kirin Tor's", style = frizStyle(13.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((key, label) in listOf("player_man" to "Invoker (man)", "player_woman" to "Invoker (woman)")) {
                        Box(
                            Modifier.width(240.dp).height(46.dp)
                                .background(if (l == key) Color(0x55FFD100) else Color(0x55000000))
                                .border(1.dp, if (l == key) WowColors.gold else Color(0x66FFD100))
                                .clickable { l = key },
                            contentAlignment = Alignment.Center,
                        ) { Text(label, style = frizStyle(15.sp, if (l == key) WowColors.gold else Color.White)) }
                    }
                }
                Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WowButton("Back", onBack, width = 150.dp)
                    WowButton("Arrive in Dalaran", { onDone(n.trim().ifEmpty { "Traveller" }, l) }, width = 230.dp)
                }
            }
        }
    }
}

/** Updates from the repository's releases. */
@Composable
fun UpdatePanel(status: String, canInstall: Boolean, onInstall: () -> Unit, onBack: () -> Unit) {
    MetalPanel(Modifier.width(520.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Updates", style = frizStyle(16.sp))
            Text(status, style = frizStyle(13.sp, Color.White))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canInstall) WowButton("Install", onInstall, width = 160.dp, height = 38.dp)
                WowButton("Back", onBack, width = 150.dp, height = 38.dp)
            }
        }
    }
}
