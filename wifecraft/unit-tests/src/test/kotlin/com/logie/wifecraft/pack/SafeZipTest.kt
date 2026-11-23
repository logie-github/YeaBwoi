package com.logie.wifecraft.pack

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeZipTest {
    private fun dir(): File = Files.createTempDirectory("safezip").toFile()
    private val data = ByteArray(100) { it.toByte() }

    @Test fun unpacksAPlainPackWithDigests() {
        val d = dir()
        val r = SafeZip.extract(PackFixtures.zip("pack/manifest.json" to "{}".toByteArray(), "pack/animations/a.package" to data).inputStream(), d)
        assertEquals(setOf("pack/manifest.json", "pack/animations/a.package"), r.files.keys)
        assertEquals(PackFixtures.sha256(data), r.files.getValue("pack/animations/a.package").sha256)
        assertEquals(100, r.files.getValue("pack/animations/a.package").size)
        assertTrue(File(d, "pack/animations/a.package").isFile)
    }

    @Test fun refusesZipSlipNames() {
        for (name in listOf("../evil.txt", "pack/../../evil.txt", "/etc/evil", "C:/evil", "pack\\..\\evil", "pack/a\u0000.package", "pack/animations/../../../evil")) {
            val d = dir()
            val e = assertFailsWith<PackException>(name) { SafeZip.extract(PackFixtures.zip(name to data).inputStream(), d) }
            assertTrue(e.message!!.contains("refused"), e.message)
            assertFalse(File(d.parentFile, "evil.txt").exists())
            assertEquals(0, d.walkTopDown().count { it.isFile }, "nothing is written for $name")
        }
    }

    @Test fun neverWritesOutsideTheFolderEvenForANormalLookingName() {
        val d = dir()
        // a name that resolves inside is fine; one that resolves out is caught by the canonical path check too
        val r = SafeZip.extract(PackFixtures.zip("pack/a/./b.package" to data).inputStream(), d)
        assertTrue(r.files.isNotEmpty())
        assertTrue(r.files.values.all { it.file.canonicalPath.startsWith(d.canonicalPath + File.separator) })
    }

    @Test fun ignoresScriptsAndFilesOutsideThePackFolder() {
        val d = dir()
        val r = SafeZip.extract(PackFixtures.zip("pack/info/Evil.ts4script" to data, "pack/x.py" to data, "pack/y.pyc" to data, "readme.txt" to data,
            "pack/animations/a.package" to data).inputStream(), d)
        assertEquals(setOf("pack/animations/a.package"), r.files.keys)
        assertEquals(4, r.ignored.size)
        assertTrue(r.ignored.any { it.first.endsWith(".ts4script") && "script" in it.second })
        assertFalse(File(d, "pack/info/Evil.ts4script").exists())
    }

    @Test fun refusesADuplicateName() {
        assertFailsWith<PackException> { SafeZip.extract(PackFixtures.zip("pack/a.package" to data, "pack/A.package" to data).inputStream(), dir()) }
    }

    @Test fun limitsTheEntryCountTheFileSizeAndTheTotal() {
        val many = PackFixtures.zip(*Array(5) { "pack/animations/f$it.package" to data })
        assertFailsWith<PackException> { SafeZip.extract(many.inputStream(), dir(), ZipLimits(maxEntries = 4)) }
        assertFailsWith<PackException> { SafeZip.extract(many.inputStream(), dir(), ZipLimits(maxEntryBytes = 50)) }
        assertFailsWith<PackException> { SafeZip.extract(many.inputStream(), dir(), ZipLimits(maxTotalBytes = 250)) }
    }

    @Test fun refusesAZipBomb() {
        val bomb = ByteArray(8 shl 20)   // 8 MB of zeros packs to a few KB
        val e = assertFailsWith<PackException> { SafeZip.extract(PackFixtures.zip("pack/animations/a.package" to bomb).inputStream(), dir(), ZipLimits(maxRatio = 50)) }
        assertTrue("bomb" in e.message!!, e.message)
    }

    @Test fun aDamagedZipIsReportedNotThrownRaw() {
        val z = PackFixtures.zip("pack/animations/a.package" to ByteArray(5000) { (it * 7).toByte() })
        val cut = z.copyOf(z.size / 2)
        assertFailsWith<PackException> { SafeZip.extract(cut.inputStream(), dir()) }
    }
}
