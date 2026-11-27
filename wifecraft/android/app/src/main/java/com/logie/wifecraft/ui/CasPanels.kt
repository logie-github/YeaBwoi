package com.logie.wifecraft.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.BodyRegion
import com.logie.wifecraft.creator.BodySculpt
import com.logie.wifecraft.creator.BodySide
import com.logie.wifecraft.creator.BustButtDesigner
import com.logie.wifecraft.creator.DesignerGroup
import com.logie.wifecraft.creator.DesignerView
import com.logie.wifecraft.creator.ShapePreset
import com.logie.wifecraft.creator.CasCamera
import com.logie.wifecraft.creator.CasPersonality
import com.logie.wifecraft.creator.CasPreset
import com.logie.wifecraft.creator.CasPresetStore
import com.logie.wifecraft.creator.CasTab
import com.logie.wifecraft.creator.CasTabs
import com.logie.wifecraft.creator.Choice
import com.logie.wifecraft.creator.ClothingSet
import com.logie.wifecraft.creator.CreatorModel
import com.logie.wifecraft.creator.Initiation
import com.logie.wifecraft.creator.Pace
import com.logie.wifecraft.creator.Preferences
import com.logie.wifecraft.creator.SculptPart
import com.logie.wifecraft.creator.SculptSlider
import com.logie.wifecraft.creator.SculptSupport
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.creator.StageKind
import com.logie.wifecraft.engine.Thumbs
import com.logie.wifecraft.simulation.Trait
import com.logie.wifecraft.simulation.Traits
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The Create-a-Character shell's panel (ui/CasScreen.kt): each tab's controls in the Warcraft look. Option rows with the
 * creator's own arrows and choice box (a tap on the box opens the choices: colours as swatches, the others as numbered
 * tiles), the sculpt sliders, the Personality tab's traits and preferences, the race and sex step, and the Presets window.
 * Not run here (Compose can't be): the logic under it is creator/Cas*.kt, BodySculpt.kt, tested on the JVM.
 */

@Composable
fun CasPanel(
    tab: CasTab, model: CreatorModel, rev: Int, atlas: ArtAtlas, icons: GameIcons?, portraits: Thumbs?,
    focus: BodyRegion, onChanged: (look: Boolean) -> Unit, onSculpt: (send: Boolean, done: Boolean) -> Unit, sculptRateMs: Long,
    sculptUi: CasSculptState = remember { CasSculptState() },
    sculptActions: CasSculptActions? = null,
) {
    Column(Modifier.fillMaxSize().wifeSurface().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tab.label, style = WifeTheme.titleStyle(), maxLines = 1, modifier = Modifier.weight(1f))
            val sub = rev.let {
                when (tab) {
                    CasTab.BODY -> if (LocalCasHd.current?.setFor?.invoke(model.appearance) != null) "HD body (Liard)" else "Game body"
                    CasTab.RACE -> model.model.race.name
                    else -> model.model.race.name + " · " + if (model.appearance.sex == Sex.FEMALE) "Female" else "Male"
                }
            }
            Text(sub, style = WifeTheme.captionStyle(), maxLines = 1, modifier = Modifier.padding(end = 8.dp))
        }
        WifeDivider(Modifier.padding(bottom = 4.dp, end = 6.dp))
        val scroll = rememberScrollState()
        LaunchedEffect(tab) { scroll.scrollTo(0) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(end = 6.dp)) {
            when (tab) {
                CasTab.RACE -> CasRaceSection(model, rev, atlas, onChanged)
                CasTab.BODY -> CasBodySection(model, rev, atlas, focus, onChanged, onSculpt, sculptRateMs, sculptUi, sculptActions)
                CasTab.PERSONALITY -> CasPersonalitySection(model, rev, icons, onChanged)
                CasTab.OUTFIT -> { CasHdOutfitNote(model, rev, onChanged); CasGearSection(model, rev, onChanged) }
                CasTab.FACE, CasTab.HAIR, CasTab.MAKEUP, CasTab.ACCESSORIES ->
                    if (rev.let { LocalCasHd.current?.setFor?.invoke(model.appearance) } != null) CasHdSection(model, rev, LocalCasHd.current, onChanged, tab)
                    else CasRows(remember(rev, tab, model.appearance.raceId, model.appearance.sex) { CasTabs.rows(model, tab) }, model, atlas, onChanged)
                else -> CasRows(remember(rev, tab, model.appearance.raceId, model.appearance.sex) { CasTabs.rows(model, tab) }, model, atlas, onChanged)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---- option rows --------------------------------------------------------------------------------------------------------------

@Composable
private fun CasRows(rows: List<CreatorModel.Row>, model: CreatorModel, atlas: ArtAtlas, onChanged: (Boolean) -> Unit) {
    var open by remember { mutableStateOf<Int?>(null) }
    if (rows.isEmpty()) WifeNote("Nothing here to change for this race and sex.", Modifier.padding(vertical = 12.dp))
    for (row in rows) {
        val o = row.option
        if (o.isSwitch) {
            val on = o.choices.indexOfFirst { it.id == row.chosen.id } == 1
            WifeSwitchRow(o.name, on, { v -> o.choices.getOrNull(if (v) 1 else 0)?.let { model.choose(o.id, it.id); onChanged(true) } },
                detail = row.why, enabled = row.enabled)
            continue
        }
        CasOptionRow(row, atlas, open == o.id,
            onStep = { d -> model.step(o.id, d); onChanged(true) },
            onOpen = { open = if (open == o.id) null else o.id })
        if (open == o.id && row.enabled) CasChoices(o.choices, row.chosen) { c -> model.choose(o.id, c.id); onChanged(true) }
    }
}

/** An option's line: its name, the previous arrow, the choice in the creator's box (its colour, or its name or number), the next arrow. */
@Composable
private fun CasOptionRow(row: CreatorModel.Row, atlas: ArtAtlas, open: Boolean, onStep: (Int) -> Unit, onOpen: () -> Unit) {
    val o = row.option
    val at = o.choices.indexOfFirst { it.id == row.chosen.id } + 1
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).alpha(if (row.enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(o.name, style = WifeTheme.labelStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.38f))
        CasArrow(atlas, back = true, enabled = row.enabled) { onStep(-1) }
        Box(Modifier.weight(0.62f).height(38.dp).warPress(enabled = row.enabled, onClick = onOpen), contentAlignment = Alignment.Center) {
            val box = atlas.piece("charactercreate-customize-dropdownbox" + if (open) "-open" else "")?.image
            if (box != null) Image(box, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
            else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(WifeTheme.slateDeep).border(1.dp, WifeTheme.trimSoft, RoundedCornerShape(6.dp)))
            val c = row.chosen
            when {
                !row.enabled && row.why != null -> Text(row.why, style = WifeTheme.captionStyle(WifeTheme.gold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp))
                c.swatch1 != null -> CasSwatch(c.swatch1, c.swatch2, Modifier.size(46.dp, 16.dp))
                else -> Text(choiceLabel(c.name, false, at), style = frizStyle(WifeTheme.body, WifeTheme.gold), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
        CasArrow(atlas, back = false, enabled = row.enabled) { onStep(1) }
    }
}

@Composable
private fun CasArrow(atlas: ArtAtlas, back: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val name = "charactercreate-customize-" + (if (back) "backbutton" else "nextbutton") + if (enabled) "" else "-disabled"
    Box(Modifier.size(44.dp).warPress(enabled = enabled, repeatable = true, onClick = onClick), contentAlignment = Alignment.Center) {
        val p = (atlas.piece(name) ?: atlas.piece(name.removeSuffix("-disabled")))?.image
        if (p != null) Image(p, if (back) "Previous" else "Next", Modifier.size(34.dp))
        else Text(if (back) "◀" else "▶", style = frizStyle(WifeTheme.label, WifeTheme.gold))
    }
}

/** The choices of an option, opened under its line: colours as swatches, the others as numbered tiles with their names. */
@Composable
private fun CasChoices(choices: List<Choice>, chosen: Choice, onPick: (Choice) -> Unit) {
    val coloured = choices.any { it.swatch1 != null }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val cell = if (coloured) 48f else 96f
        val columns = max(1, ((maxWidth.value + 6f) / (cell + 6f)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (line in choices.withIndex().chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((i, c) in line) {
                        val on = c.id == chosen.id
                        val shape = RoundedCornerShape(8.dp)
                        Box(Modifier.weight(1f).height(48.dp).clip(shape)
                            .background(Brush.verticalGradient(listOf(WifeTheme.secondaryTop, WifeTheme.secondaryBottom)))
                            .border(if (on) 2.dp else 1.dp, if (on) WifeTheme.gold else WifeTheme.trimSoft, shape)
                            .warPress(sound = "select") { onPick(c) },
                            contentAlignment = Alignment.Center) {
                            if (c.swatch1 != null) CasSwatch(c.swatch1, c.swatch2, Modifier.fillMaxSize().padding(4.dp))
                            else Text(if (c.name.isNotEmpty()) c.name else (i + 1).toString(), style = frizStyle(WifeTheme.caption, if (on) Color.White else WifeTheme.textDim),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A colour's swatch: the first, and where there is a second, it in the lower corner (the creator's two-colour swatches). */
@Composable
fun CasSwatch(c1: Int, c2: Int?, modifier: Modifier) {
    Canvas(modifier) {
        val r = CornerRadius(4.dp.toPx())
        drawRoundRect(rgbColour(c1), cornerRadius = r)
        if (c2 != null) drawRoundRect(rgbColour(c2), Offset(size.width * 0.55f, size.height * 0.5f), Size(size.width * 0.45f, size.height * 0.5f), r)
        drawRoundRect(Color(0x99000000), cornerRadius = r, style = Stroke(1.dp.toPx()))
    }
}

fun rgbColour(v: Int) = Color(0xFF000000.toInt() or (v and 0xFFFFFF))

// ---- the Body tab ---------------------------------------------------------------------------------------------------------------

@Composable
private fun CasBodySection(
    model: CreatorModel, rev: Int, atlas: ArtAtlas, focus: BodyRegion,
    onChanged: (Boolean) -> Unit, onSculpt: (send: Boolean, done: Boolean) -> Unit, rateMs: Long,
    sculptUi: CasSculptState, actions: CasSculptActions?,
) {
    var part by remember { mutableStateOf(0) }
    val focused = CasTabs.slidersFor(focus, model.appearance.sex == Sex.FEMALE)
    LaunchedEffect(focus) { if (focused.isNotEmpty() && part == 0) part = 1 }
    WifeTabs(listOf("Skin & Shape", "Sculpt", if (rev.let { model.appearance.sex == Sex.FEMALE }) "Bust & Butt" else "Chest & Glutes"), part, { next ->
        part = next
        if (next == 2) {   // (the designer opens on its group's own view: the bust from the side, the butt from behind)
            val groups = DesignerGroup.of(model.appearance.sex == Sex.FEMALE)
            val g = sculptUi.group?.takeIf { it in groups } ?: groups.first()
            sculptUi.group = g
            sculptUi.view = DesignerView.first(g)
            actions?.onView?.invoke(sculptUi.view, g)
        }
    }, Modifier.padding(bottom = 6.dp))
    if (part == 0) {
        CasBodyKindNote(model, rev, onChanged)
        if (rev.let { LocalCasHd.current?.setFor?.invoke(model.appearance) } != null) {
            // a race and sex with a Liard set: the figure's skin and marks here
            CasHdSection(model, rev, LocalCasHd.current, onChanged, CasTab.BODY)
            return
        }
        CasRows(remember(rev, model.appearance.raceId, model.appearance.sex) { CasTabs.rows(model, CasTab.BODY) }, model, atlas, onChanged)
        return
    }
    if (part == 2) { CasDesignerSection(model, rev, onSculpt, rateMs, sculptUi, actions); return }
    // the part chosen (a tap or a held press on her picks it too: ui/CasScreen.kt), its sliders, Symmetry and Lift for the drags
    val female = rev.let { model.appearance.sex == Sex.FEMALE }
    LaunchedEffect(focus) { SculptPart.of(focus)?.let { sculptUi.part = it } }
    WifeTabs(SculptPart.entries.map { it.label }, sculptUi.part.ordinal, { sculptUi.part = SculptPart.entries[it] }, Modifier.padding(bottom = 4.dp))
    WifeNote("Hold a part of her body, then drag on her: up and down its size, left and right its width. Lift (or a second finger) lifts and spaces it instead.",
        Modifier.padding(vertical = 4.dp))
    WifeSwitchRow("Symmetry", sculptUi.symmetry, { sculptUi.symmetry = it }, detail = "Off: a drag on her changes the side you hold")
    WifeSwitchRow("Lift", sculptUi.lift, { sculptUi.lift = it }, detail = "Drags lift and space the part instead of sizing it")
    val sculpt = rev.let { model.appearance.sculpt ?: BodySculpt() }
    var lastSent by remember { mutableLongStateOf(0L) }
    for (s in sculptUi.part.sliders(female)) {
        val why = SculptSupport.why(s, bodyKindOf(model.appearance))
        CasSculptRow(s, sculpt[s], enabled = why == null, glow = s in focused, why = why, label = s.labelFor(female),
            onChange = { v ->
                model.setSculpt((model.appearance.sculpt ?: BodySculpt()).with(s, v))
                // the panel shows every move; the engine sculpts the body again at most every rateMs while dragging
                val now = System.currentTimeMillis()
                val send = now - lastSent >= rateMs
                if (send) lastSent = now
                onSculpt(send, false)
            },
            onDone = { onSculpt(true, true) })
        if (sculpt.side(s) != 0f) {
            // Symmetry off left the two sides apart: what each has, and a way to make them alike
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val l = (sculpt.on(s, BodySide.LEFT) * 100).roundToInt(); val r = (sculpt.on(s, BodySide.RIGHT) * 100).roundToInt()
                WifeNote("Left ${if (l > 0) "+$l" else "$l"} · Right ${if (r > 0) "+$r" else "$r"}", Modifier.weight(1f))
                WifeChip("Even", { model.setSculpt((model.appearance.sculpt ?: BodySculpt()).even(s)); onSculpt(true, true) }, trailing = null)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        WifeButton("Reset ${sculptUi.part.label}", {
            model.setSculpt((model.appearance.sculpt ?: BodySculpt()).reset(sculptUi.part)); onSculpt(true, true)
        }, kind = WifeButtonKind.QUIET)
        WifeButton("Reset all", { model.setSculpt(null); onSculpt(true, true) }, kind = WifeButtonKind.QUIET)
    }
}

/**
 * The Sculpt's own choices, shared by the panel and the stage's drag handles (ui/CasScreen.kt): the part chosen, Symmetry,
 * Lift, and what a drag on her shows by the finger.
 */
class CasSculptState {
    var part by mutableStateOf(SculptPart.OVERALL)
    var symmetry by mutableStateOf(true)
    var lift by mutableStateOf(false)
    /** The designer's Projection drag mode (creator/SculptDrag.kt axes: the bust's or butt's projection and roundness). */
    var project by mutableStateOf(false)
    var dragLabel by mutableStateOf<String?>(null)
    /** The Bust & Butt designer's group and camera preset (null: the first for her sex). */
    var group by mutableStateOf<DesignerGroup?>(null)
    var view by mutableStateOf(DesignerView.SIDE)
}

/** What the Bust & Butt designer asks of the screen (ui/CasScreen.kt): the camera to a preset, and Undo and Redo. */
class CasSculptActions(
    val onView: (DesignerView, DesignerGroup) -> Unit = { _, _ -> },
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val canUndo: () -> Boolean = { false },
    val canRedo: () -> Boolean = { false },
)

/**
 * The Bust & Butt designer (a man's Chest & Glutes): its group, the camera's presets (Front, 3/4, Side, Back), the drag's mode on
 * her body (Size, Lift, Projection), Mirror, the quick shapes (gliding the sliders there), the group's big sliders drawn live
 * as they move (the engine's live sculpt), each side's balance with Mirror off, Randomize, Reset, Undo and Redo.
 */
@Composable
private fun CasDesignerSection(
    model: CreatorModel, rev: Int, onSculpt: (send: Boolean, done: Boolean) -> Unit, rateMs: Long, sculptUi: CasSculptState,
    actions: CasSculptActions?,
) {
    val female = rev.let { model.appearance.sex == Sex.FEMALE }
    val groups = DesignerGroup.of(female)
    val group = sculptUi.group?.takeIf { it in groups } ?: groups.first()
    val scope = rememberCoroutineScope()
    var glide by remember { mutableStateOf<Job?>(null) }
    var lastSent by remember { mutableLongStateOf(0L) }
    fun current() = model.appearance.sculpt ?: BodySculpt()
    fun look(v: DesignerView, g: DesignerGroup) { sculptUi.view = v; actions?.onView?.invoke(v, g) }
    // a glide to a new shape (a preset, Randomize, Reset): the sliders move there over ANIM_MS, sent each frame; one Undo step
    fun glideTo(target: BodySculpt) {
        glide?.cancel()
        val from = current()
        val sliders = group.sliders
        glide = scope.launch {
            val t0 = System.currentTimeMillis()
            while (true) {
                val t = (System.currentTimeMillis() - t0).toFloat() / BustButtDesigner.ANIM_MS
                if (t >= 1f) break
                model.setSculpt(BustButtDesigner.tween(from, target, t, sliders).takeIf { !it.isNeutral })
                onSculpt(true, false)
                delay(16)
            }
            model.setSculpt(BustButtDesigner.tween(from, target, 1f, sliders).takeIf { !it.isNeutral })
            onSculpt(true, true)
        }
    }
    fun moved(next: BodySculpt) {
        glide?.cancel()
        model.setSculpt(next.takeIf { !it.isNeutral })
        val now = System.currentTimeMillis()
        val send = now - lastSent >= rateMs
        if (send) lastSent = now
        onSculpt(send, false)
    }
    WifeTabs(groups.map { it.label }, groups.indexOf(group), { i -> sculptUi.group = groups[i]; look(DesignerView.first(groups[i]), groups[i]) },
        Modifier.padding(bottom = 4.dp))
    // the camera's presets
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        for (v in DesignerView.entries) WifeChip(v.label, { look(v, group) }, trailing = if (sculptUi.view == v) "•" else null)
    }
    // the drag on her body: hold the bust or the butt, then drag (a second finger: Lift)
    val mode = when { sculptUi.project -> 2; sculptUi.lift -> 1; else -> 0 }
    WifeTabs(listOf("Drag: Size", "Lift", "Projection"), mode, { m -> sculptUi.lift = m == 1; sculptUi.project = m == 2 },
        Modifier.padding(top = 6.dp, bottom = 2.dp))
    WifeNote(when (mode) {
        1 -> "Hold her ${group.label.lowercase()}, then drag: up and down lifts it, left and right spaces it."
        2 -> "Hold her ${group.label.lowercase()}, then drag: up and down its projection, left and right its roundness."
        else -> "Hold her ${group.label.lowercase()}, then drag: up and down its size, left and right its width."
    }, Modifier.padding(bottom = 2.dp))
    WifeSwitchRow("Mirror", sculptUi.symmetry, { on ->
        sculptUi.symmetry = on
        if (on && current().isSided) { model.setSculpt(BustButtDesigner.mirror(current(), group).takeIf { !it.isNeutral }); onSculpt(true, true) }
    }, detail = "Off: each side has its own balance (and a drag changes the side you hold)")
    // the quick shapes
    WifeSectionHeader("Shapes")
    val sculpt = rev.let { current() }
    val at = BustButtDesigner.presetOf(sculpt, group)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        for (p in ShapePreset.entries) WifeChip(p.label, { glideTo(BustButtDesigner.applyPreset(current(), p, group)) }, trailing = if (at == p) "•" else null)
    }
    WifeSectionHeader(group.label)
    for (s in group.sliders) {
        CasSculptRow(s, sculpt[s], enabled = true, glow = false, why = null, label = group.labelOf(s, female), tall = true,
            onChange = { v -> moved(current().with(s, v)) }, onDone = { onSculpt(true, true) })
        if (!sculptUi.symmetry && s.sided) {
            // each side's balance: her left above the value, her right below
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val l = (sculpt.on(s, BodySide.LEFT) * 100).roundToInt(); val r = (sculpt.on(s, BodySide.RIGHT) * 100).roundToInt()
                WifeNote("L ${if (l > 0) "+$l" else "$l"}", Modifier.width(44.dp))
                Box(Modifier.weight(1f)) {
                    WifeSlider((sculpt.side(s) + 1f) / 2f, { f -> moved(current().balanced(s, f * 2f - 1f)) }, Modifier.fillMaxWidth(),
                        onFinished = { onSculpt(true, true) })
                }
                WifeNote("R ${if (r > 0) "+$r" else "$r"}", Modifier.width(44.dp))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically) {
        WifeButton("Randomize", { glideTo(BustButtDesigner.randomize(current(), Random(System.nanoTime()), group)) }, kind = WifeButtonKind.QUIET)
        WifeButton("Reset", { glideTo(BustButtDesigner.reset(current(), group)) }, kind = WifeButtonKind.QUIET)
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically) {
        WifeButton("Undo", { glide?.cancel(); actions?.onUndo?.invoke() }, kind = WifeButtonKind.QUIET,
            enabled = rev.let { actions?.canUndo?.invoke() ?: false })
        WifeButton("Redo", { glide?.cancel(); actions?.onRedo?.invoke() }, kind = WifeButtonKind.QUIET,
            enabled = rev.let { actions?.canRedo?.invoke() ?: false })
    }
}

/**
 * HD Models in the creator (bodyparts/HdModels): for a race and sex with an imported HD set ([setFor] non-null), the figure's
 * own choices replace the game's: the Body tab's skin tint and body marks, the Face tab's eyes and face marks, the Hair tab's
 * styles and colours, Accessories' jewellery; the Outfit tab's class picks the set's outfit (Gear on). The game's own option
 * rows for that race aren't shown (they change nothing on the HD figure). Provided by the host ([LocalCasHd]); null: no sets.
 */
class CasHdBinding(
    /** The set drawn for this look: a set imported for its race and sex and the character not asking for the game's model. */
    val setFor: (Appearance) -> com.logie.wifecraft.bodyparts.HdSetInfo?,
    /** The set imported for its race and sex whatever the character asked ("Use game model instead" is offered where there is one). */
    val available: (Appearance) -> com.logie.wifecraft.bodyparts.HdSetInfo? = setFor,
)

val LocalCasHd = staticCompositionLocalOf<CasHdBinding?> { null }

/** Which body the creator's look is drawn with (the Options' switch, the imported sets and the character's own choice). */
@Composable
private fun bodyKindOf(a: Appearance): com.logie.wifecraft.bodyparts.BodyKind =
    if (LocalCasHd.current?.setFor?.invoke(a) != null) com.logie.wifecraft.bodyparts.BodyKind.LIARD else com.logie.wifecraft.bodyparts.BodyKind.GAME

/**
 * The Body tab's read-only note, "Body: HD (Liard)" or "Body: Game model" (bodyparts/Bodies: there are only the two), with a
 * per-character "Use game model instead" switch where a Liard set exists for her race and sex.
 */
@Composable
private fun CasBodyKindNote(model: CreatorModel, rev: Int, onChanged: (Boolean) -> Unit) {
    val hd = LocalCasHd.current
    val a = rev.let { model.appearance }
    val avail = hd?.available?.invoke(a)
    val kind = if (hd?.setFor?.invoke(a) != null) com.logie.wifecraft.bodyparts.BodyKind.LIARD else com.logie.wifecraft.bodyparts.BodyKind.GAME
    val note = if (kind == com.logie.wifecraft.bodyparts.BodyKind.LIARD) "Body: HD (Liard)"
        else "Body: Game model" + if (avail != null) " (chosen)" else ""
    WifeNote(note, Modifier.padding(bottom = 4.dp))
    if (avail != null) {
        val o = a.hd ?: com.logie.wifecraft.bodyparts.HdOptions()
        WifeSwitchRow("Use game model instead", o.useGame, { on -> model.edit { it.copy(hd = o.copy(useGame = on)) }; onChanged(true) },
            detail = "Draws this character on the game's own model instead of the Liard figure")
    }
}

@Composable
private fun CasHdOutfitNote(model: CreatorModel, rev: Int, onChanged: (Boolean) -> Unit) {
    val set = rev.let { LocalCasHd.current?.setFor?.invoke(model.appearance) } ?: return
    val a = model.appearance
    val o = a.hd ?: com.logie.wifecraft.bodyparts.HdOptions()
    val (idx, note) = com.logie.wifecraft.bodyparts.HdDress.outfitFor(set, a.classId)
    val what = when {
        !a.gear -> "Gear off: the HD figure bare."
        o.outfit >= 0 -> "HD outfit: ${set.outfits.getOrNull(o.outfit)?.label ?: "?"} (chosen)."
        idx >= 0 -> "HD outfit for this class: ${set.outfits[idx].label}." + (note?.let { " ($it)" } ?: "")
        else -> note ?: ""
    }
    WifeNote(what, Modifier.padding(bottom = 6.dp))
    val names = listOf("By class") + set.outfits.map { it.label }
    CasHdStepper("HD outfit", names, (o.outfit + 1).coerceIn(0, names.size - 1)) { at ->
        model.edit { it.copy(hd = o.copy(outfit = at - 1)) }
        onChanged(true)
    }
}

@Composable
private fun CasHdStepper(label: String, names: List<String>, at: Int, onAt: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = WifeTheme.labelStyle(), maxLines = 1, modifier = Modifier.weight(0.38f))
        val n = names.size
        Box(Modifier.size(44.dp).warPress(enabled = n > 1, repeatable = true) { onAt(Math.floorMod(at - 1, n)) }, contentAlignment = Alignment.Center) {
            Text("◀", style = frizStyle(WifeTheme.label, WifeTheme.gold))
        }
        Box(Modifier.weight(0.62f).height(38.dp).clip(RoundedCornerShape(6.dp)).background(WifeTheme.slateDeep).border(1.dp, WifeTheme.trimSoft, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center) {
            Text(names.getOrNull(at) ?: "", style = frizStyle(WifeTheme.body, WifeTheme.gold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(44.dp).warPress(enabled = n > 1, repeatable = true) { onAt(Math.floorMod(at + 1, n)) }, contentAlignment = Alignment.Center) {
            Text("▶", style = frizStyle(WifeTheme.label, WifeTheme.gold))
        }
    }
}

/** The HD figure's own rows on [tab] (see [CasHdBinding]). */
@Composable
private fun CasHdSection(model: CreatorModel, rev: Int, hd: CasHdBinding?, onChanged: (Boolean) -> Unit, tab: CasTab) {
    val set = rev.let { hd?.setFor?.invoke(model.appearance) } ?: return
    val o = rev.let { model.appearance.hd ?: com.logie.wifecraft.bodyparts.HdOptions() }
    fun put(next: com.logie.wifecraft.bodyparts.HdOptions) { model.edit { it.copy(hd = next) }; onChanged(true) }
    WifeSectionHeader("HD figure")
    WifeNote("${set.label}: drawn instead of the game's model. The game's own ${tab.label.lowercase()} choices don't apply to it.",
        Modifier.padding(bottom = 6.dp))
    when (tab) {
        CasTab.BODY -> {
            if (set.bodies.size > 1) CasHdStepper("Body", set.bodies, o.body.coerceIn(0, set.bodies.size - 1)) { put(o.copy(body = it)) }
            if (set.tints.isNotEmpty()) CasHdStepper("Skin", set.tints, o.tint.coerceIn(0, set.tints.size - 1)) { put(o.copy(tint = it)) }
            // the model's own shape keys (breasts, hips, thighs...), 0 to 100 %
            set.shapes.forEachIndexed { i, label ->
                WifeSliderRow(label, Math.round(o.shapes.getOrElse(i) { 0f } * 100), { v -> put(o.withShape(i, v / 100f)) })
            }
            if (set.bodyMarks) WifeSwitchRow("Body marks", o.bodyMarks, { put(o.copy(bodyMarks = it)) })
            WifeNote("The sculpt sliders and Bust & Butt shape the HD figure too.", Modifier.padding(top = 4.dp))
        }
        CasTab.FACE, CasTab.MAKEUP -> {
            if (set.eyes.isNotEmpty()) CasHdStepper("Eyes", set.eyes, o.eyes.coerceIn(0, set.eyes.size - 1)) { put(o.copy(eyes = it)) }
            val marks = listOf("None") + set.faceMarks
            if (set.faceMarks.isNotEmpty()) CasHdStepper("Face marks", marks, (o.faceMark + 1).coerceIn(0, marks.size - 1)) { put(o.copy(faceMark = it - 1)) }
        }
        CasTab.HAIR -> {
            val styles = set.hair + "None"
            val at = if (o.hair == -2) styles.size - 1 else o.hair.coerceIn(0, styles.size - 1)
            CasHdStepper("Style", styles, at) { put(o.copy(hair = if (it == styles.size - 1) -2 else it)) }
            if (set.hairTints.isNotEmpty()) CasHdStepper("Colour", set.hairTints, o.hairTint.coerceIn(0, set.hairTints.size - 1)) { put(o.copy(hairTint = it)) }
        }
        CasTab.ACCESSORIES -> {
            set.jewelry.forEachIndexed { i, name ->
                WifeSwitchRow(name, i in o.jewelry, { on -> put(o.copy(jewelry = if (on) (o.jewelry + i).distinct().sorted() else o.jewelry - i)) })
            }
            if (set.jewelry.isEmpty()) WifeNote("The set has no jewellery.")
        }
        else -> {}
    }
}

/** A sculpt slider: its name (a long press puts it back to 0), the slider from -1 to 1 with its middle marked, the value. */
@Composable
private fun CasSculptRow(s: SculptSlider, value: Float, enabled: Boolean, glow: Boolean, why: String?, onChange: (Float) -> Unit, onDone: () -> Unit,
                         label: String = s.label, tall: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).then(if (glow) Modifier.background(WifeTheme.glow.copy(alpha = 0.10f)).border(1.dp, WifeTheme.glow.copy(alpha = 0.6f), shape) else Modifier)
        .padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (tall) 56.dp else 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = WifeTheme.labelStyle(if (enabled) WifeTheme.text else WifeTheme.textDim), maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(if (tall) 104.dp else 96.dp).warPress(enabled = enabled, onLongPress = { onChange(0f); onDone() }) { })
            Box(Modifier.weight(1f)) {
                WifeSlider((value + 1f) / 2f, { f -> onChange(f * 2f - 1f) }, Modifier.fillMaxWidth(), enabled, onFinished = onDone)
                Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                    val x = size.width / 2f
                    drawLine(WifeTheme.trim.copy(alpha = if (enabled) 0.7f else 0.3f), Offset(x, size.height / 2 - 9.dp.toPx()), Offset(x, size.height / 2 + 9.dp.toPx()), 1.dp.toPx())
                }
            }
            val shown = (value * 100).roundToInt()
            Text(if (shown > 0) "+$shown" else "$shown", style = WifeTheme.bodyStyle(if (enabled) WifeTheme.gold else WifeTheme.textDim),
                textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(44.dp))
        }
        if (why != null && !s.drawn) WifeNote(why, Modifier.padding(start = 4.dp, bottom = 4.dp))
    }
}

// ---- the Personality tab -------------------------------------------------------------------------------------------------------

@Composable
private fun CasPersonalitySection(model: CreatorModel, rev: Int, icons: GameIcons?, onChanged: (Boolean) -> Unit) {
    val p = rev.let { model.appearance.personality ?: CasPersonality() }
    fun set(next: CasPersonality) { model.setPersonality(next); onChanged(false) }
    val opposed = { a: String, b: String ->
        val x = Trait.entries.firstOrNull { it.name == a }; val y = Trait.entries.firstOrNull { it.name == b }
        x != null && y != null && Traits.opposed(x, y)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WifeSectionHeader("Traits", Modifier.weight(1f))
        Text("${p.traits.size} of ${CasPersonality.MAX_TRAITS}", style = WifeTheme.captionStyle(), modifier = Modifier.padding(start = 8.dp))
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = max(2, ((maxWidth.value + 6f) / 150f).toInt()).coerceAtMost(4)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (line in Trait.entries.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in line) {
                        val on = t.name in p.traits
                        val blocked = !on && (p.traits.size >= CasPersonality.MAX_TRAITS || p.traits.any { opposed(it, t.name) })
                        CasTraitCard(t, on, blocked, icons, Modifier.weight(1f)) { set(p.toggle(t.name, opposed)) }
                    }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    val own = rev.let { Traits.assign("look:" + model.appearance.picks.toSortedMap(), model.appearance.raceId, model.appearance.sex == Sex.FEMALE) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        WifeNote(if (p.traits.isEmpty()) "None chosen: she is given three of her own." else p.traits.mapNotNull { n -> Trait.entries.firstOrNull { it.name == n }?.label }.joinToString(", "),
            Modifier.weight(1f))
        WifeButton("Choose for me", { set(p.copy(traits = own.map { it.name })) }, kind = WifeButtonKind.QUIET)
    }
    p.traits.firstNotNullOfOrNull { n -> Trait.entries.firstOrNull { it.name == n } }?.let { WifeNote(it.description, Modifier.padding(bottom = 4.dp)) }

    WifeSectionHeader("Preferences")
    Text("When someone else starts something", style = WifeTheme.labelStyle(), modifier = Modifier.padding(vertical = 4.dp))
    CasSegments(Initiation.entries.map { it.label }, p.preferences.initiation.ordinal) { set(p.copy(preferences = p.preferences.copy(initiation = Initiation.entries[it]))) }
    Text("Pace", style = WifeTheme.labelStyle(), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    CasSegments(Pace.entries.map { it.label }, p.preferences.pace.ordinal) { set(p.copy(preferences = p.preferences.copy(pace = Pace.entries[it]))) }
    Text("Allowed", style = WifeTheme.labelStyle(), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = max(2, (maxWidth.value / 120f).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (line in StageKind.entries.chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in line) {
                    val on = p.preferences.allows(k)
                    Box(Modifier.weight(1f)) {
                        CasPill(k.label, on) {
                            val allowed = if (on) p.preferences.allowed - k else p.preferences.allowed + k
                            set(p.copy(preferences = p.preferences.copy(allowed = allowed)))
                        }
                    }
                }
                repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
        WifeButton("All", { set(p.copy(preferences = p.preferences.copy(allowed = StageKind.entries.toSet()))) }, kind = WifeButtonKind.QUIET)
        WifeButton("None", { set(p.copy(preferences = p.preferences.copy(allowed = emptySet()))) }, kind = WifeButtonKind.QUIET)
    }
    WifeNote("NPCs keep to these when they start something by themselves; what you ask for is yours to choose.")

    WifeSectionHeader("About her")
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(shape).background(WifeTheme.slateDeep).border(1.dp, WifeTheme.trimSoft, shape).padding(10.dp)) {
        if (p.bio.isEmpty()) Text("A few words about her", style = frizStyle(WifeTheme.body, WifeTheme.textDim, shadow = false))
        BasicTextField(p.bio, { set(p.copy(bio = it.take(CasPersonality.MAX_BIO))) }, textStyle = frizStyle(WifeTheme.body, Color(0xFFE8D9B5), shadow = false),
            cursorBrush = SolidColor(WifeTheme.gold), modifier = Modifier.fillMaxWidth())
    }
    Text("Favourite colour", style = WifeTheme.labelStyle(), modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in FAVOURITE_COLOURS) {
            val on = p.favouriteColour == c
            Box(Modifier.size(44.dp).clip(CircleShape).background(rgbColour(c)).border(if (on) 3.dp else 1.dp, if (on) WifeTheme.gold else Color(0x99000000), CircleShape)
                .warPress(sound = "select") { set(p.copy(favouriteColour = if (on) null else c)) })
        }
    }
}

private val FAVOURITE_COLOURS = listOf(0xB43A4A, 0xC9A55C, 0x5AA0D8, 0x6AC0B0, 0xB8A0E0, 0xE07AB0, 0x5C8A3A, 0x2A2A2A)

@Composable
private fun CasTraitCard(t: Trait, on: Boolean, blocked: Boolean, icons: GameIcons?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(modifier.height(48.dp).clip(shape)
        .background(Brush.verticalGradient(if (on) listOf(Color(0xFF3A2F18), Color(0xFF1D180E)) else listOf(WifeTheme.slateRaised, WifeTheme.slateDeep)))
        .border(if (on) 2.dp else 1.dp, if (on) WifeTheme.gold else WifeTheme.trimSoft, shape)
        .alpha(if (blocked) 0.5f else 1f)
        .warPress(enabled = on || !blocked, sound = if (on) "check_off" else "check_on", onClick = onClick)
        .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val icon = gameIcon(icons, LifeIcons.of(t.icon).fileId)
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(5.dp)).border(1.dp, WifeTheme.trim.copy(alpha = 0.6f), RoundedCornerShape(5.dp)).background(WifeTheme.slateDeep),
            contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, Modifier.fillMaxSize().alpha(if (on) 1f else 0.55f))
        }
        Text(t.label, style = frizStyle(WifeTheme.caption, if (on) Color.White else WifeTheme.textDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Choices side by side in one frame, the one on in the game's red. */
@Composable
private fun CasSegments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().height(48.dp).clip(shape).border(1.dp, WifeTheme.trimSoft, shape)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(Modifier.weight(1f).fillMaxSize()
                .background(if (on) Brush.verticalGradient(listOf(WifeTheme.primaryTop, WifeTheme.primaryBottom)) else SolidColor(Color.Transparent))
                .warPress(sound = "tab") { if (!on) onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(l, style = frizStyle(WifeTheme.body, if (on) WifeTheme.gold else WifeTheme.textDim), maxLines = 1)
            }
        }
    }
}

// ---- Race & Sex ------------------------------------------------------------------------------------------------------------------

@Composable
private fun CasRaceSection(model: CreatorModel, rev: Int, atlas: ArtAtlas, onChanged: (Boolean) -> Unit) {
    val me = rev.let { model.appearance }
    val female = me.sex == Sex.FEMALE
    WifeSectionHeader("Sex")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (s in listOf(Sex.FEMALE, Sex.MALE)) {
            val on = me.sex == s
            val has = model.catalog.model(me.raceId, s) != null
            val p = sexPiece(s == Sex.FEMALE, on)
            WifeCard(if (s == Sex.FEMALE) "Female" else "Male", (atlas.piece("$p.sex") ?: atlas.piece(p))?.image, 64.dp, on,
                { model.setSex(s); onChanged(true) }, enabled = has)
        }
    }
    for (alliance in listOf(true, false)) {
        WifeSectionHeader(if (alliance) "Alliance" else "Horde")
        val races = raceOrder(model.catalog.races.filter { isAllianceSide(it.key, it.alliance) == alliance }) { it.key }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = max(3, ((maxWidth.value + 8f) / 84f).toInt())
            val side = ((maxWidth.value - (columns - 1) * 8f) / columns).coerceAtMost(76f)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (line in races.chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in line) {
                        val piece = racePiece(r.key, female, r.id) { atlas.has(it) }
                        val img = piece?.let { (atlas.piece("$it.race") ?: atlas.piece(it))?.image }
                        WifeCard(r.name, img, side.dp, r.id == me.raceId, { model.setRace(r.id); onChanged(true) },
                            enabled = model.catalog.model(r.id, me.sex) != null || r.sexes.isNotEmpty())
                    }
                }
            }
        }
    }
}

// ---- Presets -------------------------------------------------------------------------------------------------------------------------

/**
 * The Presets window: the built-in looks (the named characters': Chromie's) and those saved (files/cas-presets/), each to
 * load, share as a file (Export) or delete; the look now saved under a name; a shared file imported.
 */
@Composable
fun CasPresetsWindow(current: Appearance, name: String, named: List<ClothingSet>, onLoad: (CasPreset) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CasPresetStore(File(context.filesDir, "cas-presets")) }
    var saved by remember { mutableStateOf(runCatching { store.list() }.getOrDefault(emptyList())) }
    val builtIns = remember(named) { CasPreset.builtIns(named) }
    var newName by remember { mutableStateOf(name.ifBlank { "My look" }) }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf<CasPreset?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } }.getOrNull()
            val p = text?.let { runCatching { store.import(it) }.getOrNull() }
            message = if (p == null) "That file is no Wifecraft preset." else "Imported “${p.name}”."
            saved = runCatching { store.list() }.getOrDefault(saved)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val p = exporting
        if (uri != null && p != null) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(store.export(p).toByteArray()) } }.isSuccess
            message = if (ok) "Saved “${p.name}” to the file." else "The file couldn't be written."
        }
        exporting = null
    }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { onClose() } }, contentAlignment = Alignment.Center) {
        WifeWindow("Presets", onClose, Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f).heightIn(max = 620.dp).pointerInput(Unit) { detectTapGestures { } },
            subtitle = "Looks kept under a name: race, face, body, sculpt, personality and outfit") {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val shape = RoundedCornerShape(10.dp)
                    Box(Modifier.weight(1f).height(48.dp).clip(shape).background(WifeTheme.slateDeep).border(1.dp, WifeTheme.trimSoft, shape).padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart) {
                        BasicTextField(newName, { newName = it.take(CasPreset.MAX_NAME) }, singleLine = true, textStyle = frizStyle(WifeTheme.label, WifeTheme.text, shadow = false),
                            cursorBrush = SolidColor(WifeTheme.gold), modifier = Modifier.fillMaxWidth())
                    }
                    WifeButton("Save look", {
                        val n = newName.trim()
                        if (n.isEmpty()) message = "Give the look a name."
                        else {
                            message = if (runCatching { store.save(CasPreset(n, current, name.trim())) }.isSuccess) "Saved “$n”." else "The look couldn't be saved."
                            saved = runCatching { store.list() }.getOrDefault(saved)
                        }
                    }, kind = WifeButtonKind.PRIMARY)
                }
                message?.let { WifeNote(it, Modifier.padding(top = 6.dp), color = WifeTheme.gold) }
                if (builtIns.isNotEmpty()) WifeSectionHeader("Built in")
                for (p in builtIns) CasPresetRow(p, onLoad = { onLoad(p) }, onExport = { exporting = p; exporter.launch(CasPresetStore.safeName(p.name) + ".json") }, onDelete = null)
                WifeSectionHeader("Saved")
                if (saved.isEmpty()) WifeNote("No looks saved yet.")
                for (p in saved) CasPresetRow(p, onLoad = { onLoad(p) }, onExport = { exporting = p; exporter.launch(CasPresetStore.safeName(p.name) + ".json") },
                    onDelete = { store.delete(p.name); saved = runCatching { store.list() }.getOrDefault(emptyList()) })
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    WifeButton("Import a file…", { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                }
            }
        }
    }
}

