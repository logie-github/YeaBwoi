// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcrafts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.logie.warcrafts.BuildConfig
import com.logie.warcrafts.R
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Checks for, downloads and installs updates, as dialogs over whatever screen opened it (the title
 * screen when the app starts, or its Updates tile). Imported games and
 * saves stay through an update.
 */
class RtsUpdateActivity : AppCompatActivity() {
    private var dialog: AlertDialog? = null
    private var downloaded: File? = null
    private var waitingForPermission = false

    @Volatile
    private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val found = IntentCompat.getSerializableExtra(intent, EXTRA_UPDATE, RtsUpdater.Update::class.java)
        if (found != null) offer(found) else check()
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's "install unknown apps" setting.
        if (waitingForPermission) {
            waitingForPermission = false
            downloaded?.let { install(it) }
        }
    }

    override fun onDestroy() {
        cancelled = true
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun check() {
        if (!RtsUpdater.isConfigured) {
            message(R.string.rts_update_check_failed, getString(R.string.rts_update_not_configured))
            return
        }
        val progress = ProgressView(this, getString(R.string.rts_update_checking))
        show(MaterialAlertDialogBuilder(this).setView(progress.root).setOnCancelListener { finish() })
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { RtsUpdater.findUpdate() } }
            result.fold(
                onSuccess = { found ->
                    if (found != null) {
                        offer(found)
                    } else {
                        message(
                            R.string.rts_update_latest_title,
                            getString(R.string.rts_update_latest, getString(R.string.app_name), BuildConfig.VERSION_NAME)
                        )
                    }
                },
                onFailure = { message(R.string.rts_update_check_failed, errorText(it)) }
            )
        }
    }

    private fun offer(update: RtsUpdater.Update) {
        show(
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rts_update_available_title)
                .setMessage(
                    getString(
                        R.string.rts_update_available,
                        update.title,
                        BuildConfig.VERSION_NAME,
                        Formatter.formatShortFileSize(this, update.size)
                    )
                )
                .setPositiveButton(R.string.rts_update_download) { _, _ -> download(update) }
                .setNegativeButton(R.string.rts_update_later) { _, _ -> finish() }
                .setOnCancelListener { finish() }
        )
    }

    private fun download(update: RtsUpdater.Update) {
        val progress = ProgressView(this, getString(R.string.rts_update_downloading, update.title))
        progress.bar.isIndeterminate = false
        progress.bar.max = PROGRESS_MAX
        show(
            MaterialAlertDialogBuilder(this)
                .setView(progress.root)
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
        )
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    RtsUpdater.download(
                        this@RtsUpdateActivity,
                        update,
                        onProgress = { done, total -> runOnUiThread { progress.show(done, total) } },
                        isCancelled = { cancelled }
                    )
                }
            }
            if (cancelled) return@launch
            result.fold(
                onSuccess = { apk ->
                    downloaded = apk
                    install(apk)
                },
                onFailure = { message(R.string.rts_update_download_failed, errorText(it)) }
            )
        }
    }

    private fun install(apk: File) {
        if (!RtsUpdater.canInstall(this)) {
            askToAllowInstalls()
            return
        }
        dialog?.dismiss()
        startInstaller(apk)
    }

    private fun startInstaller(apk: File) {
        startActivity(RtsUpdater.installIntent(this, apk))
        setResult(RESULT_INSTALLING)
        finish()
    }

    private fun askToAllowInstalls() {
        show(
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rts_update_permission_title)
                .setMessage(getString(R.string.rts_update_permission, getString(R.string.app_name)))
                .setPositiveButton(R.string.rts_update_open_settings) { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        waitingForPermission = true
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                        )
                    }
                }
                .setNegativeButton(R.string.rts_update_later) { _, _ -> finish() }
                .setOnCancelListener { finish() }
        )
    }

    private fun message(@StringRes title: Int, text: String) {
        show(
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .setOnCancelListener { finish() }
        )
    }

    private fun show(builder: MaterialAlertDialogBuilder) {
        dialog?.dismiss()
        dialog = builder.create().apply {
            setCanceledOnTouchOutside(false)
            show()
        }
    }

    private fun errorText(error: Throwable): String = when (error) {
        is RtsUpdater.HttpException -> when (error.code) {
            401, 403, 404 -> getString(R.string.rts_update_no_access, error.code)
            else -> getString(R.string.rts_update_http_error, error.code)
        }
        is RtsUpdater.WrongApkException -> getString(R.string.rts_update_wrong_file)
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            getString(R.string.rts_update_offline)
        else -> error.message ?: error.javaClass.simpleName
    }

    /** A message with a progress bar under it, for dialogs. */
    private class ProgressView(context: Context, private val message: String) {
        val label = TextView(context).apply {
            text = message
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        }
        val bar = LinearProgressIndicator(context).apply { isIndeterminate = true }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * context.resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding / 2)
            addView(label)
            val barParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            barParams.topMargin = padding / 2
            addView(bar, barParams)
        }
        fun show(done: Long, total: Long) {
            if (total > 0) {
                bar.setProgressCompat((done * PROGRESS_MAX / total).toInt(), true)
                label.text = label.context.getString(
                    R.string.rts_update_progress,
                    message,
                    Formatter.formatShortFileSize(label.context, done),
                    Formatter.formatShortFileSize(label.context, total)
                )
            }
        }
    }

    companion object {
        const val EXTRA_UPDATE = "com.logie.warcrafts.UPDATE"

        /** The installer was opened; the app is about to be replaced. */
        const val RESULT_INSTALLING = RESULT_FIRST_USER

        private const val PROGRESS_MAX = 1000

        fun intent(context: Context, update: RtsUpdater.Update? = null): Intent =
            Intent(context, RtsUpdateActivity::class.java).apply {
                update?.let { putExtra(EXTRA_UPDATE, it) }
            }

        /** "Check for updates" in the menus. */
        @JvmStatic
        fun launch(context: Context) = context.startActivity(intent(context))
    }
}
