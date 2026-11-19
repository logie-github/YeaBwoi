package com.logie.wifecraft.ui

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.engine.FrameDriver
import com.logie.wifecraft.engine.FurniturePose
import com.logie.wifecraft.engine.Hit
import com.logie.wifecraft.engine.LocationLoader
import com.logie.wifecraft.engine.Thumbs
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.simulation.Activity
import com.logie.wifecraft.simulation.InteractionSpot
import com.logie.wifecraft.simulation.LiveScene
import com.logie.wifecraft.simulation.SceneSnapshot
import com.logie.wifecraft.simulation.TouchGesture
import com.logie.wifecraft.simulation.TwoFingers
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

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
    previewView: SceneView?,
    locationLoader: LocationLoader,
    portraits: Thumbs?,
    portraitActor: (Actor) -> Actor?,
    objects: Set<SceneObject>,
    onToggleObject: (SceneObject) -> Unit,
    onCreate: () -> Unit,
    onAnimations: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        WifecraftMenuBackdrop(Modifier.fillMaxSize())
        val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
        WowWindow(step.title, onBack, Modifier.width(layout.optionsW.dp).height(layout.optionsH.dp), body = WindowBody.TRANSLUCENT) {
            val body = Modifier.weight(1f).fillMaxWidth()
            when (step) {
                SetupStep.LOCATION -> SceneBrowser(locations, location, onLocation, checkNotNull(previewView), locationLoader, body)
                SetupStep.CAST -> NpcBrowser(roster, cast, onToggleActor, onCreate, checkNotNull(previewView), portraits, portraitActor, body)
                SetupStep.OBJECTS -> Column(body.verticalScroll(key(step) { rememberScrollState() }), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (item in SceneObject.entries) {
                        WowCheckRow(item.label, item in objects, { onToggleObject(item) })
                    }
                    RedButton("Sims 4 animations", onAnimations)
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

/**
 * The live scene. Nothing here changes every frame: the simulation, the people's motion and the camera run on the GL
 * thread ([LiveScene] driving the [SceneView] through its [FrameDriver]); this screen gets a [SceneSnapshot] only when what
 * its menus show changed (at most ten a second), and sends the player's orders and touches back. Its bars keep one size
 * (the 3D view is never resized by a selection or a longer label), and an overlay covers the view until the scene is
 * loaded and its first frames are drawn, then fades.
 */
@Composable
fun LiveSceneScreen(
    view: SceneView,
    camera: CameraPose,
    locationName: String,
    cast: List<Actor>,
    objects: Set<SceneObject>,
    spots: Map<SceneObject, InteractionSpot>,
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
    /** How far the scene has loaded (0..1), when the caller knows: the loading bar fills; null: it sweeps. */
    progress: Float? = null,
) {
    val live = remember(cast, objects, durations) {
        val origin = toStage(Vec3(0f, 0f, 0f))
        LiveScene(cast.map { it.id }, objects, durations, camera, origin.x, origin.y, origin.z, stageRotation)
    }
    SideEffect { live.camera.room = camera }
    var snapshot by remember(live) { mutableStateOf(live.snapshot()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var npcCamera by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    val names = remember(cast) { cast.associate { it.id to it.name } }
    val active = selected?.let { id -> snapshot.people.firstOrNull { it.id == id } }

    BackHandler {
        if (npcCamera) npcCamera = false
        else if (selected != null) selected = null
        else ending = true
    }

    // the GL thread drives every frame; the menus hear of changes only
    val animate by rememberUpdatedState(onAnimate)
    DisposableEffect(live, view) {
        val main = Handler(Looper.getMainLooper())
        val ids = Array(live.count) { live.id(it) }
        val keys = Array(live.count) { actorKey(ids[it]) }
        val sink = object : LiveScene.Sink {
            override fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float) =
                view.glCamera(eyeX, eyeY, eyeZ, atX, atY, atZ, fov)

            override fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float) {
                val pose = when (furniture?.action) {
                    Activity.SIT -> FurniturePose.SIT
                    Activity.REST -> FurniturePose.REST
                    else -> FurniturePose.FLOOR
                }
                view.glPlace(keys[index], x, y, z, facing, pose, sx, sy, sz)
            }

            override fun activity(index: Int, activity: Activity) {
                view.glAnimate(keys[index], activity.animation)   // this very frame, with the placement
                val id = ids[index]
                main.post { animate(id, activity) }                // the imported clips and the files (main thread)
            }
        }
        val emit: (SceneSnapshot) -> Unit = { next -> main.post { snapshot = next } }
        view.driver = FrameDriver { now -> live.frame(now, sink, emit) }
        onDispose {
            view.driver = null
            main.removeCallbacksAndMessages(null)
        }
    }
    LaunchedEffect(live, spots) {
        for ((item, spot) in spots) live.command { it.setSpot(item, spot) }
    }
    SideEffect {
        live.running = ready && !ending
        live.selected = selected?.let { id -> cast.indexOfFirst { it.id == id } } ?: -1
    }
    LaunchedEffect(npcCamera, selected) {
        if (selected == null) npcCamera = false
        live.camera.setNpc(npcCamera && selected != null)
    }
    LaunchedEffect(selected) {
        view.select(selected?.let(actorKey))
        live.order { null }
    }

    fun use(id: String, item: SceneObject) = live.order { sim -> if (sim.use(id, item)) null else "${item.label} is in use." }

    val onTap by rememberUpdatedState<(Hit?) -> Unit>({ hit ->
        when (hit?.kind) {
            Hit.Kind.ACTOR -> cast.firstOrNull { actorKey(it.id) == hit.key }?.let { selected = it.id }
            Hit.Kind.PROP -> if (ready) selected?.let { id -> objects.firstOrNull { objectKey(it) == hit.key || (it == SceneObject.TABLE && hit.key == bookKey) }?.let { use(id, it) } }
            else -> if (!npcCamera) selected = null
        }
    })
    val orbiting by rememberUpdatedState(npcCamera)

    // when the scene is in and a few frames of it are drawn (the NPCs' skins are composed a frame or so each), the cover fades
    var revealed by remember(live) { mutableStateOf(false) }
    LaunchedEffect(live, ready) {
        if (!ready) { revealed = false; return@LaunchedEffect }
        val start = view.framesDrawn
        withTimeoutOrNull(1500) { while (view.framesDrawn < start + REVEAL_FRAMES) withFrameNanos { } }
        revealed = true
    }

    Column(Modifier.fillMaxSize().background(SceneGround)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(locationName, Modifier.weight(1f), color = Color(0xFFF4DFBD), style = frizStyle(17.sp), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            // always there (greyed with nobody selected): the bar never shifts when someone is chosen
            RedButton(if (npcCamera) "Room camera" else "NPC camera", { npcCamera = !npcCamera }, Modifier.width(156.dp),
                enabled = active != null)
            RedButton("End scene", { ending = true }, Modifier.width(132.dp))
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                { (view.parent as? ViewGroup)?.removeView(view); view },
                Modifier.fillMaxSize().pointerInput(view, live) {
                    val slop = 8.dp.toPx()
                    val velocity = FloatArray(2)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val gesture = TouchGesture(slop)
                        gesture.down(down.position.x, down.position.y, down.uptimeMillis)
                        var last = down.position
                        var lastTime = down.uptimeMillis
                        var twoFingers = false
                        var span = 0f
                        var angle = 0f
                        var twist = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            event.changes.firstOrNull()?.let { lastTime = it.uptimeMillis }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val nowSpan = TwoFingers.span(a.x, a.y, b.x, b.y)
                                val nowAngle = TwoFingers.angle(a.x, a.y, b.x, b.y)
                                if (!twoFingers || span <= 0f) {
                                    twoFingers = true
                                    gesture.multi()
                                } else if (orbiting) {
                                    live.camera.pinch(nowSpan / span)
                                    // a twist turns the orbit once it is plainly a twist (a pinch's wobble never does)
                                    twist += TwoFingers.turn(angle, nowAngle)
                                    if (abs(twist) > TWIST_DEADZONE) live.camera.twist(TwoFingers.turn(angle, nowAngle))
                                    pressed.forEach { it.consume() }
                                }
                                span = nowSpan
                                angle = nowAngle
                            } else {
                                span = 0f   // one finger of a pinch lifted: the next two-finger move starts afresh
                                if (!twoFingers) {
                                    val finger = pressed.firstOrNull { it.id == down.id } ?: pressed[0]
                                    last = finger.position
                                    if (gesture.move(last.x, last.y, finger.uptimeMillis) && orbiting) {
                                        live.camera.dragBy(gesture.dx, gesture.dy, size.height.toFloat())
                                        finger.consume()
                                    }
                                }
                            }
                        }
                        when {
                            gesture.up(lastTime) -> view.pick(last.x, last.y, size.width, size.height) { onTap(it) }
                            gesture.kind == TouchGesture.Kind.DRAG && orbiting -> {
                                gesture.velocity(lastTime, velocity)
                                live.camera.release(velocity[0], velocity[1], size.height.toFloat())
                            }
                            else -> live.camera.letGo()
                        }
                    }
                },
            )
            ZoomButtons(npcCamera, live, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
            SceneLoadingCover(!revealed, locationName, status, progress)
            // a problem after loading (a prop without a surface, a file that wouldn't come): a fixed-width note on top
            ProblemNote(status.takeIf { revealed }, Modifier.align(Alignment.TopCenter).padding(8.dp))
        }
        // the cast, the orders and the notice: each row a fixed height, whether someone is selected or not
        Row(
            Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (person in snapshot.people) key(person.id) {
                WowTab("${names[person.id]} · ${person.activity.description}", person.id == selected,
                    { selected = if (person.id == selected) null else person.id }, Modifier.width(CAST_TAB_WIDTH))
            }
        }
        Crossfade(active?.id, Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), label = "orders") { id ->
            if (id == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    Text("Select an NPC to direct them.", color = Color.LightGray)
                }
            } else {
                Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    for (item in objects) key(item) {
                        val action = when (item) {
                            SceneObject.CHAIR -> "Sit in chair"
                            SceneObject.COUCH -> "Sit on couch"
                            SceneObject.BED -> "Rest on bed"
                            SceneObject.TABLE -> "Read a book"
                        }
                        RedButton(action, { use(id, item) }, enabled = ready && snapshot.available(item, id))
                    }
                    for (other in cast) if (other.id != id) key(other.id) {
                        RedButton("Chat with ${other.name}", { live.order { sim -> sim.chat(id, other.id); null } }, enabled = ready)
                    }
                    RedButton("Free will", { live.order { sim -> sim.freeWill(id); null } })
                }
            }
        }
        Text(snapshot.notice.orEmpty(), Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 12.dp), color = Color.LightGray,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    ConfirmDialog("End this scene?", "End scene", { ending = false }, onEnd, visible = ending)
}