@Composable
private fun CasPresetRow(p: CasPreset, onLoad: () -> Unit, onExport: () -> Unit, onDelete: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(p.name, style = WifeTheme.labelStyle(WifeTheme.gold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (p.builtIn) "Built in" else p.characterName.ifEmpty { " " }, style = WifeTheme.captionStyle(), maxLines = 1)
        }
        WifeButton("Load", onLoad)
        WifeButton("Export", onExport, kind = WifeButtonKind.QUIET)
        if (onDelete != null) WifeButton("Delete", onDelete, kind = WifeButtonKind.QUIET)
    }
}

// ---- glyphs ----------------------------------------------------------------------------------------------------------------------------

private val LocalCasTint = staticCompositionLocalOf { Color(0xFFFFD100) }

/** The glyphs below drawn in [colour]. */
@Composable
fun CasGlyphTint(colour: Color, content: @Composable () -> Unit) = CompositionLocalProvider(LocalCasTint provides colour, content = content)

/** The shell's small drawn pictures (the top bar's and the camera buttons'): gold strokes, no files needed. */
enum class CasGlyph {
    Dice, Undo, Redo, Book, Turn, Sun, Full, Upper, Face, Legs;

    @Composable
    fun Draw() {
        val c = LocalCasTint.current
        Canvas(Modifier.fillMaxSize()) { draw(this, c) }
    }

