package com.logie.wifecraft.dialogue

import org.json.JSONObject

/**
 * The dialogue model Options → Dialogue → Dialogue model offers, and the player's settings for it. Pure (JVM-tested:
 * DialogueModelsTest). Nothing here is bundled: the app downloads the file from Hugging Face on the player's request
 * (ModelDownloader), checks its SHA-256 against the one below (the file's own LFS hash on Hugging Face) and loads it with
 * llama.cpp.
 *
 * ONE model (the player: "don't use the lite model, just one full model, and it can be bigger" and "an expert in sex and
 * pornographic talk"): inflatebot's MN-12B-Mag-Mell-R1, a merge of Mistral-Nemo-12B role-play, erotica and prose fine-tunes
 * (Chronos Gold and Sunrose for role-play and kink and trope coverage, Magnum 2.5 KTO and Gutenberg v4 for prose, Bophades and
 * Wissenschaft for sense), in ChatML, in the imatrix Q4_K_M by mradermacher. Not an "abliterated" model that merely refuses less:
 * it was built from models trained to write this, and the UGI leaderboard's explicit-writing score for it is 7.6 of 10 (Qwen3-8B:
 * 2.0). The research (candidates, scores, sizes, memory and speed estimates) is in the README's "Dialogue model" section.
 */
enum class ModelTier { FULL, CUSTOM }

data class DialogueModelSpec(
    val id: String,
    val label: String,
    val tier: ModelTier,
    val repo: String,
    val file: String,
    val bytes: Long,
    /** SHA-256 of the file, lower case hex (null for a model the player supplied). */
    val sha256: String?,
    val license: String,
    val base: String,
    val template: ChatTemplate,
    /** What the loaded model takes from the phone's memory with the default context, in MB (weights, a q8 KV cache, buffers). */
    val ramMb: Int,
    /** The speed this class of model reaches on a modern Snapdragon's big cores, tokens a second (an estimate: see the README). */
    val estTokensPerSecond: String,
    val blurb: String,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$file"
    val page: String get() = "https://huggingface.co/$repo"
    val sizeMb: Int get() = (bytes / 1_000_000L).toInt()
}

/** How a phone's total memory suits the model: [COMFORTABLE] 13 GiB and up (16 GB phones), [TIGHT] 11.5 to 13, [TOO_SMALL] below that (12 GB phones and less: it may still try). */
enum class RamAdvice { COMFORTABLE, TIGHT, TOO_SMALL }

object DialogueModels {
    val FULL = DialogueModelSpec(
        id = "mn-12b-mag-mell-r1-q4km",
        label = "Mag-Mell 12B (Mistral Nemo, role-play and erotica), full model",
        tier = ModelTier.FULL,
        repo = "mradermacher/MN-12B-Mag-Mell-R1-i1-GGUF",
        file = "MN-12B-Mag-Mell-R1.i1-Q4_K_M.gguf",
        bytes = 7_477_206_400L,
        sha256 = "1d64916f13f0559039fa793ff63d0037322f9ed953c51f8f1d5f3cbb2c8cc777",
        license = "Apache-2.0 (Mistral Nemo and every model merged into it; the merge states none of its own)",
        base = "Mistral-Nemo-Base-2407 (ChatML), merged with Chronos Gold, Sunrose, Magnum 2.5 KTO, Gutenberg v4, Bophades and Wissenschaft",
        template = ChatTemplate.CHATML,
        // 7.48 GB weights + 0.53 GB q8 KV cache (6144 tokens x 40 layers x 8 KV heads x 128 x 2 x 34/32 bytes) + about 0.3 GB of buffers
        ramMb = 8300,
        estTokensPerSecond = "4 to 6",
        blurb = "12 billion parameters, 7.5 GB. Written for role-play and erotica: explicit, in character, no refusals or fade-outs, with a wide vocabulary; it knows Warcraft well for its size. Needs about 8.3 GB of memory while the game runs: made for phones with 16 GB.",
    )

    val all: List<DialogueModelSpec> = listOf(FULL)

    /** The ids the catalogue had before there was one model (Standard 4B, Lite 1.7B): settings that hold one are moved to [FULL]. */
    val legacyIds: Set<String> = setOf("qwen3-4b-2507-abliterated-q4km", "qwen3-1.7b-abliterated-v2-q4km")

    /** The old models' files in files/dialogue/models (and their unfinished `.part` downloads): never loaded, offered for deletion. */
    val legacyFiles: List<String> = listOf(
        "Huihui-Qwen3-4B-Instruct-2507-abliterated.i1-Q4_K_M.gguf",
        "Huihui-Qwen3-1.7B-abliterated-v2.i1-Q4_K_M.gguf",
    )

    /** The old models' files found in [dir] (a model file or its `.part`), with their sizes. */
    fun staleFiles(dir: java.io.File): List<java.io.File> =
        legacyFiles.flatMap { listOf(java.io.File(dir, it), java.io.File(dir, "$it.part")) }.filter { it.isFile }

    /** The catalogue's model for [id]; an old Standard or Lite id gives [FULL] (the settings migrate), anything else null. */
    fun byId(id: String?): DialogueModelSpec? = when {
        id == null -> null
        id in legacyIds -> FULL
        else -> all.firstOrNull { it.id == id }
    }

