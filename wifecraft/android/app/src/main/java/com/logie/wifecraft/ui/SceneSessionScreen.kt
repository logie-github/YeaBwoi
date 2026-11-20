package com.logie.wifecraft.ui

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.logie.wifecraft.anim.CheckedAnimation
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.AnimationChoice
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.PERFORMANCE_SCALE
import com.logie.wifecraft.anim.PairAnim
import com.logie.wifecraft.anim.animationChoices
import com.logie.wifecraft.anim.animationSequence
import com.logie.wifecraft.catalog.EmotePlayability
import com.logie.wifecraft.engine.Clock
import com.logie.wifecraft.simulation.PartnerSound
import com.logie.wifecraft.simulation.PerformanceOrder
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.simulation.ClipPlayback
import com.logie.wifecraft.simulation.PlaybackKeys
import com.logie.wifecraft.simulation.PlaybackRole
import java.util.concurrent.ConcurrentHashMap
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.engine.FrameDriver
import com.logie.wifecraft.engine.FurniturePose
import com.logie.wifecraft.engine.Hit
import com.logie.wifecraft.engine.LocationLoader
import com.logie.wifecraft.engine.Thumbs
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.simulation.Activity
import com.logie.wifecraft.simulation.CameraMode
import com.logie.wifecraft.simulation.InteractionSpot
import com.logie.wifecraft.simulation.LiveScene
import com.logie.wifecraft.simulation.ModelEmotes
import com.logie.wifecraft.simulation.PartnerEmotes
import com.logie.wifecraft.simulation.PartnerFrequency
import com.logie.wifecraft.simulation.PartnerPair
import com.logie.wifecraft.simulation.PartnerSoundSink
import com.logie.wifecraft.simulation.SceneSnapshot
import com.logie.wifecraft.simulation.TouchGesture
import com.logie.wifecraft.simulation.TwoFingers
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.SceneCamera
import com.logie.wifecraft.simulation.Chatter
import com.logie.wifecraft.simulation.ChatterFrequency
import com.logie.wifecraft.simulation.Races
import com.logie.wifecraft.simulation.SceneControl
import com.logie.wifecraft.simulation.Speaker
import com.logie.wifecraft.simulation.SpeechLayout
import com.logie.wifecraft.creator.Sex
import androidx.compose.ui.layout.onSizeChanged
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

enum class SetupStep(val title: String) { LOCATION("Choose Scene"), CAST("Choose NPCs"), OBJECTS("Choose objects") }

/** The scene's setup in the front menus' look: one [WifeWindow] a step (the scene, the cast, the objects and how lively). */
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
    /** How often idle NPCs start a partner animation with each other (kept with the scene setup). */
    partner: PartnerFrequency = PartnerFrequency.SOMETIMES,
    onPartner: (PartnerFrequency) -> Unit = {},
    /** How many enabled partner animations the library has (0: the Warcraft emotes are used). */
    partnerPairs: Int = 0,
    /** How often idle NPCs go and talk to each other, speech bubbles and all (kept with the scene setup). */
    chatter: ChatterFrequency = ChatterFrequency.SOMETIMES,
    onChatter: (ChatterFrequency) -> Unit = {},
) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        WifecraftMenuBackdrop(Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
            WifeWindow(
                step.title, onBack, Modifier.width(layout.optionsW.dp).height(layout.optionsH.dp),
                subtitle = "Step ${step.ordinal + 1} of ${SetupStep.entries.size}",
                footer = {
                    WifeButton("Back", onBack, Modifier.widthIn(min = 120.dp), sound = "close")
                    WifeButton(
                        if (step == SetupStep.OBJECTS) "Begin scene" else "Next", onNext, Modifier.widthIn(min = 140.dp),
                        enabled = when (step) {
                            SetupStep.LOCATION -> location != null
                            SetupStep.CAST -> cast.isNotEmpty()
                            SetupStep.OBJECTS -> true
                        },
                        kind = WifeButtonKind.PRIMARY, sound = if (step == SetupStep.OBJECTS) "ok" else "click",
                    )
                },
            ) {
                val body = Modifier.weight(1f).fillMaxWidth()
                when (step) {
                    SetupStep.LOCATION -> SceneBrowser(locations, location, onLocation, checkNotNull(previewView), locationLoader, body)
                    SetupStep.CAST -> NpcBrowser(roster, cast, onToggleActor, onCreate, checkNotNull(previewView), portraits, portraitActor, body)
                    SetupStep.OBJECTS -> Column(body.verticalScroll(key(step) { rememberScrollState() }), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                        val available = locations.firstOrNull { it.id == location }?.interactions.orEmpty()
                        val items = SceneObject.entries.filter { item -> available.any { it.item == item.name } }
                        WifeSectionHeader("Objects")
                        if (items.isEmpty()) WifeNote("This scene has nothing to sit, rest or read at.")
                        for (item in items) key(item) {
                            WifeSwitchRow(item.label, item in objects, { onToggleObject(item) })
                        }
                        WifeSectionHeader("Partner")
                        WifeTabs(PartnerFrequency.entries.map { it.label }, partner.ordinal, { onPartner(PartnerFrequency.entries[it]) })
                        WifeNote(if (partnerPairs > 0) "$partnerPairs partner animation${if (partnerPairs == 1) "" else "s"} imported"
                            else "None imported: NPCs share Warcraft emotes instead")
                        WifeSectionHeader("Chatter")
                        WifeTabs(ChatterFrequency.entries.map { it.label }, chatter.ordinal, { onChatter(ChatterFrequency.entries[it]) })
                        WifeNote("How often NPCs walk up to each other and talk, in speech bubbles")
                        WifeSectionHeader("Animations")
                        WifeButton("Animation library", onAnimations, Modifier.fillMaxWidth(), sound = "open")
                    }
                }
            }
        }
    }
}

private data class PendingAnimation(val owner: String, val choice: AnimationChoice, val mode: AnimationOrderMode)

