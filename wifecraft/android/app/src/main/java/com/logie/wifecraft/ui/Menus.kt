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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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

/** Options: the music and sounds, how fast words appear, the talking head's style, downloads, and what [more] adds. */
@Composable
fun OptionsPanel(
    textSpeed: Int, onTextSpeed: (Int) -> Unit,
    parchment: Boolean, onParchment: (Boolean) -> Unit,
    wifiOnly: Boolean, onWifiOnly: (Boolean) -> Unit,
    /** Files fetched at once by the first launch's gathering (24, 48 or 96). */
    downloadSpeed: Int, onDownloadSpeed: (Int) -> Unit,
    musicVolume: Int, onMusicVolume: (Int) -> Unit,
    soundVolume: Int, onSoundVolume: (Int) -> Unit,
    onBack: () -> Unit,
    /** More below the reader's settings (the title's Options: the creator's imports). */
    more: (@Composable () -> Unit)? = null,
) {
    val gold = SliderDefaults.colors(thumbColor = WowColors.gold, activeTrackColor = WowColors.gold, inactiveTrackColor = Color(0xFF3A3020))
    MetalPanel(Modifier.width(if (more != null) 600.dp else 520.dp)) {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                Text("Gather the game's files on Wi-Fi only (a look's own files come as it is shown)", style = frizStyle(13.sp, Color.White))
            }
            Text("Download speed (files at once; it slows itself when wago.tools asks for less)", style = frizStyle(13.sp, Color.White))
            Row(verticalAlignment = Alignment.CenterVertically) {
                for ((label, n) in listOf("Gentle 24" to 24, "Normal 48" to 48, "Fast 96" to 96)) {
                    RadioButton(
                        downloadSpeed == n, { onDownloadSpeed(n) },
                        colors = RadioButtonDefaults.colors(selectedColor = WowColors.gold, unselectedColor = WowColors.gold.copy(alpha = 0.7f)),
                    )
                    Text(label, style = frizStyle(13.sp, Color.White), modifier = Modifier.padding(end = 10.dp))
                }
            }
            more?.invoke()
            WowButton("Back", onBack, Modifier.align(Alignment.CenterHorizontally), width = 150.dp, height = 38.dp)
        }
    }
}

@Composable
fun goldCheckbox() = androidx.compose.material3.CheckboxDefaults.colors(
    checkedColor = WowColors.gold, uncheckedColor = WowColors.gold.copy(alpha = 0.7f), checkmarkColor = Color.Black,
)

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
