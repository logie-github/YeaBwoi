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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

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

private enum class Page(val label: String) { DOWNLOADS("Downloads"), SOUND("Sound"), MODS("Mods") }

@Composable
fun DownloadOptions(
    progress: AssetDownloads.Progress?,
    status: String?,
    downloading: Boolean,
    canDownload: Boolean,
    speed: Int,
    onSpeed: (Int) -> Unit,
    volume: Int,
    onVolume: (Int) -> Unit,
    onDownload: (List<AssetDownloads.Pack>) -> Unit,
    onPause: () -> Unit,
    onClose: () -> Unit,
    mods: @Composable () -> Unit,
) {
    var page by remember { mutableStateOf(Page.DOWNLOADS) }
    Overlay(onClose) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
            WowWindow("Options", onClose, Modifier.width(layout.optionsW.dp).height(layout.optionsH.dp),
                tabs = { Page.entries.forEach { entry -> WowTab(entry.label, entry == page, { page = entry }) } }) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (page) {
                        Page.DOWNLOADS -> {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Download speed", Modifier.weight(1f), style = frizStyle(14.sp))
                                WowDropdown(OptionsModel.DOWNLOAD.firstOrNull { it.first == speed }?.second ?: "$speed at once",
                                    OptionsModel.DOWNLOAD, onSpeed, Modifier.weight(1.1f))
                            }
                            // The block is always here, the same height (its texts keep their lines): starting a download
                            // never pushes the buttons down under the finger, and Pause takes Download all's place.
                            val current = progress
                            val fraction = current?.fraction?.coerceIn(0f, 1f) ?: 0f
                            Text(current?.let { "${it.pack.label} (${it.packNumber} of ${it.packCount})" } ?: "Nothing downloading",
                                style = frizStyle(15.sp), maxLines = 1)
                            LoadingBar(fraction, Modifier.fillMaxWidth().height(32.dp).semantics {
                                progressBarRangeInfo = if (current != null && current.total == 0 && downloading) ProgressBarRangeInfo.Indeterminate
                                    else ProgressBarRangeInfo(fraction, 0f..1f)
                            })
                            val line = current?.let {
                                val mb = "%.1f MB".format(it.bytes / 1_000_000.0)
                                val checked = if (it.total > 0) " · ${it.done} of ${it.total} entries checked" else " · Finding files…"
                                val failed = if (it.failed > 0) " · ${it.failed} failed" else ""
                                "${it.files} files downloaded ($mb)$checked$failed"
                            } ?: "Pick what to download; files already here are skipped."
                            Text(line, style = frizStyle(12.sp, Color.White), minLines = 2, maxLines = 2)
                            Text(status ?: "", style = frizStyle(12.sp, Color.White), minLines = 2, maxLines = 3)
                            if (downloading) {
                                RedButton("Pause", onPause, Modifier.fillMaxWidth())
                            } else {
                                RedButton("Download all", { onDownload(AssetDownloads.Pack.entries) }, Modifier.fillMaxWidth(), enabled = canDownload)
                            }
                            for (pack in AssetDownloads.Pack.entries) {
                                RedButton(pack.label, { onDownload(listOf(pack)) }, Modifier.fillMaxWidth(), enabled = canDownload && !downloading)
                            }
                            if (!canDownload) Text("Gather the character tables first.", style = frizStyle(12.sp, Color.White))
                        }
                        Page.SOUND -> WowSliderRow("Sound effects", volume, onVolume)
                        Page.MODS -> mods()
                    }
                }
                if (page != Page.DOWNLOADS && downloading) {
                    Text("Downloading ${progress?.pack?.label?.lowercase() ?: "game files"}…",
                        style = frizStyle(12.sp), modifier = Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}
