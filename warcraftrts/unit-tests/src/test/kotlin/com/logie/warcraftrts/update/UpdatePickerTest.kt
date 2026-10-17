package com.logie.warcraftrts.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdatePickerTest {
    private fun release(tag: String, apk: Boolean = true, prerelease: Boolean = false) = UpdatePicker.Release(
        tag, "Warcraft Collection $tag", draft = false, prerelease = prerelease,
        assets = if (apk) listOf(UpdatePicker.Asset("WarcraftCollection.apk", "https://api/$tag", 100)) else emptyList(),
    )

    @Test
    fun `the newest of this app's builds, never another app's`() {
        val releases = listOf(release("wcrts-build-3"), release("wow-build-90"), release("wcrts-build-7"), release("wcrts-build-5"))
        assertEquals(7, UpdatePicker.pick(releases, "wcrts-build", 4)?.versionCode)
    }

    @Test
    fun `nothing newer, no APK or a prerelease is no update`() {
        assertNull(UpdatePicker.pick(listOf(release("wcrts-build-4")), "wcrts-build", 4))
        assertNull(UpdatePicker.pick(listOf(release("wcrts-build-9", apk = false)), "wcrts-build", 4))
        assertNull(UpdatePicker.pick(listOf(release("wcrts-build-9", prerelease = true)), "wcrts-build", 4))
    }
}
