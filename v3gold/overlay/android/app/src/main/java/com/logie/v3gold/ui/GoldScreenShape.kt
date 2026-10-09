// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.logie.v3gold.GoldConfig
import com.logie.v3gold.GoldLab
import org.vita3k.emulator.R

/** The in-game menu's choice of how the game's picture fills the screen. Applies right away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoldScreenShape() {
    val context = LocalContext.current
    var shape by remember { mutableIntStateOf(GoldConfig(context).screenShape) }
    val choices = listOf(
        GoldConfig.SCREEN_SHAPE_ORIGINAL to R.string.gold_screen_original,
        GoldConfig.SCREEN_SHAPE_CROP_4_3 to R.string.gold_screen_crop,
        GoldConfig.SCREEN_SHAPE_SQUEEZE_4_3 to R.string.gold_screen_squeeze,
        GoldConfig.SCREEN_SHAPE_FILL to R.string.gold_screen_fill,
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.gold_screen_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in choices) {
                    FilterChip(
                        selected = shape == value,
                        onClick = {
                            shape = value
                            GoldLab.setScreenShape(context, value)
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            Text(
                stringResource(R.string.gold_screen_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
