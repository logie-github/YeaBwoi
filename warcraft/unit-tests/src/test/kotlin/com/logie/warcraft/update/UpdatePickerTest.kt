package com.logie.warcraft.update

import com.logie.warcraft.update.UpdatePicker.Asset
import com.logie.warcraft.update.UpdatePicker.Release
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdatePickerTest {
    private fun release(tag: String, apk: String? = "App.apk", draft: Boolean = false, prerelease: Boolean = false) =
        Release(
            tag = tag,
            title = "Title $tag",
            draft = draft,
            prerelease = prerelease,
            assets = listOfNotNull(apk?.let { Asset(it, "https://api.github.com/assets/$tag", 1000L) }),
        )

    // Both apps publish to the same repository.
    private val releases = listOf(
        release("colosseum-build-12"),
        release("wow-build-11"),
        release("wow-build-12"),
        release("colosseum-build-10"),
        release("build-5"),
        release("wow-build-13", draft = true),
        release("wow-build-14", prerelease = true),
        release("wow-build-15", apk = null),
        release("wow-build-150-test"),
    )

    @Test
    fun picksThisAppsNewestRelease() {
        val update = UpdatePicker.pick(releases, "wow-build", currentVersionCode = 10)
        assertEquals(12, update?.versionCode)
        assertEquals("Title wow-build-12", update?.title)
        assertEquals("https://api.github.com/assets/wow-build-12", update?.apk?.url)
    }

    @Test
    fun ignoresTheOtherApp() {
        assertEquals(12, UpdatePicker.pick(releases, "colosseum-build", currentVersionCode = 11)?.versionCode)
        assertNull(UpdatePicker.pick(releases, "colosseum-build", currentVersionCode = 12))
    }

    @Test
    fun nothingWhenUpToDateOrNewer() {
        assertNull(UpdatePicker.pick(releases, "wow-build", currentVersionCode = 12))
        assertNull(UpdatePicker.pick(releases, "wow-build", currentVersionCode = 99))
    }

    @Test
    fun skipsDraftsPrereleasesAndReleasesWithoutAnApk() {
        // 13 is a draft, 14 a prerelease and 15 has no APK, so 12 is the newest real update.
        assertEquals(12, UpdatePicker.pick(releases, "wow-build", currentVersionCode = 11)?.versionCode)
    }

    @Test
    fun readsBuildNumbersFromTags() {
        assertEquals(12, UpdatePicker.versionCodeOf("wow-build-12", "wow-build"))
        assertNull(UpdatePicker.versionCodeOf("colosseum-build-12", "wow-build"))
        assertNull(UpdatePicker.versionCodeOf("wow-build-12a", "wow-build"))
        assertNull(UpdatePicker.versionCodeOf("build-5", "wow-build"))
    }

    private fun withNotes(tag: String, body: String, prerelease: Boolean = false) =
        release(tag, prerelease = prerelease).copy(body = body)

    @Test
    fun gathersPatchNotesSinceTheInstalledBuildNewestFirst() {
        val notes = listOf(
            withNotes("wow-build-20", "Intro\n\n## What's new\n<!-- notes -->\n- Old fix\n<!-- /notes -->"),
            withNotes("wow-build-21", "## What's new\n<!-- notes -->\n- **Ralia's mod** on every race\n\n- `patch-Z` fix\n<!-- /notes -->\nServer package"),
            withNotes("wow-build-22", "No notes here (made before they began)"),
            withNotes("wow-build-23", "<!-- notes -->\n* Patch notes in the updates button\n<!-- /notes -->"),
            withNotes("wow-build-24", "<!-- notes -->\n- A test build's note\n<!-- /notes -->", prerelease = true),
            withNotes("colosseum-build-30", "<!-- notes -->\n- Another app's note\n<!-- /notes -->"),
        )
        val update = UpdatePicker.pick(notes, "wow-build", currentVersionCode = 20)
        assertEquals(23, update?.versionCode)
        assertEquals(listOf(23, 21), update?.notes?.map { it.versionCode })
        assertEquals(listOf("• Patch notes in the updates button"), update?.notes?.get(0)?.lines)
        assertEquals(listOf("• Ralia's mod on every race", "• patch-Z fix"), update?.notes?.get(1)?.lines)
    }

    @Test
    fun noPatchNotesWithoutMarkers() {
        assertEquals(emptyList(), UpdatePicker.notesOf("Server package:\n```\ncore abc\n```"))
    }
}
