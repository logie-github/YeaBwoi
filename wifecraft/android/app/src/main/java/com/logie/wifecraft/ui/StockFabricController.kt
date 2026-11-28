package com.logie.wifecraft.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.audio.StockSound
import com.logie.wifecraft.creator.StockingBook
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.simulation.CommandResult
import com.logie.wifecraft.simulation.ConsentPolicy
import com.logie.wifecraft.simulation.FabricState
import com.logie.wifecraft.simulation.HapticPattern
import com.logie.wifecraft.simulation.InteractFollow
import com.logie.wifecraft.simulation.InteractLayers
import com.logie.wifecraft.simulation.InteractMode
import com.logie.wifecraft.simulation.InteractPersona
import com.logie.wifecraft.simulation.LiveScene
import com.logie.wifecraft.simulation.LookTarget
import com.logie.wifecraft.simulation.PinchEvent
import com.logie.wifecraft.simulation.PinchFeedback
import com.logie.wifecraft.simulation.PinchModelLine
import com.logie.wifecraft.simulation.PinchReactions
import com.logie.wifecraft.simulation.PinchStage
import com.logie.wifecraft.simulation.PinchTracker
import com.logie.wifecraft.simulation.Reaction
import com.logie.wifecraft.simulation.StockOptions
import com.logie.wifecraft.simulation.TouchHit
import com.logie.wifecraft.simulation.TouchKind
import com.logie.wifecraft.simulation.TouchRegion
import com.logie.wifecraft.simulation.Verdict
import com.logie.wifecraft.story.Vec3
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

// The stockings' fabric in Interact mode, kept out of SceneSessionScreen (its LiveSceneScreen is at the JVM's method size limit): the pinch's host
// for the touch layer, the solver's polled state, the options, the sound, the haptics, her reactions and Come here. The screen gives it what it
// owns through [StockEnv] (set each composition) and calls it in one line at each place.

/** What the screen owns that the controller needs (reassigned every composition, so it is never stale). */
class StockEnv(
    val actor: () -> String?,
    val book: () -> StockingBook,
    val actorKey: (String) -> String,
    val persona: (String) -> InteractPersona,
    val name: (String) -> String,
    val play: (Reaction) -> Unit,
    val layer: (String, Anim?, Long) -> Unit,
    val vibrate: (HapticPattern) -> Unit,
    val say: (String, String) -> Unit,
    val toast: (String) -> Unit,
    val tick: () -> Unit,
    val consent: () -> ConsentPolicy?,
    val toLocal: (Vec3) -> Vec3,
)

class StockFabricController(private val view: SceneView, private val live: LiveScene?, private val mode: InteractMode, private val prefs: android.content.SharedPreferences?, seed: Long) {
    var env: StockEnv? = null
    val host = PinchHost()
    val tracker = PinchTracker()
    val sound = StockSound()
    private val rnd = Random(seed + 77)
    private var region = TouchRegion.THIGH
    var pinching by mutableStateOf(false)
    var fabric by mutableStateOf(FabricState.REST)

    init {
        mode.stock = StockOptions.fromJson(try { prefs?.getString("stockopts", null) } catch (e: Exception) { null })
        host.verdict = { hit -> verdict(hit) }
        host.worn = { env?.let { e -> e.actor()?.let { e.book().of(it) } } != null }
        host.onGrab = { pinching = true; tracker.begin(fabric.runs) }
        host.onEnd = { pinching = false }
    }

    /** Pushes the fabric's give and look to the engine for [id] (the side light only while [sideOn]). */
    fun pushLook(id: String, sideOn: Boolean = true) {
        val e = env ?: return
        val spec = e.book().of(id) ?: return
        val look = mode.stock.look(spec.denier)
        if (!sideOn) look[3] = 0f
        view.stockLook(e.actorKey(id), look)
    }

    fun setOptions(o: StockOptions) {
        val before = mode.stock
        mode.stock = o
        try { prefs?.edit()?.putString("stockopts", o.toJson().toString())?.apply() } catch (e: Exception) { }
        env?.actor()?.let { pushLook(it) }
        if (o.sound && !before.sound && env?.actor() != null) sound.start() else if (!o.sound) sound.stop()
        env?.tick?.invoke()
    }

    fun entered(id: String) { pushLook(id); if (mode.stock.sound) sound.start() }

    fun left(id: String) {
        sound.stop(); pinching = false; host.at = null
        env?.let { view.stockRelease(it.actorKey(id)) }
        pushLook(id, sideOn = false)
    }

