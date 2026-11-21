package com.logie.wifecraft.data

/*
 * Options' "Download everything" as pure logic (JVM-tested: DownloadPlanTest): which packs there are, what each is
 * estimated to weigh, each pack's state on the phone, the total, the free-space check, the download's states, the queue
 * of files that failed and the state kept across restarts. The work itself (the foreground service, the fetching) is
 * DownloadService.kt's.
 */

/**
 * The packs "Download everything" covers, in the order they are fetched. [optional] packs are the big ones the player
 * can leave out (Options' check list); [onByDefault] whether they are ticked at first.
 */
enum class PackKey(val id: String, val label: String, val detail: String, val optional: Boolean = false, val onByDefault: Boolean = true) {
    BASICS("basics", "Game basics", "The interface, the game's tables, Dalaran, every character creator option, the title's music"),
    PLACES("places", "Places", "All eleven locations and every race's backdrop"),
    ART("art", "Menu and Transmog art", "The Transmog window's, the creator's and the menus' art"),
    SOUNDS("sounds", "Music, ambience and voices", "Every place's music and ambience, time of day, fire, rain, cloth, the emotes' voices"),
    ANIMATIONS("animations", "Character animations", "Every animation of every character model"),
    NAMED("named", "Named characters", "Every look the named characters are drawn as or cut from"),
    TRANSMOG("transmog", "Transmog items (large)", "Every item's models and textures for every race and sex, and the icons", optional = true),
    CREATURES("creatures", "Library creatures (large)", "The placement library's creature models and the game's named creatures", optional = true),
    PROPS("props", "Library props (very large)", "Every prop of the placement library", optional = true),
    BUILDINGS("buildings", "Library buildings (huge)", "Every building of the placement library, with its rooms", optional = true);

    companion object {
        fun of(id: String): PackKey? = entries.firstOrNull { it.id == id }
        /** What "Download everything" takes before the player changes the ticks. */
        val DEFAULT: Set<PackKey> = entries.filter { !it.optional || it.onByDefault }.toSet()
        /** [chosen] with every pack that can't be left out, in fetch order. */
        fun selection(chosen: Set<PackKey>): List<PackKey> = entries.filter { !it.optional || it in chosen }
    }
}

/** A pack's weight: [exact] once counted on the phone, else assets/files.json's estimate (tools/pack_sizes.py). */
data class PackEstimate(val files: Int, val bytes: Long, val exact: Boolean = false)

/** What one pack's last run left on the phone, at [version] (DownloadPlan.version: the app's build and the packs' shape). */
data class PackRecord(val pack: PackKey, val version: String, val planned: Int, val have: Int, val bytes: Long, val verified: Boolean = false) {
    val complete: Boolean get() = planned > 0 && have >= planned
}

enum class PackStatus { NOT_DOWNLOADED, PARTIAL, COMPLETE, NEEDS_UPDATE }

/** A pack's row: its [status] and, when partial, how far ([percent], 1 to 99). */
data class PackView(val status: PackStatus, val percent: Int = 0) {
    val label: String get() = when (status) {
        PackStatus.NOT_DOWNLOADED -> "Not downloaded"
        PackStatus.PARTIAL -> "Partial $percent%"
        PackStatus.COMPLETE -> "Complete"
        PackStatus.NEEDS_UPDATE -> "Needs update"
    }
}

object DownloadPlan {
    /** Raised whenever a pack's content changes shape (a pack gains a kind of file): every record then "Needs update". */
    const val PLAN_VERSION = 1

    fun version(build: String): String = "$build#$PLAN_VERSION"

    /** A pack's state from its last [record] (null: never run) against the [version] in force. */
    fun view(record: PackRecord?, version: String): PackView = when {
        record == null || (record.have <= 0 && record.planned <= 0) -> PackView(PackStatus.NOT_DOWNLOADED)
        record.version != version -> PackView(PackStatus.NEEDS_UPDATE)
        record.complete -> PackView(PackStatus.COMPLETE)
        record.have <= 0 -> PackView(PackStatus.NOT_DOWNLOADED)
        else -> PackView(PackStatus.PARTIAL, (record.have.toLong() * 100 / record.planned).toInt().coerceIn(1, 99))
    }

    /**
     * What a pack weighs for the totals: its record once complete at this version (exact), else the estimate. Bytes still
     * to come: the estimate less what the record says is here (never below zero).
     */
    fun weight(estimate: PackEstimate?, record: PackRecord?, version: String): PackEstimate {
        val e = estimate ?: PackEstimate(0, 0)
        return if (record != null && record.version == version && record.complete) PackEstimate(record.have, record.bytes, true) else e
    }

