// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import android.widget.Toast
import com.logie.warcraft.modules.GitHubApi
import com.logie.warcraft.reports.AnomalyReport
import com.logie.warcraft.reports.GameCrash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Sends the reports Wrath Remix writes when the player taps Report Time Anomaly in the game's menu
 * (mod-wrath-remix, `files/run/reports`) to this app's repository on GitHub as issues, while the
 * server runs ([ServerService]).
 *
 * Unseen, with a token that may open issues there: the player's own (the modules' token, given
 * Issues: read and write) or the one the app was built with. Without one, or when GitHub won't take
 * it from any, github.com's new issue page opens in the browser, filled in, for the player to send:
 * while the phone is online and one of the app's screens is in front (Android lets this process
 * start a screen only then, and says nothing when it doesn't). A report that couldn't go for want of
 * a network, or while GitHub was busy, waits and goes later; one sent before whose answer never came
 * is looked for on GitHub first, by its mark ([AnomalyReport.marked]), so it isn't opened twice.
 * Sent ones are kept in `sent/`, the ones opened in the browser in `opened/`.
 *
 * The game's own crash reports go the same way ([GameCrashReports] puts them in the folder, their
 * names starting [GameCrash.PREFIX]), labelled [GameCrash.LABEL].
 */
object AnomalyReports {
    private const val TAG = "AnomalyReports"
    /** How long a report that couldn't go waits before the next try. */
    private const val RETRY_MS = 60_000L
    /** Beside a report: when it went to GitHub, its answer not heard yet. */
    private const val POSTED = ".posted"

    /** The reports that couldn't go, and when they may try again. */
    private val waiting = mutableMapOf<String, Long>()
    /** The reports for the browser, waiting for the app's screens: their pages. */
    private val toOpen = mutableMapOf<String, String>()
    /**
     * One round of sending at a time: a service made anew (the server started again) begins its
     * own while the last one's request may still be out, and would send the same report.
     */
    private val sending = Mutex()

    fun folder(context: Context) = File(WowFiles.run(context), "reports")

    private sealed interface Outcome {
        data class Sent(val number: Int, val page: String) : Outcome
        data class ForBrowser(val link: String) : Outcome
        data object Offline : Outcome
        /** GitHub is busy, or the token sent too many requests. */
        data object Busy : Outcome
        data object Broken : Outcome
    }

    /** Sends the reports waiting in the folder; tells the player how each went. */
    suspend fun sendWaiting(context: Context) {
        val repo = BuildConfig.WOW_UPDATE_REPO
        if (repo.isEmpty() || !sending.tryLock()) return
        try {
            sendEach(context, repo)
        } finally {
            sending.unlock()
        }
    }

    private suspend fun sendEach(context: Context, repo: String) {
        val files = withContext(Dispatchers.IO) {
            folder(context).listFiles { file -> file.isFile && file.name.endsWith(".md") }?.sortedBy { it.name }.orEmpty()
        }
        val names = files.map { it.name }.toSet()
        waiting.keys.retainAll(names)
        toOpen.keys.retainAll(names)
        val now = System.currentTimeMillis()
        for (file in files) {
            val link = toOpen[file.name]
            if (link != null) {
                open(context, file, link)
                continue
            }
            if ((waiting[file.name] ?: 0L) > now) continue
            when (val outcome = withContext(Dispatchers.IO) { send(context, repo, file) }) {
                is Outcome.Sent -> {
                    waiting.remove(file.name)
                    toast(context, context.getString(if (crash(file)) R.string.wow_crash_sent else R.string.wow_report_sent, outcome.number))
                }
                is Outcome.ForBrowser -> {
                    waiting.remove(file.name)
                    toOpen[file.name] = outcome.link
                    open(context, file, outcome.link)
                }
                Outcome.Offline -> {
                    if (file.name !in waiting) toast(context, context.getString(if (crash(file)) R.string.wow_crash_waiting else R.string.wow_report_waiting))
                    waiting[file.name] = now + RETRY_MS
                }
                Outcome.Busy -> waiting[file.name] = now + RETRY_MS
                Outcome.Broken -> waiting.remove(file.name)
            }
        }
    }

