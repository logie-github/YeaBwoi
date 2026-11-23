package com.logie.wifecraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.dialogue.DialogueEngine
import com.logie.wifecraft.dialogue.DirtyLevel
import com.logie.wifecraft.dialogue.DirtyTalk
import com.logie.wifecraft.dialogue.JoiLineRequest
import com.logie.wifecraft.dialogue.JoiPersona
import com.logie.wifecraft.dialogue.JoiPrompt
import com.logie.wifecraft.dialogue.JoiSteer
import com.logie.wifecraft.dialogue.KinkContext
import com.logie.wifecraft.simulation.CinematicFrame
import com.logie.wifecraft.simulation.FilmFrame
import com.logie.wifecraft.simulation.JoiEvent
import com.logie.wifecraft.simulation.JoiFraming
import com.logie.wifecraft.simulation.JoiMode
import com.logie.wifecraft.simulation.JoiPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// JOI mode's screen: the loop that runs the mode (JoiMode, pure) against the dialogue engine, and the layer over the film frame:
// subtitles in its lower third (large, readable) and, while the interface is up, the controls (framing, intensity, steer chips,
// pause, skip stage, stop). The mode's rules are in simulation/JoiMode.kt (JVM-tested); this file is compiled by CI only.

/**
 * Runs [mode] while it is on: about ten steps a second, a line asked of [engine] (in the background: the model may take
 * seconds; the answer is offered to the mode with the ask's serial, so a skipped stage drops it), a solo animation when one
 * is due ([onSolo], with the stage's energy), the end ([onFinished]). [tick] is called after every step so the layer redraws.
 */
@Composable
fun JoiRunner(
    mode: JoiMode, running: Boolean, engine: () -> DialogueEngine?, pack: KinkContext?, persona: JoiPersona?,
    onSolo: (energy: Float, stage: String, stageLeft: Float) -> Unit, onFinished: () -> Unit, tick: () -> Unit,
) {
    val engineNow by rememberUpdatedState(engine)
    val personaNow by rememberUpdatedState(persona)
    val solo by rememberUpdatedState(onSolo)
    val done by rememberUpdatedState(onFinished)
    val redraw by rememberUpdatedState(tick)
    val scope = rememberCoroutineScope()
    LaunchedEffect(mode, running) {
        if (!running) return@LaunchedEffect
        val recent = ArrayList<String>()
        var last = android.os.SystemClock.uptimeMillis()
        var pending: Job? = null
        var epoch = mode.steerEpoch
        while (isActive) {
            delay(100)
            val now = android.os.SystemClock.uptimeMillis()
            val dt = (now - last) / 1000f
            last = now
            // a steer typed meanwhile: the line being written under the old one is given up (the mode already dropped its answer and the queued line)
            if (mode.steerEpoch != epoch) { epoch = mode.steerEpoch; pending?.cancel() }
            mode.update(dt)
            for (e in mode.drain()) when (e) {
                is JoiEvent.NeedLine -> {
                    val p = personaNow
                    val eng = engineNow()
                    val steer = mode.steer.toSet()
                    val band = mode.band
                    val recentNow = recent.toList()
                    val actionNow = mode.action()
                    val levelNow = mode.level
                    val steerTextNow = mode.customSteer
                    pending = scope.launch {
                        val text = if (p != null && eng != null)
                            runCatching { JoiPrompt.line(eng, JoiLineRequest(p, e.stage, band, steer, recentNow, e.countdownFrom, npcId = mode.actor.orEmpty(), action = actionNow,
                                level = levelNow, customSteer = steerTextNow), pack) }.getOrNull()
                        else null
                        mode.offerLine(e.serial, text)
                        if (text != null) { recent += text; while (recent.size > 6) recent.removeAt(0) }
                        redraw()
                    }
                }
                is JoiEvent.AnimationDue -> solo(e.energy, e.stage, e.stageLeft)
                is JoiEvent.Finished -> done()
                else -> Unit
            }
            redraw()
        }
    }
}

/**
 * The layer over the live scene while JOI mode is on. [frame]: the film frame's shape (the subtitles sit in its lower third);
 * [controls]: the interface is up (cinematic mode hides it after 5 s untouched; the subtitles stay). [creature]: the speaker is
 * a dragon (her camera orbits: no framing choice). [tick] changes every redraw.
 */
