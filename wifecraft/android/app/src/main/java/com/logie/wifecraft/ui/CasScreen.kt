package com.logie.wifecraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.BodyRegion
import com.logie.wifecraft.creator.BodySculpt
import com.logie.wifecraft.creator.CasCamera
import com.logie.wifecraft.creator.CasHistory
import com.logie.wifecraft.creator.CasPersonality
import com.logie.wifecraft.creator.CasRandomizer
import com.logie.wifecraft.creator.CasTab
import com.logie.wifecraft.creator.CasTabs
import com.logie.wifecraft.creator.CreatorModel
import com.logie.wifecraft.creator.ResolvedLook
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.creator.Tables
import com.logie.wifecraft.creator.TransmogCatalog
import com.logie.wifecraft.creator.TransmogFrame
import com.logie.wifecraft.engine.Placement
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.engine.Thumbs
import com.logie.wifecraft.simulation.Trait
import com.logie.wifecraft.simulation.Traits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * The character creator as a Create-a-Character screen in the Warcraft look: the tabs down one side (across under her on a
 * tall screen), the character in the middle (the [SceneView] behind, placed there by the host), the tab's controls in a slate
 * panel, a top bar with Back, the name, Undo, Redo, Randomize (the locked tabs kept) and Done, and the camera's buttons. A
 * tap on her opens the part's tab (the engine's body pick) and brings the camera to it; a drag turns her, a pinch brings her
 * nearer. Underneath it is the same [CreatorModel] as before: every race, sex, option and choice, the body options, the
 * Chromie preset and custom colours, the imports, the Transmog window (the Outfit tab: [TransmogPanel] as it is).
 *
 * The logic is pure and tested (creator/CasTabs.kt, BodySculpt.kt, CasPersonality.kt, CasPresets.kt); this file only draws
 * it. Not run on a phone or a desktop: Compose can't be run here.
 */

/** The live sculpt's rate while a slider is dragged: the Eve body is fitted again at most this often (ms). */
private const val SCULPT_SEND_MS = 160L

/** The turntable's speed (degrees a second). */
private const val TURNTABLE_DPS = 14f