    fun remaining(estimate: PackEstimate?, record: PackRecord?, version: String): Long {
        val w = weight(estimate, record, version)
        if (w.exact) return 0
        val here = if (record != null && record.version == version) record.bytes else 0L
        return (w.bytes - here).coerceAtLeast(0)
    }

    /** The whole of [packs] (files, bytes), exact only when every pack is. */
    fun total(packs: Collection<PackKey>, estimates: Map<PackKey, PackEstimate>, records: Map<PackKey, PackRecord>, version: String): PackEstimate {
        var files = 0; var bytes = 0L; var exact = true
        for (p in packs) {
            val w = weight(estimates[p], records[p], version)
            files += w.files; bytes += w.bytes; exact = exact && w.exact
        }
        return PackEstimate(files, bytes, exact)
    }

    /** files.json's "estimates" ({id: (files, bytes)}) as packs; unknown ids are dropped. */
    fun estimates(raw: Map<String, Pair<Int, Long>>): Map<PackKey, PackEstimate> =
        raw.mapNotNull { (id, v) -> PackKey.of(id)?.let { it to PackEstimate(v.first, v.second) } }.toMap()
}

/** Sizes as the Options page says them. */
object ByteSize {
    /** From this much, the total says Wi-Fi is recommended. */
    const val WIFI_ADVICE_BYTES = 200_000_000L

    fun format(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b >= 10_000_000_000L -> "${(b + 500_000_000L) / 1_000_000_000L} GB"
            b >= 1_000_000_000L -> "%.1f GB".format(java.util.Locale.ROOT, b / 1e9)
            b >= 10_000_000L -> "${(b + 500_000L) / 1_000_000L} MB"
            b >= 1_000_000L -> "%.1f MB".format(java.util.Locale.ROOT, b / 1e6)
            b >= 1_000L -> "${(b + 500L) / 1_000L} KB"
            else -> "$b bytes"
        }
    }

    /** "about 18 GB: Wi-Fi recommended" (no "about" when [exact], no advice when small). */
    fun totalLine(bytes: Long, exact: Boolean): String =
        (if (exact) "" else "about ") + format(bytes) + if (bytes >= WIFI_ADVICE_BYTES) ": Wi-Fi recommended" else ""

    /** "12,345": a count as the page shows it. */
    fun count(n: Int): String = "%,d".format(java.util.Locale.ROOT, n)
}

/** Whether the packs fit in the phone's free space (File.usableSpace), and if not which of them do. */
object SpaceCheck {
    /** Always left free for the phone itself. */
    const val RESERVE_BYTES = 1_000_000_000L

    sealed interface Result
    data object Fits : Result
    /**
     * [needed] bytes (with the reserve) against [free]; [fits]: the packs that can be fetched (every pack that can't be
     * left out first, then the optional ones smallest first while they fit), empty when not even the required ones do.
     */
    data class TooBig(val needed: Long, val free: Long, val fits: Set<PackKey>) : Result {
        /** The optional packs [fits] leaves out. */
        fun leftOut(chosen: Collection<PackKey>): List<PackKey> = chosen.filter { it.optional && it !in fits }
    }

    /** [needs]: each chosen pack's bytes still to come. */
    fun check(free: Long, needs: Map<PackKey, Long>, reserve: Long = RESERVE_BYTES): Result {
        val total = needs.values.sumOf { it.coerceAtLeast(0) }
        if (total + reserve <= free) return Fits
        var used = reserve
        val fits = LinkedHashSet<PackKey>()
        val required = needs.filterKeys { !it.optional }
        val requiredBytes = required.values.sumOf { it.coerceAtLeast(0) }
        if (requiredBytes + used > free) return TooBig(total + reserve, free, emptySet())
        used += requiredBytes
        fits += required.keys
        for ((pack, bytes) in needs.filterKeys { it.optional }.entries.sortedBy { it.value }) {
            if (used + bytes.coerceAtLeast(0) <= free) { used += bytes.coerceAtLeast(0); fits += pack }
        }
        return TooBig(total + reserve, free, fits)
    }

