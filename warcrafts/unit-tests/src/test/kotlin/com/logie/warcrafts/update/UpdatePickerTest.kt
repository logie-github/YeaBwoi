package com.logie.warcrafts.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdatePickerTest {
    private fun release(tag: String, apk: Boolean = true, prerelease: Boolean = false) = UpdatePicker.Release(
        tag, "Warcraft $tag", draft = false, prerelease = prerelease,
        assets = if (apk) listOf(UpdatePicker.Asset("Warcraft.apk", "https://api/$tag", 100)) else emptyList(),
    )

    @Test
    fun `the newest of this app's builds, never another app's`() {
        val releases = listOf(release("warcrafts-build-3"), release("wow-build-90"), release("warcrafts-build-7"), release("warcrafts-build-5"))
        assertEquals(7, UpdatePicker.pick(releases, "warcrafts-build", 4)?.versionCode)
    }

    @Test
    fun `nothing newer, no APK or a prerelease is no update`() {
        assertNull(UpdatePicker.pick(listOf(release("warcrafts-build-4")), "warcrafts-build", 4))
        assertNull(UpdatePicker.pick(listOf(release("warcrafts-build-9", apk = false)), "warcrafts-build", 4))
        assertNull(UpdatePicker.pick(listOf(release("warcrafts-build-9", prerelease = true)), "warcrafts-build", 4))
    }
}
