package com.logie.wifecraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// What the player sees of the build-mode art's loading (WarArt): "Preparing the interface: n of m" over the editor the first
// time it opens (the art is fetched once), and the Options' "Interface art" row: how many pieces are here, what failed, Retry.

/**
 * Over the editor while its art is first fetched: the count, a bar, the failures with Retry, and Continue (the buttons then
 * draw their glyphs until their pictures arrive). Shown only when the art was not all here when the app started.
 */
@Composable
fun WarArtOverlay(war: WarArt?) {
    war ?: return
    val s = war.status
    if (!war.firstOpen || war.overlayDismissed || s.loaded >= s.total) return
    Box(
        Modifier.fillMaxSize().background(Color(0xD0140D08)).pointerInput(Unit) {
            // the editor under it waits: every touch stops here
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        },
        contentAlignment = Alignment.Center,
    ) {
        BookPage(Modifier.widthIn(max = 480.dp).padding(24.dp), ribbon = false) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Preparing the interface", style = warTitle(22.sp))
                Text("${s.loaded} of ${s.total} pieces", style = warText(15.sp))
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFBFA06A))) {
                    Box(Modifier.fillMaxWidth(if (s.total > 0) s.loaded / s.total.toFloat() else 0f).height(10.dp).background(War.amber))
                }
                Text("The game's own buttons and icons, fetched once (about 58 MB).", style = warText(12.sp, War.brownSoft))
                ArtFailures(s)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (s.failures.isNotEmpty()) PillButton("Retry", { war.retry() }, glyph = WarIcons.UNDO)
                    PillButton("Continue", { war.overlayDismissed = true }, glyph = WarIcons.PLAY)
                }
            }
        }
    }
}

/** The Options' row: "Interface art: N of M pieces loaded", the failures, and Retry. */
@Composable
fun WarArtRow(war: WarArt?) {
    war ?: return
    val s = war.status
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val state = when {
            s.loaded >= s.total -> "all here"
            s.busy -> "loading"
            s.failures.isNotEmpty() -> "some failed"
            else -> "not fetched yet"
        }
        Text("Interface art: ${s.loaded} of ${s.total} pieces loaded ($state)", style = frizStyle(13.sp, Color.White))
        ArtFailures(s, dark = true)
        if (s.loaded < s.total && !s.busy) WowButton("Retry", { war.retry() }, width = 150.dp, height = 38.dp)
    }
}

@Composable
private fun ArtFailures(s: ArtStatus, dark: Boolean = false) {
    if (s.failures.isEmpty()) return
    val color = if (dark) Color(0xFFFF9A7A) else War.danger
    // the seconds left, counted down once a second while a retry is pending (and not at all when none is)
    val at = s.nextRetryAt
    val secondsLeft by produceState<Long?>(at?.let { secondsUntil(it) }, at) {
        if (at == null) { value = null; return@produceState }
        while (true) {
            value = secondsUntil(at)
            if (System.currentTimeMillis() >= at) break
            // to the next whole second left (where the count changes)
            val left = at - System.currentTimeMillis()
            delay((left % 1000L).let { if (it <= 0L) 1000L else it })
        }
    }
    Text(
        "Failed: " + s.failures.take(4).joinToString("; ") + (if (s.failures.size > 4) "; and ${s.failures.size - 4} more" else "") +
            (secondsLeft?.let { " (tried again in $it s)" } ?: ""),
        style = if (dark) frizStyle(12.sp, color) else warText(12.sp, color), maxLines = 6,
    )
}

private fun secondsUntil(at: Long): Long = ((at - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(1)