/**
 * The live scene. Nothing here changes every frame: the simulation, the people's motion and the camera run on the GL
 * thread ([LiveScene] driving the [SceneView] through its [FrameDriver]); this screen gets a [SceneSnapshot] only when what
 * its menus show changed (at most ten a second), and sends the player's orders and touches back. The view fills the
 * screen and never resizes; the HUD floats over it in the front menus' look ([SceneHud] places it): a top bar (the place,
 * the clock, the scene menu), the cast as party frames, a line of help with the partner chip, and an action bar of six
 * slots a page. An overlay covers the view until the scene is loaded and its first frames are drawn, then fades.
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
    objectHits: Map<String, SceneObject>,
    toStage: (Vec3) -> Vec3,
    toLocal: (Vec3) -> Vec3,
    stageRotation: Float,
    onAnimate: (String, Activity) -> Unit,
    clips: List<CheckedAnimation>,
    onEnd: () -> Unit,
    ready: Boolean,
    status: String?,
    /** How far the scene has loaded (0..1), when the caller knows: the loading bar fills; null: it sweeps. */
    progress: Float? = null,
    /** The library's partner animations (AnimLibrary.pairs; the enabled ones are offered), how often NPCs start one
     *  by themselves, and the models' emotes (the fallback). */
    pairs: List<PairAnim> = emptyList(),
    partner: PartnerFrequency = PartnerFrequency.SOMETIMES,
    emotes: ModelEmotes = ModelEmotes.STANDARD,
    /** Plays the pairs' sound events (W46's PartnerSounds); none by default. */
    partnerSounds: PartnerSoundSink = PartnerSoundSink.NONE,
    /** Fetches a game animation's files for an actor key (Dresser.fetchAnim): the fallback's emotes. */
    fetchAnim: (String, Int) -> Unit = { _, _ -> },
    /** How often idle NPCs go and talk to each other (their lines in speech bubbles). */
    chatter: ChatterFrequency = ChatterFrequency.SOMETIMES,
    /** Opens Options over the scene (the scene menu's Options; null: the menu has none). */
    onSettings: (() -> Unit)? = null,
) {
    // the scene's seed: one per scene, so a scene replays the same way from it (the tests use it so)
    val seed = remember(cast, objects) { Random.nextLong() }
    val choices = remember(clips, pairs) { animationChoices(clips) + pairs.filter { it.enabled }.map { it.choice() } }
    // what NPCs pick from by themselves: the library's enabled pairs and the authors' two-actor performances
    val partnerPairs = remember(pairs, choices) { PartnerPair.enabled(pairs) + choices.mapNotNull { PartnerPair.of(it) } }
    // what the NPCs say to each other: their names, races and sexes, and the place
    val words = remember(cast, locationName) {
        Chatter(cast.associate { it.id to Speaker(it.name, it.appearance.raceId, it.appearance.sex == Sex.FEMALE) }, locationName)
    }
    val live = remember(cast, objects) {
        val origin = toStage(Vec3(0f, 0f, 0f))
        LiveScene(cast.map { it.id }, objects, emptyMap(), camera, origin.x, origin.y, origin.z, stageRotation, Random(seed),
            wanderOnly = true, route = { from, to -> view.glWalkRoute(toStage(from), toStage(to))?.map(toLocal) },
            pairs = partnerPairs, emotes = emotes, partnerChance = partner.chance,
            chatter = words, chatterChance = chatter.chance)
    }
    // the speech bubbles: the GL thread lays them out each frame, the overlay draws them; each person's line now
    val speechSpots = remember(live) { SpeechSpots(live.count) }
    val lines = remember(live) { mutableStateListOf<String?>().apply { repeat(live.count) { add(null) } } }
    // each one's head: a human's of their sex at human scale, a dwarf's or gnome's own (anim/HumanScale)
    val heads = remember(cast) {
        FloatArray(cast.size) { HumanScale.head(cast[it].appearance.raceId, cast[it].appearance.sex == Sex.FEMALE) }
    }
    // each one's size for the pairs' positioning: a gnome's own, everyone else 1 (HumanScale.pairScale, simulation/PairFit)
    val pairSizes = remember(cast) {
        cast.associate { it.id to HumanScale.pairScale(it.appearance.raceId, it.appearance.sex == Sex.FEMALE) }
    }
    // the clips every playback key names (GL thread reads it): the orders' (request) and the partner pairs' roles
    val playback = remember(live) { ConcurrentHashMap<String, Anim>() }
    LaunchedEffect(live, partnerPairs, partner, emotes, chatter) {
        for (p in partnerPairs) {
            playback[PlaybackKeys.pair(p.id, 0)] = lifted(p.a.copy(loop = false), p.slotA.z)
            playback[PlaybackKeys.pair(p.id, 1)] = lifted(p.b.copy(loop = false), p.slotB.z)
        }
        live.command { sim ->
            sim.pairs = partnerPairs; sim.partnerChance = partner.chance; sim.emotes = emotes; sim.chatterChance = chatter.chance
            sim.sizes = pairSizes
        }
    }
    SideEffect {
        live.camera.room = camera
        live.camera.floorZ = toStage(Vec3(0f, 0f, 0f)).z   // the stage's floor: the camera's lift stays in the room
    }
    var snapshot by remember(live) { mutableStateOf(live.snapshot()) }
    // who the player controls and that NPC's partner (SceneControl: a tap on another NPC makes it the partner)
    var control by remember { mutableStateOf(SceneControl()) }
    val selected = control.selected
    val partnerId = control.partner
    // Walk on: a tap on the floor sends the controlled NPC there. The camera: the room's view, the orbit, or Follow
    var walking by remember { mutableStateOf(false) }
    var cameraMode by remember(live) { mutableStateOf(CameraMode.ROOM) }
    var ending by remember { mutableStateOf(false) }
    var choosingAnimation by remember { mutableStateOf<String?>(null) }
    var choosingPartner by remember { mutableStateOf<PendingAnimation?>(null) }
    // the Say list open for this NPC, the Chat with list, the scene menu (the top bar's gear)
    var saying by remember { mutableStateOf<String?>(null) }
    var chatting by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val names = remember(cast) { cast.associate { it.id to it.name } }
    val active = selected?.let { id -> snapshot.people.firstOrNull { it.id == id } }

    BackHandler {
        if (saying != null) saying = null
        else if (chatting != null) chatting = null
        else if (choosingPartner != null) choosingPartner = null
        else if (choosingAnimation != null) choosingAnimation = null
        else if (menu) menu = false
        else if (walking) walking = false
        else if (cameraMode != CameraMode.ROOM) cameraMode = CameraMode.ROOM
        else if (partnerId != null) control = control.clearPartner()
        else if (selected != null) control = control.release()
        else ending = true
    }

    fun request(other: String?, choice: AnimationChoice, repeat: Boolean): PerformanceOrder {
        val animations = choice.clips.map { checkNotNull(it.forPlayback()) }
        val roles = choice.roles.mapIndexed { index, role ->
            val animation = animations[index].copy(loop = false)
            val key = "${choice.id}:$index"
            val scale = if (choice.yards) 1f else PERFORMANCE_SCALE
            playback[key] = lifted(animation, role.offset.z * scale)
            PlaybackRole(key, Vec3(role.offset.x * scale * HumanScale.PAIR, role.offset.y * scale * HumanScale.PAIR, role.offset.z * scale), role.facing)
        }
        val cycle = animations.maxOf { it.duration }.takeIf { it > 0f } ?: 12f
        return PerformanceOrder(other, roles, cycle, repeat, choice.repetitions, choice.name,
            choice.sounds.map { PartnerSound(it.timeSec, it.name, it.actor) })
    }

    fun schedule(order: PendingAnimation, partner: String?) {
        val (owner, choice, mode) = order
        if (mode == AnimationOrderMode.QUEUE || mode == AnimationOrderMode.SEQUENCE) {
            val stages = if (mode == AnimationOrderMode.SEQUENCE) animationSequence(choice, choices) else listOf(choice)
            val requests = stages.map { request(partner, it, false) }
            live.order { sim ->
                if (sim.enqueue(owner, requests)) "${requests.size} animation${if (requests.size == 1) "" else "s"} queued."
                else "Couldn’t add these animations to the queue."
            }
        } else {
            val request = request(partner, choice, mode == AnimationOrderMode.REPEAT)
            live.order { sim ->
                if (sim.perform(owner, request)) null
                else "The NPCs couldn’t reach their starting positions."
            }
        }
    }

    // the GL thread drives every frame; the menus hear of changes only
    val animate by rememberUpdatedState(onAnimate)
    val sounds by rememberUpdatedState(partnerSounds)
    val fetch by rememberUpdatedState(fetchAnim)
    DisposableEffect(live, view) {
        val main = Handler(Looper.getMainLooper())
        val ids = Array(live.count) { live.id(it) }
        val keys = Array(live.count) { actorKey(ids[it]) }
        // GL thread: the frame's time on the engine's clock; both of a pair start their clips from this one value
        var frameMs = 0
        val emoting = BooleanArray(live.count)
        // main thread: the talking gestures' files asked for once each
        val gestureFiles = HashSet<Long>()
        val sink = object : LiveScene.Sink {
            override fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float) {
                view.glCamera(eyeX, eyeY, eyeZ, atX, atY, atZ, fov)
                speechSpots.layout(eyeX, eyeY, eyeZ, atX, atY, atZ, fov)   // the bubbles follow the heads with this camera
            }

            override fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float) {
                val pose = when (furniture?.action) {
                    Activity.SIT -> FurniturePose.SIT
                    Activity.REST -> FurniturePose.REST
                    else -> FurniturePose.FLOOR
                }
                view.glPlace(keys[index], x, y, z, facing, pose, sx, sy, sz)
                val standing = heads.getOrElse(index) { 2.05f }
                speechSpots.headAt(index, sx, sy, sz + SpeechLayout.head(standing, pose == FurniturePose.SIT, pose == FurniturePose.REST))
            }

            override fun speech(index: Int, text: String?, id: Long) {
                if (index in speechSpots.speaking.indices) speechSpots.speaking[index] = text != null
                main.post { if (index in lines.indices) lines[index] = text }
            }

            override fun gesture(index: Int, animation: Int) {
                val key = keys[index]
                view.glAnimate(key, animation)   // this very frame
                main.post { if (gestureFiles.add(index.toLong() shl 32 or animation.toLong())) fetch(key, animation) }
            }

            override fun playback(index: Int, clip: ClipPlayback?) {
                val key = keys[index]
                val emote = clip?.let { PlaybackKeys.emoteOf(it.clip) }
                val wasEmote = emoting[index]
                emoting[index] = emote != null
                if (emote != null) {
                    // a Warcraft emote both play (no imported pair fits): its files, then the game animation from this frame,
                    // held only when it is a held one (Dance); a wave or a bow plays once
                    val start = frameMs
                    val held = emoteHeld(emote)
                    view.glPlayAnimation(key, null)
                    main.post { fetch(key, emote); view.anim(key, emote, held, start) }
                } else {
                    view.glPlayAnimation(key, clip?.let { playback[it.clip] })
                    if (wasEmote) { val start = frameMs; main.post { view.anim(key, 0, true, start) } }   // back to standing
                }
            }

            override fun activity(index: Int, activity: Activity) {
                view.glAnimate(keys[index], activity.animation)   // this very frame, with the placement
                val id = ids[index]
                main.post { animate(id, activity) }                // the imported clips and the files (main thread)
            }

            override fun sound(index: Int, name: String) {
                val key = keys[index]
                main.post { sounds.play(name, key) }
            }
        }
        val emit: (SceneSnapshot) -> Unit = { next -> main.post { snapshot = next } }
        view.driver = FrameDriver { now -> frameMs = Clock.now(); live.frame(now, sink, emit) }
        onDispose {
            view.driver = null
            main.removeCallbacksAndMessages(null)
            sounds.stopAll()
        }
    }
    LaunchedEffect(live, spots) {
        for ((item, spot) in spots) live.command { it.setSpot(item, spot) }
    }
    SideEffect {
        live.running = ready && !ending
        live.selected = selected?.let { id -> cast.indexOfFirst { it.id == id } } ?: -1
    }
    LaunchedEffect(selected) {
        if (selected == null) walking = false
        cameraMode = SceneHud.cameraAfterRelease(cameraMode, selected != null)
    }
    LaunchedEffect(live, cameraMode) { live.camera.setMode(cameraMode) }
    LaunchedEffect(selected) {
        view.select(selected?.let(actorKey))
        live.order { null }
    }

    fun use(id: String, item: SceneObject) = live.order { sim ->
        when {
            !sim.available(item, id) -> "${item.label} is unavailable."
            sim.use(id, item) -> null
            else -> "There’s no clear path to ${item.label.lowercase()}."
        }
    }
    fun sendToPartner(id: String, otherId: String) = live.order { sim ->
        if (sim.partner(id, otherId)) null else "${names[otherId]} can't join ${names[id]} now."
    }
    fun chatWith(id: String, otherId: String) = live.order { sim ->
        if (sim.chat(id, otherId)) null else "There’s no clear path to ${names[otherId]}."
    }
    // the partner leaves the scene's cast or the cast changes: drop who is gone
    LaunchedEffect(cast) { control = control.keep(cast.map { it.id }) }

    val onTap by rememberUpdatedState<(Hit?) -> Unit>({ hit ->
        when (hit?.kind) {
            // nobody controlled: take control; one controlled: the tapped NPC becomes (or, tapped again, stops being) its partner
            Hit.Kind.ACTOR -> cast.firstOrNull { actorKey(it.id) == hit.key }?.let { other -> control = control.tapNpc(other.id) }
            Hit.Kind.LOCATION, Hit.Kind.PROP -> if (ready) {
                val item = hit.key?.let(objectHits::get)
                val id = selected
                if (item != null && id != null) use(id, item)
            }
            Hit.Kind.GROUND -> {
                val id = selected
                if (ready && walking && id != null) {
                    val destination = toLocal(Vec3(hit.x, hit.y, hit.z))
                    live.order { sim -> if (sim.walk(id, destination)) null else "There’s no clear path to that spot." }
                } else if (!walking) control = control.release()
            }
            null -> if (!walking) control = control.release()
        }
    })

    // when the scene is in and a few frames of it are drawn (the NPCs' skins are composed a frame or so each), the cover fades
    var revealed by remember(live) { mutableStateOf(false) }
    LaunchedEffect(live, ready) {
        if (!ready) { revealed = false; return@LaunchedEffect }
        val start = view.framesDrawn
        withTimeoutOrNull(1500) { while (view.framesDrawn < start + REVEAL_FRAMES) withFrameNanos { } }
        revealed = true
    }

    // the action bar: the controlled NPC's orders, six a page
    val uses = remember(objects) { SceneObject.entries.filter { it in objects }.map { it.name to useLabel(it) } }
    val mate = partnerId?.takeIf { it != active?.id }
    val slots = if (active == null) emptyList()
        else SceneHud.orders(walking, mate != null, cast.size - 1, active.repeating, active.queued, uses)
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(selected) { page = 0 }
    fun act(id: String, slot: HudSlot) {
        when (slot.order) {
            HudOrder.WALK -> {
                walking = !walking
                // as before: walking from the room's view follows the NPC (the camera button changes it any time)
                if (walking && cameraMode == CameraMode.ROOM) cameraMode = CameraMode.FOLLOW
            }
            HudOrder.PLAY -> choosingAnimation = id
            HudOrder.PARTNER -> partnerId?.let { sendToPartner(id, it) }
            HudOrder.SAY -> saying = id
            HudOrder.CHAT -> {
                val others = cast.filter { it.id != id }
                if (others.size == 1) chatWith(id, others[0].id) else chatting = id
            }
            HudOrder.STOP_REPEAT -> live.order { sim -> sim.stopRepeat(id); null }
            HudOrder.CLEAR_QUEUE -> live.order { sim -> sim.clearQueue(id); null }
            HudOrder.RELEASE -> control = control.release()
            HudOrder.USE -> SceneObject.entries.firstOrNull { it.name == slot.target }?.let { use(id, it) }
            HudOrder.FREE_WILL -> live.order { sim -> sim.freeWill(id); null }
        }
    }
    fun slotEnabled(id: String, slot: HudSlot): Boolean = when (slot.order) {
        HudOrder.PLAY, HudOrder.PARTNER, HudOrder.SAY, HudOrder.CHAT -> ready
        HudOrder.USE -> ready && SceneObject.entries.firstOrNull { it.name == slot.target }?.let { snapshot.available(it, id) } == true
        else -> true
    }

    val reduced = reduceMotion()
    Box(Modifier.fillMaxSize().background(SceneGround)) {
        AndroidView(
            { (view.parent as? ViewGroup)?.removeView(view); view },
            Modifier.fillMaxSize().onSizeChanged { speechSpots.width = it.width.toFloat(); speechSpots.height = it.height.toFloat() }.pointerInput(view, live) {
                val slop = 8.dp.toPx()
                val velocity = FloatArray(2)
                // a drag or pinch on the room's view starts the orbit there (it begins exactly on the room's view)
                fun beginOrbit() {
                    val next = SceneHud.cameraForTouch(cameraMode)
                    if (next != cameraMode) { cameraMode = next; live.camera.setMode(next) }
                }
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
                    // the two fingers' midpoint, and how far it has gone (a pan starts past the slop: a pinch's drift never pans)
                    var midX = 0f
                    var midY = 0f
                    var travel = 0f
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
                            val nowMidX = (a.x + b.x) / 2f
                            val nowMidY = (a.y + b.y) / 2f
                            if (!twoFingers || span <= 0f) {
                                twoFingers = true
                                gesture.multi()
                            } else {
                                beginOrbit()
                                // pinch zooms; a twist turns the orbit once it is plainly a twist (a pinch's wobble never does);
                                // the two moving together pan along the ground (sideways) and raise or lower the view (up and down)
                                live.camera.pinch(nowSpan / span)
                                twist += TwoFingers.turn(angle, nowAngle)
                                if (abs(twist) > TWIST_DEADZONE) live.camera.twist(TwoFingers.turn(angle, nowAngle))
                                val moveX = nowMidX - midX
                                val moveY = nowMidY - midY
                                travel += hypot(moveX, moveY)
                                if (travel > slop * PAN_SLOPS) live.camera.pan(moveX, moveY, size.height.toFloat())
                                pressed.forEach { it.consume() }
                            }
                            span = nowSpan
                            angle = nowAngle
                            midX = nowMidX
                            midY = nowMidY
                        } else {
                            span = 0f   // one finger of a pinch lifted: the next two-finger move starts afresh
                            if (!twoFingers) {
                                val finger = pressed.firstOrNull { it.id == down.id } ?: pressed[0]
                                last = finger.position
                                if (gesture.move(last.x, last.y, finger.uptimeMillis)) {
                                    beginOrbit()
                                    live.camera.dragBy(gesture.dx, gesture.dy, size.height.toFloat())
                                    finger.consume()
                                }
                            }
                        }
                    }
                    when {
                        gesture.up(lastTime) -> view.pick(last.x, last.y, size.width, size.height) { onTap(it) }
                        gesture.kind == TouchGesture.Kind.DRAG -> {
                            gesture.velocity(lastTime, velocity)
                            live.camera.release(velocity[0], velocity[1], size.height.toFloat())
                        }
                        else -> live.camera.letGo()
                    }
                }
            },
        )
        SpeechBubbles(speechSpots, remember(cast) { cast.map { it.name } }, lines,
            partnerId?.let { id -> cast.indexOfFirst { it.id == id } } ?: -1)
        SceneLoadingCover(!revealed, locationName, status, progress)

        // the HUD, inside the screen's safe area (cut-outs, the hinge's bars, the gesture bar)
        val shown by animateFloatAsState(if (revealed) 1f else 0f, tween(MenuMotion.duration(MenuMotion.WINDOW_IN_MS, reduced)), label = "hud")
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val hud = SceneHud.layout(maxWidth.value, maxHeight.value)
            SceneTopBar(locationName, { menu = true }, Modifier.align(Alignment.TopCenter).fillMaxWidth())
            if (shown > 0f) {
                val people = snapshot.people
                val castModifier = Modifier.graphicsLayer { alpha = shown }
                if (hud.castSide) {
                    Column(
                        castModifier.align(Alignment.TopStart).padding(start = SceneHud.MARGIN.dp, top = (SceneHud.TOP_H + 4f).dp,
                            bottom = (hud.barH + 2 * SceneHud.SLOT_H + 3 * SceneHud.MARGIN).dp)
                            .width(hud.castW.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (person in people) key(person.id) {
                            CastFrame(names[person.id] ?: "", doing(person, names), person.id == selected, person.id == partnerId,
                                { control = control.tapCast(person.id) }, Modifier.fillMaxWidth().height(hud.castH.dp))
                        }
                    }
                } else {
                    Row(
                        castModifier.align(Alignment.TopStart).padding(top = SceneHud.TOP_H.dp).fillMaxWidth()
                            .horizontalScroll(rememberScrollState()).padding(horizontal = SceneHud.MARGIN.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (person in people) key(person.id) {
                            CastFrame(names[person.id] ?: "", doing(person, names), person.id == selected, person.id == partnerId,
                                { control = control.tapCast(person.id) }, Modifier.width(hud.castW.dp).height(hud.castH.dp))
                        }
                    }
                }
                CameraControls(cameraMode, selected != null, { cameraMode = it }, live,
                    Modifier.align(Alignment.TopEnd).graphicsLayer { alpha = shown }
                        .padding(end = SceneHud.MARGIN.dp, top = SceneHud.cameraTop(hud).dp))
                // the line of help (or the last refusal) with the partner chip, and the action bar: a fixed height, so
                // choosing someone or a longer line never moves anything
                Column(
                    Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = shown }.padding(bottom = SceneHud.MARGIN.dp)
                        .width(hud.barW.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val hint = SceneHud.hint(active != null, mate != null, cast.size, walking)
                    Row(Modifier.fillMaxWidth().height(WifeTheme.touch), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                        AnimatedVisibility(mate != null && active != null, enter = fadeIn(tween(MenuMotion.duration(FrontMotion.GLOW_MS, reduced))),
                            exit = fadeOut(tween(MenuMotion.duration(FrontMotion.GLOW_MS, reduced)))) {
                            WifeChip("Partner: ${rememberHeld(mate)?.let { names[it] } ?: ""}", { control = control.clearPartner() })
                        }
                        val line = (snapshot.notice ?: hint.takeIf { active != null }).orEmpty()
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (line.isNotEmpty()) HudLine(line)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(hud.barH.dp), contentAlignment = Alignment.BottomCenter) {
                        Crossfade(active?.id, Modifier.fillMaxSize(), animationSpec = tween(MenuMotion.duration(MenuMotion.PAGE_MS, reduced)),
                            label = "orders") { id ->
                            if (id == null) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                                    HudLine(SceneHud.hint(false, false, cast.size, false).ifEmpty { "Add NPCs to the scene to direct them." },
                                        Modifier.height(WifeTheme.touch), centered = true)
                                }
                            } else {
                                ActionBar(hud, slots, page, { page = it }, { slotEnabled(id, it) }, { act(id, it) })
                            }
                        }
                    }
                }
            }
            // a problem after loading (a prop without a surface, a file that wouldn't come): a fixed-width note on top
            ProblemNote(status.takeIf { revealed }, Modifier.align(Alignment.TopCenter).padding(top = (SceneHud.TOP_H + 4f).dp))
        }
    }
    choosingAnimation?.let { owner ->
        // with a partner chosen, the two-person animations come first and play with them (solo ones still play alone)
        val with = partnerId?.takeIf { it != owner }
        val shownChoices = remember(choices, with) { if (with == null) choices else choices.sortedByDescending { it.participantCount == 2 } }
        fun start(choice: AnimationChoice, mode: AnimationOrderMode) {
            val order = PendingAnimation(owner, choice, mode)
            if (choice.participantCount == 2) { if (with != null) schedule(order, with) else choosingPartner = order }
            else schedule(order, null)
        }
        AnimationBrowser(
            shownChoices, preview = null, onClose = { choosingAnimation = null },
            notice = snapshot.notice ?: with?.let { "Two-person animations play with ${names[it]}." },
            onPlay = { choice, mode ->
                if (mode == AnimationOrderMode.PLAY || mode == AnimationOrderMode.REPEAT) choosingAnimation = null
                start(choice, mode)
            },
            onPreview = { choice -> start(choice, AnimationOrderMode.PLAY) },
            partnerName = with?.let { names[it] },
            actorName = names[owner],
        )
    }
    val sayFor = rememberHeld(saying)
    if (sayFor != null) {
        val said = remember(words, sayFor) { words.sayings(sayFor) }
        HudPick("Say", said.map { it to it }, saying != null, { saying = null }) { text ->
            saying = null
            live.order { sim -> sim.say(sayFor, text); null }
        }
    }
    val chatFor = rememberHeld(chatting)
    if (chatFor != null) {
        HudPick("Chat with", cast.filter { it.id != chatFor }.map { it.id to it.name }, chatting != null, { chatting = null }) { other ->
            chatting = null
            chatWith(chatFor, other)
        }
    }
    val pending = rememberHeld(choosingPartner)
    if (pending != null) {
        val candidates = cast.filter { it.id != pending.owner }.map { it.id to it.name }
        if (candidates.isEmpty()) {
            HudConfirm("Partner animation", "Add a second NPC to the scene to play this animation.", "OK",
                choosingPartner != null, { choosingPartner = null }, { choosingPartner = null })
        } else {
            HudPick("Choose a partner", candidates, choosingPartner != null, { choosingPartner = null }) { other ->
                choosingPartner = null
                schedule(pending, other)
            }
        }
    }
    SceneMenu(menu, { menu = false }, onSettings?.let { open -> { menu = false; open() } }, { menu = false; ending = true })
    HudConfirm("End scene", "End this scene and go back to the title?", "End scene", ending, { ending = false }, {
        live.command { it.endPartners() }
        partnerSounds.stopAll()
        onEnd()
    })
}

