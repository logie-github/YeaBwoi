package com.logie.wifecraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onSizeChanged
import com.logie.wifecraft.dialogue.ChatRelationship
import com.logie.wifecraft.simulation.ChatCamera
import com.logie.wifecraft.simulation.ChatZoom
import com.logie.wifecraft.simulation.SpeechBubbleModel
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logie.wifecraft.data.DialogueHost
import com.logie.wifecraft.data.DialogueUiState
import com.logie.wifecraft.dialogue.ChatLength
import com.logie.wifecraft.dialogue.ChatWait
import com.logie.wifecraft.dialogue.ChatSession
import com.logie.wifecraft.dialogue.ChatSetup
import com.logie.wifecraft.dialogue.ChatTopics
import com.logie.wifecraft.dialogue.DialogueEngine
import com.logie.wifecraft.simulation.ChatEvent
import com.logie.wifecraft.simulation.ChatMode
import com.logie.wifecraft.simulation.ChatPhase
import com.logie.wifecraft.simulation.JoiSubtitles
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Chat mode's screens: the setup sheet (Race, Sex, Class, Name: Start, Cancel) and the layer over the live scene while the camera is in
// front of her (her lines as subtitles, the message log, topic chips, the text field, the length and Leave). The rules are in
// dialogue/ChatSetup.kt, ChatSession.kt and simulation/ChatMode.kt (JVM-tested); this file is compiled by CI only.

/** The setup sheet: choose Race, Sex, Class and a Name (the dice: a random name of the race), then Start; Cancel returns. */
@Composable
fun ChatSetupSheet(setup: ChatSetup, npcName: String, onStart: () -> Unit, onCancel: () -> Unit) {
    var redraw by remember(setup) { mutableIntStateOf(0) }
    var typed by remember(setup) { mutableStateOf(setup.name) }
    @Suppress("UNUSED_VARIABLE") val seen = redraw
    Overlay(onCancel) {
        WifeWindow(
            "Chat with $npcName", onCancel, Modifier.widthIn(min = 280.dp, max = 520.dp).heightIn(max = 600.dp).padding(WifeTheme.gapL),
            subtitle = "Who are you, to her?",
            footer = {
                WifeButton("Cancel", onCancel)
                // the last choice filled in: one tap goes on as that person (the rest of the sheet changes it first)
                val name = setup.name.trim()
                WifeButton(if (setup.remembered && name.isNotEmpty()) "Continue as $name" else "Start", onStart, enabled = setup.canStart, kind = WifeButtonKind.PRIMARY)
            },
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                WifeSectionHeader("Race")
                for (row in setup.races.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                        for (r in row) WifeChip(r.name, { setup.pickRace(r.id); redraw++ }, trailing = if (setup.raceId == r.id) "✓" else null)
                    }
                }
                setup.faction?.let { WifeNote("Faction: ${it.label}") }
                WifeSectionHeader("Sex")
                Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                    WifeChip("Woman", { setup.pickSex(true); redraw++ }, trailing = if (setup.female == true) "✓" else null)
                    WifeChip("Man", { setup.pickSex(false); redraw++ }, trailing = if (setup.female == false) "✓" else null)
                }
                WifeSectionHeader("Class")
                for (row in setup.classes.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                        for (c in row) WifeChip(c.name, { setup.pickClass(c.id); redraw++ }, trailing = if (setup.classId == c.id) "✓" else null)
                    }
                }
                WifeSectionHeader("Name")
                Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS), verticalAlignment = Alignment.CenterVertically) {
                    WowTextField(typed, { typed = it.take(ChatSetup.MAX_NAME); setup.typeName(typed); redraw++ }, "Your name", Modifier.weight(1f))
                    WifeButton("Random name", { setup.randomName()?.let { typed = it }; redraw++ }, enabled = setup.raceId != null)
                }
                WifeSectionHeader("Who are you to $npcName?")
                RelationshipPicker(setup.relationship, { setup.pickRelationship(it); redraw++ }, { setup.typeRelationship(it); redraw++ })
                val missing = setup.missing()
                if (missing.isNotEmpty()) WifeNote("Still to choose: ${missing.joinToString(", ")}.")
                else if (setup.remembered) WifeNote("Your last choice is filled in: change anything above, or go on as ${setup.name.trim()}.")
            }
        }
    }
}