/** The NPC camera's zoom buttons, faded in and out with it (the springs ease each step). */
@Composable
private fun ZoomButtons(shown: Boolean, live: LiveScene, modifier: Modifier) {
    AnimatedVisibility(shown, modifier, enter = fadeIn(), exit = fadeOut()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RoundIconButton(Face(glyph = WarIcons.PLUS), { live.camera.zoomBy(1.25f) }, size = 48.dp, description = "Zoom in")
            RoundIconButton(Face(glyph = WarIcons.MINUS), { live.camera.zoomBy(0.8f) }, size = 48.dp, description = "Zoom out")
        }
    }
}

/** A problem said over the view after loading, in a box of fixed greatest width, faded in and out. */
@Composable
private fun ProblemNote(problem: String?, modifier: Modifier) {
    AnimatedVisibility(problem != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(rememberHeld(problem) ?: "", Modifier.widthIn(max = 520.dp).background(Color(0xDD191719)).padding(8.dp),
            color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The live screen's ground (behind the view while it starts, and under its bars). */
private val SceneGround = Color(0xFF191719)
/** Every cast tab is this wide: "Walking" and "Idle" never shift the row. */
private val CAST_TAB_WIDTH = 188.dp
/** Frames drawn after loading before the cover fades (the NPCs' skins are composed about one a frame). */
private const val REVEAL_FRAMES = 8L
/** Radians two fingers must turn before a pinch turns the orbit too. */
private const val TWIST_DEADZONE = 0.15f

/**
 * Over the 3D view while the scene loads: the scene's name, what is loading (a fixed-width line: changing numbers never
 * move it) and a bar ([progress] fills it; null: it sweeps); it fades out when [visible] goes false and is gone after.
 */
@Composable
private fun BoxScope.SceneLoadingCover(visible: Boolean, name: String, status: String?, progress: Float?) {
    val shown by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 0 else 450), label = "cover")
    if (shown <= 0f) return
    Box(Modifier.matchParentSize().graphicsLayer { alpha = shown }.background(SceneGround)
        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } },
        contentAlignment = Alignment.Center) {
        WarcraftLoadingArt(Modifier.matchParentSize())
        Column(Modifier.width(360.dp).background(Color(0xCC120E0C)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, style = frizStyle(18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                Text(rememberHeld(status) ?: "Opening…", Modifier.fillMaxWidth(), color = Color.White,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clipToBounds().background(Color(0x33FFFFFF))) {
                if (progress != null) {
                    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(250), label = "bar")
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(WowColors.gold))
                } else {
                    val sweep = rememberInfiniteTransition(label = "sweep")
                    val x by sweep.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "x")
                    Box(Modifier.fillMaxHeight().fillMaxWidth(0.3f).graphicsLayer { translationX = (x * 1.3f - 0.3f) * size.width / 0.3f }
                        .background(WowColors.gold))
                }
            }
        }
    }
}

