// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.logie.warcraft.MainActivity
import com.logie.warcraft.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the app running while a local realm (SPP Legion, The War Within) runs: its Wine with no screen
 * is the app's own processes ([WindowsRealmHost]), which Android ends with the app, so the app is a
 * foreground service then, with its notification (Stop Server) and the CPU kept awake with the
 * screen off. It ends itself once no realm is busy.
 */
class LocalRealmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shownText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(text()))
        if (intent?.action == ACTION_STOP) {
            WindowsRealmRegistry.servers().filter { it.isBusy }.forEach { it.stop(applicationContext) }
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "warcraft:local-realm")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (watcher == null) {
            watcher = scope.launch {
                // (a start's first moments: the realm busy from the tap on)
                delay(2000)
                while (true) {
                    if (WindowsRealmRegistry.servers().none { it.isBusy }) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                    val text = text()
                    if (text != shownText) {
                        shownText = text
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                    }
                    delay(2000)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // (the launcher swiped away: the realm runs on; Stop Server, here or in the app, ends it)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun text(): String {
        val busy = WindowsRealmRegistry.servers().filter { it.isBusy }
        if (busy.isEmpty()) return "Starting…"
        return busy.joinToString("\n") { server ->
            val status = server.status.value
            "${server.displayName}: " + when (status.phase) {
                WindowsRealmServer.Phase.RUNNING -> if (status.clientRunning) "running, game open" else "running"
                WindowsRealmServer.Phase.STARTING -> status.detail ?: "starting"
                WindowsRealmServer.Phase.STOPPING -> "stopping"
                WindowsRealmServer.Phase.FAILED -> "stopped by an error (open the app)"
                WindowsRealmServer.Phase.STOPPED -> "stopped"
            }
        }
    }

    private fun notification(text: String): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.local_realm_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 90,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 91, Intent(this, LocalRealmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_wow_notification)
            .setContentTitle(getString(R.string.local_realm_running))
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.local_realm_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "local-realms"
        private const val NOTIFICATION_ID = 92
        private const val ACTION_STOP = "com.logie.warcraft.local.STOP_REALM"

        /** From a realm's start (a tap: the app in front), before any of its Wine runs. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LocalRealmService::class.java)) }
        }
    }
}