    fun draw(s: DrawScope, c: Color) = with(s) {
        val w = size.width; val h = size.height; val k = 2.dp.toPx()
        val st = Stroke(k)
        when (this@CasGlyph) {
            Dice -> {
                drawRoundRect(c, Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.8f), CornerRadius(w * 0.15f), style = st)
                for ((x, y) in listOf(0.32f to 0.32f, 0.68f to 0.68f, 0.5f to 0.5f, 0.68f to 0.32f, 0.32f to 0.68f)) drawCircle(c, w * 0.07f, Offset(w * x, h * y))
            }
            Undo, Redo -> {
                val flip = this@CasGlyph == Redo
                fun fx(x: Float) = if (flip) w - x else x
                drawArc(c, if (flip) 200f else -20f, if (flip) -220f else 220f, false, Offset(w * 0.15f, h * 0.2f), Size(w * 0.7f, h * 0.65f), style = st)
                val p = Path().apply { moveTo(fx(w * 0.08f), h * 0.42f); lineTo(fx(w * 0.2f), h * 0.62f); lineTo(fx(w * 0.34f), h * 0.42f); close() }
                drawPath(p, c)
            }
            Book -> {
                drawRoundRect(c, Offset(w * 0.15f, h * 0.15f), Size(w * 0.7f, h * 0.7f), CornerRadius(w * 0.06f), style = st)
                drawLine(c, Offset(w * 0.5f, h * 0.15f), Offset(w * 0.5f, h * 0.85f), k)
                for (y in listOf(0.35f, 0.5f, 0.65f)) { drawLine(c, Offset(w * 0.24f, h * y), Offset(w * 0.42f, h * y), k * 0.6f); drawLine(c, Offset(w * 0.58f, h * y), Offset(w * 0.76f, h * y), k * 0.6f) }
            }
            Turn -> {
                drawArc(c, 30f, 300f, false, Offset(w * 0.15f, h * 0.15f), Size(w * 0.7f, h * 0.7f), style = st)
                val p = Path().apply { moveTo(w * 0.78f, h * 0.18f); lineTo(w * 0.92f, h * 0.44f); lineTo(w * 0.64f, h * 0.42f); close() }
                drawPath(p, c)
            }
            Sun -> {
                drawCircle(c, w * 0.18f, Offset(w / 2, h / 2), style = st)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val cx = w / 2; val cy = h / 2
                    drawLine(c, Offset(cx + (w * 0.3f * kotlin.math.cos(a)).toFloat(), cy + (h * 0.3f * kotlin.math.sin(a)).toFloat()),
                        Offset(cx + (w * 0.45f * kotlin.math.cos(a)).toFloat(), cy + (h * 0.45f * kotlin.math.sin(a)).toFloat()), k)
                }
            }
            Full, Upper, Face, Legs -> {
                // a figure, the part the view shows in a frame
                drawCircle(c, w * 0.09f, Offset(w / 2, h * 0.16f), style = st)
                drawLine(c, Offset(w / 2, h * 0.26f), Offset(w / 2, h * 0.6f), k)
                drawLine(c, Offset(w * 0.3f, h * 0.36f), Offset(w * 0.7f, h * 0.36f), k)
                drawLine(c, Offset(w / 2, h * 0.6f), Offset(w * 0.36f, h * 0.92f), k)
                drawLine(c, Offset(w / 2, h * 0.6f), Offset(w * 0.64f, h * 0.92f), k)
                val (top, bottom) = when (this@CasGlyph) { Full -> 0.02f to 0.98f; Upper -> 0.02f to 0.62f; Face -> 0.02f to 0.32f; else -> 0.55f to 0.98f }
                drawRoundRect(c.copy(alpha = 0.55f), Offset(w * 0.12f, h * top), Size(w * 0.76f, h * (bottom - top)), CornerRadius(w * 0.08f), style = Stroke(k * 0.7f))
            }
        }
    }

    companion object {
        fun camera(c: CasCamera): CasGlyph = when (c) { CasCamera.FULL -> Full; CasCamera.UPPER -> Upper; CasCamera.FACE -> Face; CasCamera.LEGS -> Legs }

        /** A small padlock (the rail's locks). */
        fun drawLock(s: DrawScope, c: Color) = with(s) {
            val w = size.width; val h = size.height; val k = 1.4.dp.toPx()
            drawArc(c, 180f, 180f, false, Offset(w * 0.32f, h * 0.18f), Size(w * 0.36f, h * 0.4f), style = Stroke(k))
            drawRoundRect(c, Offset(w * 0.26f, h * 0.42f), Size(w * 0.48f, h * 0.38f), CornerRadius(w * 0.06f))
        }
    }
}
