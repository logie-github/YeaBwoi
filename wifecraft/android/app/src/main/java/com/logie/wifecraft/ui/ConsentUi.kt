package com.logie.wifecraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.simulation.ActCatalogue
import com.logie.wifecraft.simulation.ActGroup
import com.logie.wifecraft.simulation.ConsentAct
import com.logie.wifecraft.simulation.ConsentEdit
import com.logie.wifecraft.simulation.ConsentPolicy
import com.logie.wifecraft.simulation.NpcLimits
import com.logie.wifecraft.simulation.Stance

// The Consent and limits screen: after the scene, the cast and the objects are chosen and before the scene loads, and (as a window)
// from the scene's menu while it runs. Per NPC (tabs): the acts of the animation catalogue by category, each category Will, Ask or
// Won't, and each act of it with its own choice over the category's. The rules are in simulation/ConsentPolicy and ConsentBook
// (JVM-tested); this file only draws them and sends the edits back.

/** A cast member of the screen: the id the limits are kept under and the name shown. */
data class ConsentNpc(val id: String, val name: String)

/** The full-screen step of scene creation: Back to the objects, Skip (the remembered limits as they are), Start scene. */
@Composable
fun ConsentScreen(
    npcs: List<ConsentNpc>,
    groups: List<ActGroup>,
    policy: ConsentPolicy,
    /** Whether changes are kept for this scene alone (else for the character), and the switch's change. */
    sceneOnly: Boolean,
    onSceneOnly: (Boolean) -> Unit,
    /** An NPC's limits changed: keep them (the policy has them already). */
    onChanged: (String, NpcLimits) -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onStart: () -> Unit,
    loading: Boolean = false,
) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        WifecraftMenuBackdrop(Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
            WifeWindow(
                "Consent and limits", onBack, Modifier.width(layout.optionsW.dp).height(layout.optionsH.dp),
                subtitle = "What each NPC will, will be asked about, or won't do. Step 4 of 4",
                footer = {
                    WifeButton("Back", onBack, Modifier.widthIn(min = 96.dp), sound = "close")
                    WifeButton("Skip (use remembered)", onSkip, Modifier.widthIn(min = 150.dp))
                    WifeButton("Start scene", onStart, Modifier.widthIn(min = 130.dp), kind = WifeButtonKind.PRIMARY, sound = "ok")
                },
            ) {
                WifeSwitchRow("Remember for this scene only", sceneOnly, onSceneOnly,
                    detail = if (sceneOnly) "Kept for this place and this cast" else "Kept for each character, in every scene")
                ConsentPanel(npcs, groups, policy, onChanged, loading, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The same screen as a window over the running scene (the scene menu's Limits): changes apply from the next act. */
@Composable
fun ConsentWindow(
    visible: Boolean,
    onClose: () -> Unit,
    npcs: List<ConsentNpc>,
    groups: List<ActGroup>,
    policy: ConsentPolicy,
    onChanged: (String, NpcLimits) -> Unit,
    loading: Boolean = false,
) {
    Overlay(onClose, visible) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
            val width = (maxWidth - 24.dp).coerceAtMost(720.dp)
            val height = (maxHeight - 24.dp).coerceAtMost(700.dp)
            WifeWindow(
                "Consent and limits", onClose, Modifier.width(width).height(height),
                subtitle = "Changes apply from the next act. An act in progress that becomes a Won't ends.",
                footer = { WifeButton("Done", onClose, Modifier.widthIn(min = 120.dp), kind = WifeButtonKind.PRIMARY, sound = "close") },
            ) {
                ConsentPanel(npcs, groups, policy, onChanged, loading, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** One line of the list: a category's card, or one of its acts. */
private sealed interface ConsentRow {
    val key: String
    class Category(val group: ActGroup, val open: Boolean) : ConsentRow { override val key get() = "c:${group.category}" }
    class Act(val category: String, val act: ConsentAct) : ConsentRow { override val key get() = "a:$category:${act.id}" }
}

/** The tools (copy, reset), the search, and the categories of the animation catalogue for the NPC chosen on the tabs. */
@Composable
internal fun ConsentPanel(
    npcs: List<ConsentNpc>,
    groups: List<ActGroup>,
    policy: ConsentPolicy,
    onChanged: (String, NpcLimits) -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    // (the policy is not Compose state: every edit moves [rev] so what is drawn from it is read again)
    var rev by remember { mutableIntStateOf(0) }
    var index by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(emptySet<String>()) }
    var copying by remember { mutableStateOf(false) }
    val npc = npcs.getOrNull(index.coerceAtMost(npcs.size - 1)) ?: return
    fun edit(id: String, limits: NpcLimits) { policy.set(id, limits); onChanged(id, limits); rev++ }

    val shown = remember(groups, query) { ActCatalogue.search(groups, query) }
    val searching = query.isNotBlank()
    val rows = remember(shown, open, searching) {
        buildList<ConsentRow> {
            for (g in shown) {
                val isOpen = searching || g.category in open
                add(ConsentRow.Category(g, isOpen))
                if (isOpen) for (a in g.acts) add(ConsentRow.Act(g.category, a))
            }
        }
    }
    val limits = remember(rev, npc.id) { policy.limitsOf(npc.id) }
    val list = rememberLazyListState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
        if (npcs.size > 1) WifeTabs(npcs.map { it.name }, index, { index = it; copying = false })
        else Text(npc.name, style = WifeTheme.headingStyle())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
            if (npcs.size > 1) {
                WifeButton("Copy to all", {
                    for ((id, l) in ConsentEdit.copyToAll(policy.all, npc.id, npcs.map { it.id })) edit(id, l)
                }, Modifier.weight(1f))
                WifeButton("Copy from…", { copying = !copying }, Modifier.weight(1f))
            }
            WifeButton("Reset to defaults", { edit(npc.id, ConsentEdit.reset(limits)) }, Modifier.weight(1f))
        }
        if (copying) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
            for (other in npcs) if (other.id != npc.id) WifeChip("Copy ${other.name}", {
                copying = false
                edit(npc.id, ConsentEdit.copyFrom(policy.all, other.id, npc.id))
            }, trailing = null)
        }
        SceneSearchField(query, { query = it.take(40) }, "Find an act or a category", Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth().warScrollbar(list), list, verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
            if (rows.isEmpty()) item(key = "empty") {
                WifeNote(when {
                    loading -> "Reading the animation catalogue…"
                    groups.isEmpty() -> "No two-person animations yet: the base ones are listed once they exist. Import an animation pack to see its acts here."
                    else -> "Nothing matches."
                }, Modifier.padding(WifeTheme.gapS))
            }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is ConsentRow.Category -> {
                        val g = row.group
                        val explicit = limits.categories[g.category]
                        val effective = explicit ?: policy.defaultStance(npc.id, g.category)
                        CategoryCard(g, row.open, explicit != null, effective,
                            onToggle = { open = if (g.category in open) open - g.category else open + g.category },
                            onPick = { edit(npc.id, limits.withCategory(g.category, it)) },
                            onAll = { edit(npc.id, ConsentEdit.setCategory(limits, g.category, it, g.acts)) })
                    }
                    is ConsentRow.Act -> {
                        val r = policy.resolve(npc.id, row.act)
                        ActCard(row.act, r.stance, ConsentEdit.describe(r), overridden = r.source == com.logie.wifecraft.simulation.Resolved.Source.ACT,
                            onPick = { edit(npc.id, ConsentEdit.setAct(limits, row.act, it)) },
                            onClear = { edit(npc.id, ConsentEdit.setAct(limits, row.act, null)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(group: ActGroup, open: Boolean, explicit: Boolean, effective: Stance, onToggle: () -> Unit, onPick: (Stance) -> Unit, onAll: (Stance) -> Unit) {
    val shape = RoundedCornerShape(WifeTheme.buttonRadius)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(WifeTheme.slateRaised.copy(alpha = 0.8f)).border(WifeTheme.trimWidth, WifeTheme.trimSoft, shape)
            .padding(WifeTheme.gapS),
        verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = WifeTheme.touch).semantics { contentDescription = "${group.category}, ${if (open) "open" else "closed"}" }
                .warPress(sound = "select", onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS),
        ) {
            Text(if (open) "v" else ">", style = WifeTheme.headingStyle())
            Text(group.category, Modifier.weight(1f), style = WifeTheme.headingStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${group.acts.size} act${if (group.acts.size == 1) "" else "s"}", style = WifeTheme.captionStyle())
        }
        StanceButtons(effective, explicit, onPick)
        if (!explicit) WifeNote("Their nature's default. Tap to set it.")
        if (open) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
                Text("All acts:", style = WifeTheme.captionStyle())
                for (s in Stance.entries) WifeButton(s.label, { onAll(s) }, Modifier.weight(1f), kind = WifeButtonKind.QUIET)
            }
        }
    }
}

@Composable
private fun ActCard(act: ConsentAct, effective: Stance, why: String, overridden: Boolean, onPick: (Stance) -> Unit, onClear: () -> Unit) {
    val shape = RoundedCornerShape(WifeTheme.buttonRadius)
    Column(
        Modifier.fillMaxWidth().padding(start = WifeTheme.gapL).clip(shape).background(WifeTheme.slateRaised.copy(alpha = 0.45f))
            .border(WifeTheme.trimWidth, WifeTheme.trimSoft, shape).padding(WifeTheme.gapS),
        verticalArrangement = Arrangement.spacedBy(WifeTheme.gapXs),
    ) {
        Text(act.name, style = WifeTheme.labelStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
            Text("${effective.label}: $why", Modifier.weight(1f), style = WifeTheme.captionStyle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (overridden) WifeButton("Use category's", onClear, kind = WifeButtonKind.QUIET)
        }
        StanceButtons(effective, overridden, onPick)
    }
}

/** Will, Ask and Won't side by side: [effective] lit (solid when the player set it, faint when it is a default). */
@Composable
private fun StanceButtons(effective: Stance, explicit: Boolean, onPick: (Stance) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapXs)) {
        for (s in Stance.entries) {
            val on = s == effective
            val tint = when (s) { Stance.WILL -> WifeTheme.good; Stance.ASK -> WifeTheme.gold; Stance.WONT -> WifeTheme.danger }
            val shape = RoundedCornerShape(WifeTheme.buttonRadius)
            Box(
                Modifier.weight(1f).heightIn(min = WifeTheme.touch).clip(shape)
                    .background(if (on) tint.copy(alpha = if (explicit) 0.35f else 0.16f) else WifeTheme.slateDeep.copy(alpha = 0.6f))
                    .border(if (on) 1.5.dp else WifeTheme.trimWidth, if (on) tint else WifeTheme.trimSoft, shape)
                    .semantics { this.selected = on; contentDescription = s.label }
                    .warPress(sound = "select") { onPick(s) },
                contentAlignment = Alignment.Center,
            ) {
                Text(s.label, style = frizStyle(WifeTheme.label, if (on) tint else WifeTheme.textDim, shadow = false), maxLines = 1)
            }
        }
    }
}
