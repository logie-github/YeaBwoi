package com.logie.wifecraft.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.creator.StockingColours
import com.logie.wifecraft.creator.StockingSpec
import com.logie.wifecraft.creator.StockingStyle
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.graphics.FingerSize
import com.logie.wifecraft.graphics.Graphics
import com.logie.wifecraft.simulation.ConsentPolicy
import com.logie.wifecraft.simulation.Expression
import com.logie.wifecraft.simulation.HapticPattern
import com.logie.wifecraft.simulation.InteractAction
import com.logie.wifecraft.simulation.InteractCamera
import com.logie.wifecraft.simulation.InteractMode
import com.logie.wifecraft.simulation.InteractPersona
import com.logie.wifecraft.simulation.InteractTab
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
import kotlin.random.Random

// Interact mode's screen pieces (InteractMode, simulation/InteractMode.kt, is the logic; SceneSessionScreen owns the state and plays what these report):
// the touch layer over the picture (a tap on her body, a stroke along it, a drag on the background to orbit, a pinch to zoom, with a ripple where
// the finger is), and the body of the commands sheet (the views, Commands, Pose, Outfit). Pure drawing and gestures: nothing here decides what she does.

private class Ripple(val x: Float, val y: Float, val at: Long, val tone: Int)

/** The layer over the scene while Interact mode runs. [onTouch] answers a tap on her body (null: no reaction), [onStroke] each step of a stroke. */
@Composable
internal fun InteractTouchLayer(
    mode: InteractMode, tick: Int, camera: InteractCamera, view: SceneView, actorKey: String,
    policy: ConsentPolicy?, persona: () -> InteractPersona,
    onTouch: (TouchHit, TouchKind) -> Reaction?,
    onStrokeStep: (StrokeGate.Step) -> Unit,
    onBackgroundTap: (Float, Float, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val vibrate = remember(context) { { p: HapticPattern -> interactVibrate(context, mode, p) } }
    val personaNow by rememberUpdatedState(persona)
    val onTouchNow by rememberUpdatedState(onTouch)
    val onStrokeNow by rememberUpdatedState(onStrokeStep)
    val onBackgroundNow by rememberUpdatedState(onBackgroundTap)
    val env = remember(mode, camera, view, actorKey, policy) {
        InteractTouchEnv(mode, camera, view, actorKey, policy, { personaNow() }, { hit, kind -> onTouchNow(hit, kind) }, { step -> onStrokeNow(step) },
            { x, y, w, h -> onBackgroundNow(x, y, w, h) }, { x, y, tone -> ripple(x, y, tone) }, vibrate,
            { FingerSize.padRadius(Graphics.settings.value.fingerSize) }, rnd)
    }
    Box(modifier.fillMaxSize().interactMultiTouch(env)) {
        Canvas(Modifier.fillMaxSize()) {
            val now = clock
            for (r in ripples) {
                val age = (now - r.at).coerceIn(0L, 800L) / 800f
                val colour = when (r.tone) { 2 -> Color(0xFFE05A4F); 1 -> Color(0xFFF2C46B); else -> Color(0xFFFFFFFF) }
                drawCircle(colour.copy(alpha = (1f - age) * 0.8f), radius = 14.dp.toPx() + 46.dp.toPx() * age, center = Offset(r.x, r.y), style = Stroke(width = 2.5.dp.toPx()))
                drawCircle(colour.copy(alpha = (1f - age) * 0.35f), radius = 8.dp.toPx() + 24.dp.toPx() * age, center = Offset(r.x, r.y))
            }
        }
    }
}

/** A haptic pattern on the phone's vibrator, when the mode's Haptics switch is on (never throws). */
private fun interactVibrate(context: android.content.Context, mode: InteractMode, p: HapticPattern) {
    if (!mode.haptics) return
    try {
        val v = context.getSystemService(android.os.Vibrator::class.java)
        if (v != null && v.hasVibrator()) v.vibrate(android.os.VibrationEffect.createWaveform(p.timings, p.amplitudes, -1))
    } catch (e: Exception) { }
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
) {
    @Suppress("UNUSED_VARIABLE") val redraw = tick
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ScreenPlan.GAP.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
            for (t in InteractTab.entries) WifeChip(t.label, { onTab(t) }, trailing = if (t == tab) "✓" else null)
        }
        when (tab) {
            InteractTab.COMMANDS -> {
                InteractPinchHint()
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
        }
    }
}
