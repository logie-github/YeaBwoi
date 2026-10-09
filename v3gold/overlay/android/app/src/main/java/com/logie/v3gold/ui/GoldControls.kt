// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.v3gold.GoldConfig
import com.logie.v3gold.GoldUpdateActivity
import org.vita3k.emulator.Emulator
import org.vita3k.emulator.R

/** A Vita button as the guide shows it, in its usual color. */
private enum class GuideButton(val label: String, val color: Color) {
    CROSS("✕", Color(0xFF7CB2E8)),
    CIRCLE("○", Color(0xFFFF6B6B)),
    SQUARE("□", Color(0xFFE59AD8)),
    TRIANGLE("△", Color(0xFF4FD1A5)),
    START("START", Color(0xFF9E9E9E)),
    SELECT("SELECT", Color(0xFF9E9E9E)),
    L1("L1", Color(0xFF9E9E9E)),
    R1("R1", Color(0xFF9E9E9E)),
    LEFT_STICK("L", Color(0xFF9E9E9E)),
    RIGHT_STICK("R", Color(0xFF9E9E9E)),
    MENU("≡", Color(0xFFF2C318)),
}

private class GuideRow(@StringRes val gesture: Int, @StringRes val result: Int, val button: GuideButton)

private val GuideRows = listOf(
    GuideRow(R.string.gold_gesture_swipe, R.string.gold_button_left_stick, GuideButton.LEFT_STICK),
    GuideRow(R.string.gold_gesture_camera, R.string.gold_button_right_stick, GuideButton.RIGHT_STICK),
    GuideRow(R.string.gold_gesture_tap, R.string.gold_button_cross, GuideButton.CROSS),
    GuideRow(R.string.gold_gesture_hold, R.string.gold_button_circle, GuideButton.CIRCLE),
    GuideRow(R.string.gold_gesture_tap_hold, R.string.gold_button_square, GuideButton.SQUARE),
    GuideRow(R.string.gold_gesture_two_finger_swipe_up, R.string.gold_button_triangle, GuideButton.TRIANGLE),
    GuideRow(R.string.gold_gesture_double_tap, R.string.gold_button_start, GuideButton.START),
    GuideRow(R.string.gold_gesture_two_finger_hold, R.string.gold_button_select, GuideButton.SELECT),
    GuideRow(R.string.gold_gesture_two_finger_swipe_right, R.string.gold_button_l1, GuideButton.L1),
    GuideRow(R.string.gold_gesture_two_finger_swipe_left, R.string.gold_button_r1, GuideButton.R1),
    GuideRow(R.string.gold_gesture_menu, R.string.gold_button_menu, GuideButton.MENU),
    GuideRow(R.string.gold_gesture_back, R.string.gold_button_menu, GuideButton.MENU),
)

/** Every gesture and the button it presses. Uses the surrounding theme's colors. */
@Composable
fun GoldGestureGuide(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in GuideRows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.button.label,
                    color = row.button.color,
                    fontWeight = FontWeight.Black,
                    fontSize = if (row.button.label.length > 2) 10.sp else 16.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .background(Color(0xFF1B1512), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(row.gesture),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(row.result),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.gold_gesture_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * The in-game menu's Controls tab: gesture controls on or off, then the gesture guide, or Vita3K's
 * own on-screen button settings ([buttons]) while gestures are off.
 */
@Composable
fun GoldControlsTab(activity: Emulator, buttons: @Composable () -> Unit) {
    val context = LocalContext.current
    var gestures by remember { mutableStateOf(GoldConfig(context).gesturesEnabled) }
    var haptics by remember { mutableStateOf(GoldConfig(context).haptics) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SwitchRow(
            title = stringResource(R.string.gold_gestures_switch),
            subtitle = stringResource(R.string.gold_gestures_switch_summary),
            checked = gestures,
        ) { on ->
            gestures = on
            val overlayGestures = activity.getmOverlay()?.goldGestures
            if (overlayGestures != null) overlayGestures.setEnabled(on) else GoldConfig(context).gesturesEnabled = on
        }
        if (gestures) {
            SwitchRow(
                title = stringResource(R.string.gold_haptics_switch),
                subtitle = stringResource(R.string.gold_haptics_switch_summary),
                checked = haptics,
            ) { on ->
                haptics = on
                GoldConfig(context).haptics = on
            }
            GoldGestureGuide()
        } else {
            buttons()
        }
    }
}

/** Extra things on the in-game menu's Session tab: the experiments and Check for updates. */
@Composable
fun GoldSessionActions(activity: Emulator) {
    GoldExperiments()
    FilledTonalButton(
        onClick = { GoldUpdateActivity.launch(activity) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.SystemUpdate, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.gold_check_updates))
    }
}
