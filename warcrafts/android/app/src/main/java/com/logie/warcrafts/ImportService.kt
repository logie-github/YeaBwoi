// SPDX-License-Identifier: GPL-3.0-or-later

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
import androidx.core.content.ContextCompat
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
        if (install == null || uri == null || Imports.state.value is Imports.State.Working) return START_NOT_STICKY
        val folder = intent.getBooleanExtra(EXTRA_FOLDER, false)

        Imports.cancelled = false
        Imports.set(Imports.State.Working(install))
        startForeground(NOTIFICATION_ID, notification(install, null))
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "warcrafts:import")
            .apply { acquire(4 * 60 * 60 * 1000L) }

        scope.launch {
            val result = runCatching {
                val report = { progress: Float, detail: String? ->
                    Imports.set(Imports.State.Working(install, progress, detail))
                    notifyThrottled(install, progress)
                }
                if (folder) Imports.importFolder(this@ImportService, install, uri, report)
                else Imports.importZip(this@ImportService, install, uri, report)
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

    private fun notifyThrottled(install: Install, progress: Float?) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 1000) return
        lastNotified = now
        manager().notify(NOTIFICATION_ID, notification(install, progress))
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun channel(): String {
        manager().createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.rts_import_channel), NotificationManager.IMPORTANCE_LOW)
        )
        return CHANNEL
    }

    private fun title(install: Install) = getString(R.string.rts_import_title, getString(installName(install)))

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        packageManager.getLaunchIntentForPackage(packageName)!!.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(install: Install, progress: Float?): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ImportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_rts_notification)
            .setContentTitle(title(install))
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
        private const val ACTION_CANCEL = "com.logie.warcrafts.CANCEL_IMPORT"

        fun start(context: Context, install: Install, uri: Uri, isFolder: Boolean) = ContextCompat.startForegroundService(
            context,
            Intent(context, ImportService::class.java).setData(uri)
                .putExtra(EXTRA_INSTALL, install.key).putExtra(EXTRA_FOLDER, isFolder),
        )

        fun cancel(context: Context) {
            Imports.cancelled = true
        }

        /** A game's name, for what's being imported. */
        fun installName(install: Install): Int = when (install) {
            Install.WC1 -> R.string.rts_title_wc1
            Install.WC2 -> R.string.rts_title_wc2
            Install.WC3 -> R.string.rts_install_wc3
        }
    }
}
