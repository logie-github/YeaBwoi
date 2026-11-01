// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.update

/**
 * Picks the app's newest release out of the repository's GitHub releases. The repository publishes
 * other apps too, told apart by their tag prefix: this app's are "wow-build-N", where N is the
 * build number, which is also the APK's version code.
 */
object UpdatePicker {
    data class Asset(val name: String, val url: String, val size: Long)

    data class Release(
        val tag: String,
        val title: String,
        val draft: Boolean,
        val prerelease: Boolean,
        val assets: List<Asset>,
        /** The release's text (Markdown), where CI puts its patch notes. */
        val body: String = "",
    )

    data class Update(
        val versionCode: Int,
        val title: String,
        val apk: Asset,
        /** The patch notes of every release after the installed one up to this, newest first. */
        val notes: List<Notes> = emptyList(),
    )

    /** One release's patch notes: its title ("World of Warcraft 1.0.520") and its lines. */
    data class Notes(val versionCode: Int, val title: String, val lines: List<String>)

    private const val NOTES_START = "<!-- notes -->"
    private const val NOTES_END = "<!-- /notes -->"

    /**
     * The patch notes in a release's text: the lines between CI's markers, without their Markdown
     * (list marks, bold, code), blank lines left out. None where the release has none (the ones made
     * before patch notes began).
     */
    fun notesOf(body: String): List<String> {
        val start = body.indexOf(NOTES_START)
        if (start < 0) return emptyList()
        val end = body.indexOf(NOTES_END, start).takeIf { it >= 0 } ?: body.length
        return body.substring(start + NOTES_START.length, end)
            .lines()
            .map { line ->
                line.trim()
                    .replace(Regex("^[-*+]\\s+"), "• ")
                    .replace(Regex("^#+\\s+"), "")
                    .replace("**", "")
                    .replace("`", "")
            }
            .filter { it.isNotEmpty() }
    }

    /** The build number in [tag] if it is one of this app's release tags. */
    fun versionCodeOf(tag: String, tagPrefix: String): Int? =
        Regex("^${Regex.escape(tagPrefix)}-(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull()

    /** The newest release with an APK that is newer than [currentVersionCode], if any. */
    fun pick(releases: List<Release>, tagPrefix: String, currentVersionCode: Int): Update? {
        val newer = releases
            .filter { !it.draft && !it.prerelease }
            .mapNotNull { release -> versionCodeOf(release.tag, tagPrefix)?.let { it to release } }
            .filter { (versionCode, _) -> versionCode > currentVersionCode }
        val (versionCode, newest) = newer
            .filter { (_, release) -> release.assets.any { it.name.endsWith(".apk", ignoreCase = true) } }
            .maxByOrNull { it.first } ?: return null
        val apk = newest.assets.first { it.name.endsWith(".apk", ignoreCase = true) }
        val notes = newer
            .filter { it.first <= versionCode }
            .sortedByDescending { it.first }
            .mapNotNull { (code, release) ->
                notesOf(release.body).takeIf { it.isNotEmpty() }
                    ?.let { Notes(code, release.title.ifBlank { release.tag }, it) }
            }
        return Update(versionCode, newest.title.ifBlank { newest.tag }, apk, notes)
    }
}
