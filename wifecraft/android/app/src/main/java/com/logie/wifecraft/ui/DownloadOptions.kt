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
import com.logie.wifecraft.data.ByteSize
import com.logie.wifecraft.data.DownloadPage
import com.logie.wifecraft.data.DownloadPlan
import com.logie.wifecraft.data.DownloadSnapshot
import com.logie.wifecraft.data.PackKey
import com.logie.wifecraft.data.PackStatus
import com.logie.wifecraft.data.PageAction
import com.logie.wifecraft.data.Phase
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
import com.logie.wifecraft.dialogue.DialogueSettings
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

private enum class Page(val label: String) { SOUND("Sound"), GRAPHICS("Graphics"), IMMERSION("Immersion"), TIME("Time"), CINEMATIC("Cinematic"), DIALOGUE("Dialogue"), DOWNLOADS("Downloads"), MODS("Mods") }

/** What the Downloads page's controls do (data/DownloadHub's commands, wired by the activity). */
class DownloadActions(
    val everything: () -> Unit,
    val pack: (PackKey) -> Unit,
    val pause: () -> Unit,
    val resume: () -> Unit,
    val cancel: () -> Unit,
    val retryFailed: () -> Unit,
    val verify: () -> Unit,
    val chosen: (Set<PackKey>) -> Unit,
    val wifiOnly: (Boolean) -> Unit,
    val mobileData: (Boolean) -> Unit,
    val prebuild: (Boolean) -> Unit,
    /** The free-space refusal's "Download what fits" (the packs that fit ticked, then the download started). */
    val fit: () -> Unit,
    val dismissSpace: () -> Unit,
)

/**
 * Options, in the front menus' look (WifeWindow): Sound (the game's own Sound page: Enable Sound, Master, Music, Ambience, Sound
 * effects, Dialog; then the partner sounds), Downloads (the speed, "Download everything" and its packs) and Mods. Every change
 * applies at once.
 */
