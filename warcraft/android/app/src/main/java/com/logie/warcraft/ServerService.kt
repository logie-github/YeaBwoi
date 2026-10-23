// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
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
import kotlinx.coroutines.launch

/**
 * Runs in the realm's own process (`:realm`, [RealmLink]): the server's programs are started from
 * it, so the app's screens and the game can be closed, or taken back by Android, and the server
 * goes on. Only Stop (here, on the launcher, or in the notification) ends it.
 *
 * Keeps that process, and with it the server's programs, running while the server is up: Android
 * requires a notification for that. The notification says how the server is and has a Stop button.
 * It stays up the whole time the server runs, the game on screen or not: a service that left the
 * foreground while the game was in front had to come back to it as the app went behind, the moment
 * Android is quickest to reclaim a big app, and a server killed then lost what hadn't been saved.
 * The service ends itself once the server is stopped. While it runs, it sends the reports the
 * game's Report Time Anomaly asks Wrath Remix for ([AnomalyReports]), and takes the server to
 * ChromieCraft when a Wrath Remix Timerunner goes home ([Homecomings]).
 */
class ServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listening: BroadcastReceiver? = null
    private var watcher: Job? = null
    private var reports: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** The notification is up (the service is in the foreground). */
    private var shown = false
    /** The last start this service took: it ends itself only if none came after ([stopSelf]). */
    private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        listening = RealmLink.listen(this)
        // (no status yet: a new process's would say stopped before it takes the start it was made
        // for, and the app would take that for the server stopping; onStartCommand says how it is)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // (in the foreground at once, whatever was asked: the app starts this service in the foreground)
        startForeground(NOTIFICATION_ID, notification(Server.status.value))
        shown = true
        lastStart = startId
        when (intent?.action) {
            ACTION_STOP -> Server.stop(this)
            RealmLink.ACTION_START -> Server.start(this, Realms.Realm.entries.firstOrNull { it.key == intent.getStringExtra(RealmLink.EXTRA_REALM) })
        }
        // (the app hears how it is now, whether or not that changed: it may have guessed)
        RealmLink.publish(Server.status.value)
        if (wakeLock == null) {
            // The server keeps the game world going with the screen off (the phone's own client
            // pauses then, but a PC's doesn't).
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "warcraft:server")
                .apply { acquire() }
        }
        if (watcher == null) {
            watcher = scope.launch {
                Server.status.collect { status ->
                    when {
                        status.phase == Server.Phase.STOPPED || status.phase == Server.Phase.FAILED -> {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            shown = false
                            if (status.phase == Server.Phase.FAILED) notifyFailed()
                            // (not if the server was started again meanwhile, a Timerunner's homecoming:
                            // that start, on its way to this service, keeps it, and it comes back to
                            // the foreground; one after this makes the service anew)
                            stopSelf(lastStart)
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
                    runCatching { GameCrashReports.collect(this@ServerService) }
                    runCatching { AnomalyReports.sendWaiting(this@ServerService) }
                    runCatching { Homecomings.check(this@ServerService) }
                    delay(REPORTS_EVERY_MS)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        listening?.let { runCatching { unregisterReceiver(it) } }
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

        /**
         * The game is on screen (true) or went behind, or its screen went off (false). Going behind,
         * the realm saves every character at once: the app may not be there when it comes back.
         */
        fun setGameShown(inGame: Boolean) {
            if (gameShown.value && !inGame) Server.worldCommand("saveall")
            gameShown.value = inGame
        }

        /** Whether the game is on screen, read-only (Turtle WoW's service hides its notification too). */
        val gameOnScreen: kotlinx.coroutines.flow.StateFlow<Boolean> get() = gameShown

        /** How often the folder of Time Anomaly reports is looked in. */
        private const val REPORTS_EVERY_MS = 3_000L
        private const val CHANNEL = "server"
        private const val NOTIFICATION_ID = 1
        private const val FAILED_ID = 2
        private const val ACTION_STOP = RealmLink.ACTION_STOP

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
