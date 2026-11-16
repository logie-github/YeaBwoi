package com.logie.wifecraft.ui

import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.story.Vec3
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What the stage's gestures tell the editor (main thread). Pixels of the stage's own view; [w] x [h] its size. */
class ViewportActions(
    /** A tap (not part of a drag) at the time [ms] (the editor tells a double tap and a tap on the same spot again apart). */
    val onTap: (sx: Float, sy: Float, w: Int, h: Int, ms: Long) -> Unit,
    /** A finger held still: the context menu of what is under it. */
    val onLongPress: (sx: Float, sy: Float, w: Int, h: Int) -> Unit,
    /** The selected thing as a handle drag has it now (drawn, not yet a change). */
    val onLive: (Placement) -> Unit,
    /** The drag ended: its last state is one change (one undo step). */
    val onCommit: (Placement) -> Unit,
    /** The handle being dragged (GizmoAxis; NONE once it is let go): lit on the gizmo. */
    val onActiveAxis: (Int) -> Unit,
    /** Placing mode: a finger began dragging the ghost, moved it, lifted (drop), or two fingers made it a camera move. */
    val onGhostPress: () -> Unit,
    val onGhostDrag: (sx: Float, sy: Float, w: Int, h: Int) -> Unit,
    val onGhostRelease: () -> Unit,
    val onGhostCancel: () -> Unit,
    /** Placing mode: a tap aims the crosshair (the ghost goes there). */
    val onAim: (sx: Float, sy: Float, w: Int, h: Int) -> Unit,
)

private enum class Mode { ORBIT, CAMERA2, HANDLE, TURN }

/** The renderer hasn't answered which handle is under the finger yet. */
private const val PENDING = Int.MIN_VALUE

/** Whether the finger at [at] is on the thing [p] as the camera sees it: near its feet, middle or head (people stand about two yards). */
private fun onThing(cam: ViewRay, p: Placement, at: Offset, w: Float, h: Float, reach: Float): Boolean {
    val tall = 1.8f * p.scale.coerceIn(0.3f, 4f)
    return listOf(0f, tall / 2f, tall).any { dz ->
        cam.project(Vec3(p.x, p.y, p.z + dz), w, h)?.let { (sx, sy) -> hypot(sx - at.x, sy - at.y) <= reach } == true
    }
}

/** Waits for every finger to lift, using up what comes. */
private suspend fun AwaitPointerEventScope.drainUntilUp() {
    while (true) {
        val ev = awaitPointerEvent()
        ev.changes.forEach { it.consume() }
        if (ev.changes.none { it.pressed }) return
    }
}

/**
 * The stage and its gestures.
 *
 * Placing ([placing]): one finger drags the ghost ([ViewportActions.onGhostDrag]) and lifting drops it; a tap aims the
 * crosshair; two fingers pan, pinch and turn the camera instead (nothing drops).
 *
 * Otherwise: a tap is told to the editor (select, cycle, double tap); a finger held still opens the context menu. A drag
 * that starts on a handle of the selected, unlocked thing's gizmo (SceneView.gizmoHit; [GizmoMath.hit] until the renderer
 * answers) moves it along that axis, turns it on the ring or resizes it with the boxes ([GizmoDrag]); one that starts on the
 * thing's body, or the floor square, slides it on the floor, following the ground under it at the height it stood above
 * it. The camera never moves while a handle is dragged. Two fingers on the selected thing turn and resize it. Anywhere else
 * one finger orbits the camera and two pan and pinch to zoom. A handle drag is one change when the finger lifts.
 */
