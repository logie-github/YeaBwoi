package com.logie.wifecraft.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.data.AssetDownloads
import com.logie.wifecraft.audio.MixLevels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import com.logie.wifecraft.simulation.CinematicSettings
import com.logie.wifecraft.simulation.ClockSettings
import com.logie.wifecraft.simulation.ImmersiveSettings
import com.logie.wifecraft.simulation.ClockSpeed
import com.logie.wifecraft.simulation.WorldClock

/**
 * This flow at most once per [intervalMs] ([Sampler]): the first at once, an [urgent] one at once, the latest of those in
 * between when the interval is up (never dropped).
 */
fun <T> Flow<T>.throttleLatest(intervalMs: Long = PROGRESS_INTERVAL_MS, urgent: (T?, T) -> Boolean = { _, _ -> false }): Flow<T> =
    channelFlow {
        // (wrapped: the sampler's values are never null, a flow's may be)
        val sampler = Sampler<Slot<T>>(intervalMs, { System.nanoTime() / 1_000_000 }) { old, new -> urgent(old?.value, new.value) }
        var timer: Job? = null
        collect { value ->
            val now = sampler.offer(Slot(value))
            if (now != null) {
                timer?.cancel()
                send(now.value)
            } else if (timer?.isActive != true) {
                timer = launch {
                    delay(sampler.dueIn() ?: 0L)
                    sampler.flush()?.let { send(it.value) }
                }
            }
        }
    }

private class Slot<T>(val value: T)

/**
 * A progress flow as state, at most 4 times a second (only while the screen is started). Read it in the smallest
 * composable that shows it: every change redraws whatever read it.
 */
@Composable
fun <T> StateFlow<T>.collectThrottled(urgent: (T?, T) -> Boolean = { _, _ -> false }): State<T> {
    val throttled = remember(this) { throttleLatest(PROGRESS_INTERVAL_MS, urgent) }
    return throttled.collectAsStateWithLifecycle(value)
}

private enum class Page(val label: String) { SOUND("Sound"), IMMERSION("Immersion"), TIME("Time"), CINEMATIC("Cinematic"), DOWNLOADS("Downloads"), MODS("Mods") }

/**
 * Options, in the front menus' look (WifeWindow): Sound (the game's own Sound page: Enable Sound, Master, Music, Ambience, Sound
 * effects, Dialog; then the partner sounds), Downloads (the speed, the packs, the bar) and Mods. Every change applies at once.
 */