    /** The refusal in plain words. */
    fun message(r: TooBig, chosen: Collection<PackKey>): String {
        val head = "Not enough room: this needs ${ByteSize.format(r.needed)} (with ${ByteSize.format(RESERVE_BYTES)} kept free), " +
            "${ByteSize.format(r.free)} is free."
        if (r.fits.isEmpty()) return "$head Free some space first."
        val out = r.leftOut(chosen)
        return if (out.isEmpty()) head else "$head Without ${out.joinToString(", ") { it.label }} it fits."
    }
}

/** Where the download is. */
enum class Phase { IDLE, DOWNLOADING, WAITING_FOR_WIFI, PAUSED, VERIFYING, FAILED, COMPLETE }

/** The Downloads page's buttons, by [actions]. */
enum class PageAction(val label: String) {
    DOWNLOAD_EVERYTHING("Download everything"), PAUSE("Pause"), RESUME("Resume"), CANCEL("Cancel"),
    RETRY_FAILED("Retry failed"), VERIFY("Verify"),
}

object DownloadPage {
    /** The buttons for [phase] (the first is the primary one): [failed] files waiting, [anyHere] some pack downloaded. */
    fun actions(phase: Phase, failed: Int, anyHere: Boolean): List<PageAction> = when (phase) {
        Phase.DOWNLOADING, Phase.WAITING_FOR_WIFI, Phase.VERIFYING -> listOf(PageAction.PAUSE, PageAction.CANCEL)
        Phase.PAUSED -> listOf(PageAction.RESUME, PageAction.CANCEL)
        Phase.FAILED -> listOfNotNull(if (failed > 0) PageAction.RETRY_FAILED else PageAction.DOWNLOAD_EVERYTHING, PageAction.VERIFY.takeIf { anyHere })
        Phase.COMPLETE -> listOf(PageAction.VERIFY, PageAction.DOWNLOAD_EVERYTHING)
        Phase.IDLE -> listOfNotNull(PageAction.DOWNLOAD_EVERYTHING, PageAction.VERIFY.takeIf { anyHere })
    }

    /** The line over the bar. */
    fun title(phase: Phase, current: PackKey?, number: Int, count: Int): String {
        val where = current?.let { " ${it.label}" + if (count > 1) " ($number of $count)" else "" } ?: ""
        return when (phase) {
            Phase.IDLE -> "Nothing downloading"
            Phase.DOWNLOADING -> "Downloading:$where"
            Phase.WAITING_FOR_WIFI -> "Waiting for Wi-Fi:$where"
            Phase.PAUSED -> "Paused"
            Phase.VERIFYING -> "Checking every file:$where"
            Phase.FAILED -> "Some files are missing"
            Phase.COMPLETE -> "Everything is here"
        }
    }
}

/** What happens to it. */
enum class DownloadEvent {
    START, PAUSE, RESUME, CANCEL, NO_WIFI, WIFI_BACK,
    /** Every pack ran; nothing is left failed (verification follows) or some files are ([FINISHED_WITH_FAILURES]). */
    FINISHED, FINISHED_WITH_FAILURES,
    VERIFY, VERIFIED, VERIFIED_WITH_FAILURES,
    /** Something stopped the whole run (no room, the tables couldn't be had). */
    ERROR,
}

/** The download's state machine: [next] is total (an event that doesn't apply leaves the phase as it is). */
object DownloadMachine {
    private val ACTIVE = setOf(Phase.DOWNLOADING, Phase.WAITING_FOR_WIFI, Phase.VERIFYING)

    fun isActive(p: Phase): Boolean = p in ACTIVE

    /** A run that was going when the app stopped is taken up again when it opens. */
    fun resumesAfterRestart(p: Phase): Boolean = p in ACTIVE

    fun next(p: Phase, e: DownloadEvent): Phase = when (e) {
        DownloadEvent.START -> if (p in ACTIVE) p else Phase.DOWNLOADING
        DownloadEvent.PAUSE -> if (p in ACTIVE) Phase.PAUSED else p
        DownloadEvent.RESUME -> if (p == Phase.PAUSED || p == Phase.FAILED) Phase.DOWNLOADING else p
        DownloadEvent.CANCEL -> Phase.IDLE
        DownloadEvent.NO_WIFI -> if (p == Phase.DOWNLOADING) Phase.WAITING_FOR_WIFI else p
        DownloadEvent.WIFI_BACK -> if (p == Phase.WAITING_FOR_WIFI) Phase.DOWNLOADING else p
        DownloadEvent.FINISHED -> if (p == Phase.DOWNLOADING) Phase.VERIFYING else p
        DownloadEvent.FINISHED_WITH_FAILURES -> if (p == Phase.DOWNLOADING) Phase.FAILED else p
        DownloadEvent.VERIFY -> if (p in ACTIVE) p else Phase.VERIFYING
        DownloadEvent.VERIFIED -> if (p == Phase.VERIFYING) Phase.COMPLETE else p
        DownloadEvent.VERIFIED_WITH_FAILURES -> if (p == Phase.VERIFYING) Phase.FAILED else p
        DownloadEvent.ERROR -> if (p in ACTIVE) Phase.FAILED else p
    }
}

