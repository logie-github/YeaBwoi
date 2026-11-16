package com.logie.wifecraft.ui

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.engine.GizmoMode
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.story.Vec3

// The placement editor's only calls to the renderer's gizmo, ghost and camera (W35's SceneView API), in one place so
// that a change of their shape is a change here. Main thread; the answers come back on the main thread.

/** Shows [tool]'s gizmo on the thing [key] (null or [tool] null: none), [active] the handle being dragged (lit). */
internal fun SceneView.showGizmo(tool: GizmoTool?, key: String?, active: Int = GizmoAxis.NONE) {
    val mode = when (tool) {
        null -> GizmoMode.OFF
        GizmoTool.MOVE -> GizmoMode.MOVE
        GizmoTool.ROTATE -> GizmoMode.ROTATE
        GizmoTool.SCALE -> GizmoMode.SCALE
    }
    setGizmo(if (key == null) GizmoMode.OFF else mode, key, active)
}

/** Which handle of the gizmo is under the screen point (GizmoAxis; NONE for none). */
internal fun SceneView.gizmoAt(sx: Float, sy: Float, w: Int, h: Int, slopPx: Float, onHit: (Int) -> Unit) =
    gizmoHit(sx, sy, w, h, onHit, slopPx)

/** The renderer's camera now, as the editor's maths takes it. */
internal fun SceneView.cameraMatrices(onState: (ViewMatrices) -> Unit) = cameraState { c ->
    onState(ViewMatrices(Vec3(c.eye.x, c.eye.y, c.eye.z), c.view, c.proj, c.aspect))
}

/** The see-through copy of a library entry that follows the finger (an NPC's in its [appearance]). */
internal fun SceneView.ghostOf(kind: PlaceKind, asset: Int, scale: Float, appearance: Appearance?, onReady: (Boolean, String?) -> Unit) =
    ghostBegin(kind, asset, scale, onReady, appearance)

internal fun SceneView.ghostTo(at: Vec3, yaw: Float) = ghostMove(at.x, at.y, at.z, yaw)

internal fun SceneView.ghostOff() = ghostClear()
