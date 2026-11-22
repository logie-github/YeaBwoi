package com.logie.wifecraft.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.simulation.AutoClip
import com.logie.wifecraft.simulation.AutoOption
import com.logie.wifecraft.simulation.AutoPhase
import com.logie.wifecraft.simulation.AutoPlace
import com.logie.wifecraft.simulation.AutoStep
import com.logie.wifecraft.simulation.AutoplayPlanner
import com.logie.wifecraft.simulation.CinematicAutoplay
import com.logie.wifecraft.simulation.CinematicDirector
import com.logie.wifecraft.simulation.CinematicFrame
import com.logie.wifecraft.simulation.CutStyle
import com.logie.wifecraft.simulation.FilmFrame
import com.logie.wifecraft.simulation.Furniture
import com.logie.wifecraft.simulation.FurnitureAnimation
import com.logie.wifecraft.simulation.FurnitureAvailability
import com.logie.wifecraft.simulation.TreatAs
import com.logie.wifecraft.simulation.FurnitureKind
import com.logie.wifecraft.simulation.FurnitureSpec
import com.logie.wifecraft.simulation.LiveScene
import com.logie.wifecraft.simulation.PartnerPair
import com.logie.wifecraft.simulation.SceneObject
import com.logie.wifecraft.simulation.SceneSimulation
import com.logie.wifecraft.story.Vec3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.simulation.CinematicSettings
import com.logie.wifecraft.simulation.CinematicStyle
import com.logie.wifecraft.simulation.ShotStyle

/** How long the interface stays after the last touch in cinematic mode (Build mode off) before it hides (ms): CinematicIdle.DEFAULT_MS. */
const val CINEMATIC_PEEK_MS = 5_000L
/** The HUD's fade out and in as cinematic mode hides and shows it (ms). */
const val CINEMATIC_HUD_MS = 400

/**
 * Cinematic mode's layer over the live scene: the Cinematic button (beside the camera button, in its look; lit while the mode
 * runs, dimmed while the HUD is hidden: pressing it ends the mode) and, while the HUD is hidden, a sheet over the view that
 * takes every touch (the black bars round the frame included): a tap brings the HUD back for a while ([onPeek]); no drag turns
 * the camera under the director. Reports the 3D view's width over height ([onAspect]) for the director's framing and the film
 * bars: its [frame]'s in the mode ([FilmFrame.fit]), the screen's otherwise.
 */
@Composable
fun CinematicLayer(on: Boolean, hudHidden: Boolean, shown: Boolean, onToggle: () -> Unit, onPeek: () -> Unit,
                   onAspect: (Float) -> Unit, modifier: Modifier = Modifier, frame: CinematicFrame = CinematicFrame.FULL,
                   /** dp from the end to the button's right edge in the top bar (null: beside the camera's buttons, as before). */
                   topBarEnd: Float? = null,
                   /** The Build button directly under the Cinematic button (same look and size): null hides it; [buildOn] lights it. */
                   onBuild: (() -> Unit)? = null, buildOn: Boolean = false) {
    var screen by remember { mutableStateOf(IntSize.Zero) }
    val aspectOut by rememberUpdatedState(onAspect)
    LaunchedEffect(screen, on, frame) {
        if (screen.height > 0) aspectOut(FilmFrame.fit(screen.width, screen.height, if (on) frame.aspect else 0f).aspect)
    }
    Box(modifier.fillMaxSize().onSizeChanged { screen = it }) {
        if (hudHidden) {
            // the interface is hidden: this sheet takes every touch (the touch that shows it again is no tap in the scene)
            Box(Modifier.fillMaxSize().semantics { contentDescription = "Cinematic view: touch to show the controls" }
                .pointerInput(Unit) { detectTapGestures(onPress = { onPeek(); tryAwaitRelease() }) })
        }
        // hidden, the button goes with the rest of the interface
        if (shown && !hudHidden) {
            val buttonAlpha = 1f
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val hud = SceneHud.layout(maxWidth.value, maxHeight.value)
                // in the top bar's kept room (it covers no other control), else beside the camera's buttons
                val place = if (topBarEnd != null) Modifier.padding(end = topBarEnd.dp, top = ((SceneHud.TOP_H - 48f) / 2f).coerceAtLeast(0f).dp)
                    else Modifier.padding(end = (SceneHud.MARGIN + SceneHud.CAMERA_W + 2 * SceneHud.CAMERA_GAP).dp, top = SceneHud.cameraTop(hud).dp)
                CinematicButton(on, onToggle, Modifier.align(Alignment.TopEnd).graphicsLayer { alpha = buttonAlpha }.then(place))
                // Build, stacked right under it (the same column, the same end), lit while Build mode is on
                if (onBuild != null && topBarEnd != null) {
                    BuildChipButton(buildOn, onBuild, Modifier.align(Alignment.TopEnd)
                        .padding(end = topBarEnd.dp, top = (BuildEntry.buttonTop(SceneHud.TOP_H)).dp))
                }
            }
        }
    }
}

