package com.logie.wifecraft.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * The dialogue model's download as a foreground service (data sync), like [DownloadService]: it goes on with the app in the
 * background or the screen off, with a notification showing how far it is and a Cancel button. The work is
 * [DialogueHost.runDownload] (resumable, checksummed: dialogue/ModelDownloader). Not run on a phone.
 */
class ModelDownloadService : Service() {
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(DialogueHost.state.value)
        if (intent?.action == ACTION_CANCEL) {
            DialogueHost.cancelDownload()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wifecraft:model-download").apply { acquire(6 * 60 * 60 * 1000L) }
        worker = Thread({
            var last = 0L
            val end = try {
                DialogueHost.runDownload { s ->
                    val now = System.currentTimeMillis()
                    if (now - last > 1000) { last = now; runCatching { channel(this).notify(NOTE_PROGRESS, progressNote(this, s)) } }
                }
            } catch (t: Throwable) {
                DialogueHost.state.value.copy(phase = DialogueUiState.Phase.FAILED, message = t.message)
            }
            finish(end)
        }, "wc-model-download").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits a data-sync service to six hours a day: the part is kept, "Download" carries on from it
        DialogueHost.cancelDownload()
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun finish(s: DialogueUiState) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val (title, ok) = when (s.phase) {
            DialogueUiState.Phase.READY -> "Dialogue model ready" to true
            DialogueUiState.Phase.PARTIAL -> "Dialogue model download paused" to false
            else -> "Dialogue model download failed" to false
        }
        runCatching {
            channel(this).notify(NOTE_DONE, NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_warning)
                .setContentTitle(title).setContentText(s.message ?: "").setAutoCancel(true).setContentIntent(openApp(this)).build())
        }
        stopSelf()
    }

    private fun goForeground(s: DialogueUiState) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, NOTE_PROGRESS, progressNote(this, s), type) }
    }

    companion object {
        const val ACTION_CANCEL = "com.logie.wifecraft.model.CANCEL"
        private const val CHANNEL = "downloads"
        private const val NOTE_PROGRESS = 4102
        private const val NOTE_DONE = 4103

        private fun channel(c: Context): NotificationManager {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The game's files being downloaded"
                    setShowBadge(false)
                })
            }
            return nm
        }

        private fun openApp(c: Context): PendingIntent? =
            c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                PendingIntent.getActivity(c, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }

        private fun progressNote(c: Context, s: DialogueUiState): Notification {
            channel(c)
            val checking = s.phase == DialogueUiState.Phase.CHECKING
            val line = "${s.haveBytes / 1_000_000} of ${s.totalBytes / 1_000_000} MB"
            return NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(if (checking) "Checking the dialogue model" else "Downloading the dialogue model")
                .setContentText(line)
                .setOnlyAlertOnce(true).setOngoing(true).setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setProgress(1000, (s.fraction * 1000).toInt(), s.totalBytes <= 0)
                .setContentIntent(openApp(c))
                .addAction(0, "Cancel", PendingIntent.getService(c, 5, Intent(c, ModelDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build()
        }
    }
}
