package com.logie.warcraft.modules

import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleBuildTest {
    private val sha1 = "1111111111111111111111111111111111111111"
    private val sha2 = "2222222222222222222222222222222222222222"
    private val transmog = ModuleBuild.Chosen("azerothcore/mod-transmog", "master", "Transmog", "https://a/1", sha1)
    private val ahbot = ModuleBuild.Chosen("azerothcore/mod-ah-bot", "master", null, null, sha2)

    @Test
    fun requests() {
        val request = ModuleBuild.newRequest(Random(7))
        assertEquals(12, request.length)
        assertTrue(ModuleBuild.isRequest(request))
        assertFalse(ModuleBuild.isRequest("short"))
        assertFalse(ModuleBuild.isRequest("UPPERCASE123"))
        assertFalse(ModuleBuild.isRequest("with space 12"))
        assertEquals("server-abc123def456.tar.xz", ModuleBuild.assetName("abc123def456"))
        assertEquals("wow-build-46", ModuleBuild.release("wow-build", 46))
        assertEquals("46:modules:abc123def456", ModuleBuild.stamp(ModuleBuild.Built(46, "abc123def456", emptyList())))
    }

    @Test
    fun theModulesAskedFor() {
        val playerbots = ModuleBuild.Chosen("liyunfan1223/mod-playerbots", "master", commit = sha1)
        val unpinned = ModuleBuild.Chosen("someone/mod-x", "main")
        val twice = ModuleBuild.Chosen("fork/mod-transmog", "master", commit = sha2)
        assertEquals(
            "azerothcore/mod-transmog@$sha1\nazerothcore/mod-ah-bot@$sha2",
            ModuleBuild.modulesInput(listOf(transmog, playerbots, unpinned, ahbot, twice), setOf("mod-playerbots")),
        )
        val body = JSONObject(ModuleBuild.dispatchBody("ccr-branch", "wow-build-46", "abc123def456", "a/b@$sha1"))
        assertEquals("ccr-branch", body.getString("ref"))
        val inputs = body.getJSONObject("inputs")
        assertEquals("wow-build-46", inputs.getString("release"))
        assertEquals("abc123def456", inputs.getString("request"))
        assertEquals("a/b@$sha1", inputs.getString("modules"))
    }

    @Test
    fun branches() {
        assertTrue(ModuleBuild.isBranch("master"))
        assertTrue(ModuleBuild.isBranch("release/3.3.5"))
        assertFalse(ModuleBuild.isBranch(""))
        assertFalse(ModuleBuild.isBranch("../etc"))
        assertFalse(ModuleBuild.isBranch("a b"))
        assertFalse(ModuleBuild.isBranch("/root"))
        assertFalse(ModuleBuild.isBranch("x.lock"))
    }

    @Test
    fun findsItsRun() {
        val runs = """
            {"total_count": 2, "workflow_runs": [
              {"id": 9, "display_title": "Modules zzzzzzzzzzzz for wow-build-46", "status": "completed", "conclusion": "success", "html_url": "https://github.com/r/actions/runs/9"},
              {"id": 8, "display_title": "Modules abc123def456 for wow-build-46", "status": "in_progress", "conclusion": null, "html_url": "https://github.com/r/actions/runs/8"}
            ]}
        """.trimIndent()
        val run = ModuleBuild.findRun(runs, "abc123def456")!!
        assertEquals(8L, run.id)
        assertEquals("in_progress", run.status)
        assertNull(run.conclusion)
        assertFalse(run.isDone)
        assertEquals("https://github.com/r/actions/runs/8", run.url)
        assertNull(ModuleBuild.findRun(runs, "nothere12345"))
        assertNull(ModuleBuild.findRun("{}", "abc123def456"))

        val done = ModuleBuild.parseRun("""{"id": 8, "status": "completed", "conclusion": "failure", "html_url": "u"}""")
        assertTrue(done.isDone)
        assertFalse(done.succeeded)
    }

    @Test
    fun theStepItsOn() {
        val going = """
            {"jobs": [{"name": "Realm with the modules (build and test)", "steps": [
              {"name": "Set up job", "status": "completed", "conclusion": "success"},
              {"name": "Check what was asked", "status": "completed", "conclusion": "success"},
              {"name": "Run actions/checkout@v4", "status": "completed", "conclusion": "success"},
              {"name": "Compile the server", "status": "in_progress", "conclusion": null},
              {"name": "Package the server", "status": "queued", "conclusion": null}
            ]}]}
        """.trimIndent()
        assertEquals("Compile the server", ModuleBuild.currentStep(going))
        val failed = going.replace("\"in_progress\", \"conclusion\": null}", "\"completed\", \"conclusion\": \"failure\"}")
        assertEquals("Compile the server", ModuleBuild.currentStep(failed))
        val between = """
            {"jobs": [
              {"steps": [{"name": "Keep the package for the release", "status": "completed", "conclusion": "success"},
                         {"name": "Post Run actions/checkout@v4", "status": "completed", "conclusion": "success"},
                         {"name": "Complete job", "status": "completed", "conclusion": "success"}]},
              {"steps": []}
            ]}
        """.trimIndent()
        assertEquals("Keep the package for the release", ModuleBuild.currentStep(between))
        assertNull(ModuleBuild.currentStep("{\"jobs\": []}"))
    }

    @Test
    fun keptAsJson() {
        val chosen = listOf(transmog, ahbot.copy(commit = null))
        assertEquals(chosen, ModuleBuild.chosenFromJson(ModuleBuild.chosenToJson(chosen)))
        // Anything off is left out.
        val bad = org.json.JSONArray()
            .put(JSONObject().put("fullName", "not a name").put("branch", "master"))
            .put(JSONObject().put("fullName", "a/b").put("branch", "../x"))
            .put(JSONObject().put("fullName", "a/b").put("branch", "main").put("commit", "nope"))
            .put("a string")
        assertEquals(emptyList(), ModuleBuild.chosenFromJson(bad))

        val built = ModuleBuild.Built(46, "abc123def456", listOf(transmog), 1234L)
        assertEquals(built, ModuleBuild.builtFromJson(ModuleBuild.builtToJson(built)))
        assertNull(ModuleBuild.builtFromJson("""{"versionCode": 46, "request": "BAD", "modules": []}"""))
        assertNull(ModuleBuild.builtFromJson("not json"))
        assertNull(ModuleBuild.builtFromJson(null))

        val pending = ModuleBuild.Pending("abc123def456", "wow-build-46", 46, listOf(transmog), 100L)
        assertEquals(pending, ModuleBuild.pendingFromJson(ModuleBuild.pendingToJson(pending)))
        val run = ModuleBuild.Run(8, "completed", "success", "https://github.com/r/actions/runs/8")
        val seen = ModuleBuild.seen(pending, run, "Attach it", 200L)
        assertEquals(seen, ModuleBuild.pendingFromJson(ModuleBuild.pendingToJson(seen)))
        assertTrue(seen.succeeded)
        assertFalse(seen.failed)
        assertEquals(8L, seen.runId)
        assertEquals("Attach it", seen.step)
        // A refresh that sees no step keeps the last one.
        assertEquals("Attach it", ModuleBuild.seen(seen, run, null, 300L).step)
        assertTrue(ModuleBuild.seen(pending, run.copy(conclusion = "cancelled"), null, 1L).failed)
    }
}