/** What a cast frame says someone is doing. */
private fun doing(person: SceneSnapshot.PersonView, names: Map<String, String>): String =
    person.partner?.let { "With ${names[it]}" } ?: person.talkingWith?.let { "Talking to ${names[it]}" } ?: person.activity.description

/** The action bar's words for using an object. */
private fun useLabel(item: SceneObject): String = when (item) {
    SceneObject.CHAIR -> "Sit in chair"
    SceneObject.COUCH -> "Sit on couch"
    SceneObject.BED -> "Rest on bed"
    SceneObject.TABLE -> "Read a book"
}

/** Whether the partner fallback's game emote [anim] is held (Dance) rather than played once (Wave, Bow...). */
private fun emoteHeld(anim: Int): Boolean =
    PartnerEmotes.ALL.firstOrNull { it.anim == anim }?.let { it.loop || EmotePlayability.heldByName(it.name) } ?: false

/** The top bar: the place and the clock in a slate pill, the scene menu's gear at the other end, over a soft shade. */
@Composable
private fun SceneTopBar(place: String, onMenu: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val h24 = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
    var time by remember { mutableStateOf(clockNow(h24)) }
    LaunchedEffect(h24) { while (true) { time = clockNow(h24); delay(15_000) } }
    Box(modifier.height(SceneHud.TOP_H.dp).background(Brush.verticalGradient(listOf(Color(0x8C000000), Color.Transparent)))) {
        Row(Modifier.fillMaxSize().padding(horizontal = SceneHud.MARGIN.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.weight(1f, fill = false).clip(shape).background(WifeTheme.slate).border(WifeTheme.trimWidth, WifeTheme.trimSoft, shape)
                    .padding(horizontal = WifeTheme.gapL, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapM),
            ) {
                Text(place, Modifier.weight(1f, fill = false), style = WifeTheme.headingStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // a fixed width: "12:59 PM" and "1:05 AM" take the same room
                Text(time, Modifier.width(72.dp), style = WifeTheme.bodyStyle(WifeTheme.text), maxLines = 1, textAlign = TextAlign.End)
            }
            Spacer(Modifier.width(WifeTheme.gapS))
            HudIconButton(HudGlyph.GEAR, onMenu, "Scene menu")
        }
    }
}

private fun clockNow(h24: Boolean): String {
    val c = java.util.Calendar.getInstance()
    return SceneHud.clock(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), h24)
}

