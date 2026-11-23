package com.logie.wifecraft.ui

import android.os.Build
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import kotlin.math.max

/**
 * What the screen keeps free, in dp, for [ScreenPlan]: the safe area's sides and bottom (cut-out, bars), the display cut-outs'
 * rectangles, the keyboard and the system's gesture zones. A cut-out at the top edge is steered round by the plan (the bar splits
 * beside it), so the top inset then leaves it out and counts only the bars; any other cut-out stays in the side insets.
 */
@Composable
internal fun rememberScreenInsets(): ScreenInsets {
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val view = LocalView.current
    val safe = WindowInsets.safeDrawing
    val bars = WindowInsets.systemBars
    val gestures = WindowInsets.systemGestures
    val keyboard = WindowInsets.ime
    val d = density.density
    val cutouts = cutoutRects(view, d)
    val atTop = cutouts.any { it.t <= 1f }
    return ScreenInsets(
        l = safe.getLeft(density, dir) / d,
        t = (if (atTop) bars.getTop(density) else safe.getTop(density)) / d,
        r = safe.getRight(density, dir) / d,
        b = safe.getBottom(density) / d,
        cutouts = cutouts,
        ime = keyboard.getBottom(density) / d,
        gestureL = max(24f, gestures.getLeft(density, dir) / d),
        gestureR = max(24f, gestures.getRight(density, dir) / d),
    )
}

/** The window's display cut-outs as rectangles in dp (none before Android 9, or when the view isn't attached yet). */
private fun cutoutRects(view: android.view.View, density: Float): List<ScreenRect> {
    if (Build.VERSION.SDK_INT < 28) return emptyList()
    val cutout = view.rootWindowInsets?.displayCutout ?: return emptyList()
    return cutout.boundingRects.map { ScreenRect(it.left / density, it.top / density, it.right / density, it.bottom / density) }
}
