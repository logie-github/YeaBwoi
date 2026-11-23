package com.logie.wifecraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.simulation.CameraMode
import com.logie.wifecraft.simulation.LiveScene
import com.logie.wifecraft.simulation.SceneCamera
import com.logie.wifecraft.simulation.SceneSnapshot
import kotlinx.coroutines.delay

// The live scene's chrome, drawn from ScreenPlan (ScreenPlan.kt): the top bar (menu, the mode chip, the place and clock, Cinematic),
// the cast, the camera's buttons, the dock, the one line over it, the sheet and the toast. Nothing here computes a rectangle:
// each piece is put where the plan says. SceneSessionScreen owns the state and passes what is shown and what a tap does.

/** The words of the mode chip, the way out of a mode. */
internal fun modeLabel(mode: ScreenMode): String = when (mode) {
    ScreenMode.BUILD -> "Build"
    ScreenMode.MOVE -> "Move"
    ScreenMode.JOI -> "JOI"
    ScreenMode.CHAT -> "Chat"
    ScreenMode.CINEMATIC -> "Cinematic"
    ScreenMode.NORMAL -> ""
}

/** Puts [content] where [r] says (dp, screen coordinates), exactly its size; nothing when [r] is null. */
@Composable
internal fun At(r: ScreenRect?, content: @Composable BoxScope.() -> Unit) {
    if (r != null) Box(Modifier.offset(r.l.dp, r.t.dp).size(r.w.dp, r.h.dp), content = content)
}

/**
 * The chrome over the scene. [mode] and [panel] are what is on; [chromeAlpha] fades everything but the way out of a mode. The dock
 * is [dock] (Build's own is [buildDock]); [context] the line over it. [sheet] draws the open panel's body ([framed]: in a titled
 * frame with a close button; Build's and Move's panels bring their own). [onSheetRect] reports where the sheet is (auto-play's panel).
 */
@Composable
internal fun SceneChrome(
    mode: ScreenMode,
    panel: ScreenPanel,
    chromeAlpha: Float,
    place: String,
    face: ClockFace?,
    unseen: Int,
    people: List<SceneSnapshot.PersonView>,
    names: Map<String, String>,
    selected: String?,
    partnerId: String?,
    plainCast: Boolean,
    doing: (SceneSnapshot.PersonView) -> String,
    frameLife: (String) -> FrameLife?,
    onCast: (String) -> Unit,
    camera: CameraChoice,
    canFollow: Boolean,
    viewNames: List<String>,
    onCamera: (CameraChoice) -> Unit,
    live: LiveScene,
    dock: DockPlan,
    dockEnabled: (DockItem) -> Boolean,
    onDock: (DockItem) -> Unit,
    buildDock: @Composable () -> Unit,
    row: Boolean,
    onPlan: (List<ScreenPlaced>) -> Unit,
    context: String,
    toast: Toast?,
    onMenu: () -> Unit,
    onTime: () -> Unit,
    onCinematic: () -> Unit,
    onExitMode: () -> Unit,
    sheetTitle: String,
    framed: Boolean,
    onCloseSheet: () -> Unit,
    onSheetRect: (ScreenRect?) -> Unit,
    sheet: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        val ins = rememberScreenInsets()
        val orbit = camera.mode != CameraMode.ROOM
        val state = ScreenState(mode, panel, dock.size, dock.minWidth, people.size, orbit, toast != null, context.isNotEmpty() || mode == ScreenMode.CHAT, ins.ime > 0f, row)
        val plan = remember(w, h, ins, state) { ScreenPlan.plan(w, h, ins, state) }
        val sheetRect = ScreenPlan.find(plan, "sheet")
        SideEffect { onSheetRect(sheetRect); onPlan(plan) }
        val chip = mode == ScreenMode.BUILD || mode == ScreenMode.MOVE || mode == ScreenMode.JOI || mode == ScreenMode.CHAT
        val shown = mode == ScreenMode.NORMAL || mode == ScreenMode.BUILD || mode == ScreenMode.MOVE || mode == ScreenMode.CINEMATIC
        // the way out of a mode stays whatever the rest does
        if (chip) At(ScreenPlan.find(plan, "mode")) { ModeChip(modeLabel(mode), onExitMode) }
        if (chromeAlpha > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = chromeAlpha }) {
            // ---- the top bar
            if (mode != ScreenMode.JOI && mode != ScreenMode.CHAT) At(ScreenPlan.find(plan, "menu")) { HudIconButton(HudGlyph.GEAR, onMenu, "Menu") }
            if (mode == ScreenMode.CINEMATIC) At(ScreenPlan.find(plan, "mode")) { ModeChip(modeLabel(mode), onExitMode) }
            val placeRect = ScreenPlan.find(plan, "place") ?: ScreenPlan.find(plan, "place.clock") ?: ScreenPlan.find(plan, "place.compact")
            val placeKind = if (ScreenPlan.find(plan, "place") != null) 2 else if (ScreenPlan.find(plan, "place.clock") != null) 1 else 0
            if (shown) At(placeRect) { PlacePill(place, face, placeKind, unseen, onTime) }
            At(ScreenPlan.find(plan, "cinematic")) { CinematicEntry(onCinematic) }
            // ---- the cast
            At(ScreenPlan.find(plan, "cast")) {
                CastList(people, names, selected, partnerId, plainCast, doing, frameLife, onCast, vertical = ScreenPlan.wide(w, h))
            }
            // ---- the camera
            At(ScreenPlan.find(plan, "camera")) {
                CameraModeButton(camera, canFollow, viewNames) { onCamera(SceneHud.nextCamera(camera, canFollow, viewNames.size)) }
            }
            At(ScreenPlan.find(plan, "camera.keys")) { CameraKeys(live) }
            // ---- the dock and the line over it
            At(ScreenPlan.find(plan, "dock")) {
                when (dock.kind) {
                    DockKind.BUILD -> buildDock()
                    DockKind.CHAT -> Unit      // Chat's layer draws its own field where the plan puts the dock
                    else -> DockRow(dock, dockEnabled, onDock)
                }
            }
            if (mode != ScreenMode.CHAT) At(ScreenPlan.find(plan, "context")) { HudLine(context, Modifier.fillMaxWidth().height(ScreenPlan.CONTEXT_H.dp), centered = true) }
            // ---- the sheet and the toast
            At(sheetRect) { if (framed) SheetFrame(sheetTitle, onCloseSheet, sheet) else sheet() }
            At(ScreenPlan.find(plan, "toast")) { ToastLine(toast) }
        }
    }
}

