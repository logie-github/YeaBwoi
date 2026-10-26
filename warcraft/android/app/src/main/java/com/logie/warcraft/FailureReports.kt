// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.os.Build
import android.util.Log
import com.logie.warcraft.felsong.FelsongGame
import com.logie.warcraft.felsong.FelsongJobs
import com.logie.warcraft.felsong.FelsongServer
import com.logie.warcraft.game.Game
import com.logie.warcraft.local.LocalInstallJobs
import com.logie.warcraft.local.WindowsRealmServer
import com.logie.warcraft.reports.AppFailure
import com.logie.warcraft.spp.SppIntegration
import com.logie.warcraft.spp.SppJobs
import com.logie.warcraft.spp.SppServer
import com.logie.warcraft.turtle.TurtleGame
import com.logie.warcraft.turtle.TurtleJobs
import com.logie.warcraft.turtle.TurtleServer
import com.logie.warcraft.tww.TwwIntegration
import com.logie.warcraft.tww.TwwJobs
import com.logie.warcraft.tww.TwwServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Every failure the app meets, sent to this app's repository on GitHub as an issue: a realm that
 * wouldn't start (3.3.5a, OctoWoW, Legion, SPP Legion, The War Within), an installation, an import
 * or a game launch that failed, and the app itself crashing. Each is made a report in
 * [AnomalyReports]' folder ([AppFailure.markdown]: what the app said, the end of the logs), which
 * sends it as it sends the time anomaly reports, from the app's process: at once, and again every
 * minute while one waits (offline, GitHub busy). What the player chose (a job stopped) isn't a
 * failure; the same failure goes once a day at most (`failures-sent.txt` in the folder).
 */
object FailureReports {
    private const val TAG = "FailureReports"
    private const val SENT = "failures-sent.txt"
    /** How often the reports waiting in the folder are tried again. */
    private const val SEND_EVERY_MS = 60_000L
    /** How long a failure stays as it is before it's reported (the last of a quick run of them). */
    private const val SETTLE_MS = 3_000L
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    /** What failed, said by one of the app's states: its message, and its log when it has one. */
    private class Failure(val message: String, val log: () -> String? = { null })

    /** Once, as the app's process starts ([WowApp]): crashes in any of its processes, failures in the app's own. */
    fun watch(context: Context) {
        val app = context.applicationContext
        catchCrashes(app)
        if (RealmLink.inRealm) return
        // (a local realm that was running when Android last ended the app: why, from Android's record)
        runCatching { com.logie.warcraft.local.RealmExitReport.checkPrevious(app) }
        val stopped = setOf(
            app.getString(R.string.wow_import_cancelled), app.getString(R.string.turtle_job_cancelled),
            app.getString(R.string.felsong_job_cancelled), app.getString(R.string.tww_stop_other),
        )
        fun failure(message: String, log: String? = null) = Failure(message) { log }.takeUnless { message in stopped || AppFailure.ignored(message) }

        follow(app, "World of Warcraft 3.3.5a server", Server.status) {
            if (it.phase == Server.Phase.FAILED) failure("${it.realm.displayName} realm: ${it.error.orEmpty()}", it.log) else null
        }
        follow(app, "World of Warcraft 3.3.5a import", Imports.state) {
            (it as? Imports.State.Failed)?.let { f -> failure("${f.kind}: ${f.message}", f.log) }
        }
        follow(app, "World of Warcraft 3.3.5a game", Game.state) {
            (it as? Game.State.Failed)?.takeUnless { f -> f.needsAccount }?.let { f -> failure(f.message) }
        }
        follow(app, "OctoWoW server", TurtleServer.status) {
            if (it.phase == TurtleServer.Phase.FAILED && !it.blockedByChromieCraft) failure(it.error.orEmpty(), it.log) else null
        }
        follow(app, "OctoWoW installation", TurtleJobs.state) {
            (it as? TurtleJobs.State.Failed)?.let { f -> failure("${f.kind}: ${f.message}", f.log) }
        }
        follow(app, "OctoWoW game", TurtleGame.state) {
            (it as? TurtleGame.State.Failed)?.takeUnless { f -> f.needsAccount || f.chromieCraft }?.let { f -> failure(f.message) }
        }
        follow(app, "Legion 7.3.5 server", FelsongServer.status) {
            if (it.phase == FelsongServer.Phase.FAILED) failure(it.error.orEmpty(), it.log) else null
        }
        follow(app, "Legion 7.3.5 installation", FelsongJobs.state) {
            (it as? FelsongJobs.State.Failed)?.let { f -> failure("${f.kind}: ${f.message}", f.log) }
        }
        follow(app, "Legion 7.3.5 game", FelsongGame.state) {
            (it as? FelsongGame.State.Failed)?.takeUnless { f -> f.needsAccount }?.let { f -> failure(f.message) }
        }
        windowsRealm(app, SppServer, SppJobs, SppIntegration.displayName, ::failure)
        windowsRealm(app, TwwServer, TwwJobs, TwwIntegration.displayName, ::failure)

        scope.launch {
            while (true) {
                if (waiting(app)) runCatching { AnomalyReports.sendWaiting(app) }.onFailure { Log.w(TAG, "not sent ($it)") }
                delay(SEND_EVERY_MS)
            }
        }
    }