@Composable
fun CasScreen(
    model: CreatorModel,
    art: WowArt,
    scene: SceneView,
    tables: Tables,
    onFinish: (name: String, Appearance) -> Unit,
    onBack: () -> Unit,
    atlas: ArtAtlas? = null,
    initialName: String = "",
    actorKey: String = "creator",
    randomName: (() -> String)? = null,
    onPreviewRace: (Int) -> Unit = {},
    transmog: TransmogCatalog? = null,
    transmogStatus: String? = null,
    onLoadTransmog: () -> Unit = {},
    iconSource: GameIcons? = null,
    portraits: Thumbs? = null,
    onLook: (ResolvedLook) -> Unit = {},
    eve: CasEveBinding? = null,
) {
    val context = LocalContext.current
    val atlas2 = atlas ?: remember { ArtAtlas.open(context) }
    LaunchedEffect(atlas2) { atlas2.load() }
    val transmogArt = remember { TransmogArt.open(context) }
    val presetOutfits = remember {
        com.logie.wifecraft.characters.CreatorPresets.outfits(
            runCatching { context.assets.open("characters.json").bufferedReader().use { it.readText() } }.getOrNull())
    }
    val wardrobe = remember(transmog, presetOutfits) { transmog?.withNamed(presetOutfits) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(CasTab.BODY) }
    var locks by remember { mutableStateOf(emptySet<CasTab>()) }
    var name by remember { mutableStateOf(initialName) }
    // lookRev: the look changed (resolved and dressed again); rev: anything the panels show changed (the model isn't observable)
    var lookRev by remember { mutableIntStateOf(0) }
    var rev by remember { mutableIntStateOf(0) }
    val history = remember { CasHistory(model.appearance) }
    var camera by remember { mutableStateOf(CasCamera.FULL) }
    var turntable by remember { mutableStateOf(false) }
    var warmLight by remember { mutableStateOf(false) }
    var eveBody by remember { mutableStateOf(false) }
    var presetsOpen by remember { mutableStateOf(false) }
    var focusRegion by remember { mutableStateOf(BodyRegion.NONE) }
    var glowAt by remember { mutableStateOf<Offset?>(null) }
    val glow = remember { Animatable(0f) }
    val view = remember { ViewState() }
    // the Transmog window's unsaved changes are put back as it closes (the Outfit tab left), as the creator's always were
    var transmogBefore by remember { mutableStateOf<Appearance?>(null) }

    fun changed(look: Boolean = true) {
        history.push(model.appearance)
        if (look) lookRev++
        rev++
    }

    fun sendSculpt() {
        val s = model.appearance.sculpt ?: BodySculpt()
        scene.setSculpt(actorKey, s.toArray())
        scene.setActorScale(actorKey, s.heightScale)
    }

    fun goCamera(c: CasCamera) {
        camera = c
        view.view = null
        view.setZoom(c.zoom, c.focus)
        if (c == CasCamera.FULL) view.facing = CL.RESET_FACING
    }

    fun leaveOutfit() {
        val before = transmogBefore ?: return
        val now = model.appearance
        if (now.clothing != before.clothing || now.gear != before.gear || now.starterClothing != before.starterClothing) {
            model.setClothing(if (before.starterClothing) wardrobe?.starter ?: before.clothing else before.clothing)
            model.setGear(before.gear)
        }
        if (now.picks != before.picks && now.raceId == before.raceId && now.sex == before.sex)
            model.applyLook(com.logie.wifecraft.creator.LookPreset(before.raceId, before.sex, before.picks))
        transmogBefore = null
        changed()
    }

    fun selectTab(t: CasTab) {
        if (t == tab) return
        if (tab == CasTab.OUTFIT) leaveOutfit()
        if (t == CasTab.OUTFIT) { transmogBefore = model.appearance; onLoadTransmog() }
        tab = t
        focusRegion = BodyRegion.NONE
        goCamera(CasTabs.cameraOf(t))
    }
    BackHandler(tab == CasTab.OUTFIT) { selectTab(CasTab.BODY) }

    fun restore(a: Appearance?) {
        if (a == null) return
        model.restore(a)
        lookRev++; rev++
        sendSculpt()
    }

    val previewRace = remember(rev) { model.appearance.raceId }
    LaunchedEffect(previewRace) { onPreviewRace(previewRace) }
    LaunchedEffect(tab) { if (tab == CasTab.OUTFIT) transmogArt.load() }

    // the look, resolved where the table work is cheap to wait for; then the sculpt on the body dressed, and whether it is Eve's
    LaunchedEffect(lookRev, transmog) {
        val look = withContext(Dispatchers.Default) { runCatching { model.resolved(tables) }.getOrNull() }
        if (look != null) onLook(look)
        sendSculpt()
        delay(900)
        scene.eveStatus(actorKey) { st -> eveBody = st.startsWith("on") }
    }
    LaunchedEffect(warmLight) { scene.light(com.logie.wifecraft.data.Lights.of(if (warmLight) "dusk" else "day")) }

    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        val screenW = maxWidth
        val landscape = maxWidth >= maxHeight
        val layout = CasLayout.of(maxWidth.value, maxHeight.value, tab == CasTab.OUTFIT)
        // where she stands: the middle of the space the panels leave (the Outfit tab: the Transmog window's character column)
        val frameFit = remember(wPx, hPx, layout) {
            TransmogFrame.fit(layout.stage.w * density.density, layout.stage.h * density.density)
        }
        val centreX = if (tab == CasTab.OUTFIT) {
            val column = frameFit.box(TransmogFrame.PREVIEW)
            (layout.stage.x * density.density + column.x + column.w / 2f) / wPx
        } else (layout.stage.x + layout.stage.w / 2f) / maxWidth.value
        val centreNow by rememberUpdatedState(centreX)
        val aspect = wPx / max(1f, hPx)
        val aspectNow by rememberUpdatedState(aspect)

        // every frame: the view easing, the turntable, the scene's camera
        LaunchedEffect(Unit) {
            var last = 0L
            var lastKey = ""
            while (true) {
                withFrameNanos { t ->
                    val dt = if (last == 0L) 0f else min((t - last) / 1e9f, 0.1f)
                    last = t
                    view.update(dt)
                    if (turntable && !view.turning) view.turn(TURNTABLE_DPS * dt)
                    val key = "${view.zoom}|${view.focusY}|${view.facing}|${centreNow}|$aspectNow"
                    if (key != lastKey) {
                        lastKey = key
                        scene.frame = CasCameraLayout.pose(view.zoom, view.focusY, centreNow, aspectNow) to
                            mapOf(actorKey to Placement(0f, 0f, 0f, Math.toRadians(view.facing.toDouble()).toFloat()))
                    }
                }
            }
        }

        // the stage: a finger turns her, two bring her nearer, a tap on her opens the part's tab
        val tabNow by rememberUpdatedState(tab)
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                view.fingerDown()
                var lastX = down.position.x; var lastY = down.position.y
                var lastT = down.uptimeMillis; var lastSpan = 0f
                var travelled = 0f; var multi = false
                while (true) {
                    val e = awaitPointerEvent()
                    val live = e.changes.filter { it.pressed }
                    if (live.isEmpty() || e.changes.all { it.changedToUp() }) break
                    if (live.size >= 2) {
                        multi = true
                        val span = hypot(live[0].position.x - live[1].position.x, live[0].position.y - live[1].position.y)
                        if (lastSpan > 0f && span > 0f) view.pinch(span / lastSpan)
                        lastSpan = span; view.spin = 0f
                    } else {
                        lastSpan = 0f
                        val c = live[0]
                        val dt = max(0f, (c.uptimeMillis - lastT) / 1000f)
                        travelled += hypot(c.position.x - lastX, c.position.y - lastY)
                        view.fingerMoved(c.position.x - lastX, -(c.position.y - lastY) / (hPx / CL.SCREEN_H), dt)
                        lastX = c.position.x; lastY = c.position.y; lastT = c.uptimeMillis
                    }
                    e.changes.forEach { it.consume() }
                }
                view.fingerUp()
                // a tap (no drag, one finger): which part of her it landed on
                if (!multi && travelled < 18f * density.density && tabNow != CasTab.OUTFIT) {
                    val at = Offset(down.position.x, down.position.y)
                    scene.pickBodyRegion(actorKey, at.x, at.y, wPx.roundToInt(), hPx.roundToInt()) { code ->
                        val region = BodyRegion.of(code)
                        val t = CasTabs.tabFor(region) ?: return@pickBodyRegion
                        if (t != tab) selectTab(t)
                        focusRegion = region
                        goCamera(CasTabs.cameraFor(region))
                        glowAt = at
                        scope.launch { glow.snapTo(1f); delay(900); glow.animateTo(0f, tween(MenuMotion.duration(700, false))) }
                    }
                }
            }
        })

        // the soft ring where she was touched, fading
        glowAt?.let { p ->
            Canvas(Modifier.fillMaxSize()) {
                val a = glow.value
                if (a > 0f) {
                    val r = 46.dp.toPx()
                    drawCircle(Brush.radialGradient(listOf(WifeTheme.glow.copy(alpha = 0.35f * a), Color.Transparent), p, r * 1.6f), r * 1.6f, p)
                    drawCircle(WifeTheme.glow.copy(alpha = 0.9f * a), r, p, style = Stroke(2.dp.toPx()))
                }
            }
            if (focusRegion != BodyRegion.NONE && glow.value > 0f) {
                Box(Modifier.offset { IntOffset((p.x + 54.dp.toPx()).roundToInt(), (p.y - 16.dp.toPx()).roundToInt()) }.alpha(glow.value)) {
                    CasTag(focusRegion.label)
                }
            }
        }

        // the Outfit tab: the Transmog window as it is, over the stage and the panel's place
        if (tab == CasTab.OUTFIT) {
            Box(Modifier.offset(layout.stage.x.dp, layout.stage.y.dp).size(layout.stage.w.dp, layout.stage.h.dp)) {
                TransmogPanel(
                    wardrobe, wardrobe?.equipStarter(model.appearance)?.clothing?.takeIf { model.appearance.gear }.orEmpty(),
                    transmogStatus, onLoadTransmog,
                    onPreview = { model.setClothing(it); changed() },
                    onPreset = { outfit ->
                        model.setClothing(outfit.displays)
                        outfit.look?.let { model.applyLook(it) }
                        changed()
                    },
                    onSave = { transmogBefore = model.appearance },
                    onClose = { selectTab(CasTab.BODY) },
                    icons = iconSource,
                    art = transmogArt,
                    fit = frameFit,
                    font = art.friz,
                    outfitName = name.trim().ifEmpty { "Outfit 1" },
                    thumbs = portraits,
                    look = rev.let { model.appearance },
                )
                // Gear on or off (the creator's Gear row) at the window's foot
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) {
                    CasPill(if (model.appearance.gear) "Gear shown" else "Gear hidden", on = rev.let { model.appearance.gear }) {
                        model.setGear(!model.appearance.gear); changed()
                    }
                }
            }
        }

        // the tab rail
        Box(Modifier.offset(layout.rail.x.dp, layout.rail.y.dp).size(layout.rail.w.dp, layout.rail.h.dp)) {
            CasRail(
                selected = tab, locks = locks, vertical = landscape, atlas = atlas2, icons = iconSource,
                female = rev.let { model.appearance.sex == Sex.FEMALE },
                onSelect = { selectTab(it) },
                onLock = { t -> if (t.lockable) { locks = if (t in locks) locks - t else locks + t; rev++ } },
            )
        }

        // the camera's buttons
        if (tab != CasTab.OUTFIT) {
            Box(Modifier.offset(layout.cameras.x.dp, layout.cameras.y.dp).size(layout.cameras.w.dp, layout.cameras.h.dp)) {
                CasCameraButtons(camera, turntable, warmLight, vertical = !landscape,
                    onCamera = { goCamera(it) }, onTurntable = { turntable = !turntable }, onLight = { warmLight = !warmLight })
            }
        }

        // the panel
        if (tab != CasTab.OUTFIT) {
            Box(Modifier.offset(layout.panel.x.dp, layout.panel.y.dp).size(layout.panel.w.dp, layout.panel.h.dp)) {
                CasPanel(
                    tab = tab, model = model, rev = rev, atlas = atlas2, icons = iconSource, portraits = portraits,
                    eveBody = eveBody, focus = focusRegion,
                    onChanged = { look -> changed(look) },
                    onSculpt = { send, done ->
                        if (done) changed(look = false)
                        if (send || done) sendSculpt()
                        rev++
                    },
                    sculptRateMs = SCULPT_SEND_MS,
                    eve = eve,
                )
            }
        }

        // the top bar
        Box(Modifier.offset(layout.top.x.dp, layout.top.y.dp).size(layout.top.w.dp, layout.top.h.dp)) {
            CasTopBar(
                name = name, onName = { name = it }, randomName = randomName?.let { r -> { name = r() } },
                canUndo = rev.let { history.canUndo }, canRedo = rev.let { history.canRedo },
                onUndo = { restore(history.undo()) }, onRedo = { restore(history.redo()) },
                locked = locks.size, compact = !landscape || screenW < 760.dp,
                onRandom = {
                    val traits = Trait.entries.map { it.name }
                    CasRandomizer.randomize(model, locks, Random(System.nanoTime()), sculptable = eveBody, traits = traits,
                        opposed = { a, b -> Traits.opposed(Trait.valueOf(a), Trait.valueOf(b)) })
                    CasRandomizer.outfitPick(wardrobe?.outfits.orEmpty(), locks, Random(System.nanoTime()))?.let { model.setClothing(it.displays) }
                    changed(); sendSculpt()
                },
                onPresets = { presetsOpen = true },
                onBack = onBack,
                onDone = { if (tab == CasTab.OUTFIT) leaveOutfit(); onFinish(name, model.appearance) },
            )
        }

        if (presetsOpen) {
            CasPresetsWindow(
                current = model.appearance, name = name, named = presetOutfits,
                onLoad = { p ->
                    restore(p.appearance)
                    history.push(model.appearance)
                    if (name.isBlank() && p.characterName.isNotBlank()) name = p.characterName
                    presetsOpen = false
                },
                onClose = { presetsOpen = false },
            )
        }
    }
}

