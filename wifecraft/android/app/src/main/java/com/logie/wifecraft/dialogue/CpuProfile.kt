package com.logie.wifecraft.dialogue

import java.io.File
import java.util.Locale

/**
 * What the phone's CPU can do and how its cores are laid out, read from /proc/cpuinfo and sysfs, and the two decisions made from
 * it: which of the CPU kernels the model runs on ([CpuVariants]) and how many threads on which cores ([ThreadPlan]). Pure
 * functions over text (JVM-tested); only [read] touches the files, and every failure to read gives a plain, safe answer.
 *
 * Why it matters: the model's speed is the CPU's. Prompt reading is compute-bound and about twice as fast with the i8mm
 * instructions (every Snapdragon 8 Gen 1 and later has them); writing is bound by memory speed, so more threads than the big
 * cores can feed only add waiting, and any thread left on a little core holds the others up at each layer.
 */
data class CpuProfile(
    /** The CPU's feature flags as the kernel names them ("asimddp", "i8mm", "sve", ...), lower case. */
    val features: Set<String> = emptySet(),
    /** Each core's highest frequency in kHz, by core number (0: not readable). */
    val maxFreqKHz: List<Long> = emptyList(),
    val cores: Int = 0,
) {
    val dotprod: Boolean get() = "asimddp" in features || "dotprod" in features
    val fp16: Boolean get() = "asimdhp" in features || "fphp" in features
    val i8mm: Boolean get() = "i8mm" in features
    val bf16: Boolean get() = "bf16" in features
    val sve: Boolean get() = "sve" in features
    val sve2: Boolean get() = "sve2" in features
    val sme: Boolean get() = "sme" in features

    /** The cores worth running the model on: those within 70 % of the fastest one's top frequency (the prime and performance cores). */
    val performanceCores: List<Int> get() {
        val n = maxOf(cores, maxFreqKHz.size)
        if (n == 0) return emptyList()
        val top = maxFreqKHz.maxOrNull() ?: 0L
        if (top <= 0L) {
            // frequencies unreadable (some vendors): the upper half of the cores are the big ones on every phone that has two kinds
            return if (n >= 6) (n / 2 until n).toList() else (0 until n).toList()
        }
        val picked = maxFreqKHz.indices.filter { maxFreqKHz[it] >= top * 7 / 10 }
        return picked.ifEmpty { (0 until n).toList() }
    }

    /** One line for the log and the copied timings: the kernel set and the core layout. */
    fun describe(): String = String.format(Locale.ROOT, "cpu: %d cores (%s), performance cores %s; features: %s; kernels expected: %s",
        cores, freqSummary(), performanceCores.joinToString(","),
        listOf("dotprod" to dotprod, "fp16" to fp16, "i8mm" to i8mm, "bf16" to bf16, "sve" to sve, "sve2" to sve2, "sme" to sme).joinToString(" ") { (n, on) -> if (on) n else "no-$n" },
        CpuVariants.pick(this))

    private fun freqSummary(): String =
        if (maxFreqKHz.none { it > 0 }) "frequencies unknown"
        else maxFreqKHz.groupBy { it }.toSortedMap(compareByDescending { it }).entries.joinToString(" + ") { (f, l) -> "${l.size} x ${"%.2f".format(Locale.ROOT, f / 1_000_000.0)} GHz" }

    companion object {
        /** The "Features" line of /proc/cpuinfo (the first one: every core of a phone says the same, or the big ones say more and the union is taken). */
        fun parseFeatures(cpuinfo: String): Set<String> {
            val out = HashSet<String>()
            for (line in cpuinfo.lineSequence()) {
                val k = line.substringBefore(':', "").trim().lowercase()
                if (k == "features" || k == "flags") line.substringAfter(':').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { out += it.lowercase() }
            }
            return out
        }

        /** How many cores /proc/cpuinfo lists ("processor : N" lines). */
        fun parseCoreCount(cpuinfo: String): Int = cpuinfo.lineSequence().count { it.substringBefore(':', "").trim().lowercase() == "processor" }

        /** The file's text read from sysfs as a number, or 0. */
        fun parseKHz(text: String?): Long = text?.trim()?.toLongOrNull() ?: 0L

        /** Reads the phone's CPU (never throws: what it cannot read is empty or 0). */
        fun read(cpuinfoPath: String = "/proc/cpuinfo", sysfsRoot: String = "/sys/devices/system/cpu"): CpuProfile {
            val info = runCatching { File(cpuinfoPath).readText() }.getOrDefault("")
            var cores = parseCoreCount(info)
            if (cores == 0) cores = Runtime.getRuntime().availableProcessors()
            val freqs = (0 until cores).map { i ->
                val dir = "$sysfsRoot/cpu$i/cpufreq"
                maxOf(parseKHz(runCatching { File("$dir/cpuinfo_max_freq").readText() }.getOrNull()), parseKHz(runCatching { File("$dir/scaling_max_freq").readText() }.getOrNull()))
            }
            return CpuProfile(parseFeatures(info), freqs, cores)
        }
    }
}

