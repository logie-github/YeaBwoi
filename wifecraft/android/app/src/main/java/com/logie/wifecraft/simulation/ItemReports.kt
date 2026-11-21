package com.logie.wifecraft.simulation

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Move mode's reports: a held placement ([ItemReport]) as a GitHub issue on the app's own repository (title, a summary for a
 * person, the same as a fenced JSON block for tools/item_reports.py), sent through GitHub's REST API with the build's report
 * token ([ItemReports.send], the connection behind [HttpTransport] so it is tested without a network), kept in a queue on
 * the phone until it goes ([ReportQueue]), and the player's own mappings listed and used at once ([MappedItems]).
 * Nothing personal goes: no device, no account, no path; an NPC's name only when it is one of the game's named characters.
 * Pure (java.net and org.json only).
 */

/** The held NPC as a report gives it: race id, sex, size over a human's, and a name only for the game's own characters. */
data class NpcInfo(val race: Int, val female: Boolean, val scale: Float, val name: String?)

/** One report: a local [id] (its screenshot's name), the build, the item, the player's class, the NPC, the held place, notes. */
data class ItemReport(
    val id: String,
    val appVersion: String,
    val build: Int,
    val target: ItemTarget,
    val cls: ItemClass,
    val npc: NpcInfo,
    val offset: LocalOffset,
    val pose: HeldPose,
    val notes: String = "",
    val flags: Set<ReportFlag> = emptySet(),
    /** A screenshot was kept on the phone (it is never sent: the player may share it). */
    val screenshot: Boolean = false,
) {
    fun title(): String = "Item report: ${cls.label} ${target.displayName}" +
        (if (target.model > 0) " ${target.model}" else "") + " @ ${target.placeName}"

    fun toJson(): JSONObject {
        val t = target
        val item = JSONObject()
            .put("key", t.key).put("object", t.objectName).put("model", t.model).put("name", t.displayName)
            .put("scale", r(t.placeFrame.scale))
            .put("position", JSONArray(listOf(r(t.placeFrame.x), r(t.placeFrame.y), r(t.placeFrame.z))))
            .put("yaw", r(Math.toDegrees(t.placeFrame.yaw.toDouble()).toFloat()))
            .put("detected_class", t.detected?.id ?: JSONObject.NULL)
        t.spec?.let { s ->
            item.put("box", JSONObject().put("x", r(2 * s.halfX)).put("y", r(2 * s.halfY)).put("z", r(s.top)))
            item.put("surfaces", JSONArray(s.regions.filter { it.postures.isNotEmpty() }.map { g ->
                JSONObject().put("name", g.name).put("height", r(g.height))
                    .put("rect", JSONArray(listOf(r(g.minX), r(g.maxX), r(g.minY), r(g.maxY))))
            }))
            item.put("fit", JSONObject().put("kind", s.kind.name).put("surface", r(s.surface))
                .put("slots", JSONArray(s.slots.map { sl ->
                    JSONObject().put("name", sl.name).put("x", r(sl.x)).put("y", r(sl.y)).put("z", r(sl.height))
                        .put("yaw", r(ItemFrame.wrapDeg(Math.toDegrees(sl.yaw.toDouble()).toFloat())))
                        .put("postures", JSONArray(sl.postures.map { it.name }.sorted()))
                })))
        }
        return JSONObject()
            .put("schema", ItemReports.SCHEMA)
            .put("app", JSONObject().put("version", appVersion).put("build", build))
            .put("report", id)
            .put("place", JSONObject().put("id", t.placeId).put("name", t.placeName))
            .put("item", item)
            .put("class", cls.id)
            .put("npc", JSONObject().put("race", npc.race).put("sex", if (npc.female) "female" else "male").put("scale", r(npc.scale))
                .put("name", npc.name ?: JSONObject.NULL))
            .put("offset", JSONObject().put("x", r(offset.x)).put("y", r(offset.y)).put("z", r(offset.z)).put("yaw", r(offset.yawDeg)))
            .put("pose", JSONObject().put("id", pose.id).put("animation", pose.animation).put("activity", pose.activity.name))
            .put("notes", notes.take(ItemMapping.MAX_NOTES))
            .put("flags", JSONArray(flags.map { it.id }.sorted()))
            .put("screenshot", if (screenshot) "kept on the phone" else "none")
    }

    /** The issue's body: what a person reads first, then the JSON block the tool reads. */
    fun body(): String = buildString {
        val t = target
        appendLine("**${cls.label}**: ${t.displayName}${if (t.model > 0) " (model ${t.model})" else ""} at ${t.placeName} (`${t.key}`).")
        appendLine()
        val detected = t.detected
        appendLine("- The furniture's own measure: ${detected?.label ?: "none (open floor)"}${if (detected != null && detected != cls) " (differs)" else ""}")
        appendLine("- Held: ${pose.label} at " + String.format(Locale.ROOT, "x %.2f, y %.2f, z %.2f yd, facing %.0f°", offset.x, offset.y, offset.z, offset.yawDeg) + " from the item's front")
        appendLine("- NPC: race ${npc.race}, ${if (npc.female) "female" else "male"}${npc.name?.let { ", $it" } ?: ""}")
        if (flags.isNotEmpty()) appendLine("- Flags: ${flags.sortedBy { it.ordinal }.joinToString { it.label.lowercase() }}")
        if (notes.isNotBlank()) appendLine("- Notes: ${notes.take(ItemMapping.MAX_NOTES).replace("\n", " ")}")
        appendLine("- Screenshot: ${if (screenshot) "kept on the phone (the player may share it separately)" else "none"}")
        appendLine("- Wifecraft $appVersion (build $build)")
        appendLine()
        appendLine("```json")
        appendLine(toJson().toString(1))
        appendLine("```")
    }

    private fun r(v: Float): Double = Math.round(v * 1000.0) / 1000.0
}

