package com.logie.wifecraft.ui

import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.logie.wifecraft.anim.CheckedAnimation
import com.logie.wifecraft.anim.AnimationChoice
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.data.Lights
import com.logie.wifecraft.engine.Dresser
import com.logie.wifecraft.engine.Placement
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.story.Vec3
import java.util.UUID
import kotlin.math.PI

internal enum class AnimationOrderMode(val label: String) {
    PLAY("Play now"), QUEUE("Queue"), REPEAT("Repeat"), SEQUENCE("Queue stages"),
}

/**
 * The animations window (Play animation in a scene, Browse animations in the library) in the front menus' look: category
 * tabs ([SceneHud.animationTabs]: All, each group, Partner; Partner first while the NPC has a partner), a search, rows 56 dp
 * high. A tap picks a row; [onPreview] plays it at once with the window kept open, the footer's main button plays (or
 * queues, repeats) it with the chosen [AnimationOrderMode]; in the library ([onPlay] null) the picked one plays in [preview].
 */
@Composable
internal fun AnimationBrowser(
    choices: List<AnimationChoice>,
    preview: (@Composable (CheckedAnimation) -> Unit)?,
    onClose: () -> Unit,
    onPlay: ((AnimationChoice, AnimationOrderMode) -> Unit)? = null,
    notice: String? = null,
    /** Plays the picked one now on the NPC, the window kept open (the scene's preview). */
    onPreview: ((AnimationChoice) -> Unit)? = null,
    /** The controlled NPC's partner (the Partner tab first), and the NPC itself (the subtitle). */
    partnerName: String? = null,
    actorName: String? = null,
) {
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(AnimationOrderMode.PLAY) }
    val tabs = remember(choices, partnerName) {
        SceneHud.animationTabs(choices.map { it.group }, choices.any { it.participantCount == 2 }, partnerName != null)
    }
    var tab by remember(tabs) { mutableIntStateOf(0) }
    val tabName = tabs.getOrElse(tab) { SceneHud.ALL_TAB }
    val selected = choices.firstOrNull { it.id == selection }
    val grouped = remember(choices, query, tabName) {
        val search = query.trim()
        choices.filter {
            SceneHud.inTab(tabName, it.group, it.participantCount) && (it.name.contains(search, ignoreCase = true) ||
                it.group.contains(search, ignoreCase = true) || it.preview.clip.category.contains(search, ignoreCase = true) ||
                it.setting.contains(search, ignoreCase = true))
        }.groupBy { it.group }
    }
    val list = rememberLazyListState()
    LaunchedEffect(query, tabName) { list.scrollToItem(0) }
    val canPlay = selected != null && selected.playable
    Overlay(onClose) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            val width = (maxWidth - 24.dp).coerceAtMost(1000.dp)
            val height = (maxHeight - 24.dp).coerceAtMost(700.dp)
            val split = width >= 640.dp
            WifeWindow(
                if (onPlay == null) "Browse animations" else "Play animation", onClose, Modifier.width(width).height(height),
                subtitle = actorName?.let { who -> partnerName?.let { "$who with $it" } ?: who },
                tabs = tabs, tab = tab, onTab = { tab = it },
                footer = {
                    if (onPlay != null) {
                        Text(selected?.name ?: "Choose an animation", Modifier.weight(1f), style = WifeTheme.captionStyle(), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        if (onPreview != null) WifeButton("Preview", { selected?.let(onPreview) }, Modifier.widthIn(min = 112.dp), enabled = canPlay)
                        WifeButton(mode.label, { selected?.let { onPlay(it, mode) } }, Modifier.widthIn(min = 132.dp), enabled = canPlay,
                            kind = WifeButtonKind.PRIMARY, sound = "ok")
                    } else {
                        WifeButton("Close", onClose, Modifier.widthIn(min = 120.dp), sound = "close")
                    }
                },
            ) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapM)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                        SceneSearchField(query, { query = it.take(40) }, "Find animations", Modifier.fillMaxWidth())
                        if (!split && onPlay != null) OrderModes(mode) { mode = it }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().warScrollbar(list), list,
                            verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                            if (grouped.isEmpty()) item {
                                WifeNote(if (choices.isEmpty()) "Import an animation pack to see its clips." else "No matching animations.",
                                    Modifier.padding(WifeTheme.gapS))
                            }
                            for ((group, entries) in grouped) {
                                if (tabName != group) item(key = "group:$group") { WifeSectionHeader("$group  ${entries.size}") }
                                items(entries, key = { it.id }) { choice ->
                                    AnimRow(choice, choice.id == selection, { selection = choice.id }, Modifier.padding(end = 10.dp))
                                }
                            }
                        }
                        if (!split && preview != null && selected != null && selected.preview.inspection.reason == null) {
                            Box(Modifier.weight(1f).fillMaxWidth()) { preview(selected.preview) }
                        }
                    }
                    if (split) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                        if (onPlay != null) OrderModes(mode) { mode = it }
                        if (selected == null) {
                            WifeNote(when {
                                onPlay == null -> "Choose an animation to preview."
                                mode == AnimationOrderMode.SEQUENCE -> "Choose a stage to queue it and its linked stages."
                                mode == AnimationOrderMode.REPEAT -> "Choose an animation to repeat until stopped or another clip is queued."
                                mode == AnimationOrderMode.QUEUE -> "Choose animations in the order you want them to play."
                                partnerName != null -> "Partner animations play with $partnerName."
                                else -> "Partner animations ask for a second NPC."
                            }, color = WifeTheme.text)
                        } else {
                            Text(selected.name, style = WifeTheme.titleStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            WifeNote(details(selected), color = WifeTheme.text)
                            if (selected.participantCount == 2) WifeNote(partnerName?.let { "Plays with $it." } ?: "Asks for a second NPC.", color = PairRose)
                            if (!selected.playable) WifeNote(selected.preview.inspection.reason ?: "A required actor track is missing.", color = WifeTheme.danger)
                            if (preview != null && selected.preview.inspection.reason == null) {
                                Box(Modifier.weight(1f).fillMaxWidth()) { preview(selected.preview) }
                            }
                        }
                        if (notice != null) WifeNote(notice)
                    }
                }
            }
        }
    }
}

