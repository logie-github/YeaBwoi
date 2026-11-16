package com.logie.wifecraft.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
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

/**
 * Options, as the game's settings window: the categories down its left side (Game, Sound, Graphics, Interface, Advanced last:
 * [OptionsModel]) and the chosen page's rows beside them (gold labels; check boxes, sliders and dropdowns as the game draws
 * them), Okay at the foot. Game holds the Imports page (Ralia's mod, Alternate Female Options, the body options, the undead
 * skins, the Sims 4 skeleton) and the interface art's state. A narrow window puts the categories in a dropdown over the page.
 * Every change applies at once (Okay closes).
 */
@Composable
fun OptionsWindow(
    textSpeed: Int, onTextSpeed: (Int) -> Unit,
    parchment: Boolean, onParchment: (Boolean) -> Unit,
    wifiOnly: Boolean, onWifiOnly: (Boolean) -> Unit,
    /** Files fetched at once by the first launch's gathering (24, 48 or 96). */
    downloadSpeed: Int, onDownloadSpeed: (Int) -> Unit,
    musicVolume: Int, onMusicVolume: (Int) -> Unit,
    ambienceVolume: Int, onAmbienceVolume: (Int) -> Unit,
    soundVolume: Int, onSoundVolume: (Int) -> Unit,
    voiceVolume: Int, onVoiceVolume: (Int) -> Unit,
    /** Soft body: 0 Off, 1 Subtle, 2 Natural, 3 Strong (applies at once). */
    softBody: Int, onSoftBody: (Int) -> Unit,
    onBack: () -> Unit,
    /** The creator's imports (the title's and the editor's Options; none while a story plays). */
    imports: (@Composable () -> Unit)? = null,
    /** The Sims 4 skeleton's state ("loaded" or "not loaded") and the way to Import Animations, where it is chosen. */
    simsSkeleton: String? = null,
    onImportAnims: (() -> Unit)? = null,
    /** The editor's interface art: how much is here, what failed, Retry (WarArtRow). */
    interfaceArt: (@Composable () -> Unit)? = null,
    /** The game's files: what is missing, and Gather (the first launch's screen). */
    gameFiles: String? = null,
    onGather: (() -> Unit)? = null,
) {
    var page by remember { mutableStateOf(OptionsPage.SOUND) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
        val side = OptionsModel.sideList(layout.optionsW)
        WowWindow("Options", onBack, Modifier.width(layout.optionsW.dp).height(layout.optionsH.dp)) {
            Row(Modifier.weight(1f)) {
                if (side) Column(Modifier.width(170.dp)) {
                    for (c in OptionsPage.CATEGORIES) {
                        val on = c == page || page.parent == c
                        Box(
                            Modifier.fillMaxWidth().height(48.dp).background(if (on) Color(0x28FFD100) else Color.Transparent)
                                .border(1.dp, if (on) Color(0x8CFFD100) else Color.Transparent).clickable { page = c }.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) { Text(c.label, style = frizStyle(15.sp, if (on) Color.White else WowColors.gold)) }
                    }
                }
                if (side) Box(Modifier.padding(horizontal = 12.dp).width(1.5.dp).fillMaxHeight().background(Color(0xFF64583F)))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!side) WowDropdown(page.label, OptionsPage.CATEGORIES.map { it to it.label }, { page = it }, Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (page.parent != null) TextAction("‹ ${page.parent!!.label}", { page = page.parent!! })
                        Text(page.label, style = frizStyle(18.sp), modifier = Modifier.padding(vertical = 8.dp))
                    }
                    var importsShown = false
                    for (k in OptionsModel.rows(page)) when (k) {
                        OptionKey.MUSIC -> WowSliderRow(k.label, musicVolume, onMusicVolume)
                        OptionKey.AMBIENCE -> WowSliderRow(k.label, ambienceVolume, onAmbienceVolume)
                        OptionKey.EFFECTS -> WowSliderRow(k.label, soundVolume, onSoundVolume)
                        OptionKey.VOICES -> WowSliderRow(k.label, voiceVolume, onVoiceVolume)
                        OptionKey.SOFT_BODY -> ChoiceRow(k.label, "Chest, belly, rear and thighs hang and bounce as she moves") {
                            WowDropdown(OptionsModel.SOFT_BODY[softBody.coerceIn(0, 3)], OptionsModel.SOFT_BODY.mapIndexed { i, l -> i to l }, onSoftBody, Modifier.fillMaxWidth())
                        }
                        OptionKey.DOWNLOAD_SPEED -> ChoiceRow(k.label, "Files at once; it slows itself when wago.tools asks for less") {
                            WowDropdown(OptionsModel.DOWNLOAD.firstOrNull { it.first == downloadSpeed }?.second ?: "$downloadSpeed at once", OptionsModel.DOWNLOAD, onDownloadSpeed, Modifier.fillMaxWidth())
                        }
                        OptionKey.TEXT_SPEED -> WowSliderRow(k.label, textSpeed, onTextSpeed, if (textSpeed >= 120) "at once" else "$textSpeed/s", 15..120)
                        OptionKey.PARCHMENT -> WowCheckRow(k.label, parchment, onParchment, detail = "Instead of Legion's dark talking head")
                        OptionKey.WIFI_ONLY -> WowCheckRow(k.label, wifiOnly, onWifiOnly, detail = "A look's own files still come as it is shown")
                        OptionKey.GAME_FILES -> ChoiceRow(k.label, gameFiles ?: "All here") {
                            if (onGather != null) RedButton("Gather", onGather, Modifier.fillMaxWidth())
                        }
                        OptionKey.IMPORTS -> Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { page = OptionsPage.IMPORTS },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(k.label, style = frizStyle(15.sp))
                                Text("Ralia's mod, Alternate Female Options, Body options, Undead skins, the Sims 4 skeleton", style = frizStyle(11.sp, WowColors.grey), maxLines = 2)
                            }
                            Text("›", style = frizStyle(22.sp), modifier = Modifier.padding(horizontal = 10.dp))
                        }
                        OptionKey.INTERFACE_ART -> interfaceArt?.invoke()
                        OptionKey.RALIA, OptionKey.ALTERNATE_FEMALE, OptionKey.BODY, OptionKey.UNDEAD -> if (!importsShown) {
                            importsShown = true
                            if (imports != null) imports() else Text("The imports are set from the title's or the editor's Options.", style = frizStyle(13.sp, Color.White))
                        }
                        OptionKey.SIMS_SKELETON -> ChoiceRow(k.label, simsSkeleton ?: "Chosen in Import Animations") {
                            if (onImportAnims != null) RedButton("Import Animations", onImportAnims, Modifier.fillMaxWidth(), size = 13.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                RedButton("Okay", onBack, Modifier.width(150.dp))
            }
        }
    }
}

/** A settings row: the gold label (and a grey line under it), its control on the right. */
@Composable
private fun ChoiceRow(label: String, detail: String?, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = frizStyle(15.sp), maxLines = 1)
            if (detail != null) Text(detail, style = frizStyle(11.sp, WowColors.grey), maxLines = 2)
        }
        Box(Modifier.width(220.dp)) { control() }
    }
}

@Composable
fun goldCheckbox() = androidx.compose.material3.CheckboxDefaults.colors(
    checkedColor = WowColors.gold, uncheckedColor = WowColors.gold.copy(alpha = 0.7f), checkmarkColor = Color.Black,
)

/** Updates from the repository's releases, in a small WoW window: what is out, Install. */
@Composable
fun UpdatePanel(status: String, canInstall: Boolean, onInstall: () -> Unit, onBack: () -> Unit) {
    WowWindow("Updates", onBack, Modifier.widthIn(max = 520.dp).padding(16.dp)) {
        Text(status, style = frizStyle(14.sp, Color.White), modifier = Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canInstall) RedButton("Install", onInstall, Modifier.weight(1f))
            RedButton("Okay", onBack, Modifier.weight(1f))
        }
    }
}