    /** The player's own GGUF (a ChatML model: Qwen, Mag-Mell and most role-play fine-tunes built on them), taken as it is: no download, no checksum. */
    fun custom(path: String, template: ChatTemplate = ChatTemplate.CHATML, bytes: Long = 0): DialogueModelSpec = DialogueModelSpec(
        id = "custom:$path", label = "Your own file", tier = ModelTier.CUSTOM, repo = "", file = path.substringAfterLast('/'),
        bytes = bytes, sha256 = null, license = "as the file's own", base = "chosen by the player", template = template,
        ramMb = (bytes / 1_000_000L * 1.15).toInt() + 800, estTokensPerSecond = "depends on its size", blurb = "A GGUF file you chose. It must speak ChatML (Qwen, Hermes, most fine-tunes of them).",
    )

    private const val GIB = 1024L * 1024 * 1024

    /** Whether the phone's total memory suits the model (a warning, never a refusal: a phone with less may still try). */
    fun ramAdvice(totalRamBytes: Long): RamAdvice = when {
        totalRamBytes >= 13L * GIB -> RamAdvice.COMFORTABLE
        totalRamBytes >= (11.5 * GIB).toLong() -> RamAdvice.TIGHT
        else -> RamAdvice.TOO_SMALL
    }

    /** The words for the Options page; null when the phone is comfortable. */
    fun ramWarning(spec: DialogueModelSpec, totalRamBytes: Long): String? = when (ramAdvice(totalRamBytes)) {
        RamAdvice.COMFORTABLE -> null
        RamAdvice.TIGHT -> "This phone has ${"%.0f".format(totalRamBytes / GIB.toDouble())} GB of memory, which is on the edge. The model needs about ${"%.1f".format(spec.ramMb / 1000.0)} GB beside the game, so close other apps first; Android may stop the game if memory runs short."
        RamAdvice.TOO_SMALL -> "This phone has ${"%.0f".format(totalRamBytes / GIB.toDouble())} GB of memory and the model needs about ${"%.1f".format(spec.ramMb / 1000.0)} GB beside the game (made for 16 GB phones). It is likely to be slow or to be stopped by Android. You can still try it; if the game closes, switch the model off."
    }

    /** The model's size is checked against the phone's free memory (what Android says is available) with this much to spare. */
    fun fitsMemory(spec: DialogueModelSpec, availableRamBytes: Long, spareMb: Int = 1500): Boolean =
        availableRamBytes >= (spec.ramMb.toLong() + spareMb) * 1_000_000L
}

/** Options → Mods → Dialogue model: what the player chose. Kept as JSON in the app's settings (Settings.dialogue). */
data class DialogueConfig(
    /** On: the model is loaded in the scene and NPCs are answered by it. Off (the default until a model is downloaded): silent. */
    val enabled: Boolean = false,
    /** A [DialogueModelSpec.id] (an old Standard or Lite id is moved to the one model when parsed); null: the catalogue's model. */
    val modelId: String? = null,
    /** A custom model's path (modelId "custom"). */
    val customPath: String? = null,
    /** Threads for the model: 0 = chosen from the phone's cores (half of them, 2 to 4). */
    val threads: Int = 0,
    /** Tokens of KV cache shared by every NPC (2048 to 8192). */
    val context: Int = 6144,
    /** NPCs that keep their place in the cache at once (2 to 12); the others are saved to a file and read back. */
    val slots: Int = 6,
    /**
     * Tokens a minute that unprompted talk (nearby and background NPCs) may use; the selected NPC is never limited. The 8B model
     * makes about 5 to 9 tokens a second, so 300 a minute (about a fifth to a third of the time) leaves it free for the selected NPC.
     */
    val ambientTokensPerMinute: Int = 300,
    /** Download over mobile data too (off: Wi-Fi only). */
    val mobileData: Boolean = false,
) {
    fun resolveThreads(cores: Int): Int = if (threads > 0) threads.coerceIn(1, 8) else (cores / 2).coerceIn(2, 4)

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).apply {
        modelId?.let { put("modelId", it) }
        customPath?.let { put("customPath", it) }
        put("threads", threads); put("context", context); put("slots", slots)
        put("ambientTokensPerMinute", ambientTokensPerMinute); put("mobileData", mobileData)
    }

    companion object {
        fun parse(text: String?): DialogueConfig {
            if (text.isNullOrBlank()) return DialogueConfig()
            return runCatching {
                val j = JSONObject(text)
                val d = DialogueConfig()
                DialogueConfig(
                    enabled = j.optBoolean("enabled", d.enabled),
                    modelId = if (j.has("modelId")) j.getString("modelId").let { if (it in DialogueModels.legacyIds) DialogueModels.FULL.id else it } else null,
                    customPath = if (j.has("customPath")) j.getString("customPath") else null,
                    threads = j.optInt("threads", d.threads).coerceIn(0, 8),
                    context = j.optInt("context", d.context).coerceIn(2048, 8192),
                    slots = j.optInt("slots", d.slots).coerceIn(2, 12),
                    // 900 was the old default for the 4B model; the 12B model is slower, so a saved 900 moves to the new default
                    ambientTokensPerMinute = j.optInt("ambientTokensPerMinute", d.ambientTokensPerMinute).let { if (it == 900) d.ambientTokensPerMinute else it }.coerceIn(0, 6000),
                    mobileData = j.optBoolean("mobileData", d.mobileData),
                )
            }.getOrDefault(DialogueConfig())
        }
    }
}
