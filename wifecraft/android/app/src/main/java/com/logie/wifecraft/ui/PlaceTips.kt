package com.logie.wifecraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The first-use tips: one line on what the current mode does ([PlaceTip.of]) over the top of the stage, until **Got it**
 * (remembered for that tip: [onDismiss]). Never wider than the stage; the line wraps in two at most.
 */
@Composable
fun PlaceTipStrip(tip: PlaceTip, onDismiss: (PlaceTip) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.widthIn(max = 640.dp).background(Color(0xD0100C08)).border(1.dp, Color(0x88FFD100))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(tip.text, style = frizStyle(13.sp, Color.White, false), maxLines = 2, modifier = Modifier.weight(1f, fill = false).padding(vertical = 6.dp))
        Box(
            Modifier.heightIn(min = 48.dp).widthIn(min = 72.dp).clickable { onDismiss(tip) }.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Got it", style = frizStyle(13.sp)) }
    }
}
