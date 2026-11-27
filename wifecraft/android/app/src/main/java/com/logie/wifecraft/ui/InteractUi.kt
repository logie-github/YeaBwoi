package com.logie.wifecraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.creator.StockingColours
import com.logie.wifecraft.creator.StockingSpec
import com.logie.wifecraft.creator.StockingStyle
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.simulation.ConsentPolicy
import com.logie.wifecraft.simulation.Expression
import com.logie.wifecraft.simulation.FabricGauge
import com.logie.wifecraft.simulation.FabricState
import com.logie.wifecraft.simulation.FabricStatus
import com.logie.wifecraft.simulation.PinchGesture
import com.logie.wifecraft.simulation.StockOptions
import com.logie.wifecraft.simulation.InteractAction
import com.logie.wifecraft.simulation.InteractCamera
import com.logie.wifecraft.simulation.InteractMode
import com.logie.wifecraft.simulation.InteractPersona
import com.logie.wifecraft.simulation.InteractTab
import com.logie.wifecraft.simulation.InteractTool
import com.logie.wifecraft.simulation.InteractView
import com.logie.wifecraft.simulation.LookTarget
import com.logie.wifecraft.simulation.OutfitAct
import com.logie.wifecraft.simulation.PoseKind
import com.logie.wifecraft.simulation.Reaction
import com.logie.wifecraft.simulation.RegionMap
import com.logie.wifecraft.simulation.StrokeGate
import com.logie.wifecraft.simulation.TouchHit
import com.logie.wifecraft.simulation.TouchKind
import com.logie.wifecraft.simulation.Verdict
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

// Interact mode's screen pieces (InteractMode, simulation/InteractMode.kt, is the logic; SceneSessionScreen owns the state and plays what these report):
// the touch layer over the picture (a tap on her body, a stroke along it, a drag on the background to orbit, a pinch to zoom, with a ripple where
// the finger is), and the body of the commands sheet (the views, Commands, Pose, Outfit). Pure drawing and gestures: nothing here decides what she does.

private class Ripple(val x: Float, val y: Float, val at: Long, val tone: Int)

/**
 * The pinch on her stockings, as the touch layer reaches the screen: [worn] whether she has stockings on, [verdict] the consent gate (a pull is a
 * [TouchKind.PULL]; a refusal is played by the screen and the grab never starts), [onGrab] and [onEnd] the pull's start and end. [state] is the
 * solver's state polled by the screen; [at] where the finger is while it carries fabric (the gauge's arc is drawn there).
 */
class PinchHost {
    var worn: () -> Boolean = { false }
    var verdict: (TouchHit) -> Verdict = { Verdict.ALLOW }
    var onGrab: () -> Unit = {}
    var onEnd: () -> Unit = {}
    var state by mutableStateOf(FabricState.REST)
    var at by mutableStateOf<Offset?>(null)
}

