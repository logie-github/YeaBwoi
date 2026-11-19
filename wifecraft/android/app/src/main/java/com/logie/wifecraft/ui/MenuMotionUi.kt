package com.logie.wifecraft.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// The motion kit on screen (MenuMotion holds the numbers): every window, dialog and dropdown fades and grows in and out the
// same way, pages cross-fade, presses sink; none of it moves while the phone's animations are off.

/** Set to force motion off (true) or on (false) below it; null: the phone's own setting. */
val LocalReduceMotion = compositionLocalOf<Boolean?> { null }

/** Inside an [Overlay] (it moves the window in and out): a [WowWindow] there does not move itself too. */
internal val LocalInMovingOverlay = staticCompositionLocalOf { false }

/** Whether the menus keep still: [LocalReduceMotion], else the phone's "Remove animations" (animator duration scale 0). */
@Composable
fun reduceMotion(): Boolean {
    LocalReduceMotion.current?.let { return it }
    val ctx = LocalContext.current
    return remember(ctx) {
        val scale = runCatching {
            android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        MenuMotion.reduced(scale)
    }
}

/** One gate per tap target, kept while it is on screen. */
@Composable
fun rememberClickGate(windowMs: Long = MenuMotion.DEBOUNCE_MS): ClickGate = remember(windowMs) { ClickGate(windowMs) }

/** 0 to 1 once, as the caller first shows (a window's or a screen's way in); 1 at once under reduced motion. */
@Composable
fun rememberEnter(ms: Int = MenuMotion.WINDOW_IN_MS): Animatable<Float, *> {
    val reduced = reduceMotion()
    val a = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (a.value < 1f) a.animateTo(1f, tween(MenuMotion.duration(ms, reduced), easing = LinearOutSlowInEasing)) }
    return a
}

/** A window's way in: faded and a little smaller at [t] 0, as it is at 1. */
fun Modifier.windowMotion(t: () -> Float): Modifier = graphicsLayer {
    val v = t()
    alpha = v
    val s = MenuMotion.WINDOW_SCALE_FROM + (1f - MenuMotion.WINDOW_SCALE_FROM) * v
    scaleX = s; scaleY = s
}

/** Takes every touch while [on] (a window on its way out: nothing on it can be tapped twice). */
fun Modifier.blockTouches(on: Boolean): Modifier = if (!on) this else pointerInput(on) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/**
 * A dimmed backdrop with one panel in the middle; tapping outside closes it. It fades and grows in as it is first shown. To
 * fade it out too, show it with [visible] (or [OverlayFor]) instead of leaving it out: it stays on screen until it has gone,
 * and nothing on it answers a tap meanwhile.
 */
@Composable
fun Overlay(onDismiss: () -> Unit, visible: Boolean = true, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    if (!state.currentState && !state.targetState && state.isIdle) return
    val reduced = reduceMotion()
    val dismiss by rememberUpdatedState(onDismiss)
    val gate = rememberClickGate()
    val t = rememberTransition(state, label = "overlay")
    val shown by t.animateFloat(
        { tween(MenuMotion.duration(if (targetState) MenuMotion.WINDOW_IN_MS else MenuMotion.WINDOW_OUT_MS, reduced), easing = FastOutSlowInEasing) },
        label = "shown",
    ) { if (it) 1f else 0f }
    val leaving = !state.targetState
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = shown }.background(Scrim)
            .clickable(remember { MutableInteractionSource() }, null) { if (!leaving && gate.pass()) dismiss() },
        contentAlignment = Alignment.Center,
    ) {
        // Taps on the panel stay on the panel.
        // while it goes, its buttons see no touch at all: taken first (the Initial pass, before any child) and covered over
        Box(Modifier.windowMotion { shown }.pointerInput(Unit) { detectTapGestures { } }.blockTouches(leaving)) {
            CompositionLocalProvider(LocalInMovingOverlay provides true) { content() }
            if (leaving) Box(Modifier.matchParentSize().blockTouches(true))
        }
    }
}

private val Scrim = Color(0xAA000000)

/**
 * An [Overlay] for a thing that may be there or not ([value] null: none): it shows the thing, and when [value] goes back to
 * null it fades out still showing the last one (a Delete question keeps its words while it goes).
 */
@Composable
fun <T : Any> OverlayFor(value: T?, onDismiss: () -> Unit, content: @Composable (T) -> Unit) {
    val shown = rememberHeld(value) ?: return
    Overlay(onDismiss, visible = value != null) { content(shown) }
}

/** [value], or while it is null the last one it was (null before it was ever set): what a window shows while it fades out. */
@Composable
fun <T : Any> rememberHeld(value: T?): T? {
    val held = remember { arrayOfNulls<Any>(1) }
    if (value != null) held[0] = value
    @Suppress("UNCHECKED_CAST")
    return (value ?: held[0]) as T?
}

/** The dp a dropdown drops from as it shows. */
internal val PopSlide = MenuMotion.POP_SLIDE_DP.dp
