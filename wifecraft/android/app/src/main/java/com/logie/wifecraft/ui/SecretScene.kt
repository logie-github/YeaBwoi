package com.logie.wifecraft.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.AnimArrays
import com.logie.wifecraft.anim.CheckedAnimation
import com.logie.wifecraft.anim.animationChoices
import com.logie.wifecraft.creator.StockingBook
import com.logie.wifecraft.creator.StockingSpec
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.engine.Thumbs
import com.logie.wifecraft.simulation.CommandResult
import com.logie.wifecraft.simulation.ConsentPolicy
import com.logie.wifecraft.simulation.HapticPattern
import com.logie.wifecraft.simulation.InteractAction
import com.logie.wifecraft.simulation.InteractCamera
import com.logie.wifecraft.simulation.InteractLayers
import com.logie.wifecraft.simulation.InteractMode
import com.logie.wifecraft.simulation.InteractPersona
import com.logie.wifecraft.simulation.InteractPrior
import com.logie.wifecraft.simulation.InteractReactions
import com.logie.wifecraft.simulation.InteractTool
import com.logie.wifecraft.simulation.InteractView
import com.logie.wifecraft.simulation.LookTarget
import com.logie.wifecraft.simulation.OutfitAct
import com.logie.wifecraft.simulation.Reaction
import com.logie.wifecraft.simulation.SecretCast
import com.logie.wifecraft.simulation.SecretClip
import com.logie.wifecraft.simulation.SecretKneel
import com.logie.wifecraft.simulation.SecretSettings
import com.logie.wifecraft.simulation.SecretStore
import com.logie.wifecraft.simulation.SecretVoid
import com.logie.wifecraft.simulation.StrokeGate
import com.logie.wifecraft.simulation.TouchHit
import com.logie.wifecraft.simulation.TouchKind
import com.logie.wifecraft.simulation.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// The hidden mode (README, "Hidden mode"): one character kneeling in a void, to orbit, zoom and touch. The title's secret gesture opens it
// (simulation/SecretGesture.kt, ui/SecretGestureLayer.kt); nothing in a menu names it. Her pose, light, settings and cast choice are
// simulation/SecretModel.kt (JVM-tested); this file is the screen: the GL view with SecretFrame as its driver, the Interact touch layer
// (ui/InteractMulti.kt) and camera (simulation/InteractCamera.kt) as the scenes use them, a small dock and the character row.

/** What the screen needs from the app (MainActivity builds it): the view, the cast, how to dress her, her consent and persona, the clips. */
internal class SecretHost(
    val view: SceneView,
    val roster: List<Actor>,
    val isStarter: (Actor) -> Boolean,
    val dress: (Actor, () -> Unit) -> Unit,
    val cancelDress: (String) -> Unit,
    val withGear: (Actor, Boolean) -> Actor,
    val gearOn: (Actor) -> Boolean,
    val keepGear: (Actor, Boolean) -> Unit,
    val clips: List<CheckedAnimation>,
    val policy: (Actor) -> ConsentPolicy,
    val persona: (Actor) -> InteractPersona,
    val portraits: Thumbs?,
    val portraitActor: (Actor) -> Actor,
    val portraitDisplay: (Actor) -> Int,
    val prefs: android.content.SharedPreferences?,
    val store: SecretStore,
)

private enum class SecretTab(val label: String) { VIEW("View"), OUTFIT("Outfit"), TELL("Tell her"), SETTINGS("Settings") }

internal const val SECRET_KEY = "secret:npc"