/** What became of one send: gone (the issue's [issue]), to try again later, not possible with this build, or refused as it is. */
enum class SendOutcome { SENT, RETRY, NOT_CONFIGURED, REFUSED }

data class SendResult(val outcome: SendOutcome, val issue: Int = 0, val detail: String = "")

/** An HTTP answer: [code] (0: no connection, [error] says why), its body and the headers the classification reads. */
data class HttpReply(val code: Int, val body: String = "", val retryAfter: String? = null, val remaining: String? = null, val error: String? = null)

/** The one request a report needs (a POST); the app's is [UrlTransport], the tests' a fake. */
fun interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): HttpReply
}

/** A request ready to go: where, its headers (the token among them) and its JSON. */
data class IssueRequest(val url: String, val headers: Map<String, String>, val body: String)

/** [HttpTransport] over HttpURLConnection. */
class UrlTransport(private val connectMs: Int = 15_000, private val readMs: Int = 30_000) : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): HttpReply = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString().take(100_000) } }.getOrNull().orEmpty()
            HttpReply(code, text, c.getHeaderField("Retry-After"), c.getHeaderField("X-RateLimit-Remaining"))
        } finally { c.disconnect() }
    } catch (e: IOException) {
        HttpReply(0, error = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}")
    }
}

object ItemReports {
    const val SCHEMA = 1
    const val LABEL = "item-report"
    const val API = "https://api.github.com"
    private val BLOCK = Regex("```json\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)

    /** The issue for [report] on [repo] ("owner/name") with [token]. */
    fun request(repo: String, token: String, title: String, body: String, appVersion: String, api: String = API, label: String = LABEL): IssueRequest =
        IssueRequest("$api/repos/$repo/issues", mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "Content-Type" to "application/json",
            "User-Agent" to "Wifecraft-Android/$appVersion",
        ), payload(title, body, label))

    /** The issue's JSON: its title, body and [label] (item reports' own, or build mode's: BuildReports.LABEL). */
    fun payload(title: String, body: String, label: String = LABEL): String =
        JSONObject().put("title", title.take(250)).put("body", body.take(60_000)).put("labels", JSONArray(listOf(label))).toString()

    /** What GitHub's answer means for the report: gone, again later (no connection, too many at once), never with this build, or dropped. */
    fun classify(h: HttpReply): SendResult {
        val said = runCatching { JSONObject(h.body).optString("message") }.getOrDefault("").take(120)
        val code = h.code
        return when {
            h.error != null || code == 0 -> SendResult(SendOutcome.RETRY, 0, "no connection to GitHub${h.error?.let { ": $it" } ?: ""}")
            code in 200..299 -> SendResult(SendOutcome.SENT, runCatching { JSONObject(h.body).optInt("number", 0) }.getOrDefault(0))
            code == 429 || (code == 403 && h.retryAfter != null) -> SendResult(SendOutcome.RETRY, 0, "GitHub says too many requests; it will be sent later")
            code == 401 || code == 404 || code == 410 || (code == 403 && h.remaining != "0") ->
                SendResult(SendOutcome.NOT_CONFIGURED, 0, "GitHub answered $code${if (said.isNotEmpty()) ": $said" else ""}")
            code == 400 || code == 413 || code == 422 -> SendResult(SendOutcome.REFUSED, 0, "GitHub answered $code${if (said.isNotEmpty()) ": $said" else ""}")
            else -> SendResult(SendOutcome.RETRY, 0, "GitHub answered $code")
        }
    }

    /** One issue sent: [repo] and [token] empty mean this build has no report token (nothing is tried). */
    fun send(repo: String, token: String, title: String, body: String, appVersion: String, http: HttpTransport, api: String = API,
             label: String = LABEL): SendResult {
        if (repo.isBlank() || token.isBlank()) return SendResult(SendOutcome.NOT_CONFIGURED, 0, "this build has no report token")
        val q = request(repo, token, title, body, appVersion, api, label)
        return classify(http.post(q.url, q.headers, q.body))
    }

