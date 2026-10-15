// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.logie.warcraft.modules.GitHubApi
import com.logie.warcraft.reports.AnomalyReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Sends the reports Wrath Remix writes when the player taps Report Time Anomaly in the game's menu
 * (mod-wrath-remix, `files/run/reports`) to this app's repository on GitHub as issues, while the
 * server runs ([ServerService]).
 *
 * Unseen, with a token that may open issues there: the player's own (the modules' token, given
 * Issues: read and write) or the one the app was built with. Without one, or when GitHub won't take
 * it, github.com's new issue page opens in the browser, filled in, for the player to send. A report
 * that couldn't go for want of a network waits and goes later. Sent ones are kept in `sent/`, the
 * ones opened in the browser in `opened/`.
 */
object AnomalyReports {
    private const val TAG = "AnomalyReports"
    /** How long a report that couldn't go waits before the next try. */
    private const val RETRY_MS = 60_000L

    /** The reports that couldn't go, and when they may try again. */
    private val waiting = mutableMapOf<String, Long>()

    fun folder(context: Context) = File(WowFiles.run(context), "reports")

    private sealed interface Outcome {
        data class Sent(val number: Int, val page: String) : Outcome
        data class Opened(val link: String) : Outcome
        data object Waiting : Outcome
        data object Broken : Outcome
    }

    /** Sends the reports waiting in the folder; tells the player how each went. */
    suspend fun sendWaiting(context: Context) {
        val repo = BuildConfig.WOW_UPDATE_REPO
        if (repo.isEmpty()) return
        val files = withContext(Dispatchers.IO) {
            folder(context).listFiles { file -> file.isFile && file.name.endsWith(".md") }?.sortedBy { it.name }.orEmpty()
        }
        val now = System.currentTimeMillis()
        for (file in files) {
            if ((waiting[file.name] ?: 0L) > now) continue
            val outcome = withContext(Dispatchers.IO) { send(context, repo, file) }
            when (outcome) {
                is Outcome.Sent -> {
                    waiting.remove(file.name)
                    toast(context, context.getString(R.string.wow_report_sent, outcome.number))
                }
                is Outcome.Opened -> {
                    waiting.remove(file.name)
                    val opened = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(outcome.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess
                    toast(context, context.getString(if (opened) R.string.wow_report_opened else R.string.wow_report_no_browser))
                }
                Outcome.Waiting -> {
                    if (file.name !in waiting) toast(context, context.getString(R.string.wow_report_waiting))
                    waiting[file.name] = now + RETRY_MS
                }
                Outcome.Broken -> waiting.remove(file.name)
            }
        }
    }

    private fun send(context: Context, repo: String, file: File): Outcome {
        val report = AnomalyReport.parse(runCatching { file.readText() }.getOrDefault(""))?.signed(signature())
        if (report == null) {
            // (half written, or not a report: out of the way)
            if (System.currentTimeMillis() - file.lastModified() > RETRY_MS) moveTo(file, "broken")
            return Outcome.Broken
        }
        val tokens = listOfNotNull(ModuleStore.token(context), BuildConfig.WOW_UPDATE_TOKEN)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (token in tokens) {
            try {
                val answer = GitHubApi({ token }, WowUpdater.userAgent)
                    .postForAnswer("https://${GitHubApi.API_HOST}/repos/$repo/issues", report.issueJson())
                val (number, page) = AnomalyReport.openedIssue(answer) ?: (0 to "")
                moveTo(file, "sent")
                Log.i(TAG, "${file.name}: issue #$number, $page")
                return Outcome.Sent(number, page)
            } catch (e: GitHubApi.HttpError) {
                // (a token without Issues, or for another repository: the next one, or the browser)
                Log.w(TAG, "${file.name}: GitHub said ${e.message}")
            } catch (e: IOException) {
                Log.w(TAG, "${file.name}: no way to GitHub ($e)")
                return Outcome.Waiting
            }
        }
        moveTo(file, "opened")
        return Outcome.Opened(report.newIssueLink(repo))
    }

    private fun signature() =
        "Sent by the World of Warcraft app ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) on Android ${Build.VERSION.RELEASE}, " +
            "from the realm's report."

    private fun moveTo(file: File, folder: String) {
        val into = File(file.parentFile, folder).apply { mkdirs() }
        if (!file.renameTo(File(into, file.name))) file.delete()
    }

    private suspend fun toast(context: Context, text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }
}