/** "Who are you to her?": Stranger, Friend, Wife, Husband or Custom (a short text, quoted to her as how they are known). */
@Composable
fun RelationshipPicker(current: ChatRelationship, onKind: (ChatRelationship.Kind) -> Unit, onText: (String) -> Unit) {
    var typed by remember { mutableStateOf(current.custom) }
    Column(verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
            for (k in ChatRelationship.Kind.entries) WifeChip(k.label, { onKind(k) }, trailing = if (current.kind == k) "✓" else null)
        }
        if (current.kind == ChatRelationship.Kind.CUSTOM)
            WowTextField(typed, { typed = it.take(ChatRelationship.MAX_CUSTOM); onText(typed) }, "e.g. my sister's old rival", Modifier.fillMaxWidth())
    }
}

/**
 * Runs Chat mode's clock while it talks: about ten steps a second; a small gesture when one is due ([onGesture], with whether she is
 * speaking now: the reply is being written or is on screen), and a redraw ([tick]).
 */
@Composable
fun ChatRunner(mode: ChatMode, running: Boolean, onGesture: (speaking: Boolean) -> Unit, tick: () -> Unit) {
    val gesture by rememberUpdatedState(onGesture)
    val redraw by rememberUpdatedState(tick)
    LaunchedEffect(mode, running) {
        if (!running) return@LaunchedEffect
        var last = android.os.SystemClock.uptimeMillis()
        var seenLines = 0
        var speakUntil = 0L
        while (isActive) {
            delay(100)
            val now = android.os.SystemClock.uptimeMillis()
            val dt = (now - last) / 1000f
            last = now
            val s = mode.session
            if (s != null) {
                val log = s.log
                if (log.size != seenLines) {
                    seenLines = log.size
                    val l = log.lastOrNull()
                    if (l != null && !l.fromYou) speakUntil = now + (JoiSubtitles.hold(l.text, "steady") * 1000).toLong()
                }
            }
            val speaking = (s?.typing == true) || now < speakUntil
            mode.update(dt, speaking)
            for (e in mode.drain()) when (e) { is ChatEvent.GestureDue -> gesture(e.speaking) }
            redraw()
        }
    }
}

/**
 * The layer over the live scene while Chat mode runs. The chrome's plan ([plan], ScreenPlan.kt) says where everything goes: the text
 * field, Send and Leave in the dock slot; the row over it (the view, who you are, the reply length, the log, the topic chips); and the
 * last line of the talk in the line over that. The whole log is the chrome's sheet ([logOpen], [onLog]; [ChatLogSheet]). Her reply is the
 * world's chat bubble over her head ([onSpeech]); nothing here covers her face (the camera frames her above the stack: [ChatCamera.panelInset]).
 * With no model it says so ([ChatPhase.NEEDS_MODEL]) and offers Download it, Try again and Back. [engine]: the runtime's model, asked at each send.
 */
