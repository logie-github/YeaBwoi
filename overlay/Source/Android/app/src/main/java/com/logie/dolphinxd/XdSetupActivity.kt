// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.databinding.XdActivitySetupBinding

/**
 * Asks for the setup files (optional GameCube BIOS) and the Pokémon XD game file. Shown on the
 * first launch and from the main menu's "Change game file".
 */
class XdSetupActivity : AppCompatActivity() {
    private lateinit var config: XdConfig
    private lateinit var binding: XdActivitySetupBinding
    private var busy = false

    private val pickBios = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onBiosPicked(uri)
    }

    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onRomPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        config = XdConfig(this)

        binding = XdActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.xdSetupContent) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val padding = resources.getDimensionPixelSize(R.dimen.spacing_large)
            view.updatePadding(
                left = bars.left + padding,
                top = bars.top + padding,
                right = bars.right + padding,
                bottom = bars.bottom + padding
            )
            insets
        }

        binding.xdBiosChoose.setOnClickListener { pickBios.launch(arrayOf("*/*")) }
        binding.xdBiosSkip.setOnClickListener {
            XdGameFiles.discardStagedBios(this)
            config.stagedBiosRegion = null
            config.setupFilesDone = true
            render()
        }
        binding.xdRomChoose.setOnClickListener { pickRom.launch(arrayOf("*/*")) }
        binding.xdPlay.setOnClickListener { onPlay() }
        render()
    }

    private fun render() {
        val setupFilesDone = config.setupFilesDone
        val stagedRegion = config.stagedBiosRegion

        binding.xdBiosStatus.apply {
            text = when {
                stagedRegion != null -> getString(R.string.xd_setup_files_added, stagedRegion)
                config.installedBiosRegionDir != null -> getString(R.string.xd_setup_files_installed)
                setupFilesDone -> getString(R.string.xd_setup_files_skipped)
                else -> ""
            }
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.xdBiosSkip.visibility = if (setupFilesDone) View.GONE else View.VISIBLE
        binding.xdBiosChoose.isEnabled = !busy

        val title = config.romTitle
        val gameId = config.romGameId
        binding.xdRomStatus.apply {
            text = when {
                busy -> getString(R.string.xd_checking)
                config.romUri != null && title != null && gameId != null ->
                    getString(R.string.xd_rom_selected, title, gameId)
                else -> ""
            }
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.xdRomCard.alpha = if (setupFilesDone) 1f else 0.5f
        binding.xdRomChoose.isEnabled = setupFilesDone && !busy
        binding.xdProgress.visibility = if (busy) View.VISIBLE else View.GONE

        binding.xdPlay.isEnabled = setupFilesDone && config.romUri != null && !busy
    }

    private fun onBiosPicked(uri: Uri) {
        busy = true
        render()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { XdGameFiles.stageBios(this@XdSetupActivity, uri) }
            busy = false
            when (result) {
                is XdGameFiles.BiosCheck.Ok -> {
                    config.stagedBiosRegion = result.region
                    config.setupFilesDone = true
                }
                XdGameFiles.BiosCheck.NotABios -> showError(getString(R.string.xd_error_not_bios))
                XdGameFiles.BiosCheck.Unreadable -> showError(getString(R.string.xd_error_unreadable_bios))
            }
            render()
        }
    }

    private fun onRomPicked(uri: Uri) {
        val path = uri.toString()
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not every document provider supports persistable permissions; the main menu
            // notices at the next launch if the file can't be opened any more.
        }

        busy = true
        render()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { XdGameFiles.checkRom(path) }
            busy = false
            when (result) {
                is XdGameFiles.RomCheck.PokemonXd -> {
                    val previous = config.romUri
                    if (previous != null && previous != path) releasePermission(previous)
                    config.setRom(path, result.title, result.gameId)
                }
                is XdGameFiles.RomCheck.OtherGame -> {
                    releasePermission(path)
                    showError(getString(R.string.xd_error_wrong_game, result.title, result.gameId))
                }
                XdGameFiles.RomCheck.Unreadable -> {
                    releasePermission(path)
                    showError(getString(R.string.xd_error_unreadable_rom))
                }
            }
            render()
        }
    }

    private fun onPlay() {
        val gameId = config.romGameId ?: return
        busy = true
        render()
        lifecycleScope.launch {
            val install = withContext(Dispatchers.IO) {
                XdGameFiles.installStagedBios(this@XdSetupActivity, config.stagedBiosRegion, gameId)
            }
            config.stagedBiosRegion = null
            when (install) {
                XdGameFiles.BiosInstall.REGION_MISMATCH ->
                    Toast.makeText(this@XdSetupActivity, R.string.xd_bios_region_mismatch, Toast.LENGTH_LONG).show()
                XdGameFiles.BiosInstall.FAILED ->
                    Toast.makeText(this@XdSetupActivity, R.string.xd_bios_install_failed, Toast.LENGTH_LONG).show()
                else -> {}
            }

            // The game file the player just chose is what Play starts.
            XdMods.select(this@XdSetupActivity, XdConfig.ORIGINAL)
            config.setupComplete = true
            busy = false

            startActivity(
                Intent(this@XdSetupActivity, XdHomeActivity::class.java)
                    .putExtra(XdHomeActivity.EXTRA_AUTOPLAY, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
    }

    private fun releasePermission(path: String) {
        if (!path.startsWith("content://")) return
        // Mods added as already-patched games may share the same file.
        if (config.mods.any { it.path == path }) return
        try {
            contentResolver.releasePersistableUriPermission(Uri.parse(path), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.xd_error_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