@Composable
fun PlaceViewport(
    view: SceneView,
    camera: OrbitCamera,
    onCamera: (OrbitCamera) -> Unit,
    selected: Placement?,
    canMove: Boolean,
    snap: Snap,
    tool: GizmoTool,
    placing: Boolean,
    crosshair: Pair<Float, Float>?,
    rays: (w: Float, h: Float) -> ViewRay,
    actions: ViewportActions,
    onSize: (w: Int, h: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cam by rememberUpdatedState(camera)
    val sel by rememberUpdatedState(selected)
    val movable by rememberUpdatedState(canMove)
    val snapNow by rememberUpdatedState(snap)
    val toolNow by rememberUpdatedState(tool)
    val placingNow by rememberUpdatedState(placing)
    val raysNow by rememberUpdatedState(rays)
    val acts by rememberUpdatedState(actions)
    val camChange by rememberUpdatedState(onCamera)
    // each gesture's number: an answer (a ground height, a handle) that comes back after its gesture ended is dropped
    val gesture = remember { intArrayOf(0) }
    val density = LocalDensity.current

    Box(modifier.onSizeChanged { onSize(it.width, it.height) }) {
        AndroidView({ (view.parent as? ViewGroup)?.removeView(view); view }, Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val my = ++gesture[0]
                    val w = this.size.width.toFloat()
                    val h = this.size.height.toFloat()
                    val slop = viewConfiguration.touchSlop
                    val ray = raysNow(w, h)

                    // ---- placing: the ghost under one finger
                    if (placingNow) {
                        var moved = false
                        var two = false
                        var camNow = cam
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                if (!two) { two = true; if (moved) acts.onGhostCancel() }
                                val a = pressed[0]; val b = pressed[1]
                                val a0 = a.previousPosition.x to a.previousPosition.y; val b0 = b.previousPosition.x to b.previousPosition.y
                                val a1 = a.position.x to a.position.y; val b1 = b.position.x to b.position.y
                                val m0 = Gestures.mid(a0, b0); val m1 = Gestures.mid(a1, b1)
                                camNow = camNow.pan(m1.first - m0.first, m1.second - m0.second, h).zoom(Gestures.pinch(a0, b0, a1, b1))
                                camNow = camNow.copy(yaw = wrapAngle(camNow.yaw - Gestures.twist(a0, b0, a1, b1)))
                                camChange(camNow)
                            } else if (!two) {
                                val c = pressed[0]
                                if (!moved && (c.position - down.position).getDistance() > slop) { moved = true; acts.onGhostPress() }
                                if (moved) acts.onGhostDrag(c.position.x, c.position.y, w.toInt(), h.toInt())
                            }
                            ev.changes.forEach { it.consume() }
                        }
                        when {
                            two -> Unit
                            !moved -> acts.onAim(down.position.x, down.position.y, w.toInt(), h.toInt())
                            else -> acts.onGhostRelease()
                        }
                        return@awaitEachGesture
                    }

                    // ---- selecting, the gizmo, the camera
                    val s0 = sel
                    val canDrag = s0 != null && movable
                    val slopPx = with(density) { 20.dp.toPx() }
                    val answer = intArrayOf(PENDING)
                    if (canDrag) view.gizmoAt(down.position.x, down.position.y, w.toInt(), h.toInt(), slopPx) { a -> if (gesture[0] == my) answer[0] = a }
                    val guess = if (canDrag && s0 != null) GizmoMath.hit(ray, toolNow, s0, down.position.x, down.position.y, w, h, slopPx) else GizmoAxis.NONE
                    val onBody = canDrag && s0 != null && onThing(ray, s0, down.position, w, h, with(density) { 56.dp.toPx() })

                    // a tap, a finger held still (the context menu), or a drag
                    var upEarly = false
                    var two = false
                    val first: PointerEvent? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var got: PointerEvent? = null
                        while (got == null) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            when {
                                pressed.isEmpty() -> { upEarly = true; got = ev }
                                pressed.size >= 2 -> { two = true; got = ev }
                                (pressed[0].position - down.position).getDistance() > slop -> got = ev
                            }
                        }
                        got
                    }
                    if (first == null) {
                        acts.onLongPress(down.position.x, down.position.y, w.toInt(), h.toInt())
                        drainUntilUp()
                        return@awaitEachGesture
                    }
                    if (upEarly) {
                        first.changes.forEach { it.consume() }
                        acts.onTap(down.position.x, down.position.y, w.toInt(), h.toInt(), down.uptimeMillis)
                        return@awaitEachGesture
                    }

                    // the handle under the finger, as this mode drags it (Scale's per-axis boxes size the whole thing)
                    val axis = GizmoMath.dragAxis(toolNow, if (answer[0] != PENDING) answer[0] else guess)
                    var mode = when {
                        two -> if (onBody) Mode.TURN else Mode.CAMERA2
                        canDrag && axis != GizmoAxis.NONE -> Mode.HANDLE
                        onBody -> Mode.HANDLE
                        else -> Mode.ORBIT
                    }
                    val handle = if (mode != Mode.HANDLE) GizmoAxis.NONE else if (axis != GizmoAxis.NONE) axis else GizmoAxis.FLOOR
                    val drag = if (mode == Mode.HANDLE && s0 != null) GizmoDrag.begin(handle, s0, ray, down.position.x, down.position.y, w, h) else null
                    if (mode == Mode.HANDLE && drag == null) mode = Mode.ORBIT
                    if (drag != null) acts.onActiveAxis(handle)
                    var now: Placement? = s0
                    var lift: Float? = null
                    var camNow = cam
                    if (handle == GizmoAxis.FLOOR && s0 != null) view.groundAt(s0.x, s0.y, s0.z + 2f) { g -> if (gesture[0] == my) lift = if (g == null) 0f else s0.z - g }

                    fun step(ev: PointerEvent) {
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2 && mode != Mode.HANDLE) {
                            val a = pressed[0]; val b = pressed[1]
                            val a0 = a.previousPosition.x to a.previousPosition.y; val b0 = b.previousPosition.x to b.previousPosition.y
                            val a1 = a.position.x to a.position.y; val b1 = b.position.x to b.position.y
                            val n = now
                            if (mode == Mode.TURN && n != null) {
                                val turned = n.copy(
                                    yaw = wrapAngle(n.yaw + Gestures.yawOfTwist(Gestures.twist(a0, b0, a1, b1))),
                                    scale = Snap.clampScale(n.scale * Gestures.pinch(a0, b0, a1, b1)),
                                )
                                now = turned
                                acts.onLive(turned)
                            } else {
                                mode = Mode.CAMERA2
                                val m0 = Gestures.mid(a0, b0); val m1 = Gestures.mid(a1, b1)
                                camNow = camNow.pan(m1.first - m0.first, m1.second - m0.second, h).zoom(Gestures.pinch(a0, b0, a1, b1))
                                camChange(camNow)
                            }
                        } else if (mode == Mode.HANDLE && drag != null) {
                            // the finger that took the handle; a second one is ignored (the camera stays put)
                            val c = ev.changes.firstOrNull { it.id == down.id && it.pressed } ?: return
                            val next = drag.to(ray, c.position.x, c.position.y, w, h, snapNow)
                            now = next
                            acts.onLive(next)
                            if (handle == GizmoAxis.FLOOR) {
                                val x = next.x; val y = next.y
                                view.groundAt(x, y, drag.start.z + 2f) { g ->
                                    val cur = now
                                    if (gesture[0] == my && cur != null && cur.x == x && cur.y == y) {
                                        val z = followGround(g, lift ?: 0f, cur.z)
                                        if (z != cur.z) { now = cur.copy(z = z); acts.onLive(cur.copy(z = z)) }
                                    }
                                }
                            }
                        } else if (mode == Mode.ORBIT && pressed.size == 1) {
                            val c = pressed[0]
                            val dl = c.position - c.previousPosition
                            camNow = camNow.orbit(dl.x, dl.y, h)
                            camChange(camNow)
                        }
                    }

                    step(first)
                    first.changes.forEach { it.consume() }
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.none { it.pressed }) break
                        step(ev)
                        ev.changes.forEach { it.consume() }
                    }
                    val end = now
                    if (drag != null) acts.onActiveAxis(GizmoAxis.NONE)
                    when {
                        end == null -> Unit
                        mode == Mode.HANDLE && handle == GizmoAxis.FLOOR -> view.groundAt(end.x, end.y, drag!!.start.z + 2f) { g ->
                            acts.onCommit(end.copy(z = followGround(g, lift ?: 0f, end.z)))
                        }
                        mode == Mode.HANDLE -> acts.onCommit(end)
                        mode == Mode.TURN -> acts.onCommit(end.copy(yaw = snapNow.yaw(end.yaw), scale = snapNow.scale(end.scale)))
                    }
                }
            },
        )

        // the crosshair while placing: where Drop puts one (the middle until a tap moves it)
        if (placing) {
            val s = with(density) { 28.dp.toPx() }
            Canvas(Modifier.matchParentSize()) {
                val c = crosshair?.let { Offset(it.first, it.second) } ?: Offset(size.width / 2f, size.height / 2f)
                val k = 2.dp.toPx()
                drawLine(Color(0xCC000000), Offset(c.x - s, c.y), Offset(c.x + s, c.y), k * 2.2f)
                drawLine(Color(0xCC000000), Offset(c.x, c.y - s), Offset(c.x, c.y + s), k * 2.2f)
                drawLine(WowColors.gold, Offset(c.x - s, c.y), Offset(c.x + s, c.y), k)
                drawLine(WowColors.gold, Offset(c.x, c.y - s), Offset(c.x, c.y + s), k)
                drawCircle(WowColors.gold, s * 0.45f, c, style = Stroke(k))
            }
        }
    }
}

