// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.content.ContextCompat
import com.logie.warcrafts.library.BlizzardDownload
import com.logie.warcrafts.library.Install
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs an import of [Imports] (gigabytes) with the app in the background too: Android keeps a
 * service with a notification running, and the wake lock keeps the phone working with the screen
 * off.
 */
class ImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Imports.cancelled = true
            return START_NOT_STICKY
        }
        val install = intent?.getStringExtra(EXTRA_INSTALL)?.let { key -> Install.entries.firstOrNull { it.key == key } }
        val uri = intent?.data
        val installer = intent?.let { IntentCompat.getParcelableArrayListExtra(it, EXTRA_INSTALLER, Uri::class.java) }
        val convertOnly = intent?.getBooleanExtra(EXTRA_CONVERT_ONLY, false) == true
        val wc3Setup = intent?.getBooleanExtra(EXTRA_WC3_SETUP, false) == true
        if (install == null || (uri == null && installer.isNullOrEmpty() && !convertOnly && !wc3Setup) || Imports.state.value is Imports.State.Working) {
            return START_NOT_STICKY
        }
        val folder = intent.getBooleanExtra(EXTRA_FOLDER, false)

        Imports.cancelled = false
        Imports.set(Imports.State.Working(install, phase = when {
            convertOnly -> Imports.Phase.CONVERT
            wc3Setup -> Imports.Phase.DOWNLOAD
            else -> Imports.Phase.COPY
        }))
        startForeground(NOTIFICATION_ID, notification(install, Imports.Phase.COPY, null))
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "warcrafts:import")
            .apply { acquire(4 * 60 * 60 * 1000L) }

        scope.launch {
            val result = runCatching {
                val report = Imports.Report { phase, progress, detail ->
                    Imports.set(Imports.State.Working(install, progress, detail, phase))
                    notifyThrottled(install, phase, progress)
                }
                when {
                    convertOnly -> Unit
                    // Warcraft III from Blizzard: both installers downloaded; installing is the player's step.
                    wc3Setup -> for (product in BlizzardDownload.Product.entries) {
                        if (Wc3Setup.installer(this@ImportService, product) == null) Wc3Setup.download(this@ImportService, product, report)
                    }
                    !installer.isNullOrEmpty() -> Imports.importInstaller(this@ImportService, install, installer, report)
                    folder -> Imports.importFolder(this@ImportService, install, uri!!, report)
                    else -> Imports.importZip(this@ImportService, install, uri!!, report)
                }
                // Warcraft I and II: their data for Stratagus, made from the files just brought in.
                if (install.stratagusGame != null) {
                    Conversion.run(this@ImportService, install) { line -> report(Imports.Phase.CONVERT, null, line) }
                }
            }
            RtsFiles.staging(this@ImportService).deleteRecursively()
            val state = result.fold(
                onSuccess = { Imports.State.Done(install) },
                onFailure = {
                    Imports.State.Failed(
                        install,
                        if (Imports.cancelled) getString(R.string.rts_import_cancelled) else it.message ?: it.javaClass.simpleName,
                    )
                },
            )
            Imports.set(state)
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!MainActivity.visible) notifyDone(state)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notifyThrottled(install: Install, phase: Imports.Phase, progress: Float?) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 1000) return
        lastNotified = now
        manager().notify(NOTIFICATION_ID, notification(install, phase, progress))
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun channel(): String {
        manager().createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.rts_import_channel), NotificationManager.IMPORTANCE_LOW)
        )
        return CHANNEL
    }

    private fun title(install: Install, phase: Imports.Phase = Imports.Phase.COPY) = getString(phaseTitle(phase), getString(installName(install)))

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        packageManager.getLaunchIntentForPackage(packageName)!!.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(install: Install, phase: Imports.Phase, progress: Float?): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ImportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_rts_notification)
            .setContentTitle(title(install, phase))
            .setContentText(progress?.let { "${(it * 100).toInt()}%" })
            .setProgress(1000, ((progress ?: 0f) * 1000).toInt(), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.rts_import_stop), cancel)
            .build()
    }

    private fun notifyDone(state: Imports.State) {
        val (install, ok) = when (state) {
            is Imports.State.Done -> state.install to true
            is Imports.State.Failed -> state.install to false
            else -> return
        }
        manager().notify(
            DONE_ID,
            NotificationCompat.Builder(this, channel())
                .setSmallIcon(R.drawable.ic_rts_notification)
                .setContentTitle(title(install))
                .setContentText(getString(if (ok) R.string.rts_import_notify_done else R.string.rts_import_notify_failed))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "imports"
        private const val NOTIFICATION_ID = 3
        private const val DONE_ID = 4
        private const val EXTRA_INSTALL = "install"
        private const val EXTRA_FOLDER = "folder"
        private const val EXTRA_CONVERT_ONLY = "convert"
        private const val EXTRA_INSTALLER = "installer"
        private const val EXTRA_WC3_SETUP = "wc3setup"
        private const val ACTION_CANCEL = "com.logie.warcrafts.CANCEL_IMPORT"

        fun start(context: Context, install: Install, uri: Uri, isFolder: Boolean) = ContextCompat.startForegroundService(
            context,
            Intent(context, ImportService::class.java).setData(uri)
                .putExtra(EXTRA_INSTALL, install.key).putExtra(EXTRA_FOLDER, isFolder),
        )

        /** GOG's offline installer for Warcraft I or II: its setup program and .bin files, picked together. */
        fun startInstaller(context: Context, install: Install, uris: List<Uri>) = ContextCompat.startForegroundService(
            context,
            Intent(context, ImportService::class.java)
                .putExtra(EXTRA_INSTALL, install.key)
                .putParcelableArrayListExtra(EXTRA_INSTALLER, ArrayList(uris)),
        )

        /** Downloads Warcraft III's classic installers from Blizzard ([Wc3Setup]). */
        fun downloadWc3(context: Context) = ContextCompat.startForegroundService(
            context,
            Intent(context, ImportService::class.java).putExtra(EXTRA_INSTALL, Install.WC3.key).putExtra(EXTRA_WC3_SETUP, true),
        )

        /** Makes Stratagus's data again from the files already in the app (Warcraft I and II). */
        fun convert(context: Context, install: Install) = ContextCompat.startForegroundService(
            context,
            Intent(context, ImportService::class.java).putExtra(EXTRA_INSTALL, install.key).putExtra(EXTRA_CONVERT_ONLY, true),
        )

        fun cancel(context: Context) {
            Imports.cancelled = true
        }

        /** What an import is doing, for its title ("%1$s": the game's name). */
        fun phaseTitle(phase: Imports.Phase): Int = when (phase) {
            Imports.Phase.COPY -> R.string.rts_import_title
            Imports.Phase.UNPACK -> R.string.rts_unpack_title
            Imports.Phase.CONVERT -> R.string.rts_convert_title
            Imports.Phase.DOWNLOAD -> R.string.rts_setup_download_title
        }

        /** A game's name, for what's being imported. */
        fun installName(install: Install): Int = when (install) {
            Install.WC1 -> R.string.rts_install_wc1
            Install.WC2 -> R.string.rts_install_wc2
            Install.WC3 -> R.string.rts_install_wc3
        }
    }
}