    /** The JSON block of an issue's body, or null. */
    fun extractJson(body: String): JSONObject? = BLOCK.find(body)?.groupValues?.get(1)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** What is wrong with a report's JSON ([extractJson]); empty: it is good. */
    fun validate(o: JSONObject): List<String> {
        val bad = ArrayList<String>()
        if (o.optInt("schema", -1) != SCHEMA) bad += "schema is not $SCHEMA"
        if (o.optJSONObject("place")?.optString("id").isNullOrBlank()) bad += "no place id"
        val item = o.optJSONObject("item")
        if (item == null || item.optString("key").isBlank() || item.optString("object").isBlank()) bad += "no item key"
        if (ItemClass.of(o.optString("class")) == null) bad += "unknown class '${o.optString("class")}'"
        val off = o.optJSONObject("offset")
        if (off == null || listOf("x", "y", "z", "yaw").any { k -> !off.optDouble(k, Double.NaN).isFinite() }) bad += "offset missing or not numbers"
        if (HeldPose.of(o.optJSONObject("pose")?.optString("id")) == null) bad += "unknown pose"
        return bad
    }

    /** A good report's JSON as the merge reads it: its item's key and its sample ([issue] its issue's number). Null: not good. */
    fun sample(o: JSONObject, issue: Int): Pair<String, ReportSample>? {
        if (validate(o).isNotEmpty()) return null
        val off = o.getJSONObject("offset")
        val cls = ItemClass.of(o.optString("class")) ?: return null
        val surface = o.optJSONObject("item")?.optJSONArray("surfaces")?.optJSONObject(0)?.optDouble("height", Double.NaN)?.toFloat()?.takeIf { it.isFinite() }
            ?: o.optJSONObject("item")?.optJSONObject("fit")?.optDouble("surface", Double.NaN)?.toFloat()?.takeIf { it.isFinite() && it > 0f }
        val notes = listOf(o.optString("notes"), o.optJSONArray("flags")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }.orEmpty())
            .filter { it.isNotBlank() }.joinToString("; ")
        return o.getJSONObject("item").getString("key") to ReportSample(issue, cls,
            LocalOffset(off.getDouble("x").toFloat(), off.getDouble("y").toFloat(), off.getDouble("z").toFloat(), off.getDouble("yaw").toFloat()),
            HeldPose.of(o.getJSONObject("pose").getString("id"))!!, surface, notes)
    }
}

/**
 * Reports waiting to go, one file each in [dir] (files/reports/queue on the phone): the issue's title and body as made when
 * the player sent it, how often it was tried and why it didn't go. [flush] sends them oldest first, stopping at the first
 * that can't go now (no connection: the rest wait too).
 */
class ReportQueue(private val dir: File) {
    data class Entry(val file: File, val reportId: String, val key: String, val title: String, val body: String, val tries: Int, val lastError: String?)

    fun enqueue(report: ItemReport, now: Long = System.currentTimeMillis()): File =
        enqueue(report.id, report.target.key, report.title(), report.body(), now)

    /** Any issue to send later (build mode's reports use their own queue's folder): its id, key, title and body. */
    fun enqueue(reportId: String, key: String, title: String, body: String, now: Long = System.currentTimeMillis()): File {
        dir.mkdirs()
        val f = File(dir, "item-$now-$reportId.json")
        val o = JSONObject().put("report", reportId).put("key", key).put("title", title).put("body", body)
            .put("tries", 0).put("created", now)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        return f
    }

    fun pending(): List<Entry> = (dir.listFiles { f -> f.name.startsWith("item-") && f.name.endsWith(".json") } ?: emptyArray())
        .sortedBy { it.name }
        .mapNotNull { f ->
            runCatching { JSONObject(f.readText()) }.getOrNull()?.let { o ->
                Entry(f, o.optString("report"), o.optString("key"), o.optString("title"), o.optString("body"), o.optInt("tries"),
                    o.optString("error").takeIf { it.isNotEmpty() })
            } ?: run { f.delete(); null }
        }

    val size: Int get() = pending().size

    /** Drops [reportId]'s waiting report (the player deleted the mapping). */
    fun remove(reportId: String) { for (e in pending()) if (e.reportId == reportId) e.file.delete() }

