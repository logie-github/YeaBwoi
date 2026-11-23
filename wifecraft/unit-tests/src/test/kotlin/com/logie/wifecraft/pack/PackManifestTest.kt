package com.logie.wifecraft.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackManifestTest {
    private val sha = "a".repeat(64)

    private fun entry(path: String = "pack/animations/a.package", type: String = "animations", id: String = "e1", sha: String = this.sha, size: Long = 10) =
        """{"id":"$id","type":"$type","path":"${path.replace("\\", "\\\\")}","source":"A.package","sha256":"$sha","size":$size,"summary":{"category":"LPGS"}}"""

    private fun manifest(vararg entries: String, schema: Int = 1, format: String = "wifecraft-pack") =
        """{"format":"$format","schema":$schema,"name":"P","entries":[${entries.joinToString(",")}],"notes":["n"]}"""

    private fun refused(text: String, part: String) {
        val e = assertFailsWith<PackException> { PackManifest.parse(text) }
        assertTrue(part in e.message!!, "expected '$part' in: ${e.message}")
    }

    @Test fun readsAValidManifest() {
        val m = PackManifest.parse(manifest(entry(), entry("pack/props/t.package", "props", "e2"), entry("pack/info/s.txt", "script-info", "e3")))
        assertEquals(1, m.schema)
        assertEquals(3, m.entries.size)
        assertEquals(PackKind.PROPS, m.entries[1].kind)
        assertEquals("LPGS", m.entries[0].category)
        assertEquals(listOf("e1"), m.of(PackKind.ANIMATIONS).map { it.id })
        assertEquals(listOf("n"), m.notes)
    }

    @Test fun roundTripsThroughJson() {
        val m = PackManifest.parse(manifest(entry(), entry("pack/furniture/b.package", "furniture", "e2")))
        assertEquals(m.toJson().toString(), PackManifest.parse(m.toJson().toString()).toJson().toString())
    }

    @Test fun refusesWhatIsNotAPack() {
        refused(manifest(entry(), format = "other"), "not a Wifecraft pack")
        refused("not json", "not valid JSON")
        refused("""{"format":"wifecraft-pack","schema":1}""", "no entries")
    }

    @Test fun refusesANewerSchemaInsteadOfHalfReadingIt() = refused(manifest(entry(), schema = 2), "newer version")

    @Test fun refusesPathsThatCouldLeaveTheFolder() {
        for (p in listOf("../x.package", "pack/animations/../../x.package", "/pack/animations/a.package", "pack\\animations\\a.package",
            "pack/animations/C:evil.package", "pack/animations//a.package", "pack/animations/./a.package", "pack/animations/a/b.package", "a.package")) {
            val e = assertFailsWith<PackException>(p) { PackManifest.parse(manifest(entry(path = p))) }
            assertTrue(e.message!!.contains("Entry \"e1\""), e.message)
        }
    }

    @Test fun anEntryLivesInItsKindsFolder() {
        refused(manifest(entry("pack/props/a.package", "animations")), "belongs under pack/animations/")
        refused(manifest(entry("pack/animations/a.txt")), "is a .package file")
        refused(manifest(entry("pack/info/a.package", "script-info")), "is a .txt file")
        refused(manifest(entry("pack/animations/a.ts4script")), "is a .package file")
    }

    @Test fun refusesUnknownTypesBadDigestsAndDuplicates() {
        refused(manifest(entry(type = "scripts")), "a type this app does not know")
        refused(manifest(entry(sha = "xyz")), "no valid sha256")
        refused(manifest(entry(), entry("pack/animations/b.package")), "twice")
        refused(manifest(entry(), entry("pack/animations/A.package", id = "e2")), "twice")
    }

    @Test fun limitsTheCountsAndSizes() {
        refused(manifest(entry(size = PackManifest.MAX_ENTRY_BYTES + 1)), "bytes")
        refused(manifest(*Array(PackManifest.MAX_ENTRIES + 1) { entry("pack/animations/f$it.package", id = "e$it") }), "most it may hold")
        refused(manifest(*Array(5) { entry("pack/animations/f$it.package", id = "e$it", size = 250L shl 20) }), "add up to over")
    }
}
