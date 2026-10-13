// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import com.logie.warcraft.client.ZipDownload
import com.logie.warcraft.modules.GitHubApi
import com.logie.warcraft.modules.ModuleBuild
import com.logie.warcraft.modules.ModuleCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLEncoder

/**
 * The module browser behind the Modules screen: AzerothCore's modules as GitHub lists them
 * (ModuleCatalog), the ones the user picked, the realm built with them on GitHub (this
 * repository's workflow build-warcraft-modules.yml, started with the user's own token) and that
 * realm put in place of the one the app came with.
 *
 * Kept in `files/modules`: chosen.json (the picks), pending.json (the build asked for and how it
 * went), realm.json (the realm in, with the modules it was built with), and each realm put in
 * (realms/<request>.tar.xz, listed in realms.json), so realms can be swapped without building
 * them again. The token is only ever in the app's own preferences.
 */
object ModuleStore {
    private val _chosen = MutableStateFlow<List<ModuleBuild.Chosen>>(emptyList())
    val chosen: StateFlow<List<ModuleBuild.Chosen>> = _chosen

    private val _pending = MutableStateFlow<ModuleBuild.Pending?>(null)
    val pending: StateFlow<ModuleBuild.Pending?> = _pending

    private val _realm = MutableStateFlow<ModuleBuild.Built?>(null)
    /** The modules realm built last, whichever version of the app it was for. */
    val realm: StateFlow<ModuleBuild.Built?> = _realm

    private val _saved = MutableStateFlow<List<ModuleBuild.Built>>(emptyList())
    /** The realms kept in the app, newest first. */
    val saved: StateFlow<List<ModuleBuild.Built>> = _saved

    private val _hasToken = MutableStateFlow(false)
    val hasToken: StateFlow<Boolean> = _hasToken

    private var loaded = false

    /** The realm's own modules (warcraft/server-version.env), always in. */
    val baseModules: Set<String> = BuildConfig.WOW_BASE_MODULES.split(',').filter { it.isNotBlank() }.toSet()

    /** This build knows where to build a realm: its repository, release and branch. */
    val isAvailable: Boolean
        get() = WowUpdater.isConfigured && BuildConfig.WOW_BUILD_BRANCH.isNotEmpty()

    /** The app's own release, which a realm is built from. */
    val release: String get() = ModuleBuild.release(BuildConfig.WOW_RELEASE_TAG_PREFIX, BuildConfig.VERSION_CODE)

    private fun dir(context: Context) = File(WowFiles.root(context), "modules").also { it.mkdirs() }

    fun load(context: Context) {
        if (loaded) return
        val dir = dir(context)
        _chosen.value = runCatching {
            ModuleBuild.chosenFromJson(org.json.JSONArray(File(dir, CHOSEN).readText()))
        }.getOrDefault(emptyList())
        _pending.value = ModuleBuild.pendingFromJson(File(dir, PENDING).takeIf { it.isFile }?.readText())
        _realm.value = ModuleBuild.builtFromJson(File(dir, REALM).takeIf { it.isFile }?.readText())
        _saved.value = ModuleBuild.savedFromJson(File(dir, SAVED).takeIf { it.isFile }?.readText())
        _hasToken.value = !token(context).isNullOrBlank()
        loaded = true
        runCatching { tidySaved(context) }
    }

    /** The realm built with modules for this version of the app, if it's the one to run. */
    fun realmForThisVersion(context: Context): ModuleBuild.Built? {
        load(context)
        return _realm.value?.takeIf { it.versionCode == BuildConfig.VERSION_CODE }
    }

    private fun write(file: File, text: String) {
        val part = File(file.path + ".part")
        part.writeText(text)
        if (!part.renameTo(file)) throw IOException("Can't save ${file.name}")
    }

    // The picks -------------------------------------------------------------------------------------

    fun isChosen(fullName: String) = _chosen.value.any { it.fullName.equals(fullName, ignoreCase = true) }