// ---- the top bar ------------------------------------------------------------------------------------------------------------

@Composable
private fun CasTopBar(
    name: String, onName: (String) -> Unit, randomName: (() -> Unit)?,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit,
    locked: Int, compact: Boolean, onRandom: () -> Unit, onPresets: () -> Unit, onBack: () -> Unit, onDone: () -> Unit,
) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WifeButton("Back", onBack, kind = WifeButtonKind.PRIMARY)
        Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp)).background(WifeTheme.slateDeep)
            .border(1.dp, WifeTheme.trimSoft, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (!compact) Text("Name", style = frizStyle(WifeTheme.label, WifeTheme.gold), modifier = Modifier.padding(end = 8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (name.isEmpty()) Text("Her name", style = frizStyle(WifeTheme.label, WifeTheme.textDim, shadow = false))
                BasicTextField(name, { onName(it.take(40)) }, singleLine = true, textStyle = frizStyle(WifeTheme.label, WifeTheme.text, shadow = false),
                    cursorBrush = SolidColor(WifeTheme.gold), modifier = Modifier.fillMaxWidth())
            }
            if (randomName != null) CasIconButton("Random name", size = 40.dp, onClick = randomName) { CasGlyph.Dice.Draw() }
        }
        CasIconButton("Undo", enabled = canUndo, repeatable = true, onClick = onUndo) { CasGlyph.Undo.Draw() }
        CasIconButton("Redo", enabled = canRedo, repeatable = true, onClick = onRedo) { CasGlyph.Redo.Draw() }
        CasIconButton("Presets", onClick = onPresets) { CasGlyph.Book.Draw() }
        WifeButton(if (compact) "⚄" else if (locked > 0) "Randomize · $locked locked" else "Randomize", onRandom)
        WifeButton("Done", onDone, kind = WifeButtonKind.PRIMARY)
    }
}