/**
 * Sees every touch on this container's area (any finger down or moving) without taking it: [onTouch] is called for each event
 * with a finger down, in the initial pass, so the children still get it. Used for cinematic mode's idle count ([CinematicIdle]).
 */
fun Modifier.observeTouches(onTouch: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.pressed || it.changedToDown() }) onTouch()
        }
    }
}

/**
 * The 3D view in cinematic mode's frame: measured at the largest rectangle of [frame]'s shape that fits ([FilmFrame.fit]),
 * centred, with black round it; off ([on] false), the whole space as before. The view's own size changes with it (its picture,
 * projection, effects and taps are the frame's: the bars are outside it and take no tap).
 */
fun Modifier.cinematicFrame(on: Boolean, frame: CinematicFrame): Modifier = this
    .drawBehind { if (on) drawRect(Color.Black) }
    .layout { measurable, constraints ->
        val w = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val h = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val r = if (on && w > 0 && h > 0) FilmFrame.fit(w, h, frame.aspect) else FilmFrame.Rect(0, 0, w, h)
        val placeable = measurable.measure(Constraints.fixed(r.width.coerceAtLeast(0), r.height.coerceAtLeast(0)))
        layout(w, h) { placeable.place(r.x, r.y) }
    }

/**
 * Cinematic auto-play over the live scene ([CinematicAutoplay]): while [on], [lead] and [partner] (else the first other of
 * [castIds]) play the installed partner animations ([pairs], what each needs: [needs]) one after another on the places of
 * [furniture] and the open floor where they fit, each change hidden under a cut: the picture to black (this layer), the next one
 * started with both on their places at once (SceneSimulation.autoplay, GL thread), the camera cut to a new shot of it
 * ([CinematicDirector.cutNow]), then shown again. With [controls] (the HUD up), its panel: what it plays from, Next scene,
 * Pause, Stay on this animation and the pace.
 */
