package com.logie.wifecraft.dialogue

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuProfileTest {
    // /proc/cpuinfo as a Snapdragon 8 Gen 3 phone prints it (every core the same Features line)
    private val gen3 = (0 until 8).joinToString("\n\n") { i ->
        "processor\t: $i\nBogoMIPS\t: 38.40\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg dcpodp sve2 sveaes svepmull svebitperm svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm bf16 dgh bti mte ecv afp wfxt\nCPU implementer\t: 0x41"
    }
    private val gen3Freq = listOf(2_266_000L, 2_266_000L, 2_995_200L, 2_995_200L, 2_995_200L, 3_148_800L, 3_148_800L, 3_302_400L)   // 2 little, 5 performance, 1 prime (kHz)
    private val gen3Cpu = CpuProfile(CpuProfile.parseFeatures(gen3), gen3Freq, CpuProfile.parseCoreCount(gen3))

    private fun cpu(vararg f: String) = CpuProfile(f.toSet(), List(8) { 2_000_000L }, 8)

    @Test fun theFeaturesAndTheCoreCountAreReadFromCpuinfo() {
        assertEquals(8, CpuProfile.parseCoreCount(gen3))
        assertTrue(gen3Cpu.dotprod && gen3Cpu.fp16 && gen3Cpu.i8mm && gen3Cpu.sve && gen3Cpu.sve2 && gen3Cpu.bf16)
        assertFalse(gen3Cpu.sme)
        assertTrue(CpuProfile.parseFeatures("").isEmpty() && CpuProfile.parseCoreCount("") == 0, "an unreadable file is an empty answer")
        assertEquals(setOf("fp", "asimd"), CpuProfile.parseFeatures("processor : 0\nFeatures : FP ASIMD\n"))
    }

    @Test fun theKernelsFollowTheFeaturesBestFirstAndAlwaysHaveAFloor() {
        assertEquals("android_armv9.0_1", CpuVariants.pick(gen3Cpu), "dotprod + fp16 + i8mm + sve2 without sme")
        assertEquals("android_armv8.6_1", CpuVariants.pick(cpu("asimddp", "asimdhp", "i8mm")), "a Snapdragon 8 Gen 1 class core: i8mm, no SVE2")
        assertEquals("android_armv8.2_2", CpuVariants.pick(cpu("asimddp", "asimdhp")))
        assertEquals("android_armv8.2_1", CpuVariants.pick(cpu("asimddp")))
        assertEquals("android_armv8.0_1", CpuVariants.pick(cpu("asimd", "fp")), "plain NEON: every arm64 phone")
        assertEquals("android_armv8.0_1", CpuVariants.pick(CpuProfile()), "nothing known: the floor, never a kernel the CPU might lack")
        assertEquals("android_armv9.2_2", CpuVariants.pick(cpu("asimddp", "asimdhp", "i8mm", "sve", "sve2", "sme")))
        assertEquals("android_armv9.2_1", CpuVariants.pick(cpu("asimddp", "asimdhp", "i8mm", "sve", "sme")))
        // i8mm without fp16 or dotprod is no known build: the best one that fits
        assertEquals("android_armv8.0_1", CpuVariants.pick(cpu("i8mm")))
        assertTrue(CpuVariants.hasI8mm(gen3Cpu) && CpuVariants.hasI8mm(cpu("asimddp", "asimdhp", "i8mm")))
        assertFalse(CpuVariants.hasI8mm(cpu("asimddp", "asimdhp")))
    }

    @Test fun theTableIsTheBuildsListOfVariants() {
        // CMakeLists asks ggml for GGML_CPU_ALL_VARIANTS: its Android builds are these seven (ggml/src/CMakeLists.txt at the pinned tag)
        assertEquals(listOf("android_armv9.2_2", "android_armv9.2_1", "android_armv9.0_1", "android_armv8.6_1", "android_armv8.2_2", "android_armv8.2_1", "android_armv8.0_1"), CpuVariants.names)
    }

    @Test fun theBigCoresAreThoseWithinSeventyPercentOfTheFastest() {
        assertEquals(listOf(2, 3, 4, 5, 6, 7), gen3Cpu.performanceCores, "the two 2.27 GHz little cores are out")
        val noFreq = CpuProfile(setOf("asimddp"), emptyList(), 8)
        assertEquals(listOf(4, 5, 6, 7), noFreq.performanceCores, "frequencies unreadable: the upper half")
        assertEquals(listOf(0, 1, 2, 3), CpuProfile(emptySet(), emptyList(), 4).performanceCores)
        assertEquals(emptyList(), CpuProfile().performanceCores)
    }

    @Test fun autoUsesFourThreadsToWriteAndTheBigCoresToReadAndKeepsThemOnThem() {
        val p = ThreadPlan.choose(gen3Cpu)
        assertEquals(4, p.decode, "memory-bound: more threads only wait on the bus")
        assertEquals(6, p.prefill, "compute-bound: every big core")
        assertEquals(0b11111100L, p.coreMask, "cores 2 to 7")
        assertTrue("Auto" in p.note && "6 performance cores" in p.note, p.note)
        val small = ThreadPlan.choose(CpuProfile(setOf("asimddp"), listOf(2_000_000L, 2_000_000L, 1_000_000L, 1_000_000L), 4))
        assertEquals(2, small.decode); assertEquals(2, small.prefill); assertEquals(0b11L, small.coreMask)
        val unknown = ThreadPlan.choose(CpuProfile())
        assertEquals(2, unknown.decode, "never below two"); assertEquals(0L, unknown.coreMask, "no mask: any core")
    }

    @Test fun aHandPickedNumberIsUsedForBoth() {
        val p = ThreadPlan.choose(gen3Cpu, 5)
        assertEquals(5, p.decode); assertEquals(5, p.prefill)
        assertTrue("your choice" in p.note)
        assertEquals(ThreadPlan.choose(gen3Cpu), ThreadPlan.choose(gen3Cpu, 0))
    }

    @Test fun readingAMissingFileGivesASafeEmptyProfile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wc-cpu-test-${System.nanoTime()}")
        val p = CpuProfile.read("$dir/none", "$dir/sys")
        assertTrue(p.features.isEmpty())
        assertTrue(p.cores >= 1, "falls back to the JVM's core count")
        assertEquals("android_armv8.0_1", CpuVariants.pick(p))
        assertTrue("kernels expected: android_armv8.0_1" in p.describe())
    }

    @Test fun aSysfsTreeIsReadForTheFrequencies() {
        val root = File(System.getProperty("java.io.tmpdir"), "wc-cpu-sys-${System.nanoTime()}")
        try {
            for (i in 0 until 4) File(root, "cpu$i/cpufreq").also { it.mkdirs() }.let { d -> File(d, "cpuinfo_max_freq").writeText(if (i < 2) "1800000\n" else "3000000\n") }
            val info = File(root, "cpuinfo").also { it.writeText("processor : 0\nprocessor : 1\nprocessor : 2\nprocessor : 3\nFeatures : asimddp asimdhp i8mm\n") }
            val p = CpuProfile.read(info.path, root.path)
            assertEquals(listOf(1_800_000L, 1_800_000L, 3_000_000L, 3_000_000L), p.maxFreqKHz)
            assertEquals(listOf(2, 3), p.performanceCores)
            assertTrue("2 x 3.00 GHz + 2 x 1.80 GHz" in p.describe(), p.describe())
        } finally { root.deleteRecursively() }
    }
}