    private fun windowsRealm(app: Context, server: WindowsRealmServer, jobs: LocalInstallJobs, name: String,
                             failure: (String, String?) -> Failure?) {
        follow(app, "$name server", server.status) {
            if (it.phase == WindowsRealmServer.Phase.FAILED) failure(it.error.orEmpty(), null)
                ?.let { f -> Failure(f.message) { server.runtimeLogs(app) } } else null
        }
        follow(app, "$name installation", jobs.state) {
            (it as? LocalInstallJobs.State.Failed)?.let { f -> failure("${f.kind}: ${f.message}", null) }
        }
        // (the game's session not opening or the game exiting with an error: the realm runs on)
        follow(app, "$name game", server.status) {
            it.clientError?.let { message -> failure(message, null)?.let { f -> Failure(f.message) { server.runtimeLogs(app) } } }
        }
    }

    /**
     * Each time [states] comes to a failure (a new one: not the same one said again), reported, once
     * it has stayed a moment ([SETTLE_MS]): a realm says its session ended, then, a moment later, why.
     */
    @OptIn(FlowPreview::class)
    private fun <T> follow(app: Context, source: String, states: Flow<T>, failed: (T) -> Failure?) {
        scope.launch {
            states.map { runCatching { failed(it) }.getOrNull() }
                .debounce(SETTLE_MS)
                .distinctUntilChanged { a, b -> a?.message == b?.message }
                .collect { failure ->
                    if (failure != null) {
                        val log = runCatching { failure.log() }.getOrNull()
                        if (record(app, source, failure.message, log)) runCatching { AnomalyReports.sendWaiting(app) }
                    }
                }
        }
    }

    /**
     * The report of a failure in [source], put in the reports' folder unless the same one went in
     * the last day ([AppFailure.due]). True when it was put there.
     */
    fun record(context: Context, source: String, message: String, log: String?): Boolean = synchronized(lock) {
        val folder = AnomalyReports.folder(context).apply { mkdirs() }
        val sentFile = File(folder, SENT)
        val now = System.currentTimeMillis()
        val sent = runCatching { AppFailure.parseSent(sentFile.readText()) }.getOrDefault(emptyMap())
        val key = AppFailure.key(source, message)
        if (!AppFailure.due(sent, key, now)) return false
        val report = AppFailure.markdown(source, message, log, stamp.format(Instant.ofEpochMilli(now)), device())
        val name = AppFailure.PREFIX + now + "-" + key.substringAfterLast(' ') + ".md"
        // (written whole under another name first: the sender takes only .md files)
        val partial = File(folder, "$name.part")
        val kept = runCatching {
            partial.writeText(report)
            check(partial.renameTo(File(folder, name)))
        }.onFailure { Log.w(TAG, "$source: not kept ($it)") }.isSuccess
        if (kept) runCatching { sentFile.writeText(AppFailure.formatSent(sent + (key to now), now)) }
        Log.i(TAG, "$source failed: ${AppFailure.summary(message)}")
        kept
    }

    /**
     * The app crashing, in either of its processes: its report written there and then (the process
     * ends right after), sent when the app next runs. Then Android's own handling, as before.
     */
    private fun catchCrashes(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val where = if (RealmLink.inRealm) "the realm's process" else "the app's process"
                record(app, "The app", "Crashed in $where (thread ${thread.name}): $error", Log.getStackTraceString(error))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun waiting(context: Context): Boolean =
        AnomalyReports.folder(context).listFiles { file -> file.isFile && file.name.endsWith(".md") }?.isNotEmpty() == true

    private fun device() =
        "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}), app ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
}
