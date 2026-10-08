// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.databinding.XdActivityTexturePacksBinding
import org.dolphinemu.dolphinemu.databinding.XdItemTexturePackBinding

/** Lists, imports, switches on/off and deletes texture packs. */
class XdTexturePacksActivity : AppCompatActivity() {
    private lateinit var binding: XdActivityTexturePacksBinding

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport(uri) { progress -> XdTexturePacks.importZip(this, uri, progress) }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) startImport(uri) { progress -> XdTexturePacks.importFolder(this, uri, progress) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        XdGame.enableEdgeToEdge(this)
        super.onCreate(savedInstanceState)
        binding = XdActivityTexturePacksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root, binding.xdTexturesContent)

        binding.xdTexturesToolbar.setNavigationOnClickListener { finish() }
        binding.xdTexturesImportZip.setOnClickListener {
            if (!XdTasks.isRunning) pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }
        binding.xdTexturesImportFolder.setOnClickListener {
            if (!XdTasks.isRunning) pickFolder.launch(null)
        }
        binding.xdTexturesLoad.setOnCheckedChangeListener { _, checked ->
            if (checked != XdTexturePacks.loadCustomTextures) XdTexturePacks.loadCustomTextures = checked
        }
        binding.xdTexturesPrefetch.setOnCheckedChangeListener { _, checked ->
            if (checked != XdTexturePacks.prefetchCustomTextures) XdTexturePacks.prefetchCustomTextures = checked
        }

        XdTasks.observe(this, binding.xdTexturesTask,
            onChanged = { state ->
                val running = state != null && !state.finished
                binding.xdTexturesImportZip.isEnabled = !running
                binding.xdTexturesImportFolder.isEnabled = !running
            },
            onFinished = { refresh() })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun startImport(uri: Uri, work: (XdTasks.Progress) -> String) {
        val name = XdFiles.displayName(this, uri) ?: getString(R.string.xd_textures_default_name)
        if (!XdTasks.start(getString(R.string.xd_textures_importing, name)) { progress -> work(progress) }) {
            Toast.makeText(this, R.string.xd_busy, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refresh() {
        binding.xdTexturesLoad.isChecked = XdTexturePacks.loadCustomTextures
        binding.xdTexturesPrefetch.isChecked = XdTexturePacks.prefetchCustomTextures

        val packs = XdTexturePacks.list(this)
        binding.xdTexturesList.removeAllViews()
        binding.xdTexturesEmpty.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
        for (pack in packs) {
            val row = XdItemTexturePackBinding.inflate(layoutInflater, binding.xdTexturesList, true)
            row.xdPackName.text = pack.name
            row.xdPackDetails.text = getString(
                if (pack.hasDds) R.string.xd_textures_count_dds else R.string.xd_textures_count,
                pack.textureCount
            )
            row.xdPackEnabled.isChecked = pack.enabled
            row.xdPackEnabled.setOnCheckedChangeListener { _, checked -> XdTexturePacks.setEnabled(this, pack, checked) }
            row.xdPackDelete.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.xd_textures_delete_title, pack.name))
                    .setMessage(R.string.xd_textures_delete_message)
                    .setPositiveButton(R.string.xd_delete) { _, _ ->
                        XdTexturePacks.delete(pack)
                        refresh()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    companion object {
        /** Pads [content] for the system bars; the toolbar in [root] goes under the status bar. */
        fun applyInsets(root: View, content: View) {
            val basePadding = content.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.updatePadding(left = bars.left, top = bars.top, right = bars.right)
                content.updatePadding(bottom = basePadding + bars.bottom)
                insets
            }
        }
    }
}