@Composable
fun ChatLayer(
    mode: ChatMode, tick: Int, engine: () -> DialogueEngine?,
    onDownloadModel: (() -> Unit)?, onRetry: () -> Unit, onLeave: () -> Unit, onChanged: () -> Unit,
    plan: List<ScreenPlaced>, logOpen: Boolean, onLog: () -> Unit,
    modifier: Modifier = Modifier,
    /** The camera: the layer zooms and turns it (pinch, drag, the view chip) and tells it how much of the screen the bottom stack covers. */
    camera: ChatCamera? = null,
    /** Her words as the world's chat bubble over her head (null: none): what she is saying now, or the reply being written. */
    onSpeech: (String?) -> Unit = {},
) {
    @Suppress("UNUSED_VARIABLE") val redrawn = tick
    val session = mode.session
    var zoomUi by remember(camera) { mutableFloatStateOf(camera?.zoom ?: ChatZoom.DEFAULT) }
    var relOpen by remember(session) { mutableStateOf(false) }
    // the touches on the picture are taken here: the scene's own controls are under it, hidden while the talk runs; two fingers pinch
    // the zoom, one finger drags the view round her a little, a double tap straightens it
    BoxWithConstraints(modifier.fillMaxSize()
        .pointerInput(camera) { detectTapGestures(onDoubleTap = { camera?.orbit = 0f }) }
        .pointerInput(camera) {
            detectTransformGestures { _, pan, zoom, _ ->
                val c = camera ?: return@detectTransformGestures
                if (zoom != 1f) { c.zoom = ChatZoom.pinch(c.zoom, zoom); zoomUi = c.zoom }
                if (pan.x != 0f) c.orbit = ChatZoom.drag(c.orbit, pan.x, size.width.toFloat())
            }
        }) {
        val screenHeight = maxHeight.value
        SideEffect { camera?.panelInset = ChatZoom.clampInset(ScreenPlan.bottomShare(plan, screenHeight)) }
        if (session == null) return@BoxWithConstraints
        if (mode.phase == ChatPhase.NEEDS_MODEL) {
            ChatNeedsModel(onDownloadModel, onRetry, onLeave, Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        var local by remember(session) { mutableIntStateOf(0) }
        @Suppress("UNUSED_VARIABLE") val seen = local
        val scope = rememberCoroutineScope()
        val engineNow by rememberUpdatedState(engine)
        val log = session.log
        // while she writes, the words come in: redraw a few times a second
        LaunchedEffect(session) {
            while (isActive) { delay(120); if (session.typing) local++ }
        }
        fun send(text: String?) {
            if (session.typing) return
            val e = engineNow()
            scope.launch {
                val reply = session.say(text, e)
                if (reply == null && engineNow() == null) mode.modelLost()
                local++; onChanged()
            }
            local++
        }
        // her last line as the world's bubble for its reading time
        val last = log.lastOrNull()?.takeIf { !it.fromYou }
        var subtitle by remember(session) { mutableStateOf<String?>(null) }
        LaunchedEffect(last?.text, log.size) {
            if (last != null) { subtitle = last.text; delay((SpeechBubbleModel.holdSeconds(last.text) * 1000).toLong()); subtitle = null }
        }
        val typing = session.typing
        val shown = if (typing) session.partial.ifBlank { "…" } else subtitle
        SideEffect { onSpeech(shown) }   // the world's chat bubble over her head shows it
        // ---- the line over the row: the last thing said, one line (the log's sheet has the rest)
        At(ScreenPlan.find(plan, "context")) {
            val line = when {
                typing -> ChatWait.thinking(session.npcName, System.currentTimeMillis() - session.typingSince, 1 + (local / 3) % 3)
                session.quiet -> "${session.npcName} says nothing."
                log.isEmpty() -> "Say something, or choose a topic."
                else -> log.last().let { l -> (if (l.fromYou) session.youName else session.npcName) + ": " + l.text }
            }
            HudLine(line, Modifier.fillMaxSize(), centered = true)
        }
        // ---- the row: the view, who you are, how long she answers, the log, then the topics (it scrolls)
        At(ScreenPlan.find(plan, "row")) {
            Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs),
                verticalAlignment = Alignment.CenterVertically) {
                if (camera != null) {
                    val presets = ChatZoom.PRESETS
                    val near = presets.minBy { kotlin.math.abs(it.zoom - zoomUi) }
                    val label = if (kotlin.math.abs(near.zoom - zoomUi) < 0.03f) near.label else "Custom"
                    WifeChip("View: $label", { val n = presets[(presets.indexOf(near) + 1) % presets.size]; camera.zoom = n.zoom; zoomUi = n.zoom }, trailing = null)
                }
                WifeChip("You: " + (session.relationship.described()?.replace("\"", "") ?: "Stranger"), { relOpen = true }, trailing = "✎")
                val lengths = ChatLength.entries
                WifeChip("Reply: ${session.length.label}", { session.length = lengths[(lengths.indexOf(session.length) + 1) % lengths.size]; local++ }, trailing = null)
                WifeChip("Log", onLog, trailing = if (logOpen) "✓" else null)
                for (c in ChatTopics.CHIPS) {
                    WifeChip(c.label, {
                        val on = session.toggleTopic(c.topic)
                        // a topic chosen with nothing typed: she brings it up herself
                        if (on) send(null)
                        local++
                    }, trailing = if (session.topicOn(c.topic)) "✓" else null)
                }
            }
        }
        // ---- the dock: the text field, Send, Leave
        At(ScreenPlan.find(plan, "dock")) {
            var typed by remember(session) { mutableStateOf("") }
            val sendNow = { val t = typed; if (t.isNotBlank() && !session.typing) { typed = ""; send(t) } }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(ScreenPlan.GAP.dp), verticalAlignment = Alignment.CenterVertically) {
                WowTextField(typed, { typed = it.take(300) }, "Say something to ${session.npcName}", Modifier.weight(1f).fillMaxHeight(), onSend = sendNow)
                ActionSlot("Send", false, typed.isNotBlank() && !typing, sendNow, Modifier.width(64.dp).fillMaxHeight())
                ActionSlot("Leave", false, true, onLeave, Modifier.width(64.dp).fillMaxHeight())
            }
        }
        if (relOpen) Overlay({ relOpen = false }) {
            WifeWindow("Who are you to ${session.npcName}?", { relOpen = false }, Modifier.widthIn(min = 280.dp, max = 460.dp).padding(WifeTheme.gapL),
                subtitle = "It applies from her next reply.",
                footer = { WifeButton("Done", { relOpen = false }, kind = WifeButtonKind.PRIMARY) },
            ) {
                RelationshipPicker(session.relationship,
                    { k -> session.changeRelationship(session.relationship.copy(kind = k)); local++ },
                    { t -> session.changeRelationship(session.relationship.copy(custom = t)); local++ })
            }
        }
    }
}