/** The way out of the mode that is on: a slate pill with a cross, "Build", "JOI", "Chat"...; 48 dp high. */
@Composable
private fun ModeChip(label: String, onClick: () -> Unit) {
    val pill = RoundedCornerShape(50)
    Row(
        Modifier.fillMaxSize().semantics { contentDescription = "Leave $label" }.warPress(sound = "close", onClick = onClick)
            .padding(vertical = 4.dp).clip(pill).background(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)))
            .border(2.dp, WifeTheme.gold, pill).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Text("×  $label", style = frizStyle(13.sp, Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The Cinematic button: its words at 96 dp, a small film glyph at 48. */
@Composable
private fun CinematicEntry(onClick: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 96.dp) CinematicButton(false, onClick)
        else Box(
            Modifier.fillMaxSize().semantics { contentDescription = "Cinematic mode" }.warPress(sound = "tab", onClick = onClick).padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = CornerRadius(8.dp.toPx())
                drawRoundRect(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)), cornerRadius = r)
                drawRoundRect(WifeTheme.trim, cornerRadius = r, style = Stroke(1.dp.toPx()))
                val c = center
                val k = size.minDimension * 0.2f
                drawRoundRect(WifeTheme.gold, Offset(c.x - k * 1.3f, c.y - k), Size(k * 2.6f, k * 2f), CornerRadius(2.dp.toPx()), style = Stroke(1.6.dp.toPx()))
                drawLine(WifeTheme.gold, Offset(c.x - k * 1.3f, c.y - k * 1.5f), Offset(c.x + k * 1.3f, c.y - k * 1.5f), 2.dp.toPx())
            }
        }
    }
}

/**
 * The place and the world's clock in a slate pill, a tap on it opens the time sheet (speeds and what happened lately). [kind] 2: the
 * place's name and the clock, 1: the clock, 0: the time only; a gold dot while there is something new in the feed.
 */
@Composable
private fun PlacePill(place: String, face: ClockFace?, kind: Int, unseen: Int, onClick: () -> Unit) {
    val context = LocalContext.current
    val h24 = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }
    var time by remember { mutableStateOf(clockNow(h24)) }
    LaunchedEffect(h24, face != null) { while (face == null) { time = clockNow(h24); delay(15_000) } }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.fillMaxSize().padding(vertical = 2.dp).clip(shape).background(WifeTheme.slate).border(WifeTheme.trimWidth, WifeTheme.trimSoft, shape)
            .semantics { contentDescription = (face?.description ?: "$place, $time") + (if (unseen > 0) ", $unseen new events" else "") }
            .warPress(sound = "tab", onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (kind == 2) Text(place, Modifier.weight(1f, fill = false), style = WifeTheme.headingStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (face != null && kind >= 1) WorldClockFace(face)
        else Text(face?.time ?: time, Modifier.weight(1f, fill = false), style = WifeTheme.bodyStyle(WifeTheme.text), maxLines = 1, textAlign = TextAlign.Center)
        if (unseen > 0) Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(WifeTheme.glow))
    }
}