/**
 * One NPC's party frame: their initial on a round plate, the name in gold over what they are doing; the controlled one in a
 * gold edge with a glow, the partner in rose with "Partner" said. The whole frame is the touch target (taps control, or let go).
 */
@Composable
private fun CastFrame(name: String, doing: String, controlled: Boolean, partner: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val reduced = reduceMotion()
    val ms = MenuMotion.duration(FrontMotion.GLOW_MS, reduced)
    val edge by animateColorAsState(when { controlled -> WifeTheme.gold; partner -> PartnerRose; else -> WifeTheme.trimSoft }, tween(ms), label = "edge")
    val lit by animateFloatAsState(if (controlled) 1f else 0f, tween(ms), label = "lit")
    val shape = RoundedCornerShape(WifeTheme.buttonRadius)
    Row(
        modifier
            .semantics { contentDescription = "$name, $doing" + if (partner) ", partner" else "" }
            .drawBehind {
                if (lit > 0f) drawRoundRect(WifeTheme.glow.copy(alpha = 0.22f * lit), cornerRadius = CornerRadius(WifeTheme.buttonRadius.toPx()))
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(WifeTheme.slate, WifeTheme.slateDeep)))
            .border(if (controlled || partner) 2.dp else WifeTheme.trimWidth, edge, shape)
            .warPress(sound = "select", onClick = onClick)
            .padding(horizontal = WifeTheme.gapS),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS),
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(WifeTheme.slateRaised, WifeTheme.slateDeep)))
                .border(1.dp, edge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), style = WifeTheme.headingStyle())
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = frizStyle(WifeTheme.body, if (controlled) Color.White else WifeTheme.gold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (partner) "Partner · $doing" else doing, style = WifeTheme.captionStyle(if (partner) PartnerRose else WifeTheme.textDim),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A line of help over the scene, on a slate pill so it reads over any room. */
@Composable
private fun HudLine(text: String, modifier: Modifier = Modifier, centered: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape).background(WifeTheme.slate).border(WifeTheme.trimWidth, WifeTheme.trimSoft, shape)
            .padding(horizontal = WifeTheme.gapL, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(rememberHeld(text) ?: "", style = WifeTheme.bodyStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start)
    }
}

