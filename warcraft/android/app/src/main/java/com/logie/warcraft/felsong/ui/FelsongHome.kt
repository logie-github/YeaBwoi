// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.logie.warcraft.Environments
import com.logie.warcraft.R
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.WowUpdateActivity
import com.logie.warcraft.WowUpdater
import com.logie.warcraft.turtle.ui.GameSwitch
import com.logie.warcraft.turtle.ui.SwitchToTurtleDialog
import com.logie.warcraft.turtle.ui.SwitchToWrathDialog
import com.logie.warcraft.turtle.ui.TurtleTheme
import com.logie.warcraft.ui.WowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The launcher in Felsong's look: Illidan over the Broken Isles, its logo, and where Felsong stands.
 * It has no Play, no realm and no install yet: nothing here can start the game (FELSONG.md), so
 * nothing pretends to. The app's update check on opening (as the other launchers do), and the way
 * to the other games.
 */
@Composable
fun FelsongLauncher(checkUpdateOnOpen: Boolean) {
    val context = LocalContext.current
    var checkUpdate by rememberSaveable { mutableStateOf(checkUpdateOnOpen) }
    val updateOffer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { checkUpdate = false }
    LaunchedEffect(Unit) {
        if (!checkUpdate) return@LaunchedEffect
        val update = if (WowUpdater.isConfigured) withContext(Dispatchers.IO) { runCatching { WowUpdater.findUpdate() }.getOrNull() } else null
        if (update != null) updateOffer.launch(WowUpdateActivity.intent(context, update)) else checkUpdate = false
    }
    var choosing by remember { mutableStateOf(false) }
    // A realm the chosen game's can't run beside is running: asked in that game's look.
    var asking by remember { mutableStateOf<Environments.Environment?>(null) }
    FelsongTheme {
        Box(Modifier.fillMaxSize().background(FelsongColors.Olive)) {
            FelsongHomeScreen(onSwitchGame = { choosing = true })
        }
        if (choosing) FelsongGameChooser(onDismiss = { choosing = false }) { target ->
            choosing = false
            if (!GameSwitch.trySwitch(context, target)) asking = target
        }
    }
    when (asking) {
        Environments.Environment.WRATH -> TurtleTheme { SwitchToWrathDialog(onDismiss = { asking = null }) }
        Environments.Environment.TURTLE -> WowTheme { SwitchToTurtleDialog(onDismiss = { asking = null }) }
        else -> {}
    }
}

/** The other games, in Felsong's look. */
@Composable
fun FelsongGameChooser(onDismiss: () -> Unit, onPick: (Environments.Environment) -> Unit) {
    FelsongDialog(
        title = stringResource(R.string.games_title),
        onDismiss = onDismiss,
        buttons = { FelsongButton(stringResource(R.string.turtle_cancel), primary = false, onClick = onDismiss) },
    ) {
        FelsongChoiceRow(stringResource(R.string.wrath_name), stringResource(R.string.wrath_name_summary)) { onPick(Environments.Environment.WRATH) }
        FelsongChoiceRow(stringResource(R.string.turtle_tile_name), stringResource(R.string.turtle_tile_name_summary)) { onPick(Environments.Environment.TURTLE) }
        FelsongChoiceRow(stringResource(R.string.tww_name), stringResource(R.string.tww_summary)) { onPick(Environments.Environment.TWW) }
    }
}

@Composable
internal fun FelsongHomeScreen(onSwitchGame: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        FelsongBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FelsongHeader(screenWidth, screenHeight, onSwitchGame)
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FelsongWindow(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FelsongTitle(stringResource(R.string.felsong_status_title))
                        Text(stringResource(R.string.felsong_status_body), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FelsongWindow(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FelsongTitle(stringResource(R.string.felsong_realm_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.felsong_realm_body), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FelsongWindow(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FelsongTitle(stringResource(R.string.felsong_client_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.felsong_client_body), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FelsongButton(stringResource(R.string.games_tile), Modifier.align(Alignment.CenterHorizontally), onClick = onSwitchGame)
            }
        }
    }
}

/** Illidan over the Broken Isles, drifting slowly, fel motes rising, the logo half over its foot. */
@Composable
private fun FelsongHeader(screenWidth: Dp, screenHeight: Dp, onSwitch: () -> Unit) {
    val headerHeight = min((screenWidth * 0.56f).coerceIn(240.dp, 460.dp), screenHeight * 0.6f)
    val logoWidth = min(min(screenWidth * 0.8f, headerHeight * 1.5f), 460.dp)
    val logoHeight = logoWidth * (326f / 733f)
    val motion = rememberInfiniteTransition(label = "header")
    val drift by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(32_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val breathe by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(3_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120)
        reveal.animateTo(1f, tween(1_400, easing = FastOutSlowInEasing))
    }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth().padding(top = statusBar).height(headerHeight + logoHeight * 0.62f)) {
        Box(Modifier.fillMaxWidth().height(headerHeight).clipToBounds()) {
            Image(
                painter = painterResource(R.drawable.felsong_header),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Illidan, left of the middle, stays in sight on a narrow screen.
                alignment = BiasAlignment(-0.35f, -0.3f),
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val zoom = 1.02f + 0.05f * drift
                    scaleX = zoom
                    scaleY = zoom
                    transformOrigin = TransformOrigin(0.4f, 0.4f)
                    alpha = reveal.value
                },
            )
            FelsongMotes(alpha = reveal.value)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to FelsongColors.Olive)))
        }
        Image(
            painter = painterResource(R.drawable.felsong_logo),
            contentDescription = stringResource(R.string.felsong_name),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = headerHeight - logoHeight * 0.55f)
                .width(logoWidth)
                .height(logoHeight)
                .graphicsLayer {
                    val value = reveal.value
                    alpha = ((value - 0.2f) / 0.8f).coerceIn(0f, 1f)
                    val scale = (0.95f + 0.05f * value) * (1f + 0.008f * breathe)
                    scaleX = scale
                    scaleY = scale
                },
        )
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .graphicsLayer { alpha = reveal.value }
                .clip(CircleShape)
                .background(FelsongColors.Soot.copy(alpha = 0.55f))
                .clickable(onClick = onSwitch)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = FelsongColors.Lime, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.games_chip), style = FelsongDisplayStyle.copy(fontSize = 13.sp))
        }
        MusicChip(Modifier.align(Alignment.TopEnd).padding(10.dp).graphicsLayer { alpha = reveal.value })
    }
}

/** The launcher music's switch: a note, crossed out while it's off. */
@Composable
private fun MusicChip(modifier: Modifier) {
    val context = LocalContext.current
    val on by TitleMusic.on.collectAsState()
    Box(
        modifier.size(44.dp).clip(CircleShape).background(FelsongColors.Soot.copy(alpha = 0.5f)).clickable { TitleMusic.setOn(context, !on) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (on) Icons.Default.MusicNote else Icons.Default.MusicOff,
            contentDescription = stringResource(if (on) R.string.wow_music_turn_off else R.string.wow_music_turn_on),
            tint = if (on) FelsongColors.Lime else FelsongColors.Sage,
            modifier = Modifier.size(22.dp),
        )
    }
}
