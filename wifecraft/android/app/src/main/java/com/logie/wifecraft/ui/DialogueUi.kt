package com.logie.wifecraft.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.logie.wifecraft.dialogue.Approach
import com.logie.wifecraft.dialogue.DialogueDirector
import com.logie.wifecraft.dialogue.DialogueSnapshot
import com.logie.wifecraft.dialogue.HesitantView
import com.logie.wifecraft.dialogue.KinkPack
import com.logie.wifecraft.dialogue.Lore
import com.logie.wifecraft.dialogue.PersonaKit
import com.logie.wifecraft.dialogue.Steer
import com.logie.wifecraft.dialogue.SteerEntry
import com.logie.wifecraft.dialogue.SteerPresets
import com.logie.wifecraft.dialogue.SteerStrength
import com.logie.wifecraft.simulation.LiveScene
import kotlinx.coroutines.delay

/** The dialogue layer's data, read from the app's assets (null when a file is missing: the layer is then simply off). */
object DialogueAssets {
    private fun text(ctx: Context, path: String): String? = try { ctx.assets.open(path).bufferedReader().use { it.readText() } } catch (e: Exception) { null }

    fun lore(ctx: Context): Lore? = text(ctx, "dialogue/lore.json")?.let { try { Lore(it).also { l -> l.kit = persona(ctx) } } catch (e: Exception) { null } }

    @Volatile private var personaKit: PersonaKit? = null

    /** The character profiles and eras (assets/dialogue/profiles, eras), loaded once and shared by the scene's lore and Chat's, so one era is in force for both. Null: none, the prompts are as they were. */
    fun persona(ctx: Context): PersonaKit? = synchronized(this) {
        personaKit ?: run {
            val kit = try { PersonaKit.load({ dir -> ctx.assets.list(dir)?.toList().orEmpty() }, { path -> text(ctx, path) }) } catch (e: Exception) { null }
            personaKit = kit
            kit
        }
    }

    fun kink(ctx: Context): KinkPack? = try { KinkPack.load { name -> text(ctx, "dialogue/kink/$name") } } catch (e: Exception) { null }
}

/**
 * The dialogue layer's interface over a scene: while [steerOpen], the steer window (topic and direction chips, free text, a strength, on the whole
 * scene, one NPC or a pair). Everything it changes goes to the GL thread as a command; what it shows is the director's snapshot.
 */
@Composable
fun DialogueOverlay(
    director: DialogueDirector,
    live: LiveScene,
    names: Map<String, String>,
    steerOpen: Boolean,
    onCloseSteer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var snap by remember(director) { mutableStateOf(director.snapshot) }
    LaunchedEffect(director) { while (true) { snap = director.snapshot; delay(300) } }
    if (steerOpen) SteerWindow(director, live, names, snap, onCloseSteer)
}

@Composable
private fun SteerWindow(director: DialogueDirector, live: LiveScene, names: Map<String, String>, snap: DialogueSnapshot, onClose: () -> Unit) {
    val ids = remember(names) { names.keys.toList() }
    val labels = remember(names) { ids.map { names[it] ?: it } }
    var who by remember { mutableIntStateOf(0) }
    var with by remember { mutableIntStateOf(0) }
    var firm by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    fun add(topic: String) {
        val t = topic.trim().take(80)
        if (t.isEmpty()) return
        val steer = Steer(t, if (firm == 1) SteerStrength.FIRM else SteerStrength.GENTLE)
        val a = ids.getOrNull(who - 1)
        val b = ids.getOrNull(with - 1)
        live.command {
            if (a == null) director.steers.addScene(steer)
            else if (b == null || b == a) director.steers.addNpc(a, steer)
            else director.steers.addPair(a, b, steer)
        }
    }
    fun label(e: SteerEntry): String {
        val on = when {
            e.npc == null -> "everyone"
            e.other == null -> names[e.npc] ?: e.npc
            else -> "${names[e.npc] ?: e.npc} + ${names[e.other] ?: e.other}"
        }
        return "${e.steer.text} (${e.steer.strength.label.lowercase()}, $on)"
    }
    Overlay(onClose) {
        WifeWindow("Steer the talk", onClose, Modifier.widthIn(min = 280.dp, max = 520.dp).heightIn(max = 560.dp).padding(WifeTheme.gapL)) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                WifeSectionHeader("Who")
                WifeTabs(listOf("Everyone") + labels, who, { who = it; if (with == who) with = 0 })
                if (who > 0) {
                    WifeSectionHeader("With")
                    WifeTabs(listOf("Anyone") + labels, with, { with = it })
                }
                WifeSectionHeader("How hard")
                WifeTabs(SteerStrength.entries.map { it.label }, firm, { firm = it })
                WifeSectionHeader("Topic")
                for (row in SteerPresets.TOPICS.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                        for (topic in row) WifeChip(topic, { add(topic) }, trailing = null)
                    }
                }
                WowTextField(typed, { typed = it.take(80) }, "Or type a topic or a direction", Modifier.fillMaxWidth())
                WifeButton("Add", { add(typed); typed = "" }, Modifier.fillMaxWidth(), enabled = typed.isNotBlank())
                WifeSectionHeader("In force")
                if (snap.steers.isEmpty()) WifeNote("Nothing. They talk of what they like.")
                for (e in snap.steers) WifeChip(label(e), { live.command { director.steers.remove(e) } })
                WifeNote("A steer is a nudge, never a script: the NPC weaves it in, in character, and may resist if it goes against their nature.")
            }
        }
    }
}