    /**
     * GitHub's page for the report in the browser, once the phone is online and one of the app's
     * screens is in front (from behind, Android drops the start without a word); kept in `opened/`
     * once it's open. Without a browser it tries again in a while (a token may come by then).
     */
    private suspend fun open(context: Context, file: File, link: String) {
        if (!appInFront(context) || !online(context)) return
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        toOpen.remove(file.name)
        if (opened) {
            // (here, not on another thread: nothing comes between its opening and its leaving the folder)
            moveTo(file, "opened")
        } else {
            waiting[file.name] = System.currentTimeMillis() + RETRY_MS
        }
        toast(context, context.getString(when {
            crash(file) -> if (opened) R.string.wow_crash_opened else R.string.wow_crash_no_browser
            else -> if (opened) R.string.wow_report_opened else R.string.wow_report_no_browser
        }))
    }

    private fun send(context: Context, repo: String, file: File): Outcome {
        val text = runCatching { file.readText() }.getOrDefault("")
        val report = AnomalyReport.parse(text)?.signed(signature(crash(file)))
        val label = if (crash(file)) GameCrash.LABEL else AnomalyReport.LABEL
        if (report == null) {
            // (half written, or not a report: out of the way)
            if (System.currentTimeMillis() - file.lastModified() > RETRY_MS) moveTo(file, "broken")
            return Outcome.Broken
        }
        val marker = AnomalyReport.marker(file.name, text)
        val posted = File(file.path + POSTED)
        val tokens = listOfNotNull(ModuleStore.token(context), BuildConfig.WOW_UPDATE_TOKEN)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (token in tokens) {
            val github = GitHubApi({ token }, WowUpdater.userAgent)
            try {
                // Sent before, and no answer came (the connection dropped, or the app ended, after
                // GitHub took it): the issue may be there already.
                if (posted.isFile) {
                    val since = Instant.ofEpochMilli(posted.lastModified()).minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
                    val issues = github.get("https://${GitHubApi.API_HOST}/repos/$repo/issues?state=all&sort=created&direction=desc&per_page=100&since=$since")
                    val found = AnomalyReport.markedIssue(issues, marker)
                    if (found != null) {
                        moveTo(file, "sent")
                        Log.i(TAG, "${file.name}: already issue #${found.first}, ${found.second}")
                        return Outcome.Sent(found.first, found.second)
                    }
                }
                runCatching { posted.writeText("${System.currentTimeMillis()}\n") }
                val answer = github.postForAnswer("https://${GitHubApi.API_HOST}/repos/$repo/issues", report.marked(marker).issueJson(label))
                val (number, page) = AnomalyReport.openedIssue(answer) ?: (0 to "")
                moveTo(file, "sent")
                Log.i(TAG, "${file.name}: issue #$number, $page")
                return Outcome.Sent(number, page)
            } catch (e: GitHubApi.HttpError) {
                Log.w(TAG, "${file.name}: GitHub said ${e.message}")
                // (busy: later; a token without Issues, or for another repository: the next one, or the browser)
                if (AnomalyReport.busy(e.code, e.reason)) return Outcome.Busy
            } catch (e: IOException) {
                Log.w(TAG, "${file.name}: no way to GitHub ($e)")
                return Outcome.Offline
            }
        }
        return if (online(context)) Outcome.ForBrowser(report.newIssueLink(repo, label)) else Outcome.Offline
    }

    private fun signature(crash: Boolean) =
        "Sent by the World of Warcraft app ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) on Android ${Build.VERSION.RELEASE}, " +
            if (crash) "from the game's crash report." else "from the realm's report."

    private fun crash(file: File) = file.name.startsWith(GameCrash.PREFIX)

    /** One of the app's screens is in front (the launcher's, the game's): its process, not this one. */
    private fun appInFront(context: Context): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().any {
            it.pid != Process.myPid() && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }.getOrDefault(false)

    /** The phone is on a network that reaches the internet, as Android checked it. */
    private fun online(context: Context): Boolean = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        network != null && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    private fun moveTo(file: File, folder: String) {
        val into = File(file.parentFile, folder).apply { mkdirs() }
        if (!file.renameTo(File(into, file.name))) file.delete()
        // (while the report is still here, it may have gone: it's looked for before it goes again)
        if (!file.exists()) File(file.path + POSTED).delete()
    }

    private suspend fun toast(context: Context, text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }
}