    fun add(context: Context, listing: ModuleCatalog.Listing) {
        load(context)
        if (isChosen(listing.fullName) || listing.name in baseModules) return
        if (!ModuleBuild.isBranch(listing.defaultBranch)) return
        save(context, _chosen.value + ModuleBuild.Chosen(listing.fullName, listing.defaultBranch, listing.description, listing.avatar))
    }

    fun remove(context: Context, fullName: String) {
        load(context)
        save(context, _chosen.value.filterNot { it.fullName.equals(fullName, ignoreCase = true) })
    }

    private fun save(context: Context, list: List<ModuleBuild.Chosen>) {
        write(File(dir(context), CHOSEN), ModuleBuild.chosenToJson(list).toString())
        _chosen.value = list
    }

    private fun setPending(context: Context, pending: ModuleBuild.Pending?) {
        val file = File(dir(context), PENDING)
        if (pending == null) file.delete() else write(file, ModuleBuild.pendingToJson(pending))
        _pending.value = pending
    }

    private fun setRealm(context: Context, built: ModuleBuild.Built?) {
        val file = File(dir(context), REALM)
        if (built == null) file.delete() else write(file, ModuleBuild.builtToJson(built))
        _realm.value = built
    }

    /** The app's own realm is back in (ServerPackage): no modules realm any more. */
    fun forgetRealm(context: Context) {
        load(context)
        if (_realm.value != null) setRealm(context, null)
    }

    // The realms kept --------------------------------------------------------------------------------

    private fun savedDir(context: Context) = File(dir(context), "realms").also { it.mkdirs() }

    /** Where a kept realm's package is. */
    fun savedFile(context: Context, request: String) = File(savedDir(context), ModuleBuild.assetName(request))

    /** A kept realm that can be put in: for this version of the app, its package there. */
    fun canPutIn(context: Context, built: ModuleBuild.Built) =
        built.versionCode == BuildConfig.VERSION_CODE && savedFile(context, built.request).isFile

    private fun setSaved(context: Context, list: List<ModuleBuild.Built>) {
        write(File(dir(context), SAVED), ModuleBuild.savedToJson(list))
        _saved.value = list
    }

    /**
     * A realm for an earlier version of the app can't be put in (the app and its realm change
     * together): its package goes, its modules stay listed, to build again. At most [MAX_SAVED].
     */
    private fun tidySaved(context: Context) {
        val list = _saved.value
        val tidy = list.map { built ->
            if (built.versionCode != BuildConfig.VERSION_CODE && built.size > 0) {
                savedFile(context, built.request).delete()
                built.copy(size = 0)
            } else {
                built
            }
        }.take(MAX_SAVED)
        list.drop(MAX_SAVED).forEach { savedFile(context, it.request).delete() }
        // Packages no entry lists (a download that was being kept when the app stopped).
        val listed = tidy.map { ModuleBuild.assetName(it.request) }.toSet()
        savedDir(context).listFiles().orEmpty().filter { it.name !in listed }.forEach { it.delete() }
        if (tidy != list) setSaved(context, tidy)
    }

    /** Forgets a kept realm and deletes its package. */
    fun deleteSaved(context: Context, request: String) {
        load(context)
        savedFile(context, request).delete()
        setSaved(context, _saved.value.filterNot { it.request == request })
    }

    /** [built]'s modules become the picks (to build again, or to change). */
    fun useModules(context: Context, built: ModuleBuild.Built) {
        load(context)
        save(context, built.modules.filter { it.name !in baseModules })
    }