/**
 * The files that couldn't be fetched, each tried again by itself after a wait that doubles ([backoffMs]: 30 s, 1 min,
 * 2 min...) up to [MAX_AUTO] times; after that, or when wago.tools has no such file ([permanent]), it waits for the
 * player's "Retry failed" ([retryAll]). Thread-safe; the clock (ms) injected for tests.
 */
class FailedQueue(private val clock: () -> Long = System::currentTimeMillis) {
    data class Entry(val id: Int, val pack: PackKey?, val reason: String, val attempts: Int, val nextAt: Long, val permanent: Boolean = false)

    private val entries = LinkedHashMap<Int, Entry>()

    @Synchronized fun failed(id: Int, pack: PackKey?, reason: String, permanent: Boolean = false) {
        val attempts = (entries[id]?.attempts ?: 0) + 1
        entries[id] = Entry(id, pack ?: entries[id]?.pack, reason.take(MAX_REASON), attempts, clock() + backoffMs(attempts), permanent)
    }

    @Synchronized fun succeeded(id: Int) { entries.remove(id) }

    /** The ids to try again now. */
    @Synchronized fun due(): List<Int> {
        val now = clock()
        return entries.values.filter { !it.permanent && it.attempts <= MAX_AUTO && it.nextAt <= now }.map { it.id }
    }

    /** How long until the next automatic try (null: none left: every one waits for "Retry failed"). */
    @Synchronized fun nextDueIn(): Long? =
        entries.values.filter { !it.permanent && it.attempts <= MAX_AUTO }.minOfOrNull { (it.nextAt - clock()).coerceAtLeast(0) }

    /** "Retry failed": every entry due at once, its count of tries started again. */
    @Synchronized fun retryAll() {
        for ((id, e) in entries.toList()) entries[id] = e.copy(attempts = 0, nextAt = 0, permanent = false)
    }

    @Synchronized fun clear() = entries.clear()
    @Synchronized fun list(): List<Entry> = entries.values.toList()
    val size: Int @Synchronized get() = entries.size
    fun isEmpty(): Boolean = size == 0

    @Synchronized fun restore(list: List<Entry>) { entries.clear(); for (e in list) entries[e.id] = e }

    companion object {
        const val BASE_MS = 30_000L
        const val MAX_MS = 30 * 60_000L
        const val MAX_AUTO = 3
        const val MAX_REASON = 160

        fun backoffMs(attempt: Int): Long = (BASE_MS shl (attempt - 1).coerceIn(0, 16)).coerceAtMost(MAX_MS)
    }
}

/**
 * What survives a restart (files/cache/download-state.txt): the phase, the ticks, the mobile-data override, the
 * pre-build switch, the pack being fetched, each pack's record, the failed files and the last message. Plain lines,
 * unknown ones skipped: a damaged file gives what could be read, never an error.
 */
