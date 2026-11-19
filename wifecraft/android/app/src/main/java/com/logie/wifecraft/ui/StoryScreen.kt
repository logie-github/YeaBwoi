package com.logie.wifecraft.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.logie.wifecraft.engine.Clock
import com.logie.wifecraft.play.Director
import kotlin.math.abs

/** Gestures over the story: tap, hold, swipe up or down, two-finger tap. */
fun Modifier.storyGestures(
    onTap: () -> Unit,
    onHold: (Boolean) -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onTwoFingers: () -> Unit,
): Modifier = pointerInput(Unit) {
    val swipe = 70.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val t0 = down.uptimeMillis
        var most = 1
        var held = false
        var last = start
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.count { it.pressed }
            if (pressed > most) most = pressed
            ev.changes.firstOrNull { it.id == down.id }?.let { last = it.position }
            val moved = last - start
            if (!held && most == 1 && pressed == 1 && ev.changes.first().uptimeMillis - t0 > 450 &&
                abs(moved.x) < swipe / 3 && abs(moved.y) < swipe / 3
            ) {
                held = true
                onHold(true)
            }
            if (pressed == 0) break
        }
        val moved: Offset = last - start
        when {
            most >= 2 -> onTwoFingers()
            held -> onHold(false)
            moved.y < -swipe && abs(moved.y) > abs(moved.x) -> onSwipeUp()
            moved.y > swipe && abs(moved.y) > abs(moved.x) -> onSwipeDown()
            abs(moved.x) < swipe / 2 && abs(moved.y) < swipe / 2 -> onTap()
        }
    }
}

/** The story: the 3D scene, and over it the talking head, choices, cards, fades and endings. */
@Composable
fun StoryScreen(
    scene: android.view.View,
    director: Director,
    parchment: Boolean,
    frame: Int,
    onMenu: () -> Unit,
    onLog: () -> Unit,
) {
    var boxHidden by remember { mutableStateOf(false) }
    @Suppress("UNUSED_EXPRESSION") frame   // recomposes every frame while the story plays
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ (scene.parent as? android.view.ViewGroup)?.removeView(scene); scene }, Modifier.fillMaxSize())

        val bars by animateFloatAsState(if (director.letterbox) 1f else 0f, tween(600), label = "letterbox")
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val barH = maxHeight * 0.11f * bars
            Box(Modifier.fillMaxWidth().height(barH).background(Color.Black).align(Alignment.TopCenter))
            Box(Modifier.fillMaxWidth().height(barH).background(Color.Black).align(Alignment.BottomCenter))
        }

        Box(
            Modifier.fillMaxSize().storyGestures(
                onTap = { if (boxHidden) boxHidden = false else director.tap() },
                onHold = { director.hold(it) },
                onSwipeUp = onLog,
                onSwipeDown = { boxHidden = !boxHidden },
                onTwoFingers = onMenu,
            ),
        )

        val line = director.line
        if (line != null && !boxHidden) {
            TalkingHead(
                line.name, line.text, director.revealed, parchment,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).widthIn(max = 820.dp).fillMaxWidth(0.88f).heightIn(min = 118.dp),
                more = director.revealed >= line.text.length,
            )
        }

        director.choice?.let { c ->
            // Low, where the talking head sits: the faces stay in sight above it. Each choice fades up into place.
            key(c) {
            val enter = rememberEnter()
            MetalPanel(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).widthIn(max = 820.dp).fillMaxWidth(0.88f)
                    .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 10.dp.toPx() },
                parchment = true,
            ) {
                Column {
                    Text(c.prompt, style = morpheusStyle(19.sp, WowColors.ink).copy(shadow = null), modifier = Modifier.padding(bottom = 2.dp))
                    c.options.forEachIndexed { i, text -> GossipOption(text, { director.choose(i) }) }
                }
            }
            }
        }

        director.title?.let { (heading, sub) ->
            val age = Clock.now() - director.titleShownAt
            val a = when {
                age < 500 -> age / 500f
                age > 2300 -> ((2800 - age) / 500f).coerceAtLeast(0f)
                else -> 1f
            }
            Column(Modifier.align(Alignment.Center).graphicsLayer { alpha = a }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(heading, style = morpheusStyle(46.sp), textAlign = TextAlign.Center)
                if (sub.isNotEmpty()) Text(sub, style = frizStyle(20.sp, Color.White), textAlign = TextAlign.Center)
            }
        }

        Black(director.fade)

        director.ending?.let { title ->
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                AchievementToast("The End", title, "", "", Modifier.width(520.dp).height(170.dp))
                Spacer(Modifier.height(16.dp))
                WowButton("Back", { director.endingDone() }, width = 200.dp)
            }
        }

        if (director.loading) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                WarcraftLoadingArt(Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Setting the stage…", style = frizStyle(16.sp))
                    Spacer(Modifier.height(6.dp))
                    val t = (Clock.now() % 2400) / 2400f
                    LoadingBar(t, Modifier.width(520.dp).height(48.dp))
                }
            }
        }
    }
}

/** The pause menu: WoW's Game Menu. [onLeave] stops the story and goes back to where it was played from (the editor). */
@Composable
fun PauseMenu(
    chapter: String,
    onResume: () -> Unit,
    onLog: () -> Unit,
    onOptions: () -> Unit,
    onLeave: () -> Unit,
) {
    // the game's Game Menu over the dimmed story, fading in as the Overlay does; a tap beside it is Resume (as Escape is)
    Overlay(onResume) {
        // the game's Game Menu: the metal frame, its red buttons stacked
        WowWindow("Game Menu", onClose = null, modifier = Modifier.width(288.dp)) {
            if (chapter.isNotEmpty()) Text(chapter, style = frizStyle(11.sp, WowColors.grey), modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp))
            Column(Modifier.align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RedButton("Resume", onResume, Modifier.width(240.dp))
                RedButton("What Was Said", onLog, Modifier.width(240.dp))
                RedButton("Options", onOptions, Modifier.width(240.dp))
                RedButton("Stop Playing", onLeave, Modifier.width(240.dp))
            }
        }
    }
}
