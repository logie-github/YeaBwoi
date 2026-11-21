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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.logie.wifecraft.creator.PartCut
import com.logie.wifecraft.creator.PartOptions
import com.logie.wifecraft.creator.PartPlacement
import com.logie.wifecraft.creator.PartSize
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.editor.PlaceKind
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
                            // the card names the building ("Lion's Pride Inn"); the preview above it the whole name, the town too
                            WifeCard(entry.name.substringBefore(", "), icon, 76.dp, entry.id == location, onClick = { onLocation(entry.id) })
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
    val actions = LocalNpcActions.current
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
                        // a named character drawn as the game's own NPC display: its picture is the display's
                        val display = remember(actor, actions) { actions?.portraitDisplay?.invoke(actor) ?: 0 }
                        // a portrait already made shows at once (no placeholder frame when scrolled back)
                        var picture by remember(preview, portraits, display) {
                            mutableStateOf<ImageBitmap?>(
                                if (display > 0) portraits?.cached(PlaceKind.CREATURE, display, 192)?.asImageBitmap()
                                else preview?.let { portraits?.cachedNpc(it.appearance, actor.id.hashCode(), 192)?.asImageBitmap() })
                        }
                        DisposableEffect(preview, portraits, display) {
                            var active = true
                            // a request is cancelled when its cell leaves the screen
                            val ticket = when {
                                picture != null -> null
                                display > 0 -> portraits?.request(PlaceKind.CREATURE, display, 192) { if (active) picture = it?.asImageBitmap() }
                                preview != null -> portraits?.requestNpc(preview.appearance, actor.id.hashCode(), 192) {
                                    if (active) picture = it?.asImageBitmap()
                                }
                                else -> null
                            }
                            onDispose { active = false; ticket?.cancel() }
                        }
                        val inCast = actor.id in cast
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.width(side)) {
                                WifeCard(actor.name, picture ?: icon, side, inCast, onClick = { onToggle(actor.id) }, enabled = inCast || cast.size < 6)
                                if (actions != null && actions.hasMenu(actor)) {
                                    NpcMenuButton(actor.name, { actions.open(actor) }, Modifier.align(Alignment.TopEnd))
                                }
                            }
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

/**
 * What the NPC picker's cards offer beyond choosing (MainActivity provides it around the scene's setup, [LocalNpcActions]):
 * [hasMenu] whether a card has its menu button (a named character's look, a man's body-part settings, Delete for an NPC the
 * user made), [open] opens that menu, [portraitDisplay] the game's NPC display a card's picture is drawn from (a named
 * character in one of its own displays), 0 for the NPC's own look.
 */
class NpcActions(
    val hasMenu: (Actor) -> Boolean,
    val open: (Actor) -> Unit,
    val portraitDisplay: (Actor) -> Int = { 0 },
)

/** The NPC picker's [NpcActions]; none: the cards have no menu button. */
val LocalNpcActions = compositionLocalOf<NpcActions?> { null }

/**
 * The live scene's body-part switch for the controlled NPC (MainActivity provides it around the live scene,
 * [LocalPartControl]): [has] whether the NPC [has] wears the parts (a man with the add-on imported and nothing on his lower
 * body), [isOn] its ON state, [toggle] switches it. Reads Compose state: a screen that calls [has] or [isOn] is redrawn
 * when they change.
 */
interface PartControl {
    fun has(npcId: String): Boolean
    fun isOn(npcId: String): Boolean
    fun toggle(npcId: String)
}

/** The live scene's [PartControl]; none outside a live scene. */
val LocalPartControl = compositionLocalOf<PartControl?> { null }

/** A card's small round menu button (three dots), at the card's top corner. */
@Composable
private fun NpcMenuButton(name: String, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.size(40.dp).semantics { contentDescription = "$name options" }.warPress(sound = "click", onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(WifeTheme.slateDeep).border(1.dp, WifeTheme.trim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("\u22EF", style = frizStyle(14.sp, WifeTheme.gold), textAlign = TextAlign.Center)
        }
    }
}