@Composable
fun SceneAnimationScreen(
    clips: List<Anim>,
    bindings: Map<Activity, String>,
    onChange: (Activity, String?) -> Unit,
    onImport: () -> Unit,
    onImportWickedWhims: () -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<Activity?>(null) }
    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        WarcraftLoadingArt(Modifier.fillMaxSize())
        val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
        WowWindow("Sims 4 animations", onBack, Modifier.width(layout.optionsW.dp).height(layout.optionsH.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RedButton("Import WickedWhims", onImportWickedWhims, Modifier.weight(1f), textSize = 13.sp)
                RedButton("Import rig and clips", onImport, Modifier.weight(1f), textSize = 13.sp)
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                for (activity in Activity.entries) {
                    val current = clips.firstOrNull { it.id == bindings[activity] }?.name ?: "Warcraft default"
                    WowListRow(activity.label, current, selected = false, onClick = { editing = activity })
                }
            }
        }
    }
    rememberHeld(editing)?.let { activity ->
        PickDialog(activity.label, listOf("" to "Warcraft default") + clips.map { it.id to "${it.name} · ${it.category}" },
            bindings[activity], onCancel = { editing = null }, visible = editing != null, searchHint = "Find animations") { id ->
            onChange(activity, id.takeIf { it.isNotEmpty() })
            editing = null
        }
    }
}