/** The gizmo's mode buttons' pictures: Move a four-way arrow, Rotate a turning arrow, Scale a box with a corner arrow. */
@Composable
fun GizmoIcon(tool: GizmoTool, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val k = 2.dp.toPx()
        val w = size.width; val h = size.height
        val c = Offset(w / 2, h / 2)
        fun head(tip: Offset, dx: Float, dy: Float) {
            val a = 0.28f * w
            drawLine(color, tip, Offset(tip.x - dx * a + dy * a * 0.7f, tip.y - dy * a - dx * a * 0.7f), k)
            drawLine(color, tip, Offset(tip.x - dx * a - dy * a * 0.7f, tip.y - dy * a + dx * a * 0.7f), k)
        }
        when (tool) {
            GizmoTool.MOVE -> {
                drawLine(color, Offset(0f, c.y), Offset(w, c.y), k)
                drawLine(color, Offset(c.x, 0f), Offset(c.x, h), k)
                head(Offset(w, c.y), 1f, 0f); head(Offset(0f, c.y), -1f, 0f); head(Offset(c.x, 0f), 0f, -1f); head(Offset(c.x, h), 0f, 1f)
            }
            GizmoTool.ROTATE -> {
                drawArc(color, 30f, 280f, false, Offset(w * 0.12f, h * 0.12f), Size(w * 0.76f, h * 0.76f), style = Stroke(k))
                head(Offset(w * 0.83f, h * 0.30f), 0.3f, -1f)
            }
            GizmoTool.SCALE -> {
                drawRect(color, Offset(0f, h * 0.4f), Size(w * 0.6f, h * 0.6f), style = Stroke(k))
                drawLine(color, Offset(w * 0.35f, h * 0.65f), Offset(w, 0f), k)
                head(Offset(w, 0f), 0.7f, -0.7f)
            }
        }
    }
}

