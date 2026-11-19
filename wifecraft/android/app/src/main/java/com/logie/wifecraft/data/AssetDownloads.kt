package com.logie.wifecraft.data

import com.logie.wifecraft.creator.*
import com.logie.wifecraft.engine.PropLoader
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.simulation.SceneObject
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

class AssetDownloads(private val files: GameFiles, private val tables: Tables) {
    enum class Pack(val label: String) { SCENES("Scenes"), ANIMATIONS("Animations"), CLOTHING("Clothes") }

    suspend fun download(packs: List<Pack>, locations: List<LocationInfo>, catalog: Catalog?,
                         wardrobe: suspend () -> TransmogCatalog, onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        val seen = ConcurrentHashMap.newKeySet<Int>()
        val failed = ConcurrentHashMap.newKeySet<Int>()
        val done = AtomicInteger()
        for (pack in packs) {
            val job = coroutineContext.job
            val fetchBatch: (Collection<Int>) -> Unit = { ids ->
                for (id in ids) {
                    job.ensureActive()
                    if (id > 0 && !files.has(id) && seen.add(id)) {
                        try { files.fetchNow(id) } catch (_: Exception) { failed += id }
                        val count = done.incrementAndGet()
                        if (count % 20 == 0) onStatus("${pack.label} · $count files checked")
                    }
                }
            }
            val read: (Int) -> ByteArray? = { id -> if (files.has(id)) files.file(id).readBytes() else null }
            val models = when (pack) {
                Pack.SCENES -> {
                    val manifest = locations.flatMap { it.files().map { pair -> pair.first } }.distinct()
                    onStatus("Downloading scenes…")
                    manifest.chunked(32).mapBatches { fetchBatch(it) }
                    val backdrops = catalog?.races.orEmpty().mapNotNull { RaceBackdrop.file(tables, it.id) }
                    val objects = (SceneObject.entries.map { it.model } + SceneObject.BOOK_MODEL + backdrops).distinct()
                    objects.mapBatches { id ->
                        val problem = PropLoader.closure(files, id) { fetchBatch(it) }
                        if (problem != null) failed += id
                    }
                    emptySet()
                }
                Pack.CLOTHING -> {
                    onStatus("Preparing the clothing catalog…")
                    val pieces = wardrobe().pieces
                    val displays = pieces.mapTo(HashSet()) { it.display }
                    val iconIds = pieces.mapNotNullTo(HashSet()) { it.icon.takeIf { icon -> icon > 0 } }
                    val (modelIds, textureIds) = CreatureLooks.clothingFiles(tables, displays)
                    onStatus("Downloading clothes…")
                    (textureIds + iconIds).chunked(32).mapBatches { fetchBatch(it) }
                    modelIds
                }
                Pack.ANIMATIONS -> {
                    onStatus("Downloading Warcraft character animations…")
                    checkNotNull(catalog) { "Gather the character tables first." }
                    tables.rows("ChrModel").mapNotNullTo(LinkedHashSet()) { row ->
                        val displayId = row.int("DisplayID")
                        val modelId = tables.rowBy("CreatureDisplayInfo", "ID", displayId)?.int("ModelID") ?: 0
                        tables.rowBy("CreatureModelData", "ID", modelId)?.int("FileDataID")?.takeIf { it > 0 }
                    }
                }
            }
            models.toList().mapBatches { model ->
                fetchBatch(listOf(model))
                M2Files.closure(model, if (pack == Pack.ANIMATIONS) null else emptySet(), read, fetchBatch)
            }
        }
        if (failed.isEmpty()) "Download complete" else "${failed.size} files could not be downloaded. Retry to resume."
    }

    private suspend fun <T> List<T>.mapBatches(work: suspend (T) -> Unit) {
        for (batch in chunked(8)) coroutineScope { batch.map { item -> async { work(item) } }.awaitAll() }
    }
}