@Composable
fun CinematicAutoplayHost(
    on: Boolean, live: LiveScene, director: CinematicDirector, settings: CinematicSettings,
    lead: String?, partner: String?, castIds: List<String>,
    pairs: List<PartnerPair>, needs: Map<String, Requirement>, furniture: Map<SceneObject, Furniture>,
    bodyScales: Map<String, Float>, controls: Boolean, modifier: Modifier = Modifier,
    /** The player's "Treat as" per pair id (each played as that, not as read). */
    treatAs: Map<String, TreatAs> = emptyMap(),
    /** Build mode is on: the sequence stands on its current shot ([CinematicAutoplay.hold]; nothing changes until it is left). */
    hold: Boolean = false,
) {
    val auto = remember(live) { CinematicAutoplay() }
    var cover by remember(live) { mutableFloatStateOf(0f) }
    var pace by remember(live) { mutableIntStateOf(settings.pace) }
    LaunchedEffect(settings.pace) { pace = settings.pace }
    var paused by remember(live) { mutableStateOf(false) }
    var stay by remember(live) { mutableStateOf(false) }
    var playing by remember(live) { mutableStateOf<String?>(null) }
    var notice by remember(live) { mutableStateOf<String?>(null) }
    val a = lead ?: castIds.firstOrNull()
    val b = partner?.takeIf { it != a } ?: castIds.firstOrNull { it != a }
    val floorZ = furniture.values.firstOrNull()?.z ?: 0f
    // every installed two-person animation on every place it fits these two bodies (off the main thread)
    val options by produceState(emptyList<AutoOption>(), pairs, needs, furniture, a, b, treatAs) {
        value = withContext(Dispatchers.Default) {
            val places = furniture.entries.sortedBy { it.key.ordinal }.map { (item, f) -> AutoPlace(item.name, item, f) } +
                AutoPlace("floor", null, Furniture("floor", FurnitureSpec.standard(FurnitureKind.FLOOR), 0f, 0f, floorZ, 0f))
            // each as the player classed it; one needing furniture none here takes may play on the floor where, lowered, it rests
            val clips = pairs.mapNotNull { p -> needs[p.id]?.takeIf { it.actors.size == 2 }?.let { r ->
                val t = treatAs[p.id]
                val need = FurnitureAvailability.applied(r, t)
                val rests = t == TreatAs.FLOOR || (t == null && FurnitureAvailability.placed(r) && r.surface !in Requirement.FLOOR_LEVEL &&
                    FurnitureAvailability.floorRests(r, runCatching { FurnitureAnimation.floorDrop(p) }.getOrDefault(0f)))
                AutoClip(p.id, p.name, need, rests)
            } }
            val unit = SceneSimulation.HUMAN_BODY_UNIT * listOfNotNull(a, b).map { bodyScales[it] ?: 1f }.ifEmpty { listOf(1f) }.average().toFloat()
            AutoplayPlanner.options(places, clips, unit)
        }
    }
    auto.options = options
    auto.installed = pairs.size
    auto.people = if (a != null && b != null) 2 else 1
    auto.pace = pace
    auto.cutStyle = settings.cutStyle
    auto.showMovement = settings.showMovement
    auto.paused = paused
    auto.hold = hold
    auto.stay = stay
    LaunchedEffect(live, on, a, b) {
        val one: String? = a
        val two: String? = b
        if (!on || one == null) {
            if (auto.phase != AutoPhase.OFF && one != null && two != null) {
                val x: String = one; val y: String = two
                live.command { sim -> sim.stopRepeat(x); sim.stopRepeat(y) }
            }
            auto.stop(); cover = 0f; playing = null
            return@LaunchedEffect
        }
        val first: String = one
        val swapResult = AtomicReference<Boolean?>(null)
        val arrived = AtomicReference<Boolean?>(null)
        val starts = AtomicReference<List<Vec3>>(emptyList())
        live.command { sim -> starts.set(listOfNotNull(sim.position(first), two?.let { sim.position(it) })) }
        val pieces = furniture.values.toList()
        fun handle(steps: List<AutoStep>) {
            for (s in steps) when (s) {
                is AutoStep.Swap -> {
                    val beat = s.beat
                    val tele = s.teleport
                    val other: String = two ?: continue
                    swapResult.set(null)
                    live.command { sim ->
                        val spot = if (beat.place?.item == null) AutoplayPlanner.floorSpot(starts.get(), pieces, floorZ) else null
                        swapResult.set(sim.autoplay(first, other, beat.clip?.id, beat.place?.item, tele, spot))
                    }
                }
                is AutoStep.Cut -> director.cutNow(s.resetSide)
            }
        }
        auto.stop()
        handle(auto.start())
        var last = 0L
        var polled = 0f
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            handle(auto.tick(dt))
            swapResult.getAndSet(null)?.let { handle(auto.swapped(it)) }
            if (auto.phase == AutoPhase.WALK) {
                polled += dt
                if (polled >= 0.25f && two != null) {
                    polled = 0f
                    live.command { sim -> if (sim.autoplayStarted(first)) arrived.set(true) }
                }
                if (arrived.getAndSet(null) == true) handle(auto.arrived())
            }
            cover = auto.cover
            playing = auto.beat?.let { it.clip?.name ?: "A partner animation" }
            notice = auto.notice.text
        }
    }
    Box(modifier.fillMaxSize()) {
        if (on) Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover }.background(Color.Black))
        if (on && controls) {
            CinematicAutoplayPanel(playing, notice, paused, stay, pace, { auto.next() }, { paused = !paused }, { stay = !stay },
                { pace = it }, Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = WifeTheme.gapL, bottom = WifeTheme.gapL))
        }
    }
}