// ---- the tab rail -----------------------------------------------------------------------------------------------------------

@Composable
private fun CasRail(selected: CasTab, locks: Set<CasTab>, vertical: Boolean, atlas: ArtAtlas, icons: GameIcons?, female: Boolean,
                    onSelect: (CasTab) -> Unit, onLock: (CasTab) -> Unit) {
    val items: @Composable (Modifier) -> Unit = { m ->
        for (t in CasTabs.ORDER) CasRailTab(t, t == selected, t in locks, atlas, icons, female, m, { onSelect(t) }, { onLock(t) })
    }
    Box(Modifier.fillMaxSize().wifeSurface()) {
        if (vertical) Column(Modifier.fillMaxSize().padding(vertical = 4.dp), verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally) { items(Modifier.weight(1f)) }
        else Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) { items(Modifier.weight(1f)) }
    }
}

/** One tab: its picture in its ring (gold and glowing when chosen), its name under it, and a small lock (a long press on the tab, or the lock). */
@Composable
private fun CasRailTab(t: CasTab, on: Boolean, locked: Boolean, atlas: ArtAtlas, icons: GameIcons?, female: Boolean,
                       modifier: Modifier, onTap: () -> Unit, onLock: () -> Unit) {
    Column(modifier.warPress(sound = "tab", onLongPress = if (t.lockable) onLock else null, onClick = onTap),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(44.dp)) {
            CasTabIcon(t, on, atlas, icons, female, Modifier.size(40.dp).align(Alignment.Center))
            if (t.lockable) {
                Box(Modifier.size(22.dp).align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp).warPress(sound = "check_on", onClick = onLock),
                    contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(16.dp)) {
                        drawCircle(WifeTheme.slateDeep)
                        drawCircle(if (locked) WifeTheme.gold else WifeTheme.trimSoft, style = Stroke(1.dp.toPx()))
                        CasGlyph.drawLock(this, if (locked) WifeTheme.gold else WifeTheme.textDim.copy(alpha = 0.6f))
                    }
                }
            }
        }
        Text(if (t == CasTab.MAKEUP) "Makeup" else t.label, style = frizStyle(10.sp, if (on) WifeTheme.gold else WifeTheme.textDim, shadow = false),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CasTabIcon(t: CasTab, on: Boolean, atlas: ArtAtlas, icons: GameIcons?, female: Boolean, modifier: Modifier) {
    val ring = Modifier.clip(CircleShape).border(if (on) 2.dp else 1.dp, if (on) WifeTheme.gold else WifeTheme.trimSoft, CircleShape)
    val glowing = if (on) Modifier.background(Brush.radialGradient(listOf(WifeTheme.glow.copy(alpha = 0.35f), Color.Transparent)), CircleShape) else Modifier
    Box(modifier.then(glowing), contentAlignment = Alignment.Center) {
        val piece: String? = when (t) {
            CasTab.RACE -> sexPiece(female, on)
            CasTab.BODY -> categoryPiece("Body", female, atlas.has(ArtAtlas.FIGURE), on)
            CasTab.FACE -> "charactercreate-icon-customize-head" + if (on) "-selected" else ""
            CasTab.HAIR -> "charactercreate-icon-customize-hair" + if (on) "-selected" else ""
            CasTab.OUTFIT -> "charactercreate-icon-customize-torso" + if (on) "-selected" else ""
            CasTab.ACCESSORIES -> "charactercreate-icon-customize-accessories" + if (on) "-selected" else ""
            else -> null
        }
        val image = piece?.let { (atlas.piece("$it.category") ?: atlas.piece(it))?.image }
        when {
            image != null -> Image(image, t.label, Modifier.fillMaxSize())
            t == CasTab.MAKEUP -> Box(Modifier.fillMaxSize().then(ring).background(WifeTheme.slateDeep), contentAlignment = Alignment.Center) {
                CasPalette(atlas, on, Modifier.size(28.dp))
            }
            t == CasTab.PERSONALITY -> {
                val icon = gameIcon(icons, 236709)
                Box(Modifier.fillMaxSize().then(ring).background(WifeTheme.slateDeep), contentAlignment = Alignment.Center) {
                    if (icon != null) Image(icon, t.label, Modifier.fillMaxSize().alpha(if (on) 1f else 0.6f))
                    else Text("♥", style = frizStyle(WifeTheme.heading, if (on) WifeTheme.gold else WifeTheme.textDim))
                }
            }
            else -> Box(Modifier.fillMaxSize().then(ring).background(WifeTheme.slateDeep), contentAlignment = Alignment.Center) {
                Text(t.label.take(1), style = frizStyle(WifeTheme.heading, if (on) WifeTheme.gold else WifeTheme.textDim))
            }
        }
    }
}

/** Three of the creator's brush strokes (charactercreate-customize-palette) in red, gold and blue: the Makeup tab's picture. */
@Composable
fun CasPalette(atlas: ArtAtlas, on: Boolean, modifier: Modifier) {
    val stroke = atlas.piece("charactercreate-customize-palette")?.image
    Canvas(modifier) {
        val colours = listOf(Color(0xFFC0392B), Color(0xFFE8B04A), Color(0xFF5AA0D8))
        val h = size.height / 4.2f
        colours.forEachIndexed { i, c ->
            val tint = if (on) c else Color(0xFF8A8A8A)
            val y = size.height * 0.18f + i * h * 1.15f
            if (stroke != null) drawImage(stroke, dstOffset = IntOffset(0, y.roundToInt()), dstSize = androidx.compose.ui.unit.IntSize(size.width.roundToInt(), h.roundToInt()),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint, androidx.compose.ui.graphics.BlendMode.Modulate))
            else drawRoundRect(tint, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
        }
    }
}

// ---- the camera's buttons -----------------------------------------------------------------------------------------------------

@Composable
private fun CasCameraButtons(camera: CasCamera, turntable: Boolean, warm: Boolean, vertical: Boolean,
                             onCamera: (CasCamera) -> Unit, onTurntable: () -> Unit, onLight: () -> Unit) {
    val content: @Composable () -> Unit = {
        for (c in CasCamera.entries) CasRoundButton(c.label.substringBefore(' '), camera == c, { onCamera(c) }) { CasGlyph.camera(c).Draw() }
        CasRoundButton("Turn", turntable, onTurntable) { CasGlyph.Turn.Draw() }
        CasRoundButton("Light", warm, onLight) { CasGlyph.Sun.Draw() }
    }
    if (vertical) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) { content() }
    else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun CasRoundButton(label: String, on: Boolean, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Column(Modifier.width(54.dp).warPress(sound = "click", onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(42.dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(WifeTheme.secondaryTop, WifeTheme.slateDeep)))
            .border(if (on) 2.dp else 1.dp, if (on) WifeTheme.gold else Color(0xFF5B5E66), CircleShape),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp)) { CasGlyphTint(if (on) WifeTheme.gold else WifeTheme.textDim) { glyph() } }
        }
        Text(label, style = frizStyle(10.sp, if (on) WifeTheme.gold else WifeTheme.textDim, shadow = false), maxLines = 1)
    }
}