/**
 * The action bar: [SceneHud.SLOTS] slots a page in [HudLayout.cols] x [HudLayout.rows] (empty places kept, so the slots never
 * move), and the page button at its end (the game's action bar arrows: it turns the pages, and is dim with one page).
 */
@Composable
private fun ActionBar(
    hud: HudLayout,
    slots: List<HudSlot>,
    page: Int,
    onPage: (Int) -> Unit,
    enabled: (HudSlot) -> Boolean,
    onSlot: (HudSlot) -> Unit,
) {
    val pages = SceneHud.pages(slots.size)
    val at = SceneHud.clampPage(page, slots.size)
    val reduced = reduceMotion()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(SceneHud.GAP.dp), verticalAlignment = Alignment.Bottom) {
        Crossfade(at, Modifier.weight(1f), animationSpec = tween(MenuMotion.duration(MenuMotion.PAGE_MS, reduced)), label = "page") { p ->
            val shownSlots = SceneHud.page(slots, p)
            Column(verticalArrangement = Arrangement.spacedBy(SceneHud.GAP.dp)) {
                for (r in 0 until hud.rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(SceneHud.GAP.dp)) {
                        for (c in 0 until hud.cols) {
                            val slot = shownSlots.getOrNull(r * hud.cols + c)
                            if (slot == null) Spacer(Modifier.width(hud.slotW.dp).height(SceneHud.SLOT_H.dp))
                            else key(slot.key) { ActionSlot(slot, enabled(slot), { onSlot(slot) }, Modifier.width(hud.slotW.dp)) }
                        }
                    }
                }
            }
        }
        PageButton(at, pages, { onPage((at + 1) % pages) })
    }
}