/** Auto-play's controls while the HUD is up: what plays (or why the base ones do), Next scene, Pause, Stay, the pace. */
@Composable
fun CinematicAutoplayPanel(playing: String?, notice: String?, paused: Boolean, stay: Boolean, pace: Int,
                           onNext: () -> Unit, onPause: () -> Unit, onStay: () -> Unit, onPace: (Int) -> Unit,
                           modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 340.dp).wifeSurface().padding(WifeTheme.gapM), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
        Text(playing ?: "Choosing a scene", style = WifeTheme.labelStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (notice != null) Text(notice, style = WifeTheme.captionStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
            WifeButton("Next scene", onNext)
            WifeButton(if (paused) "Resume" else "Pause", onPause)
            WifeButton(if (stay) "Move on" else "Stay", onStay)
        }
        WifeSliderRow("Pace", pace, onPace, valueText = when { pace < 34 -> "Slow"; pace < 67 -> "Steady"; else -> "Quick" })
    }
}

/** The Cinematic button: a slate pill with the gold trim like the camera button, lit gold while the mode runs. */
@Composable
fun CinematicButton(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pill = RoundedCornerShape(50)
    Box(
        modifier.width(CINEMATIC_BUTTON_W.dp).height(WifeTheme.touch)
            .semantics { contentDescription = if (on) "Cinematic mode on: end it" else "Cinematic mode" }
            .warPress(sound = "tab", onClick = onClick)
            .padding(vertical = 4.dp)
            .clip(pill).background(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)))
            .border(if (on) 2.dp else WifeTheme.trimWidth, if (on) WifeTheme.gold else WifeTheme.trim, pill),
        contentAlignment = Alignment.Center,
    ) {
        Text("Cinematic", style = frizStyle(13.sp, if (on) Color.White else WifeTheme.gold), maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** The Build button: the Cinematic button's pill, lit gold while Build mode is on ("Building"), "Build" otherwise. */
@Composable
fun BuildChipButton(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pill = RoundedCornerShape(50)
    Box(
        modifier.width(CINEMATIC_BUTTON_W.dp).height(WifeTheme.touch)
            .semantics { contentDescription = if (on) "Build mode on: leave it" else "Build mode" }
            .warPress(sound = "tab", onClick = onClick)
            .padding(vertical = 4.dp)
            .clip(pill).background(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)))
            .border(if (on) 2.dp else WifeTheme.trimWidth, if (on) WifeTheme.gold else WifeTheme.trim, pill),
        contentAlignment = Alignment.Center,
    ) {
        Text(BuildEntry.chipLabel(on), style = frizStyle(13.sp, if (on) Color.White else WifeTheme.gold), maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

private const val CINEMATIC_BUTTON_W = 96f

/**
 * Options' Cinematic page: the lighting's strength, the camera's style and speed, each shot's length, the letterbox bars,
 * film grain, and names and bubbles hidden while it runs. Every change applies at once (a running scene included).
 */
@Composable
fun CinematicPage(settings: CinematicSettings, onChange: (CinematicSettings) -> Unit) {
    WifeSectionHeader("Lighting")
    WifeSliderRow("Mood lighting", settings.lighting, { onChange(settings.copy(lighting = it)) })
    WifeNote("A warm, low light over the time of day's: less ambient light, a warm key from the side, a soft rim from behind, a slow candle-like flicker, a gentle grade and darker corners.")
    WifeSliderRow("Zone mood", settings.zoneMood, { onChange(settings.copy(zoneMood = it)) })
    WifeSwitchRow("Mood boost", settings.moodBoost, { onChange(settings.copy(moodBoost = it)) },
        detail = "Exaggerates the zone's colours: more saturation and contrast, for a stylised look.")
    WifeNote("The zone's own light from the game: Tirisfal's mauve and teal gloom over Brill, Teldrassil's violet, Durotar's dusty red. Its ambient, sun, sky and fog colour the place, its fog comes closer, and the grade takes its colours; candles and hearths stay the warm part.")
    WifeSectionHeader("Camera")
    val styles = CinematicStyle.entries
    WifeTabs(styles.map { it.label }, styles.indexOf(settings.style).coerceAtLeast(0), { onChange(settings.copy(style = styles[it])) })
    WifeSliderRow("Camera speed", settings.motion, { onChange(settings.copy(motion = it)) }, valueText = "${settings.motion}%",
        range = CinematicSettings.MOTION_MIN..CinematicSettings.MOTION_MAX)
    Column(verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        WifeSliderRow("Shortest shot", settings.shortest, { v -> onChange(settings.copy(shortest = v, longest = maxOf(v, settings.longest))) },
            valueText = "${settings.shortest} s", range = CinematicSettings.SHOT_MIN..CinematicSettings.SHOT_MAX)
        WifeSliderRow("Longest shot", settings.longest, { v -> onChange(settings.copy(longest = v, shortest = minOf(v, settings.shortest))) },
            valueText = "${settings.longest} s", range = CinematicSettings.SHOT_MIN..CinematicSettings.SHOT_MAX)
    }
    WifeNote("The camera moves round the bed or the people from shot to shot: orbits, low and high angles, close-ups, over a shoulder, wide shots, the bed's own views, slow dollies. Cuts joins them without the move.")
    WifeSectionHeader("Shots during animations")
    val shotStyles = ShotStyle.entries
    WifeTabs(shotStyles.map { it.label }, shotStyles.indexOf(settings.shotStyle).coerceAtLeast(0), { onChange(settings.copy(shotStyle = shotStyles[it])) })
    WifeSliderRow("Randomness", settings.randomness, { onChange(settings.copy(randomness = it)) }, valueText = "${settings.randomness}%")
    WifeSwitchRow("Camera cuts", settings.allowCuts, { onChange(settings.copy(allowCuts = it)) },
        detail = if (settings.allowCuts) "Cuts allowed between shots" else "Smooth moves only")
    WifeNote("While an animation plays, the shots follow its position: a wide shot to set the scene, then medium, close-up and detail shots chosen for it, the camera kept on one side of the pair. Randomness loosens that order and lets each shot drift.")
    WifeSectionHeader("Auto-play")
    WifeSwitchRow("Auto-play scene", settings.autoplay, { onChange(settings.copy(autoplay = it)) },
        detail = if (settings.autoplay) "The pair plays one animation after another" else "Only what you choose plays")
    val cuts = CutStyle.entries
    WifeTabs(cuts.map { it.label }, cuts.indexOf(settings.cutStyle).coerceAtLeast(0), { onChange(settings.copy(cutStyle = cuts[it])) })
    WifeSliderRow("Pace", settings.pace, { onChange(settings.copy(pace = it)) },
        valueText = AutoplayPlanner.dwellRange(settings.pace).let { "${it.start.toInt()}-${it.endInclusive.toInt()} s" })
    WifeSwitchRow("Show movement", settings.showMovement, { onChange(settings.copy(showMovement = it)) },
        detail = if (settings.showMovement) "They walk to the next place in view" else "A move to another place happens during the cut")
    WifeNote("Once cinematic mode starts, the two play the installed partner animations on the bed, the couch, a chair, the table or the floor, wherever their bodies fit, each for a while. Every change happens during a cut to a new camera angle, so the switch is never seen.")
    WifeSectionHeader("Picture")
    val frames = CinematicFrame.entries
    WifeTabs(frames.map { it.label }, frames.indexOf(settings.frame).coerceAtLeast(0), { onChange(settings.copy(frame = frames[it])) })
    WifeSwitchRow("Letterbox", settings.letterbox, { onChange(settings.copy(letterbox = it)) }, detail = "Thin black bars above and below the picture")
    WifeSwitchRow("Film grain", settings.grain, { onChange(settings.copy(grain = it)) })
    WifeSwitchRow("Hide names and bubbles", settings.hideWords, { onChange(settings.copy(hideWords = it)) },
        detail = "Speech and thought bubbles hidden while the mode runs")
}
