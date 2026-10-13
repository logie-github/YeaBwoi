// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.modules

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * A realm built with the user's modules: what's asked of the build (.github/workflows/
 * build-warcraft-modules.yml), how its run on GitHub goes, and what's kept of it in the app.
 */
object ModuleBuild {
    const val WORKFLOW = "build-warcraft-modules.yml"

    /** At most this many modules in one build (the workflow's limit too). */
    const val MAX_MODULES = 40

    /**
     * A module the user picked: its repository and branch; [commit] is the branch's newest commit
     * when the build was asked for (null until then).
     */
    data class Chosen(
        val fullName: String,
        val branch: String,
        val description: String? = null,
        val avatar: String? = null,
        val commit: String? = null,
    ) {
        val name get() = fullName.substringAfter('/')
    }

    /** A realm built with [modules], for the app's [versionCode], from build [request]. */
    data class Built(val versionCode: Int, val request: String, val modules: List<Chosen>, val builtAt: Long = 0)

    /** What the server package's stamp is when [built] is unpacked (ServerPackage). */
    fun stamp(built: Built) = "${built.versionCode}:modules:${built.request}"

    /** A build asked for and followed on GitHub: what came of it, as last seen. */
    data class Pending(
        val request: String,
        /** The app's release the realm is built from (wow-build-N). */
        val release: String,
        val versionCode: Int,
        val modules: List<Chosen>,
        val startedAt: Long,
        val runId: Long? = null,
        val runUrl: String? = null,
        val status: String? = null,
        val conclusion: String? = null,
        val step: String? = null,
        val checkedAt: Long = 0,
    ) {
        val isDone get() = status == "completed"
        val succeeded get() = isDone && conclusion == "success"
        val failed get() = isDone && conclusion != "success"
    }

    /** A name for a build, so its run and its package can be found. */
    fun newRequest(random: Random = Random.Default): String =
        (1..12).map { "abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)] }.joinToString("")

    fun isRequest(text: String) = Regex("[a-z0-9]{8,32}").matches(text)

    fun assetName(request: String) = "server-$request.tar.xz"

    /** The app's release for [versionCode], as CI tags it ([prefix]-N). */
    fun release(prefix: String, versionCode: Int) = "$prefix-$versionCode"

    /** A branch name that's safe in a URL's path and as git's ref. */
    fun isBranch(text: String): Boolean =
        text.isNotEmpty() && text.length <= 200 && Regex("[A-Za-z0-9._/-]+").matches(text) &&
            ".." !in text && !text.startsWith("/") && !text.endsWith("/") && !text.endsWith(".lock")

    /** The modules the build adds: owner/name@commit a line, without the realm's own ([base]). */
    fun modulesInput(chosen: List<Chosen>, base: Set<String>): String =
        chosen.filter { it.name !in base && ModuleCatalog.isFullName(it.fullName) && it.commit != null && ModuleCatalog.isCommit(it.commit) }
            .distinctBy { it.name.lowercase() }
            .joinToString("\n") { "${it.fullName}@${it.commit}" }

    /** The workflow's inputs, as GitHub's dispatch wants them. */
    fun dispatchBody(branch: String, release: String, request: String, modules: String): String =
        JSONObject()
            .put("ref", branch)
            .put("inputs", JSONObject().put("release", release).put("request", request).put("modules", modules))
            .toString()

    // How the run goes ------------------------------------------------------------------------------

    data class Run(val id: Long, val status: String, val conclusion: String?, val url: String) {
        val isDone get() = status == "completed"
        val succeeded get() = isDone && conclusion == "success"
    }

    /** The run of build [request] among the workflow's runs (its title names the request). */
    fun findRun(runsJson: String, request: String): Run? {
        val runs = JSONObject(runsJson).optJSONArray("workflow_runs") ?: return null
        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            val title = run.optString("display_title")
            if (title.split(' ').getOrNull(1) == request) return run(run)
        }
        return null
    }

    fun parseRun(json: String): Run = run(JSONObject(json))

    private fun run(run: JSONObject) = Run(
        id = run.optLong("id"),
        status = run.optString("status"),
        conclusion = run.optString("conclusion").takeIf { it.isNotEmpty() && it != "null" },
        url = run.optString("html_url"),
    )