/**
 * Which build of the CPU kernels ggml uses on a phone with [CpuProfile]'s features. ggml picks it itself when the model loads (it
 * loads every libggml-cpu-android_*.so beside the app's library, asks each how well it fits this CPU and keeps the best: a build
 * that needs an instruction the CPU lacks scores 0 and is never run, so no phone meets an illegal instruction); this is the same
 * table, for the Options page and the copied timings to say which one should be running and for the tests to pin. The names are
 * ggml's (GGML_CPU_ALL_VARIANTS, Android).
 */
object CpuVariants {
    private class V(val name: String, val needs: Set<String>)
    // best first: the first whose every need is present
    private val TABLE = listOf(
        V("android_armv9.2_2", setOf("dotprod", "i8mm", "fp16", "sve", "sve2", "sme")),
        V("android_armv9.2_1", setOf("dotprod", "i8mm", "fp16", "sve", "sme")),
        V("android_armv9.0_1", setOf("dotprod", "i8mm", "fp16", "sve2")),
        V("android_armv8.6_1", setOf("dotprod", "i8mm", "fp16")),
        V("android_armv8.2_2", setOf("dotprod", "fp16")),
        V("android_armv8.2_1", setOf("dotprod")),
        V("android_armv8.0_1", emptySet()),
    )

    fun pick(p: CpuProfile): String {
        val have = buildSet {
            if (p.dotprod) add("dotprod"); if (p.fp16) add("fp16"); if (p.i8mm) add("i8mm"); if (p.sve) add("sve"); if (p.sve2) add("sve2"); if (p.sme) add("sme")
        }
        return TABLE.first { have.containsAll(it.needs) }.name
    }

    /** Whether the picked kernels have the i8mm instructions (the roughly doubled prompt reading). */
    fun hasI8mm(p: CpuProfile): Boolean = pick(p).let { it.contains("armv8.6") || it.contains("armv9") }

    /** Every variant's name, best first (the build's list; a test checks the table against it). */
    val names: List<String> get() = TABLE.map { it.name }
}

/** How many threads read the prompt and write the answer, and which cores they may use. */
data class ThreadPlan(val decode: Int, val prefill: Int, val coreMask: Long, val note: String) {
    companion object {
        /** The most threads Speed offers by hand, and the least. */
        const val MIN_MANUAL = 3
        const val MAX_MANUAL = 6

        /**
         * [manual] 0 is Auto. Auto: writing uses as many threads as there are performance cores up to 4 (memory-bound: more threads
         * on a phone's memory bus add waiting, not speed), reading the prompt up to 6 (compute-bound, and every thread must be a
         * big core: a thread on a little core makes the others wait for it at each layer). A hand-picked number is used for both.
         * The threads are kept on the performance cores (the mask), never more of them than there are such cores.
         */
        fun choose(cpu: CpuProfile, manual: Int = 0): ThreadPlan {
            val perf = cpu.performanceCores
            val n = maxOf(1, perf.size.takeIf { it > 0 } ?: cpu.cores)
            val mask = perf.fold(0L) { m, c -> if (c in 0..62) m or (1L shl c) else m }
            if (manual > 0) {
                val t = manual.coerceIn(1, 8)
                return ThreadPlan(t, t, mask, "$t threads (your choice) on ${perf.size} performance cores")
            }
            val decode = n.coerceIn(2, 4)
            val prefill = n.coerceIn(2, 6)
            return ThreadPlan(decode, prefill, mask, "Auto: $decode threads writing, $prefill reading, on the ${perf.size.takeIf { it > 0 } ?: cpu.cores} performance cores")
        }
    }
}