/**
 * An NPC's menu from the picker, in the front menus' window: a named character's look ([variants]: key to label, [variant]
 * the one chosen), a man's body-part settings ([parts]: null for anyone else; [partsImported] false says they wait for an
 * import), and Delete for an NPC the user made ([canDelete]; a built-in NPC or named character has none). Each choice is
 * kept at once by the caller.
 */
@Composable
internal fun NpcMenuWindow(
    actor: Actor,
    variants: List<Pair<String, String>>,
    variant: String?,
    onVariant: (String) -> Unit,
    parts: PartOptions?,
    partsImported: Boolean,
    onParts: (PartOptions) -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    /** The Gear switch (characters/GearSwitch): on, class clothing or a named character's outfit; off, nude. Null: none. */
    gear: Boolean? = null,
    onGear: (Boolean) -> Unit = {},
) {
    WifeWindow(
        actor.name, onClose, Modifier.widthIn(max = 520.dp).heightIn(max = 560.dp).padding(16.dp),
        subtitle = if (canDelete) "Your NPC" else "Built-in NPC",
        footer = {
            if (canDelete) WifeButton("Delete NPC", onDelete, kind = WifeButtonKind.SECONDARY, sound = "click")
            WifeButton("Done", onClose, Modifier.widthIn(min = 120.dp), kind = WifeButtonKind.PRIMARY, sound = "close")
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
            if (gear != null) NpcGearRow(gear, onGear, named = variants.isNotEmpty())
            if (variants.isNotEmpty()) {
                WifeSectionHeader("Look")
                for ((key, label) in variants) {
                    WifeButton(label, { onVariant(key) }, Modifier.fillMaxWidth(),
                        kind = if (key == variant) WifeButtonKind.PRIMARY else WifeButtonKind.SECONDARY, sound = "select")
                }
            }
            if (parts != null) {
                WifeSectionHeader("Body parts")
                if (!partsImported) WifeNote("Import body parts in Options (Mods) for these to show. They show only while nothing is worn below the waist.")
                else WifeNote("Shown while nothing is worn below the waist; switched with On / Off in a scene.")
                PartRow("Size", PartSize.values().map { it.label }, parts.size.ordinal) { onParts(parts.copy(size = PartSize.values()[it])) }
                PartRow("Cut", PartCut.values().map { it.label }, parts.cut.ordinal) { onParts(parts.copy(cut = PartCut.values()[it])) }
                PartRow("Scrotum", PartPlacement.values().map { it.label }, parts.placement.ordinal) { onParts(parts.copy(placement = PartPlacement.values()[it])) }
            }
            if (variants.isEmpty() && parts == null && gear == null && canDelete) {
                WifeNote("Delete takes this NPC out of your roster for good.")
            }
        }
    }
}

/** One setting's choices as a row of buttons, [chosen] lit. */
@Composable
private fun PartRow(label: String, choices: List<String>, chosen: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        Text(label, style = WifeTheme.labelStyle(WifeTheme.gold), modifier = Modifier.width(84.dp), maxLines = 1)
        choices.forEachIndexed { i, text ->
            WifeButton(text, { onPick(i) }, Modifier.weight(1f), kind = if (i == chosen) WifeButtonKind.PRIMARY else WifeButtonKind.SECONDARY, sound = "select")
        }
    }
}

/** The question before an NPC the user made is deleted: Cancel, or Delete for good. */
@Composable
internal fun NpcDeleteWindow(name: String, onCancel: () -> Unit, onDelete: () -> Unit) {
    WifeWindow(
        "Delete NPC", onCancel, Modifier.widthIn(max = 480.dp).padding(16.dp),
        footer = {
            WifeButton("Cancel", onCancel, Modifier.widthIn(min = 112.dp), kind = WifeButtonKind.SECONDARY, sound = "close")
            WifeButton("Delete", onDelete, Modifier.widthIn(min = 112.dp), kind = WifeButtonKind.PRIMARY, sound = "click")
        },
    ) {
        Text("Delete $name for good? They leave the roster and any scene cast; this can't be undone.",
            style = WifeTheme.bodyStyle(), modifier = Modifier.fillMaxWidth())
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