// ---- small pieces -------------------------------------------------------------------------------------------------------------

/** A square button with a drawn glyph, 48 dp to touch. */
@Composable
fun CasIconButton(description: String, enabled: Boolean = true, repeatable: Boolean = false, size: Dp = 48.dp, onClick: () -> Unit,
                  glyph: @Composable () -> Unit) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp))
        .background(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)))
        .border(1.dp, if (enabled) WifeTheme.trim.copy(alpha = 0.75f) else WifeTheme.trimSoft, RoundedCornerShape(10.dp))
        .warPress(enabled = enabled, repeatable = repeatable, onClick = onClick)
        .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 0.5f)) { CasGlyphTint(WifeTheme.gold) { glyph() } }
    }
}

/** A rounded on/off pill (the Outfit tab's Gear). */
@Composable
fun CasPill(text: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.height(40.dp).clip(shape).background(if (on) Color(0xFF2D2A20) else WifeTheme.slateRaised)
        .border(1.dp, if (on) WifeTheme.gold else WifeTheme.trimSoft, shape).warPress(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center) {
        Text((if (on) "✓ " else "") + text, style = frizStyle(WifeTheme.body, if (on) WifeTheme.gold else WifeTheme.textDim, shadow = false), maxLines = 1)
    }
}

/** The name of the part touched, by the ring. */
@Composable
private fun CasTag(text: String) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape).background(WifeTheme.slateDeep).border(1.dp, WifeTheme.trim, shape).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, style = frizStyle(WifeTheme.body, WifeTheme.gold), maxLines = 1)
    }
}
