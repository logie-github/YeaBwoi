// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export

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
import com.logie.warcraft.MainActivity
import com.logie.warcraft.R
import com.logie.warcraft.export.core.ExportRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs [BlizzardExport]'s export or verification with the app in the background too (the way
 * ImportService runs the imports): a foreground service with a notification and a wake lock, so
 * copying gigabytes carries on with the screen off. Stop is in the notification.
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            BlizzardExport.cancelled = true
            return START_NOT_STICKY
        }
        val job = intent?.getStringExtra(EXTRA_JOB)
        val tree = intent?.data
        if (job == null || tree == null || BlizzardExport.state.value is BlizzardExport.State.Working) return START_NOT_STICKY
        val stopRealms = intent.getBooleanExtra(EXTRA_STOP_REALMS, false)
        val groups = intent.getStringArrayExtra(EXTRA_GROUPS)?.mapNotNull { key -> ExportRules.Group.entries.firstOrNull { it.key == key } }?.toSet()
            ?: ExportRules.Group.entries.toSet()

        BlizzardExport.cancelled = false
        BlizzardExport.set(BlizzardExport.State.Working(BlizzardExport.Phase.CHECKING))
        startForeground(NOTIFICATION_ID, notification(null))
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "warcraft:export")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        val watcher = scope.launch {
            BlizzardExport.state.collect { state ->
                if (state is BlizzardExport.State.Working) notifyThrottled(state)
            }
        }
        scope.launch {
            val result = runCatching {
                if (job == JOB_VERIFY) BlizzardExport.verify(this@ExportService, tree)
                else BlizzardExport.export(this@ExportService, tree, groups, stopRealms)
            }
            watcher.cancel()
            result.exceptionOrNull()?.let { error ->
                BlizzardExport.set(
                    BlizzardExport.State.Failed(
                        error.message ?: error.javaClass.simpleName,
                        canResume = (error as? BlizzardExport.ExportException)?.canResume ?: (job == JOB_EXPORT),
                    ),
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!MainActivity.visible) notifyDone(result.isSuccess)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun channel(): String {
        manager().createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.export_channel), NotificationManager.IMPORTANCE_LOW))
        return CHANNEL
    }

    private fun notifyThrottled(state: BlizzardExport.State.Working) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 1000) return
        lastNotified = now
        manager().notify(NOTIFICATION_ID, notification(state))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        packageManager.getLaunchIntentForPackage(packageName)!!.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(state: BlizzardExport.State.Working?): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val progress = state?.progress
        val fraction = progress?.takeIf { it.totalBytes > 0 }?.let { (it.bytes.toDouble() / it.totalBytes).toFloat() }
        return NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_wow_notification)
            .setContentTitle(getString(if (state?.phase == BlizzardExport.Phase.VERIFYING) R.string.export_verify_title else R.string.export_title))
            .setContentText(
                progress?.let { getString(R.string.export_notification_progress, it.files, it.totalFiles, (fraction ?: 0f) * 100) }
                    ?: state?.note,
            )
            .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.wow_import_stop), cancel)
            .build()
    }

    private fun notifyDone(ok: Boolean) {
        manager().notify(
            DONE_ID,
            NotificationCompat.Builder(this, channel())
                .setSmallIcon(R.drawable.ic_wow_notification)
                .setContentTitle(getString(R.string.export_title))
                .setContentText(getString(if (ok) R.string.wow_import_notify_done else R.string.wow_import_notify_failed))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "export"
        private const val NOTIFICATION_ID = 21
        private const val DONE_ID = 22
        private const val EXTRA_JOB = "job"
        private const val EXTRA_STOP_REALMS = "stop"
        private const val EXTRA_GROUPS = "groups"
        private const val JOB_EXPORT = "export"
        private const val JOB_VERIFY = "verify"
        private const val ACTION_CANCEL = "com.logie.warcraft.CANCEL_EXPORT"

        /** Copies [groups]' files into the folder [tree]; [stopRealms]: a running realm may be stopped first. */
        fun export(context: Context, tree: Uri, groups: Set<ExportRules.Group>, stopRealms: Boolean) {
            BlizzardExport.remember(context, tree)
            ContextCompat.startForegroundService(
                context,
                Intent(context, ExportService::class.java).setData(tree)
                    .putExtra(EXTRA_JOB, JOB_EXPORT).putExtra(EXTRA_STOP_REALMS, stopRealms)
                    .putExtra(EXTRA_GROUPS, groups.map { it.key }.toTypedArray()),
            )
        }

        fun verify(context: Context, tree: Uri) = ContextCompat.startForegroundService(
            context,
            Intent(context, ExportService::class.java).setData(tree).putExtra(EXTRA_JOB, JOB_VERIFY),
        )

        fun cancel() {
            BlizzardExport.cancelled = true
        }
    }
}