@Composable
internal fun SecretScreen(host: SecretHost, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = host.view
    val key = SECRET_KEY
    var settings by remember { mutableStateOf(SecretSettings.parse(host.store.read())) }
    fun keep(next: SecretSettings) { settings = next; host.store.write(next.toJson()) }
    val entries = remember(host.roster) { host.roster.map { SecretCast.Entry(it.id, host.isStarter(it), it.appearance.sex == Sex.FEMALE) } }
    var selected by remember(host.roster) { mutableStateOf(SecretCast.pick(entries, settings.character)) }
    var gear by remember { mutableStateOf(emptyMap<String, Actor>()) }
    val actor: Actor? = host.roster.firstOrNull { it.id == selected }?.let { gear[it.id] ?: it }
    var ready by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf<SecretTab?>(null) }
    var typed by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var kneel by remember { mutableStateOf(SecretKneel.procedural()) }
    var kneelNote by remember { mutableStateOf("a pose built from the skeleton") }
    var stockingBook by remember(host.prefs) {
        mutableStateOf(StockingBook.fromJson(try { host.prefs?.getString("stockings", null) } catch (e: Exception) { null }))
    }
    val camera = remember { InteractCamera() }
    val mode = remember { InteractMode(System.nanoTime()) }
    val frame = remember { SecretFrameState() }
    val policy: ConsentPolicy? = remember(actor?.id) { actor?.let { host.policy(it) } }
    val actorNow by androidx.compose.runtime.rememberUpdatedState(actor)

    fun say(text: String) { caption = (caption?.first ?: 0) + 1 to text }
    fun vibrate(p: HapticPattern) {
        if (!mode.haptics) return
        try {
            val v = context.getSystemService(android.os.Vibrator::class.java)
            if (v != null && v.hasVibrator()) v.vibrate(android.os.VibrationEffect.createWaveform(p.timings, p.amplitudes, -1))
        } catch (e: Exception) { }
    }
    fun layer(anim: Anim?, ms: Long) {
        if (anim == null) return
        val arrays = AnimArrays.of(anim)
        view.queueEvent { view.glBoneLayer(key, arrays) }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ view.queueEvent { view.glBoneLayer(key, null) } }, ms)
    }
    fun persona(): InteractPersona = actorNow?.let { host.persona(it) } ?: InteractPersona()
    fun play(r: Reaction) {
        layer(InteractLayers.layer(r), (InteractLayers.SECONDS * 1000f).toLong())
        r.line?.let { say(it) }
        vibrate(r.haptic)
        tick++
    }
    val stock = rememberStockFabric(view, null, mode, host.prefs, System.nanoTime(), actor?.id) { key }
    SideEffect {
        stock.env = StockEnv({ actorNow?.id }, { stockingBook }, { key }, { persona() }, { actorNow?.name ?: "She" }, { play(it) },
            { _, a, ms -> layer(a, ms) }, { vibrate(it) }, { _, line -> say(line) }, { say(it) }, { tick++ }, { policy }, { it })
    }
    fun applyStockings(spec: StockingSpec?) {
        val id = actorNow?.id ?: return
        stockingBook = stockingBook.with(id, spec)
        try { host.prefs?.edit()?.putString("stockings", stockingBook.toJson().toString())?.apply() } catch (e: Exception) { }
        view.setStockings(key, spec?.toFloats())
        stock.pushLook(id, true)
    }
    fun setGear(on: Boolean) {
        val a = actorNow ?: return
        val next = host.withGear(a, on)
        gear = gear + (a.id to next)
        host.keepGear(next, on)
        say(if (on) "Her gear is on." else "Her gear is off.")
    }
    fun perform(a: InteractAction) {
        when (a) {
            is InteractAction.Look -> layer(InteractLayers.look(a.target), 3700L)
            is InteractAction.Express -> layer(InteractLayers.expression(a.expression), (InteractLayers.SECONDS * 1000f).toLong())
            is InteractAction.Outfit -> when (a.act) {
                OutfitAct.GEAR_ON -> setGear(true)
                OutfitAct.GEAR_OFF -> setGear(false)
                OutfitAct.STOCKINGS_ON -> applyStockings(StockingSpec.fromWords(a.arg.orEmpty(), stockingBook.of(actorNow?.id ?: "") ?: StockingSpec()))
                OutfitAct.STOCKINGS_OFF -> applyStockings(null)
            }
            else -> say("She stays kneeling.")
        }
    }
    fun result(res: CommandResult) {
        val id = actorNow?.id ?: return
        when (res) {
            is CommandResult.Do -> perform(res.action)
            is CommandResult.Declined -> { say(res.line); vibrate(InteractReactions.haptic(com.logie.wifecraft.simulation.TouchRegion.NONE, Verdict.DECLINE)) }
            is CommandResult.Hesitates -> { say(res.line); mode.later() }
            CommandResult.PullAllowed, is CommandResult.PullDeclined, is CommandResult.PullHesitates -> stock.pullResult(id, res)
            CommandResult.AwaitSpot -> say("She stays kneeling.")
            is CommandResult.Unknown -> say("She doesn't know what that means.")
        }
        tick++
    }
    fun command(a: InteractAction) { result(mode.command(a, policy, persona())) }
    fun touch(hit: TouchHit, kind: TouchKind): Reaction? {
        val r = mode.touch(hit, kind, policy, persona()) ?: return null
        play(r)
        view.lifeTouch(key, hit, kind, r)
        return r
    }
    fun stroke(step: StrokeGate.Step) {
        val r = step.reaction
        if (r != null) play(r) else vibrate(InteractReactions.haptic(step.region, Verdict.ALLOW, TouchKind.STROKE))
    }

    // the void: nothing of a place, nobody else, the frame driver, the light
    DisposableEffect(view) {
        view.removeAll(); view.clearProps(); view.clearLocation(); view.showCity(false)
        view.light(settings.light.values())
        view.driver = SecretFrame(view, key, camera, frame)
        camera.restart()
        onDispose {
            view.driver = null
            mode.leave()
            host.cancelDress(key)
            view.releaseTouch(key)
            view.queueEvent { view.glBoneLayer(key, null) }
            view.removeAll(); view.clearLocation()
            view.frame = null
        }
    }
    LaunchedEffect(settings.light) { view.light(settings.light.values()) }
    androidx.compose.runtime.DisposableEffect(view, ready, settings.portrait) { view.portraitOn(key, settings.portrait, 17L); onDispose { view.portraitOff(key) } }
    SideEffect { frame.breathing = settings.breathing; frame.dof = settings.depthOfField; frame.panel = if (tab != null) 0.45f else 0.24f }

    // the kneeling pose: an imported clip of the library when it has one, else the pose built from the skeleton
    LaunchedEffect(host.clips) {
        val made = withContext(Dispatchers.Default) {
            val choices = animationChoices(host.clips).filter { it.participantCount == 1 && it.playable }
            val clips = choices.map { SecretClip(it.id, it.name, it.group, 1, it.preview.inspection.type, it.preview.clip.duration) }
            val pick = SecretKneel.choose(clips)
            val chosen = choices.firstOrNull { it.id == pick.clip?.id }
            if (chosen != null) SecretKneel.held(chosen.preview.clip, chosen.preview.inspection.type) to "the library's clip ${chosen.name}"
            else SecretKneel.procedural() to "a pose built from the skeleton"
        }
        kneel = made.first; kneelNote = made.second
    }
    LaunchedEffect(kneel) { frame.dropUnits = withContext(Dispatchers.Default) { SecretKneel.floorDrop(kneel) } }

    // her, dressed; the pose and her stockings put on whenever she is
    LaunchedEffect(actor) {
        val a = actor ?: return@LaunchedEffect
        ready = false
        host.dress(a) { ready = true }
    }
    LaunchedEffect(ready, kneel, actor?.id) {
        val a = actor ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        view.playAnim(key, kneel)
        view.setStockings(key, stockingBook.of(a.id)?.toFloats())
        stock.pushLook(a.id, true)
        tick++
    }
    LaunchedEffect(actor?.id) {
        val a = actor ?: return@LaunchedEffect
        if (mode.on) { mode.leave() }
        mode.enter(a.id, InteractPrior(false, "", 0))
        stock.entered(a.id)
        tick++
    }
    LaunchedEffect(caption) { if (caption != null) { delay(3600); caption = null } }

    val dockFade = remember { Animatable(1f) }
    LaunchedEffect(ready) { if (ready) dockFade.animateTo(0f, tween(700)) }
    BackHandler { if (tab != null) tab = null else onBack() }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0E))) {
        AndroidView({ (view.parent as? ViewGroup)?.removeView(view); view }, Modifier.fillMaxSize())
        if (settings.floorShadow) SecretShadow(frame)
        if (actor != null && policy != null) {
            InteractTouchLayer(mode, tick, camera, view, key, policy, { persona() }, { hit, kind -> touch(hit, kind) }, { step -> stroke(step) },
                { _, _, _, _ -> }, Modifier.fillMaxSize(), stock.host)
        }
        caption?.let { (_, text) ->
            Text(text, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 14.dp, start = 24.dp, end = 24.dp)
                .clip(RoundedCornerShape(WifeTheme.buttonRadius)).background(Color(0xCC101014)).padding(horizontal = 12.dp, vertical = 6.dp),
                style = WifeTheme.bodyStyle(), textAlign = TextAlign.Center)
        }
        if (actor == null) Text("Nobody to show yet: gather the game's files first.", Modifier.align(Alignment.Center), style = WifeTheme.bodyStyle(), textAlign = TextAlign.Center)
        else if (!ready) Text("Preparing ${actor.name}…", Modifier.align(Alignment.Center), style = WifeTheme.captionStyle(), textAlign = TextAlign.Center)
        // the dock: it stays; Back always works. It takes its own touches, so none of them reach the touch layer under it.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 10.dp, vertical = 8.dp)
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val open = tab
            if (open != null) {
                WifePanel(Modifier.fillMaxWidth().heightIn(max = 250.dp), padding = 10.dp) {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        @Suppress("UNUSED_VARIABLE") val redraw = tick
                        when (open) {
                            SecretTab.VIEW -> SecretViewSheet(camera, tick)
                            SecretTab.OUTFIT -> SecretOutfitSheet(
                                actor?.id, actor?.let { host.gearOn(it) } ?: false, { on -> command(InteractAction.Outfit(if (on) OutfitAct.GEAR_ON else OutfitAct.GEAR_OFF)) },
                                actor?.let { stockingBook.of(it.id) }, { s -> applyStockings(s) }, stock, mode, tick)
                            SecretTab.TELL -> SecretTellSheet(typed, { typed = it }, { text -> result(mode.tell(text, policy, persona())) }, { a -> command(a) })
                            SecretTab.SETTINGS -> SecretSettingsSheet(settings, { keep(it) }, mode, { tick++ }, kneelNote)
                        }
                    }
                }
            }
            if (host.roster.size > 1) SecretPicker(host, selected) { id -> selected = id; keep(settings.copy(character = id)) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs),
                verticalAlignment = Alignment.CenterVertically) {
                WifeButton("Back", onBack)
                WifeButton(if (mode.tool == InteractTool.TOUCH) "Touch" else "Look", { mode.toggleTool(); tick++ })
                for (t in SecretTab.entries) WifeChip(t.label, { tab = if (tab == t) null else t }, trailing = if (tab == t) "✓" else null)
            }
        }
        if (dockFade.value > 0.001f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dockFade.value)))
    }
}

