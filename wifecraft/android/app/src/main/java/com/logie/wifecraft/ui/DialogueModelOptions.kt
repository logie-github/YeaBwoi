package com.logie.wifecraft.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logie.wifecraft.data.DialogueHost
import com.logie.wifecraft.data.DialogueUiState
import com.logie.wifecraft.dialogue.DialogueModels
import com.logie.wifecraft.dialogue.DialogueModelSpec

/**
 * Options → Dialogue → Dialogue model (the Dialogue page's `dialogueExtra` slot): which model drives the NPCs, its download
 * (from Hugging Face, resumable, checked, on request: nothing is bundled) and its switch. Compiled by CI only; not run on a phone.
 */
@Composable
fun DialogueModelSection() {
    val st by DialogueHost.state.collectAsStateWithLifecycle()
    val cfg = DialogueHost.config
    val spec = st.spec
    val gb = 1024.0 * 1024 * 1024
    val mb = { b: Long -> "${b / 1_000_000} MB" }

    WifeSectionHeader("Dialogue model")
    WifeTabs(DialogueModels.all.map { if (it === DialogueModels.STANDARD) "Standard (4B)" else "Lite (1.7B)" },
        DialogueModels.all.indexOfFirst { it.id == spec.id }.coerceAtLeast(0), { i ->
            DialogueHost.setConfig(cfg.copy(modelId = DialogueModels.all[i].id)); DialogueHost.refresh()
        })
    WifeNote("${spec.label}. ${spec.blurb}")
    WifeNote("File ${mb(spec.bytes)}, about ${spec.ramMb} MB of memory while loaded, an estimated ${spec.estTokensPerSecond} tokens a second on a recent Snapdragon. " +
        "${spec.license}. From huggingface.co/${spec.repo}. This phone has ${"%.1f".format(st.totalRam / gb)} GB (${"%.1f".format(st.availableRam / gb)} GB free now); " +
        "recommended: ${if (DialogueModels.recommended(st.totalRam) === spec) "this one" else DialogueModels.recommended(st.totalRam).label}.")
    val status = when (st.phase) {
        DialogueUiState.Phase.NOT_DOWNLOADED -> "Not downloaded"
        DialogueUiState.Phase.PARTIAL -> "Partly downloaded: ${mb(st.haveBytes)} of ${mb(st.totalBytes)}"
        DialogueUiState.Phase.DOWNLOADING -> "Downloading ${mb(st.haveBytes)} of ${mb(st.totalBytes)}"
        DialogueUiState.Phase.CHECKING -> "Checking the file ${(st.fraction * 100).toInt()}%"
        DialogueUiState.Phase.READY -> "Downloaded"
        DialogueUiState.Phase.LOADING -> "Loading the model"
        DialogueUiState.Phase.LOADED -> "Loaded: the NPCs are answered by it"
        DialogueUiState.Phase.FAILED -> "Failed"
    }
    WifeNote(status + (st.message?.let { ": $it" } ?: ""))
    when (st.phase) {
        DialogueUiState.Phase.NOT_DOWNLOADED, DialogueUiState.Phase.PARTIAL, DialogueUiState.Phase.FAILED ->
            WifeButton(if (st.phase == DialogueUiState.Phase.PARTIAL) "Resume download" else "Download ${mb(spec.bytes)}", { DialogueHost.startDownload() })
        DialogueUiState.Phase.DOWNLOADING, DialogueUiState.Phase.CHECKING -> WifeButton("Pause", { DialogueHost.cancelDownload() })
        DialogueUiState.Phase.READY, DialogueUiState.Phase.LOADED, DialogueUiState.Phase.LOADING -> {
            WifeSwitchRow("Use the model", cfg.enabled, { DialogueHost.setEnabled(it) }, detail = "Off frees its memory; the NPCs go quiet or keep their scripted small talk")
            WifeButton("Delete the file", { DialogueHost.deleteModel() })
        }
    }
    WifeSwitchRow("Download over mobile data too", cfg.mobileData, { DialogueHost.setConfig(cfg.copy(mobileData = it)) }, detail = "Off: Wi-Fi only")
}
