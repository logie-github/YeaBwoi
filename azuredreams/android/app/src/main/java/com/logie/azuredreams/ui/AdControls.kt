// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.azuredreams.R

/** A PlayStation button as the guide shows it, in its usual color. */
private enum class GuideButton(val label: String, val color: Color) {
    DPAD("✚", Color(0xFFE8EEFB)),
    CROSS("✕", Color(0xFF7CB2E8)),
    CIRCLE("○", Color(0xFFFF6B6B)),
    SQUARE("□", Color(0xFFE59AD8)),
    TRIANGLE("△", Color(0xFF4FD1A5)),
    RUN("○+✚", Color(0xFFFF6B6B)),
    START("START", Color(0xFFB4C4EC)),
    SELECT("SELECT", Color(0xFFB4C4EC)),
    SHOULDER("R1·L1", Color(0xFFB4C4EC)),
    L1R1("L1·R1", Color(0xFFB4C4EC)),
    MENU("≡", AdColors.Gold),
}

private class GuideRow(@StringRes val gesture: Int, @StringRes val result: Int, val button: GuideButton)

private val GuideRows = listOf(
    GuideRow(R.string.ad_gesture_swipe, R.string.ad_button_dpad, GuideButton.DPAD),
    GuideRow(R.string.ad_gesture_tap, R.string.ad_button_cross, GuideButton.CROSS),
    GuideRow(R.string.ad_gesture_hold, R.string.ad_button_circle, GuideButton.CIRCLE),
    GuideRow(R.string.ad_gesture_run, R.string.ad_button_run, GuideButton.RUN),
    GuideRow(R.string.ad_gesture_tap_hold, R.string.ad_button_shoulder, GuideButton.SHOULDER),
    GuideRow(R.string.ad_gesture_two_finger_swipe_down, R.string.ad_button_square, GuideButton.SQUARE),
    GuideRow(R.string.ad_gesture_two_finger_swipe_up, R.string.ad_button_triangle, GuideButton.TRIANGLE),
    GuideRow(R.string.ad_gesture_two_finger_swipe_sideways, R.string.ad_button_l1r1, GuideButton.L1R1),
    GuideRow(R.string.ad_gesture_double_tap, R.string.ad_button_start, GuideButton.START),
    GuideRow(R.string.ad_gesture_two_finger_hold, R.string.ad_button_select, GuideButton.SELECT),
    GuideRow(R.string.ad_gesture_menu, R.string.ad_button_menu, GuideButton.MENU),
    GuideRow(R.string.ad_gesture_back, R.string.ad_button_menu, GuideButton.MENU),
)

/** Every gesture and the button it presses, with what that does in Azure Dreams. */
@Composable
fun AdGestureGuide(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in GuideRows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.button.label,
                    color = row.button.color,
                    fontSize = if (row.button.label.length > 2) 11.sp else 18.sp,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .widthIn(min = 62.dp)
                        .background(Color(0x66000000), RoundedCornerShape(50))
                        .border(1.dp, Color(0x55E8EEFB), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(row.gesture), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(row.result), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(
            stringResource(R.string.ad_gesture_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
