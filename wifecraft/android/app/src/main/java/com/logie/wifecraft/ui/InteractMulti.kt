package com.logie.wifecraft.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.simulation.CameraGesture
import com.logie.wifecraft.simulation.ConsentPolicy
import com.logie.wifecraft.simulation.FingerRole
import com.logie.wifecraft.simulation.FingerTarget
import com.logie.wifecraft.simulation.HapticPattern
import com.logie.wifecraft.simulation.InteractCamera
import com.logie.wifecraft.simulation.InteractGestures
import com.logie.wifecraft.simulation.InteractMode
import com.logie.wifecraft.simulation.InteractPersona
import com.logie.wifecraft.simulation.InteractTool
import com.logie.wifecraft.simulation.PinchGate
import com.logie.wifecraft.simulation.PinchGesture
import com.logie.wifecraft.simulation.PinchHaptics
import com.logie.wifecraft.simulation.Reaction
import com.logie.wifecraft.simulation.RegionMap
import com.logie.wifecraft.simulation.StrokeGate
import com.logie.wifecraft.simulation.TouchHit
import com.logie.wifecraft.simulation.TouchKind
import com.logie.wifecraft.simulation.Verdict
import kotlin.math.hypot
import kotlin.random.Random

// Interact mode's finger handling over the picture, for any number of fingers (simulation/InteractGestures.kt decides what each finger is: a
// touch, one of a pinch, the camera, ignored). This file only carries the Compose events in and the answers out: every pointer is tracked by
// its id, every change of an event is handled (not just the first), a pointer that is cancelled or a layer that leaves the screen lets every
// contact go. One finger on her = touch or stroke, two on her = a pinch (the engine pairs them), one of the two on the background or three
// or more = the camera, the Look tool = camera only. A stocking grab (another piece of work) takes the first finger alone: not wired here.

/** What the gesture layer needs from the screen. */
internal class InteractTouchEnv(
    val mode: InteractMode, val camera: InteractCamera, val view: SceneView, val actorKey: String,
    val policy: ConsentPolicy?, val persona: () -> InteractPersona,
    val onTouch: (TouchHit, TouchKind) -> Reaction?,
    val onStrokeStep: (StrokeGate.Step) -> Unit,
    val onBackgroundTap: (Float, Float, Int, Int) -> Unit,
    val ripple: (Float, Float, Int) -> Unit,
    val vibrate: (HapticPattern) -> Unit,
    /** The fingertip pad's radius in world units now (Finger size and Character size: graphics/FingerSize). */
    val pad: () -> Float,
    val rnd: Random,
    /** The stockings' pull (hold on a stocking-covered part, then drag): null when the screen has none; its first-finger-alone precedence is kept here. */
    val pinch: PinchHost? = null,
    val stock: com.logie.wifecraft.simulation.PinchGesture = com.logie.wifecraft.simulation.PinchGesture(),
)

private class FingerState {
    var moved = false
    var gate: StrokeGate? = null
    var nextSample = 0L
    var hit: TouchHit? = null
    var allowed = false
}

internal fun Modifier.interactMultiTouch(env: InteractTouchEnv): Modifier = pointerInput(env.view, env.actorKey) {
    val slop = 8.dp.toPx()
    awaitEachGesture {
        val run = InteractGestureRun(env, size.width, size.height, slop)
        try {
            val first = awaitFirstDown(requireUnconsumed = false)
            run.down(first.id.value, first.position.x, first.position.y)
            while (true) {
                run.holdCheck()
                val waitMs = run.holdWaitMs()
                val event = (if (waitMs > 0L) withTimeoutOrNull(waitMs) { awaitPointerEvent() } else awaitPointerEvent())
                if (event == null) { run.holdCheck(); continue }   // (the hold time passed with the finger still)
                for (c in event.changes) {
                    when {
                        c.changedToDown() -> run.down(c.id.value, c.position.x, c.position.y)
                        c.changedToUp() -> { run.up(c.id.value, c.position.x, c.position.y); c.consume() }
                        c.pressed && c.positionChanged() -> { run.move(c.id.value, c.position.x, c.position.y, c.uptimeMillis); c.consume() }
                    }
                }
                run.frame(event.changes.firstOrNull()?.uptimeMillis ?: 0L)
                if (event.changes.none { it.pressed }) break
            }
        } finally {
            run.finish()   // (a cancelled gesture or a layer that left: no contact stays)
        }
    }
}

