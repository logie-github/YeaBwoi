package com.logie.wifecraft.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Overlay (the dimmed backdrop with one panel, moving in and out) is in MenuMotionUi.kt; the front menus' kit (WifeWindow,
// WifeButton...) in WowWidgets.kt.

/** Updates from the repository's releases, in a small front-menu window: what is out, Install. */
@Composable
fun UpdatePanel(status: String, canInstall: Boolean, onInstall: () -> Unit, onBack: () -> Unit) {
    WifeWindow(
        "Updates", onBack, Modifier.widthIn(max = 520.dp).padding(16.dp),
        subtitle = "Wifecraft updates itself from its releases",
        footer = {
            if (canInstall) WifeButton("Install", onInstall, Modifier.weight(1f), kind = WifeButtonKind.PRIMARY, sound = "ok")
            WifeButton("Okay", onBack, Modifier.weight(1f), kind = if (canInstall) WifeButtonKind.SECONDARY else WifeButtonKind.PRIMARY, sound = "close")
        },
    ) {
        Text(status, style = WifeTheme.bodyStyle(), modifier = Modifier.fillMaxWidth().padding(vertical = WifeTheme.gapXs))
    }
}

/** A short message in a front-menu window (the problem box): the words, and Close. */
@Composable
fun WifeMessage(title: String, message: String, onClose: () -> Unit) {
    WifeWindow(
        title, onClose, Modifier.widthIn(max = 500.dp).padding(16.dp),
        footer = { WifeButton("Close", onClose, Modifier.widthIn(min = 132.dp), kind = WifeButtonKind.PRIMARY, sound = "close") },
    ) {
        Text(message, style = WifeTheme.bodyStyle(), modifier = Modifier.fillMaxWidth())
    }
}