/**
 * The gizmo's mode bar on the selected thing: Move, Rotate, Scale (with their pictures and labels), the handle being
 * dragged named; a locked thing shows Locked and Unlock instead (no handles).
 */
@Composable
fun GizmoBar(tool: GizmoTool, onTool: (GizmoTool) -> Unit, locked: Boolean, onUnlock: () -> Unit, active: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color(0xC0000000)).border(1.dp, Color(0x66FFD100)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (locked) {
            Text("Locked", style = frizStyle(14.sp, WowColors.grey), modifier = Modifier.padding(horizontal = 8.dp))
            PButton("Unlock", onUnlock, width = 96.dp)
            return@Row
        }
        for (t in GizmoTool.values()) {
            val on = t == tool
            Row(
                Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp)
                    .background(if (on) Color(0x66FFD100) else Color(0x66000000))
                    .border(1.dp, if (on) WowColors.gold else Color(0x66FFD100))
                    .clickable { onTool(t) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GizmoIcon(t, if (on) Color.White else WowColors.gold)
                Text(t.label, style = frizStyle(14.sp, if (on) Color.White else WowColors.gold), maxLines = 1)
            }
        }
        if (active != GizmoAxis.NONE) Text(GizmoAxis.name(active), style = frizStyle(13.sp, Color.White), modifier = Modifier.padding(horizontal = 6.dp))
    }
}

/** What the context menu (a long press on a placed thing) can do. */
enum class PlaceAction(val label: String) { DUPLICATE("Duplicate"), DELETE("Delete"), LOCK("Lock"), HIDE("Hide"), EMOTE("Emote"), FOCUS("Focus") }

/**
 * The small menu of a long-pressed thing [p], beside where the finger was ([at], pixels of the stage [stageW] x [stageH]),
 * kept inside the stage: Duplicate, Delete, Lock (Unlock), Hide (Show), Emote (a person or a creature), Focus. A tap
 * outside closes it.
 */
@Composable
fun PlaceContextMenu(p: Placement, at: Pair<Float, Float>, stageW: Int, stageH: Int, onAction: (PlaceAction) -> Unit, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
    val actions = PlaceAction.values().filter { it != PlaceAction.EMOTE || p.kind != PlaceKind.PROP }
    val wPx = with(density) { 180.dp.toPx() }
    val hPx = with(density) { (48.dp * actions.size + 40.dp).toPx() }
    val x = (at.first + 12f).coerceAtMost(stageW - wPx).coerceAtLeast(0f)
    val y = (at.second - hPx / 3f).coerceAtMost(stageH - hPx).coerceAtLeast(0f)
    // the build mode's look: a small parchment card with a round glyph or icon per action
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(WarSize.R_CARD.dp)
    Column(
        Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(180.dp)
            .background(War.cream, shape).border(1.5.dp, War.goldSoft, shape).padding(4.dp),
    ) {
        Text(p.name, style = warTitle(16.sp), maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        for (a in actions) {
            val label = when (a) {
                PlaceAction.LOCK -> if (p.locked) "Unlock" else "Lock"
                PlaceAction.HIDE -> if (p.hidden) "Show" else "Hide"
                else -> a.label
            }
            val face = when (a) {
                PlaceAction.DUPLICATE -> toolFace(BuildTool.DUPLICATE); PlaceAction.DELETE -> toolFace(BuildTool.DELETE)
                PlaceAction.LOCK -> toolFace(BuildTool.LOCK); PlaceAction.HIDE -> toolFace(BuildTool.HIDE)
                PlaceAction.EMOTE -> toolFace(BuildTool.EMOTE); PlaceAction.FOCUS -> toolFace(BuildTool.FOCUS)
            }
            androidx.compose.foundation.layout.Row(
                Modifier.height(48.dp).width(172.dp).warPress { onAction(a) }.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoundIconButton(face, { onAction(a) }, size = 40.dp, description = label)
                Text(label, style = warText(15.sp, if (a == PlaceAction.DELETE) War.danger else War.brown))
            }
        }
    }
}
