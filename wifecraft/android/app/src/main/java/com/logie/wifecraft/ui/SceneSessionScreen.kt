package com.logie.wifecraft.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        WarcraftLoadingArt(Modifier.fillMaxSize())
        val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
        WowWindow(step.title, onBack, Modifier.width(layout.optionsW.dp).height(layout.optionsH.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (step) {
                    SetupStep.LOCATION -> for (preset in locations) {
                        WowListRow(preset.name, null, selected = preset.id == location, onClick = { onLocation(preset.id) })
                    }
                    SetupStep.CAST -> {
                        Text("Choose up to six NPCs.", style = frizStyle(13.sp))
                        RedButton("Create NPC", onCreate)
                        for (actor in roster) {
                            WowCheckRow(actor.name, actor.id in cast, { onToggleActor(actor.id) },
                                enabled = actor.id in cast || cast.size < 6)
                        }
                    }
                    SetupStep.OBJECTS -> {
                        for (item in SceneObject.entries) {
                            WowCheckRow(item.label, item in objects, { onToggleObject(item) })
                        }
                        RedButton("Sims 4 animations", onAnimations)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RedButton("Back", onBack, Modifier.weight(1f))
                RedButton(if (step == SetupStep.OBJECTS) "Begin scene" else "Next", onNext, Modifier.weight(1f),
                    enabled = when (step) {
                        SetupStep.LOCATION -> location != null
                        SetupStep.CAST -> cast.isNotEmpty()
                        SetupStep.OBJECTS -> true
                    })
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
            Text(locationName, Modifier.weight(1f), color = Color(0xFFF4DFBD), style = frizStyle(17.sp))
            RedButton("End scene", { ending = true })
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
                WowTab("${names[person.id]} · ${person.activity.description}", person.id == selected,
                    { selected = if (person.id == selected) null else person.id })
            }
        }
        if (active != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in objects) {
                        val action = when (item) {
                            SceneObject.CHAIR -> "Sit in chair"
                            SceneObject.COUCH -> "Sit on couch"
                            SceneObject.BED -> "Rest on bed"
                            SceneObject.TABLE -> "Read a book"
                        }
                        RedButton(action, { use(active.id, item) }, enabled = ready && simulation.available(item, active.id))
                    }
                    for (other in cast.filter { it.id != active.id }) {
                        RedButton("Chat with ${other.name}", {
                            simulation.chat(active.id, other.id)
                            command = null
                            people = simulation.people()
                        }, enabled = ready)
                    }
                    RedButton("Free will", {
                        simulation.freeWill(active.id)
                        command = null
                        people = simulation.people()
                    })
                }
                command?.let { Text(it, color = Color.LightGray) }
            }
        } else {
            Text("Select an NPC to direct them.", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color.LightGray)
        }
    }
    if (ending) ConfirmDialog("End this scene?", "End scene", { ending = false }, onEnd)
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
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        WarcraftLoadingArt(Modifier.fillMaxSize())
        val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
        WowWindow("Sims 4 animations", onBack, Modifier.width(layout.optionsW.dp).height(layout.optionsH.dp)) {
            RedButton("Import rig and clips", onImport)
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                for (activity in Activity.entries) {
                    val current = clips.firstOrNull { it.id == bindings[activity] }?.name ?: "Warcraft default"
                    WowListRow(activity.label, current, selected = false, onClick = { editing = activity })
                }
            }
        }
    }
    editing?.let { activity ->
        PickDialog(activity.label, listOf("" to "Warcraft default") + clips.map { it.id to it.name },
            bindings[activity], onCancel = { editing = null }) { id ->
            onChange(activity, id.takeIf { it.isNotEmpty() })
            editing = null
        }
    }
}