    // The token -------------------------------------------------------------------------------------

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)

    /** Checks [token] can see and start this repository's builds, then keeps it. */
    suspend fun setToken(context: Context, token: String) = withContext(Dispatchers.IO) {
        val trimmed = token.trim()
        GitHubApi({ trimmed }, WowUpdater.userAgent).get(workflowUrl())
        prefs(context).edit().putString(KEY_TOKEN, trimmed).apply()
        _hasToken.value = true
    }

    fun clearToken(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).apply()
        _hasToken.value = false
    }

    private fun api(context: Context) = GitHubApi({ token(context) }, WowUpdater.userAgent)

    // GitHub ----------------------------------------------------------------------------------------

    private val repo get() = BuildConfig.WOW_UPDATE_REPO
    private fun workflowUrl() = "https://${GitHubApi.API_HOST}/repos/$repo/actions/workflows/${ModuleBuild.WORKFLOW}"

    private fun path(text: String) = text.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** A page of the topic's modules. */
    suspend fun search(context: Context, text: String, order: ModuleCatalog.Order, page: Int): ModuleCatalog.Page =
        withContext(Dispatchers.IO) { ModuleCatalog.parseSearch(api(context).get(ModuleCatalog.searchUrl(text, order, page))) }

    /** A module's README (Markdown), or null when it has none. */
    suspend fun readme(context: Context, fullName: String): String? = withContext(Dispatchers.IO) {
        try {
            api(context).get("https://${GitHubApi.API_HOST}/repos/${path(fullName)}/readme", GitHubApi.RAW)
        } catch (e: GitHubApi.HttpError) {
            if (e.code == 404) null else throw e
        }
    }

    /**
     * Asks GitHub for a realm with the chosen modules: each at its branch's newest commit, built
     * from this version's release. Background work; throws what went wrong.
     */
    suspend fun startBuild(context: Context) = withContext(Dispatchers.IO) {
        load(context)
        if (token(context).isNullOrBlank()) throw IOException(context.getString(R.string.wow_modules_error_token))
        val api = api(context)
        val picks = _chosen.value.filter { it.name !in baseModules }.take(ModuleBuild.MAX_MODULES)
        if (picks.isEmpty()) throw IOException(context.getString(R.string.wow_modules_error_none))
        val pinned = picks.map { module ->
            val sha = api.get(
                "https://${GitHubApi.API_HOST}/repos/${path(module.fullName)}/commits/${path(module.branch)}",
                "application/vnd.github.sha",
            ).trim()
            if (!ModuleCatalog.isCommit(sha)) throw IOException(context.getString(R.string.wow_modules_error_commit, module.fullName))
            module.copy(commit = sha)
        }
        val request = ModuleBuild.newRequest()
        val modules = ModuleBuild.modulesInput(pinned, baseModules)
        api.post("${workflowUrl()}/dispatches", ModuleBuild.dispatchBody(BuildConfig.WOW_BUILD_BRANCH, release, request, modules))
        setPending(
            context,
            ModuleBuild.Pending(request, release, BuildConfig.VERSION_CODE, pinned, System.currentTimeMillis()),
        )
    }

    /** How the build is going, from GitHub. Background work. */
    suspend fun refresh(context: Context): ModuleBuild.Pending? = withContext(Dispatchers.IO) {
        load(context)
        val pending = _pending.value ?: return@withContext null
        if (pending.succeeded && pending.leftOut == null) {
            // What went in: read once the build is done.
            val leftOut = runCatching { leftOut(context, pending) }.getOrNull() ?: return@withContext pending
            if (_pending.value?.request != pending.request) return@withContext _pending.value
            return@withContext pending.copy(leftOut = leftOut).also { setPending(context, it) }
        }
        if (pending.isDone || token(context).isNullOrBlank()) return@withContext pending
        val api = api(context)
        val run = if (pending.runId == null) {
            ModuleBuild.findRun(api.get("${workflowUrl()}/runs?event=workflow_dispatch&per_page=30"), pending.request)
        } else {
            ModuleBuild.parseRun(api.get("https://${GitHubApi.API_HOST}/repos/$repo/actions/runs/${pending.runId}"))
        } ?: return@withContext pending.copy(checkedAt = System.currentTimeMillis()).also { _pending.value = it }
        val step = runCatching {
            ModuleBuild.currentStep(api.get("https://${GitHubApi.API_HOST}/repos/$repo/actions/runs/${run.id}/jobs"))
        }.getOrNull()
        // Asked for and seen by a newer refresh meanwhile: the newer one stays.
        if (_pending.value?.request != pending.request) return@withContext _pending.value
        ModuleBuild.seen(pending, run, step, System.currentTimeMillis()).also { setPending(context, it) }
    }

    /** Stops following the build, and stops it on GitHub if it's still going. */
    suspend fun cancelBuild(context: Context) = withContext(Dispatchers.IO) {
        val pending = _pending.value ?: return@withContext
        if (pending.runId != null && !pending.isDone) {
            runCatching { api(context).post("https://${GitHubApi.API_HOST}/repos/$repo/actions/runs/${pending.runId}/cancel", "{}") }
        }
        setPending(context, null)
    }

    /**
     * The modules build [pending] left out, from its record next to its package (none when it has
     * no record: built before builds kept one). Background thread; throws when GitHub can't be asked.
     */
    private fun leftOut(context: Context, pending: ModuleBuild.Pending): List<ModuleBuild.LeftOut> {
        val record = WowUpdater.releaseAssets(pending.release).firstOrNull { it.name == ModuleBuild.recordName(pending.request) }
            ?: return emptyList()
        val api = GitHubApi({ BuildConfig.WOW_UPDATE_TOKEN.ifEmpty { token(context).orEmpty() } }, WowUpdater.userAgent)
        return ModuleBuild.parseLeftOut(api.get(record.url, GitHubApi.FILE))
    }

    /** A finished build is put aside: done with. */
    fun dismissBuild(context: Context) = setPending(context, null)

    // Putting the realm in (ImportService, Imports.Kind.MODULES) ------------------------------------

    /** The built realm's package, downloaded, put in place of the realm. Background thread. */
    fun install(context: Context, onProgress: (Float?, String?) -> Unit) {
        load(context)
        val pending = _pending.value ?: throw Imports.ImportException(context.getString(R.string.wow_modules_error_no_build))
        if (!pending.succeeded) throw Imports.ImportException(context.getString(R.string.wow_modules_error_no_build))
        if (pending.versionCode != BuildConfig.VERSION_CODE) {
            throw Imports.ImportException(context.getString(R.string.wow_modules_error_other_version))
        }
        val name = ModuleBuild.assetName(pending.request)
        onProgress(null, context.getString(R.string.wow_modules_finding))
        val asset = network(context) { WowUpdater.releaseAssets(pending.release) }.firstOrNull { it.name == name }
            ?: throw Imports.ImportException(context.getString(R.string.wow_modules_error_missing, name, pending.release))
        val file = File(dir(context), name)
        if (!file.isFile) download(context, asset, file, onProgress)
        val leftOut = pending.leftOut ?: runCatching { leftOut(context, pending) }.getOrDefault(emptyList())

        val built = ModuleBuild.Built(
            BuildConfig.VERSION_CODE, pending.request, ModuleBuild.without(pending.modules, leftOut), System.currentTimeMillis(),
            leftOut, file.length(),
        )
        val unpacking = context.getString(R.string.wow_modules_unpacking)
        onProgress(0f, unpacking)
        try {
            ServerPackage.installFile(context, file, ModuleBuild.stamp(built)) { onProgress(it, unpacking) }
        } catch (e: IOException) {
            // A damaged download goes, so the next try fetches it again.
            file.delete()
            throw Imports.ImportException(context.getString(R.string.wow_server_error_unpack, e.message ?: ""))
        }
        setRealm(context, built)
        setPending(context, null)
        // Kept, to put it back in later without building it again.
        if (file.renameTo(savedFile(context, built.request))) {
            setSaved(context, listOf(built) + _saved.value.filterNot { it.request == built.request })
            runCatching { tidySaved(context) }
        } else {
            file.delete()
        }
    }

    /** A kept realm, put in place of the one in now. Background thread. */
    fun putIn(context: Context, request: String, onProgress: (Float?, String?) -> Unit) {
        load(context)
        val built = _saved.value.firstOrNull { it.request == request }
            ?: throw Imports.ImportException(context.getString(R.string.wow_modules_error_no_build))
        if (built.versionCode != BuildConfig.VERSION_CODE) {
            throw Imports.ImportException(context.getString(R.string.wow_modules_error_other_version))
        }
        val file = savedFile(context, request)
        if (!file.isFile) throw Imports.ImportException(context.getString(R.string.wow_modules_error_saved_missing))
        // Unpacked next to the realm in now (about seven times the package).
        Imports.checkSpace(context, file.length() * 7)
        val unpacking = context.getString(R.string.wow_modules_unpacking)
        onProgress(0f, unpacking)
        try {
            ServerPackage.installFile(context, file, ModuleBuild.stamp(built)) { onProgress(it, unpacking) }
        } catch (e: IOException) {
            throw Imports.ImportException(context.getString(R.string.wow_server_error_unpack, e.message ?: ""))
        }
        setRealm(context, built)
    }

    /** The realm the app came with, in place of the modules one. Background thread. */
    fun installStock(context: Context, onProgress: (Float?, String?) -> Unit) {
        val unpacking = context.getString(R.string.wow_modules_unpacking)
        onProgress(0f, unpacking)
        ServerPackage.install(context) { onProgress(it, unpacking) }
        forgetRealm(context)
    }

    private fun download(context: Context, asset: WowUpdater.Asset, file: File, onProgress: (Float?, String?) -> Unit) {
        val part = File(file.path + ".part")
        val source = ZipDownload.Http(asset.url, WowUpdater.userAgent) { url -> assetHeaders(context, url) }
        val label = context.getString(R.string.wow_modules_downloading)
        var tries = 0
        while (true) {
            try {
                val total = source.size()
                if (part.length() > total) part.delete()
                // The rest of the download, and the realm unpacked from it (about seven times its size).
                Imports.checkSpace(context, (total - part.length()) + total * 8)
                if (part.length() < total) {
                    source.open(part.length(), null).use { input ->
                        java.io.FileOutputStream(part, true).use { output ->
                            val buffer = ByteArray(1 shl 16)
                            var done = part.length()
                            var reported = 0L
                            while (true) {
                                if (Imports.cancelled) throw Imports.ImportException("cancelled")
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                if (done - reported >= 1 shl 20 || done == total) {
                                    reported = done
                                    onProgress(done.toFloat() / total, "$label · ${shortSize(context, done)} / ${shortSize(context, total)}")
                                }
                            }
                        }
                    }
                }
                if (part.length() != total) throw IOException("Download ended early")
                if (!part.renameTo(file)) throw Imports.ImportException(context.getString(R.string.wow_import_error_write))
                return
            } catch (e: Imports.ImportException) {
                throw e
            } catch (e: IOException) {
                if (++tries >= 6) {
                    throw Imports.ImportException(context.getString(R.string.wow_download_error_network, e.message ?: e.javaClass.simpleName))
                }
                Thread.sleep(3_000L * tries)
            }
        }
    }

    /** GitHub's API gets the build's own read token, or the user's; the file's server, nothing. */
    private fun assetHeaders(context: Context, url: URL): Map<String, String> {
        if (url.host != GitHubApi.API_HOST) return emptyMap()
        val token = BuildConfig.WOW_UPDATE_TOKEN.ifEmpty { token(context).orEmpty() }
        return buildMap {
            put("Accept", GitHubApi.FILE)
            put("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank()) put("Authorization", "Bearer $token")
        }
    }

    private fun <T> network(context: Context, block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw Imports.ImportException(context.getString(R.string.wow_download_error_network, e.message ?: e.javaClass.simpleName))
        }

    private fun shortSize(context: Context, bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)

    private const val CHOSEN = "chosen.json"
    private const val PENDING = "pending.json"
    private const val REALM = "realm.json"
    private const val SAVED = "realms.json"
    private const val MAX_SAVED = 12
    private const val PREFS = "github"
    private const val KEY_TOKEN = "token"
}
