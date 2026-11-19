package com.logie.wifecraft.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.audio.PartnerSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Options > Game > Partner sounds: the sounds of partner animations (the user's own sound files, audio/PartnerSounds). An On box,
 * a volume slider, "N files, X MB", Add sounds (Android's document picker: files or a zip) and Remove all. Compiled by CI only;
 * NOT run on a phone.
 */
@Composable
fun PartnerSoundsSection(
    sounds: PartnerSounds,
    on: Boolean, onOn: (Boolean) -> Unit,
    volume: Int, onVolume: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(work: () -> String?) {
        busy = true; message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { work() } }
            message = result.exceptionOrNull()?.let { it.message ?: "It didn't work." } ?: result.getOrNull()
            busy = false; version++
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) run {
            var added = 0; var skipped = 0
            var failure: Exception? = null
            for (uri in uris) {
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull() ?: uri.lastPathSegment ?: "sound"
                try {
                    val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("That file can't be opened.")
                    val r = input.use { sounds.import(name, it) }
                    added += r.added; skipped += r.skipped
                } catch (e: Exception) { failure = e }
            }
            failure?.let { if (added == 0) throw it }
            "Added $added sound file${if (added == 1) "" else "s"}" + (if (skipped > 0) " ($skipped not audio, left out)" else "") + (failure?.let { "; ${it.message}" } ?: "")
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Partner sounds", style = frizStyle(16.sp))
        WowCheckRow("Partner sounds", on, onOn, detail = "Play the sounds of partner animations from your files")
        WowSliderRow("Volume", volume, onVolume)
        val status = remember(version) { sounds.status() }
        Text(if (busy) "Adding…" else status.summary() + if (status.formats.isEmpty()) "" else " (" + status.formats.joinToString(", ") + ")", style = frizStyle(13.sp, WowColors.white))
        Text(
            "Put sound files here that match the sound names in your animation mods (ogg, wav, mp3). The game's own EA audio formats are not decoded yet.",
            style = frizStyle(11.sp, WowColors.grey),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            WowButton("Add sounds", {
                picker.launch(arrayOf("audio/*", "application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }, enabled = !busy, width = 150.dp, height = 36.dp)
            WowButton("Remove all", { run { sounds.clear(); "Removed" } }, enabled = !busy && status.count > 0, width = 140.dp, height = 36.dp)
        }
        message?.let { Text(it, style = frizStyle(12.sp, Color(0xFFFF6A5A))) }
    }
}
