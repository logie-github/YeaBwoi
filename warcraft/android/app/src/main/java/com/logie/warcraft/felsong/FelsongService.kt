// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong

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
import com.logie.warcraft.ModuleStore
import com.logie.warcraft.R
import com.logie.warcraft.WowUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app running while Felsong's realm runs or one of its jobs ([FelsongJobs]) works:
 * Android requires a notification for that, Felsong's own (its own channel and ids, next to the
 * other realms' services, which it never touches). Jobs run here on a thread of their own, one at
 * a time. The service ends itself once nothing of Felsong's runs.
 */
class FelsongService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shown = false
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A download may start here with the launcher gone (a build without CI's token reads the user's).
        WowUpdater.userToken = ModuleStore.token(this)
        when (intent?.action) {
            ACTION_STOP -> {
                FelsongServer.stop(this)
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                FelsongJobs.cancel()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, notification())
        shown = true
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "warcraft:felsong")
                .apply { acquire() }
        }
        if (intent?.action == ACTION_JOB) startJob(intent)
        if (watcher == null) {
            watcher = scope.launch {
                combine(FelsongServer.status, FelsongJobs.state) { server, job -> server to job }
                    .collect { (server, job) ->
                        val serverIdle = server.phase == FelsongServer.Phase.STOPPED || server.phase == FelsongServer.Phase.FAILED
                        val jobIdle = job !is FelsongJobs.State.Working
                        when {
                            serverIdle && jobIdle -> {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                shown = false
                                if (job is FelsongJobs.State.Done || job is FelsongJobs.State.Failed) notifyResult(job)
                                stopSelf()
                            }
                            !shown -> {
                                startForeground(NOTIFICATION_ID, notification())
                                shown = true
                            }
                            else -> {
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
        if (FelsongJobs.busy && FelsongJobs.state.value.let { it is FelsongJobs.State.Working && it.step != FelsongJobs.Step.CHECKING }) return
        val kind = FelsongJobs.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: return)
        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        val options = FelsongJobs.Options(
            withMmaps = intent.getBooleanExtra(EXTRA_MMAPS, false),
            accountName = intent.getStringExtra(EXTRA_ACCOUNT),
            accountPassword = intent.getStringExtra(EXTRA_PASSWORD),
            repairs = intent.getStringArrayExtra(EXTRA_REPAIRS).orEmpty().mapNotNull { name ->
                runCatching { FelsongJobs.Repair.valueOf(name) }.getOrNull()
            }.toSet(),
            restoreFile = intent.getStringExtra(EXTRA_FILE),
        )
        FelsongJobs.begin(kind)
        Thread({ FelsongJobs.run(applicationContext, kind, uri, intent.getBooleanExtra(EXTRA_FOLDER, false), options) }, "felsong-job").start()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun channel(): String {
        manager().createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.felsong_channel), NotificationManager.IMPORTANCE_LOW))
        return CHANNEL
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 20,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, FelsongService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(): Notification {
        val job = FelsongJobs.state.value
        val server = FelsongServer.status.value
        return NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_wow_notification)
            .setContentTitle(getString(R.string.felsong_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .apply {
                if (job is FelsongJobs.State.Working) {
                    setContentText(FelsongTexts.step(this@FelsongService, job.step))
                    job.detail?.let { setSubText(it.take(60)) }
                    val progress = job.progress
                    if (progress != null) setProgress(1000, (progress * 1000).toInt(), false) else setProgress(0, 0, true)
                    addAction(0, getString(R.string.felsong_cancel), action(ACTION_CANCEL, 22))
                } else {
                    setContentText(FelsongTexts.server(this@FelsongService, server))
                    if (server.phase == FelsongServer.Phase.STARTING) setProgress(0, 0, true)
                    if (server.phase != FelsongServer.Phase.STOPPING) addAction(0, getString(R.string.felsong_stop_realm), action(ACTION_STOP, 21))
                }
            }
            .build()
    }

    private fun notifyResult(job: FelsongJobs.State) {
        if (MainActivity.visible) return
        val (title, text) = when (job) {
            is FelsongJobs.State.Done -> getString(R.string.felsong_job_done) to job.message
            is FelsongJobs.State.Failed -> getString(R.string.felsong_job_failed) to job.message
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
        private const val CHANNEL = "felsong"
        // Not ServerService's (1, 2), ImportService's or TurtleService's (41, 42).
        private const val NOTIFICATION_ID = 51
        private const val RESULT_ID = 52
        private const val ACTION_STOP = "com.logie.warcraft.felsong.STOP"
        private const val ACTION_CANCEL = "com.logie.warcraft.felsong.CANCEL"
        private const val ACTION_JOB = "com.logie.warcraft.felsong.JOB"
        private const val ACTION_KEEP = "com.logie.warcraft.felsong.KEEP"
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
            FelsongServer.startService(context, FelsongServer.serviceIntent(context).setAction(ACTION_KEEP))

        /** Starts a job (if none runs). */
        fun job(
            context: Context,
            kind: FelsongJobs.Kind,
            uri: Uri? = null,
            folder: Boolean = false,
            options: FelsongJobs.Options = FelsongJobs.Options(),
        ) {
            if (FelsongJobs.busy) return
            FelsongJobs.begin(kind)
            val intent = FelsongServer.serviceIntent(context).setAction(ACTION_JOB)
                .putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_FOLDER, folder)
                .putExtra(EXTRA_MMAPS, options.withMmaps)
                .putExtra(EXTRA_REPAIRS, options.repairs.map { it.name }.toTypedArray())
            uri?.let { intent.putExtra(EXTRA_URI, it.toString()) }
            options.accountName?.let { intent.putExtra(EXTRA_ACCOUNT, it) }
            options.accountPassword?.let { intent.putExtra(EXTRA_PASSWORD, it) }
            options.restoreFile?.let { intent.putExtra(EXTRA_FILE, it) }
            FelsongServer.startService(context, intent)
        }
    }
}
