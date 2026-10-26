// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs one of [Imports]' jobs (copying gigabytes, or the data tools for an hour) with the app in
 * the background too: Android keeps a service with a notification running, and the wake lock keeps
 * the phone working with the screen off. The server is stopped first: the world server reads the
 * data that's being replaced.
 */
class ImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Imports.cancelled = true
            return START_NOT_STICKY
        }
        val kind = intent?.getStringExtra(EXTRA_KIND)?.let { Imports.Kind.valueOf(it) }
        if (kind == null || Imports.state.value is Imports.State.Working) return START_NOT_STICKY
        val uri = intent.data
        val withMmaps = intent.getBooleanExtra(EXTRA_MMAPS, false)
        val folder = intent.getBooleanExtra(EXTRA_FOLDER, false)
        val stock = intent.getBooleanExtra(EXTRA_STOCK, false)
        val savedRealm = intent.getStringExtra(EXTRA_REALM)

        Imports.cancelled = false
        Imports.set(Imports.State.Working(kind))
        startForeground(NOTIFICATION_ID, notification(kind, null, null))
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "warcraft:import")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        if (kind == Imports.Kind.DOWNLOAD || kind == Imports.Kind.MODS || kind == Imports.Kind.MODULES || kind == Imports.Kind.ZONES) {
            // With the screen off, Android would otherwise slow the Wi-Fi down to save power.
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "warcraft:download")
                .apply { setReferenceCounted(false); acquire() }
        }

        scope.launch {
            val result = runCatching {
                // An HD look changes the client only: the realm can keep running.
                if (kind != Imports.Kind.MODS) {
                    Server.stopAndWait(this@ImportService)
                }
                when (kind) {
                    Imports.Kind.CLIENT -> {
                        val report = { progress: Float, detail: String? -> progress(kind, progress, detail) }
                        if (folder) Imports.importClientFolder(this@ImportService, uri!!, report)
                        else Imports.importClientZip(this@ImportService, uri!!, report)
                    }
                    Imports.Kind.DOWNLOAD -> Imports.downloadServerData(this@ImportService) { part, progress, detail ->
                        Imports.set(Imports.State.Working(kind, part, 2, progress, detail))
                        notifyThrottled(kind, progress, "$part / 2 · ${(progress * 100).toInt()}%")
                    }
                    Imports.Kind.SERVER_DATA -> Imports.importServerData(this@ImportService, uri!!) { progress, detail ->
                        progress(kind, progress, detail)
                    }
                    Imports.Kind.EXTRACT -> Imports.extract(this@ImportService, withMmaps) { part, parts, detail ->
                        Imports.set(Imports.State.Working(kind, part, parts, null, detail))
                        notifyThrottled(kind, null, "$part / $parts")
                    }
                    Imports.Kind.MODS -> ClientMods.apply(this@ImportService) { progress, detail ->
                        Imports.set(Imports.State.Working(kind, progress = progress, detail = detail))
                        notifyThrottled(kind, progress, detail)
                    }
                    Imports.Kind.ZONES -> ZonesPackage.downloadAndInstall(this@ImportService, { Imports.cancelled }) { part, progress, detail ->
                        Imports.set(Imports.State.Working(kind, part, 2, progress, detail))
                        notifyThrottled(kind, progress, "$part / 2 · ${(progress * 100).toInt()}%")
                    }
                    Imports.Kind.MODULES -> {
                        val report = { progress: Float?, detail: String? ->
                            Imports.set(Imports.State.Working(kind, progress = progress, detail = detail))
                            notifyThrottled(kind, progress, detail)
                        }
                        when {
                            stock -> ModuleStore.installStock(this@ImportService, report)
                            savedRealm != null -> ModuleStore.putIn(this@ImportService, savedRealm, report)
                            else -> ModuleStore.install(this@ImportService, report)
                        }
                    }
                }
            }
            val state = result.fold(
                onSuccess = { Imports.State.Done(kind) },
                onFailure = {
                    if (Imports.cancelled) Imports.State.Failed(kind, getString(R.string.wow_import_cancelled))
                    else Imports.State.Failed(kind, it.message ?: it.javaClass.simpleName, (it as? Imports.ImportException)?.log)
                },
            )
            // A stopped or failed job leaves its half-made folder behind: it's of no use. (A
            // download's stays, to carry on from next time.)
            for (name in listOf("client.import", "data.import", "data.work", "zones.new")) {
                java.io.File(WowFiles.root(this@ImportService), name).deleteRecursively()
            }
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
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun progress(kind: Imports.Kind, progress: Float, detail: String?) {
        Imports.set(Imports.State.Working(kind, progress = progress, detail = detail))
        notifyThrottled(kind, progress, null)
    }

    private fun notifyThrottled(kind: Imports.Kind, progress: Float?, text: String?) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 1000) return
        lastNotified = now
        manager().notify(NOTIFICATION_ID, notification(kind, progress, text))
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun channel(): String {
        manager().createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.wow_import_channel), NotificationManager.IMPORTANCE_LOW)
        )
        return CHANNEL
    }

    private fun title(kind: Imports.Kind) = getString(
        when (kind) {
            Imports.Kind.CLIENT -> R.string.wow_import_client_title
            Imports.Kind.DOWNLOAD -> R.string.wow_download_title
            Imports.Kind.EXTRACT -> R.string.wow_extract_title
            Imports.Kind.SERVER_DATA -> R.string.wow_data_import_title
            Imports.Kind.MODS -> R.string.wow_mods_title
            Imports.Kind.MODULES -> R.string.wow_modules_job_title
            Imports.Kind.ZONES -> R.string.wow_zones_title
        }
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        packageManager.getLaunchIntentForPackage(packageName)!!.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(kind: Imports.Kind, progress: Float?, text: String?): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ImportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, channel())
            .setSmallIcon(R.drawable.ic_wow_notification)
            .setContentTitle(title(kind))
            .setContentText(text ?: progress?.let { "${(it * 100).toInt()}%" })
            .setProgress(1000, ((progress ?: 0f) * 1000).toInt(), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.wow_import_stop), cancel)
            .build()
    }

    private fun notifyDone(state: Imports.State) {
        val (kind, ok) = when (state) {
            is Imports.State.Done -> state.kind to true
            is Imports.State.Failed -> state.kind to false
            else -> return
        }
        manager().notify(
            DONE_ID,
            NotificationCompat.Builder(this, channel())
                .setSmallIcon(R.drawable.ic_wow_notification)
                .setContentTitle(title(kind))
                .setContentText(getString(if (ok) R.string.wow_import_notify_done else R.string.wow_import_notify_failed))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "imports"
        private const val NOTIFICATION_ID = 3
        private const val DONE_ID = 4
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_MMAPS = "mmaps"
        private const val EXTRA_FOLDER = "folder"
        private const val EXTRA_STOCK = "stock"
        private const val EXTRA_REALM = "realm"
        private const val ACTION_CANCEL = "com.logie.warcraft.CANCEL_IMPORT"

        private fun start(context: Context, intent: Intent) = ContextCompat.startForegroundService(context, intent)

        fun importClient(context: Context, uri: Uri, isFolder: Boolean) = start(
            context,
            Intent(context, ImportService::class.java).setData(uri)
                .putExtra(EXTRA_KIND, Imports.Kind.CLIENT.name).putExtra(EXTRA_FOLDER, isFolder),
        )

        fun importServerData(context: Context, uri: Uri) = start(
            context,
            Intent(context, ImportService::class.java).setData(uri).putExtra(EXTRA_KIND, Imports.Kind.SERVER_DATA.name),
        )

        fun downloadServerData(context: Context) = start(
            context,
            Intent(context, ImportService::class.java).putExtra(EXTRA_KIND, Imports.Kind.DOWNLOAD.name),
        )

        /** The Worgen's and Goblins' starting areas (ZonesPackage). */
        fun downloadZones(context: Context) = start(
            context,
            Intent(context, ImportService::class.java).putExtra(EXTRA_KIND, Imports.Kind.ZONES.name),
        )

        fun extract(context: Context, withMmaps: Boolean) = start(
            context,
            Intent(context, ImportService::class.java)
                .putExtra(EXTRA_KIND, Imports.Kind.EXTRACT.name).putExtra(EXTRA_MMAPS, withMmaps),
        )

        /** Brings the client in line with the HD look chosen (ClientMods). */
        fun applyMods(context: Context) = start(
            context,
            Intent(context, ImportService::class.java).putExtra(EXTRA_KIND, Imports.Kind.MODS.name),
        )

        /**
         * Puts the realm built with the user's modules in place of the server ([stock]: the app's
         * own back in its place).
         */
        /** The realm built last ([stock]: the app's own; [saved]: a kept one, by its request) put in. */
        fun installModules(context: Context, stock: Boolean = false, saved: String? = null) = start(
            context,
            Intent(context, ImportService::class.java)
                .putExtra(EXTRA_KIND, Imports.Kind.MODULES.name).putExtra(EXTRA_STOCK, stock)
                .apply { if (saved != null) putExtra(EXTRA_REALM, saved) },
        )

        fun cancel(context: Context) {
            Imports.cancelled = true
        }
    }
}
