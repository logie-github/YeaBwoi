// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.pe.MenuActivity.Action
import com.logie.pe.PeConfig
import com.logie.pe.PeFiles
import com.logie.pe.R

private enum class MenuPage { MAIN, SAVE, LOAD, PICTURE, CONTROLS }

/**
 * The in-game menu: a panel of night glass over the darkened, paused game.
 *
 * [discs] is how many discs the game has, [currentDisc] the one that's in (0 for disc 1).
 */
@Composable
fun PeMenuScreen(
    discs: Int,
    currentDisc: Int,
    onResume: () -> Unit,
    onAction: (Action) -> Unit,
    onUpdates: () -> Unit,
) {
    var page by remember { mutableStateOf(MenuPage.MAIN) }
    BackHandler { if (page == MenuPage.MAIN) onResume() else page = MenuPage.MAIN }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    1f to PeColors.Abyss.copy(alpha = 0.88f),
                )
            )
            // A tap beside the menu carries on playing.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onResume)
    ) {
        PeGrain(alpha = 0.05f)
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
        ) {
            val wide = maxWidth > maxHeight
            Box(
                Modifier
                    .align(if (wide) Alignment.CenterEnd else Alignment.Center)
                    .fillMaxWidth(if (wide) 0.5f else 1f)
                    .widthIn(max = 470.dp)
                    .graphicsLayer { translationX = (1f - appear.value) * 40.dp.toPx() }
                    // Taps on the menu itself don't close it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it / 8 }) togetherWith fadeOut(tween(120))
                    },
                    label = "page",
                ) { current ->
                    when (current) {
                        MenuPage.MAIN -> MainPage(
                            discs = discs,
                            currentDisc = currentDisc,
                            onResume = onResume,
                            onPage = { page = it },
                            onAction = onAction,
                            onUpdates = onUpdates,
                        )
                        MenuPage.SAVE -> SlotsPage(save = true, onBack = { page = MenuPage.MAIN }, onAction = onAction)
                        MenuPage.LOAD -> SlotsPage(save = false, onBack = { page = MenuPage.MAIN }, onAction = onAction)
                        MenuPage.PICTURE -> SettingsPage(stringResource(R.string.pe_picture_title), onBack = { page = MenuPage.MAIN }) {
                            PictureSettings()
                        }
                        MenuPage.CONTROLS -> SettingsPage(stringResource(R.string.pe_controls_title), onBack = { page = MenuPage.MAIN }) {
                            ControlsSettings()
                            PeGestureGuide()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainPage(
    discs: Int,
    currentDisc: Int,
    onResume: () -> Unit,
    onPage: (MenuPage) -> Unit,
    onAction: (Action) -> Unit,
    onUpdates: () -> Unit,
) {
    val context = LocalContext.current
    var speed by remember { mutableIntStateOf(PeConfig(context).speed) }
    var confirmReset by remember { mutableStateOf(false) }

    PeWindow(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 12.dp, horizontal = 10.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PeMenuLine(stringResource(R.string.pe_menu_resume), highlighted = true, onClick = onResume)
            PeMenuLine(stringResource(R.string.pe_menu_save)) { onPage(MenuPage.SAVE) }
            PeMenuLine(stringResource(R.string.pe_menu_load)) { onPage(MenuPage.LOAD) }
            PeInlineChoice(
                stringResource(R.string.pe_menu_speed),
                options = (1..PeConfig.MAX_SPEED).map { it to "$it×" },
                selected = speed,
            ) {
                speed = it
                PeConfig(context).speed = it
            }
            // Parasite Eve asks for disc 2 at the end of disc 1: put it in here.
            if (discs > 1) {
                PeInlineChoice(
                    stringResource(R.string.pe_menu_disc),
                    options = (0 until discs).map { it to "${it + 1}" },
                    selected = currentDisc,
                ) {
                    if (it != currentDisc) onAction(Action.ChangeDisc(it))
                }
            }
            PeMenuLine(stringResource(R.string.pe_menu_picture)) { onPage(MenuPage.PICTURE) }
            PeMenuLine(stringResource(R.string.pe_menu_controls)) { onPage(MenuPage.CONTROLS) }
            PeMenuLine(stringResource(if (confirmReset) R.string.pe_menu_reset_confirm else R.string.pe_menu_reset)) {
                if (confirmReset) onAction(Action.Reset) else confirmReset = true
            }
            PeMenuLine(stringResource(R.string.pe_menu_updates), onClick = onUpdates)
            PeMenuLine(stringResource(R.string.pe_menu_quit)) { onAction(Action.Quit) }
        }
    }
}

@Composable
private fun SlotsPage(save: Boolean, onBack: () -> Unit, onAction: (Action) -> Unit) {
    val context = LocalContext.current
    PeWindow(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PeTitle(stringResource(if (save) R.string.pe_menu_save else R.string.pe_menu_load))
            for (slot in 1..PeFiles.SLOTS) {
                val time = PeFiles.stateTime(context, slot)
                val enabled = save || time != null
                PeWindow(
                    Modifier.fillMaxWidth(),
                    enabled = enabled,
                    padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    onClick = { onAction(if (save) Action.Save(slot) else Action.Load(slot)) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.pe_slot, slot).lowercase(),
                            style = PeDisplayStyle.copy(fontSize = 15.sp),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (time != null) relativeTime(time) else stringResource(R.string.pe_slot_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (time != null) PeColors.Text else PeColors.Mist,
                        )
                    }
                }
            }
            Text(
                stringResource(if (save) R.string.pe_slots_save_note else R.string.pe_slots_load_note),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.padding(2.dp))
            PeButton(stringResource(R.string.pe_menu_back), primary = false, modifier = Modifier.align(Alignment.End), onClick = onBack)
        }
    }
}

@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    PeWindow(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PeTitle(title)
            content()
            PeButton(stringResource(R.string.pe_menu_back), primary = false, modifier = Modifier.align(Alignment.End), onClick = onBack)
        }
    }
}