/** The layer over the scene while Interact mode runs. [onTouch] answers a tap on her body (null: no reaction), [onStroke] each step of a stroke. */
@Composable
internal fun InteractTouchLayer(
    mode: InteractMode, tick: Int, camera: InteractCamera, view: SceneView, actorKey: String,
    policy: ConsentPolicy?, persona: () -> InteractPersona,
    onTouch: (TouchHit, TouchKind) -> Reaction?,
    onStrokeStep: (StrokeGate.Step) -> Unit,
    onBackgroundTap: (Float, Float, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    pinch: PinchHost? = null,
) {
    @Suppress("UNUSED_VARIABLE") val redraw = tick
    val ripples = remember { mutableStateListOf<Ripple>() }
    var clock by remember { mutableLongStateOf(0L) }
    val rnd = remember { Random(17) }
    LaunchedEffect(ripples.size > 0) {
        while (ripples.isNotEmpty()) {
            withFrameNanos { clock = it / 1_000_000L }
            ripples.removeAll { clock - it.at > 800L }
        }
    }
    fun ripple(x: Float, y: Float, tone: Int) { ripples.add(Ripple(x, y, android.os.SystemClock.uptimeMillis().also { clock = it }, tone)); if (ripples.size > 12) ripples.removeAt(0) }
    val pinchGesture = remember(view, actorKey) { PinchGesture() }
    Box(modifier.fillMaxSize().pointerInput(view, actorKey) {
        val slop = 8.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val w = size.width
            val h = size.height
            var last = down.position
            var moved = false
            var multi = false
            var span = 0f
            // what the finger went down on: 0 not known yet, 1 her body, 2 the background
            var target = 0
            var gate: StrokeGate? = null
            var nextSample = 0L
            // the pinch on her stockings: a hold on a part that can wear them (a quick swipe stays a stroke), then the fabric follows the finger
            var downHit: TouchHit? = null
            var holdDone = pinch == null || mode.tool != InteractTool.TOUCH
            var pinchUsed = false
            fun applyStep(step: PinchGesture.Step) {
                val host = pinch ?: return
                when (step) {
                    is PinchGesture.Step.Probe -> view.stockProbe(actorKey, step.x, step.y, w, h) { c -> applyStep(pinchGesture.covered(c)) }
                    is PinchGesture.Step.Ask -> applyStep(pinchGesture.verdict(host.verdict(step.hit)))
                    is PinchGesture.Step.Grab -> view.stockGrab(actorKey, step.x, step.y, w, h) { ok ->
                        val next = pinchGesture.grabbed(ok)
                        if (pinchGesture.held) { host.onGrab(); host.at = Offset(step.x, step.y) }
                        applyStep(next)
                    }
                    is PinchGesture.Step.Refused -> ripple(down.position.x, down.position.y, 2)
                    is PinchGesture.Step.Drag -> { view.stockDrag(actorKey, step.x, step.y, w, h); host.at = Offset(step.x, step.y) }
                    PinchGesture.Step.Release -> { view.stockRelease(actorKey); host.at = null; host.onEnd() }
                    PinchGesture.Step.NotPull, PinchGesture.Step.None -> Unit
                }
            }
            if (mode.tool == InteractTool.TOUCH) {
                view.pickBodyDetail(actorKey, down.position.x, down.position.y, w, h) { d -> downHit = RegionMap.classify(d); target = if (downHit != null) 1 else 2 }
            } else target = 2
            while (true) {
                if (!holdDone && !moved && !multi && target == 1 && android.os.SystemClock.uptimeMillis() - down.uptimeMillis >= PinchGesture.HOLD_MS) {
                    holdDone = true
                    applyStep(pinchGesture.hold(downHit, down.position.x, down.position.y, pinch != null && pinch.worn()))
                }
                val waitMs = PinchGesture.HOLD_MS - (android.os.SystemClock.uptimeMillis() - down.uptimeMillis)
                val event = if (!holdDone && !moved && !multi && waitMs > 0L) withTimeoutOrNull(waitMs) { awaitPointerEvent() } else awaitPointerEvent()
                if (event == null) {
                    // the hold time passed with the finger still: take hold of the stocking (when the pick has come back)
                    holdDone = true
                    applyStep(pinchGesture.hold(downHit, down.position.x, down.position.y, pinch != null && pinch.worn()))
                    continue
                }
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pinchGesture.active) pinchUsed = true
                if (pressed.size >= 2) {
                    if (pinchGesture.active) applyStep(pinchGesture.up())
                    multi = true
                    val a = pressed[0].position
                    val b = pressed[1].position
                    val s = hypot(a.x - b.x, a.y - b.y)
                    if (span > 0f && s > 0f) camera.pinch(s / span)
                    span = s
                    pressed.forEach { it.consume() }
                    continue
                }
                span = 0f
                val f = pressed[0]
                val dx = f.position.x - last.x
                val dy = f.position.y - last.y
                last = f.position
                if (pinchGesture.active && !multi) {
                    // the finger carries the fabric (or was refused): nothing else is done with this touch
                    pinchUsed = true
                    applyStep(pinchGesture.move(f.position.x, f.position.y, f.uptimeMillis))
                    f.consume()
                    continue
                }
                if (!moved && hypot(f.position.x - down.position.x, f.position.y - down.position.y) > slop) moved = true
                if (moved && !multi) {
                    if (target == 1 && mode.tool == InteractTool.TOUCH) {
                        if (gate == null) gate = mode.stroke(policy, persona())
                        val now = f.uptimeMillis
                        if (now >= nextSample) {
                            nextSample = now + 70L
                            val at = f.position
                            val g = gate
                            view.pickBodyDetail(actorKey, at.x, at.y, w, h) { d ->
                                if (g != null) {
                                    val step = g.sample(RegionMap.classify(d), rnd)
                                    if (step.kind != StrokeGate.Step.Kind.NONE) {
                                        ripple(at.x, at.y, if (step.kind == StrokeGate.Step.Kind.STOP) 2 else 0)
                                        onStrokeStep(step)
                                    }
                                }
                            }
                        }
                    } else camera.orbit(dx, dy, w.toFloat(), h.toFloat())
                    f.consume()
                }
            }
            if (pinchGesture.active) applyStep(pinchGesture.up())
            pinch?.at = null
            if (!moved && !multi && !pinchUsed) {
                val at = last
                if (mode.tool == InteractTool.TOUCH) {
                    view.pickBodyDetail(actorKey, at.x, at.y, w, h) { d ->
                        val hit = RegionMap.classify(d)
                        if (hit != null) {
                            val r = onTouch(hit, TouchKind.TAP)
                            ripple(at.x, at.y, if (r == null) 0 else if (r.verdict == Verdict.ALLOW) 1 else 2)
                        } else onBackgroundTap(at.x, at.y, w, h)
                    }
                } else onBackgroundTap(at.x, at.y, w, h)
            }
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val now = clock
            for (r in ripples) {
                val age = (now - r.at).coerceIn(0L, 800L) / 800f
                val colour = when (r.tone) { 2 -> Color(0xFFE05A4F); 1 -> Color(0xFFF2C46B); else -> Color(0xFFFFFFFF) }
                drawCircle(colour.copy(alpha = (1f - age) * 0.8f), radius = 14.dp.toPx() + 46.dp.toPx() * age, center = Offset(r.x, r.y), style = Stroke(width = 2.5.dp.toPx()))
                drawCircle(colour.copy(alpha = (1f - age) * 0.35f), radius = 8.dp.toPx() + 24.dp.toPx() * age, center = Offset(r.x, r.y))
            }
            // the tension gauge: a thin arc round the finger that carries fabric, filling as it tightens (cream, amber, red)
            val finger = pinch?.at
            if (pinch != null && finger != null) {
                val t = pinch.state.tension.coerceIn(0f, 1f)
                val radius = 40.dp.toPx()
                val tl = Offset(finger.x - radius, finger.y - radius)
                val box = Size(radius * 2, radius * 2)
                val colour = when (FabricGauge.zone(t)) { 2 -> Color(0xFFE5574A); 1 -> Color(0xFFF2C46B); else -> Color(0xFFF1E9DA) }
                drawArc(Color(0x66000000), 135f, 270f, false, tl, box, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
                if (t > 0.005f) drawArc(colour, 135f, 270f * t, false, tl, box, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(colour.copy(alpha = 0.25f + 0.4f * t), radius = 6.dp.toPx(), center = finger)
            }
        }
    }
}

/** The "Tell her..." row (over the dock): a field and a button; [onTell] gets the words. */
@Composable
internal fun InteractTellRow(typed: String, onTyped: (String) -> Unit, onTell: (String) -> Unit) {
    val send = { if (typed.isNotBlank()) { onTell(typed.trim()); onTyped("") } }
    Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        WowTextField(typed, { onTyped(it.take(120)) }, "Tell her... e.g. put on thigh-high stockings", Modifier.width(260.dp).fillMaxHeight(), onSend = send)
        WifeButton("Tell", { send() }, enabled = typed.isNotBlank())
    }
}

/**
 * The commands sheet's body: the tabs, then the open one's buttons. [poses] are the poses she can take (PoseLibrary.available), [stockings] the
 * stockings she wears now (null: none); every button hands an [InteractAction] (or a view change) to the screen, which runs it through the consent gate.
 */
@Composable
internal fun InteractSheetBody(
    tab: InteractTab, onTab: (InteractTab) -> Unit, camera: InteractCamera, tick: Int, poses: List<PoseKind>, stockings: StockingSpec?,
    onAction: (InteractAction) -> Unit, onStockings: (StockingSpec?) -> Unit, onOverview: () -> Unit,
    fabric: FabricState = FabricState.REST, options: StockOptions = StockOptions(), onOptions: (StockOptions) -> Unit = {},
    onRestore: () -> Unit = {}, onRepairAll: () -> Unit = {},
) {
    @Suppress("UNUSED_VARIABLE") val redraw = tick
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ScreenPlan.GAP.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
            for (t in InteractTab.entries) WifeChip(t.label, { onTab(t) }, trailing = if (t == tab) "✓" else null)
        }
        when (tab) {
            InteractTab.COMMANDS -> {
                WifeNote("View")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                    for (v in InteractView.entries) WifeChip(v.label, { camera.goTo(v) }, trailing = if (camera.current() == v) "✓" else null)
                }
                WifeButton("Back to overview", onOverview, Modifier.fillMaxWidth())
                WifeNote("Tell her")
                for (a in listOf(InteractAction.Look(LookTarget.CAMERA), InteractAction.Look(LookTarget.AWAY), InteractAction.Look(LookTarget.DOWN),
                    InteractAction.Express(Expression.SMILE), InteractAction.Express(Expression.BLUSH), InteractAction.Express(Expression.TEASE), InteractAction.Express(Expression.SHY),
                    InteractAction.ComeHere, InteractAction.MoveToSpot, InteractAction.Follow, InteractAction.Stay))
                    WifeButton(a.label, { onAction(a) }, Modifier.fillMaxWidth())
            }
            InteractTab.POSE -> {
                if (poses.isEmpty()) WifeNote("No poses yet: import animations in the Animation library.")
                for (p in poses) WifeButton(p.label, { onAction(InteractAction.Pose(p)) }, Modifier.fillMaxWidth())
            }
            InteractTab.OUTFIT -> {
                WifeButton("Gear on", { onAction(InteractAction.Outfit(OutfitAct.GEAR_ON)) }, Modifier.fillMaxWidth())
                WifeButton("Gear off", { onAction(InteractAction.Outfit(OutfitAct.GEAR_OFF)) }, Modifier.fillMaxWidth())
                WifeNote("Stockings" + (stockings?.let { ": ${it.style.label}, ${it.denier} D, ${it.colour.name}" } ?: ""))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                    for (s in StockingStyle.entries) WifeChip(s.label, { onStockings((stockings ?: StockingSpec()).copy(style = s)) }, trailing = if (stockings?.style == s) "✓" else null)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                    for (c in StockingColours.PRESETS) WifeChip(c.name, { onStockings((stockings ?: StockingSpec()).copy(colour = c)) }, trailing = if (stockings?.colour == c) "✓" else null)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                    for (d in listOf(8, 15, 20, 30, 40, 60, 80)) WifeChip("$d D", { onStockings((stockings ?: StockingSpec()).copy(denier = d)) }, trailing = if (stockings?.denier == d) "✓" else null)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                    val cur = stockings ?: StockingSpec()
                    WifeChip("Seam", { onStockings(cur.copy(seam = !cur.seam)) }, trailing = if (cur.seam) "✓" else null)
                    WifeChip("Lace top", { onStockings(cur.copy(lace = !cur.lace)) }, trailing = if (cur.lace) "✓" else null)
                }
                if (stockings != null) WifeButton("Remove stockings", { onStockings(null) }, Modifier.fillMaxWidth())
            }
            InteractTab.STOCKINGS -> StockingsPanel(stockings, onStockings, fabric, options, onOptions, onRestore, onRepairAll)
        }
    }
}