/** The whole talk, for the chrome's sheet: one line a message, the newest on top. */
@Composable
fun ChatLogSheet(session: ChatSession?, tick: Int) {
    @Suppress("UNUSED_VARIABLE") val redrawn = tick
    val log = session?.log.orEmpty()
    if (session == null || log.isEmpty()) { WifeNote("Nothing said yet."); return }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (l in log.asReversed()) {
            val who = if (l.fromYou) session.youName else session.npcName
            Text("$who: ${l.text}", style = WifeTheme.bodyStyle(if (l.fromYou) WifeTheme.textDim else WifeTheme.text))
        }
    }
}

@Composable
private fun ChatNeedsModel(onDownload: (() -> Unit)?, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier.widthIn(max = 340.dp).wifeSurface().padding(WifeTheme.gapM), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Chat needs the dialogue model", style = WifeTheme.labelStyle(), textAlign = TextAlign.Center)
        val ds by DialogueHost.state.collectAsStateWithLifecycle()
        if (ds.phase == DialogueUiState.Phase.LOADING) Text(ChatWait.loading(ds.loadProgress) + " (it is a big file; this takes a while)", style = WifeTheme.captionStyle(), textAlign = TextAlign.Center)
        else Text("Her words come from the model on your phone, and it is not there yet.", style = WifeTheme.captionStyle(), textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
            if (onDownload != null) WifeButton("Download it", onDownload)
            WifeButton("Try again", onRetry)
            WifeButton("Back", onBack)
        }
        if (onDownload == null) Text("Download it in Options.", style = WifeTheme.captionStyle(), textAlign = TextAlign.Center)
    }
}
