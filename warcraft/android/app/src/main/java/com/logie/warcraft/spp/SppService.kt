// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.spp

import com.logie.warcraft.local.LocalInstallJobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.logie.warcraft.MainActivity
import com.logie.warcraft.R
import com.logie.warcraft.spp.core.SppRealm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The same foreground installation lifecycle used by the other expansions. */
class SppService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var worker: Thread? = null
    private var notifiedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            SppJobs.cancel()
            if (worker?.isAlive != true) stopSelf()
            return START_NOT_STICKY
        }
        val kind = intent?.getStringExtra(KIND)?.let { runCatching { LocalInstallJobs.Kind.valueOf(it) }.getOrNull() }
        if (worker?.isAlive == true) return START_REDELIVER_INTENT
        if (kind == null) { stopSelf(startId); return START_NOT_STICKY }
        if (!SppJobs.busy && !SppJobs.begin(kind)) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification())
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "warcraft:spp-import")
            .apply { setReferenceCounted(false); acquire() }
        if (kind in setOf(LocalInstallJobs.Kind.INSTALL, LocalInstallJobs.Kind.DOWNLOAD_CLIENT, LocalInstallJobs.Kind.DOWNLOAD_SERVER, LocalInstallJobs.Kind.DOWNLOAD_MAPS)) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "warcraft:spp-download")
                .apply { setReferenceCounted(false); acquire() }
        }
        scope.launch {
            SppJobs.state.collect {
                val now = System.currentTimeMillis()
                if (it is LocalInstallJobs.State.Working && now - notifiedAt >= 500) {
                    notifiedAt = now
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                }
            }
        }
        val picked = intent.getStringExtra(URI)?.let { Uri.parse(it) }
        val folder = intent.getBooleanExtra(FOLDER, false)
        lastRequest = Request(kind, picked, folder)
        worker = Thread({
            SppJobs.run(applicationContext, kind, picked, folder)
            val outcome = SppJobs.state.value
            if (!MainActivity.visible) notifyResult(outcome)
            stopForeground(STOP_FOREGROUND_REMOVE)
            // A notification cancellation receives a newer startId too; stop the whole idle
            // service so its locks cannot remain held after that request.
            stopSelf()
        }, "spp-installation").apply { start() }
        // Android can restart an interrupted foreground job with its original source.
        // The installer validates/reuses completed files and resumes its partial download.
        return START_REDELIVER_INTENT
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The service owns this job; removing the launcher task is not cancellation.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (worker?.isAlive == true) { SppJobs.cancel(); worker?.interrupt() }
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 70,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun channel(): String {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, SppRealm.DISPLAY_NAME, NotificationManager.IMPORTANCE_LOW))
        return CHANNEL
    }

    private fun notification(): Notification {
        val current = SppJobs.state.value as? LocalInstallJobs.State.Working
        val cancel = PendingIntent.getService(this, 71, Intent(this, SppService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_wow_notification)
            .setContentTitle(SppRealm.DISPLAY_NAME)
            .setContentText(current?.detail ?: "Checking installation")
            .setStyle(NotificationCompat.BigTextStyle().bigText(current?.detail))
            .setProgress(1000, ((current?.progress ?: 0f) * 1000).toInt(), current?.progress == null)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun notifyResult(state: LocalInstallJobs.State) {
        val message = when (state) {
            is LocalInstallJobs.State.Done -> state.message
            is LocalInstallJobs.State.Failed -> state.message
            else -> return
        }
        getSystemService(NotificationManager::class.java).notify(RESULT_ID,
            NotificationCompat.Builder(this, channel())
                .setSmallIcon(R.drawable.ic_wow_notification)
                .setContentTitle(SppRealm.DISPLAY_NAME)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(openApp()).setAutoCancel(true).build())
    }

    companion object {
        private const val CHANNEL = "spp-imports"
        private const val NOTIFICATION_ID = 71
        private const val RESULT_ID = 72
        private const val KIND = "kind"
        private const val URI = "uri"
        private const val FOLDER = "folder"
        private const val CANCEL = "com.logie.warcraft.spp.CANCEL_IMPORT"
        private data class Request(val kind: LocalInstallJobs.Kind, val uri: Uri?, val folder: Boolean)
        @Volatile private var lastRequest: Request? = null

        /** Reserve the job before starting the service, preventing double taps from racing. */
        fun job(context: Context, kind: LocalInstallJobs.Kind, uri: Uri? = null, folder: Boolean = false) {
            if (!SppJobs.begin(kind)) return
            lastRequest = Request(kind, uri, folder)
            if (uri != null) runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val intent = Intent(context, SppService::class.java).putExtra(KIND, kind.name).putExtra(FOLDER, folder)
            uri?.let { intent.putExtra(URI, it.toString()) }
            try { ContextCompat.startForegroundService(context, intent) }
            catch (e: Exception) { SppJobs.failed(kind, "Cannot start installation service: ${e.message ?: e.javaClass.simpleName}") }
        }

        fun start(context: Context, kind: LocalInstallJobs.Kind, uri: Uri? = null, folder: Boolean = false) = job(context, kind, uri, folder)

        /** Uses the same source; downloads automatically retain and validate their partial file. */
        fun retry(context: Context) {
            lastRequest?.let { job(context, it.kind, it.uri, it.folder) }
        }

        fun cancel(context: Context) {
            SppJobs.cancel()
        }
    }
}