/** What a row says under an animation's name: how many NPCs, the setting, the cycles, a missing track. */
private fun details(choice: AnimationChoice): String {
    val count = "${choice.participantCount} NPC${if (choice.participantCount == 1) "" else "s"}"
    return listOfNotNull(count, choice.setting.takeIf(String::isNotBlank), "${choice.repetitions} cycles".takeIf { choice.repetitions > 1 },
        "Missing track".takeUnless { choice.playable }).joinToString(" · ")
}

/** How a scene's animation is ordered: Play now, Queue, Repeat, Queue stages (tabs of their own, 48 dp). */
@Composable
private fun OrderModes(mode: AnimationOrderMode, onMode: (AnimationOrderMode) -> Unit) {
    WifeTabs(AnimationOrderMode.entries.map { it.label }, mode.ordinal, { onMode(AnimationOrderMode.entries[it]) })
}

/** One animation: its name, what it is, a rose "2 NPCs" mark on partner ones; 56 dp high, lit gold when picked. */
@Composable
private fun AnimRow(choice: AnimationChoice, picked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val reduced = reduceMotion()
    val edge by animateColorAsState(if (picked) WifeTheme.gold else WifeTheme.trimSoft, tween(MenuMotion.duration(FrontMotion.GLOW_MS, reduced)), label = "row")
    val shape = RoundedCornerShape(WifeTheme.buttonRadius)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (picked) Color(0x26FFD37A) else WifeTheme.slateRaised.copy(alpha = 0.55f))
            .border(if (picked) 1.5.dp else WifeTheme.trimWidth, edge, shape)
            .semantics { this.selected = picked }
            .warPress(sound = "select", onClick = onClick)
            .padding(horizontal = WifeTheme.gapM, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS),
    ) {
        Column(Modifier.weight(1f)) {
            Text(choice.name, style = frizStyle(WifeTheme.label, if (!choice.playable) WifeTheme.textDim else if (picked) Color.White else WifeTheme.gold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(details(choice), style = WifeTheme.captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (choice.participantCount == 2) {
            val pill = RoundedCornerShape(50)
            Text("2 NPCs", Modifier.clip(pill).border(1.dp, PairRose, pill).padding(horizontal = WifeTheme.gapS, vertical = 2.dp),
                style = WifeTheme.captionStyle(PairRose), maxLines = 1)
        }
    }
}

/** The search field in the front menus' look: a dark rounded field, a drawn magnifier, a clear x that keeps its room. */
@Composable
internal fun SceneSearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier.height(WifeTheme.touch).clip(shape).background(WifeTheme.track)
            .border(if (focused) 1.5.dp else WifeTheme.trimWidth, if (focused) WifeTheme.gold else WifeTheme.trimSoft, shape)
            .padding(start = WifeTheme.gapM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val r = this.size.minDimension * 0.34f
            val c = Offset(r + 1.dp.toPx(), r + 1.dp.toPx())
            drawCircle(WifeTheme.trim, r, c, style = Stroke(1.6.dp.toPx()))
            drawLine(WifeTheme.trim, Offset(c.x + r * 0.7f, c.y + r * 0.7f), Offset(this.size.width, this.size.height), 1.8.dp.toPx())
        }
        Box(Modifier.weight(1f).padding(horizontal = WifeTheme.gapS)) {
            if (value.isEmpty()) Text(hint, style = WifeTheme.bodyStyle(WifeTheme.textDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onChange, singleLine = true, textStyle = frizStyle(WifeTheme.body, WifeTheme.text, shadow = false),
                cursorBrush = SolidColor(WifeTheme.gold), modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
        }
        // the clear button keeps its room while the field is empty (hidden, untappable): the first letter moves nothing
        Box(
            Modifier.size(WifeTheme.touch).graphicsLayer { alpha = if (value.isEmpty()) 0f else 1f }
                .semantics { contentDescription = "Clear" }
                .warPress(value.isNotEmpty(), sound = "close") { onChange("") },
            contentAlignment = Alignment.Center,
        ) {
            Text("×", style = frizStyle(WifeTheme.heading, WifeTheme.gold), maxLines = 1)
        }
    }
}

private val PairRose = Color(0xFFE88AA8)

@Composable
internal fun AnimationViewport(
    view: SceneView,
    dresser: Dresser,
    catalog: Catalog,
    appearance: Appearance,
    name: String,
    checked: CheckedAnimation,
) {
    val actorKey = remember { "animation-preview:${UUID.randomUUID()}" }
    var ready by remember(appearance) { mutableStateOf(false) }
    var replay by remember(checked.clip.id) { mutableIntStateOf(0) }
    var angle by remember { mutableIntStateOf(0) }
    val animation = remember(checked) { checkNotNull(checked.forPlayback()) }
    var mapped by remember(animation, replay) { mutableStateOf<Int?>(null) }

    DisposableEffect(view, actorKey, appearance) {
        var active = true
        view.showCity(false)
        view.clearLocation()
        view.light(Lights.of("day"))
        dresser.dress(view, catalog, actorKey, appearance) { if (active) ready = true }
        onDispose {
            active = false
            dresser.cancel(actorKey)
            view.stopAnim(actorKey)
            view.removeActor(actorKey)
            view.frame = null
        }
    }

    LaunchedEffect(view, actorKey, animation, ready, replay) {
        if (ready) view.playAnim(actorKey, animation) { mapped = it }
    }
    LaunchedEffect(view, animation, angle) {
        val camera = CreatorCamera.pose(1f, 0.5f)
        val offsets = animation.tracks.firstOrNull { it.bone == "Root" && it.channel == Channel.POSITION }?.values
        val orbit = OrbitCamera.from(camera)
        val radius = offsets?.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtMost(4f) ?: 0f
        val previewCamera = orbit.copy(yaw = PI.toFloat() * 0.25f + angle * PI.toFloat() / 2f,
            pitch = 0.18f, distance = orbit.distance + radius * 1.8f)
        view.frame = previewCamera.pose() to mapOf(actorKey to Placement(0f, 0f, 0f, 0f))
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(factory = {
                (view.parent as? ViewGroup)?.removeView(view)
                view
            }, modifier = Modifier.matchParentSize())
            val message = when {
                !ready || mapped == null -> "Loading $name…"
                mapped == 0 -> "$name could not play this clip."
                else -> null
            }
            if (message != null) Text(message, Modifier.clip(RoundedCornerShape(WifeTheme.buttonRadius)).background(WifeTheme.slate)
                .padding(horizontal = WifeTheme.gapM, vertical = 6.dp), style = WifeTheme.bodyStyle())
        }
        val matched = mapped
        val summary = when {
            matched == null || matched == 0 -> name
            matched < animation.tracks.size -> "$name · $matched of ${animation.tracks.size} tracks matched"
            animation.omittedChannels -> "$name · Some source channels omitted"
            else -> "$name · $matched tracks matched"
        }
        Text(summary, style = WifeTheme.captionStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
            WifeButton("Replay", { replay++ }, Modifier.weight(1f), enabled = ready)
            WifeButton("Rotate", { angle = (angle + 1) % 4 }, Modifier.weight(1f), enabled = ready)
        }
    }
}