@Composable
fun DownloadOptions(
    progress: AssetDownloads.Progress?,
    status: String?,
    downloading: Boolean,
    canDownload: Boolean,
    speed: Int,
    onSpeed: (Int) -> Unit,
    /** The Sound page's levels and their change (applied at once, kept by the caller). */
    mix: MixLevels,
    onMix: (MixLevels) -> Unit,
    onDownload: (List<AssetDownloads.Pack>) -> Unit,
    onPause: () -> Unit,
    onClose: () -> Unit,
    mods: @Composable () -> Unit,
    /** Extra blocks under the Sound page's sliders (the partner sounds). */
    sound: @Composable () -> Unit = {},
    /** The Time page's world clock settings and their change (null: no Time page). */
    clock: ClockSettings? = null,
    onClock: (ClockSettings) -> Unit = {},
    /** The Time page's Life simulation switch. */
    lifeSimulation: Boolean = true,
    onLifeSimulation: (Boolean) -> Unit = {},
    /** The Cinematic page's settings and their change (null: no Cinematic page). */
    cinematic: CinematicSettings? = null,
    onCinematic: (CinematicSettings) -> Unit = {},
    /** The Immersion page's settings and their change (null: no Immersion page), and what every NPC keeps on. */
    immersive: ImmersiveSettings? = null,
    onImmersive: (ImmersiveSettings) -> Unit = {},
    keepOn: Set<Int> = emptySet(),
    onKeepOn: (Set<Int>) -> Unit = {},
) {
    var page by remember { mutableStateOf(Page.SOUND) }
    val pages = remember(clock != null, cinematic != null, immersive != null) {
        Page.entries.filter { (it != Page.TIME || clock != null) && (it != Page.CINEMATIC || cinematic != null) &&
            (it != Page.IMMERSION || immersive != null) }
    }
    val reduced = reduceMotion()
    Overlay(onClose) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
            WifeWindow(
                "Options", onClose, Modifier.width(layout.optionsW.dp).height(layout.optionsH.dp),
                tabs = pages.map { it.label }, tab = pages.indexOf(page).coerceAtLeast(0), onTab = { page = pages[it] },
                footer = {
                    if (page != Page.DOWNLOADS && downloading) Text(
                        "Downloading ${progress?.pack?.label?.lowercase() ?: "game files"}…",
                        style = WifeTheme.captionStyle(), maxLines = 1, modifier = Modifier.weight(1f),
                    )
                    WifeButton("Okay", onClose, Modifier.widthIn(min = 132.dp), kind = WifeButtonKind.PRIMARY, sound = "ok")
                },
            ) {
                // one page gives way to the next (a short cross-fade), each with its own scroll from its top
                Crossfade(page, Modifier.fillMaxWidth().weight(1f), animationSpec = tween(MenuMotion.duration(MenuMotion.PAGE_MS, reduced)), label = "page") { shown ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                        when (shown) {
                            Page.SOUND -> {
                                WifeSwitchRow("Enable Sound", mix.enabled, { onMix(mix.copy(enabled = it)) }, detail = "Silent whenever Wifecraft is in the background")
                                WifeSectionHeader("Volume")
                                val on = mix.enabled
                                WifeSliderRow("Master", mix.master, { onMix(mix.copy(master = it)) }, enabled = on)
                                WifeSliderRow("Music", mix.music, { onMix(mix.copy(music = it)) }, enabled = on)
                                WifeSliderRow("Ambience", mix.ambience, { onMix(mix.copy(ambience = it)) }, enabled = on)
                                WifeSliderRow("Sound effects", mix.effects, { onMix(mix.copy(effects = it)) }, enabled = on)
                                WifeSliderRow("Dialog", mix.dialog, { onMix(mix.copy(dialog = it)) }, enabled = on)
                                WifeNote("The music dips while someone speaks. The game's music and sounds are fetched from wago.tools as they are first played.")
                                WifeSectionHeader("Partner sounds")
                                sound()
                            }
                            Page.DOWNLOADS -> {
                                Row(Modifier.fillMaxWidth().heightIn(min = WifeTheme.touch), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapM)) {
                                    Text("Download speed", Modifier.weight(1f), style = WifeTheme.labelStyle(), maxLines = 2)
                                    WowDropdown(OptionsModel.DOWNLOAD.firstOrNull { it.first == speed }?.second ?: "$speed at once",
                                        OptionsModel.DOWNLOAD, onSpeed, Modifier.weight(1.1f))
                                }
                                // The block is always here, the same height (its texts keep their lines): starting a download
                                // never pushes the buttons down under the finger, and Pause takes Download all's place.
                                val current = progress
                                val fraction = current?.fraction?.coerceIn(0f, 1f) ?: 0f
                                WifeSectionHeader("Game files")
                                Text(current?.let { "${it.pack.label} (${it.packNumber} of ${it.packCount})" } ?: "Nothing downloading",
                                    style = WifeTheme.labelStyle(WifeTheme.gold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                WifeProgress(fraction, Modifier.padding(vertical = WifeTheme.gapXs).semantics {
                                    progressBarRangeInfo = if (current != null && current.total == 0 && downloading) ProgressBarRangeInfo.Indeterminate
                                        else ProgressBarRangeInfo(fraction, 0f..1f)
                                })
                                val line = current?.let {
                                    val mb = "%.1f MB".format(it.bytes / 1_000_000.0)
                                    val checked = if (it.total > 0) " · ${it.done} of ${it.total} entries checked" else " · Finding files…"
                                    val failed = if (it.failed > 0) " · ${it.failed} failed" else ""
                                    "${it.files} files downloaded ($mb)$checked$failed"
                                } ?: "Pick what to download; files already here are skipped."
                                Text(line, style = WifeTheme.captionStyle(WifeTheme.text), minLines = 2, maxLines = 2)
                                Text(status ?: "", style = WifeTheme.captionStyle(WifeTheme.text), minLines = 2, maxLines = 3)
                                if (downloading) {
                                    WifeButton("Pause", onPause, Modifier.fillMaxWidth(), kind = WifeButtonKind.PRIMARY)
                                } else {
                                    WifeButton("Download all", { onDownload(AssetDownloads.Pack.entries) }, Modifier.fillMaxWidth(), enabled = canDownload, kind = WifeButtonKind.PRIMARY)
                                }
                                for (pack in AssetDownloads.Pack.entries) {
                                    WifeButton(pack.label, { onDownload(listOf(pack)) }, Modifier.fillMaxWidth(), enabled = canDownload && !downloading)
                                }
                                if (!canDownload) WifeNote("Gather the character tables first.")
                            }
                            Page.TIME -> if (clock != null) TimePage(clock, onClock, lifeSimulation, onLifeSimulation)
                            Page.CINEMATIC -> if (cinematic != null) CinematicPage(cinematic, onCinematic)
                            Page.IMMERSION -> if (immersive != null) ImmersionPage(immersive, onImmersive, keepOn, onKeepOn)
                            Page.MODS -> mods()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Options' Time page: the Life simulation switch, and the world clock's settings (ClockSettings): its speed (where each scene
 * starts; the clock's own buttons change it for that scene), the hour a new cast's day begins, and Day/night cycle.
 */
@Composable
private fun TimePage(clock: ClockSettings, onClock: (ClockSettings) -> Unit, life: Boolean, onLife: (Boolean) -> Unit) {
    WifeSwitchRow("Life simulation", life, onLife,
        detail = "NPCs have needs, moods, wishes and relationships, and live their day as in The Sims")
    WifeSectionHeader("Speed")
    val speeds = LifeHud.SPEEDS
    WifeTabs(speeds.map { if (it == ClockSpeed.PAUSED) "Paused" else it.label }, speeds.indexOf(clock.speed).coerceAtLeast(0),
        { onClock(clock.copy(speed = speeds[it])) })
    WifeNote("At x1 a real second is a game minute: a day takes 24 minutes (8 at x3, 4 at x6). The needs follow the clock; Paused stops them too.")
    WifeSectionHeader("Day")
    Row(Modifier.fillMaxWidth().heightIn(min = WifeTheme.touch), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapM)) {
        Text("Start hour", Modifier.weight(1f), style = WifeTheme.labelStyle(), maxLines = 2)
        val label = ClockSettings.START_HOURS.firstOrNull { it.first == clock.startHour }?.second ?: WorldClock.format24(clock.startHour)
        WowDropdown(label, ClockSettings.START_HOURS, { h: Double -> onClock(clock.copy(startHour = h)) }, Modifier.weight(1.1f))
    }
    WifeNote("Where a new cast's first day begins; a cast you have played before goes on from its own time.")
    WifeSwitchRow("Day/night cycle", clock.cycle, { onClock(clock.copy(cycle = it)) },
        detail = "Off: the light and the sounds keep the start hour while the clock still runs")
}
