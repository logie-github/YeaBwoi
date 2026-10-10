// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.pe.R

/** A PlayStation button as the guide shows it, in its usual color. */
private enum class GuideButton(val label: String, val color: Color) {
    DPAD("✚", Color(0xFFE8EEFB)),
    CROSS("✕", Color(0xFF7CB2E8)),
    CIRCLE("○", Color(0xFFFF6B6B)),
    SQUARE("□", Color(0xFFE59AD8)),
    TRIANGLE("△", Color(0xFF4FD1A5)),
    RUN("○+✚", Color(0xFFFF6B6B)),
    HOLD_TRIANGLE("○+△", Color(0xFF4FD1A5)),
    START("START", Color(0xFFB4C4EC)),
    SELECT("SELECT", Color(0xFFB4C4EC)),
    SHOULDER("R1·L1", Color(0xFFB4C4EC)),
    L1R1("L1·R1", Color(0xFFB4C4EC)),
    MENU("≡", PeColors.Teal),
}

private class GuideRow(@StringRes val gesture: Int, @StringRes val result: Int, val button: GuideButton)

private val GuideRows = listOf(
    GuideRow(R.string.pe_gesture_swipe, R.string.pe_button_dpad, GuideButton.DPAD),
    GuideRow(R.string.pe_gesture_tap, R.string.pe_button_cross, GuideButton.CROSS),
    GuideRow(R.string.pe_gesture_hold, R.string.pe_button_circle, GuideButton.CIRCLE),
    GuideRow(R.string.pe_gesture_run, R.string.pe_button_run, GuideButton.RUN),
    GuideRow(R.string.pe_gesture_hold_tap, R.string.pe_button_hold_triangle, GuideButton.HOLD_TRIANGLE),
    GuideRow(R.string.pe_gesture_tap_hold, R.string.pe_button_shoulder, GuideButton.SHOULDER),
    GuideRow(R.string.pe_gesture_double_tap, R.string.pe_button_triangle, GuideButton.TRIANGLE),
    GuideRow(R.string.pe_gesture_two_finger_swipe_up, R.string.pe_button_square, GuideButton.SQUARE),
    GuideRow(R.string.pe_gesture_two_finger_swipe_sideways, R.string.pe_button_l1r1, GuideButton.L1R1),
    GuideRow(R.string.pe_gesture_two_finger_swipe_down, R.string.pe_button_start, GuideButton.START),
    GuideRow(R.string.pe_gesture_two_finger_hold, R.string.pe_button_select, GuideButton.SELECT),
    GuideRow(R.string.pe_gesture_menu, R.string.pe_button_menu, GuideButton.MENU),
    GuideRow(R.string.pe_gesture_back, R.string.pe_button_menu, GuideButton.MENU),
)

/** Every gesture and the button it presses, with what that does in Parasite Eve. */
@Composable
fun PeGestureGuide(modifier: Modifier = Modifier) {
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
                        .drawBehind {
                            drawRect(Color(0x55000000))
                            drawBrackets(row.button.color, alpha = 0.6f, arm = 5.dp.toPx())
                        }
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
            stringResource(R.string.pe_gesture_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
