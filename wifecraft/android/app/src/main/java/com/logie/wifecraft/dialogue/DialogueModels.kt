package com.logie.wifecraft.dialogue

import org.json.JSONObject

/**
 * The dialogue models Options → Mods → Dialogue model offers, and the player's settings for them. Pure (JVM-tested:
 * DialogueModelsTest). Nothing here is bundled: the app downloads the chosen file from Hugging Face on the player's request
 * (ModelDownloader), checks its SHA-256 against the one below (the file's own LFS hash on Hugging Face) and loads it with
 * llama.cpp.
 *
 * Why these two (the research is in the README's "Dialogue model" section): both are Alibaba's Qwen3 (Apache-2.0), the
 * best-informed small models about Warcraft that run in a phone's RAM, in a ChatML format that follows a persona well, and
 * both are the huihui-ai "abliterated" versions (the model's refusal direction removed) so that adult scenes between fictional
 * adult characters are written rather than declined. The quantisation is the imatrix Q4_K_M by mradermacher.
 */
enum class ModelTier { STANDARD, LITE, CUSTOM }

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
    /** The speed this class of model reaches on a modern Snapdragon's four big cores, tokens a second (an estimate: see the README). */
    val estTokensPerSecond: String,
    val blurb: String,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$file"
    val page: String get() = "https://huggingface.co/$repo"
    val sizeMb: Int get() = (bytes / 1_000_000L).toInt()
}

object DialogueModels {
    val STANDARD = DialogueModelSpec(
        id = "qwen3-4b-2507-abliterated-q4km",
        label = "Qwen3 4B (abliterated), recommended",
        tier = ModelTier.STANDARD,
        repo = "mradermacher/Huihui-Qwen3-4B-Instruct-2507-abliterated-i1-GGUF",
        file = "Huihui-Qwen3-4B-Instruct-2507-abliterated.i1-Q4_K_M.gguf",
        bytes = 2_497_281_632L,
        sha256 = "0d81e713690710c86ce4c46c4b979d4bba48442ae883ba6e5e281cc8b0674ef6",
        license = "Apache-2.0",
        base = "Qwen/Qwen3-4B-Instruct-2507, abliterated by huihui-ai",
        template = ChatTemplate.CHATML,
        ramMb = 3300,
        estTokensPerSecond = "7 to 12",
        blurb = "4 billion parameters. The best Warcraft knowledge and in-character writing that fits a phone; about 3.3 GB of memory while the game runs.",
    )

    val LITE = DialogueModelSpec(
        id = "qwen3-1.7b-abliterated-v2-q4km",
        label = "Qwen3 1.7B (abliterated), for weaker phones",
        tier = ModelTier.LITE,
        repo = "mradermacher/Huihui-Qwen3-1.7B-abliterated-v2-i1-GGUF",
        file = "Huihui-Qwen3-1.7B-abliterated-v2.i1-Q4_K_M.gguf",
        bytes = 1_107_405_664L,
        sha256 = "578d94267903dc808e476ba8f94f65e5cd8cca29d9a1110b65407d76c56689ac",
        license = "Apache-2.0",
        base = "Qwen/Qwen3-1.7B, abliterated by huihui-ai",
        template = ChatTemplate.CHATML_NO_THINK,
        ramMb = 1500,
        estTokensPerSecond = "15 to 25",
        blurb = "1.7 billion parameters. Twice as fast and half the memory, with simpler lines and less lore.",
    )

    val all: List<DialogueModelSpec> = listOf(STANDARD, LITE)

    fun byId(id: String?): DialogueModelSpec? = all.firstOrNull { it.id == id }

    /** The player's own GGUF (a Qwen-family ChatML model), taken as it is: no download, no checksum. */
    fun custom(path: String, template: ChatTemplate = ChatTemplate.CHATML, bytes: Long = 0): DialogueModelSpec = DialogueModelSpec(
        id = "custom:$path", label = "Your own file", tier = ModelTier.CUSTOM, repo = "", file = path.substringAfterLast('/'),
        bytes = bytes, sha256 = null, license = "as the file's own", base = "chosen by the player", template = template,
        ramMb = (bytes / 1_000_000L * 1.3).toInt(), estTokensPerSecond = "depends on its size", blurb = "A GGUF file you chose. It must speak ChatML (Qwen, Hermes, most fine-tunes of them).",
    )

    /**
     * Which model suits a phone with [totalRamBytes] of memory: the standard one from 10 GB up (it takes about 3.3 GB beside the
     * game, the renderer and the Android system), the lite one below that.
     */
    fun recommended(totalRamBytes: Long): DialogueModelSpec =
        if (totalRamBytes >= 10L * 1024 * 1024 * 1024) STANDARD else LITE

    /** The model's size is checked against the phone's free memory (what Android says is available) with this much to spare. */
    fun fitsMemory(spec: DialogueModelSpec, availableRamBytes: Long, spareMb: Int = 1500): Boolean =
        availableRamBytes >= (spec.ramMb.toLong() + spareMb) * 1_000_000L
}

/** Options → Mods → Dialogue model: what the player chose. Kept as JSON in the app's settings (Settings.dialogue). */
data class DialogueConfig(
    /** On: the model is loaded in the scene and NPCs are answered by it. Off (the default until a model is downloaded): silent. */
    val enabled: Boolean = false,
    /** A [DialogueModelSpec.id]; null: the one [DialogueModels.recommended] for this phone. */
    val modelId: String? = null,
    /** A custom model's path (modelId "custom"). */
    val customPath: String? = null,
    /** Threads for the model: 0 = chosen from the phone's cores (half of them, 2 to 4). */
    val threads: Int = 0,
    /** Tokens of KV cache shared by every NPC (2048 to 8192). */
    val context: Int = 6144,
    /** NPCs that keep their place in the cache at once (2 to 12); the others are saved to a file and read back. */
    val slots: Int = 6,
    /** Tokens a minute that unprompted talk (nearby and background NPCs) may use; the selected NPC is never limited. */
    val ambientTokensPerMinute: Int = 900,
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
                    modelId = if (j.has("modelId")) j.getString("modelId") else null,
                    customPath = if (j.has("customPath")) j.getString("customPath") else null,
                    threads = j.optInt("threads", d.threads).coerceIn(0, 8),
                    context = j.optInt("context", d.context).coerceIn(2048, 8192),
                    slots = j.optInt("slots", d.slots).coerceIn(2, 12),
                    ambientTokensPerMinute = j.optInt("ambientTokensPerMinute", d.ambientTokensPerMinute).coerceIn(0, 6000),
                    mobileData = j.optBoolean("mobileData", d.mobileData),
                )
            }.getOrDefault(DialogueConfig())
        }
    }
}
