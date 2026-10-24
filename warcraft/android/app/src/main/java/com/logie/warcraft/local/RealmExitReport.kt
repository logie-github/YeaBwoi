// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.local

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.logie.warcraft.FailureReports
import java.io.File

/**
 * Why Android ended the app while a local realm ran: the realm's Wine is the app's own processes, so
 * the app's end (memory: Android 17's memory limiter, the low-memory killer; a crash; Force stop) is
 * the realm's, with nothing said at the time. A start marks the realm running ([running]), its stop
 * clears it ([stopped]); the app's next start finds the mark and reports Android's own record of the
 * app's last end ([checkPrevious]), with the realm's logs, before a new start moves them aside.
 */
object RealmExitReport {
    private fun mark(context: Context) = File(context.filesDir, "run/local-realm.running")

    /** [name]'s Wine is running, its logs in [runtime]. */
    fun running(context: Context, name: String, runtime: File) {
        runCatching {
            val file = mark(context)
            file.parentFile?.mkdirs()
            file.writeText("$name\n${System.currentTimeMillis()}\n${runtime.path}\n")
        }
    }

    /** The realm's Wine ended as the app ended it (Stop, Force Stop). */
    fun stopped(context: Context) { runCatching { mark(context).delete() } }

    /** Once, as the app starts: the realm that was running when the app last ended, and why it ended. */
    fun checkPrevious(context: Context) {
        val file = mark(context)
        if (!file.isFile) return
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        file.delete()
        val name = lines.getOrNull(0) ?: return
        val since = lines.getOrNull(1)?.toLongOrNull() ?: 0L
        val runtime = lines.getOrNull(2)?.let(::File)
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lastExit(context, since) else null
        val message = "$name was running when Android ended the app" + (reason?.let { ": $it" } ?: " (Android gave no reason).") +
            " The realm's servers are the app's own processes and ended with it."
        val logs = runtime?.let { folder ->
            listOf("host", "world", "authentication", "database", "session").joinToString("\n\n") { log ->
                val tail = runCatching { File(folder, "$log.log").readLines().filter { it.isNotBlank() }.takeLast(30) }.getOrDefault(emptyList())
                if (tail.isEmpty()) "$log.log: nothing written." else "$log.log:\n" + tail.joinToString("\n")
            }
        }
        FailureReports.record(context, "$name server", message, logs)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun lastExit(context: Context, since: Long): String? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull()
            ?.filter { it.timestamp >= since && !it.processName.endsWith(":realm") }
            ?.maxByOrNull { it.timestamp } ?: return null
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory (Android's low-memory killer)"
            ApplicationExitInfo.REASON_OTHER -> "other"
            ApplicationExitInfo.REASON_SIGNALED -> "signal ${exit.status}"
            ApplicationExitInfo.REASON_CRASH -> "a crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "a native crash"
            ApplicationExitInfo.REASON_ANR -> "not responding"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "Force stop (asked for in Android's settings or task manager)"
            ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much CPU or memory used in the background"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a process it depended on died"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission change"
            ApplicationExitInfo.REASON_FREEZER -> "frozen in the background"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "the app updated"
            else -> "reason ${exit.reason}"
        }
        val memory = "${exit.pss / 1024} MB PSS, ${exit.rss / 1024} MB RSS"
        return reason + (exit.description?.let { " ($it)" } ?: "") + ", $memory"
    }
}
