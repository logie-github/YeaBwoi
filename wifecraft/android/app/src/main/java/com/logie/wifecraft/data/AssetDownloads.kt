package com.logie.wifecraft.data

import com.logie.wifecraft.creator.*
import com.logie.wifecraft.engine.PropLoader
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.simulation.SceneObject
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class AssetDownloads(private val files: GameFiles, private val tables: Tables) {
    enum class Pack(val label: String) { SCENES("Scenes"), ANIMATIONS("Animations"), CLOTHING("Clothes") }

    data class Progress(
        val pack: Pack,
        val packNumber: Int,
        val packCount: Int,
        val done: Int = 0,
        val total: Int = 0,
        val files: Int = 0,
        val bytes: Long = 0L,
        val failed: Int = 0,
    ) {
        val fraction: Float get() = ((packNumber - 1) + (if (total == 0) 0f else done.toFloat() / total)) / packCount
    }

    private data class Work(val direct: Set<Int>, val models: Set<Int>)

    suspend fun download(
        packs: List<Pack>, locations: List<LocationInfo>, catalog: Catalog?,
        wardrobe: suspend () -> TransmogCatalog, onProgress: (Progress) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val seen = ConcurrentHashMap.newKeySet<Int>()
        val failed = ConcurrentHashMap.newKeySet<Int>()
        val lock = Any()
        var downloaded = 0
        var bytes = 0L
        val packErrors = mutableListOf<String>()

        for ((index, pack) in packs.withIndex()) {
            var state = Progress(pack, index + 1, packs.size, files = downloaded, bytes = bytes, failed = failed.size)
            fun report(advance: Boolean = false) = synchronized(lock) {
                state = state.copy(done = state.done + if (advance) 1 else 0, files = downloaded, bytes = bytes, failed = failed.size)
                onProgress(state)
            }
            report()
            val job = coroutineContext.job
            fun fetch(ids: Collection<Int>, checkDependencies: Boolean) {
                for (id in ids) {
                    job.ensureActive()
                    if (id <= 0 || files.has(id) || id in failed) continue
                    val first = seen.add(id)
                    if (!first && !checkDependencies) continue
                    try {
                        files.fetchNow(id)
                        if (first) synchronized(lock) {
                            downloaded++
                            bytes += files.file(id).length()
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed += id }
                }
            }
            try {
                val work = plan(pack, locations, catalog, wardrobe)
                state = state.copy(total = work.direct.size + work.models.size)
                report()
                work.direct.toList().parallel { id ->
                    fetch(listOf(id), checkDependencies = false)
                    report(advance = true)
                }
                work.models.toList().parallel { id ->
                    if (pack == Pack.SCENES) {
                        if (PropLoader.closure(files, id) { fetch(it, checkDependencies = true) } != null) failed += id
                    } else {
                        fetch(listOf(id), checkDependencies = true)
                        M2Files.closure(id, if (pack == Pack.ANIMATIONS) null else emptySet(),
                            { if (files.has(it)) files.file(it).readBytes() else null },
                            { fetch(it, checkDependencies = true) })
                    }
                    report(advance = true)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { packErrors += "${pack.label}: ${e.message ?: "Download failed"}" }
        }
        listOfNotNull(
            "${failed.size} files could not be downloaded. Retry to resume.".takeIf { failed.isNotEmpty() },
            packErrors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        ).joinToString("\n").ifEmpty { "Download complete" }
    }

    private suspend fun plan(pack: Pack, locations: List<LocationInfo>, catalog: Catalog?, wardrobe: suspend () -> TransmogCatalog): Work =
        when (pack) {
            Pack.SCENES -> {
                val files = locations.flatMap { loc -> loc.files().map { it.first } }.toSet()
                val backdrops = catalog?.races.orEmpty().mapNotNull { RaceBackdrop.file(tables, it.id) }
                Work(files, (SceneObject.entries.map { it.model } + SceneObject.BOOK_MODEL + backdrops).toSet())
            }
            Pack.CLOTHING -> {
                val pieces = wardrobe().pieces
                val displays = pieces.mapTo(HashSet()) { it.display }
                val icons = pieces.mapNotNull { p -> p.icon.takeIf { it > 0 } }
                val (models, textures) = CreatureLooks.clothingFiles(tables, displays)
                Work(textures + icons, models)
            }
            Pack.ANIMATIONS -> {
                checkNotNull(catalog) { "Gather the character tables first." }
                val models = tables.rows("ChrModel").mapNotNullTo(LinkedHashSet()) { row ->
                    val displayId = row.int("DisplayID")
                    val modelId = tables.rowBy("CreatureDisplayInfo", "ID", displayId)?.int("ModelID") ?: 0
                    tables.rowBy("CreatureModelData", "ID", modelId)?.int("FileDataID")?.takeIf { it > 0 }
                }
                Work(emptySet(), models)
            }
        }

    private suspend fun <T> List<T>.parallel(work: suspend (T) -> Unit) {
        for (batch in chunked(8)) coroutineScope { batch.map { item -> async { work(item) } }.awaitAll() }
    }
}
