// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle

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
import com.logie.warcraft.MainActivity
import com.logie.warcraft.R
import com.logie.warcraft.ServerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app running while Turtle WoW's realm runs or one of its jobs ([TurtleJobs]) works:
 * Android requires a notification for that, Turtle's own (its own channel and ids, next to the
 * ChromieCraft realm's ServerService, which it never touches). Jobs run here on a thread of their
 * own, one at a time. While the game is on screen the notification steps aside, as ServerService's
 * does; the service ends itself once nothing of Turtle's runs.
 */
class TurtleService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shown = false
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                TurtleServer.stop(this)
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                TurtleJobs.cancel()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, notification())
        shown = true
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "warcraft:turtle")
                .apply { acquire() }
        }
        if (intent?.action == ACTION_JOB) startJob(intent)
        if (watcher == null) {
            watcher = scope.launch {
                combine(TurtleServer.status, TurtleJobs.state, ServerService.gameOnScreen) { server, job, inGame -> Triple(server, job, inGame) }
                    .collect { (server, job, inGame) ->
                        val serverIdle = server.phase == TurtleServer.Phase.STOPPED || server.phase == TurtleServer.Phase.FAILED
                        val jobIdle = job !is TurtleJobs.State.Working
                        when {
                            serverIdle && jobIdle -> {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                shown = false
                                if (job is TurtleJobs.State.Done || job is TurtleJobs.State.Failed) notifyResult(job)
                                stopSelf()
                            }
                            inGame && jobIdle -> if (shown) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                shown = false
                            }
                            !shown -> {
                                startForeground(NOTIFICATION_ID, notification())
                                shown = true
                            }
                            else -> {
                                // Progress at most twice a second.
                                val now = System.currentTimeMillis()
                                if (now - lastNotified > 500) {
                                    lastNotified = now
                                    manager().notify(NOTIFICATION_ID, notification())
                                }
                            }
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun startJob(intent: Intent) {
        if (TurtleJobs.busy && TurtleJobs.state.value.let { it is TurtleJobs.State.Working && it.step != TurtleJobs.Step.CHECKING }) return
        val kind = TurtleJobs.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: return)
        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        val options = TurtleJobs.Options(
            withMmaps = intent.getBooleanExtra(EXTRA_MMAPS, false),
            accountName = intent.getStringExtra(EXTRA_ACCOUNT),
            accountPassword = intent.getStringExtra(EXTRA_PASSWORD),
            repairs = intent.getStringArrayExtra(EXTRA_REPAIRS).orEmpty().mapNotNull { name ->
                runCatching { TurtleJobs.Repair.valueOf(name) }.getOrNull()
            }.toSet(),
            restoreFile = intent.getStringExtra(EXTRA_FILE),
        )
        TurtleJobs.begin(kind)
        Thread({ TurtleJobs.run(applicationContext, kind, uri, intent.getBooleanExtra(EXTRA_FOLDER, false), options) }, "turtle-job").start()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun channel(): String {
        manager().createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.turtle_channel), NotificationManager.IMPORTANCE_LOW))
        return CHANNEL
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 10,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, TurtleService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(): Notification {
        val job = TurtleJobs.state.value
        val server = TurtleServer.status.value
        return NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_wow_notification)
            .setContentTitle(getString(R.string.turtle_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .apply {
                if (job is TurtleJobs.State.Working) {
                    setContentText(TurtleTexts.step(this@TurtleService, job.step))
                    job.detail?.let { setSubText(it.take(60)) }
                    val progress = job.progress
                    if (progress != null) setProgress(1000, (progress * 1000).toInt(), false) else setProgress(0, 0, true)
                    addAction(0, getString(R.string.turtle_cancel), action(ACTION_CANCEL, 12))
                } else {
                    setContentText(TurtleTexts.server(this@TurtleService, server))
                    if (server.phase == TurtleServer.Phase.STARTING) setProgress(0, 0, true)
                    if (server.phase != TurtleServer.Phase.STOPPING) addAction(0, getString(R.string.turtle_stop_realm), action(ACTION_STOP, 11))
                }
            }
            .build()
    }

    private fun notifyResult(job: TurtleJobs.State) {
        if (MainActivity.visible) return
        val (title, text) = when (job) {
            is TurtleJobs.State.Done -> getString(R.string.turtle_job_done) to job.message
            is TurtleJobs.State.Failed -> getString(R.string.turtle_job_failed) to job.message
            else -> return
        }
        manager().notify(
            RESULT_ID,
            NotificationCompat.Builder(this, channel())
                .setSmallIcon(R.drawable.ic_wow_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "turtle"
        // Not ServerService's (1, 2) nor ImportService's.
        private const val NOTIFICATION_ID = 41
        private const val RESULT_ID = 42
        private const val ACTION_STOP = "com.logie.warcraft.turtle.STOP"
        private const val ACTION_CANCEL = "com.logie.warcraft.turtle.CANCEL"
        private const val ACTION_JOB = "com.logie.warcraft.turtle.JOB"
        private const val ACTION_KEEP = "com.logie.warcraft.turtle.KEEP"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_FOLDER = "folder"
        private const val EXTRA_MMAPS = "mmaps"
        private const val EXTRA_ACCOUNT = "account"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_REPAIRS = "repairs"
        private const val EXTRA_FILE = "file"

        /** The realm is starting: keep the app running for it. */
        fun keepAlive(context: Context) =
            TurtleServer.startService(context, TurtleServer.serviceIntent(context).setAction(ACTION_KEEP))

        /** Starts a job (if none runs). */
        fun job(
            context: Context,
            kind: TurtleJobs.Kind,
            uri: Uri? = null,
            folder: Boolean = false,
            options: TurtleJobs.Options = TurtleJobs.Options(),
        ) {
            if (TurtleJobs.busy) return
            TurtleJobs.begin(kind)
            val intent = TurtleServer.serviceIntent(context).setAction(ACTION_JOB)
                .putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_FOLDER, folder)
                .putExtra(EXTRA_MMAPS, options.withMmaps)
                .putExtra(EXTRA_REPAIRS, options.repairs.map { it.name }.toTypedArray())
            uri?.let { intent.putExtra(EXTRA_URI, it.toString()) }
            options.accountName?.let { intent.putExtra(EXTRA_ACCOUNT, it) }
            options.accountPassword?.let { intent.putExtra(EXTRA_PASSWORD, it) }
            options.restoreFile?.let { intent.putExtra(EXTRA_FILE, it) }
            TurtleServer.startService(context, intent)
        }
    }
}
