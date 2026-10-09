// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.azuredreams.AdConfig
import com.logie.azuredreams.AdFiles
import com.logie.azuredreams.MenuActivity.Action
import com.logie.azuredreams.R

private enum class MenuPage { MAIN, SAVE, LOAD, PICTURE, CONTROLS }

/** The in-game menu: a column of PlayStation RPG windows over the dimmed, paused game. */
@Composable
fun AdMenuScreen(
    onResume: () -> Unit,
    onAction: (Action) -> Unit,
    onUpdates: () -> Unit,
) {
    var page by remember { mutableStateOf(MenuPage.MAIN) }
    BackHandler { if (page == MenuPage.MAIN) onResume() else page = MenuPage.MAIN }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 500f)) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f * appear.value))
            // A tap beside the menu carries on playing.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onResume)
    ) {
        AdScanlines(alpha = 0.12f)
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
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(if (wide) 0.5f else 1f)
                    .graphicsLayer {
                        alpha = appear.value.coerceIn(0f, 1f)
                        translationX = (1f - appear.value) * 60.dp.toPx()
                    }
                    // Taps on the menu itself don't close it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        (fadeIn(tween(160)) + slideInHorizontally(tween(200)) { it / 6 }) togetherWith fadeOut(tween(100))
                    },
                    label = "page",
                ) { current ->
                    when (current) {
                        MenuPage.MAIN -> MainPage(
                            onResume = onResume,
                            onPage = { page = it },
                            onAction = onAction,
                            onUpdates = onUpdates,
                        )
                        MenuPage.SAVE -> SlotsPage(save = true, onBack = { page = MenuPage.MAIN }, onAction = onAction)
                        MenuPage.LOAD -> SlotsPage(save = false, onBack = { page = MenuPage.MAIN }, onAction = onAction)
                        MenuPage.PICTURE -> SettingsPage(stringResource(R.string.ad_picture_title), onBack = { page = MenuPage.MAIN }) {
                            PictureSettings()
                        }
                        MenuPage.CONTROLS -> SettingsPage(stringResource(R.string.ad_controls_title), onBack = { page = MenuPage.MAIN }) {
                            ControlsSettings()
                            AdGestureGuide()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainPage(
    onResume: () -> Unit,
    onPage: (MenuPage) -> Unit,
    onAction: (Action) -> Unit,
    onUpdates: () -> Unit,
) {
    val context = LocalContext.current
    var speed by remember { mutableIntStateOf(AdConfig(context).speed) }
    var confirmReset by remember { mutableStateOf(false) }

    AdWindow(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 10.dp, horizontal = 8.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            MenuItem(stringResource(R.string.ad_menu_resume), highlighted = true, onClick = onResume)
            MenuItem(stringResource(R.string.ad_menu_save)) { onPage(MenuPage.SAVE) }
            MenuItem(stringResource(R.string.ad_menu_load)) { onPage(MenuPage.LOAD) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 34.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.ad_menu_speed).uppercase(),
                    style = AdDisplayStyle.copy(fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
                for (value in 1..AdConfig.MAX_SPEED) {
                    Text(
                        "${value}×",
                        style = AdDisplayStyle.copy(fontSize = 13.sp, color = if (value == speed) AdColors.Gold else AdColors.TextDim),
                        modifier = Modifier
                            .clickable {
                                speed = value
                                AdConfig(context).speed = value
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            MenuItem(stringResource(R.string.ad_menu_picture)) { onPage(MenuPage.PICTURE) }
            MenuItem(stringResource(R.string.ad_menu_controls)) { onPage(MenuPage.CONTROLS) }
            MenuItem(stringResource(if (confirmReset) R.string.ad_menu_reset_confirm else R.string.ad_menu_reset)) {
                if (confirmReset) onAction(Action.Reset) else confirmReset = true
            }
            MenuItem(stringResource(R.string.ad_menu_updates), onClick = onUpdates)
            MenuItem(stringResource(R.string.ad_menu_quit)) { onAction(Action.Quit) }
        }
    }
}

/** A menu line; the pointer sits beside the highlighted one and whichever is being touched. */
@Composable
private fun MenuItem(text: String, highlighted: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { touched = it is androidx.compose.foundation.interaction.PressInteraction.Press }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(30.dp)) {
            if (highlighted || touched) AdPointer()
        }
        Text(
            text.uppercase(),
            style = AdDisplayStyle.copy(fontSize = 14.sp, color = if (highlighted || touched) AdColors.Gold else AdColors.Text),
        )
    }
}

@Composable
private fun SlotsPage(save: Boolean, onBack: () -> Unit, onAction: (Action) -> Unit) {
    val context = LocalContext.current
    AdWindow(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (save) R.string.ad_menu_save else R.string.ad_menu_load).uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = AdColors.Gold,
            )
            for (slot in 1..AdFiles.SLOTS) {
                val time = AdFiles.stateTime(context, slot)
                val enabled = save || time != null
                AdWindow(
                    Modifier.fillMaxWidth(),
                    enabled = enabled,
                    padding = PaddingValues(12.dp),
                    onClick = { onAction(if (save) Action.Save(slot) else Action.Load(slot)) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.ad_slot, slot),
                            style = AdDisplayStyle.copy(fontSize = 13.sp),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (time != null) relativeTime(time) else stringResource(R.string.ad_slot_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (time != null) AdColors.Text else AdColors.TextDim,
                        )
                    }
                }
            }
            Text(
                stringResource(if (save) R.string.ad_slots_save_note else R.string.ad_slots_load_note),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.padding(2.dp))
            AdButton(stringResource(R.string.ad_menu_back), primary = false, modifier = Modifier.align(Alignment.End), onClick = onBack)
        }
    }
}

@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    AdWindow(Modifier.fillMaxWidth()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title.uppercase(), style = MaterialTheme.typography.headlineSmall, color = AdColors.Gold)
            content()
            AdButton(stringResource(R.string.ad_menu_back), primary = false, modifier = Modifier.align(Alignment.End), onClick = onBack)
        }
    }
}
