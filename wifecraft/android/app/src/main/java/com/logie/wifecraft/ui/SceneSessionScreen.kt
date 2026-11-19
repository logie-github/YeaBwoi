package com.logie.wifecraft.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.engine.Hit
import com.logie.wifecraft.engine.Placement
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.simulation.Activity
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.SceneSimulation
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3

enum class SetupStep(val title: String) { LOCATION("Choose Scene"), CAST("Choose NPCs"), OBJECTS("Choose objects") }

@Composable
fun SceneSetupScreen(
    step: SetupStep,
    locations: List<LocationInfo>,
    location: String?,
    onLocation: (String) -> Unit,
    roster: List<Actor>,
    cast: Set<String>,
    onToggleActor: (String) -> Unit,
    objects: Set<SceneObject>,
    onToggleObject: (SceneObject) -> Unit,
    onCreate: () -> Unit,
    onAnimations: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = Color(0xFF191719),
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                OutlinedButton(onBack) { Text("Back") }
                Button(onNext, enabled = when (step) {
                    SetupStep.LOCATION -> location != null
                    SetupStep.CAST -> cast.isNotEmpty()
                    SetupStep.OBJECTS -> true
                }) { Text(if (step == SetupStep.OBJECTS) "Begin scene" else "Next") }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(step.title, style = MaterialTheme.typography.headlineMedium, color = Color(0xFFF4DFBD))
            when (step) {
                SetupStep.LOCATION -> for (preset in locations) {
                    FilterChip(preset.id == location, { onLocation(preset.id) }, { Text(preset.name) })
                }
                SetupStep.CAST -> {
                    Text("Choose up to six NPCs.", color = Color.LightGray)
                    OutlinedButton(onCreate) { Text("Create NPC") }
                    for (actor in roster) {
                        FilterChip(actor.id in cast, { onToggleActor(actor.id) }, { Text(actor.name) },
                            enabled = actor.id in cast || cast.size < 6)
                    }
                }
                SetupStep.OBJECTS -> {
                    Text("Objects are placed automatically in the scene.", color = Color.LightGray)
                    OutlinedButton(onAnimations) { Text("Sims 4 animations") }
                    for (item in SceneObject.entries) {
                        FilterChip(item in objects, { onToggleObject(item) }, { Text(item.label) })
                    }
                }
            }
        }
    }
}

