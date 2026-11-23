package com.logie.wifecraft.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logie.wifecraft.data.DialogueHost
import com.logie.wifecraft.data.DialogueUiState
import com.logie.wifecraft.dialogue.DialogueModels
import com.logie.wifecraft.dialogue.ModelTier

/**
 * Options → Dialogue → Dialogue model (the Dialogue page's `dialogueExtra` slot): which model drives the NPCs, its download
 * (from Hugging Face, resumable, checked, on request: nothing is bundled) and its switch. Compiled by CI only; not run on a phone.
 */
@Composable
fun DialogueModelSection() {
    val st by DialogueHost.state.collectAsStateWithLifecycle()
    val cfg = DialogueHost.config
    val active = st.spec
    val gb = 1024.0 * 1024 * 1024
    val mb = { b: Long -> "${b / 1_000_000} MB" }

    WifeSectionHeader("Dialogue model")
    WifeNote("Two models. Fast answers a JOI line or a chat reply in a few seconds; Quality (slow) writes richer prose but takes 15 to 90 seconds on most phones. " +
        "This phone has ${"%.1f".format(st.totalRam / gb)} GB of memory (${"%.1f".format(st.availableRam / gb)} GB free now).")
    for (m in DialogueModels.all) {
        val have = st.installed.contains(m.id)
        val partial = (st.haveById[m.id] ?: 0L).takeIf { !have && it > 0 }
        val downloadingThis = st.downloadingId == m.id && (st.phase == DialogueUiState.Phase.DOWNLOADING || st.phase == DialogueUiState.Phase.CHECKING)
        val inUse = active.id == m.id && have
        WifeSectionHeader(m.label + if (m.tier == ModelTier.FAST) " (recommended)" else "")
        WifeNote(m.blurb)
        WifeNote("${mb(m.bytes)} (free space needed: about ${"%.1f".format(m.bytes * 1.1 / 1_000_000_000.0)} GB), about ${m.ramMb} MB of memory while loaded, an estimated ${m.estTokensPerSecond} tokens a second on a recent Snapdragon. ${m.speedNote}")
        WifeNote("${m.license}. From huggingface.co/${m.repo}.")
        DialogueModels.ramWarning(m, st.totalRam)?.takeIf { m.tier == ModelTier.QUALITY }?.let { WifeNote("Warning: $it") }
        val status = when {
            downloadingThis -> if (st.phase == DialogueUiState.Phase.CHECKING) "Checking the file ${(st.fraction * 100).toInt()}%" else "Downloading ${mb(st.haveBytes)} of ${mb(m.bytes)}"
            inUse && st.phase == DialogueUiState.Phase.LOADING -> if (st.loadProgress >= 0f) "Loading the model into memory: ${(st.loadProgress * 100).toInt()}% (the game is usable meanwhile)" else "Loading the model"
            inUse && st.phase == DialogueUiState.Phase.LOADED -> "In use: the NPCs are answered by it"
            inUse && st.phase == DialogueUiState.Phase.FAILED -> "Failed" + (st.message?.let { ": $it" } ?: "")
            have -> "Downloaded" + if (inUse) " (in use)" else ""
            partial != null -> "Partly downloaded: ${mb(partial)} of ${mb(m.bytes)}"
            else -> "Not downloaded"
        }
        WifeNote(status)
        if (downloadingThis) WifeButton("Pause", { DialogueHost.cancelDownload() })
        else if (!have) WifeButton(if (partial != null) "Resume download" else "Download ${mb(m.bytes)}", { DialogueHost.startDownload(m) }, enabled = st.downloadingId == null || st.phase != DialogueUiState.Phase.DOWNLOADING)
        else {
            if (!inUse) WifeButton("Use this model", { DialogueHost.chooseModel(m) })
            WifeButton("Delete the file", { DialogueHost.deleteModel(m) })
        }
    }
    if (st.installed.isNotEmpty()) WifeSwitchRow("Use the model", cfg.enabled, { DialogueHost.setEnabled(it) }, detail = "Off frees its memory; the NPCs go quiet or keep their scripted small talk")
    if (st.phase == DialogueUiState.Phase.FAILED && st.downloadingId == null && st.message != null && !st.installed.contains(active.id)) WifeNote("Failed: ${st.message}")
    if (st.oldModelBytes > 0) {
        WifeNote("The earlier models (Standard 4B, Lite 1.7B) are still on this phone, ${mb(st.oldModelBytes)}. They are not used any more.")
        WifeButton("Delete the earlier models (frees ${mb(st.oldModelBytes)})", { DialogueHost.deleteOldModels() })
    }
    WifeSwitchRow("Download over mobile data too", cfg.mobileData, { DialogueHost.setConfig(cfg.copy(mobileData = it)) }, detail = "Off: Wi-Fi only")
    DialogueSpeedSection()
}