    fun restore(id: String) { val e = env ?: return; view.stockUndo(e.actorKey(id), false) { ok -> e.toast(if (ok) "The latest tear is mended." else "Nothing to restore."); e.tick() } }
    fun repairAll(id: String) { val e = env ?: return; view.stockUndo(e.actorKey(id), true) { _ -> e.toast("Every tear is mended."); e.tick() } }

    /** A pull is a touch act (TouchKind.PULL): a refusal or a hesitation is played and the grab never starts. */
    private fun verdict(hit: TouchHit): Verdict {
        val e = env ?: return Verdict.DECLINE
        val id = e.actor() ?: return Verdict.DECLINE
        val r = mode.touch(hit, TouchKind.PULL, e.consent(), e.persona(id)) ?: return Verdict.DECLINE
        region = hit.region
        if (r.verdict != Verdict.ALLOW) e.play(r)
        e.tick()
        return r.verdict
    }

    /** The persuasion's outcome for a pull. */
    fun pullResult(id: String, res: CommandResult) {
        val e = env ?: return
        when (res) {
            CommandResult.PullAllowed -> { e.say(id, "All right... but be careful with them."); e.toast("She allows it. Hold a stocking, then pull.") }
            is CommandResult.PullDeclined -> { e.say(id, res.line); e.vibrate(com.logie.wifecraft.simulation.InteractReactions.haptic(TouchRegion.NONE, Verdict.DECLINE)) }
            is CommandResult.PullHesitates -> e.say(id, res.line)
            else -> Unit
        }
    }

    internal fun react(stage: PinchStage, scope: kotlinx.coroutines.CoroutineScope) {
        val e = env ?: return
        val id = e.actor() ?: return
        val persona = e.persona(id)
        val pr = PinchReactions.react(stage, region, persona, rnd)
        if (pr.lookDown) e.layer(id, InteractLayers.look(LookTarget.DOWN), 3700L)
        else e.layer(id, InteractLayers.layer(pr.reaction), (InteractLayers.SECONDS * 1000f).toLong())
        val template = pr.reaction.line
        val engine = com.logie.wifecraft.dialogue.DialogueRuntime.engine?.takeIf { it.ready }
        if (template != null && engine != null) {
            val nm = e.name(id)
            scope.launch {
                val raw = withTimeoutOrNull(5000L) { engine.complete(PinchModelLine.request(id, nm, persona, stage, region)) }
                e.say(id, PinchModelLine.line(raw, nm) ?: template)
            }
        } else if (template != null) e.say(id, template)
        e.tick()
    }

    internal fun onState(st: FabricState, scope: kotlinx.coroutines.CoroutineScope) {
        fabric = st; host.state = st
        val e = env ?: return
        for (ev in tracker.update(st, SystemClock.uptimeMillis())) {
            PinchFeedback.of(ev)?.let { e.vibrate(it) }
            when (ev) {
                PinchEvent.Tear -> if (mode.sound) sound.rip()
                is PinchEvent.Stage -> react(ev.stage, scope)
                else -> Unit
            }
        }
        if (mode.sound) sound.friction(st.tension, tracker.slope, st.grabbed)
    }

    /** Come here and Follow me: she walks to the floor a yard in front of the orbit camera. */
    fun comeToCamera(id: String, cam: com.logie.wifecraft.simulation.InteractCamera) {
        val e = env ?: return
        val spot = InteractFollow.standPoint(cam.eyeX, cam.eyeY, cam.atX, cam.atY)
        if (spot == null) { e.toast("She is already beside you."); return }
        view.groundAt(spot.first, spot.second) { gz ->
            val dest = e.toLocal(Vec3(spot.first, spot.second, gz ?: cam.atZ))
            live?.order { sim -> sim.endHold(id); if (sim.walk(id, dest)) null else "There's no clear path to you." }   // (the hidden mode has no scene to walk in: null)
        }
    }
}

/** The controller, its poll of the solver while Interact mode is on on [actor], and the sound stopped when the app pauses. */
@Composable
fun rememberStockFabric(view: SceneView, live: LiveScene?, mode: InteractMode, prefs: android.content.SharedPreferences?, seed: Long, actor: String?, actorKey: (String) -> String): StockFabricController {
    val ctl = remember(live) { StockFabricController(view, live, mode, prefs, seed) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, ctl) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) ctl.sound.stop() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); ctl.sound.stop() }
    }
    val cb = rememberUpdatedState<(FabricState) -> Unit> { ctl.onState(it, scope) }
    LaunchedEffect(actor, view) {
        val id = actor ?: return@LaunchedEffect
        val key = actorKey(id)
        while (true) {
            view.stockState(key) { arr -> FabricState.of(arr)?.let { cb.value(it) } }
            delay(if (ctl.pinching || ctl.fabric.tension > 0.01f) 45L else 250L)
        }
    }
    return ctl
}
