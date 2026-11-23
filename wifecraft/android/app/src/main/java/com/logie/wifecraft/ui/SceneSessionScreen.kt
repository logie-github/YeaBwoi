package com.logie.wifecraft.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.logie.wifecraft.anim.CheckedAnimation
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.FleshProfile
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.AnimationChoice
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.PairAnim
import com.logie.wifecraft.anim.PairSync
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.RequirementStore
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.anim.animationChoices
import com.logie.wifecraft.anim.animationSequence
import com.logie.wifecraft.anim.StagePart
import com.logie.wifecraft.simulation.PlaybackSegment
import com.logie.wifecraft.simulation.ConsentAct
import com.logie.wifecraft.simulation.ConsentEdit
import com.logie.wifecraft.simulation.ConsentPolicy
import com.logie.wifecraft.simulation.SegmentKind
import com.logie.wifecraft.catalog.EmotePlayability
import com.logie.wifecraft.engine.Clock
import com.logie.wifecraft.simulation.PartnerSound
import com.logie.wifecraft.simulation.PerformanceOrder
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.simulation.ClipPlayback
import com.logie.wifecraft.simulation.PlaybackKeys
import com.logie.wifecraft.simulation.PlaybackRole
import com.logie.wifecraft.simulation.HoldFrame
import com.logie.wifecraft.simulation.PairConfirm
import com.logie.wifecraft.simulation.PreviewKeys
import com.logie.wifecraft.simulation.RoleMemory
import com.logie.wifecraft.simulation.RoleSwitch
import com.logie.wifecraft.simulation.StagePreview
import java.util.concurrent.ConcurrentHashMap
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.engine.FrameDriver
import com.logie.wifecraft.engine.FurniturePose
import com.logie.wifecraft.engine.Hit
import com.logie.wifecraft.engine.LocationLoader
import com.logie.wifecraft.engine.Thumbs
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.location.FurniturePiece
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.simulation.CameraClearance
import com.logie.wifecraft.simulation.CinematicDirector
import com.logie.wifecraft.simulation.CinematicIdle
import com.logie.wifecraft.simulation.ShotLibrary
import com.logie.wifecraft.simulation.CinematicLook
import com.logie.wifecraft.simulation.CinematicSettings
import com.logie.wifecraft.simulation.Furniture
import com.logie.wifecraft.simulation.Availability
import com.logie.wifecraft.simulation.FurnitureAvailability
import com.logie.wifecraft.simulation.TreatAs
import com.logie.wifecraft.simulation.TreatAsMemory
import com.logie.wifecraft.simulation.FurnitureAnimation
import com.logie.wifecraft.simulation.FurnitureSelection
import com.logie.wifecraft.simulation.SceneSimulation
import com.logie.wifecraft.simulation.Activity
import com.logie.wifecraft.simulation.CameraMode
import com.logie.wifecraft.simulation.BuildGizmo
import com.logie.wifecraft.simulation.RoleActor
import com.logie.wifecraft.simulation.InteractionSpot
import com.logie.wifecraft.simulation.ItemOverrides
import com.logie.wifecraft.simulation.LiveScene
import com.logie.wifecraft.simulation.ModelEmotes
import com.logie.wifecraft.simulation.PartnerEmotes
import com.logie.wifecraft.simulation.PartnerFrequency
import com.logie.wifecraft.characters.CreatureNpcs
import com.logie.wifecraft.characters.JoiDragonSway
import com.logie.wifecraft.dialogue.DialogueEngine
import com.logie.wifecraft.dialogue.JoiPersona
import com.logie.wifecraft.dialogue.JoiPrompt
import com.logie.wifecraft.dialogue.KinkPack
import com.logie.wifecraft.simulation.JoiAnimationPicker
import com.logie.wifecraft.simulation.JoiAnimations
import com.logie.wifecraft.simulation.JoiCamera
import com.logie.wifecraft.simulation.JoiClip
import com.logie.wifecraft.simulation.JoiFraming
import com.logie.wifecraft.simulation.JoiHandjobs
import com.logie.wifecraft.simulation.JoiStroke
import com.logie.wifecraft.simulation.StrokeHands
import com.logie.wifecraft.simulation.JoiMode
import com.logie.wifecraft.simulation.JoiPacing
import com.logie.wifecraft.simulation.JoiPrior
import com.logie.wifecraft.simulation.ChatCamera
import com.logie.wifecraft.simulation.ChatMode
import com.logie.wifecraft.dialogue.ChatNames
import com.logie.wifecraft.dialogue.ChatPrompt
import com.logie.wifecraft.dialogue.ChatSession
import com.logie.wifecraft.dialogue.ChatSetup
import com.logie.wifecraft.dialogue.ChatStore
import com.logie.wifecraft.anim.AnimArrays
import com.logie.wifecraft.characters.DragonStages
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
import com.logie.wifecraft.simulation.ClockSettings
import com.logie.wifecraft.simulation.ClockSpeed
import com.logie.wifecraft.simulation.InteractionId
import com.logie.wifecraft.simulation.LifePersonView
import com.logie.wifecraft.simulation.LifeResult
import com.logie.wifecraft.simulation.LifeSim
import com.logie.wifecraft.simulation.LifeProp
import com.logie.wifecraft.simulation.LifeProps
import com.logie.wifecraft.simulation.LifeView
import com.logie.wifecraft.simulation.LightFeed
import com.logie.wifecraft.simulation.WorldClock
import com.logie.wifecraft.simulation.FlowPhase
import com.logie.wifecraft.simulation.FlowStep
import com.logie.wifecraft.simulation.HudReveal
import com.logie.wifecraft.simulation.ImmersiveSettings
import com.logie.wifecraft.graphics.Graphics
import com.logie.wifecraft.simulation.Sparkles
import androidx.compose.runtime.collectAsState
import com.logie.wifecraft.graphics.SkinSheen
import com.logie.wifecraft.simulation.IntensityEase
import com.logie.wifecraft.simulation.IntensityModel
import com.logie.wifecraft.simulation.MomentProgress
import com.logie.wifecraft.simulation.SceneFlow
import com.logie.wifecraft.simulation.SoftControl
import com.logie.wifecraft.creator.Sex
import androidx.compose.ui.layout.onSizeChanged
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
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
                subtitle = "Step ${step.ordinal + 1} of ${SetupStep.entries.size + 1}",
                footer = {
                    WifeButton("Back", onBack, Modifier.widthIn(min = 120.dp), sound = "close")
                    WifeButton(
                        "Next", onNext, Modifier.widthIn(min = 140.dp),
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

/** A two-person animation waiting on its preview ([confirm]): the order as chosen, on [item] when played on furniture. */
private data class PairPlay(val confirm: PairConfirm, val order: PendingAnimation, val item: SceneObject?)

/** Where the switch per animation is kept (RoleMemory.encode). */
private const val ROLE_PREFS = "partner_roles"
private const val ROLE_KEY = "switched"

/** The Play animation window of a piece of furniture: [owner] plays, on [item], one of [choices] (only what fits on it). */
private class FurnitureMenu(val owner: String, val item: SceneObject, val choices: List<AnimationChoice>)

/** What each animation needs (anim/Requirements): by the browser's choice id, and by the partner pair's id (the simulation's). */
private class AnimationNeeds(val choices: Map<String, Requirement> = emptyMap(), val pairs: Map<String, Requirement> = emptyMap())

/**
 * The needs of [choices] and the partner pairs (off the main thread): the library's kept requirements ([RequirementStore]:
 * its single clips and enabled pairs, classified once and kept beside it), the authors' two-person performances classified
 * here. One that can't be classified is left out (it plays as before, and isn't offered on furniture).
 */
private fun needsOf(choices: List<AnimationChoice>, library: List<PairAnim>, partnerPairs: List<PartnerPair>): AnimationNeeds {
    val enabled = library.filter { it.enabled }
    val singles = choices.filter { it.participantCount == 1 }.mapNotNull { it.clips.firstOrNull()?.clip }.distinctBy { it.id }
    val kept = try { RequirementStore.all(singles, enabled) } catch (e: Exception) { emptyMap() }
    val byChoice = HashMap<String, Requirement>()
    val byPair = HashMap<String, Requirement>()
    for (p in enabled) kept[PairAnim.CHOICE_PREFIX + p.id]?.let { byPair[p.id] = it; byChoice[PairAnim.CHOICE_PREFIX + p.id] = it }
    for (pp in partnerPairs) {
        if (pp.id in byPair) continue
        val r = try { FurnitureAnimation.of(pp).requirement } catch (e: Exception) { null } ?: continue
        byPair[pp.id] = r
        byChoice[pp.id] = r
    }
    for (c in choices) if (c.participantCount == 1) c.clips.firstOrNull()?.clip?.let { a -> kept[a.id]?.let { byChoice[c.id] = it } }
    return AnimationNeeds(byChoice, byPair)
}

/** Whether [r] was read from the bodies (where each actor goes is known): only those are offered on furniture. */
private fun placedNeed(r: Requirement) = r.surface != Surface.UNKNOWN && "geometry" in r.basis

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
    /**
     * The life simulation for this cast (LifeApi: its ids the cast's, in order; one that isn't is left out), or null: none.
     * Once the scene runs only the GL thread touches it (through LiveScene.life).
     */
    lifeSim: LifeSim? = null,
    /** Options' Life simulation switch: off, the needs stand still and NPCs choose as they did without it (LifeControl.setEnabled). */
    lifeEnabled: Boolean = true,
    /**
     * The world's clock (WorldClock): driven here every frame on the GL thread (its hour lights the scene through
     * SceneView.setTimeOfDay and drives the life simulation); nothing else touches it once the scene runs. Null: no clock.
     */
    worldClock: WorldClock? = null,
    /** Options' clock settings (speed, start hour, day/night cycle): applied to the running clock when they change. */
    clockSettings: ClockSettings = ClockSettings.DEFAULT,
    /** The clock's light hour and real seconds a game hour, for the place's sound (Audio.timeOfDay): main thread, 4 a second. */
    onTimeOfDay: (hours: Double, realSecondsPerHour: Double) -> Unit = { _, _ -> },
    /** Options' Cinematic page (applied at once), and cinematic mode going on or off (the music eases down: Audio.cinematic). */
    cinematicSettings: CinematicSettings = CinematicSettings.DEFAULT,
    onCinematic: (Boolean) -> Unit = {},
    /** Options' Dialogue page: whether NPCs talk with the on-device model, and how much (applied at once). */
    dialogue: com.logie.wifecraft.dialogue.DialogueSettings = com.logie.wifecraft.dialogue.DialogueSettings(),
    /** The life and the clock as JSON to keep (null: none), every half minute and when the scene ends. Any thread. */
    onLifeSave: (life: String?, clock: String?) -> Unit = { _, _ -> },
    /** The game's icons (the life HUD's: needs, moods, thoughts, the radial menu); null: their drawn glyphs. */
    icons: GameIcons? = null,
    /** Options' Immersion page (applied at once): the life HUD, the HUD's time, the flow, body language, atmosphere. */
    immersive: ImmersiveSettings = ImmersiveSettings.DEFAULT,
    /** The equipment slots an NPC wears now, and what they keep on (the flow's undressing). */
    worn: (String) -> Set<Int> = { emptySet() },
    keepOn: (String) -> Set<Int> = { emptySet() },
    /** An NPC dressed again without these equipment slots (empty: as they were). Main thread. */
    onUndress: (String, Set<Int>) -> Unit = { _, _ -> },
    /** A piece of clothing came off (its rustle). Main thread. */
    onCloth: () -> Unit = {},
    /** The scene's intensity (0..1, the most intense person's, eased; null when the scene ends), four times a second. */
    onIntensity: (Float?) -> Unit = {},
    /** The Consent and limits policy (null: none): the scene's NPCs follow it everywhere an act can start; edited live by [onLimits]'s window. */
    consent: ConsentPolicy? = null,
    /** Opens the Consent and limits window from the scene menu (null: the menu has none). */
    onLimits: (() -> Unit)? = null,
    /** Opens the same window on one NPC's tab (More's Limits for the chosen NPC); null: More has the plain entry. */
    onLimitsFor: ((String) -> Unit)? = null,
) {
    // the Character size in force for this scene (before anything below is built from the pair frame; held until the scene ends)
    val sizeContext = LocalContext.current
    remember { com.logie.wifecraft.graphics.CharacterSizeSetting.beginScene(sizeContext) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.logie.wifecraft.graphics.CharacterSizeSetting.endScene() } }
    // the scene's seed: one per scene, so a scene replays the same way from it (the tests use it so)
    val seed = remember(cast, objects) { Random.nextLong() }
    val choices = remember(clips, pairs) { animationChoices(clips) + pairs.filter { it.enabled }.map { it.choice() } }
    // the Play animation window's repeat and queue choices (PlayOptions), kept for this scene: its place and its cast
    val playContext = LocalContext.current
    val playPrefs = remember(playContext) { playContext.getSharedPreferences("scene_play_options", android.content.Context.MODE_PRIVATE) }
    val playKey = remember(locationName, cast) { PlayOptions.sceneKey(locationName, cast.map { it.id }) }
    var playOptions by remember(playKey) { mutableStateOf(PlayOptions.decode(runCatching { playPrefs.getString(playKey, null) }.getOrNull())) }
    // Options-style switch kept with them: a partner animation asks ("Ready to play") before it plays; off: it plays at once, in the roles last kept
    var askPairs by remember(playContext) { mutableStateOf(runCatching { playPrefs.getBoolean(ASK_PAIR_KEY, false) }.getOrDefault(false)) }
    fun chooseAskPairs(on: Boolean) {
        askPairs = on
        runCatching { playPrefs.edit().putBoolean(ASK_PAIR_KEY, on).apply() }
    }
    fun choosePlayOptions(options: PlayOptions) {
        playOptions = options
        runCatching { playPrefs.edit().putString(playKey, options.encode()).apply() }
    }
    // what NPCs pick from by themselves: the library's enabled pairs and the authors' two-actor performances
    // (+ the creature's own stages: Sindragosa's, offered and played only with her, characters/DragonStages)
    val stageChoices = remember { DragonStages.pairs().map { it.choice() } }
    val partnerPairs = remember(pairs, choices) {
        PartnerPair.enabled(pairs) + choices.mapNotNull { PartnerPair.of(it) } + DragonStages.pairs().map { PartnerPair.of(it) }
    }
    // (the cinematic auto-play cycles the library's stages: a creature's own are played from the animation browser)
    val autoPairs = remember(partnerPairs) { partnerPairs.filter { it.exclusiveTo == null } }
    // what the NPCs say to each other: their names, races and sexes, and the place
    val words = remember(cast, locationName) {
        Chatter(cast.associate { it.id to Speaker(it.name, it.appearance.raceId, it.appearance.sex == Sex.FEMALE) }, locationName)
    }
    // the life simulation, only when its cast is this one in the same order (SceneSimulation insists on it)
    val life = remember(cast, lifeSim) { lifeSim?.takeIf { it.ids == cast.map { a -> a.id } } }
    val live = remember(cast, objects, life) {
        val origin = toStage(Vec3(0f, 0f, 0f))
        LiveScene(cast.map { it.id }, objects, emptyMap(), camera, origin.x, origin.y, origin.z, stageRotation, Random(seed),
            wanderOnly = true, route = { from, to -> view.glWalkRoute(toStage(from), toStage(to))?.map(toLocal) },
            pairs = partnerPairs, emotes = emotes, partnerChance = partner.chance,
            chatter = words, chatterChance = chatter.chance, lifeSim = life)
    }
    // the world's clock: the life simulation follows it, its speed buttons go through life (LifeSim.onSpeed) to it
    val clock = worldClock
    DisposableEffect(live, clock) {
        if (clock != null) {
            life?.onSpeed = { s -> clock.speed = LifeHud.clockSpeed(s) }   // GL thread (a command), as the clock's driver
            live.life.setTimeSource(clock)
        }
        onDispose { life?.onSpeed = null }
    }
    LaunchedEffect(live, lifeEnabled) { live.life.setEnabled(lifeEnabled) }
    // Options' clock settings on the running clock (its speed and cycle: GL thread)
    LaunchedEffect(live, clock, clockSettings) {
        if (clock != null) live.command { sim ->
            clock.apply(clockSettings)
            sim.life?.setSpeed(LifeHud.lifeSpeed(clockSettings.speed))
        }
    }
    // the speech bubbles: the GL thread lays them out each frame, the overlay draws them; each person's line now
    val speechSpots = remember(live) { SpeechSpots(live.count) }
    val lines = remember(live) { mutableStateListOf<String?>().apply { repeat(live.count) { add(null) } } }
    // each one's head: a human's of their sex at human scale, a dwarf's or gnome's own (anim/HumanScale)
    val heads = remember(cast) {
        FloatArray(cast.size) { CreatureNpcs.of(cast[it].appearance)?.head ?: HumanScale.head(cast[it].appearance.raceId, cast[it].appearance.sex == Sex.FEMALE) }
    }
    // each one's size for the pairs' positioning: a gnome's own, everyone else 1 (HumanScale.pairScale, simulation/PairFit)
    val pairSizes = remember(cast) {
        cast.associate { it.id to HumanScale.pairScale(it.appearance.raceId, it.appearance.sex == Sex.FEMALE) }
    }
    // the creature NPCs (Sindragosa's dragon): game animations only, put at their role's marker (characters/CreatureNpcs)
    val creatureSpecs = remember(cast) { cast.mapNotNull { a -> CreatureNpcs.of(a.appearance)?.let { a.id to it } }.toMap() }
    // each one's body over a human's (a dwarf's or gnome's own): what furniture fits them (simulation/FurnitureFit)
    val bodyScales = remember(cast) {
        cast.associate { it.id to HumanScale.bodyScale(it.appearance.raceId, it.appearance.sex == Sex.FEMALE) }
    }
    // what each animation needs (a bed, a seat, a top, the floor), read off the main thread
    val needs by produceState(AnimationNeeds(), choices, partnerPairs) {
        value = withContext(Dispatchers.Default) { needsOf(choices, pairs, partnerPairs) }
    }
    // the place's furniture as locations.json measures it (its beds', seats' and tops' heights), by the object it is
    val context = LocalContext.current
    val pieces by produceState(emptyList<FurniturePiece>(), locationName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open("locations.json").bufferedReader().use { LocationInfo.parseAll(it.readText()) }
                    .firstOrNull { it.name == locationName }?.furniture.orEmpty()
            } catch (e: Exception) { emptyList() }
        }
    }
    // the room's props for the life simulation (a bookshelf...: locations.json "props", only the game's own models there)
    val lifeProps by produceState(emptyList<LifeProp>(), locationName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open("locations.json").bufferedReader().use { LifeProps.of(it.readText(), locationName, toLocal, -stageRotation) }
            } catch (e: Exception) { emptyList() }
        }
    }
    LaunchedEffect(live, lifeProps) { live.life.setProps(lifeProps) }
    val furnitureMeasured = remember(pieces, objects) {
        val out = HashMap<SceneObject, Furniture>()
        for (item in objects) pieces.firstOrNull { it.item == item.name }?.let { piece ->
            Furniture.of(piece, toLocal, -stageRotation)?.let { out[item] = it }
        }
        out
    }
    // Move mode (ui/ItemMapperUi.kt): the player's item mappings (files/reports) and the shipped ones (assets/item_overrides.json)
    // override each piece's class and places; the measured shape stays what a report describes
    val mapper = rememberItemMapper(live, locationName)
    // Build mode (ui/BuildModeUi.kt): items given an interaction there override the furniture too (any prop can be a bed)
    val build = rememberBuildController(live, view)
    build.toStage = toStage; build.toLocal = toLocal; build.stageRotation = stageRotation; build.keyOf = mapper::keyOf
    LaunchedEffect(build, mapper.placeId) { if (mapper.placeId.isNotEmpty()) build.load(locationName, mapper.placeId) }
    LaunchedEffect(build, cast) {
        build.cast = cast.associate { it.id to RoleActor(it.appearance.raceId, it.appearance.sex == Sex.FEMALE) }
        build.names = cast.associate { it.id to it.name }
    }
    val furnitureAt = remember(furnitureMeasured, mapper.local, mapper.shipped, mapper.placeId, build.items, build.spotSig) {
        build.overlay(mapper.overridden(furnitureMeasured))
    }
    build.furnitureOf = { furnitureAt[it] }
    // the clips every playback key names (GL thread reads it): the orders' (request) and the partner pairs' roles
    val playback = remember(live) { ConcurrentHashMap<String, Anim>() }
    // what each playback key's stage does to a man wearing the body parts (bodyparts/Arousal: assets/arousal_map.json)
    val arousalMap = remember {
        runCatching { com.logie.wifecraft.bodyparts.ArousalMap.parse(context.assets.open("arousal_map.json").bufferedReader().use { it.readText() }) }.getOrNull()
    }
    val stageClasses = remember(live) { ConcurrentHashMap<String, com.logie.wifecraft.bodyparts.StageClass>() }
    LaunchedEffect(live, partnerPairs, partner, emotes, chatter) {
        for (p in partnerPairs) {
            playback[PlaybackKeys.pair(p.id, 0)] = lifted(p.a.copy(loop = false), p.slotA.z)
            playback[PlaybackKeys.pair(p.id, 1)] = lifted(p.b.copy(loop = false), p.slotB.z)
            arousalMap?.classify(com.logie.wifecraft.bodyparts.StageHints(listOf(p.name, p.a.name, p.a.sourceName, p.b.sourceName), p.kind, 2))?.let { cls ->
                stageClasses[PlaybackKeys.pair(p.id, 0)] = cls
                stageClasses[PlaybackKeys.pair(p.id, 1)] = cls
            }
        }
        live.command { sim ->
            sim.pairs = partnerPairs; sim.partnerChance = partner.chance; sim.emotes = emotes; sim.chatterChance = chatter.chance
            sim.sizes = pairSizes
            sim.creatures = creatureSpecs
            sim.bodyScales = bodyScales
        }
    }
    LaunchedEffect(live, needs) { val known = needs.pairs; live.command { it.requirements = known } }
    // the limits: the policy object is shared with the Consent window (thread-safe), the scene reads it as acts start
    LaunchedEffect(live, consent) { live.command { it.consent = consent } }
    // the player's "Treat as" per animation (a long press in the animation window), kept; the scene plays each as that
    val treatPrefs = remember(context) { context.getSharedPreferences(TreatAsMemory.PREFS, android.content.Context.MODE_PRIVATE) }
    var treatAs by remember(treatPrefs) {
        mutableStateOf(TreatAsMemory.decode(try { treatPrefs.getString(TreatAsMemory.KEY, null) } catch (e: Exception) { null }).all.toMap())
    }
    fun treat(choice: AnimationChoice, t: TreatAs?) {
        val memory = TreatAsMemory(treatAs)
        if (!memory.set(choice.id.removePrefix(PairAnim.CHOICE_PREFIX), t)) return
        treatAs = memory.all.toMap()
        try { treatPrefs.edit().putString(TreatAsMemory.KEY, memory.encode()).apply() } catch (e: Exception) { }
    }
    LaunchedEffect(live, treatAs) { val known = treatAs; live.command { it.treatAs = known } }
    // how each animation plays here (its chip in the animation window: Ready, on the bed, on the floor in its place), off the main thread
    val availability by produceState(emptyMap<String, Availability>(), choices, needs, furnitureAt, treatAs) {
        value = withContext(Dispatchers.Default) {
            val here = furnitureAt.values.toList()
            val out = HashMap<String, Availability>()
            for (c in choices) {
                val r = needs.choices[c.id] ?: continue
                out[c.id] = try { FurnitureAvailability.of(r, here, SceneSimulation.HUMAN_BODY_UNIT, treatAs[c.id.removePrefix(PairAnim.CHOICE_PREFIX)]) }
                    catch (e: Exception) { continue }
            }
            out
        }
    }
    // each one's own flesh as the engine measured their dressed model (anim/FleshProfile: how far a clip is raised off the
    // floor, a seat or a bed for them, how far apart a pair's bodies are kept), again whenever someone is redressed; and the
    // single clips the orders name, for a single clip's lift on furniture
    LaunchedEffect(live) { live.command { sim -> sim.clips = { key -> playback[key] } } }
    LaunchedEffect(live, cast) {
        var seen = -1L
        while (true) {
            val version = view.fleshVersion
            if (version != seen) {
                seen = version
                val measured = HashMap<String, FleshProfile>()
                for (c in cast) view.flesh(actorKey(c.id))?.let { measured[c.id] = it }
                live.command { sim -> sim.flesh = measured }
            }
            delay(500)
        }
    }
    LaunchedEffect(live, furnitureAt) {
        val measured = furnitureAt
        live.command { sim -> for ((item, f) in measured) sim.setFurniture(item, f) }
    }
    SideEffect {
        live.roomView = camera
        live.camera.floorZ = toStage(Vec3(0f, 0f, 0f)).z   // the stage's floor: the camera's lift stays in the room
    }
    // the furniture views' walls: a walk along the floor from beside the furniture to under the eye must be clear (the
    // scene's own test, GL thread); a view none passes is offered without it (LiveScene)
    DisposableEffect(live, view) {
        live.clearance = CameraClearance { eye, at -> view.glWalkRoute(at, eye)?.size == 1 }
        onDispose { live.clearance = null }
    }
    var snapshot by remember(live) { mutableStateOf(live.snapshot()) }
    // cinematic mode (the Cinematic button): the director's camera, the mood's light, the HUD hidden (a tap shows it a while)
    val cineLook = remember(live) { CinematicLook() }
    val cineDirector = remember(live) { CinematicDirector(cinematicSettings) }
    // the shot library (assets/shot_library.json): the director's shots for what the cast plays; without it, its own mix
    LaunchedEffect(cineDirector) {
        cineDirector.library = withContext(Dispatchers.IO) {
            runCatching { ShotLibrary.parse(context.assets.open("shot_library.json").bufferedReader().use { it.readText() }) }.getOrNull()
        }
    }
    var cinematic by remember(live) { mutableStateOf(false) }
    // JOI mode (the JOI order: JoiMode, JoiCamera, ui/JoiUi.kt): who gives it (null: off), its camera, the redraw count, a leave asked for
    var joiActor by remember(live) { mutableStateOf<String?>(null) }
    val joiCamera = remember(live) { JoiCamera(seed.toLong()) }
    var joiTick by remember(live) { mutableIntStateOf(0) }
    var strokeBaked by remember(live) { mutableIntStateOf(-1) }
    var strokeBakedAt by remember(live) { mutableLongStateOf(0L) }
    var strokeEnds by remember(live) { mutableLongStateOf(0L) }
    var strokeRuns by remember(live) { mutableIntStateOf(0) }
    var joiLeave by remember(live) { mutableStateOf(false) }
    var joiPersona by remember(live) { mutableStateOf<JoiPersona?>(null) }
    // Chat mode (the Chat order: ChatMode, ChatCamera, ui/ChatUi.kt): who she is (null: off), the setup sheet's NPC (null: closed), the redraw count, a leave asked for
    var chatActor by remember(live) { mutableStateOf<String?>(null) }
    var chatSheet by remember(live) { mutableStateOf<String?>(null) }
    val chatCamera = remember(live) { ChatCamera(seed.toLong() + 3) }
    val chatMode = remember(live) { ChatMode(seed.toLong() + 7) }
    var chatTick by remember(live) { mutableIntStateOf(0) }
    var chatLeave by remember(live) { mutableStateOf(false) }
    // Chat mode: her reply as the world's chat bubble over her head (set by the chat layer)
    var chatSpeech by remember(live) { mutableStateOf<String?>(null) }
    // cinematic mode's idle hide (CinematicIdle): all the interface goes after 5 s untouched, Build mode off, until the next touch
    val cineIdleClock = remember(live) { CinematicIdle { SystemClock.uptimeMillis() } }
    var cineIdle by remember(live) { mutableStateOf(false) }
    val cineNotify by rememberUpdatedState(onCinematic)
    SideEffect {
        // Chat mode: a clean full-width view (no film frame, no bars)
        cineLook.settings = if (chatActor != null) cinematicSettings.copy(letterbox = false, frame = com.logie.wifecraft.simulation.CinematicFrame.FULL) else cinematicSettings
        cineDirector.settings = cinematicSettings
        live.director = cineDirector
        cineLook.on = cinematic
        // Build mode's orbit round the selection is the camera while it is on (the film frame, light and shots stay)
        live.directing = BuildEntry.directorCamera(cinematic, build.on)
    }
    LaunchedEffect(live, cinematic) { cineNotify(cinematic) }
    // Build mode's sharp image (graphics/SharpImage; Options' Graphics: Sharp image in Build mode): the renderer's effects are set sharp while it is on
    SideEffect { Graphics.buildMode = build.on }
    DisposableEffect(Unit) { onDispose { Graphics.buildMode = false } }
    // interactable sparkles (engine/sparkle.h): WoW-style twinkles over every spot a body can use (the scene's usable furniture and the
    // New tab's spots), hidden while one is occupied; off in Build mode, in cinematic mode and with the switch off; fewer as the governor lowers effects
    val graphicsNow by Graphics.live.collectAsState()
    LaunchedEffect(view, build.sparklesOn, build.on, cinematic, snapshot.holders, snapshot.usable, furnitureAt, build.spotSig, graphicsNow.lowered.size) {
        val show = Sparkles.shown(build.sparklesOn, build.on, cinematic)
        view.setSparkles(Sparkles.floats(build.sparkleSources(furnitureAt, snapshot.usable, snapshot.holders.keys), show), Sparkles.density(graphicsNow.lowered.size))
    }
    DisposableEffect(view) { onDispose { view.setSparkles(FloatArray(0), 0f) } }
    DisposableEffect(live, view) { onDispose { live.directing = false; view.clearCinematic(); cineNotify(false) } }
    // who the player controls and that NPC's partner (SceneControl: a tap on another NPC makes it the partner)
    var control by remember { mutableStateOf(SceneControl()) }
    val selected = control.selected
    val partnerId = control.partner
    // Walk on: a tap on the floor sends the controlled NPC there. The camera: the room's view, the orbit, or Follow
    var walking by remember { mutableStateOf(false) }
    var cameraMode by remember(live) { mutableStateOf(CameraMode.ROOM) }
    // the furniture view shown (an index into the controlled NPC's views; -1: none) and the Play window of a tapped piece
    var furnitureView by remember(live) { mutableIntStateOf(-1) }
    var furnitureMenu by remember { mutableStateOf<FurnitureMenu?>(null) }
    var ending by remember { mutableStateOf(false) }
    var choosingAnimation by remember { mutableStateOf<String?>(null) }
    var choosingPartner by remember { mutableStateOf<PendingAnimation?>(null) }
    // Switch positions: the preview before a two-person animation plays (PairConfirm), the switch kept per animation (RoleMemory)
    val rolePrefs = remember(context) { context.getSharedPreferences(ROLE_PREFS, android.content.Context.MODE_PRIVATE) }
    val roleMemory = remember(rolePrefs) { RoleMemory.decode(try { rolePrefs.getString(ROLE_KEY, null) } catch (e: Exception) { null }) }
    var pairPlay by remember(live) { mutableStateOf<PairPlay?>(null) }
    // the last two-person animation played from the preview: its id and its two people (a switch mid-play is kept for it)
    var lastPair by remember(live) { mutableStateOf<PairConfirm?>(null) }
    // the Say list open for this NPC, the Chat with list, the scene menu (the top bar's gear)
    var saying by remember { mutableStateOf<String?>(null) }
    var chatting by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    // the one sheet that may be open over the scene (ScreenPanels: Time, More; Build's and Move's panels and auto-play's follow their modes)
    var panel by remember(live) { mutableStateOf(ScreenPanel.NONE) }
    var autoRect by remember(live) { mutableStateOf<ScreenRect?>(null) }
    val names = remember(cast) { cast.associate { it.id to it.name } }
    // "Won't: <name>" on a two-person animation the owner or their partner won't do (the scene declines it all the same)
    val limitNoteOf: (String, String?, AnimationChoice) -> String? = { owner, with, c ->
        if (consent == null || with == null || c.participantCount != 2) null
        else consent.decliner(owner, with, ConsentAct.of(c))?.let { "Won't: ${names[it] ?: it}" }
    }
    // the life HUD: its radial menu (and what can be had there), the Life window, the clock's speed buttons, the feed
    var radial by remember(live) { mutableStateOf<RadialRequest?>(null) }
    var radialAvail by remember(live) { mutableStateOf<List<Pair<InteractionId, Boolean>>?>(null) }
    var lifeWindow by remember(live) { mutableStateOf<String?>(null) }
    var lifeTab by remember { mutableStateOf(LifeTab.NEEDS) }
    // the answer to the player's last interaction, and when it came (the same answer twice still shows twice)
    var lifeToast by remember(live) { mutableStateOf<Pair<LifeResult, Long>?>(null) }
    // immersive mode (Options' Immersion, Show life HUD off): the HUD hidden until a tap, then up a few seconds (HudReveal)
    val immersiveHud = !immersive.lifeHud
    val hudReveal = remember(live) { HudReveal() }
    var hudUp by remember(live) { mutableStateOf(false) }
    var revealTick by remember(live) { mutableIntStateOf(0) }
    fun revealHud() { hudReveal.tap(SystemClock.uptimeMillis()); revealTick++ }
    // the scene's flow between the controlled NPC and their partner (SceneFlow), shared with the GL thread (ImmersionFeed)
    var flow by remember(live) { mutableStateOf<SceneFlow?>(null) }
    var flowPhase by remember(live) { mutableStateOf(FlowPhase.NONE) }
    val feed = remember(live) { ImmersionFeed() }
    SideEffect { feed.settings = immersive.clamped(); feed.cinematic = cinematic }
    // the dialogue layer: NPCs speak with the on-device model (dialogue/), its lore and kink packs read from the assets; no model loaded, no change
    val dialogueDirector = remember(live) {
        com.logie.wifecraft.ui.DialogueAssets.lore(context)?.let { lore ->
            com.logie.wifecraft.dialogue.DialogueDirector({ com.logie.wifecraft.dialogue.DialogueRuntime.engine }, lore, live.speechSink(), dialogue,
                kink = com.logie.wifecraft.ui.DialogueAssets.kink(context))
        }
    }
    SideEffect { dialogueDirector?.settings = dialogue; chatMode.session?.dirty = dialogue.dirty }
    DisposableEffect(live, dialogueDirector, cast) {
        if (dialogueDirector != null) live.dialogue = LiveScene.DialogueFeed(dialogueDirector,
            cast.associate { it.id to com.logie.wifecraft.dialogue.Profiles.of(it) }, com.logie.wifecraft.dialogue.PlaceView(locationName), { feed.cinematic })
        onDispose { live.dialogue = null }
    }
    var steerOpen by remember(live) { mutableStateOf(false) }
    // the era chosen from the scene menu for this scene alone (null: the place's own era, else Options' Default era)
    var sceneEra by remember(live) { mutableStateOf<String?>(null) }
    var sceneEraYear by remember(live) { mutableIntStateOf(0) }
    val active = selected?.let { id -> snapshot.people.firstOrNull { it.id == id } }
    val views = selected?.let { snapshot.furnitureViews[it] }.orEmpty()
    val viewNames = remember(views) { views.map { it.label } }
    LaunchedEffect(views.size, furnitureView) { if (furnitureView >= views.size) furnitureView = -1 }
    SideEffect { live.furnitureView = views.getOrNull(furnitureView) }

    BackHandler {
        if (joiActor != null) joiLeave = true
        else if (chatActor != null) chatLeave = true
        else if (chatSheet != null) chatMode.cancel().also { chatSheet = null }
        else if (cinematic && !build.on) cinematic = false
        else if (radial != null) radial = null
        else if (lifeWindow != null) lifeWindow = null
        else if (panel != ScreenPanel.NONE) panel = ScreenPanel.NONE
        else if (saying != null) saying = null
        else if (chatting != null) chatting = null
        else if (pairPlay != null) pairPlay = null
        else if (choosingPartner != null) choosingPartner = null
        else if (choosingAnimation != null) choosingAnimation = null
        else if (menu) menu = false
        else if (mapper.on) mapper.back()
        else if (furnitureMenu != null) furnitureMenu = null
        else if (walking) walking = false
        else if (furnitureView >= 0) furnitureView = -1
        else if (cameraMode != CameraMode.ROOM) cameraMode = CameraMode.ROOM
        else if (partnerId != null) control = control.clearPartner()
        else if (selected != null) control = control.release()
        else ending = true
    }

    fun request(other: String?, choice: AnimationChoice, options: PlayOptions): PerformanceOrder {
        // one choice's roles for the scene (its clips kept under their keys) and one play's seconds
        fun rolesOf(c: AnimationChoice): Pair<List<PlaybackRole>, Float> {
            // the roles' clips on one clock where their lengths differ (anim/PairSync: the shorter looped or held to the end)
            val animations = PairSync.synced(c.clips.map { checkNotNull(it.forPlayback()) })
            val roles = c.roles.mapIndexed { index, role ->
                val animation = animations[index].copy(loop = false)
                val key = "${c.id}:$index"
                val scale = c.roleScale
                playback[key] = lifted(animation, role.offset.z * scale)
                arousalMap?.classify(com.logie.wifecraft.bodyparts.StageHints(listOf(c.name, c.group, c.stage) + c.clips.map { it.clip.sourceName.ifEmpty { it.clip.name } },
                    c.kind, c.participantCount))?.let { stageClasses[key] = it }
                PlaybackRole(key, Vec3(role.offset.x * scale * HumanScale.PAIR, role.offset.y * scale * HumanScale.PAIR, role.offset.z * scale), role.facing)
            }
            return roles to (animations.maxOf { it.duration }.takeIf { it > 0f } ?: 12f)
        }
        val (roles, cycle) = rolesOf(choice)
        // a staged one (anim/StageRules): its start once, its loops, its end once, played as one (SceneSimulation)
        val segments = choice.segments.map { part ->
            val (partRoles, seconds) = rolesOf(part.choice)
            val kind = when (part.part) {
                StagePart.START -> SegmentKind.START
                StagePart.LOOP -> SegmentKind.LOOP
                StagePart.END -> SegmentKind.END
            }
            PlaybackSegment(kind, partRoles, seconds)
        }
        // a two-person one names its partner pair: one whose bodies need furniture (a bed, a seat, a top) is played on it,
        // fitted as the furniture's Play window fits it (SceneSimulation: placed orders); a floor one plays as before
        val pairId = if (other != null && choice.participantCount == 2) choice.id.removePrefix(PairAnim.CHOICE_PREFIX) else null
        return PerformanceOrder(other, roles, cycle, options.untilStopped, options.loops(choice.repetitions), choice.name,
            choice.sounds.map { PartnerSound(it.timeSec, it.name, it.actor) }, pairId, segments)
    }

    fun schedule(order: PendingAnimation, partner: String?) {
        val (owner, choice, mode) = order
        val options = playOptions
        if (mode == AnimationOrderMode.QUEUE || mode == AnimationOrderMode.SEQUENCE) {
            val stages = if (mode == AnimationOrderMode.SEQUENCE) animationSequence(choice, choices) else listOf(choice)
            val requests = stages.map { request(partner, it, options) }
            live.order { sim ->
                // the queue's own choices: round again when it ends, in a random order
                sim.queueOptions(owner, options.repeatQueue, options.shuffle)
                if (sim.enqueue(owner, requests)) "${requests.size} animation${if (requests.size == 1) "" else "s"} queued."
                else "Couldn’t add these animations to the queue."
            }
        } else {
            val request = request(partner, choice, options)
            live.order { sim ->
                if (sim.perform(owner, request)) null
                else sim.takeRefusal() ?: "The NPCs couldn’t reach their starting positions."
            }
        }
    }

    /** [order]'s preview with [partner] (on [item] when it is played there): nothing plays until its Play. */
    fun confirmPair(order: PendingAnimation, partner: String, item: SceneObject? = null) {
        choosingAnimation = null
        furnitureMenu = null
        pairPlay = PairPlay(PairConfirm.open(order.choice.id, order.owner, partner, order.choice.participantCount, roleMemory), order, item)
    }
    fun keepRoles(id: String, switched: Boolean) {
        if (roleMemory.set(id, switched)) try { rolePrefs.edit().putString(ROLE_KEY, roleMemory.encode()).apply() } catch (e: Exception) { }
    }
    /** [order] with [partner] at once (no preview), the two in the roles last chosen for it. */
    fun scheduleKept(order: PendingAnimation, partner: String) {
        if (RoleSwitch.switchable(order.choice.participantCount) && roleMemory.isSwitched(order.choice.id)) schedule(order.copy(owner = partner), order.owner)
        else schedule(order, partner)
    }
    /** The preview's Play: the two in the roles shown (kept for this animation), then the cinematic director as before. */
    fun playPair(p: PairPlay) {
        val c = p.confirm.play()
        keepRoles(c.choiceId, c.switched)
        lastPair = c
        pairPlay = null
        live.preview = null
        val item = p.item
        if (item != null) {
            val pairId = p.order.choice.id.removePrefix(PairAnim.CHOICE_PREFIX)
            live.order { sim ->
                if (sim.partnerOn(item, pairId, c.lead, c.other, fixedRoles = true)) null
                else sim.takeRefusal() ?: "That can’t be played on the ${item.label.lowercase()} now."
            }
        } else schedule(p.order.copy(owner = c.lead), c.other)
    }
    /** Switch positions while it plays: started again with the two in each other's roles (kept for it when it was ours). */
    fun switchPlaying(id: String, other: String) {
        live.order { sim -> if (sim.switchRoles(id)) null else sim.takeRefusal() ?: "They can switch positions once it plays." }
        lastPair?.takeIf { setOf(it.owner, it.partner) == setOf(id, other) }?.let { c ->
            val next = c.copy(switched = !roleMemory.isSwitched(c.choiceId))
            keepRoles(c.choiceId, next.switched)
            lastPair = next
        }
    }
    // the preview: the scene held with the two at their start places in their roles, the camera framing them (the room's
    // view); closed or played, the player's camera again
    val previewing = pairPlay?.takeIf { it.confirm.open }
    LaunchedEffect(live, previewing) {
        val p = previewing
        if (p == null) {
            live.preview = null
            live.camera.setMode(cameraMode)
            return@LaunchedEffect
        }
        val c = p.confirm
        val order = request(c.other, p.order.choice, PlayOptions())
        live.camera.setMode(CameraMode.ROOM)
        live.preview = StagePreview(c.lead, order, p.item)
    }

    // the GL thread drives every frame; the menus hear of changes only
    val animate by rememberUpdatedState(onAnimate)
    val sounds by rememberUpdatedState(partnerSounds)
    val fetch by rememberUpdatedState(fetchAnim)
    // the world clock as the top bar shows it: posted by the GL thread when its minute or speed changes
    var clockNow by remember(live) { mutableStateOf<ClockReading?>(null) }
    val timeOfDay by rememberUpdatedState(onTimeOfDay)
    val saveLife by rememberUpdatedState(onLifeSave)
    val intensityOut by rememberUpdatedState(onIntensity)
    DisposableEffect(live, view) {
        val main = Handler(Looper.getMainLooper())
        val ids = Array(live.count) { live.id(it) }
        val creatureOf = Array(live.count) { creatureSpecs[ids[it]] }
        val keys = Array(live.count) { actorKey(ids[it]) }
        // GL thread: the frame's time on the engine's clock; both of a pair start their clips from this one value
        var frameMs = 0
        val emoting = BooleanArray(live.count)
        // main thread: the talking gestures' files asked for once each
        val gestureFiles = HashSet<Long>()
        // GL thread: the world clock's last frame, the light's hand-over, and when the face and the sound were last posted
        val light = LightFeed()
        var clockNanos = 0L
        var clockKey = -1L
        var soundNanos = 0L
        // GL thread: each person's intensity (IntensityModel, eased), whom they look at, and when these were last looked at
        val eases = Array(live.count) { IntensityEase() }
        val sheens = Array(live.count) { SkinSheen.Ease() }   // the skin's wet sheen from the intensity (Options' Graphics)
        val goals = FloatArray(live.count) { IntensityModel.IDLE }
        // GL thread: each person's playing key (a partner stage's role), and their arousal (bodyparts/Arousal)
        val playingKeys = arrayOfNulls<String>(live.count)
        val arousal = Array(live.count) { com.logie.wifecraft.bodyparts.ArousalTracker() }
        val lookAt = arrayOfNulls<String>(live.count)
        val keyOf = ids.indices.associate { ids[it] to keys[it] }
        // the pack's props (Options → Mods → Import pack): the toys, dolls and layers of the stage that plays, drawn at its origin
        val propStore = com.logie.wifecraft.props.PropStore(java.io.File(context.filesDir, "pack/props"))
        val packOptions = com.logie.wifecraft.pack.PackOptions(java.io.File(context.filesDir, "pack/options.json"))
        val propDriver = com.logie.wifecraft.props.PropStageDriver(propStore, { kind -> packOptions.shows(kind) },
            { com.logie.wifecraft.props.PropStageDriver.DEFAULT_SCALE * com.logie.wifecraft.anim.CharacterSize.active })   // (the props grow with the bodies: Character size)
        var momentNanos = 0L
        var motionNanos = 0L
        var intensityNanos = 0L
        val sink = object : LiveScene.Sink {
            override fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float) {
                view.glCamera(eyeX, eyeY, eyeZ, atX, atY, atZ, fov)
                speechSpots.layout(eyeX, eyeY, eyeZ, atX, atY, atZ, fov)   // the bubbles follow the heads with this camera
            }

            override fun focus(near: Float, far: Float, strength: Float) {
                // Options' Graphics: Focus follows the NPC off: the renderer's own band round the camera's target
                if (Graphics.settings.value.focusFollows) view.glFocus(near, far, strength) else view.glFocus(0f, 0f, 1f)
            }

            override fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float) {
                val pose = when {
                    furniture?.action == Activity.SIT -> FurniturePose.SIT
                    furniture?.action == Activity.REST -> FurniturePose.REST
                    // a clip raised over its floor (onto a bed or seat), or lowered onto it in place of a bed (SceneSimulation's
                    // floor plays: under sz): drawn at its height, not stood on the floor
                    furniture == null && kotlin.math.abs(z - sz) > 0.01f -> FurniturePose.RAISED
                    else -> FurniturePose.FLOOR
                }
                view.glPlace(keys[index], x, y, z, facing, pose, sx, sy, sz)
                propDriver.place(index, x, y, z, facing)
                if (index == ids.size - 1) view.setProps(propDriver.frame(frameMs)) { id -> propStore.modelFile(id).takeIf { it.isFile }?.readBytes() }
                val standing = heads.getOrElse(index) { 2.05f }
                val base = if (pose == FurniturePose.RAISED) z else sz
                speechSpots.headAt(index, sx, sy, base + SpeechLayout.head(standing, pose == FurniturePose.SIT, pose == FurniturePose.REST))
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
                propDriver.play(index, clip?.let { c -> playback[c.clip]?.sourceName }, frameMs)
                val key = keys[index]
                val creature = creatureOf[index]
                // a creature's own stage (DragonStages): the creature's layer over her own animation, the person's reach on her
                val stage = clip?.clip?.let { DragonStages.ofKey(it) }
                if (creature != null) {
                    // a creature never plays a Sims clip (nothing is retargeted onto her): her own game animation, looped
                    view.glPlayAnimation(key, null)
                    playingKeys[index] = clip?.clip
                    emoting[index] = false
                    view.glAnimate(key, creature.standAnimation)
                    view.glBoneLayer(key, if (stage != null && stage.second == 1 && stage.first.creature == creature.character) stage.first.baked.layer else null)
                    return
                }
                if (stage != null && stage.second == 0) {
                    val at = creatureOf.indexOfFirst { it?.character == stage.first.creature }
                    val r = stage.first.reach
                    view.glReach(key, if (at >= 0) keys[at] else null, r.bone, r.left, stage.first.baked.reach)
                } else view.glReach(key, null, 0, false, null)
                val emote = clip?.let { PlaybackKeys.emoteOf(it.clip) }
                playingKeys[index] = clip?.clip?.takeIf { emote == null }
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
                    view.glPlayAnimation(key, clip?.let { c ->
                        playback[c.clip] ?: PreviewKeys.heldOf(c.clip)?.let { held -> playback[held]?.let(HoldFrame::of) }
                    })
                    if (wasEmote) { val start = frameMs; main.post { view.anim(key, 0, true, start) } }   // back to standing
                }
            }

            override fun bonePoints(index: Int, out: FloatArray): Boolean = view.glBonePoints(keys[index], out)

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
        view.driver = FrameDriver { now ->
            frameMs = Clock.now()
            if (clock != null) {
                // the world's time moves while the scene runs (not while it loads or ends), the light and sound follow it
                if (live.running) {
                    if (clockNanos != 0L) clock.advance((now - clockNanos) / 1e9)
                    clockNanos = now
                } else clockNanos = 0L
                light.next(clock)?.let { view.setTimeOfDay(it) }
                val key = LifeHud.clockKey(clock.simHours, clock.speed)
                if (key != clockKey) {
                    clockKey = key
                    val reading = ClockReading(clock.hourOfDay, clock.day, clock.speed)
                    main.post { clockNow = reading }
                }
                if (now - soundNanos >= LifeHud.UI_MS * 1_000_000L) {
                    soundNanos = now
                    val hour = clock.lightHour
                    val perHour = clock.realSecondsPerHour
                    main.post { timeOfDay(hour, perHour) }
                }
            }
            cineLook.frame(now)
            view.glCinematic(cineLook.mood(), cineLook.vignette(), cineLook.grain(), cineLook.letterbox())
            view.glZoneMood(cineLook.zoneMood(), cineLook.moodBoost())
            live.frame(now, sink, emit)
            // the body language (cpp engine/motionlayer.h) and the atmosphere, after the frame's placements
            val imm = feed.settings
            view.glMotionParams(imm.breathing / 100f, imm.idleMotion / 100f, imm.eyeContact)
            val step = if (motionNanos == 0L) 0f else ((now - motionNanos) / 1e9f).coerceIn(0f, 0.1f)
            motionNanos = now
            if (now - momentNanos >= MOMENT_NANOS) {
                momentNanos = now
                val fl = feed.flow
                for (i in 0 until live.count) {
                    val other = if (fl == null) null else if (ids[i] == fl.a) fl.b else if (ids[i] == fl.b) fl.a else null
                    goals[i] = if (fl != null && other != null) IntensityModel.of(live.moment(i), fl.phase, fl.tempo)
                        else IntensityModel.of(live.moment(i))
                    lookAt[i] = live.companion(i) ?: other
                }
            }
            var most = 0f
            for (i in 0 until live.count) {
                val v = eases[i].step(goals[i], step)
                if (v > most) most = v
                view.glMotion(keys[i], v, lookAt[i]?.let { keyOf[it] })
                view.glSheen(keys[i], sheens[i].step(SkinSheen.target(v, Graphics.effective), step))
            }
            // the body parts: each wearer's arousal from the stage they play and the intensity, and the partner whose entry the
            // erect shaft goes into (the engine draws nothing for someone without the parts)
            val partners = com.logie.wifecraft.bodyparts.StageKeys.partners(playingKeys)
            for (i in 0 until live.count) {
                val cls = playingKeys[i]?.let { stageClasses[it] } ?: com.logie.wifecraft.bodyparts.StageClass.NONE
                val tracker = arousal[i]
                val level = tracker.step(cls, eases[i].value, com.logie.wifecraft.bodyparts.ArousalControl.mode,
                    com.logie.wifecraft.bodyparts.ArousalControl.override(ids[i]), step)
                val p = partners[i]
                val entry = if (p >= 0 && cls.intimate) tracker.entryFor(cls) else com.logie.wifecraft.bodyparts.PartEntryKind.NONE
                view.glPartDrive(keys[i], level, if (p >= 0) keys[p] else null, entry.engine.coerceAtLeast(0), cls.rest)
            }
            val cine = feed.cinematic
            view.glParticles(if (imm.particlesOn(cine)) MOTES else 0, if (cine) 0.9f else 0.6f)
            live.camera.handheld = if (cine) imm.handheld / 100f else 0f
            if (now - intensityNanos >= LifeHud.UI_MS * 1_000_000L) {
                intensityNanos = now
                val v = most
                main.post { intensityOut(v) }
            }
        }
        onDispose {
            view.driver = null
            main.removeCallbacksAndMessages(null)
            sounds.stopAll()
            view.clearAtmosphere()
            intensityOut(null)
            // the scene's life and time as they were last (the GL thread drives them no more)
            // (the GL thread may still be finishing a frame: a failed read here loses only the last half minute, which the periodic save keeps)
            if (life != null || clock != null) runCatching { saveLife(life?.toJson(), clock?.toJson()?.toString()) }
        }
    }
    LaunchedEffect(live, spots) {
        for ((item, spot) in spots) live.command { it.setSpot(item, spot) }
        build.baseSpots = spots
        build.applySpots()
    }
    // the life and the clock kept every half minute (GL thread: where both are driven)
    LaunchedEffect(live) {
        if (life == null && clock == null) return@LaunchedEffect
        while (true) {
            delay(LIFE_SAVE_MS)
            live.command { sim -> saveLife(sim.life?.toJson(), clock?.toJson()?.toString()) }
        }
    }
    // the life HUD's numbers at most 4 a second (LifeHud.UI_MS): needs, moods, the thought bubbles, who is on screen
    var lifeShown by remember(live) { mutableStateOf<LifeView?>(null) }
    val thoughtBoard = remember(live) { ThoughtBoard(live.count) }
    var thoughts by remember(live) { mutableStateOf<List<ShownThought?>>(List(live.count) { null }) }
    var onScreen by remember(live) { mutableStateOf(List(live.count) { true }) }
    val feedSeen = remember(live) { FeedSeen() }
    var feedSeenAt by remember(live) { mutableIntStateOf(0) }
    LaunchedEffect(live) {
        if (life == null) return@LaunchedEffect
        val ids = List(live.count) { live.id(it) }
        var seeded = false
        while (true) {
            val next = snapshot.life
            if (next != lifeShown) lifeShown = next
            // what was in the feed when the scene opened is not new
            if (!seeded && next != null) { feedSeen.seeAll(next.events.map { it.hours to it.text }); feedSeenAt++; seeded = true }
            val buf = speechSpots.read()
            val vis = List(live.count) { buf[it * SpeechLayout.STRIDE + 2] > 0f }
            if (vis != onScreen) onScreen = vis
            val on = next?.enabled == true
            val now = ids.map { id -> if (on) next?.person(id)?.thought else null }
            val preferred = control.selected?.let { ids.indexOf(it) } ?: -1
            if (thoughtBoard.update(SystemClock.uptimeMillis(), now, { lines.getOrNull(it) != null }, { vis[it] }, preferred)) {
                thoughts = thoughtBoard.current()
            }
            delay(LifeHud.UI_MS)
        }
    }
    // the toast shows a moment; the answer is kept (the scene's notice says the same: the help line doesn't repeat it)
    var toastShown by remember(live) { mutableStateOf(false) }
    LaunchedEffect(lifeToast) { if (lifeToast != null) { toastShown = true; delay(LifeHud.TOAST_MS); toastShown = false } }
    LaunchedEffect(panel, lifeShown?.events) {
        val events = lifeShown?.events
        if (panel == ScreenPanel.TIME && events != null) { feedSeen.seeAll(events.map { it.hours to it.text }); feedSeenAt++ }
    }
    // the radial menu's items: what can be had where the NPC is (LifeControl.menu, answered on the GL thread)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    LaunchedEffect(radial) {
        radialAvail = null
        val r = radial ?: return@LaunchedEffect
        live.life.menu(r.actor, r.other) { list -> mainHandler.post { if (radial == r) radialAvail = list } }
    }
    // the life simulation's HUD only with Options' Show life HUD (the simulation runs underneath either way)
    val lifeOn = life != null && lifeShown?.enabled == true && immersive.lifeHud
    val lifeOf: (String) -> LifePersonView? = { id -> lifeShown?.takeIf { it.enabled }?.person(id) }
    SideEffect {
        live.running = ready && !ending
        live.selected = selected?.let { id -> cast.indexOfFirst { it.id == id } } ?: -1
    }
    LaunchedEffect(selected) {
        if (mapper.on && mapper.npc != selected) mapper.leave()
        if (selected == null) walking = false
        cameraMode = SceneHud.cameraAfterRelease(cameraMode, selected != null)
        furnitureView = -1
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
    /**
     * Furniture tapped with [id] controlled: its Play animation window, with only the animations that fit on it, grouped by
     * the posture on it (Lying, Sitting...): two-person ones with the partner (else the nearest who can join). Nothing fits
     * (or nothing is classified yet): it is used, as before.
     */
    fun openFurniture(id: String, item: SceneObject, override: Furniture? = null) {
        val f = override ?: furnitureAt[item] ?: spots[item]?.let { Furniture.of(item, it) }
        val with = partnerId?.takeIf { it != id }
        val known = needs.choices
        val byId = choices.associateBy { it.id }
        val library = choices.mapNotNull { c ->
            // (as the player classed it: Treat as)
            val r = known[c.id]?.takeIf(::placedNeed)?.let { FurnitureAvailability.applied(it, treatAs[c.id.removePrefix(PairAnim.CHOICE_PREFIX)]) }
                ?: return@mapNotNull null
            if (c.participantCount == 2 && cast.size < 2 || c.participantCount !in 1..2 || !c.playable) null
            else FurnitureAnimation(c.id, c.name, r)
        }
        val unit = SceneSimulation.HUMAN_BODY_UNIT * listOfNotNull(id, with).map { bodyScales[it] ?: 1f }.average().toFloat()
        // (a piece the player classified in Move or Build mode, or its own kind, no longer limits what it offers: what an animation
        // is marked for is only a preference, so every one is listed, those that belong on its class first)
        val mappedAs = mapper.classOf(item)
        val builtAs = build.overrideOf(item)
        val belongs = (builtAs ?: mappedAs)
        val offered = if (belongs == null) library else library.sortedBy { e ->
            val ok = if (builtAs != null) ItemOverrides.compatible(builtAs, e.requirement) else ItemOverrides.compatible(mappedAs!!, e.requirement)
            if (ok) 0 else 1
        }
        val listed = if (f == null) emptyList() else FurnitureSelection.listFor(f, offered, unit)
            .flatMap { (group, entries) -> entries.mapNotNull { e -> byId[e.animation.id]?.copy(group = group) } }
        if (listed.isEmpty()) use(id, item) else furnitureMenu = FurnitureMenu(id, item, listed)
    }
    /** [choice] played on [target]'s furniture: a two-person one with the partner (else the nearest who can join), or alone. */
    fun playOn(target: FurnitureMenu, choice: AnimationChoice, options: PlayOptions) {
        val item = target.item
        val owner = target.owner
        val refused = "That can’t be played on the ${item.label.lowercase()} now."
        if (choice.participantCount == 2) {
            val pairId = choice.id.removePrefix(PairAnim.CHOICE_PREFIX)
            val with = partnerId?.takeIf { it != owner }
            // with a partner: the preview first (who plays which role, on this piece); without one, the nearest joins as before
            if (with != null) { confirmPair(PendingAnimation(owner, choice, AnimationOrderMode.PLAY), with, item); return }
            live.order { sim -> if (sim.partnerOn(item, pairId, owner, with)) null else sim.takeRefusal() ?: refused }
        } else {
            val need = needs.choices[choice.id] ?: return
            val order = request(null, choice, options)
            live.order { sim -> if (sim.performOn(owner, item, need, order)) null else sim.takeRefusal() ?: refused }
        }
    }
    // the partner leaves the scene's cast or the cast changes: drop who is gone
    LaunchedEffect(cast) { control = control.keep(cast.map { it.id }) }

    /**
     * The radial menu (the life simulation's interactions) at [x], [y] (px; NaN: the screen's middle): held on an NPC, their
     * own menu when nobody (or they) are controlled, else the controlled NPC's to them; held on an object, the controlled
     * NPC's menu for what it gives (and its Play animation window).
     */
    fun openRadial(npc: String?, item: SceneObject?, x: Float, y: Float) {
        if (!lifeOn) return
        val sel = selected
        radial = when {
            item != null -> if (sel != null) RadialRequest(sel, null, item, x, y) else {
                lifeToast = LifeResult(false, "Choose an NPC first, then hold the ${item.label.lowercase()}.") to SystemClock.uptimeMillis()
                null
            }
            npc == null -> null
            sel == null || sel == npc -> RadialRequest(npc, null, null, x, y)
            else -> RadialRequest(sel, npc, null, x, y)
        }
    }
    fun pickRadial(r: RadialRequest, item: RadialItem) {
        radial = null
        if (item.animations) { r.item?.let { openFurniture(r.actor, it) }; return }
        val id = item.id ?: return
        live.life.interact(r.actor, r.other, id) { result -> mainHandler.post { lifeToast = result to SystemClock.uptimeMillis() } }
    }
    val onLong by rememberUpdatedState<(Hit?, Float, Float) -> Unit>({ hit, x, y ->
        when (hit?.kind) {
            Hit.Kind.ACTOR -> cast.firstOrNull { actorKey(it.id) == hit.key }?.let { openRadial(it.id, null, x, y) }
            Hit.Kind.LOCATION, Hit.Kind.PROP -> hit.key?.let(objectHits::get)?.let { openRadial(null, it, x, y) }
            else -> Unit
        }
    })
    /** The world's speed from the clock's buttons (this scene only; Options' speed is where each scene starts). GL thread. */
    fun setSpeed(speed: ClockSpeed) {
        val c = clock ?: return
        live.command { sim -> c.speed = speed; sim.life?.setSpeed(LifeHud.lifeSpeed(speed)) }
    }
    val h24 = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
    val castIndex = remember(cast) { cast.withIndex().associate { it.value.id to it.index } }
    /** What a party frame shows of [id]'s life (null: no life): the ring of needs, the gem, the mark for the controlled NPC. */
    fun frameLife(id: String): FrameLife? {
        if (!lifeOn) return null
        val mine = lifeOf(id) ?: return null
        val sel = selected
        val relation = if (sel == null || sel == id) RelationGlyph.NONE
            else LifeHud.relation(lifeOf(sel)?.relationships?.firstOrNull { it.other == id })
        return FrameLife(mine, relation, onScreen.getOrElse(castIndex[id] ?: -1) { true },
            onPlate = { lifeWindow = id }, onHold = { openRadial(id, null, Float.NaN, Float.NaN) })
    }

    val onTap by rememberUpdatedState<(Hit?) -> Unit>({ hit ->
        // immersive mode: a tap on the hidden HUD only brings it back; while it is up, every tap keeps it a while longer
        if (immersiveHud && !cinematic) {
            val wasUp = hudUp
            revealHud()
            if (!wasUp) return@rememberUpdatedState
        }
        // build mode: a tap selects what is under it and centres the orbit on it (ui/BuildModeUi.kt)
        if (build.on && !mapper.on) {
            if (ready) build.onTap(hit, objectHits) { key -> cast.firstOrNull { actorKey(it.id) == key }?.id }
            return@rememberUpdatedState
        }
        // Move mode: a tap on a piece asks what it is, on open floor holds the NPC there (ui/ItemMapperUi.kt)
        if (mapper.on) {
            if (!ready) return@rememberUpdatedState
            when (hit?.kind) {
                Hit.Kind.LOCATION, Hit.Kind.PROP -> hit.key?.let(objectHits::get)?.let { item ->
                    mapper.tapItem(item, furnitureMeasured[item] ?: spots[item]?.let { Furniture.of(item, it) }, toStage, stageRotation)
                }
                Hit.Kind.GROUND -> mapper.tapFloor(toLocal(Vec3(hit.x, hit.y, hit.z)), null, toStage, stageRotation)
                else -> Unit
            }
            return@rememberUpdatedState
        }
        // a spot made in Build mode's New tab: tapped, it is the one in use for its object and offers what plays on it
        val spotHit = hit?.takeIf { it.kind != Hit.Kind.ACTOR && ready }?.let { build.spotUnder(it.x, it.y, it.z) }
        if (spotHit != null) {
            val id = selected
            if (id != null) { build.activate(spotHit); openFurniture(id, spotHit.slot, build.spotFurniture(spotHit)) }
            else if (hit?.kind == Hit.Kind.GROUND && !walking) control = control.release()
            return@rememberUpdatedState
        }
        when (hit?.kind) {
            // nobody controlled: take control; one controlled: the tapped NPC becomes (or, tapped again, stops being) its partner
            Hit.Kind.ACTOR -> cast.firstOrNull { actorKey(it.id) == hit.key }?.let { other -> control = control.tapNpc(other.id) }
            Hit.Kind.LOCATION, Hit.Kind.PROP -> if (ready) {
                val item = hit.key?.let(objectHits::get)
                val id = selected
                // (a spot that stood in for it no longer does: the scene's own object again)
                val own = if (item != null && build.deactivate(item)) build.overlay(mapper.overridden(furnitureMeasured))[item] else null
                if (item != null && id != null) openFurniture(id, item, own)
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
        else SceneHud.orders(walking, mate != null, cast.size - 1, active.repeating, active.queued, uses, life = lifeOn,
            performing = active.performing, joi = true, chatMode = true)
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(selected) { page = 0 }
    // ---- JOI mode: the selected NPC gives a JOI to someone in the room (she believes: an NPC; the viewer only watches). The camera
    // is JoiCamera (framing choice, cuts, her bones; a dragon is circled), her words come from the dialogue engine, the stages and
    // pacing from the kink pack (JoiMode), solo animations are mixed in at random (JoiAnimationPicker). Entering it enters cinematic
    // framing; leaving puts back the cinematic state and camera that were on.
    val kink = remember(context) {
        runCatching { KinkPack.load { n -> runCatching { context.assets.open("dialogue/kink/$n").bufferedReader().use { it.readText() } }.getOrNull() } }.getOrNull()
    }
    val joiMode = remember(live, kink) {
        val paces = kink?.joiStages?.associate { it.id to it.pace }?.takeIf { it.isNotEmpty() } ?: JoiPacing.DEFAULT_PACES
        JoiMode({ b, s -> kink?.joiPlan(b, s) ?: emptyList() }, { b, s -> kink?.countdown(b, s) ?: (5 to 2000) }, paces, seed.toLong())
    }
    val joiPicker = remember(live) { JoiAnimationPicker(seed.toLong() + 1) }
    val joiSolos = remember(choices) {
        val imported = choices.filter { it.participantCount == 1 && it.playable }
            .map { JoiClip(it.id, it.name, it.group, it.preview.clip.duration.takeIf { d -> d > 0f } ?: 6f) }
        val emote = PartnerEmotes.ALL.map { JoiClip(PlaybackKeys.emote(it.anim), it.name, "Emotes", if (it.loop) 8f else 3.5f, it.loop, emote = true) }
        // the handjobs: the library's partner stages (the giving role alone) and the procedural stroke, always there
        val hands = choices.mapNotNull { runCatching { JoiHandjobs.fromChoice(it) }.getOrNull() } + JoiClip.stroke()
        JoiAnimations.catalogue(imported + emote + hands)
    }
    // (the engine is the runtime's, when a model is loaded: DialogueRuntime.engine; Options > Mods > Dialogue model downloads it)
    fun joiEngine(): DialogueEngine? = com.logie.wifecraft.dialogue.DialogueRuntime.engine?.takeIf { it.ready }
    fun startJoi(id: String) {
        val a = cast.firstOrNull { it.id == id } ?: return
        val spec = CreatureNpcs.of(a.appearance)
        joiPersona = JoiPersona(
            a.name, listOfNotNull(JoiPrompt.raceKey(a.appearance.raceId)), listOfNotNull(JoiPrompt.classKey(a.appearance.classId)),
            JoiPrompt.personalitiesFrom(a.appearance.personality?.traits.orEmpty(), spec != null), "dom", spec != null, a.appearance.personality?.bio.orEmpty(),
            female = a.appearance.sex == Sex.FEMALE)
        joiMode.defaultLevel = dialogue.dirty   // Options > Dialogue > Dirty talk level; the panel's slider is this session's own
        joiMode.enter(id, spec != null, JoiPrior(cinematic, cameraMode.name, furnitureView), joiEngine() != null)
        joiCamera.restart(); joiCamera.creature = spec != null; joiCamera.framing = joiMode.framing
        joiPicker.reset()
        live.joiCreature = spec; live.joiActor = castIndex[id] ?: -1; live.joiCamera = joiCamera
        live.joiPlace(id, spec != null)
        // a creature keeps her own game animation; her neck and head sway over it (the engine's bone layer, GL thread)
        if (spec != null) {
            val layer = AnimArrays.of(JoiDragonSway.layer(0.5f, seed.toLong()))
            view.queueEvent { view.glBoneLayer(actorKey(id), layer) }
        }
        radial = null; panel = ScreenPanel.NONE; menu = false; choosingAnimation = null; walking = false
        cinematic = true
        joiActor = id
        joiTick++
    }
    fun leaveJoi() {
        val id = joiActor ?: return
        val prior = joiMode.leave()
        live.joiCamera = null; live.joiActor = -1
        if (live.joiCreature != null || strokeBaked >= 0) view.queueEvent { view.glBoneLayer(actorKey(id), null) }
        strokeBaked = -1
        live.joiCreature = null
        live.joiRelease(id)
        cinematic = prior.cinematic
        runCatching { cameraMode = CameraMode.valueOf(prior.cameraMode) }
        furnitureView = prior.cameraView
        joiActor = null
        joiLeave = false
    }
    LaunchedEffect(joiLeave) { if (joiLeave) leaveJoi() }
    // the camera follows the framing chosen on the controls
    SideEffect { joiCamera.framing = joiMode.framing }
    // the procedural hand stroke (JoiStroke): its layer is baked ahead a few seconds at a time from the stroke's own state, and again
    // whenever the stage, what she says or a chip changes the tempo; it ends with its stage's segment and the arms come down
    fun bakeStroke() {
        val id = joiActor ?: return
        val anim = joiMode.stroke.bake()
        strokeBaked = joiMode.stroke.generation; strokeBakedAt = SystemClock.uptimeMillis()
        val arrays = AnimArrays.of(anim)
        view.queueEvent { view.glBoneLayer(actorKey(id), arrays) }
    }
    fun startStroke(stage: String, stageLeft: Float) {
        val id = joiActor ?: return
        val seconds = JoiHandjobs.runSeconds(stageLeft)
        strokeRuns++
        val r = kotlin.random.Random(seed.toLong() * 31 + strokeRuns)
        val hands = when { r.nextFloat() < 0.5f -> StrokeHands.BOTH; r.nextFloat() < 0.7f -> StrokeHands.RIGHT; else -> StrokeHands.LEFT }
        live.order { sim -> sim.stopAll(id); null }
        joiMode.stroke.start(stage, hands)
        joiMode.holdSolos(seconds + 2f)
        strokeEnds = SystemClock.uptimeMillis() + (seconds * 1000).toLong()
        bakeStroke()
    }
    LaunchedEffect(joiActor, joiMode) {
        while (true) {
            delay(100)
            val id = joiActor ?: continue
            val st = joiMode.stroke
            if (!st.running) { if (strokeBaked >= 0) { strokeBaked = -1; view.queueEvent { view.glBoneLayer(actorKey(id), null) } }; continue }
            if (st.active && SystemClock.uptimeMillis() >= strokeEnds) { st.stop(); joiMode.holdSolos(0f) }
            val stale = SystemClock.uptimeMillis() - strokeBakedAt >= ((JoiStroke.CHUNK - 1f) * 1000).toLong()
            if (st.generation != strokeBaked || stale) bakeStroke()
        }
    }
    fun playJoiSolo(energy: Float, stage: String, stageLeft: Float) {
        val id = joiActor ?: return
        if (live.joiCreature != null) return
        val clip = joiPicker.pick(joiSolos, energy, SystemClock.uptimeMillis() / 1000f, stage, joiCamera.shot?.framing ?: joiMode.framing.takeIf { it != JoiFraming.AUTO }) ?: return
        if (clip.procedural) { startStroke(stage, stageLeft); return }
        if (clip.giver >= 0) {
            // a library handjob: the giving role's own track, alone, turned as little from the camera as the unseen receiver allows
            val c = choices.firstOrNull { it.id == clip.key } ?: return
            val animations = PairSync.synced(c.clips.map { it.forPlayback() ?: return })
            val role = c.roles[clip.giver]
            val key = "${c.id}:${clip.giver}"
            playback[key] = lifted(animations[clip.giver].copy(loop = false), role.offset.z * c.roleScale)
            val turn = Math.toRadians(JoiHandjobs.turn(JoiHandjobs.ghostAngle(clip.ghostForward, clip.ghostSide)).toDouble()).toFloat()
            val cycle = animations.maxOf { it.duration }.takeIf { it > 0f } ?: 6f
            val seconds = JoiHandjobs.runSeconds(stageLeft)
            val order = PerformanceOrder(null, listOf(PlaybackRole(key, Vec3(0f, 0f, role.offset.z * c.roleScale), role.facing + turn)), cycle,
                repetitions = kotlin.math.ceil(seconds / cycle).toInt().coerceAtLeast(1), name = c.name)
            joiMode.holdSolos(cycle * kotlin.math.ceil(seconds / cycle).toInt().coerceAtLeast(1) + 2f)
            live.order { sim -> sim.perform(id, order); null }
            return
        }
        val choice = choices.firstOrNull { it.id == clip.key && it.participantCount == 1 }
        val order = if (choice != null) request(null, choice, PlayOptions())
            else PerformanceOrder(null, listOf(PlaybackRole(clip.key, Vec3(0f, 0f, 0f), 0f)), clip.seconds)
        live.order { sim -> sim.perform(id, order); null }
    }
    JoiRunner(joiMode, joiActor != null, { joiEngine() }, kink, joiPersona, { energy, stage, left -> playJoiSolo(energy, stage, left) }, { joiLeave = true }, { joiTick++ })
    // ---- Chat mode: the selected NPC talks with the player's character as with an ordinary person standing in front of her (ChatSession:
    // her persona, lore, comfort, steers and filters as for NPC-NPC talk; the words are the model's). The Chat order opens the setup sheet
    // (Race, Sex, Class, Name: remembered); Start sends the camera to the front of her (ChatCamera, a clean full-width view), Leave puts back
    // the cinematic state, camera and furniture view that were on. Others keep their day, out of frame (cinematic mode keeps them quiet).
    val chatPrefs = remember(context) { context.getSharedPreferences("wifecraft.chat", android.content.Context.MODE_PRIVATE) }
    val chatNames = remember(context) {
        ChatNames(runCatching { context.assets.open("dialogue/chat_names.json").bufferedReader().use { it.readText() } }.getOrNull())
    }
    val chatLore = remember(context) { com.logie.wifecraft.ui.DialogueAssets.lore(context) }
    val chatSolos = remember(choices) {
        val imported = choices.filter { it.participantCount == 1 && it.playable }
            .map { JoiClip(it.id, it.name, it.group, it.preview.clip.duration.takeIf { d -> d > 0f } ?: 6f) }
        val emote = PartnerEmotes.ALL.map { JoiClip(PlaybackKeys.emote(it.anim), it.name, "Emotes", if (it.loop) 8f else 3.5f, it.loop, emote = true) }
        chatMode.gestures.catalogue(imported + emote)
    }
    val chatStore = object : ChatStore {
        override fun load(): String? = try { chatPrefs.getString("last", null) } catch (e: Exception) { null }
        override fun save(text: String) { try { chatPrefs.edit().putString("last", text).apply() } catch (e: Exception) { } }
    }
    fun openChat(id: String) {
        val lore = chatLore ?: return
        val a = cast.firstOrNull { it.id == id } ?: return
        chatMode.open(id, CreatureNpcs.of(a.appearance) != null, JoiPrior(cinematic, cameraMode.name, furnitureView), ChatSetup(lore, chatNames, chatStore))
        radial = null; panel = ScreenPanel.NONE; menu = false; choosingAnimation = null; walking = false
        chatSheet = id
        chatTick++
        // the sheet is open: while the player picks who they are, her persona is read into the model (the first reply then pays for the new words only)
        runCatching { joiEngine()?.warm(com.logie.wifecraft.dialogue.ChatPrompt.CHAT_PREFIX + id, com.logie.wifecraft.dialogue.ChatPrompt.system(com.logie.wifecraft.dialogue.Profiles.of(a), lore, CreatureNpcs.of(a.appearance) != null), urgent = true) }
    }
    fun cancelChat() { chatMode.cancel(); chatSheet = null; chatTick++ }
    fun startChat() {
        val id = chatSheet ?: return
        val lore = chatLore ?: return
        val a = cast.firstOrNull { it.id == id } ?: return
        val spec = CreatureNpcs.of(a.appearance)
        val started = chatMode.start({ you ->
            ChatSession(com.logie.wifecraft.dialogue.Profiles.of(a), you, lore, spec != null, com.logie.wifecraft.dialogue.PlaceView(locationName), kink,
                kotlin.random.Random(seed.toLong() + 11),
                { r -> ChatSetup.rememberRelationship(chatStore, r) }).also { it.dirty = dialogue.dirty }
        }, joiEngine() != null)
        if (!started) return
        // the person before her is known now: her whole system prompt is read while the player types the first message
        runCatching { chatMode.session?.warmUp(joiEngine(), withContext = true) }
        chatSheet = null
        chatCamera.restart(); chatCamera.creature = spec != null
        chatMode.gestures.reset()
        live.chatCreature = spec; live.chatActor = castIndex[id] ?: -1; live.chatCamera = chatCamera
        // she turns to face the camera and stays where she stands (as JOI mode does), and is put back to idle after each gesture
        live.joiPlace(id, spec != null)
        // a creature keeps her own game animation; her neck and head sway over it (the engine's bone layer, GL thread)
        if (spec != null) {
            val layer = AnimArrays.of(JoiDragonSway.layer(0.5f, seed.toLong()))
            view.queueEvent { view.glBoneLayer(actorKey(id), layer) }
        }
        cinematic = true
        chatActor = id
        chatTick++
    }
    fun leaveChat() {
        val id = chatActor ?: return
        val prior = chatMode.leave()
        joiEngine()?.forget(ChatPrompt.CHAT_PREFIX + id)
        live.chatCamera = null; live.chatActor = -1; chatSpeech = null
        if (live.chatCreature != null) view.queueEvent { view.glBoneLayer(actorKey(id), null) }
        live.chatCreature = null
        live.joiRelease(id)
        cinematic = prior.cinematic
        runCatching { cameraMode = CameraMode.valueOf(prior.cameraMode) }
        furnitureView = prior.cameraView
        chatActor = null
        chatLeave = false
        chatTick++
    }
    LaunchedEffect(chatLeave) { if (chatLeave) leaveChat() }
    fun playChatGesture(@Suppress("UNUSED_PARAMETER") speaking: Boolean) {
        val id = chatActor ?: return
        if (live.chatCreature != null) return
        val clip = chatMode.gestures.pick(chatSolos, SystemClock.uptimeMillis() / 1000f) ?: return
        val choice = choices.firstOrNull { it.id == clip.key && it.participantCount == 1 }
        val order = if (choice != null) request(null, choice, PlayOptions())
            else PerformanceOrder(null, listOf(PlaybackRole(clip.key, Vec3(0f, 0f, 0f), 0f)), clip.seconds)
        chatMode.holdGestures(clip.seconds + 2f)
        live.order { sim -> sim.perform(id, order); null }
    }
    ChatRunner(chatMode, chatActor != null, { speaking -> playChatGesture(speaking) }, { chatTick++ })
    fun act(id: String, slot: HudSlot) {
        when (slot.order) {
            HudOrder.WALK -> {
                walking = !walking
                // as before: walking from the room's view follows the NPC (the camera button changes it any time)
                if (walking && cameraMode == CameraMode.ROOM) cameraMode = CameraMode.FOLLOW
            }
            HudOrder.PLAY -> choosingAnimation = id
            HudOrder.PARTNER -> partnerId?.let { sendToPartner(id, it) }
            HudOrder.JOI -> startJoi(id)
            HudOrder.CHAT_MODE -> openChat(id)
            HudOrder.SAY -> saying = id
            HudOrder.CHAT -> {
                val others = cast.filter { it.id != id }
                if (others.size == 1) chatWith(id, others[0].id) else chatting = id
            }
            HudOrder.STOP_REPEAT -> live.order { sim -> sim.stopRepeat(id); null }
            // Stop: the animation's end now, then they stand (the queue cleared); Skip: its end, then the queue's next
            HudOrder.STOP -> live.order { sim -> sim.stopAll(id); null }
            HudOrder.SKIP -> live.order { sim -> sim.stop(id); null }
            HudOrder.CLEAR_QUEUE -> live.order { sim -> sim.clearQueue(id); null }
            HudOrder.RELEASE -> control = control.release()
            HudOrder.USE -> SceneObject.entries.firstOrNull { it.name == slot.target }?.let { use(id, it) }
            HudOrder.FREE_WILL -> live.order { sim -> sim.freeWill(id); null }
            // the radial menu: with the partner when there is one, else their own
            HudOrder.LIFE -> openRadial(mate ?: id, null, Float.NaN, Float.NaN)
        }
    }
    fun slotEnabled(id: String, slot: HudSlot): Boolean = when (slot.order) {
        HudOrder.PLAY, HudOrder.PARTNER, HudOrder.SAY, HudOrder.CHAT, HudOrder.LIFE -> ready
        HudOrder.JOI -> ready && !build.on && joiActor == null && chatActor == null && chatSheet == null
        HudOrder.CHAT_MODE -> ready && !build.on && chatLore != null && joiActor == null && chatActor == null && chatSheet == null
        HudOrder.USE -> ready && SceneObject.entries.firstOrNull { it.name == slot.target }?.let { snapshot.available(it, id) } == true
        else -> true
    }

    // immersive mode's HUD: up after a tap for Options' time, kept while a window or menu is open
    // the first-run hint for Build mode (BuildEntry): until dismissed, or until Build mode was entered once
    val buildPrefs = remember(context) { context.getSharedPreferences(BuildEntry.PREFS, android.content.Context.MODE_PRIVATE) }
    var buildHintSeen by remember(buildPrefs) {
        mutableStateOf(try { buildPrefs.getBoolean(BuildEntry.KEY_HINT_SEEN, false) } catch (e: Exception) { false })
    }
    fun dismissBuildHint() {
        buildHintSeen = true
        try { buildPrefs.edit().putBoolean(BuildEntry.KEY_HINT_SEEN, true).apply() } catch (e: Exception) { }
    }
    // the one toast line under the top bar (ToastQueue): an answer, a refusal, a problem after loading, the first-run Build hint
    val toasts = remember(live) { ToastQueue() }
    var toastNow by remember(live) { mutableStateOf<Toast?>(null) }
    fun pushToast(text: String, danger: Boolean) {
        val at = SystemClock.uptimeMillis()
        toasts.show(text, danger, at)
        toastNow = toasts.at(at)
    }
    LaunchedEffect(lifeToast) { lifeToast?.let { (result, _) -> pushToast(result.message, !result.accepted) } }
    LaunchedEffect(status, revealed) { if (revealed && status != null) pushToast(status, true) }
    LaunchedEffect(revealed, buildHintSeen) {
        if (BuildEntry.hintShown(buildHintSeen, false, false, revealed)) { pushToast(BuildEntry.HINT, false); dismissBuildHint() }
    }
    LaunchedEffect(toastNow) {
        val t = toastNow ?: return@LaunchedEffect
        delay((if (t.danger) ToastQueue.PROBLEM_MS else ToastQueue.LIFE_MS) - (SystemClock.uptimeMillis() - t.at) + 50L)
        toastNow = toasts.at(SystemClock.uptimeMillis())
    }
    // the one way in (the HUD chip and the scene menu): cinematic mode goes on (BuildEntry.cinematicAfterEnter), then the orbit camera round what is selected
    fun enterBuild() {
        if (BuildEntry.cinematicAfterEnter(cinematic) != cinematic) cinematic = false
        menu = false
        if (mapper.on) mapper.leave()
        dismissBuildHint()
        build.enter()
        cameraMode = CameraMode.ORBIT; furnitureView = -1
        live.camera.setMode(CameraMode.ORBIT)
    }
    val windowOpen = choosingAnimation != null || furnitureMenu != null || saying != null || chatting != null ||
        choosingPartner != null || pairPlay != null || menu || ending || panel != ScreenPanel.NONE || mapper.on || build.on || chatActor != null || chatSheet != null
    // cinematic mode's idle hide: counted while cinematic, not building, no window open; any touch restarts it (see observeTouches below)
    SideEffect { cineIdleClock.mode(cinematic, build.on, windowOpen) }
    LaunchedEffect(live, cinematic, build.on, windowOpen) {
        cineIdleClock.mode(cinematic, build.on, windowOpen)
        while (true) {
            cineIdle = cineIdleClock.hidden()
            delay(100)
        }
    }
    // Build mode: no animation ends (the simulation loops what plays, auto-play stands on its shot)
    val holdAnimations = BuildEntry.holdAnimations(build.on)
    val holdNow by rememberUpdatedState(holdAnimations)
    SideEffect { live.holdAnimations = holdAnimations }
    LaunchedEffect(revealTick, windowOpen, immersive.hudSeconds, immersiveHud) {
        if (!immersiveHud) { hudUp = true; return@LaunchedEffect }
        hudReveal.holdMs = immersive.clamped().hudSeconds * 1000L
        hudReveal.hold(windowOpen, SystemClock.uptimeMillis())
        while (true) {
            val now = SystemClock.uptimeMillis()
            hudUp = windowOpen || hudReveal.alpha(now) >= 1f
            if (windowOpen || !hudUp) break
            delay((hudReveal.remaining(now) - HudReveal.FADE_MS).coerceIn(16L, 1000L))
        }
    }
    // the flow (SceneFlow): what each of its steps does in the scene
    val undone = remember(live) { HashMap<String, MutableSet<Int>>() }
    val immersiveNow by rememberUpdatedState(immersive)
    fun startStages(a: String, b: String) {
        // the author's chain from its first stage when there is one, else any two-person animation, else a partner one
        val two = choices.filter { it.participantCount == 2 }
        val first = two.firstOrNull { MomentProgress.stageOf(it.stage.ifEmpty { it.name }) == 1 } ?: two.firstOrNull()
        if (first != null) scheduleKept(PendingAnimation(a, first, AnimationOrderMode.SEQUENCE), b) else sendToPartner(a, b)
    }
    fun runFlow(steps: List<FlowStep>) {
        for (st in steps) when (st) {
            is FlowStep.LeadIn -> {
                // they come together, face each other, and share a slow emote
                val key = PlaybackKeys.emote(st.emote)
                val roles = listOf(PlaybackRole(key, Vec3(-FLOW_APART, 0f, 0f), 0f), PlaybackRole(key, Vec3(FLOW_APART, 0f, 0f), PI.toFloat()))
                live.order { sim -> if (sim.perform(st.a, PerformanceOrder(st.b, roles, FLOW_EMOTE_S, repetitions = 2))) null else sim.takeRefusal() }
            }
            is FlowStep.TakeOff -> {
                val off = undone.getOrPut(st.actor) { HashSet() }
                off += st.slot
                onUndress(st.actor, off.toSet())
                onCloth()
            }
            is FlowStep.Stages -> startStages(st.a, st.b)
            is FlowStep.Afterglow -> {
                val bed = SceneObject.BED.takeIf { it in objects }
                live.order { sim ->
                    sim.stopAll(st.a); sim.stopAll(st.b)
                    if (bed != null && sim.available(bed, st.a)) sim.use(st.a, bed)
                    null
                }
            }
            is FlowStep.Rest -> if (life != null) { live.life.interact(st.a, null, InteractionId.SLEEP); live.life.interact(st.b, null, InteractionId.SLEEP) }
            is FlowStep.Redress -> { undone.remove(st.actor); onUndress(st.actor, emptySet()) }
        }
        val f = flow
        flowPhase = f?.phase ?: FlowPhase.NONE
        feed.flow = f?.let { ImmersionFeed.FlowNow(it.a, it.b, it.phase, it.tempo) }
    }
    LaunchedEffect(flow) {
        val f = flow ?: return@LaunchedEffect
        var last = SystemClock.uptimeMillis()
        while (true) {
            delay(FLOW_TICK_MS)
            val now = SystemClock.uptimeMillis()
            // Build mode on: the flow's own timers stand still (no phase moves on; Continue still acts)
            if (holdNow) { last = now; continue }
            f.auto = immersiveNow.autoFlow
            val steps = f.tick((now - last) / 1000f)
            last = now
            if (steps.isNotEmpty()) runFlow(steps)
        }
    }
    // the stages' queue has played out: the flow hears of it (Auto flow moves on to the afterglow)
    var flowSawPlaying by remember(live) { mutableStateOf(false) }
    LaunchedEffect(snapshot, flowPhase) {
        val f = flow ?: return@LaunchedEffect
        if (holdNow) return@LaunchedEffect
        if (f.phase != FlowPhase.STAGE) { flowSawPlaying = false; return@LaunchedEffect }
        val lead = snapshot.people.firstOrNull { it.id == f.a } ?: return@LaunchedEffect
        if (lead.performing || lead.queued > 0) flowSawPlaying = true
        else if (flowSawPlaying) { flowSawPlaying = false; runFlow(f.stagesFinished()) }
    }
    /** A soft control: Slower and Faster the tempo, Closer the camera, Change position the next stage, Continue, End. */
    fun soft(c: SoftControl) {
        revealHud()
        val f = flow
        when (c) {
            SoftControl.CONTINUE -> if (f != null && f.active) runFlow(f.proceed()) else {
                val a = selected
                val b = mate
                if (a != null && b != null) {
                    val next = SceneFlow(a, b, worn(a), worn(b), keepOn(a), keepOn(b), undress = immersive.undress, auto = immersive.autoFlow)
                    flow = next
                    runFlow(next.start())
                }
            }
            SoftControl.END -> {
                val steps = f?.end().orEmpty()
                val lead = f?.a ?: selected
                if (lead != null) live.order { sim -> sim.stopAll(lead); null }
                flow = null
                runFlow(steps)
            }
            SoftControl.SLOWER -> { f?.slower(); runFlow(emptyList()) }
            SoftControl.FASTER -> { f?.faster(); runFlow(emptyList()) }
            SoftControl.CLOSER -> {
                if (selected != null && cameraMode == CameraMode.ROOM) cameraMode = CameraMode.FOLLOW
                live.camera.zoomBy(CLOSER_ZOOM)
            }
            SoftControl.POSITION -> {
                val a = f?.a ?: selected
                if (a != null) {
                    val person = snapshot.people.firstOrNull { it.id == a }
                    if (person != null && person.queued > 0) live.order { sim -> sim.stop(a); null } else choosingAnimation = a
                }
            }
        }
    }

    val reduced = reduceMotion()
    Box(Modifier.fillMaxSize().background(SceneGround).observeTouches { if (cineIdleClock.touch()) cineIdle = false }) {
        AndroidView(
            { (view.parent as? ViewGroup)?.removeView(view); view },
            Modifier.fillMaxSize().cinematicFrame(cinematic, cinematicSettings.frame).onSizeChanged { speechSpots.width = it.width.toFloat(); speechSpots.height = it.height.toFloat() }.pointerInput(view, live) {
                val slop = 8.dp.toPx()
                val velocity = FloatArray(2)
                // a drag or pinch on the room's view starts the orbit there (it begins exactly on the room's view)
                // a still finger held this long opens the radial menu on what is under it (with the life simulation)
                val longPressOn = life != null
                fun beginOrbit() {
                    val next = SceneHud.cameraForTouch(cameraMode)
                    if (next != cameraMode) { cameraMode = next; live.camera.setMode(next) }
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // build mode: a finger down on a gizmo handle drags it along its axis (not the camera)
                    build.viewSize(size.width.toFloat(), size.height.toFloat())
                    val gizmoGrab = build.on && build.grab(down.position.x, down.position.y, BuildGizmo.TOUCH_DP.dp.toPx())
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
                    var held = false
                    while (true) {
                        val wait = LONG_PRESS_MS - (SystemClock.uptimeMillis() - down.uptimeMillis)
                        val event = if (longPressOn && !build.on && !held && !twoFingers && gesture.kind == TouchGesture.Kind.PENDING && wait > 0)
                            withTimeoutOrNull(wait) { awaitPointerEvent() } else awaitPointerEvent()
                        if (event == null) {
                            held = true
                            val at = last
                            view.pick(at.x, at.y, size.width, size.height) { hit -> onLong(hit, at.x, at.y) }
                            continue
                        }
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
                            if (!twoFingers && !held) {
                                val finger = pressed.firstOrNull { it.id == down.id } ?: pressed[0]
                                last = finger.position
                                if (gesture.move(last.x, last.y, finger.uptimeMillis)) {
                                    if (gizmoGrab) build.dragTo(last.x, last.y) else {
                                        beginOrbit()
                                        live.camera.dragBy(gesture.dx, gesture.dy, size.height.toFloat())
                                    }
                                    finger.consume()
                                }
                            }
                        }
                    }
                    when {
                        gizmoGrab -> build.endDrag()
                        held -> Unit   // the long press opened the radial menu: no tap, no fling
                        gesture.up(lastTime) -> if (!(build.on && build.tapMarker(last.x, last.y, 32.dp.toPx())))
                            view.pick(last.x, last.y, size.width, size.height) { onTap(it) }
                        gesture.kind == TouchGesture.Kind.DRAG -> {
                            gesture.velocity(lastTime, velocity)
                            live.camera.release(velocity[0], velocity[1], size.height.toFloat())
                        }
                        else -> live.camera.letGo()
                    }
                }
            },
        )
        val partnerIndex = partnerId?.let { id -> cast.indexOfFirst { it.id == id } } ?: -1
        val wordsShown = !(cinematic && cinematicSettings.hideWords) && joiActor == null && chatActor == null
        if (life != null && wordsShown && lifeOn) {
            val gems = remember(lifeShown, cast) { cast.map { a -> lifeOf(a.id)?.mood?.let(LifeHud::gem) } }
            LifeMarkers(speechSpots, gems, lines, if (lifeOn) thoughts else List(cast.size) { null }, partnerIndex, icons)
        }
        if (wordsShown) SpeechBubbles(speechSpots, remember(cast) { cast.map { it.name } }, lines, partnerIndex)
        else if (chatActor != null && joiActor == null) {
            // Chat mode: her reply is the bubble over her (the chat's own words, shown whatever the cinematic mode hides of the others')
            val ci = chatActor?.let { castIndex[it] } ?: -1
            SideEffect { speechSpots.extra.fill(false); if (ci in speechSpots.extra.indices && chatSpeech != null) speechSpots.extra[ci] = true }
            SpeechBubbles(speechSpots, remember(cast) { cast.map { it.name } }, List(cast.size) { if (it == ci) chatSpeech else null }, -1)
        }
        SceneLoadingCover(!revealed, locationName, status, progress)

        // the HUD, inside the screen's safe area (cut-outs, the hinge's bars, the gesture bar)
        val shown by animateFloatAsState(if (revealed) 1f else 0f, tween(MenuMotion.duration(MenuMotion.WINDOW_IN_MS, reduced)), label = "hud")
        // cinematic mode hides it (the layer over it takes the touches; a tap brings it back a while)
        val cineHidden = cinematic && cineIdle && !build.on
        // immersive mode hides it too until a tap (HudReveal); hidden, it isn't there to be touched
        val hudHidden = cineHidden || chatActor != null || (!cinematic && immersiveHud && !hudUp)
        val hudAlpha by animateFloatAsState(if (hudHidden) 0f else 1f, tween(MenuMotion.duration(CINEMATIC_HUD_MS, reduced)), label = "cinematicHud")
        // the chrome (SceneChrome.kt): top bar, cast, camera, dock, sheet and toast, each placed by ScreenPlan (ScreenPlan.kt)
        val screenMode = when {
            chatActor != null -> ScreenMode.CHAT
            joiActor != null -> ScreenMode.JOI
            mapper.on -> ScreenMode.MOVE
            build.on -> ScreenMode.BUILD
            cinematic -> ScreenMode.CINEMATIC
            else -> ScreenMode.NORMAL
        }
        LaunchedEffect(screenMode) { panel = PanelRules.onMode() }
        val playingNow = active?.performing == true
        val playingWith = active?.takeIf { it.performing }?.partner
        val softOn = active != null && flow?.active == true && !mapper.on
        val softNow = SoftControl.offered(flowPhase)
        val dockPlan = when {
            build.on -> DockModel.build()
            joiActor != null || chatActor != null || active == null -> DockPlan(DockKind.NONE, emptyList())
            softOn -> DockModel.soft(softNow.map { it.name }, softNow.map { if (it == SoftControl.POSITION) "Position" else it.label })
            else -> DockModel.normal(slots, playingNow, playingWith != null)
        }
        val sheetPanel = when {
            panel != ScreenPanel.NONE -> panel
            mapper.on -> ScreenPanel.MOVE_PANEL
            build.on -> ScreenPanel.BUILD_PANEL
            screenMode == ScreenMode.CINEMATIC && cinematicSettings.autoplay -> ScreenPanel.AUTOPLAY
            else -> ScreenPanel.NONE
        }
        val events = lifeShown?.events
        val unseen = remember(events, feedSeenAt) { events?.let { e -> feedSeen.unseen(e.map { it.hours to it.text }) } ?: 0 }
        val hint = SceneHud.hint(active != null, mate != null, cast.size, walking)
        val toastText = lifeToast?.first?.message
        val playLine = (snapshot.notice?.takeIf { it != toastText } ?: if (immersiveHud) null else (active?.playing ?: hint.takeIf { active != null })).orEmpty()
        val contextLine = when {
            build.on || joiActor != null || chatActor != null -> ""
            active == null -> SceneHud.hint(false, false, cast.size, false).ifEmpty { "Add NPCs to the scene to direct them." }
            mate != null -> "Partner: ${names[mate] ?: ""}" + (if (playLine.isEmpty()) "" else "  ·  $playLine")
            else -> playLine
        }
        val gearControl = LocalGearControl.current
        val partControl = LocalPartControl.current
        val moreItems = ArrayList<Pair<String, () -> Unit>>()
        if (active != null && mate != null) moreItems += "Clear partner" to { control = control.clearPartner() }
        if (active != null && mate != null && !softOn) for (c in softNow) moreItems += c.label to { soft(c) }
        val partNpc = active?.id?.takeIf { partControl?.has(it) == true }
        if (partControl != null && partNpc != null) moreItems += partControl.label(partNpc) to { partControl.toggle(partNpc) }
        if (gearControl != null && active != null) {
            val gearOn = gearControl.isOn(active.id)
            moreItems += (if (gearOn) "Gear off" else "Gear on") to { gearControl.set(active.id, !gearOn) }
        }
        if (gearControl != null && cast.size > 1) {
            val allOn = gearControl.allOn()
            moreItems += (if (allOn) "All gear off" else "All gear on") to { gearControl.setAll(!allOn) }
        }
        if (!build.on) moreItems += BuildEntry.menuLabel(false) to { enterBuild() }
        if (consent != null && onLimitsFor != null && active != null) moreItems += "Limits: ${names[active.id] ?: ""}" to { onLimitsFor(active.id) }
        else if (consent != null && onLimits != null) moreItems += "Consent and limits" to { onLimits() }
        fun exitMode() {
            when (screenMode) {
                ScreenMode.CHAT -> chatLeave = true
                ScreenMode.JOI -> joiLeave = true
                ScreenMode.MOVE -> mapper.leave()
                ScreenMode.BUILD -> build.leave()
                ScreenMode.CINEMATIC -> cinematic = false
                ScreenMode.NORMAL -> Unit
            }
        }
        SceneChrome(
            mode = screenMode, panel = sheetPanel, chromeAlpha = hudAlpha * shown, place = locationName,
            face = clockNow?.takeIf { lifeOn }?.let { LifeHud.clockFace(it.hour, it.day, it.speed, h24) },
            unseen = if (lifeOn) unseen else -1,
            people = snapshot.people, names = names, selected = selected, partnerId = partnerId, plainCast = immersiveHud,
            doing = { person -> doing(person, names) }, frameLife = { id -> frameLife(id) }, onCast = { id -> control = control.tapCast(id) },
            camera = CameraChoice(cameraMode, furnitureView), canFollow = selected != null, viewNames = viewNames,
            onCamera = { cameraMode = it.mode; furnitureView = it.view }, live = live,
            dock = dockPlan,
            dockEnabled = { item -> val sid = active?.id; if (sid != null && item.order != null) slots.firstOrNull { it.key == item.id }?.let { slotEnabled(sid, it) } ?: true else true },
            onDock = { item ->
                val sid = active?.id
                if (item.id == "more") panel = PanelRules.open(panel, ScreenPanel.MORE, screenMode)
                else {
                    panel = ScreenPanel.NONE
                    if (dockPlan.kind == DockKind.SOFT) SoftControl.entries.firstOrNull { it.name == item.id }?.let { soft(it) }
                    else if (item.id == "switch") { if (sid != null && playingWith != null) switchPlaying(sid, playingWith) }
                    else if (sid != null) slots.firstOrNull { it.key == item.id }?.let { act(sid, it) }
                }
            },
            buildDock = { BuildBar(build, Modifier.fillMaxSize()) },
            context = contextLine, toast = toastNow,
            onMenu = { panel = ScreenPanel.NONE; menu = true },
            onTime = { panel = PanelRules.open(panel, ScreenPanel.TIME, screenMode) },
            onCinematic = { cinematic = !cinematic; if (cinematic) { radial = null; panel = ScreenPanel.NONE; menu = false } },
            onExitMode = { exitMode() },
            sheetTitle = when (sheetPanel) { ScreenPanel.TIME -> "Time and events"; ScreenPanel.MORE -> active?.id?.let { names[it] } ?: "More"; else -> "" },
            framed = sheetPanel == ScreenPanel.TIME || sheetPanel == ScreenPanel.MORE,
            onCloseSheet = { panel = ScreenPanel.NONE },
            onSheetRect = { autoRect = it },
        ) {
            when (sheetPanel) {
                ScreenPanel.TIME -> TimeSheet(clockNow?.takeIf { lifeOn }?.speed, { setSpeed(it) }, events.orEmpty(), icons)
                ScreenPanel.MORE -> MoreSheet(dockPlan.more, { slot -> active?.id?.let { slotEnabled(it, slot) } ?: true },
                    { slot -> panel = ScreenPanel.NONE; active?.id?.let { act(it, slot) } }, moreItems, { panel = ScreenPanel.NONE })
                ScreenPanel.BUILD_PANEL -> BuildPanel(build, active?.id?.let { npc -> { obj: SceneObject ->
                    mapper.start(npc)
                    mapper.tapItem(obj, furnitureMeasured[obj] ?: spots[obj]?.let { Furniture.of(obj, it) }, toStage, stageRotation)
                } }, Modifier.fillMaxSize())
                ScreenPanel.MOVE_PANEL -> MovePanel(mapper, view, Modifier.fillMaxSize())
                else -> Unit
            }
        }
        // cinematic auto-play: the pair's animations one after another, each change under a cut (CinematicUi, CinematicAutoplay)
        CinematicAutoplayHost(cinematic && cinematicSettings.autoplay && joiActor == null && chatActor == null, live, cineDirector, cinematicSettings,
            selected ?: flow?.a, partnerId ?: flow?.b, cast.map { it.id }, autoPairs, needs.pairs, furnitureAt, bodyScales,
            controls = cinematic && !cineHidden && revealed && joiActor == null && chatActor == null, treatAs = treatAs, hold = holdAnimations,
            consent = consent, panelRect = autoRect.takeIf { sheetPanel == ScreenPanel.AUTOPLAY })
        CinematicLayer(cinematic, cineHidden, { cineIdleClock.touch(); cineIdle = false }, { live.aspect = it; cineLook.viewAspect = it },
            frame = if (chatActor != null) com.logie.wifecraft.simulation.CinematicFrame.FULL else cinematicSettings.frame)
        // JOI mode: the subtitles in the film frame's lower third, and (the interface up) its controls
        if (joiActor != null) JoiLayer(joiMode, joiTick, cinematicSettings.frame, controls = !cineHidden && revealed,
            creature = live.joiCreature != null, onDownloadModel = { com.logie.wifecraft.data.DialogueHost.startDownload() },
            onRetry = { if (joiEngine() != null) joiMode.modelReady(); joiTick++ }, onStop = { joiLeave = true }, onChanged = { joiTick++ })
        // Chat mode: the setup sheet, and (the camera in front of her) her subtitles, the log, the topic chips and the text field
        chatSheet?.let { sid ->
            val setup = chatMode.setup
            if (setup != null) ChatSetupSheet(setup, cast.firstOrNull { it.id == sid }?.name.orEmpty(), { startChat() }, { cancelChat() })
        }
        if (chatActor != null) ChatLayer(chatMode, chatTick, { joiEngine() }, onDownloadModel = { com.logie.wifecraft.data.DialogueHost.startDownload() },
            onRetry = { if (joiEngine() != null) chatMode.modelReady(); chatTick++ }, onLeave = { chatLeave = true }, onChanged = { chatTick++ },
            camera = chatCamera, onSpeech = { chatSpeech = it })
    }
    choosingAnimation?.let { owner ->
        // with a partner chosen, the two-person animations come first and play with them (solo ones still play alone)
        val with = partnerId?.takeIf { it != owner }
        // (a creature's own stage is offered only between that creature and a person: DragonStages)
        val stagesHere = remember(owner, with) {
            stageChoices.filter { c ->
                val mine = creatureSpecs[owner]?.character
                val theirs = with?.let { creatureSpecs[it]?.character }
                with != null && ((mine == c.exclusiveTo && with !in creatureSpecs) || (theirs == c.exclusiveTo && owner !in creatureSpecs))
            }
        }
        val shownChoices = remember(choices, with, stagesHere) {
            if (with == null) choices else stagesHere + choices.sortedByDescending { it.participantCount == 2 }
        }
        fun start(choice: AnimationChoice, mode: AnimationOrderMode, preview: Boolean = true) {
            val order = PendingAnimation(owner, choice, mode)
            if (choice.participantCount == 2) {
                // with a partner: the preview before it plays (the browser's own preview plays at once, in the roles kept)
                if (with == null) choosingPartner = order else if (preview && askPairs) confirmPair(order, with) else scheduleKept(order, with)
            }
            else schedule(order, null)
        }
        AnimationBrowser(
            shownChoices, preview = null, onClose = { choosingAnimation = null },
            notice = snapshot.notice ?: with?.let { "Two-person animations play with ${names[it]}." },
            onPlay = { choice, mode ->
                if (mode == AnimationOrderMode.PLAY) choosingAnimation = null
                start(choice, mode)
            },
            onPreview = { choice -> start(choice, AnimationOrderMode.PLAY, preview = false) },
            partnerName = with?.let { names[it] },
            actorName = names[owner],
            options = playOptions,
            onOptions = { choosePlayOptions(it) },
            availability = availability,
            onTreatAs = { choice, t -> treat(choice, t) },
            treatedAs = treatAs,
            limitNote = { c -> limitNoteOf(owner, with, c) },
            askBefore = askPairs, onAskBefore = { chooseAskPairs(it) },
        )
    }
    furnitureMenu?.let { menuOf ->
        val with = partnerId?.takeIf { it != menuOf.owner }
        AnimationBrowser(
            menuOf.choices, preview = null, onClose = { furnitureMenu = null },
            notice = snapshot.notice ?: ("What fits on the ${menuOf.item.label.lowercase()}." +
                (with?.let { " Two-person animations play with ${names[it]}." } ?: "")),
            onPlay = { choice, mode ->
                furnitureMenu = null
                playOn(menuOf, choice, playOptions)
            },
            onPreview = { choice -> playOn(menuOf, choice, PlayOptions()) },
            partnerName = with?.let { names[it] },
            actorName = names[menuOf.owner],
            options = playOptions,
            onOptions = { choosePlayOptions(it) },
            onTreatAs = { choice, t -> treat(choice, t) },
            treatedAs = treatAs,
            limitNote = { c -> limitNoteOf(menuOf.owner, with, c) },
        )
    }
    // Move mode's windows: what is this (the class picker), the one-time question, the report
    MoveWindows(mapper) { cast.firstOrNull { it.id == mapper.npc }?.let(::npcInfoOf) }
    if (dialogueDirector != null) DialogueOverlay(dialogueDirector, live, names, steerOpen, { steerOpen = false })
    BuildWindows(build)
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
        val ownerLife = lifeOf(pending.owner)
        // each name with what they are to the one inviting, when the life simulation is on ("Rowan  ·  Friendly")
        val candidates = cast.filter { it.id != pending.owner }.map { person ->
            val tier = ownerLife?.relationships?.firstOrNull { it.other == person.id }?.friendTier
            person.id to if (tier.isNullOrEmpty()) person.name else "${person.name}  ·  $tier"
        }
        if (candidates.isEmpty()) {
            HudConfirm("Invite who?", "Add a second NPC to the scene to play this animation.", "OK",
                choosingPartner != null, { choosingPartner = null }, { choosingPartner = null })
        } else {
            HudPick("Invite who?", candidates, choosingPartner != null, { choosingPartner = null }) { other ->
                choosingPartner = null
                if (askPairs) confirmPair(pending, other) else { choosingAnimation = null; furnitureMenu = null; scheduleKept(pending, other) }
            }
        }
    }
    // before a two-person animation plays: who plays which role, seen in the scene; Switch positions, Play, Cancel
    val shownPair = rememberHeld(pairPlay)
    if (shownPair != null) {
        val sexes = remember(cast) { cast.associate { it.id to (it.appearance.sex == Sex.FEMALE) } }
        PairConfirmPanel(shownPair.confirm, shownPair.order.choice.name, names, sexes, pairPlay != null,
            onSwitch = { pairPlay = pairPlay?.let { it.copy(confirm = it.confirm.switch()) } },
            onPlay = { pairPlay?.let { playPair(it) } },
            onCancel = { pairPlay = null })
    }
    val selectedLife = selected?.let(lifeOf)
    SceneMenu(menu, { menu = false }, onSettings?.let { open -> { menu = false; open() } }, { menu = false; ending = true },
        freezeName = selected?.takeIf { selectedLife != null }?.let { names[it] },
        frozen = selectedLife?.frozen == true,
        onFreeze = { on -> selected?.let { live.life.freeze(it, on) } },
        onLife = selected?.takeIf { selectedLife != null }?.let { id -> { menu = false; lifeWindow = id } },
        buildOn = build.on, onBuild = { if (build.on) { menu = false; build.leave() } else enterBuild() },
        onDialogue = dialogueDirector?.let { { menu = false; steerOpen = true } },
        era = dialogueDirector?.let { (sceneEra ?: dialogue.era) to (if (sceneEra != null) sceneEraYear else dialogue.customYear) },
        onEra = dialogueDirector?.let { d -> { id: String, year: Int -> sceneEra = id; sceneEraYear = year; d.sceneEra = id; d.sceneCustomYear = year } },
        limitsLine = consent?.let { p -> ConsentEdit.summary(cast.map { p.limitsOf(it.id) }) },
        onLimits = if (consent != null) onLimits?.let { open -> { menu = false; open() } } else null)
    val windowFor = rememberHeld(lifeWindow)
    val windowPerson = windowFor?.let { id -> lifeShown?.person(id) }
    if (windowFor != null && windowPerson != null) {
        LifeWindow(windowPerson, names[windowFor] ?: "", names, icons, lifeTab, { lifeTab = it },
            onFreeze = { on -> live.life.freeze(windowFor, on) }, onClose = { lifeWindow = null }, visible = lifeWindow != null && lifeOn)
    }
    val request = radial
    val radialSpec = request?.let { r ->
        val with = r.other?.let { o -> lifeOf(r.actor)?.relationships?.firstOrNull { it.other == o } }
        val ids = if (r.other != null) InteractionId.SOCIAL_MENU else LifeHud.forObject(r.item)
        val title = if (r.other != null) "${names[r.actor] ?: ""} and ${names[r.other] ?: ""}" else names[r.actor] ?: ""
        val subtitle = when {
            r.other != null -> with?.let { "${it.friendTier} · ${it.romanceTier}" } ?: ""
            r.item != null -> "At the ${r.item.label.lowercase()}"
            else -> lifeOf(r.actor)?.wish?.let { "Wish: $it" } ?: ""
        }
        RadialMenuSpec(title, subtitle, LifeHud.items(ids, radialAvail, with, animations = r.item != null), r.x, r.y)
    }
    RadialMenu(radialSpec, icons, { item -> request?.let { pickRadial(it, item) } }, { radial = null })
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

internal fun clockNow(h24: Boolean): String {
    val c = java.util.Calendar.getInstance()
    return SceneHud.clock(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), h24)
}

/**
 * One NPC's party frame: their initial on a round plate, the name in gold over what they are doing; the controlled one in a
 * gold edge with a glow, the partner in rose with "Partner" said. The whole frame is the touch target (taps control, or let go).
 */
@Composable
internal fun CastFrame(name: String, doing: String, controlled: Boolean, partner: Boolean, onClick: () -> Unit, modifier: Modifier,
                      life: FrameLife? = null) {
    val reduced = reduceMotion()
    val ms = MenuMotion.duration(FrontMotion.GLOW_MS, reduced)
    val edge by animateColorAsState(when { controlled -> WifeTheme.gold; partner -> PartnerRose; else -> WifeTheme.trimSoft }, tween(ms), label = "edge")
    val lit by animateFloatAsState(if (controlled) 1f else 0f, tween(ms), label = "lit")
    val shape = RoundedCornerShape(WifeTheme.buttonRadius)
    Row(
        modifier
            .semantics {
                contentDescription = "$name, $doing" + (if (partner) ", partner" else "") +
                    (life?.let { l -> ", ${l.person.mood.label}" + (if (l.relation != RelationGlyph.NONE) ", ${l.relation.label}" else "") } ?: "")
            }
            .drawBehind {
                if (lit > 0f) drawRoundRect(WifeTheme.glow.copy(alpha = 0.22f * lit), cornerRadius = CornerRadius(WifeTheme.buttonRadius.toPx()))
            }
            .clip(shape)
            .background(Brush.verticalGradient(listOf(WifeTheme.slate, WifeTheme.slateDeep)))
            .border(if (controlled || partner) 2.dp else WifeTheme.trimWidth, edge, shape)
            .warPress(sound = "select", onLongPress = life?.onHold, onClick = onClick)
            .padding(horizontal = if (life != null) 4.dp else WifeTheme.gapS),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (life != null) 4.dp else WifeTheme.gapS),
    ) {
        if (life == null) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(WifeTheme.slateRaised, WifeTheme.slateDeep)))
                    .border(1.dp, edge, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), style = WifeTheme.headingStyle())
            }
        } else {
            // the plate in its ring of needs (a tap opens the Life window), the mood gem at its shoulder, the mark for the
            // controlled NPC at its foot; dimmer while the NPC is off screen
            val person = life.person
            val gem = LifeHud.gem(person.mood)
            val seen by animateFloatAsState(if (life.onScreen) 1f else 0.4f, tween(MenuMotion.duration(FrontMotion.GLOW_MS, reduced)), label = "seen")
            Box(
                Modifier.size(48.dp).semantics { contentDescription = "$name's needs and relationships" }
                    .warPress(sound = "open", onClick = life.onPlate),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.matchParentSize()) {
                    drawNeedRing(person.needs, center.x, center.y, 21.dp.toPx(), 3.dp.toPx())
                }
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(WifeTheme.slateRaised, WifeTheme.slateDeep)))
                        .border(1.dp, edge, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.take(1).uppercase(), style = WifeTheme.headingStyle())
                }
                Canvas(Modifier.matchParentSize()) {
                    drawMoodGem(gem, center.x + 17.dp.toPx(), center.y - 5.dp.toPx(), 0.6f, seen)
                    drawRelation(life.relation, center.x + 16.dp.toPx(), center.y + 16.dp.toPx(), 14.dp.toPx())
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = frizStyle(WifeTheme.body, if (controlled) Color.White else WifeTheme.gold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = life?.let { l -> (if (l.person.frozen) "Frozen · " else "") + "${l.person.mood.label} · $doing" } ?: doing
            Text(if (partner) "Partner · $line" else line, style = WifeTheme.captionStyle(if (partner) PartnerRose else WifeTheme.textDim),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What a party frame shows of an NPC's life ([CastFrame]): their [person], the controlled NPC's [relation] to them, whether on screen. */
internal class FrameLife(
    val person: LifePersonView,
    val relation: RelationGlyph,
    val onScreen: Boolean,
    val onPlate: () -> Unit,
    val onHold: () -> Unit,
)

/** A radial menu asked for: [actor] does it (with [other], or at [item]), centred at [x], [y] px (NaN: the screen's middle). */
private data class RadialRequest(val actor: String, val other: String?, val item: SceneObject?, val x: Float, val y: Float)

/** The world clock as the GL thread last posted it. */
private data class ClockReading(val hour: Double, val day: Int, val speed: ClockSpeed)

/** A line of help over the scene, on a slate pill so it reads over any room. */
@Composable
internal fun HudLine(text: String, modifier: Modifier = Modifier, centered: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape).background(WifeTheme.slate).border(WifeTheme.trimWidth, WifeTheme.trimSoft, shape)
            .padding(horizontal = WifeTheme.gapL, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(rememberHeld(text) ?: "", style = WifeTheme.bodyStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start)
    }
}

/** One action-bar slot: a slate key with the gold trim, its words; lit gold while on (Walk), glowing while pressed. */
@Composable
internal fun ActionSlot(label: String, lit: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val play = LocalSounds.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = reduceMotion()
    val glow by animateFloatAsState(
        when { !enabled -> 0f; pressed -> 1f; lit -> 0.75f; else -> 0f },
        tween(MenuMotion.duration(FrontMotion.GLOW_MS, reduced)), label = "slot",
    )
    val sink by animateFloatAsState(MenuMotion.pressScale(pressed && enabled, reduced), tween(MenuMotion.duration(MenuMotion.PRESS_MS, reduced)), label = "press")
    val gate = rememberClickGate()
    Box(
        modifier
            .height(SceneHud.SLOT_H.dp)
            .graphicsLayer { scaleX = sink; scaleY = sink }
            .semantics { if (lit) contentDescription = "$label, on" }
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
            lit || glow > 0.5f -> Color.White
            else -> WifeTheme.gold
        }
        Text(label, style = frizStyle(13.sp, color), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

internal enum class HudGlyph { GEAR, PLUS, MINUS, UP, DOWN }

/** A round slate button with a gold glyph: 40 dp drawn, 48 dp to touch. */
@Composable
internal fun HudIconButton(
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

/** The camera button: the mode's name on a slate pill with the gold trim, 48 dp to touch, a fixed width (no shift). */
@Composable
internal fun CameraModeButton(choice: CameraChoice, canFollow: Boolean, viewNames: List<String>, onClick: () -> Unit) {
    val pill = RoundedCornerShape(50)
    val lit = choice.mode != CameraMode.ROOM || choice.view >= 0
    Box(
        Modifier.width(SceneHud.CAMERA_W.dp).height(WifeTheme.touch)
            .semantics { contentDescription = SceneHud.cameraDescription(choice, canFollow, viewNames) }
            .warPress(sound = "tab", onClick = onClick)
            .padding(vertical = 4.dp)
            .clip(pill).background(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)))
            .border(if (lit) 2.dp else WifeTheme.trimWidth, if (lit) WifeTheme.gold else WifeTheme.trim, pill),
        contentAlignment = Alignment.Center,
    ) {
        Text(SceneHud.cameraLabel(choice, viewNames.getOrNull(choice.view)), style = frizStyle(13.sp, if (lit) Color.White else WifeTheme.gold), maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** The scene menu (the top bar's gear): Resume, Options when the caller opens them, End scene. Fades in and out. */
@Composable
private fun SceneMenu(
    visible: Boolean,
    onClose: () -> Unit,
    onOptions: (() -> Unit)?,
    onEnd: () -> Unit,
    /** The controlled NPC's name when life runs (their needs can be frozen and their Life window opened), else null. */
    freezeName: String? = null,
    frozen: Boolean = false,
    onFreeze: (Boolean) -> Unit = {},
    onLife: (() -> Unit)? = null,
    /** Build mode's entry (always there): enters it, or leaves it while [buildOn]. */
    buildOn: Boolean = false,
    onBuild: (() -> Unit)? = null,
    /** Opens the steer window (the dialogue layer's nudges for the talk); null: no layer. */
    onDialogue: (() -> Unit)? = null,
    /** The scene's era and its change from the menu (this scene only, from the next line); null: no dialogue layer. */
    era: Pair<String, Int>? = null,
    onEra: ((String, Int) -> Unit)? = null,
    /** The Limits line (a summary of the Consent and limits settings) and the button that re-opens that screen; null: none. */
    limitsLine: String? = null,
    onLimits: (() -> Unit)? = null,
) {
    val name = rememberHeld(freezeName)
    Overlay(onClose, visible) {
        WifeWindow("Scene", onClose, Modifier.widthIn(min = 260.dp, max = 340.dp).padding(WifeTheme.gapL)) {
            Column(verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                WifeButton("Resume", onClose, Modifier.fillMaxWidth(), kind = WifeButtonKind.PRIMARY, sound = "close")
                if (freezeName != null && name != null) {
                    if (onLife != null) WifeButton("$name's life", onLife, Modifier.fillMaxWidth(), sound = "open")
                    WifeSwitchRow("Freeze $name's needs", frozen, onFreeze, detail = "Their needs stand still")
                }
                if (onBuild != null && BuildEntry.menuShown()) {
                    WifeButton(BuildEntry.menuLabel(buildOn), onBuild, Modifier.fillMaxWidth(), sound = "open")
                    Text(BuildEntry.menuDetail(buildOn), style = WifeTheme.captionStyle())
                }
                if (onDialogue != null) WifeButton("Steer the talk", onDialogue, Modifier.fillMaxWidth(), sound = "open")
                if (era != null && onEra != null) EraPickerRow("Era", era.first, era.second, onEra)
                if (onLimits != null) {
                    WifeButton("Consent and limits", onLimits, Modifier.fillMaxWidth(), sound = "open")
                    if (limitsLine != null) Text(limitsLine, style = WifeTheme.captionStyle())
                }
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

/**
 * Before a two-person animation plays: who plays role A and role B ([confirm]'s lead and other: each one's name and sex),
 * with Switch positions (only for two roles), Play and Cancel, at the bottom so the scene above shows the two in their
 * places (LiveScene.preview). Fades in and out ([visible]).
 */
@Composable
private fun PairConfirmPanel(confirm: PairConfirm, title: String, names: Map<String, String>, female: Map<String, Boolean>,
    visible: Boolean, onSwitch: () -> Unit, onPlay: () -> Unit, onCancel: () -> Unit) {
    val reduced = reduceMotion()
    val shown by animateFloatAsState(if (visible) 1f else 0f, tween(MenuMotion.duration(MenuMotion.PAGE_MS, reduced)), label = "pairPanel")
    if (shown <= 0f) return
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(WifeTheme.gapL).graphicsLayer { alpha = shown },
        contentAlignment = Alignment.BottomCenter) {
        WifeWindow("Ready to play", onCancel, Modifier.widthIn(max = 560.dp).fillMaxWidth(), subtitle = title, footer = {
            WifeButton("Cancel", onCancel, Modifier.widthIn(min = 96.dp), sound = "close")
            if (confirm.switchable) WifeButton("Switch positions", onSwitch, Modifier.widthIn(min = 96.dp))
            WifeButton("Play", onPlay, Modifier.widthIn(min = 96.dp), kind = WifeButtonKind.PRIMARY, sound = "ok")
        }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                RoleChip("A", names[confirm.lead] ?: "", female[confirm.lead], Modifier.weight(1f))
                RoleChip("B", names[confirm.other] ?: "", female[confirm.other], Modifier.weight(1f))
            }
            Text("Shown in the scene where each starts, at the animation's first moment.", style = WifeTheme.captionStyle(),
                modifier = Modifier.padding(top = WifeTheme.gapS))
        }
    }
}

/** One role of the preview: its letter, the sex of who plays it and their name. */
@Composable
private fun RoleChip(role: String, name: String, female: Boolean?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(WifeTheme.buttonRadius)
    Row(modifier.heightIn(min = WifeTheme.touch).clip(shape).background(WifeTheme.slateRaised.copy(alpha = 0.6f))
        .border(WifeTheme.trimWidth, WifeTheme.trimSoft, shape).padding(horizontal = WifeTheme.gapM, vertical = WifeTheme.gapS)
        .semantics { contentDescription = "Role $role: $name" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (role == "A") WifeTheme.primaryTop else WifeTheme.secondaryTop)
            .border(WifeTheme.trimWidth, WifeTheme.trim, CircleShape), contentAlignment = Alignment.Center) {
            Text(role, style = WifeTheme.labelStyle(WifeTheme.gold))
        }
        if (female != null) Text(if (female) "\u2640" else "\u2642", style = WifeTheme.labelStyle(if (female) PartnerRose else MaleBlue))
        Column(Modifier.weight(1f)) {
            Text(name, style = WifeTheme.labelStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Role $role", style = WifeTheme.captionStyle(), maxLines = 1)
        }
    }
}

/** The male sign's colour on the preview's role chips. */
private val MaleBlue = Color(0xFF8AB8E8)

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
/** The scene's play preferences' key for the "ask before partner animations play" switch (default off). */
private const val ASK_PAIR_KEY = "ask_pair"
/** Touch slops two fingers' midpoint must travel before they pan (a pinch's drift never does). */
private const val PAN_SLOPS = 2f
/** A still finger held this long on the view opens the radial menu (a tap is shorter: TouchGesture's 500 ms). */
private const val LONG_PRESS_MS = 520L
/** The life and the clock are kept this often while a scene runs (and when it ends). */
private const val LIFE_SAVE_MS = 30_000L

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

/** The scene's intensity is looked at again this often (ns): the moments' goals; each frame eases toward them. */
private const val MOMENT_NANOS = 100_000_000L
/** The atmosphere's motes on screen. */
private const val MOTES = 48
/** The flow's timers' step (ms), the lead-in's emote (seconds a cycle) and how far apart they stand for it (yards). */
private const val FLOW_TICK_MS = 100L
private const val FLOW_EMOTE_S = 4f
private const val FLOW_APART = 0.45f
/** Closer: the camera's distance times this. */
private const val CLOSER_ZOOM = 0.8f

/** What the GL thread reads of immersive mode each frame: Options' settings, the flow, whether cinematic mode is on. */
private class ImmersionFeed {
    class FlowNow(val a: String, val b: String, val phase: FlowPhase, val tempo: Int)
    @Volatile var settings: ImmersiveSettings = ImmersiveSettings.DEFAULT
    @Volatile var flow: FlowNow? = null
    @Volatile var cinematic = false
}