@Composable
fun DownloadOptions(
    /** "Download everything"'s state (data/DownloadHub.snapshot, at most 4 times a second). */
    downloads: DownloadSnapshot,
    speed: Int,
    onSpeed: (Int) -> Unit,
    wifiOnly: Boolean,
    actions: DownloadActions,
    /** The free-space refusal, and whether some packs fit ("Download what fits"). */
    spaceProblem: String?,
    canFit: Boolean,
    /** The Sound page's levels and their change (applied at once, kept by the caller). */
    mix: MixLevels,
    onMix: (MixLevels) -> Unit,
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
    /** The Dialogue page's settings and their change (null: no Dialogue page), and extra rows under them (the model's picker). */
    dialogue: DialogueSettings? = null,
    onDialogue: (DialogueSettings) -> Unit = {},
    dialogueExtra: @Composable () -> Unit = {},
    /** The Immersion page's settings and their change (null: no Immersion page), and what every NPC keeps on. */
    immersive: ImmersiveSettings? = null,
    onImmersive: (ImmersiveSettings) -> Unit = {},
    keepOn: Set<Int> = emptySet(),
    onKeepOn: (Set<Int>) -> Unit = {},
    /** The Graphics page (ui/GraphicsOptions.kt's GraphicsPage; null: no Graphics page). */
    graphics: (@Composable () -> Unit)? = null,
) {
    var page by remember { mutableStateOf(Page.SOUND) }
    val pages = remember(clock != null, cinematic != null, immersive != null, graphics != null, dialogue != null) {
        Page.entries.filter { (it != Page.TIME || clock != null) && (it != Page.CINEMATIC || cinematic != null) &&
            (it != Page.IMMERSION || immersive != null) && (it != Page.GRAPHICS || graphics != null) &&
            (it != Page.DIALOGUE || dialogue != null) }
    }
    val reduced = reduceMotion()
    Overlay(onClose) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
            WifeWindow(
                "Options", onClose, Modifier.width(layout.optionsW.dp).height(layout.optionsH.dp),
                tabs = pages.map { it.label }, tab = pages.indexOf(page).coerceAtLeast(0), onTab = { page = pages[it] },
                footer = {
                    if (page != Page.DOWNLOADS && downloads.running) Text(
                        "Downloading ${downloads.state.current?.label?.lowercase() ?: "game files"}…",
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
                                DownloadsPage(downloads, wifiOnly, actions, spaceProblem, canFit)
                            }
                            Page.TIME -> if (clock != null) TimePage(clock, onClock, lifeSimulation, onLifeSimulation)
                            Page.CINEMATIC -> if (cinematic != null) CinematicPage(cinematic, onCinematic)
                            Page.IMMERSION -> if (immersive != null) ImmersionPage(immersive, onImmersive, keepOn, onKeepOn)
                            Page.DIALOGUE -> if (dialogue != null) DialoguePage(dialogue, onDialogue, dialogueExtra)
                            Page.MODS -> mods()
                            Page.GRAPHICS -> graphics?.invoke()
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

/**
 * Options' Downloads page below the speed: "Download everything" (every pack this app can fetch from wago.tools, the big
 * ones ticked or not), its bar and what it is doing, Pause/Resume/Cancel, Retry failed, Verify, the network switches, the
 * pre-build step, each pack's row (files, size, state, its own Download) and the files that failed. The block over the
 * buttons keeps its lines whatever it says, so the buttons never move under the finger.
 */
@Composable
private fun DownloadsPage(d: DownloadSnapshot, wifiOnly: Boolean, actions: DownloadActions, spaceProblem: String?, canFit: Boolean) {
    val s = d.state
    val running = d.running
    val ticked = PackKey.selection(s.chosen)
    val total = DownloadPlan.total(ticked, d.estimates, s.records, d.version)
    val fraction = if (s.phase == Phase.COMPLETE) 1f else d.fraction.coerceIn(0f, 1f)

    WifeSectionHeader("Download everything")
    Text(DownloadPage.title(s.phase, s.current, d.packNumber, d.packCount), style = WifeTheme.labelStyle(WifeTheme.gold),
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    WifeProgress(fraction, Modifier.padding(vertical = WifeTheme.gapXs).semantics {
        progressBarRangeInfo = if (running && d.total == 0) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(fraction, 0f..1f)
    })
    val line = if (running || d.files > 0) {
        val entries = if (d.total > 0) " · ${ByteSize.count(d.done)} of ${ByteSize.count(d.total)} in this pack" else " · Finding files…"
        val failed = if (s.failed.isNotEmpty()) " · ${ByteSize.count(s.failed.size)} failed" else ""
        "${ByteSize.count(d.files)} files downloaded (${ByteSize.format(d.bytes)})$entries$failed"
    } else "Everything ticked: ${ByteSize.totalLine(total.bytes, total.exact)}. Files already here are skipped."
    Text(line, style = WifeTheme.captionStyle(WifeTheme.text), minLines = 2, maxLines = 2)
    Text(s.message ?: "${ByteSize.format(d.free)} free on the phone.", style = WifeTheme.captionStyle(WifeTheme.text), minLines = 2, maxLines = 3)

    if (spaceProblem != null && !running) {
        WifeNote(spaceProblem, color = WifeTheme.gold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
            if (canFit) WifeButton("Download what fits", actions.fit, Modifier.weight(1f), kind = WifeButtonKind.PRIMARY)
            WifeButton("Pick packs", actions.dismissSpace, Modifier.weight(1f))
        }
    }

    val anyHere = s.records.values.any { it.have > 0 }
    val buttons = DownloadPage.actions(s.phase, s.failed.size, anyHere)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
        buttons.forEachIndexed { i, a ->
            val label = if (a == PageAction.RETRY_FAILED) "${a.label} (${ByteSize.count(s.failed.size)})" else a.label
            val run: () -> Unit = when (a) {
                PageAction.DOWNLOAD_EVERYTHING -> actions.everything
                PageAction.PAUSE -> actions.pause
                PageAction.RESUME -> actions.resume
                PageAction.CANCEL -> actions.cancel
                PageAction.RETRY_FAILED -> actions.retryFailed
                PageAction.VERIFY -> actions.verify
            }
            WifeButton(label, run, Modifier.weight(1f), kind = if (i == 0) WifeButtonKind.PRIMARY else WifeButtonKind.SECONDARY)
        }
    }

    WifeSwitchRow("Wi-Fi only", wifiOnly, actions.wifiOnly, detail = "Wait for Wi-Fi before fetching the game's files")
    WifeSwitchRow("Also on mobile data", s.mobileData, actions.mobileData, enabled = wifiOnly,
        detail = "Let this download go on over mobile data too")
    WifeSwitchRow("Pre-build caches", s.prebuild, actions.prebuild,
        detail = "When everything is here, also prepare the scene sounds' index (off: made the first time a sound plays)")

    WifeSectionHeader("Packs")
    for (pack in PackKey.entries) {
        val record = s.records[pack]
        val view = DownloadPlan.view(record, d.version)
        val weight = DownloadPlan.weight(d.estimates[pack], record, d.version)
        val size = (if (weight.exact) "" else "about ") + ByteSize.format(weight.bytes)
        val state = if (running && s.current == pack && d.total > 0) "Downloading ${(d.done * 100L / d.total).toInt()}%" else view.label
        val detail = "${ByteSize.count(weight.files)} files · $size · $state"
        val included = !pack.optional || pack in s.chosen
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
            WowCheckRow(pack.label, included, { on -> actions.chosen(if (on) s.chosen + pack else s.chosen - pack) },
                Modifier.weight(1f), enabled = pack.optional && !running, detail = detail)
            WifeButton(if (view.status == PackStatus.NEEDS_UPDATE) "Update" else "Download", { actions.pack(pack) },
                Modifier.widthIn(min = 112.dp), enabled = !running && view.status != PackStatus.COMPLETE, kind = WifeButtonKind.QUIET)
        }
    }
    WifeNote("Sizes marked \"about\" are estimates (wago.tools gives no sizes ahead); each is replaced by the real count once its pack is here. " +
        "The download goes on in the background with a notification. Community animation packs are installed from the Animation library, not here.")

    if (s.failed.isNotEmpty()) {
        WifeSectionHeader("Failed files")
        for (f in s.failed.take(FAILED_SHOWN)) {
            Text("File ${f.id}${f.pack?.let { " (${it.label})" } ?: ""}: ${f.reason}", style = WifeTheme.captionStyle(WifeTheme.text),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (s.failed.size > FAILED_SHOWN) WifeNote("and ${ByteSize.count(s.failed.size - FAILED_SHOWN)} more")
    }
}

private const val FAILED_SHOWN = 12