data class SavedState(
    val phase: Phase = Phase.IDLE,
    val chosen: Set<PackKey> = PackKey.DEFAULT,
    val mobileData: Boolean = false,
    val prebuild: Boolean = false,
    val current: PackKey? = null,
    val records: Map<PackKey, PackRecord> = emptyMap(),
    val failed: List<FailedQueue.Entry> = emptyList(),
    val message: String? = null,
    /** Android stopped the service at its time limit: carried on when the app is opened. */
    val stoppedBySystem: Boolean = false,
    /** A run of these packs alone (a pack's own Download button); null: everything ticked. */
    val only: Set<PackKey>? = null,
) {
    /** The packs this run fetches, in order. */
    val packs: List<PackKey> get() = only?.let { o -> PackKey.entries.filter { it in o } } ?: PackKey.selection(chosen)

    fun encode(): String = buildString {
        append(HEADER).append('\n')
        append("phase ").append(phase.name).append('\n')
        append("chosen ").append(chosen.joinToString(",") { it.id }).append('\n')
        append("mobile ").append(if (mobileData) 1 else 0).append('\n')
        append("prebuild ").append(if (prebuild) 1 else 0).append('\n')
        append("system ").append(if (stoppedBySystem) 1 else 0).append('\n')
        current?.let { append("current ").append(it.id).append('\n') }
        only?.let { o -> append("only ").append(o.joinToString(",") { it.id }).append('\n') }
        for (r in records.values) {
            append("record ").append(r.pack.id).append(' ').append(r.version.replace(' ', '_')).append(' ')
                .append(r.planned).append(' ').append(r.have).append(' ').append(r.bytes).append(' ').append(if (r.verified) 1 else 0).append('\n')
        }
        for (f in failed) {
            append("failed ").append(f.id).append(' ').append(f.pack?.id ?: "-").append(' ').append(f.attempts).append(' ')
                .append(f.nextAt).append(' ').append(if (f.permanent) 1 else 0).append(' ').append(oneLine(f.reason)).append('\n')
        }
        message?.let { append("message ").append(oneLine(it)).append('\n') }
    }

    companion object {
        const val HEADER = "wifecraft-downloads 1"

        private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ')

        fun decode(text: String?): SavedState {
            if (text == null) return SavedState()
            val lines = text.lineSequence().toList()
            if (lines.firstOrNull()?.trim() != HEADER) return SavedState()
            var s = SavedState()
            val records = LinkedHashMap<PackKey, PackRecord>()
            val failed = ArrayList<FailedQueue.Entry>()
            for (line in lines.drop(1)) {
                val key = line.substringBefore(' ')
                val rest = line.substringAfter(' ', "")
                runCatching {
                    when (key) {
                        "phase" -> s = s.copy(phase = Phase.valueOf(rest.trim()))
                        "chosen" -> s = s.copy(chosen = rest.split(',').mapNotNull { PackKey.of(it.trim()) }.toSet())
                        "mobile" -> s = s.copy(mobileData = rest.trim() == "1")
                        "prebuild" -> s = s.copy(prebuild = rest.trim() == "1")
                        "system" -> s = s.copy(stoppedBySystem = rest.trim() == "1")
                        "current" -> s = s.copy(current = PackKey.of(rest.trim()))
                        "only" -> s = s.copy(only = rest.split(',').mapNotNull { PackKey.of(it.trim()) }.toSet().takeIf { it.isNotEmpty() })
                        "message" -> s = s.copy(message = rest)
                        "record" -> {
                            val f = rest.split(' ')
                            val pack = PackKey.of(f[0])
                            if (pack != null) records[pack] = PackRecord(pack, f[1], f[2].toInt(), f[3].toInt(), f[4].toLong(), f.getOrNull(5) == "1")
                        }
                        "failed" -> {
                            val f = rest.split(' ', limit = 6)
                            failed += FailedQueue.Entry(f[0].toInt(), PackKey.of(f[1]), f.getOrElse(5) { "" }, f[2].toInt(), f[3].toLong(), f[4] == "1")
                        }
                    }
                }
            }
            return s.copy(records = records, failed = failed)
        }
    }
}

/**
 * A pack's files as last fetched (files/cache/packs/<id>.ids: "id length" a line), for the "Verify" step: a file is
 * damaged when it is missing or its length isn't the one it had when it came ([expected] 0: any length but none).
 */
object PackFiles {
    fun encode(files: Map<Int, Long>): String = buildString {
        for ((id, len) in files) append(id).append(' ').append(len).append('\n')
    }

    fun decode(text: String): LinkedHashMap<Int, Long> {
        val out = LinkedHashMap<Int, Long>()
        for (line in text.lineSequence()) {
            val sp = line.indexOf(' ')
            val id = (if (sp < 0) line else line.substring(0, sp)).trim().toIntOrNull() ?: continue
            out[id] = if (sp < 0) 0L else line.substring(sp + 1).trim().toLongOrNull() ?: 0L
        }
        return out
    }

    /** Whether a file of [actual] length (-1 or 0: not there) is whole against [expected]. */
    fun whole(actual: Long, expected: Long): Boolean = actual > 0 && (expected <= 0 || actual == expected)

    /** The ids of [files] that are damaged by [length] (a file's length on the phone, 0 when it isn't there). */
    fun damaged(files: Map<Int, Long>, length: (Int) -> Long): List<Int> = files.filter { (id, exp) -> !whole(length(id), exp) }.keys.toList()
}
