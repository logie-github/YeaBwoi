// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe.hd

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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.logie.pe.MainActivity
import com.logie.pe.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the app running while an HD texture pack unpacks (it takes minutes), with a notification
 * showing how far it is and a button to stop it; the phone stays awake for it with the screen
 * off. When the unpacking ends, the notification says how it went, unless the app is on screen.
 */
class HdImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watcher: Job? = null
    private var lastUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            HdTextures.cancel()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_PROGRESS,
            progressNotification(null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ParasiteEve:hd-textures")
                .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
        watcher?.cancel()
        watcher = scope.launch {
            HdTextures.state.collectLatest { state ->
                when (state) {
                    is HdTextures.State.Checking -> notify(progressNotification(null))
                    is HdTextures.State.Unpacking -> {
                        // A notification a second is plenty.
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 1000) {
                            lastUpdate = now
                            notify(progressNotification(state))
                        }
                    }
                    is HdTextures.State.Done, is HdTextures.State.Failed -> {
                        finished(state)
                        return@collectLatest
                    }
                    HdTextures.State.Idle -> finished(null)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_PROGRESS, notification)
    }

    private fun finished(state: HdTextures.State?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val text = when (state) {
            is HdTextures.State.Done -> getString(R.string.pe_hd_notify_done)
            is HdTextures.State.Failed ->
                if (state.reason == HdTextures.Reason.CANCELLED) null else getString(R.string.pe_hd_notify_failed)
            else -> null
        }
        if (text != null && !HdTextures.appVisible) {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_RESULT,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_pe_notification)
                    .setContentTitle(getString(R.string.pe_hd_title))
                    .setContentText(text)
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build(),
            )
        }
        stopSelf()
    }

    private fun progressNotification(state: HdTextures.State.Unpacking?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_pe_notification)
            .setContentTitle(getString(R.string.pe_hd_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                getString(R.string.pe_hd_stop),
                PendingIntent.getService(
                    this, 1, Intent(this, HdImportService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        if (state == null || state.total <= 0) {
            builder.setContentText(getString(R.string.pe_hd_checking)).setProgress(0, 0, true)
        } else {
            val percent = (state.done * 100 / state.total).toInt().coerceIn(0, 100)
            builder.setContentText(getString(R.string.pe_hd_unpacking, percent)).setProgress(1000, (state.done * 1000 / state.total).toInt(), false)
        }
        return builder.build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL = "hd_textures"
        private const val NOTIFICATION_PROGRESS = 1
        private const val NOTIFICATION_RESULT = 2
        private const val ACTION_CANCEL = "com.logie.pe.hd.CANCEL"
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L

        private fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.pe_hd_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
