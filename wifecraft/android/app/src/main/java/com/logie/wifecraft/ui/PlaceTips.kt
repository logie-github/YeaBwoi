package com.logie.wifecraft.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The first-use tips: one line on what the current mode does ([PlaceTip.of]) on the tooltip strip under the top bar, until
 * **Got it** (remembered for that tip: [onDismiss]). Never wider than the scene; the line wraps in two at most.
 */
@Composable
fun PlaceTipStrip(tip: PlaceTip, onDismiss: (PlaceTip) -> Unit, modifier: Modifier = Modifier) =
    Tooltip(tip.text, modifier, onDismiss = { onDismiss(tip) })