/** The floor's shadow under her knees, drawn over the picture from the camera the frame last set: soft, darkest in a ring round her, nothing under the floor. */
@Composable
private fun SecretShadow(frame: SecretFrameState) {
    var clock by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { clock = it } }
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val redraw = clock
        if (!frame.camSet) return@Canvas
        val ring = secretShadowRing(frame.cam, size.width, size.height, SecretVoid.SHADOW_RADIUS * frame.height)
        if (ring.size < 3) return@Canvas
        val c = secretProject(frame.cam, size.width, size.height, 0f, 0f, 0f) ?: return@Canvas
        val path = Path().apply { moveTo(ring[0].x, ring[0].y); for (i in 1 until ring.size) lineTo(ring[i].x, ring[i].y); close() }
        val r = ring.maxOf { kotlin.math.hypot(it.x - c.x, it.y - c.y) }.coerceAtLeast(1f)
        val dark = SecretVoid.SHADOW_DARK
        drawPath(path, Brush.radialGradient(0f to Color.Black.copy(alpha = dark * 0.15f), 0.4f to Color.Black.copy(alpha = dark * 0.8f), 1f to Color.Transparent, center = c, radius = r))
    }
}

/** The cast, one card each, the one shown lit; remembered. */
@Composable
private fun SecretPicker(host: SecretHost, selected: String?, onPick: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(host.roster, key = { it.id }) { a ->
            val preview = remember(a) { host.portraitActor(a) }
            val display = remember(a) { host.portraitDisplay(a) }
            val portraits = host.portraits
            var picture by remember(preview, portraits, display) {
                mutableStateOf<ImageBitmap?>(
                    if (display > 0) portraits?.cached(PlaceKind.CREATURE, display, 192)?.asImageBitmap()
                    else portraits?.cachedNpc(preview.appearance, a.id.hashCode(), 192)?.asImageBitmap())
            }
            DisposableEffect(preview, portraits, display) {
                var active = true
                val ticket = when {
                    picture != null -> null
                    display > 0 -> portraits?.request(PlaceKind.CREATURE, display, 192) { if (active) picture = it?.asImageBitmap() }
                    else -> portraits?.requestNpc(preview.appearance, a.id.hashCode(), 192) { if (active) picture = it?.asImageBitmap() }
                }
                onDispose { active = false; ticket?.cancel() }
            }
            WifeCard(a.name, picture, 60.dp, a.id == selected, { onPick(a.id) })
        }
    }
}
