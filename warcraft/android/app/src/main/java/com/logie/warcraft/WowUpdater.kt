// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.logie.warcraft.update.UpdatePicker
import com.logie.warcraft.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the repository's GitHub releases, the same place CI publishes every build. CI
 * tells the build where to look: the repository (-PwowUpdateRepo), this app's release tag prefix
 * (-PwowReleaseTagPrefix) and, for a private repository, a read-only token (-PwowUpdateToken).
 */
object WowUpdater {
    data class Update(
        val versionCode: Int,
        /** The release's title, like "World of Warcraft 1.0.3". */
        val title: String,
        val apkName: String,
        /** The asset's API URL, which works for private repositories too. */
        val apkUrl: String,
        val size: Long,
        /** Patch notes of each release since the installed one, newest first: its title and its lines. */
        val notes: List<Pair<String, List<String>>> = emptyList(),
    ) : Serializable

    class HttpException(val code: Int) : IOException("HTTP $code")

    private const val API_HOST = "api.github.com"
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 8000
    private const val MAX_REDIRECTS = 5

    val isConfigured: Boolean
        get() = BuildConfig.WOW_UPDATE_REPO.isNotEmpty() && BuildConfig.WOW_RELEASE_TAG_PREFIX.isNotEmpty()

    /** The newest release if it's newer than this build, otherwise null. Background thread. */
    fun findUpdate(): Update? {
        val url = "https://$API_HOST/repos/${BuildConfig.WOW_UPDATE_REPO}/releases?per_page=50"
        val body = connect(url, "application/vnd.github+json").let { connection ->
            try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

        val array = JSONArray(body)
        val releases = List(array.length()) { index ->
            val release = array.getJSONObject(index)
            val assets = release.optJSONArray("assets") ?: JSONArray()
            UpdatePicker.Release(
                tag = release.optString("tag_name"),
                title = release.optString("name"),
                draft = release.optBoolean("draft"),
                prerelease = release.optBoolean("prerelease"),
                body = release.optString("body"),
                assets = List(assets.length()) { i ->
                    val asset = assets.getJSONObject(i)
                    UpdatePicker.Asset(
                        name = asset.optString("name"),
                        url = asset.optString("url"),
                        size = asset.optLong("size"),
                    )
                },
            )
        }
        val update = UpdatePicker.pick(releases, BuildConfig.WOW_RELEASE_TAG_PREFIX, BuildConfig.VERSION_CODE)
            ?: return null
        return Update(
            update.versionCode, update.title, update.apk.name, update.apk.url, update.apk.size,
            update.notes.map { it.title to it.lines },
        )
    }

    /** A file of a release: its API URL (which works for private repositories too) and size. */
    data class Asset(val name: String, val url: String, val size: Long)

    /** The files of this repository's release [tag]. Background thread. */
    fun releaseAssets(tag: String): List<Asset> {
        val url = "https://$API_HOST/repos/${BuildConfig.WOW_UPDATE_REPO}/releases/tags/$tag"
        val body = connect(url, "application/vnd.github+json").let { connection ->
            try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        val assets = JSONObject(body).optJSONArray("assets") ?: JSONArray()
        return List(assets.length()) { i ->
            val asset = assets.getJSONObject(i)
            Asset(asset.optString("name"), asset.optString("url"), asset.optLong("size"))
        }
    }

    /**
     * What a request to [url] carries to get a release's file: for GitHub's API, the file itself
     * and the token; for the server it sends the file from, nothing.
     */
    /**
     * The user's own GitHub token (the Modules screen's, kept by [ModuleStore]): what a build made
     * outside CI, which has none of its own built in, reads this repository's releases with.
     */
    @Volatile
    var userToken: String? = null

    /** The token for this repository's releases: CI's read-only one, else the user's. */
    private val token: String get() = BuildConfig.WOW_UPDATE_TOKEN.ifEmpty { userToken.orEmpty() }

    fun assetHeaders(url: URL): Map<String, String> =
        if (url.host != API_HOST) {
            emptyMap()
        } else {
            buildMap {
                put("Accept", "application/octet-stream")
                put("X-GitHub-Api-Version", "2022-11-28")
                if (token.isNotEmpty()) put("Authorization", "Bearer $token")
            }
        }

    val userAgent: String get() = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"

    /**
     * Downloads [update]'s APK into the cache and checks it's a newer build of this app.
     * Background thread.
     */
    fun download(
        context: Context,
        update: Update,
        onProgress: (done: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): File {
        val directory = File(context.cacheDir, "updates")
        directory.deleteRecursively()
        directory.mkdirs()
        val target = File(directory, update.apkName.replace('/', '_'))
        val partial = File(directory, "${target.name}.part")

        val connection = connect(update.apkUrl, "application/octet-stream")
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.size
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                    if (total > 0 && done != total) throw IOException("Download ended early")
                }
            }
        } finally {
            connection.disconnect()
        }
        if (!partial.renameTo(target)) throw IOException("Can't save the download")
        checkApk(context, target)
        return target
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens Android's installer for a downloaded update. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.filesprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Makes sure [apk] is a newer build of this same app before handing it to the installer. */
    private fun checkApk(context: Context, apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw WrongApkException()
        if (info.packageName != context.packageName ||
            PackageInfoCompat.getLongVersionCode(info) <= BuildConfig.VERSION_CODE
        ) {
            throw WrongApkException()
        }
    }

    class WrongApkException : IOException("Not an update for this app")

    class CancelledException : IOException("Cancelled")

    /**
     * Opens [url], following redirects by hand: GitHub sends release files from another server,
     * and the token must only ever go to GitHub's API.
     */
    private fun connect(url: String, accept: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
                if (current.host == API_HOST) {
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val code = connection.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> return connection
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    current = URL(current, location ?: throw HttpException(code))
                }
                else -> {
                    connection.disconnect()
                    throw HttpException(code)
                }
            }
        }
        throw IOException("Too many redirects")
    }
}
