// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.v3gold.GoldConfig
import com.logie.v3gold.GoldLab
import kotlinx.coroutines.delay
import org.vita3k.emulator.R

/**
 * The in-game menu's experiments (GoldLab): removing the motion blur, and 60 FPS with a speed fix
 * to pick. Shows what the renderer and the search found, so the right settings can be found on the
 * phone and sent back as a screenshot.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoldExperiments() {
    val context = LocalContext.current
    val config = remember { GoldConfig(context) }
    var blurFix by remember { mutableStateOf(config.blurFix) }
    var sixtyFps by remember { mutableStateOf(config.sixtyFps) }
    var speedFix by remember { mutableIntStateOf(config.speedFix) }
    var speedFixCode by remember { mutableStateOf(config.speedFixCode) }
    var showDetails by remember { mutableStateOf(false) }
    var fps by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf(emptyList<String>()) }
    var dataCount by remember { mutableIntStateOf(0) }
    var codeCount by remember { mutableIntStateOf(0) }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            fps = GoldLab.fps()
            report = GoldLab.blurReport()
            dataCount = GoldLab.dataValues.size
            codeCount = GoldLab.codeValues.size
            searched = GoldLab.searched
            delay(1000)
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.gold_lab_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            // Motion blur
            ExperimentSwitch(
                title = stringResource(R.string.gold_lab_blur),
                summary = stringResource(R.string.gold_lab_blur_summary),
                checked = blurFix,
            ) {
                blurFix = it
                GoldLab.setBlurFix(context, it)
            }
            val removed = report.count { !it.startsWith("kept") }
            Text(
                if (report.isEmpty()) {
                    stringResource(R.string.gold_lab_blur_none)
                } else {
                    stringResource(R.string.gold_lab_blur_status, removed, report.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showDetails = !showDetails }) {
                Text(stringResource(if (showDetails) R.string.gold_lab_hide_details else R.string.gold_lab_show_details))
            }
            if (showDetails) {
                for (line in report) {
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                }
                Text(
                    stringResource(R.string.gold_lab_details_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // 60 FPS
            ExperimentSwitch(
                title = stringResource(R.string.gold_lab_sixty),
                summary = if (fps > 0) {
                    stringResource(R.string.gold_lab_sixty_summary_fps, fps)
                } else {
                    stringResource(R.string.gold_lab_sixty_summary)
                },
                checked = sixtyFps,
            ) {
                sixtyFps = it
                GoldLab.setSixtyFps(context, it)
            }
            if (sixtyFps) {
                Text(
                    stringResource(R.string.gold_lab_speed_fix),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeedChip(stringResource(R.string.gold_lab_speed_fix_off), speedFix == GoldLab.SPEED_FIX_OFF) {
                        speedFix = GoldLab.SPEED_FIX_OFF
                        GoldLab.setSpeedFix(context, speedFix)
                    }
                    SpeedChip(stringResource(R.string.gold_lab_speed_fix_all, dataCount), speedFix == GoldLab.SPEED_FIX_ALL) {
                        speedFix = GoldLab.SPEED_FIX_ALL
                        GoldLab.setSpeedFix(context, speedFix)
                    }
                    for (number in 1..dataCount) {
                        SpeedChip(number.toString(), speedFix == number) {
                            speedFix = number
                            GoldLab.setSpeedFix(context, speedFix)
                        }
                    }
                }
                Text(
                    when {
                        !searched -> stringResource(R.string.gold_lab_speed_searching)
                        dataCount == 0 && codeCount == 0 -> stringResource(R.string.gold_lab_speed_none)
                        else -> stringResource(R.string.gold_lab_speed_found, dataCount, codeCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExperimentSwitch(
                    title = stringResource(R.string.gold_lab_speed_code),
                    summary = stringResource(R.string.gold_lab_speed_code_summary),
                    checked = speedFixCode,
                ) {
                    speedFixCode = it
                    GoldLab.setSpeedFixCode(context, it)
                }
                TextButton(onClick = { GoldLab.searchAgain() }) {
                    Text(stringResource(R.string.gold_lab_search_again))
                }
                Text(
                    stringResource(R.string.gold_lab_speed_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExperimentSwitch(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SpeedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
