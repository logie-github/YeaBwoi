package com.logie.wifecraft.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.engine.Native
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.engine.ToyNative
import com.logie.wifecraft.props.PropStore
import com.logie.wifecraft.simulation.Expression
import com.logie.wifecraft.simulation.HapticPattern
import com.logie.wifecraft.simulation.MapToyKeyValue
import com.logie.wifecraft.simulation.ReactionLevel
import com.logie.wifecraft.simulation.ToyActivity
import com.logie.wifecraft.simulation.ToyController
import com.logie.wifecraft.simulation.ToyEvent
import com.logie.wifecraft.simulation.ToyGeometry
import com.logie.wifecraft.simulation.ToyHaptics
import com.logie.wifecraft.simulation.ToyItem
import com.logie.wifecraft.simulation.ToyKeyValue
import com.logie.wifecraft.simulation.ToyKind
import com.logie.wifecraft.simulation.ToyPrefs
import com.logie.wifecraft.simulation.ToyReactions
import com.logie.wifecraft.simulation.ToyTray
import java.io.File

// Interact mode's Toys (README "Toy interaction"): the tray in the commands sheet (the Toys tab), the held toy's touch layer and its small
// bar (Remove, Drop, the vibrator), and the session that drives the engine each frame and her reactions. The logic is simulation/
// ToyInteraction.kt and ToyReactions.kt (JVM-tested); the engine side is engine/orificedraw.cpp and toyrig.cpp. Kept out of SceneSessionScreen
// but for three one-line call sites (rememberToySession, ToyTouchLayer and the Toys tab's body).

/** The session of the screen that is in Interact mode now (the sheet's Toys tab reads it). */
object ToyHub { @Volatile var session: ToySession? = null }

