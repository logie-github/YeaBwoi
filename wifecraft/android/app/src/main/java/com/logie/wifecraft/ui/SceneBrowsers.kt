package com.logie.wifecraft.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.engine.LocationLoader
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.engine.Thumbs
import com.logie.wifecraft.location.LocationInfo

@Composable
internal fun SceneBrowser(
    locations: List<LocationInfo>,
    location: String?,
    onLocation: (String) -> Unit,
    view: SceneView,
    loader: LocationLoader,
    modifier: Modifier,
) {
    val preset = locations.firstOrNull { it.id == location } ?: locations.firstOrNull()
    val icon = LocalWar.current?.icon("menu.location")
    BoxWithConstraints(modifier) {
        val sideBySide = maxWidth >= 460.dp || maxHeight < 300.dp && maxWidth >= 285.dp
        val cards: @Composable (Modifier) -> Unit = { area ->
            if (locations.isEmpty()) {
                Box(area, contentAlignment = Alignment.Center) {
                    Text("No scenes available", style = WifeTheme.bodyStyle(WifeTheme.textDim))
                }
            } else {
                LazyVerticalGrid(
                    columns = if (sideBySide) GridCells.Fixed(1) else GridCells.Adaptive(84.dp),
                    modifier = area,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(locations, key = { it.id }, contentType = { 0 }) { entry ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            WifeCard(entry.name, icon, 76.dp, entry.id == location, onClick = { onLocation(entry.id) })
                        }
                    }
                }
            }
        }
        if (sideBySide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                cards(Modifier.width(112.dp).fillMaxHeight())
                if (preset != null) LocationPreview(preset, view, loader, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (preset != null) LocationPreview(preset, view, loader, Modifier.weight(1.65f).fillMaxWidth())
                cards(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LocationPreview(preset: LocationInfo, view: SceneView, loader: LocationLoader, modifier: Modifier) {
    var retry by remember(preset.id) { mutableIntStateOf(0) }
    var completed by remember(preset.id, retry) { mutableStateOf<Boolean?>(null) }
    DisposableEffect(view, preset.id, retry) {
        view.showCity(false)
        view.frame = checkNotNull(preset.views["inside"]?.camera) to emptyMap()
        loader.load(view, preset.id, "inside", sound = false) { completed = it }
        onDispose {
            loader.cancel()
            view.clearLocation()
            view.frame = null
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(preset.name, style = WifeTheme.headingStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val shape = RoundedCornerShape(WifeTheme.buttonRadius)
        Box(Modifier.weight(1f).fillMaxWidth().clip(shape).background(Color.Black), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize()) {
                BrowserSurface(view, Modifier.matchParentSize())
            }
            if (completed != true) {
                Column(
                    Modifier.matchParentSize().background(WifeTheme.slateDeep).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (completed == false) {
                        Text("Scene preview unavailable", style = WifeTheme.bodyStyle(), textAlign = TextAlign.Center)
                        WifeButton("Retry preview", { retry++ })
                    } else {
                        PreviewProgress(loader, preset.id)
                    }
                }
            }
            Box(Modifier.matchParentSize().border(1.dp, WifeTheme.trim.copy(alpha = 0.8f), shape))
        }
    }
}

/**
 * The preview's download count and bar: its own composable, so the loader's ticks (one per file, at most 4 a second here)
 * redraw only these, not the preview, its frame and the cards.
 */
@Composable
private fun PreviewProgress(loader: LocationLoader, id: String) {
    val progress = loader.progress.collectThrottled { old, new -> old?.id != new.id || new.error != null || new.ready }
    val counts by remember(id) {
        derivedStateOf { progress.value.takeIf { it.id == id }?.let { it.done to it.total } ?: (0 to 0) }
    }
    val (done, total) = counts
    val downloading = total > 0 && done < total
    Text(if (downloading) "Downloading scene  $done / $total" else "Loading scene…", style = WifeTheme.bodyStyle(), textAlign = TextAlign.Center)
    // the bar's room is kept while there is nothing to count: the text doesn't jump up and down as it comes and goes
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(12.dp)) {
        if (downloading) WifeProgress(done.toFloat() / total)
    }
}

@Composable
internal fun NpcBrowser(
    roster: List<Actor>,
    cast: Set<String>,
    onToggle: (String) -> Unit,
    onCreate: () -> Unit,
    view: SceneView,
    portraits: Thumbs?,
    portraitActor: (Actor) -> Actor?,
    modifier: Modifier,
) {
    var search by remember { mutableStateOf("") }
    val shown = remember(roster, search) { roster.filter { it.name.contains(search.trim(), ignoreCase = true) } }
    val icon = LocalWar.current?.icon("tab.npcs")
    BoxWithConstraints(modifier) {
        // Thumbnails render off-screen on the same GL context that the creator and live scene use.
        BrowserSurface(view, Modifier.size(1.dp))
        val side = if (maxHeight < 235.dp) 72.dp else 96.dp
        val newNpc: @Composable (Modifier) -> Unit = { area ->
            WifeButton("Create NPC", onCreate, area, kind = WifeButtonKind.PRIMARY, sound = "create")
        }
        val rosterGrid: @Composable (Modifier) -> Unit = { area ->
            if (shown.isEmpty()) {
                Box(area, contentAlignment = Alignment.Center) {
                    Text(if (search.isBlank()) "Create an NPC to add to the scene." else "No NPCs match your search.",
                        style = WifeTheme.bodyStyle(WifeTheme.textDim), textAlign = TextAlign.Center)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(side + 8.dp),
                    modifier = area,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }, contentType = { 0 }) { actor ->
                        // dressing a look (the starter clothes) once per actor, not on every redraw of the grid
                        val preview = remember(actor, portraitActor) { portraitActor(actor) }
                        // a portrait already made shows at once (no placeholder frame when scrolled back)
                        var picture by remember(preview, portraits) {
                            mutableStateOf<ImageBitmap?>(
                                preview?.let { portraits?.cachedNpc(it.appearance, actor.id.hashCode(), 192)?.asImageBitmap() })
                        }
                        DisposableEffect(preview, portraits) {
                            var active = true
                            // a request is cancelled when its cell leaves the screen
                            val ticket = if (preview != null && picture == null) {
                                portraits?.requestNpc(preview.appearance, actor.id.hashCode(), 192) {
                                    if (active) picture = it?.asImageBitmap()
                                }
                            } else null
                            onDispose { active = false; ticket?.cancel() }
                        }
                        val inCast = actor.id in cast
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            WifeCard(actor.name, picture ?: icon, side, inCast, onClick = { onToggle(actor.id) }, enabled = inCast || cast.size < 6)
                        }
                    }
                }
            }
        }
        if (maxWidth >= 410.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SceneSearchField(search, { search = it.take(40) }, "Find NPCs", Modifier.fillMaxWidth())
                    rosterGrid(Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.width(148.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    newNpc(Modifier.fillMaxWidth())
                    WifeSectionHeader("Cast  ${cast.size} / 6")
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        for (actor in roster) if (actor.id in cast) {
                            WifeChip(actor.name, { onToggle(actor.id) })
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SceneSearchField(search, { search = it.take(40) }, "Find NPCs", Modifier.weight(1f))
                    newNpc(Modifier)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cast  ${cast.size} / 6", style = WifeTheme.labelStyle(WifeTheme.gold), maxLines = 1)
                    if (cast.isNotEmpty()) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                            for (actor in roster) if (actor.id in cast) {
                                WifeChip(actor.name, { onToggle(actor.id) })
                            }
                        }
                    }
                }
                rosterGrid(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun BrowserSurface(view: SceneView, modifier: Modifier) {
    AndroidView(
        factory = {
            (view.parent as? ViewGroup)?.removeView(view)
            view
        },
        modifier = modifier,
        update = {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },
        onRelease = {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        },
    )
}