/** One action-bar slot: a slate key with the gold trim, its words; lit gold while on (Walk), glowing while pressed. */
@Composable
private fun ActionSlot(slot: HudSlot, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val play = LocalSounds.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = reduceMotion()
    val glow by animateFloatAsState(
        when { !enabled -> 0f; pressed -> 1f; slot.lit -> 0.75f; else -> 0f },
        tween(MenuMotion.duration(FrontMotion.GLOW_MS, reduced)), label = "slot",
    )
    val sink by animateFloatAsState(MenuMotion.pressScale(pressed && enabled, reduced), tween(MenuMotion.duration(MenuMotion.PRESS_MS, reduced)), label = "press")
    val gate = rememberClickGate()
    Box(
        modifier
            .height(SceneHud.SLOT_H.dp)
            .graphicsLayer { scaleX = sink; scaleY = sink }
            .semantics { if (slot.lit) contentDescription = "${slot.label}, on" }
            .clickable(source, null, enabled = enabled, role = Role.Button, onClick = { if (gate.pass()) { play("click"); onClick() } })
            .drawBehind {
                val r = CornerRadius(WifeTheme.buttonRadius.toPx())
                val a = if (enabled) 0.94f else 0.5f
                drawRoundRect(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)), cornerRadius = r, alpha = a)
                if (glow > 0f) drawRoundRect(
                    Brush.radialGradient(listOf(WifeTheme.glow.copy(alpha = 0.38f * glow), Color.Transparent),
                        center = Offset(size.width / 2, size.height), radius = (size.width * 0.7f).coerceAtLeast(1f)),
                    cornerRadius = r,
                )
                drawRoundRect(Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color.Transparent), endY = size.height * 0.5f), cornerRadius = r)
                val w = (1f + glow).dp.toPx()
                val edge = if (enabled) WifeTheme.trim.copy(alpha = 0.7f + 0.3f * glow) else WifeTheme.trimSoft
                drawRoundRect(edge, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), r, style = Stroke(w))
            }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val color = when {
            !enabled -> WowColors.grey
            slot.lit || glow > 0.5f -> Color.White
            else -> WifeTheme.gold
        }
        Text(slot.label, style = frizStyle(13.sp, color), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The action bar's page button: "1/2" between two small chevrons; 48 dp; dim and still with one page. */
@Composable
private fun PageButton(page: Int, pages: Int, onNext: () -> Unit) {
    val many = pages > 1
    Column(
        Modifier.width(SceneHud.PAGER_W.dp).height(SceneHud.SLOT_H.dp)
            .semantics { contentDescription = "Action bar page ${page + 1} of $pages" }
            .warPress(many, sound = "tab", onClick = onNext),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val tint = if (many) WifeTheme.gold else WifeTheme.trimSoft
        Canvas(Modifier.width(14.dp).height(7.dp)) {
            drawLine(tint, Offset(0f, size.height), Offset(size.width / 2, 0f), 1.6.dp.toPx())
            drawLine(tint, Offset(size.width / 2, 0f), Offset(size.width, size.height), 1.6.dp.toPx())
        }
        Text("${page + 1}/$pages", style = WifeTheme.captionStyle(if (many) WifeTheme.text else WifeTheme.textDim), maxLines = 1)
        Canvas(Modifier.width(14.dp).height(7.dp)) {
            drawLine(tint, Offset(0f, 0f), Offset(size.width / 2, size.height), 1.6.dp.toPx())
            drawLine(tint, Offset(size.width / 2, size.height), Offset(size.width, 0f), 1.6.dp.toPx())
        }
    }
}

private enum class HudGlyph { GEAR, PLUS, MINUS, UP, DOWN }

/** A round slate button with a gold glyph: 40 dp drawn, 48 dp to touch. */
@Composable
private fun HudIconButton(
    glyph: HudGlyph,
    onClick: () -> Unit,
    description: String,
    repeatable: Boolean = false,
    enabled: Boolean = true,
    /** Held down, it repeats (the camera's up, down and zoom): a step at once, then every [HOLD_REPEAT_MS]. */
    hold: Boolean = false,
) {
    Box(
        Modifier.size(WifeTheme.touch).semantics { contentDescription = description }
            .then(if (hold) Modifier.holdRepeat(enabled, onClick) else Modifier.warPress(enabled = enabled, repeatable = repeatable, onClick = onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp)) {
            val r = size.minDimension / 2
            drawCircle(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)), r)
            drawCircle(WifeTheme.trim, r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            val k = r * 0.42f
            val w = 2.dp.toPx()
            when (glyph) {
                HudGlyph.PLUS -> {
                    drawLine(WifeTheme.gold, Offset(center.x - k, center.y), Offset(center.x + k, center.y), w)
                    drawLine(WifeTheme.gold, Offset(center.x, center.y - k), Offset(center.x, center.y + k), w)
                }
                HudGlyph.MINUS -> drawLine(WifeTheme.gold, Offset(center.x - k, center.y), Offset(center.x + k, center.y), w)
                HudGlyph.UP -> {
                    drawLine(WifeTheme.gold, Offset(center.x - k, center.y + k / 2), Offset(center.x, center.y - k / 2), w)
                    drawLine(WifeTheme.gold, Offset(center.x, center.y - k / 2), Offset(center.x + k, center.y + k / 2), w)
                }
                HudGlyph.DOWN -> {
                    drawLine(WifeTheme.gold, Offset(center.x - k, center.y - k / 2), Offset(center.x, center.y + k / 2), w)
                    drawLine(WifeTheme.gold, Offset(center.x, center.y + k / 2), Offset(center.x + k, center.y - k / 2), w)
                }
                HudGlyph.GEAR -> {
                    // eight teeth round a ring, a hole in the middle
                    val tooth = r * 0.16f
                    for (i in 0 until 8) rotate(i * 45f, center) {
                        drawRect(WifeTheme.gold, Offset(center.x - tooth / 2, center.y - r * 0.56f), Size(tooth, r * 0.2f))
                    }
                    drawCircle(WifeTheme.gold, r * 0.4f, center, style = Stroke(r * 0.16f))
                }
            }
        }
    }
}