    /** What the run is on: the step under way (or the last one done) of the job under way. */
    fun currentStep(jobsJson: String): String? {
        val jobs = JSONObject(jobsJson).optJSONArray("jobs") ?: return null
        var last: String? = null
        for (i in 0 until jobs.length()) {
            val job = jobs.getJSONObject(i)
            val steps = job.optJSONArray("steps") ?: continue
            for (j in 0 until steps.length()) {
                val step = steps.getJSONObject(j)
                val name = step.optString("name")
                if (name.startsWith("Set up") || name.startsWith("Post ") || name.startsWith("Complete") || name.startsWith("Run actions/")) continue
                when (step.optString("status")) {
                    "in_progress" -> return name
                    "completed" -> {
                        // A failed step is what to tell.
                        if (step.optString("conclusion") == "failure") return name
                        last = name
                    }
                }
            }
        }
        return last
    }

    /** The run, as last seen, into [pending]. */
    fun seen(pending: Pending, run: Run, step: String?, now: Long): Pending =
        pending.copy(runId = run.id, runUrl = run.url, status = run.status, conclusion = run.conclusion, step = step ?: pending.step, checkedAt = now)

    // Kept in the app -------------------------------------------------------------------------------

    fun chosenToJson(list: List<Chosen>): JSONArray = JSONArray().apply {
        for (module in list) {
            put(
                JSONObject().put("fullName", module.fullName).put("branch", module.branch)
                    .put("description", module.description ?: JSONObject.NULL).put("avatar", module.avatar ?: JSONObject.NULL)
                    .put("commit", module.commit ?: JSONObject.NULL)
            )
        }
    }

    fun chosenFromJson(array: JSONArray?): List<Chosen> = (0 until (array?.length() ?: 0)).mapNotNull { i ->
        val o = array!!.optJSONObject(i) ?: return@mapNotNull null
        val fullName = o.optString("fullName")
        val branch = o.optString("branch")
        val commit = o.text("commit")
        if (!ModuleCatalog.isFullName(fullName) || !isBranch(branch) || (commit != null && !ModuleCatalog.isCommit(commit))) {
            return@mapNotNull null
        }
        Chosen(fullName, branch, o.text("description"), o.text("avatar"), commit)
    }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    fun builtToJson(built: Built): String =
        JSONObject().put("versionCode", built.versionCode).put("request", built.request)
            .put("modules", chosenToJson(built.modules)).put("builtAt", built.builtAt).toString()

    fun builtFromJson(text: String?): Built? = runCatching {
        val o = JSONObject(text ?: return null)
        val request = o.getString("request").takeIf { isRequest(it) } ?: return null
        Built(o.getInt("versionCode"), request, chosenFromJson(o.optJSONArray("modules")), o.optLong("builtAt"))
    }.getOrNull()

    fun pendingToJson(pending: Pending): String =
        JSONObject().put("request", pending.request).put("release", pending.release).put("versionCode", pending.versionCode)
            .put("modules", chosenToJson(pending.modules)).put("startedAt", pending.startedAt)
            .put("runId", pending.runId ?: JSONObject.NULL).put("runUrl", pending.runUrl ?: JSONObject.NULL)
            .put("status", pending.status ?: JSONObject.NULL).put("conclusion", pending.conclusion ?: JSONObject.NULL)
            .put("step", pending.step ?: JSONObject.NULL).put("checkedAt", pending.checkedAt)
            .toString()

    fun pendingFromJson(text: String?): Pending? = runCatching {
        val o = JSONObject(text ?: return null)
        val request = o.getString("request").takeIf { isRequest(it) } ?: return null
        Pending(
            request = request,
            release = o.getString("release"),
            versionCode = o.getInt("versionCode"),
            modules = chosenFromJson(o.optJSONArray("modules")),
            startedAt = o.optLong("startedAt"),
            runId = if (o.isNull("runId")) null else o.optLong("runId").takeIf { it > 0 },
            runUrl = o.text("runUrl"),
            status = o.text("status"),
            conclusion = o.text("conclusion"),
            step = o.text("step"),
            checkedAt = o.optLong("checkedAt"),
        )
    }.getOrNull()
}