private class InteractGestureRun(val env: InteractTouchEnv, val w: Int, val h: Int, val slop: Float) {
    val g = InteractGestures()
    val st = HashMap<Long, FingerState>()
    val gate = PinchGate(env.policy, env.mode.actor ?: "", { env.mode.persona(env.persona()) })
    val haptics = PinchHaptics()
    var multi = false
    var camCount = 0
    var camSpan = 0f
    var camX = 0f
    var camY = 0f
    var pinchBegun = false
    // the stockings' pull: a hold on the first finger alone
    var firstId = -1L
    var firstDownMs = 0L
    var firstX = 0f
    var firstY = 0f
    var downHit: TouchHit? = null
    var holdDone = true
    var stockUsed = false

    fun apply(changes: List<InteractGestures.Change>) {
        for (c in changes) {
            val wasContact = c.from == FingerRole.TOUCH || c.from == FingerRole.PINCH
            val isContact = c.to == FingerRole.TOUCH || c.to == FingerRole.PINCH
            if (wasContact && !isContact && c.slot >= 0) env.view.releaseTouch(env.actorKey, c.slot)   // (a finger that is no longer a touch lets go)
            if (c.from == FingerRole.PINCH && c.to != FingerRole.PINCH) { gate.end(c.id); pinchBegun = false }
            if (c.to == FingerRole.PINCH) { pinchBegun = false; st[c.id]?.nextSample = 0L }
        }
        if (g.count >= 2) multi = true
    }

    fun down(id: Long, x: Float, y: Float) {
        if (g.count == 0) {
            apply(g.setLook(env.mode.tool == InteractTool.LOOK)); multi = false; camCount = 0
            stockStep(env.stock.up()); stockUsed = false
            firstId = id; firstX = x; firstY = y; firstDownMs = android.os.SystemClock.uptimeMillis(); downHit = null
            holdDone = env.pinch == null || env.mode.tool != InteractTool.TOUCH
        } else if (env.stock.active && !env.stock.held) stockStep(env.stock.up())   // (a second finger before the grab took hold: no pull)
        st[id] = FingerState()
        apply(g.down(id, x, y))
        if (!g.lookTool && g.roleOf(id) != FingerRole.CAMERA && g.roleOf(id) != FingerRole.IGNORED && g.roleOf(id) != FingerRole.STOCKING) {
            env.view.pickBodyDetail(env.actorKey, x, y, w, h) { d ->
                val hit = RegionMap.classify(d)
                if (id == firstId) downHit = hit
                apply(g.classified(id, if (hit != null) FingerTarget.BODY else FingerTarget.BACKGROUND, env.actorKey))
            }
        }
    }

    fun move(id: Long, x: Float, y: Float, now: Long) {
        val f = g.finger(id) ?: return
        val s = st[id] ?: return
        g.move(id, x, y)
        if (env.stock.active && id == firstId) { stockUsed = true; stockStep(env.stock.move(x, y, now)); return }   // (the finger carries the fabric, or was refused)
        if (!s.moved && hypot(x - f.downX, y - f.downY) > slop) s.moved = true
        if ((env.mode.tool == InteractTool.LOOK) != g.lookTool) apply(g.setLook(env.mode.tool == InteractTool.LOOK))
        when (f.role) {
            FingerRole.TOUCH -> if (s.moved && f.target == FingerTarget.BODY && now >= s.nextSample) stroke(f, s, now)
            FingerRole.PINCH -> if (now >= s.nextSample) pinchSample(f, s, now)
            else -> Unit
        }
    }

    /** One step of a stroke along her (as ever: her consent gate stops it at the first part she won't have). */
    private fun stroke(f: InteractGestures.Finger, s: FingerState, now: Long) {
        s.nextSample = now + 70L
        if (s.gate == null) s.gate = env.mode.stroke(env.policy, env.persona())
        val at = f.x to f.y
        val slot = f.slot
        val gate = s.gate ?: return
        env.view.pickBodyDetail(env.actorKey, at.first, at.second, w, h) { d ->
            val step = gate.sample(RegionMap.classify(d), env.rnd)
            if ((step.kind == StrokeGate.Step.Kind.GO || step.kind == StrokeGate.Step.Kind.NEW_PART) && slot >= 0)
                env.view.pushTouch(env.actorKey, slot, at.first, at.second, w, h, 0.5f, 200, env.pad())   // (flesh contact: the skin gives under a touch she allows)
            if (step.kind != StrokeGate.Step.Kind.NONE) {
                env.ripple(at.first, at.second, if (step.kind == StrokeGate.Step.Kind.STOP) 2 else 0)
                env.onStrokeStep(step)
            }
        }
    }

