// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.databinding.XdActivityModsBinding
import org.dolphinemu.dolphinemu.databinding.XdItemModBinding

/** Picks which version of the game Play starts, and installs or removes mods. */
class XdModsActivity : AppCompatActivity() {
    private lateinit var binding: XdActivityModsBinding
    private lateinit var config: XdConfig
    private var shownRunning = false

    private val pickPatch = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) choosePatchBase(uri)
    }

    private val pickGame = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) start(R.string.xd_mods_installing, uri) { progress -> XdMods.addPatchedGame(this, uri, progress) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        XdGame.enableEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        config = XdConfig(this)
        binding = XdActivityModsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        XdTexturePacksActivity.applyInsets(binding.root, binding.xdModsContent)

        binding.xdModsToolbar.setNavigationOnClickListener { finish() }
        binding.xdModsImportPatch.setOnClickListener { if (!XdTasks.isRunning) pickPatch.launch(arrayOf("*/*")) }
        binding.xdModsAddIso.setOnClickListener { if (!XdTasks.isRunning) pickGame.launch(arrayOf("*/*")) }

        XdTasks.observe(this, binding.xdModsTask,
            onChanged = { state ->
                val running = state != null && !state.finished
                if (running != shownRunning) refresh()
            },
            onFinished = { refresh() })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Mods are normally patched onto the original game, but add-ons (like Grand Colosseum's
     * Nuzlocke patch) go on top of an installed mod, so ask when there's a choice.
     */
    private fun choosePatchBase(uri: Uri) {
        val mods = XdMods.patchableMods(this)
        if (mods.isEmpty()) {
            installPatch(uri, XdConfig.ORIGINAL)
            return
        }
        val ids = listOf(XdConfig.ORIGINAL) + mods.map { it.id }
        val names = listOf(getString(R.string.xd_mods_base_original, getString(R.string.xd_mods_original))) +
                mods.map { getString(R.string.xd_mods_base_mod, it.name) }
        var chosen = 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xd_mods_base_title)
            .setSingleChoiceItems(names.toTypedArray(), chosen) { _, which -> chosen = which }
            .setPositiveButton(R.string.xd_mods_base_apply) { _, _ -> installPatch(uri, ids[chosen]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun installPatch(uri: Uri, baseId: String) {
        start(R.string.xd_mods_installing, uri) { progress -> XdMods.installUps(this, uri, baseId, progress) }
    }

    private fun start(title: Int, uri: Uri, work: (XdTasks.Progress) -> String) {
        val name = XdFiles.displayName(this, uri) ?: ""
        if (!XdTasks.start(getString(title, name)) { progress -> work(progress) }) {
            Toast.makeText(this, R.string.xd_busy, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refresh() {
        val running = XdTasks.isRunning
        shownRunning = running
        binding.xdModsImportPatch.isEnabled = !running
        binding.xdModsAddIso.isEnabled = !running

        val active = config.activeVersionId
        binding.xdModsList.removeAllViews()

        val original = XdItemModBinding.inflate(layoutInflater, binding.xdModsList, true)
        original.xdModName.text = getString(R.string.xd_mods_original)
        original.xdModDetails.text = getString(R.string.xd_mods_original_sub, config.romGameId ?: "")
        original.xdModDelete.visibility = View.GONE
        bindSelection(original, XdConfig.ORIGINAL, active, running, getString(R.string.xd_mods_original))

        for (mod in config.mods) {
            val row = XdItemModBinding.inflate(layoutInflater, binding.xdModsList, true)
            row.xdModName.text = mod.name
            row.xdModDetails.text = getString(R.string.xd_mods_mod_sub, mod.gameId)
            bindSelection(row, mod.id, active, running, mod.name)
            row.xdModDelete.isEnabled = !running
            row.xdModDelete.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.xd_mods_delete_title, mod.name))
                    .setMessage(if (mod.owned) R.string.xd_mods_delete_message else R.string.xd_mods_delete_message_external)
                    .setPositiveButton(R.string.xd_delete) { _, _ ->
                        XdMods.delete(this, mod)
                        refresh()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun bindSelection(row: XdItemModBinding, id: String, active: String, running: Boolean, name: String) {
        row.xdModSelected.isChecked = id == active
        row.xdModCard.strokeColor = getColor(if (id == active) R.color.xd_accent else R.color.xd_surface_stroke)
        row.xdModCard.isEnabled = !running
        row.xdModCard.setOnClickListener {
            if (id != config.activeVersionId && !XdTasks.isRunning) {
                XdMods.select(this, id)
                Toast.makeText(this, getString(R.string.xd_mods_switched, name), Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
    }
}