    /**
     * Sends what waits: each that goes is removed and passed to [onSent]; one GitHub refuses as it is is removed and passed to
     * [onRefused]; one that can't go now stays (its tries and reason kept) and the rest wait behind it. The last outcome.
     */
    fun flush(send: (title: String, body: String) -> SendResult, onSent: (Entry, Int) -> Unit = { _, _ -> },
              onRefused: (Entry, String) -> Unit = { _, _ -> }): SendResult? {
        var last: SendResult? = null
        for (e in pending()) {
            val r = send(e.title, e.body)
            last = r
            when (r.outcome) {
                SendOutcome.SENT -> { e.file.delete(); onSent(e, r.issue) }
                SendOutcome.REFUSED -> { e.file.delete(); onRefused(e, r.detail) }
                SendOutcome.RETRY, SendOutcome.NOT_CONFIGURED -> {
                    runCatching {
                        val o = JSONObject(e.file.readText()).put("tries", e.tries + 1).put("error", r.detail)
                        e.file.writeText(o.toString())
                    }
                    return r
                }
            }
        }
        return last
    }
}

/** One of the player's mappings: the report it made, the item, the class and held place, and what became of the report. */
data class MappedItem(
    val reportId: String, val key: String, val placeName: String, val displayName: String, val cls: ItemClass,
    val offset: LocalOffset, val pose: HeldPose, val surface: Float?, val status: Status, val issue: Int = 0, val at: Long = 0L,
) {
    enum class Status(val label: String) { QUEUED("Waiting to send"), SENT("Reported"), FAILED("Not sent"), LOCAL("On this phone only") }

    val statusLine: String get() = if (status == Status.SENT && issue > 0) "Reported: #$issue" else status.label
}

/**
 * The player's mappings, kept in [file] (files/reports/mapped.json): listed in Options (Mods → Mapped items), deletable, and
 * used at once as overrides ([overrides]: the newest of each item), before any report is merged into the shipped file.
 */
class MappedItems(private val file: File) {
    fun load(): List<MappedItem> {
        val a = runCatching { JSONObject(file.readText()).optJSONArray("items") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val cls = ItemClass.of(o.optString("class")) ?: return@mapNotNull null
            val off = o.optJSONObject("offset") ?: return@mapNotNull null
            val offset = LocalOffset(off.optDouble("x").toFloat(), off.optDouble("y").toFloat(), off.optDouble("z").toFloat(), off.optDouble("yaw").toFloat())
            if (!offset.x.isFinite() || !offset.y.isFinite() || !offset.z.isFinite() || !offset.yawDeg.isFinite()) return@mapNotNull null
            MappedItem(o.optString("report"), o.optString("key"), o.optString("place"), o.optString("name"), cls, offset,
                HeldPose.of(o.optString("pose")) ?: cls.holds.first(),
                o.optDouble("surface", Double.NaN).toFloat().takeIf { it.isFinite() },
                runCatching { MappedItem.Status.valueOf(o.optString("status")) }.getOrDefault(MappedItem.Status.LOCAL),
                o.optInt("issue"), o.optLong("at"))
        }
    }

    fun save(items: List<MappedItem>) {
        file.parentFile?.mkdirs()
        val a = JSONArray(items.map { m ->
            JSONObject().put("report", m.reportId).put("key", m.key).put("place", m.placeName).put("name", m.displayName).put("class", m.cls.id)
                .put("offset", JSONObject().put("x", m.offset.x.toDouble()).put("y", m.offset.y.toDouble()).put("z", m.offset.z.toDouble()).put("yaw", m.offset.yawDeg.toDouble()))
                .put("pose", m.pose.id).put("surface", m.surface?.toDouble() ?: JSONObject.NULL).put("status", m.status.name)
                .put("issue", m.issue).put("at", m.at)
        })
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("schema", 1).put("items", a).toString(1))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    /** [m] added, or put in place of the one with its report id. */
    fun upsert(m: MappedItem): List<MappedItem> = (load().filter { it.reportId != m.reportId } + m).also(::save)

    fun status(reportId: String, status: MappedItem.Status, issue: Int = 0): List<MappedItem> =
        load().map { if (it.reportId == reportId) it.copy(status = status, issue = if (issue > 0) issue else it.issue) else it }.also(::save)

    fun delete(reportId: String): List<MappedItem> = load().filter { it.reportId != reportId }.also(::save)

    companion object {
        /** The newest mapping of each item as an override (its class and that class's held place). */
        fun overrides(items: List<MappedItem>): Map<String, ItemOverride> =
            items.groupBy { it.key }.mapValues { (key, list) ->
                val m = list.maxBy { it.at }
                ItemOverride(key, m.cls, m.surface, mapOf(m.cls to HeldSpot(m.offset, m.pose)))
            }

        /** [r] as a mapping just made (its report waiting to go). */
        fun of(r: ItemReport, status: MappedItem.Status, now: Long = System.currentTimeMillis()): MappedItem =
            MappedItem(r.id, r.target.key, r.target.placeName, r.target.displayName, r.cls, r.offset, r.pose,
                r.target.spec?.regions?.firstOrNull()?.height, status, 0, now)
    }
}