@Composable
fun JoiLayer(
    mode: JoiMode, tick: Int, frame: CinematicFrame, controls: Boolean, creature: Boolean,
    onDownloadModel: (() -> Unit)?, onRetry: () -> Unit, onStop: () -> Unit, onChanged: () -> Unit,
    modifier: Modifier = Modifier,
    /** Where the controls go (the chrome's sheet, dp; the side in landscape, above the bottom in portrait); null: the top left, as before. */
    controlsRect: ScreenRect? = null,
) {
    @Suppress("UNUSED_VARIABLE") val redraw = tick
    var typed by remember { mutableStateOf("") }   // the steer being typed: kept while the controls hide
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }.toInt(); val hPx = with(density) { maxHeight.toPx() }.toInt()
        val rect = FilmFrame.fit(wPx, hPx, frame.aspect)
        val frameTop = with(density) { rect.y.toDp() }; val frameLeft = with(density) { rect.x.toDp() }
        val frameW = with(density) { rect.width.toDp() }; val frameH = with(density) { rect.height.toDp() }
        // the lower third: the subtitle's bottom edge a ninth of the frame up
        Box(Modifier.offset(frameLeft, frameTop).size(frameW, frameH)) {
            when {
                mode.phase == JoiPhase.NEEDS_MODEL -> NeedsModel(mode, onDownloadModel, onRetry, onStop, Modifier.align(Alignment.Center))
                else -> {
                    val text = mode.subtitles.text
                    if (text != null) {
                        val number = mode.subtitles.isNumber
                        val size = with(density) { (rect.height * if (number) 0.16f else 0.052f).toSp() }
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = frameH * 0.11f, start = frameW * 0.06f, end = frameW * 0.06f)) {
                            Text(
                                text, textAlign = TextAlign.Center,
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = if (number) 0f else 0.45f)).padding(horizontal = 14.dp, vertical = 6.dp),
                                style = TextStyle(color = Color.White, fontSize = size, fontWeight = if (number) FontWeight.Bold else FontWeight.Medium,
                                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(0f, 2f), 6f)),
                            )
                        }
                    }
                }
            }
        }
        if (controls && controlsRect != null) JoiControls(mode, creature, typed, { typed = it }, controlsRect.h.dp, onStop, onChanged,
            Modifier.align(Alignment.TopStart).offset(controlsRect.l.dp, controlsRect.t.dp).size(controlsRect.w.dp, controlsRect.h.dp))
        else if (controls) JoiControls(mode, creature, typed, { typed = it }, maxHeight - 72.dp, onStop, onChanged,
            Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 8.dp, top = 64.dp))
    }
}

@Composable
private fun NeedsModel(mode: JoiMode, onDownload: (() -> Unit)?, onRetry: () -> Unit, onStop: () -> Unit, modifier: Modifier) {
    Column(modifier.widthIn(max = 340.dp).wifeSurface().padding(WifeTheme.gapM), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("JOI needs the dialogue model", style = WifeTheme.labelStyle(), textAlign = TextAlign.Center)
        Text(if (mode.stageCount == 0) "Her words come from the model on your phone, and it is not there yet."
            else "The model did not answer.", style = WifeTheme.captionStyle(), textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
            if (onDownload != null) WifeButton("Download it", onDownload)
            WifeButton("Try again", onRetry)
            WifeButton("Back", onStop)
        }
        if (onDownload == null) Text("Download it in Options.", style = WifeTheme.captionStyle(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun JoiControls(mode: JoiMode, creature: Boolean, typed: String, onTyped: (String) -> Unit, maxHeight: Dp, onStop: () -> Unit, onChanged: () -> Unit, modifier: Modifier) {
    Column(modifier.widthIn(max = 380.dp).heightIn(max = maxHeight).wifeSurface().verticalScroll(rememberScrollState()).padding(WifeTheme.gapS),
        verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS), verticalAlignment = Alignment.CenterVertically) {
            Text("JOI", style = WifeTheme.labelStyle())
            Text(mode.stage?.replaceFirstChar { it.uppercase() }?.let { "$it ${mode.stageIndex + 1}/${mode.stageCount}" } ?: "", style = WifeTheme.captionStyle())
        }
        if (!creature) {
            val framings = JoiFraming.SHOWN
            WifeTabs(framings.map { it.label }, framings.indexOf(mode.framing).coerceAtLeast(0), { mode.setFraming(framings[it]); onChanged() })
        } else Text("Her head, circled slowly", style = WifeTheme.captionStyle())
        // the dirty talk level: Cute .. WTF??!, this session's own (Options' default is where it starts); applies from the next line
        val levels = DirtyLevel.entries
        WifeSliderRow("Dirty talk", mode.level.ordinal, { mode.setLevel(levels[it.coerceIn(0, levels.lastIndex)]); onChanged() },
            valueText = mode.level.label, range = 0..levels.lastIndex)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
            for (s in JoiSteer.entries) WifeChip(s.label, { mode.toggleSteer(s); onChanged() }, trailing = if (s in mode.steer) "✓" else null)
        }
 // a steer of her own words: the very next line carries it, and it stands (a chip) until cleared
        Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs), verticalAlignment = Alignment.CenterVertically) {
            WowTextField(typed, { onTyped(it.take(DirtyTalk.STEER_MAX)) }, "Steer her, e.g. talk about her ass", Modifier.weight(1f))
            WifeButton("Steer", { mode.setSteerText(typed); onTyped(""); onChanged() })
        }
        mode.customSteer?.let { s -> WifeChip(s, { mode.clearSteerText(); onChanged() }) }
        Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
            WifeButton(if (mode.phase == JoiPhase.PAUSED) "Resume" else "Pause", { mode.togglePause(); onChanged() })
            WifeButton("Skip stage", { mode.skip(); onChanged() })
            WifeButton("Stop", onStop)
        }
    }
}