    /** A finger of a pinch: where it is, what she allows there (its own consent), its contact; both allowed: her reaction and the haptic start. */
    private fun pinchSample(f: InteractGestures.Finger, s: FingerState, now: Long) {
        s.nextSample = now + 70L
        val x = f.x; val y = f.y; val slot = f.slot
        env.view.pickBodyDetail(env.actorKey, x, y, w, h) { d ->
            if (g.roleOf(f.id) != FingerRole.PINCH || slot < 0) return@pickBodyDetail
            val hit = RegionMap.classify(d)
            val o = gate.finger(f.id, hit, env.rnd)
            s.hit = hit; s.allowed = o.allowed
            if (o.allowed) env.view.pushTouch(env.actorKey, slot, x, y, w, h, 0.7f, 200, env.pad())
            else {
                env.view.releaseTouch(env.actorKey, slot)   // (declined: no dent)
                if (hit != null && o.reaction != null) { env.onTouch(hit, TouchKind.PINCH); env.ripple(x, y, 2) }
            }
            bothAllowed()
        }
    }

    private fun bothAllowed() {
        if (pinchBegun) return
        val two = g.withRole(FingerRole.PINCH)
        if (two.size != 2) return
        val sa = st[two[0].id] ?: return
        val sb = st[two[1].id] ?: return
        val ha = sa.hit ?: return
        val hb = sb.hit ?: return
        if (!sa.allowed || !sb.allowed) return
        pinchBegun = true
        env.vibrate(haptics.begin(ha.region, g.span()))
        if (gate.pinch(two[0].id, two[1].id, ha, hb, android.os.SystemClock.uptimeMillis(), env.rnd) != null) {
            val tender = if (com.logie.wifecraft.simulation.TouchConsent.sensitivity(ha.region) >= com.logie.wifecraft.simulation.TouchConsent.sensitivity(hb.region)) ha else hb
            env.onTouch(tender, TouchKind.PINCH)
            env.ripple(two[0].x, two[0].y, 1)
        }
    }

    /** After each event: the camera's fingers (one orbits, two zoom, three or more pan) and the pinch's gentle haptic while the fingers close. */
    fun frame(now: Long) {
        val cams = g.withRole(FingerRole.CAMERA)
        if (cams.size != camCount) { camCount = cams.size; camSpan = 0f; g.cameraCentroid()?.let { camX = it.first; camY = it.second } }
        else when (g.cameraGesture()) {
            CameraGesture.ZOOM -> {
                val s = hypot(cams[0].x - cams[1].x, cams[0].y - cams[1].y)
                if (camSpan > 0f && s > 0f) env.camera.pinch(s / camSpan)
                camSpan = s
            }
            CameraGesture.ORBIT, CameraGesture.PAN -> g.cameraCentroid()?.let { (cx, cy) ->
                val moved = cams.any { st[it.id]?.moved == true } || cams.size > 1
                if (moved) env.camera.orbit(cx - camX, cy - camY, w.toFloat(), h.toFloat())
                camX = cx; camY = cy
            }
            CameraGesture.NONE -> Unit
        }
        val two = g.withRole(FingerRole.PINCH)
        if (pinchBegun && two.size == 2) haptics.hold(g.span(), now)?.let { env.vibrate(it) }
    }

