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
                            progress?.let { current ->
                                val fraction = current.fraction.coerceIn(0f, 1f)
                                Text("${current.pack.label} (${current.packNumber} of ${current.packCount})", style = frizStyle(15.sp))
                                LoadingBar(fraction, Modifier.fillMaxWidth().height(32.dp).semantics {
                                    progressBarRangeInfo = if (current.total == 0 && downloading) ProgressBarRangeInfo.Indeterminate
                                        else ProgressBarRangeInfo(fraction, 0f..1f)
                                })
                                val mb = "%.1f MB".format(current.bytes / 1_000_000.0)
                                val checked = if (current.total > 0) " · ${current.done} of ${current.total} entries checked" else " · Finding files…"
                                val failed = if (current.failed > 0) " · ${current.failed} failed" else ""
                                Text("${current.files} files downloaded ($mb)$checked$failed", style = frizStyle(12.sp, Color.White))
                            }
                            status?.let { Text(it, style = frizStyle(12.sp, Color.White)) }
                            if (downloading) {
                                RedButton("Pause", onPause, Modifier.fillMaxWidth())
                            } else {
                                RedButton("Download all", { onDownload(AssetDownloads.Pack.entries) }, Modifier.fillMaxWidth(), enabled = canDownload)
                                for (pack in AssetDownloads.Pack.entries) {
                                    RedButton(pack.label, { onDownload(listOf(pack)) }, Modifier.fillMaxWidth(), enabled = canDownload)
                                }
                                if (!canDownload) Text("Gather the character tables first.", style = frizStyle(12.sp, Color.White))
                            }
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
