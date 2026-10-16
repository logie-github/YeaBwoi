// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app, and with it the server's programs, running while the server is up: Android
 * requires a notification for that. The notification says how the server is and has a Stop button.
 * While the game is on screen the notification steps aside (the game in front keeps the app
 * running) and comes back when the game goes behind, or the screen goes off ([setGameShown]).
 * The service ends itself once the server is stopped. While it runs, it sends the reports the
 * game's Report Time Anomaly asks Wrath Remix for ([AnomalyReports]), and takes the server to
 * ChromieCraft when a Wrath Remix Timerunner goes home ([Homecomings]).
 */
class ServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var reports: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** The notification is up (the service is in the foreground). */
    private var shown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Server.stop(this)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(Server.status.value))
        shown = true
        if (wakeLock == null) {
            // The server keeps the game world going with the screen off (the phone's own client
            // pauses then, but a PC's doesn't).
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "warcraft:server")
                .apply { acquire() }
        }
        if (watcher == null) {
            watcher = scope.launch {
                combine(Server.status, gameShown) { status, inGame -> status to inGame }.collect { (status, inGame) ->
                    when {
                        status.phase == Server.Phase.STOPPED || status.phase == Server.Phase.FAILED -> {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            shown = false
                            if (status.phase == Server.Phase.FAILED) notifyFailed()
                            stopSelf()
                        }
                        inGame -> if (shown) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            shown = false
                        }
                        !shown -> {
                            startForeground(NOTIFICATION_ID, notification(status))
                            shown = true
                        }
                        else -> manager().notify(NOTIFICATION_ID, notification(status))
                    }
                }
            }
        }
        if (reports == null) {
            reports = scope.launch {
                while (isActive) {
                    runCatching { AnomalyReports.sendWaiting(this@ServerService) }
                    runCatching { Homecomings.check(this@ServerService) }
                    delay(REPORTS_EVERY_MS)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun channel(): String {
        manager().createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.wow_server_channel), NotificationManager.IMPORTANCE_LOW)
        )
        return CHANNEL
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        packageManager.getLaunchIntentForPackage(packageName)!!.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(status: Server.Status): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_wow_notification)
            .setContentTitle(getString(R.string.wow_server_notification_title))
            .setContentText(statusText(this, status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .apply {
                val progress = status.progress
                if (status.phase == Server.Phase.STARTING) {
                    if (progress != null) setProgress(1000, (progress * 1000).toInt(), false) else setProgress(0, 0, true)
                }
                if (status.phase != Server.Phase.STOPPING) {
                    addAction(0, getString(R.string.wow_server_stop), stop)
                }
            }
            .build()
    }

    private fun notifyFailed() {
        manager().notify(
            FAILED_ID,
            NotificationCompat.Builder(this, channel())
                .setSmallIcon(R.drawable.ic_wow_notification)
                .setContentTitle(getString(R.string.wow_server_failed))
                .setContentText(getString(R.string.wow_server_failed_notification))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private val gameShown = MutableStateFlow(false)

        /** The game is on screen (true) or went behind, or its screen went off (false). */
        fun setGameShown(inGame: Boolean) {
            gameShown.value = inGame
        }

        /** Whether the game is on screen, read-only (Turtle WoW's service hides its notification too). */
        val gameOnScreen: kotlinx.coroutines.flow.StateFlow<Boolean> get() = gameShown

        /** How often the folder of Time Anomaly reports is looked in. */
        private const val REPORTS_EVERY_MS = 3_000L
        private const val CHANNEL = "server"
        private const val NOTIFICATION_ID = 1
        private const val FAILED_ID = 2
        private const val ACTION_STOP = "com.logie.warcraft.STOP_SERVER"

        /** What the server is doing, in a line. */
        fun statusText(context: Context, status: Server.Status): String = when (status.phase) {
            Server.Phase.STOPPED -> context.getString(R.string.wow_server_stopped)
            Server.Phase.STOPPING -> context.getString(R.string.wow_server_stopping)
            Server.Phase.FAILED -> context.getString(R.string.wow_server_failed)
            Server.Phase.RUNNING -> context.getString(
                if (status.world == Server.World.UP) R.string.wow_server_running else R.string.wow_server_running_no_world
            )
            Server.Phase.STARTING -> context.getString(
                when (status.step) {
                    Server.Step.UNPACKING -> R.string.wow_step_unpacking
                    Server.Step.CONFIGURING -> R.string.wow_step_configuring
                    Server.Step.DATABASE -> R.string.wow_step_database
                    Server.Step.IMPORT -> R.string.wow_step_import
                    Server.Step.RACES -> R.string.wow_step_races
                    Server.Step.MODULES -> R.string.wow_step_modules
                    Server.Step.AUTH -> R.string.wow_step_auth
                    Server.Step.WORLD, null -> R.string.wow_step_world
                }
            )
        }
    }
}