/** The cast: a strip along the top (portrait) or a column down the left (landscape), a party frame each. */
@Composable
private fun CastList(
    people: List<SceneSnapshot.PersonView>, names: Map<String, String>, selected: String?, partnerId: String?, plain: Boolean,
    doing: (SceneSnapshot.PersonView) -> String, frameLife: (String) -> FrameLife?, onCast: (String) -> Unit, vertical: Boolean,
) {
    val frame = Modifier.width(ScreenPlan.CAST_W.dp).height(ScreenPlan.CAST_H.dp)
    if (vertical) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (person in people) key(person.id) {
                CastFrame(names[person.id] ?: "", if (plain) "" else doing(person), person.id == selected, person.id == partnerId,
                    { onCast(person.id) }, frame, frameLife(person.id))
            }
        }
    } else {
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (person in people) key(person.id) {
                CastFrame(names[person.id] ?: "", if (plain) "" else doing(person), person.id == selected, person.id == partnerId,
                    { onCast(person.id) }, frame, frameLife(person.id))
            }
        }
    }
}

/** The camera's up, down and zoom keys (held, they repeat); shown while the view orbits. */
@Composable
private fun CameraKeys(live: LiveScene) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(SceneHud.CAMERA_GAP.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(SceneHud.CAMERA_GAP.dp)) {
            HudIconButton(HudGlyph.UP, { live.camera.liftBy(SceneCamera.LIFT_STEP) }, "Camera up", hold = true)
            HudIconButton(HudGlyph.DOWN, { live.camera.liftBy(-SceneCamera.LIFT_STEP) }, "Camera down", hold = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(SceneHud.CAMERA_GAP.dp)) {
            HudIconButton(HudGlyph.PLUS, { live.camera.zoomBy(1.25f) }, "Zoom in", hold = true)
            HudIconButton(HudGlyph.MINUS, { live.camera.zoomBy(0.8f) }, "Zoom out", hold = true)
        }
    }
}

/** The dock's targets in a row, each as wide as the row shares out (none wider than 140 dp). */
@Composable
private fun DockRow(dock: DockPlan, enabled: (DockItem) -> Boolean, onItem: (DockItem) -> Unit) {
    if (DockModel.scrolls(dock.kind)) {
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ScreenPlan.GAP.dp)) {
            for (item in dock.items) key(item.id) { ActionSlot(item.label, item.lit, enabled(item), { onItem(item) }, Modifier.width(item.minW.dp).fillMaxHeight()) }
        }
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(ScreenPlan.GAP.dp, Alignment.CenterHorizontally)) {
        for (item in dock.items) key(item.id) {
            ActionSlot(item.label, item.lit, enabled(item), { onItem(item) }, Modifier.weight(1f, fill = true).widthIn(max = 140.dp))
        }
    }
}

/** The sheet's frame: a slate panel with its title and a close button over [body]. */
@Composable
private fun SheetFrame(title: String, onClose: () -> Unit, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().wifeSurface().padding(start = WifeTheme.gapM, end = 4.dp, bottom = WifeTheme.gapS)) {
        Row(Modifier.fillMaxWidth().height(WifeTheme.touch), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = WifeTheme.headingStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            WifeCloseButton(onClose)
        }
        Column(Modifier.weight(1f).padding(end = WifeTheme.gapS).verticalScroll(rememberScrollState())) { body() }
    }
}

/** The one line under the bar: an answer (green), a refusal or a problem (red), the first-run hint. Fades with the toast. */
@Composable
private fun ToastLine(toast: Toast?) {
    val t = toast ?: return
    val shape = RoundedCornerShape(50)
    val edge = if (t.danger) WifeTheme.danger.copy(alpha = 0.8f) else WifeTheme.good.copy(alpha = 0.8f)
    Box(
        Modifier.fillMaxSize().clip(shape).background(WifeTheme.slate).border(1.5.dp, edge, shape).padding(horizontal = WifeTheme.gapL)
            .semantics { contentDescription = (if (t.danger) "Problem: " else "") + t.text },
        contentAlignment = Alignment.Center,
    ) {
        Text(t.text, style = WifeTheme.bodyStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * The More sheet: every order of the NPC that is not in the dock (Say, Chat with, Release, Use <object>, Free will, Clear queue...)
 * and the extras ([extras]: Clear partner, the soft controls, Gear, Part, Build mode, Consent and limits), one 48 dp row each.
 */
@Composable
internal fun MoreSheet(
    more: List<HudSlot>, enabled: (HudSlot) -> Boolean, onSlot: (HudSlot) -> Unit,
    extras: List<Pair<String, () -> Unit>>, onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ScreenPlan.GAP.dp)) {
        for (slot in more) key(slot.key) { WifeButton(slot.label, { onSlot(slot) }, Modifier.fillMaxWidth(), enabled = enabled(slot)) }
        for ((label, run) in extras) key(label) { WifeButton(label, { run(); onDone() }, Modifier.fillMaxWidth()) }
    }
}