@Composable
fun LiveSceneScreen(
    view: SceneView,
    camera: CameraPose,
    locationName: String,
    cast: List<Actor>,
    objects: Set<SceneObject>,
    actorKey: (String) -> String,
    objectKey: (SceneObject) -> String,
    bookKey: String,
    toStage: (Vec3) -> Vec3,
    stageRotation: Float,
    onAnimate: (String, Activity) -> Unit,
    durations: Map<Activity, Float>,
    onEnd: () -> Unit,
    ready: Boolean,
    status: String?,
) {
    val simulation = remember(durations) { SceneSimulation(cast.map { it.id }, objects, durations = durations) }
    var people by remember { mutableStateOf(simulation.people()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var ending by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf<String?>(null) }
    val names = remember(cast) { cast.associate { it.id to it.name } }
    val active = selected?.let { id -> people.firstOrNull { it.id == id } }

    BackHandler { if (selected != null) selected = null else ending = true }

    fun use(id: String, item: SceneObject) {
        command = if (simulation.use(id, item)) null else "${item.label} is in use."
        people = simulation.people()
    }

    LaunchedEffect(ready, ending) {
        var previous: Long? = null
        var interval = 0f
        while (true) withFrameMillis { now ->
            val elapsed = previous?.let { (now - it).coerceAtMost(100L) / 1000f } ?: 0f
            previous = now
            interval += elapsed
            if (ready && !ending) simulation.tick(elapsed)
            val current = simulation.people()
            view.frame = camera to current.associate { p ->
                val pos = toStage(p.position)
                actorKey(p.id) to Placement(pos.x, pos.y, pos.z, p.facing + stageRotation)
            }
            if (interval > 0.15f) {
                people = current
                interval = 0f
            }
        }
    }
    LaunchedEffect(people.map { it.activity }, durations, onAnimate) {
        for (p in people) onAnimate(p.id, p.activity)
    }
    LaunchedEffect(selected) { view.select(selected?.let(actorKey)); command = null }

    val onTap by rememberUpdatedState<(Hit?) -> Unit>({ hit ->
        when (hit?.kind) {
            Hit.Kind.ACTOR -> cast.firstOrNull { actorKey(it.id) == hit.key }?.let { selected = it.id }
            Hit.Kind.PROP -> if (ready) selected?.let { id -> objects.firstOrNull { objectKey(it) == hit.key || (it == SceneObject.TABLE && hit.key == bookKey) }?.let { use(id, it) } }
            else -> { selected = null }
        }
    })

    Column(Modifier.fillMaxSize().background(Color(0xFF191719))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(locationName, Modifier.weight(1f), color = Color(0xFFF4DFBD), style = MaterialTheme.typography.titleLarge)
            TextButton({ ending = true }) { Text("End scene") }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                { (view.parent as? ViewGroup)?.removeView(view); view },
                Modifier.fillMaxSize().pointerInput(view) {
                    detectTapGestures { point -> view.pick(point.x, point.y, size.width, size.height) { onTap(it) } }
                },
            )
            status?.let {
                Text(it, Modifier.align(Alignment.TopCenter).padding(8.dp).background(Color(0xDD191719)).padding(8.dp),
                    color = Color.White)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (person in people) {
                FilterChip(
                    person.id == selected,
                    { selected = if (person.id == selected) null else person.id },
                    { Text("${names[person.id]} · ${person.activity.description}") },
                )
            }
        }
        if (active != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in objects) {
                        OutlinedButton({ use(active.id, item) }, enabled = ready && simulation.available(item, active.id)) {
                            Text(when (item) {
                                SceneObject.CHAIR -> "Sit in chair"
                                SceneObject.COUCH -> "Sit on couch"
                                SceneObject.BED -> "Rest on bed"
                                SceneObject.TABLE -> "Read a book"
                            })
                        }
                    }
                    for (other in cast.filter { it.id != active.id }) {
                        OutlinedButton({
                            simulation.chat(active.id, other.id)
                            command = null
                            people = simulation.people()
                        }, enabled = ready) { Text("Chat with ${other.name}") }
                    }
                    TextButton({
                        simulation.freeWill(active.id)
                        command = null
                        people = simulation.people()
                    }) { Text("Free will") }
                }
                command?.let { Text(it, color = Color.LightGray) }
            }
        } else {
            Text("Select an NPC to direct them.", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color.LightGray)
        }
    }
    if (ending) AlertDialog(
        onDismissRequest = { ending = false },
        title = { Text("End this scene?") },
        confirmButton = { TextButton(onEnd) { Text("End scene") } },
        dismissButton = { TextButton({ ending = false }) { Text("Keep playing") } },
    )
}

@Composable
fun SceneAnimationScreen(
    clips: List<Anim>,
    bindings: Map<Activity, String>,
    onChange: (Activity, String?) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<Activity?>(null) }
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(Color(0xFF191719)).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sims 4 animations", color = Color(0xFFF4DFBD), style = MaterialTheme.typography.headlineMedium)
        Text("Import an adult Sims 4 skeleton and animation clips, then assign clips to NPC actions.", color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onImport) { Text("Import rig and clips") }
            OutlinedButton(onBack) { Text("Back") }
        }
        if (clips.isNotEmpty()) for (activity in Activity.entries) {
            val current = clips.firstOrNull { it.id == bindings[activity] }?.name ?: "Warcraft default"
            OutlinedButton({ editing = activity }) { Text("${activity.label} · $current") }
        }
    }
    editing?.let { activity ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(activity.label) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    item {
                        TextButton({ onChange(activity, null); editing = null }) { Text("Warcraft default") }
                    }
                    items(clips.size) { index ->
                        val clip = clips[index]
                        TextButton({ onChange(activity, clip.id); editing = null }) { Text(clip.name) }
                    }
                }
            },
            confirmButton = { TextButton({ editing = null }) { Text("Cancel") } },
        )
    }
}
