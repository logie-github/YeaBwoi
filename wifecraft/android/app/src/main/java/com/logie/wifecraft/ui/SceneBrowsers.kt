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
                    Text("No scenes available", style = frizStyle(14.sp))
                }
            } else {
                LazyVerticalGrid(
                    columns = if (sideBySide) GridCells.Fixed(1) else GridCells.Adaptive(84.dp),
                    modifier = area,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(locations, key = { it.id }) { entry ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            ItemCard(
                                AnnotatedString(entry.name), icon, 76.dp, entry.id == location,
                                favourite = false, onClick = { onLocation(entry.id) }, onFavourite = null,
                                selection = entry.id == location,
                            )
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
    val art = LocalArt.current
    val progress by loader.progress.collectAsState()
    val loading = progress.takeIf { it.id == preset.id }
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
        Text(preset.name, style = frizStyle(15.sp), maxLines = 1)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().padding(5.dp).background(Color.Black)) {
                BrowserSurface(view, Modifier.matchParentSize())
            }
            if (completed != true) {
                Column(
                    Modifier.matchParentSize().padding(5.dp).background(Color(0xEE15110C)).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (completed == false) {
                        Text("Scene preview unavailable", style = frizStyle(13.sp), textAlign = TextAlign.Center)
                        RedButton("Retry preview", { retry++ })
                    } else {
                        val total = loading?.total ?: 0
                        val done = loading?.done ?: 0
                        Text(if (total > 0 && done < total) "Downloading scene  $done / $total" else "Loading scene…",
                            style = frizStyle(13.sp), textAlign = TextAlign.Center)
                        if (total > 0 && done < total) {
                            LoadingBar(done.toFloat() / total, Modifier.fillMaxWidth().height(12.dp))
                        }
                    }
                }
            }
            Canvas(Modifier.matchParentSize()) {
                drawMetalFrame(art, minOf(14.dp.toPx(), size.minDimension / 2f))
            }
        }
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
        val size = if (maxHeight < 235.dp) 72.dp else 96.dp
        val newNpc: @Composable (Modifier) -> Unit = { area ->
            RedButton("Create NPC", onCreate, area, textSize = 13.sp)
        }
        val rosterGrid: @Composable (Modifier) -> Unit = { area ->
            if (shown.isEmpty()) {
                Box(area, contentAlignment = Alignment.Center) {
                    Text(if (search.isBlank()) "Create an NPC to add to the scene." else "No NPCs match your search.",
                        style = frizStyle(14.sp))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(size + 8.dp),
                    modifier = area,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { actor ->
                        val preview = portraitActor(actor)
                        var picture by remember(preview, portraits) { mutableStateOf<ImageBitmap?>(null) }
                        DisposableEffect(preview, portraits) {
                            var active = true
                            if (preview != null) {
                                portraits?.requestNpc(preview.appearance, actor.id.hashCode(), 192) {
                                    if (active) picture = it?.asImageBitmap()
                                }
                            }
                            onDispose { active = false }
                        }
                        val inCast = actor.id in cast
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            ItemCard(
                                AnnotatedString(actor.name), picture ?: icon, size, inCast,
                                favourite = false, onClick = { onToggle(actor.id) }, onFavourite = null,
                                enabled = inCast || cast.size < 6, selection = inCast,
                            )
                        }
                    }
                }
            }
        }
        if (maxWidth >= 410.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WowSearchBox(search, { search = it }, "Find NPCs", Modifier.fillMaxWidth())
                    rosterGrid(Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.width(148.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    newNpc(Modifier.fillMaxWidth())
                    Text("Cast  ${cast.size} / 6", style = frizStyle(13.sp))
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        for (actor in roster) if (actor.id in cast) {
                            WowCheckRow(actor.name, true, { onToggle(actor.id) }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    WowSearchBox(search, { search = it }, "Find NPCs", Modifier.weight(1f))
                    newNpc(Modifier)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cast  ${cast.size} / 6", style = frizStyle(13.sp), maxLines = 1)
                    if (cast.isNotEmpty()) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (actor in roster) if (actor.id in cast) {
                                WowCheckRow(actor.name, true, { onToggle(actor.id) })
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