class ToySession(
    private val view: SceneView,
    private val store: PropStore?,
    val prefs: ToyPrefs,
    private val actorKey: () -> String?,
    private val boldness: () -> Float,
    private val express: (Expression) -> Unit,
    private val say: (String) -> Unit,
    private val vibrate: (HapticPattern) -> Unit,
    private val closeSheet: () -> Unit,
) {
    val controller = ToyController()
    val reactions = ToyReactions(prefs.level).also { it.climaxEnabled = prefs.climax }
    /** Bumped whenever something the panel shows changed. */
    var version by mutableIntStateOf(0)
    @Volatile private var geometry: ToyGeometry? = null
    private val loaded = HashSet<Long>()
    private var lastExpression: Expression? = null
    private var buzzMs = 0L
    private var poseSent = false
    private var clockMs = 0L
    private var items: List<ToyItem> = emptyList()
    var width = 1; var height = 1

    fun items(): List<ToyItem> {
        if (items.isEmpty()) items = ToyTray.items(store?.index().orEmpty().map { Triple(it.guid, it.source, it.size) })
        return items
    }

    fun refreshItems() { items = emptyList(); version++ }

    fun pick(item: ToyItem) {
        val g = geometry
        // near her pelvis: a hand's length below the entry, or the middle of the view before the entry is known
        val x = g?.entryX ?: (width * 0.5f)
        val y = g?.let { it.entryY + 0.25f * it.pxPerMetre } ?: (height * 0.6f)
        controller.pick(item, x, y)
        if (!item.kind.insertable) controller.setVibration(false, prefs.vibration) else controller.setVibration(false, prefs.vibration)
        vibrate(ToyHaptics.pick())
        closeSheet()
        version++
    }

    fun setLevel(l: ReactionLevel) { prefs.level = l; reactions.level = l; version++ }
    fun setClimax(on: Boolean) { prefs.climax = on; reactions.climaxEnabled = on; version++ }
    fun setLines(on: Boolean) { prefs.lines = on; version++ }
    fun setVibration(on: Boolean, level: Float) { prefs.vibration = level; controller.setVibration(on, level); version++ }
    fun remove() { controller.remove(); version++ }
    fun drop() { controller.drop(); vibrate(ToyHaptics.tick()); version++ }

    fun down(x: Float, y: Float) { if (controller.phase == ToyController.Phase.HELD) handle(controller.move(x, y, geometry)) else { controller.fx = x; controller.fy = y } }
    fun drag(x: Float, y: Float, dx: Float, dy: Float, dt: Float) {
        when (controller.phase) {
            ToyController.Phase.HELD -> handle(controller.move(x, y, geometry))
            ToyController.Phase.ENGAGED -> handle(controller.drag(dx, dy, dt, geometry))
            else -> Unit
        }
    }
    fun up() { handle(controller.release()); version++ }

    private fun handle(e: ToyEvent) {
        when (e) {
            ToyEvent.SNAPPED -> { vibrate(ToyHaptics.snap()); version++ }
            ToyEvent.TICK -> vibrate(ToyHaptics.tick())
            ToyEvent.REMOVED -> { vibrate(ToyHaptics.tick()); version++ }
            else -> Unit
        }
    }

    /** Each frame (UI thread): the controller's step, the engine's toy and her pose, the buzz. */
    fun frame(dtSeconds: Float) {
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        clockMs += (dt * 1000).toLong()
        handle(controller.step(dt))
        val key = actorKey() ?: return
        val cmd = controller.command(width, height)
        val w = width; val h = height
        val item = controller.item
        view.post { hh, ids ->
            val id = ids[key] ?: return@post
            if (cmd == null || item == null) ToyNative.setToy(hh, id, 0L, false, 0, 0f, 0f, w, h, 0f, 0f, 0f, false)
            else {
                if (loaded.add(item.guid)) store?.modelFile(item.guid)?.takeIf { it.isFile }?.readBytes()?.let { Native.loadProp(hh, item.guid, it) }
                ToyNative.setToy(hh, id, item.guid, item.kind.doubleEnded, cmd.mode, cmd.fx, cmd.fy, w, h, cmd.depth, cmd.vibration, cmd.roll, false)
                val buf = FloatArray(14)
                if (ToyNative.toyInfo(hh, buf)) geometry = ToyGeometry(buf[2], buf[3], buf[4], buf[5], buf[6], buf[7], buf[1] > 0.5f)
            }
        }
        // her reactions: only while a toy is in or has been (the layer lets go when she has calmed)
        val active = controller.held || reactions.arousal > 0.01f || reactions.inClimax
        if (active) {
            reactions.boldness = boldness()
            val out = reactions.step(dt, ToyActivity(controller.inserted, controller.depth, controller.speed, controller.vibration, controller.held && !controller.inserted && controller.item?.kind?.usable == true && geometry?.let { g -> kotlin.math.hypot(controller.fx - g.entryX, controller.fy - g.entryY) < 0.08f * g.pxPerMetre } == true), prefs.lines)
            val values = out.pose.toArray()
            view.post { hh, ids -> ids[key]?.let { ToyNative.setPose(hh, it, values) } }
            poseSent = true
            if (out.expression != null && out.expression != lastExpression) { lastExpression = out.expression; express(out.expression) }
            out.line?.let { say(it) }
        } else if (poseSent) {
            poseSent = false; lastExpression = null
            view.post { hh, ids -> ids[key]?.let { ToyNative.setPose(hh, it, null) } }
        }
        // the vibrator's buzz on the phone, in proportion to the strength (Interact's Haptics switch gates `vibrate`)
        if (controller.vibration > 0f && clockMs - buzzMs >= ToyHaptics.PULSE_MS) { buzzMs = clockMs; ToyHaptics.buzz(controller.vibration)?.let(vibrate) }
    }

    fun end() {
        controller.drop(); reactions.reset()
        val key = actorKey()
        view.post { hh, ids -> ids[key ?: ""]?.let { ToyNative.setToy(hh, it, 0L, false, 0, 0f, 0f, 1, 1, 0f, 0f, 0f, false); ToyNative.setPose(hh, it, null) } }
    }
}

private class PrefsKeyValue(private val sp: android.content.SharedPreferences) : ToyKeyValue {
    override fun getString(key: String) = try { sp.getString(key, null) } catch (e: Exception) { null }
    override fun putString(key: String, value: String) { try { sp.edit().putString(key, value).apply() } catch (e: Exception) { } }
}

/** The screen's toy session while Interact mode is on ([actor] the person; null: none). */
@Composable
internal fun rememberToySession(
    view: SceneView, actor: String?, actorKey: (String) -> String, boldness: (String) -> Float, hapticsOn: () -> Boolean,
    express: (Expression) -> Unit, say: (String) -> Unit, closeSheet: () -> Unit,
): ToySession {
    val context = LocalContext.current
    val actorNow by rememberUpdatedState(actor)
    val session = remember(view) {
        val kv = try { PrefsKeyValue(context.getSharedPreferences("toy_prefs", android.content.Context.MODE_PRIVATE)) } catch (e: Exception) { MapToyKeyValue() }
        val store = try { PropStore(File(context.filesDir, "pack/props")) } catch (e: Exception) { null }
        ToySession(view, store, ToyPrefs(kv), { actorNow?.let(actorKey) }, { actorNow?.let(boldness) ?: 0f }, express, say,
            { p -> if (hapticsOn()) interactToyVibrate(context, p) }, closeSheet)
    }
    DisposableEffect(session, actor) {
        ToyHub.session = if (actor != null) session else null
        onDispose { if (ToyHub.session === session) ToyHub.session = null; session.end() }
    }
    return session
}