    fun up(id: Long, x: Float, y: Float) {
        val f = g.finger(id)
        val s = st[id]
        val role = f?.role
        val slot = f?.slot ?: -1
        if (env.stock.active && id == firstId) { stockUsed = true; stockStep(env.stock.up()); env.pinch?.at = null }
        val tap = f != null && s != null && !s.moved && !multi && !stockUsed
        apply(g.up(id))
        st.remove(id)
        if (slot >= 0 && role != null && (role == FingerRole.TOUCH || role == FingerRole.PINCH)) env.view.releaseTouch(env.actorKey, slot)   // (the finger is up, the skin springs back)
        if (!tap) return
        if (role == FingerRole.TOUCH) {
            env.view.pickBodyDetail(env.actorKey, x, y, w, h) { d ->
                val hit = RegionMap.classify(d)
                if (hit != null) {
                    val r = env.onTouch(hit, TouchKind.TAP)
                    if (r != null && r.verdict == Verdict.ALLOW) env.view.pushTouch(env.actorKey, if (slot >= 0) slot else 0, x, y, w, h, 0.6f, 250, env.pad())   // (a dent under a tap she allows)
                    env.ripple(x, y, if (r == null) 0 else if (r.verdict == Verdict.ALLOW) 1 else 2)
                } else env.onBackgroundTap(x, y, w, h)
            }
        } else if (role == FingerRole.CAMERA || role == FingerRole.PENDING) env.onBackgroundTap(x, y, w, h)
    }

    /** Milliseconds until the first finger has rested long enough to take hold of a stocking (0 or less: not waiting). */
    fun holdWaitMs(): Long {
        if (holdDone || g.count != 1 || st[firstId]?.moved != false || g.roleOf(firstId) != FingerRole.TOUCH) return -1L
        return PinchGesture.HOLD_MS - (android.os.SystemClock.uptimeMillis() - firstDownMs)
    }

    /** The hold time has passed with the first finger still and alone on a part she can wear stockings on: the pull starts (the engine says whether there is stocking). */
    fun holdCheck() {
        val host = env.pinch ?: return
        if (holdDone || g.count != 1 || st[firstId]?.moved != false || g.roleOf(firstId) != FingerRole.TOUCH) return
        if (android.os.SystemClock.uptimeMillis() - firstDownMs < PinchGesture.HOLD_MS) return
        holdDone = true
        stockStep(env.stock.hold(downHit, firstX, firstY, host.worn()))
    }

    private fun stockStep(step: PinchGesture.Step) {
        val host = env.pinch ?: return
        when (step) {
            is PinchGesture.Step.Probe -> env.view.stockProbe(env.actorKey, step.x, step.y, w, h) { c -> stockStep(env.stock.covered(c)) }
            is PinchGesture.Step.Ask -> stockStep(env.stock.verdict(host.verdict(step.hit)))
            is PinchGesture.Step.Grab -> env.view.stockGrab(env.actorKey, step.x, step.y, w, h) { ok ->
                val next = env.stock.grabbed(ok)
                if (env.stock.held) { stockUsed = true; apply(g.stockingGrabbed(firstId)); host.onGrab(); host.at = androidx.compose.ui.geometry.Offset(step.x, step.y) }
                stockStep(next)
            }
            is PinchGesture.Step.Refused -> { stockUsed = true; env.ripple(firstX, firstY, 2) }
            is PinchGesture.Step.Drag -> { env.view.stockDrag(env.actorKey, step.x, step.y, w, h); host.at = androidx.compose.ui.geometry.Offset(step.x, step.y) }
            PinchGesture.Step.Release -> { env.view.stockRelease(env.actorKey); host.at = null; host.onEnd() }
            PinchGesture.Step.NotPull, PinchGesture.Step.None -> Unit
        }
    }

    /** The gesture is over (or was cancelled): every contact lets go. */
    fun finish() {
        stockStep(env.stock.up()); env.pinch?.at = null
        apply(g.cancelAll())
        env.view.releaseTouch(env.actorKey)   // (every finger of her: nothing stays pressed)
        gate.clear()
        st.clear()
    }
}

/** The Interact sheet's first-time hint about the two-finger pinch: shown until it is dismissed (kept in the preferences, per phone). */
@Composable
internal fun InteractPinchHint() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { try { context.getSharedPreferences("wifecraft", android.content.Context.MODE_PRIVATE) } catch (e: Exception) { null } }
    var seen by remember { mutableStateOf(try { prefs?.getBoolean(PINCH_HINT_KEY, false) ?: false } catch (e: Exception) { false }) }
    if (seen) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        WifeNote("Use two fingers on her skin to pinch")
        WifeChip("Got it", trailing = null, onClick = { seen = true; try { prefs?.edit()?.putBoolean(PINCH_HINT_KEY, true)?.apply() } catch (e: Exception) { } })
    }
}

private const val PINCH_HINT_KEY = "interactPinchHintSeen"
