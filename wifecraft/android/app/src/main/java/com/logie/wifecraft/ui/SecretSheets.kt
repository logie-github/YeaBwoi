package com.logie.wifecraft.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.creator.StockingColours
import com.logie.wifecraft.creator.StockingSpec
import com.logie.wifecraft.creator.StockingStyle
import com.logie.wifecraft.graphics.FingerSize
import com.logie.wifecraft.graphics.Graphics
import com.logie.wifecraft.simulation.Expression
import com.logie.wifecraft.simulation.InteractAction
import com.logie.wifecraft.simulation.InteractCamera
import com.logie.wifecraft.simulation.InteractMode
import com.logie.wifecraft.simulation.InteractView
import com.logie.wifecraft.simulation.LookTarget
import com.logie.wifecraft.simulation.SecretLight
import com.logie.wifecraft.simulation.SecretSettings

// The hidden mode's sheets (ui/SecretScene.kt owns them): the view presets, her outfit, the "Tell her" field and the settings.

@Composable
internal fun SecretViewSheet(camera: InteractCamera, tick: Int) {
    @Suppress("UNUSED_VARIABLE") val redraw = tick
    WifeNote("Drag on the background to turn the view, pinch or use two fingers to zoom, three to pan.")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (v in InteractView.entries) WifeChip(v.label, { camera.goTo(v) }, trailing = if (camera.current() == v) "✓" else null)
    }
    WifeButton("Back to overview", { camera.overview() }, Modifier.fillMaxWidth())
}

@Composable
internal fun SecretOutfitSheet(
    actorId: String?, gearOn: Boolean, onGear: (Boolean) -> Unit, stockings: StockingSpec?, onStockings: (StockingSpec?) -> Unit,
    stock: StockFabricController, mode: InteractMode, tick: Int,
) {
    @Suppress("UNUSED_VARIABLE") val redraw = tick
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        WifeButton("Gear on", { onGear(true) }, Modifier.weight(1f), kind = if (gearOn) WifeButtonKind.PRIMARY else WifeButtonKind.SECONDARY)
        WifeButton("Gear off", { onGear(false) }, Modifier.weight(1f), kind = if (!gearOn) WifeButtonKind.PRIMARY else WifeButtonKind.SECONDARY)
    }
    val cur = stockings ?: StockingSpec()
    WifeNote("Stockings" + (stockings?.let { ": ${it.style.label}, ${it.denier} D, ${it.colour.name}" } ?: ""))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (s in StockingStyle.entries) WifeChip(s.label, { onStockings(cur.copy(style = s)) }, trailing = if (stockings?.style == s) "✓" else null)
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (c in StockingColours.PRESETS) WifeChip(c.name, { onStockings(cur.copy(colour = c)) }, trailing = if (stockings?.colour == c) "✓" else null)
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        WifeChip("Seam", { onStockings(cur.copy(seam = !cur.seam)) }, trailing = if (cur.seam) "✓" else null)
        WifeChip("Lace top", { onStockings(cur.copy(lace = !cur.lace)) }, trailing = if (cur.lace) "✓" else null)
    }
    StockingsPanel(stockings, onStockings, stock.fabric, mode.stock, { stock.setOptions(it) },
        { actorId?.let { stock.restore(it) } }, { actorId?.let { stock.repairAll(it) } })
}

@Composable
internal fun SecretTellSheet(typed: String, onTyped: (String) -> Unit, onTell: (String) -> Unit, onAction: (InteractAction) -> Unit) {
    Box(Modifier.fillMaxWidth().height(48.dp)) { InteractTellRow(typed, onTyped, onTell) }
    WifeNote("She kneels. You can tell her where to look, how to look and what she wears.")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (a in listOf(InteractAction.Look(LookTarget.CAMERA), InteractAction.Look(LookTarget.AWAY), InteractAction.Look(LookTarget.DOWN)))
            WifeChip(a.label, { onAction(a) }, trailing = null)
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (a in listOf(Expression.SMILE, Expression.BLUSH, Expression.TEASE, Expression.SHY).map { InteractAction.Express(it) })
            WifeChip(a.label, { onAction(a) }, trailing = null)
    }
}

@Composable
internal fun SecretSettingsSheet(settings: SecretSettings, onChange: (SecretSettings) -> Unit, mode: InteractMode, onTick: () -> Unit, pose: String) {
    val context = LocalContext.current
    Graphics.load(context)
    val g by Graphics.settings.collectAsState()
    val edit = { next: com.logie.wifecraft.graphics.GraphicsSettings -> Graphics.set(context, next.withMatchingPreset()) }
    WifeSwitchRow("Floor shadow", settings.floorShadow, { onChange(settings.copy(floorShadow = it)) }, detail = "A soft shadow under her knees")
    WifeSwitchRow("Breathing", settings.breathing, { onChange(settings.copy(breathing = it)) }, detail = "A tiny sway while she kneels")
    WifeSwitchRow("Depth of field", settings.depthOfField, { onChange(settings.copy(depthOfField = it)) }, detail = "The background blur follows her; off keeps everything sharp")
    WifeSwitchRow("Haptics", mode.haptics, { mode.haptics = it; onTick() })
    WifeNote("Light")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (l in SecretLight.entries) WifeChip(l.label, { onChange(settings.copy(light = l)) }, trailing = if (settings.light == l) "✓" else null)
    }
    WifeNote("Flesh contact")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for ((n, label) in listOf(0 to "Off", 1 to "Low", 2 to "High")) WifeChip(label, { edit(g.copy(fleshContact = n)) }, trailing = if (g.fleshContact == n) "✓" else null)
    }
    WifeSliderRow("Softness", g.fleshSoftness, { edit(g.copy(fleshSoftness = it)) }, valueText = "${g.fleshSoftness}%", range = 0..200)
    WifeSliderRow("Finger size", g.fingerSize, { edit(g.copy(fingerSize = FingerSize.clamp(it))) }, valueText = "%.2fx".format(g.fingerSize / 100f), range = FingerSize.MIN..FingerSize.MAX)
    WifeNote("Pose: $pose.")
}