/**
 * A press that repeats while held: [step] at once (with the interface's click), then after [HOLD_DELAY_MS] every
 * [HOLD_REPEAT_MS] until the finger lifts or slides off. Disabled: it takes no touches at all.
 */
@Composable
private fun Modifier.holdRepeat(enabled: Boolean, step: () -> Unit): Modifier {
    val play = LocalSounds.current
    val current by rememberUpdatedState(step)
    return this
        .semantics {
            role = Role.Button
            if (enabled) onClick { current(); true } else disabled()
        }
        .then(if (!enabled) Modifier else Modifier.pointerInput(Unit) {
            coroutineScope {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    play("click")
                    current()
                    val repeating = launch {
                        delay(HOLD_DELAY_MS)
                        while (true) { current(); delay(HOLD_REPEAT_MS) }
                    }
                    try { waitForUpOrCancellation()?.consume() } finally { repeating.cancel() }
                }
            }
        })
}

/**
 * The camera's controls at the screen's right: the camera button (Room view, Orbit, Follow: a tap gives the next), and under
 * it, while an orbit is shown, the up and down buttons (the view rises and lowers along the room's height) beside zoom in and
 * out, all held to repeat and eased by the camera's springs. Their place is kept while they are hidden: nothing moves.
 */
@Composable
private fun CameraControls(mode: CameraMode, canFollow: Boolean, onMode: (CameraMode) -> Unit, live: LiveScene, modifier: Modifier) {
    val reduced = reduceMotion()
    val orbit = mode != CameraMode.ROOM
    val controlsAlpha by animateFloatAsState(if (orbit) 1f else 0f,
        tween(MenuMotion.duration(if (orbit) MenuMotion.WINDOW_IN_MS else MenuMotion.WINDOW_OUT_MS, reduced)), label = "cameraControls")
    Column(modifier.width(SceneHud.CAMERA_W.dp), verticalArrangement = Arrangement.spacedBy(SceneHud.CAMERA_GAP.dp)) {
        CameraModeButton(mode, canFollow) { onMode(mode.next(canFollow)) }
        // hidden: an empty space of the same size (touches go through to the room); shown or fading: the buttons
        if (!orbit && controlsAlpha <= 0f) {
            Spacer(Modifier.width(SceneHud.CAMERA_W.dp).height((2 * SceneHud.SLOT_H + SceneHud.CAMERA_GAP).dp))
        } else Row(Modifier.graphicsLayer { alpha = controlsAlpha }, horizontalArrangement = Arrangement.spacedBy(SceneHud.CAMERA_GAP.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(SceneHud.CAMERA_GAP.dp)) {
                HudIconButton(HudGlyph.UP, { live.camera.liftBy(SceneCamera.LIFT_STEP) }, "Camera up", enabled = orbit, hold = true)
                HudIconButton(HudGlyph.DOWN, { live.camera.liftBy(-SceneCamera.LIFT_STEP) }, "Camera down", enabled = orbit, hold = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(SceneHud.CAMERA_GAP.dp)) {
                HudIconButton(HudGlyph.PLUS, { live.camera.zoomBy(1.25f) }, "Zoom in", enabled = orbit, hold = true)
                HudIconButton(HudGlyph.MINUS, { live.camera.zoomBy(0.8f) }, "Zoom out", enabled = orbit, hold = true)
            }
        }
    }
}

/** The camera button: the mode's name on a slate pill with the gold trim, 48 dp to touch, a fixed width (no shift). */
@Composable
private fun CameraModeButton(mode: CameraMode, canFollow: Boolean, onClick: () -> Unit) {
    val pill = RoundedCornerShape(50)
    val lit = mode != CameraMode.ROOM
    Box(
        Modifier.width(SceneHud.CAMERA_W.dp).height(WifeTheme.touch)
            .semantics { contentDescription = SceneHud.cameraDescription(mode, canFollow) }
            .warPress(sound = "tab", onClick = onClick)
            .padding(vertical = 4.dp)
            .clip(pill).background(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)))
            .border(if (lit) 2.dp else WifeTheme.trimWidth, if (lit) WifeTheme.gold else WifeTheme.trim, pill),
        contentAlignment = Alignment.Center,
    ) {
        Text(SceneHud.cameraLabel(mode), style = frizStyle(13.sp, if (lit) Color.White else WifeTheme.gold), maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** A problem said over the view after loading, in a box of fixed greatest width, faded in and out. */
@Composable
private fun ProblemNote(problem: String?, modifier: Modifier) {
    AnimatedVisibility(problem != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        val shape = RoundedCornerShape(WifeTheme.buttonRadius)
        Text(rememberHeld(problem) ?: "", Modifier.widthIn(max = 520.dp).clip(shape).background(WifeTheme.slate)
            .border(WifeTheme.trimWidth, WifeTheme.danger.copy(alpha = 0.7f), shape).padding(horizontal = WifeTheme.gapM, vertical = WifeTheme.gapS),
            style = WifeTheme.bodyStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The scene menu (the top bar's gear): Resume, Options when the caller opens them, End scene. Fades in and out. */
@Composable
private fun SceneMenu(visible: Boolean, onClose: () -> Unit, onOptions: (() -> Unit)?, onEnd: () -> Unit) {
    Overlay(onClose, visible) {
        WifeWindow("Scene", onClose, Modifier.widthIn(min = 260.dp, max = 340.dp).padding(WifeTheme.gapL)) {
            Column(verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                WifeButton("Resume", onClose, Modifier.fillMaxWidth(), kind = WifeButtonKind.PRIMARY, sound = "close")
                if (onOptions != null) WifeButton("Options", onOptions, Modifier.fillMaxWidth(), sound = "open")
                WifeButton("End scene", onEnd, Modifier.fillMaxWidth())
            }
        }
    }
}

/** A question in a small [WifeWindow]: Cancel and [ok]. Fades in and out ([visible]). */
@Composable
private fun HudConfirm(title: String, question: String, ok: String, visible: Boolean, onCancel: () -> Unit, onOk: () -> Unit) {
    Overlay(onCancel, visible) {
        WifeWindow(title, onCancel, Modifier.widthIn(max = 480.dp).padding(WifeTheme.gapL), footer = {
            if (ok != "OK") WifeButton("Cancel", onCancel, Modifier.widthIn(min = 110.dp), sound = "close")
            WifeButton(ok, onOk, Modifier.widthIn(min = 110.dp), kind = WifeButtonKind.PRIMARY, sound = "ok")
        }) {
            Text(question, style = WifeTheme.bodyStyle(), modifier = Modifier.padding(vertical = WifeTheme.gapXs))
        }
    }
}

/** A list of (id, words) to pick one from (Say, Chat with, a partner), in a [WifeWindow]; rows 48 dp or more. */
@Composable
private fun HudPick(title: String, options: List<Pair<String, String>>, visible: Boolean, onCancel: () -> Unit, onPick: (String) -> Unit) {
    Overlay(onCancel, visible) {
        WifeWindow(title, onCancel, Modifier.widthIn(min = 280.dp, max = 520.dp).heightIn(max = 520.dp).padding(WifeTheme.gapL)) {
            val list = rememberLazyListState()
            val keys = remember(options) { ListKeys.unique(options.map { it.first }) }
            LazyColumn(Modifier.weight(1f, fill = false).warScrollbar(list), list, verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                if (options.isEmpty()) item { WifeNote("Nothing to choose.", Modifier.padding(WifeTheme.gapS)) }
                items(options.size, key = { keys[it] }, contentType = { "row" }) { i ->
                    val (id, label) = options[i]
                    val shape = RoundedCornerShape(WifeTheme.buttonRadius)
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = WifeTheme.touch).clip(shape).background(WifeTheme.slateRaised.copy(alpha = 0.6f))
                            .border(WifeTheme.trimWidth, WifeTheme.trimSoft, shape).warPress { onPick(id) }
                            .padding(horizontal = WifeTheme.gapM, vertical = WifeTheme.gapS),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(label, style = WifeTheme.labelStyle(), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The live screen's ground (behind the view while it starts). */
private val SceneGround = Color(0xFF191719)
/** The partner's colour on the cast frames (the controlled one is gold). */
private val PartnerRose = Color(0xFFE88AA8)
/** Frames drawn after loading before the cover fades (the NPCs' skins are composed about one a frame). */
private const val REVEAL_FRAMES = 8L
/** Radians two fingers must turn before a pinch turns the orbit too. */
private const val TWIST_DEADZONE = 0.15f
/** A held camera button: the wait before it repeats, and the time between repeats, ms. */
private const val HOLD_DELAY_MS = 350L
private const val HOLD_REPEAT_MS = 90L
/** Touch slops two fingers' midpoint must travel before they pan (a pinch's drift never does). */
private const val PAN_SLOPS = 2f

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
        Column(Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = WifeTheme.gapL).wifeSurface().padding(WifeTheme.gapL),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, style = WifeTheme.titleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                Text(rememberHeld(status) ?: "Opening…", Modifier.fillMaxWidth(), style = WifeTheme.bodyStyle(),
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (progress != null) {
                val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(250), label = "bar")
                WifeProgress(p)
            } else {
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).clipToBounds().background(WifeTheme.track)) {
                    val sweep = rememberInfiniteTransition(label = "sweep")
                    val x by sweep.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "x")
                    Box(Modifier.fillMaxHeight().fillMaxWidth(0.3f).graphicsLayer { translationX = (x * 1.3f - 0.3f) * size.width / 0.3f }
                        .background(WifeTheme.gold))
                }
            }
        }
    }
}

/** The animation library's front page (Import, Browse, Manage) in a [WifeWindow]; Browse opens the [AnimationBrowser]. */
@Composable
fun SceneAnimationScreen(
    clips: List<CheckedAnimation>,
    preview: @Composable (CheckedAnimation) -> Unit,
    onImport: () -> Unit,
    onImportWickedWhims: () -> Unit,
    onBack: () -> Unit,
    /** The library's partner animations (the enabled ones are browsed with the clips). */
    pairs: List<PairAnim> = emptyList(),
    /** Opens the library's list (rename, recategorize, turn partner animations off for scenes, delete). */
    onManage: (() -> Unit)? = null,
) {
    var browsing by remember { mutableStateOf(false) }
    BackHandler(!browsing, onBack)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        WarcraftLoadingArt(Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
            WifeWindow("Animation library", onBack, Modifier.width(layout.optionsW.dp).heightIn(max = layout.optionsH.dp),
                subtitle = "${clips.size} clip${if (clips.size == 1) "" else "s"}, ${pairs.count { it.enabled }} partner animation${if (pairs.count { it.enabled } == 1) "" else "s"}") {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                    WifeButton("Browse animations", { browsing = true }, Modifier.fillMaxWidth(), kind = WifeButtonKind.PRIMARY, sound = "open")
                    WifeSectionHeader("Import")
                    WifeButton("Import WickedWhims", onImportWickedWhims, Modifier.fillMaxWidth())
                    WifeButton("Import rig and clips", onImport, Modifier.fillMaxWidth())
                    if (onManage != null) {
                        WifeSectionHeader("Library")
                        WifeButton("Manage library", onManage, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    if (browsing) AnimationBrowser(remember(clips, pairs) { animationChoices(clips) + pairs.filter { it.enabled }.map { it.choice() } },
        preview, onClose = { browsing = false })
}

/** [animation] with its Root track raised by [lift] yards (a role that stands above its anchor: on a bed, a lap). */
internal fun lifted(animation: Anim, lift: Float): Anim = if (lift == 0f) animation else animation.copy(tracks = animation.tracks.map { track ->
    if (track.bone == "Root" && track.channel == Channel.POSITION) {
        BoneTrack(track.bone, track.channel, track.times, track.values.copyOf().also { values ->
            for (i in 2 until values.size step 3) values[i] += lift
        })
    } else track
})
