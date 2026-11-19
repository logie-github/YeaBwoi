package com.logie.wifecraft.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
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
import com.logie.wifecraft.anim.ClipType
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

@Composable
internal fun AnimationBrowser(
    clips: List<CheckedAnimation>,
    action: String?,
    current: String?,
    preview: @Composable (CheckedAnimation) -> Unit,
    onClose: () -> Unit,
    onAssign: ((String?) -> Unit)?,
) {
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf(current) }
    val selected = clips.firstOrNull { it.clip.id == selectedId }
    val grouped = remember(clips, query) {
        clips.filter {
            val search = query.trim()
            it.clip.name.contains(search, ignoreCase = true) ||
                it.clip.category.contains(search, ignoreCase = true)
        }.groupBy { it.inspection.type }
    }
    val list = rememberLazyListState()
    LaunchedEffect(query) { list.scrollToItem(0) }
    Overlay(onClose) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val width = (maxWidth - 24.dp).coerceAtMost(1000.dp)
            val height = (maxHeight - 24.dp).coerceAtMost(700.dp)
            WowWindow(action ?: "Browse animations", onClose, Modifier.width(width).height(height)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        WowSearchBox(query, { query = it }, "Find animations", Modifier.fillMaxWidth())
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().warScrollbar(list), list,
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (grouped.isEmpty()) item {
                                Text(if (clips.isEmpty()) "Import an animation pack to see its clips." else "No matching animations.",
                                    Modifier.padding(8.dp), style = frizStyle(13.sp))
                            }
                            for (type in ClipType.entries) {
                                val entries = grouped[type].orEmpty()
                                if (entries.isEmpty()) continue
                                item(key = type.name) {
                                    Text("${type.label} (${entries.size})", Modifier.padding(top = 5.dp), style = frizStyle(14.sp))
                                }
                                items(entries, key = { it.clip.id }) { entry ->
                                    val clip = entry.clip
                                    val detail = if (entry.inspection.type == ClipType.POSE) clip.category else
                                        "${"%.1f s".format(clip.duration)} · ${clip.category}"
                                    WowListRow(clip.name, detail, selected = clip.id == selectedId, onClick = { selectedId = clip.id })
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val reason = selected?.inspection?.reason
                            when {
                                reason != null -> Text(reason, Modifier.padding(8.dp), style = frizStyle(14.sp))
                                selected != null -> preview(selected)
                                else -> Text("Choose a clip to preview.", style = frizStyle(14.sp))
                            }
                        }
                        if (onAssign != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                RedButton("Warcraft default", { onAssign(null) }, Modifier.weight(1f), textSize = 12.sp)
                                RedButton("Assign", { selected?.let { onAssign(it.clip.id) } }, Modifier.weight(1f),
                                    enabled = selected != null && selected.inspection.type != ClipType.INVALID, textSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

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
            if (message != null) Text(message, Modifier.background(Color(0xDD191719)).padding(6.dp), style = frizStyle(14.sp))
        }
        val matched = mapped
        val summary = when {
            matched == null || matched == 0 -> name
            matched < animation.tracks.size -> "$name · $matched of ${animation.tracks.size} tracks matched"
            animation.omittedChannels -> "$name · Some source channels omitted"
            else -> "$name · $matched tracks matched"
        }
        Text(summary, style = frizStyle(12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RedButton("Replay", { replay++ }, Modifier.weight(1f), enabled = ready, textSize = 12.sp)
            RedButton("Rotate", { angle = (angle + 1) % 4 }, Modifier.weight(1f), enabled = ready, textSize = 12.sp)
        }
    }
}