private fun interactToyVibrate(context: android.content.Context, p: HapticPattern) {
    try {
        val v = context.getSystemService(android.os.Vibrator::class.java)
        if (v != null && v.hasVibrator()) v.vibrate(android.os.VibrationEffect.createWaveform(p.timings, p.amplitudes, -1))
    } catch (e: Exception) { }
}

/**
 * The layer over the scene for the toy (under the chrome): it runs the session's frame, and while a toy is held it takes the touches: a finger
 * carries the toy (free), or, once it has snapped, pushes and pulls it along the entry's axis. A small bar over the dock has Remove, Drop and the
 * vibrator's switch and strength.
 */
@Composable
internal fun ToyTouchLayer(s: ToySession, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val redraw = s.version
    LaunchedEffect(s) {
        var last = 0L
        while (true) {
            withFrameNanos { n -> val dt = if (last == 0L) 0.016f else (n - last) / 1e9f; last = n; s.frame(dt) }
        }
    }
    val held = s.controller.held
    Box(modifier.fillMaxSize().onSizeChanged { s.width = it.width; s.height = it.height }
        .then(if (held) Modifier.pointerInput(s) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                s.down(down.position.x, down.position.y)
                var t = down.uptimeMillis
                do {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull() ?: break
                    val d = c.positionChange()
                    val dt = ((c.uptimeMillis - t).coerceAtLeast(1L)) / 1000f
                    t = c.uptimeMillis
                    if (d.x != 0f || d.y != 0f) { s.drag(c.position.x, c.position.y, d.x, d.y, dt); c.consume() }
                } while (c.pressed)
                s.up()
            }
        } else Modifier)) {
        if (held) ToyBar(s, Modifier.align(Alignment.BottomCenter).padding(bottom = 86.dp))
    }
}

@Composable
private fun ToyBar(s: ToySession, modifier: Modifier) {
    val c = s.controller
    val kind = c.item?.kind
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        WifeNote(when (c.phase) {
            ToyController.Phase.HELD -> if (kind?.insertable == true) "Bring the ${kind.label.lowercase()} to her. It snaps in near the opening." else "Hold it against her."
            ToyController.Phase.ENGAGED -> if (c.inserted) "Drag along it to push or pull. Let go and it stays." else "Drag toward her to push in."
            else -> ""
        })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (c.phase == ToyController.Phase.ENGAGED) WifeButton("Remove", { s.remove() })
            WifeButton("Drop", { s.drop() })
            if (kind?.vibrates == true || kind?.insertable == true) WifeChip(if (c.vibrating) "Buzz on" else "Buzz off", { s.setVibration(!c.vibrating, s.prefs.vibration) }, trailing = if (c.vibrating) "✓" else null)
        }
        if (c.vibrating) WifeSlider(c.intensity, { s.setVibration(true, it) }, Modifier.fillMaxWidth())
    }
}

/** The Toys tab's body: the tray, then the reactions' settings. */
@Composable
internal fun ToysPanel(s: ToySession?) {
    if (s == null) { WifeNote("Toys need a person in Interact mode."); return }
    @Suppress("UNUSED_VARIABLE") val redraw = s.version
    val items = s.items()
    WifeNote("Toys")
    if (items.isEmpty()) WifeNote("No toys yet. Import a pack that has props (Options, Mods, Import pack).")
    for (it in items) {
        val note = when {
            it.kind.insertable -> it.kind.label
            it.kind.vibrates -> it.kind.label + " (held against her)"
            else -> it.kind.label + " (listed, not usable)"
        }
        WifeButton("${it.label}: $note", { s.pick(it) }, Modifier.fillMaxWidth(), enabled = it.kind.usable)
    }
    WifeNote("Reactions")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (l in ReactionLevel.entries) WifeChip(l.label, { s.setLevel(l) }, trailing = if (s.prefs.level == l) "✓" else null)
    }
    WifeSwitchRow("Climax", s.prefs.climax, { s.setClimax(it) }, detail = "Sustained strokes build to a few seconds of full-body tension, then she relaxes")
    WifeSwitchRow("Her words", s.prefs.lines, { s.setLines(it) }, detail = "A short line now and then (the dirty talk level's style)")
    WifeNote("Pick a toy up, then touch the screen: it follows your finger and snaps in near the opening. Haptics (More) gives the ticks and the buzz.")
}