/**
 * The Stockings material panel: the fabric's status and tension gauge, Restore (takes back the latest damage) and Repair all, the yarn's thickness
 * (denier 8 to 80, applied when the slider is let go: it makes the stocking again), the elasticity, and the side light, fabric sound and haptics switches.
 */
@Composable
internal fun StockingsPanel(
    stockings: StockingSpec?, onStockings: (StockingSpec?) -> Unit, fabric: FabricState, options: StockOptions,
    onOptions: (StockOptions) -> Unit, onRestore: () -> Unit, onRepairAll: () -> Unit,
) {
    val status = FabricGauge.status(fabric)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Text(status.label, style = WifeTheme.labelStyle(when (status) { FabricStatus.INTACT -> WifeTheme.good; FabricStatus.STRETCHING -> WifeTheme.gold; FabricStatus.TORN -> WifeTheme.danger }))
        androidx.compose.material3.Text(FabricGauge.line(fabric), style = WifeTheme.captionStyle())
    }
    WifeProgress(fabric.tension)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        WifeButton("Restore", onRestore, Modifier.weight(1f), enabled = fabric.runs > 0)
        WifeButton("Repair all", onRepairAll, Modifier.weight(1f), enabled = fabric.runs > 0)
    }
    if (stockings == null) {
        WifeNote("She isn't wearing stockings.")
        WifeButton("Put on thigh-highs", { onStockings(StockingSpec()) }, Modifier.fillMaxWidth())
        return
    }
    var pending by remember(stockings.denier) { mutableIntStateOf(stockings.denier) }
    val span = StockOptions.MAX_DENIER - StockOptions.MIN_DENIER
    WifeNote("Yarn thickness: $pending D (${if (pending < 20) "sheer" else if (pending < 45) "semi-sheer" else "opaque"})")
    WifeSlider((pending - StockOptions.MIN_DENIER) / span.toFloat(), { f -> pending = StockOptions.MIN_DENIER + (f * span).roundToInt() }, Modifier.fillMaxWidth(),
        onFinished = { if (pending != stockings.denier) onStockings(stockings.copy(denier = pending)) })
    WifeSliderRow("Elasticity", options.elasticity, { onOptions(options.copy(elasticity = it)) }, valueText = "${options.elasticity}%")
    WifeSwitchRow("Side light", options.sideLight, { onOptions(options.copy(sideLight = it)) }, detail = "A raking light that shows the weave and its stretch")
    WifeSwitchRow("Fabric sound", options.sound, { onOptions(options.copy(sound = it)) }, detail = "Friction while it stretches and a rip when it tears")
    WifeSwitchRow("Haptics", options.haptics, { onOptions(options.copy(haptics = it)) }, detail = "Ticks as it tightens, a long buzz when it tears")
    WifeNote("Touch tool: hold a finger on her stocking, then drag to pull it. Pull until the gauge is full and it tears into a run. Let go and it springs back.")
}
