package com.logie.wifecraft.dialogue

import org.json.JSONObject

/**
 * The dialogue models Options → Dialogue → Dialogue model offers, and the player's settings for them. Pure (JVM-tested:
 * DialogueModelsTest). Nothing here is bundled: the app downloads the file from Hugging Face on the player's request
 * (ModelDownloader), checks its SHA-256 against the one below (the file's own LFS hash on Hugging Face) and loads it with llama.cpp.
 *
 * TWO models, because a 12B model on a phone cannot answer in seconds (the player: "JOI and chat took 90 seconds to respond. That's
 * absurd. It needs to be in less than 5."): reading its prompt takes 15 to 30 tokens a second and writing 4 to 6, and 7.5 GB are
 * read from memory for every token written, so even a 25-token line takes about 5 s of writing alone.
 *
 *  - **Fast** (the default): TheDrummer's Gemmasutra-Mini-2B-v1, a Gemma 2 2B fine-tune made for role-play and explicit talk "on your
 *    mid-tier phone" (UGI leaderboard explicit-writing 8.7 of 10, the best of every model of 1 to 3 B that does not think aloud; no thinking
 *    step), in Gemma's turn format with a `system` turn, in mradermacher's imatrix Q4_K_M. 1.7 GB: four times fewer bytes to read than the 12B
 *    for every token written, and prompts that are read compactly (JoiPrompt, ChatPrompt `compact`). (The first pick, Satyr-V0.1-4B, scored 10
 *    on the same leaderboard but only because it thinks aloud for 4500 characters before it answers: with the think block closed it writes
 *    its reasoning into the line, which a desktop run caught; it was dropped.)
 *  - **Quality (slow)**: inflatebot's MN-12B-Mag-Mell-R1, a merge of Mistral-Nemo-12B role-play, erotica and prose fine-tunes, in
 *    ChatML, in the imatrix Q4_K_M by mradermacher: the richer writer (UGI explicit 7.6, writing 35.8, pop culture 16.2), optional, with
 *    the plain warning that its replies take 15 to 90 seconds on most phones.
 *
 * The research (candidates, scores, sizes, memory and speed estimates) is in the README's "The dialogue model" section.
 */
enum class ModelTier { FAST, QUALITY, CUSTOM }

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
    /** One line on how long a reply takes, for the Options page. */
    val speedNote: String = "",
    /** The prompts for this model are the compact ones (a short persona, two or three examples): a small model reads them in a fraction of the time. */
    val compact: Boolean = false,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$file"
    val page: String get() = "https://huggingface.co/$repo"
    val sizeMb: Int get() = (bytes / 1_000_000L).toInt()
    /** The folder name its saved NPC places are kept in (files/dialogue/state/<this>). */
    val stateName: String get() = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/** How a phone's total memory suits the model: [COMFORTABLE] 13 GiB and up (16 GB phones), [TIGHT] 11.5 to 13, [TOO_SMALL] below that (12 GB phones and less: it may still try). */
enum class RamAdvice { COMFORTABLE, TIGHT, TOO_SMALL }

object DialogueModels {
    /** The default: a 2B model that answers a JOI line or a chat reply in a few seconds once its prompt is read (see the README, "Why replies are slow"). */
    val FAST = DialogueModelSpec(
        id = "gemmasutra-mini-2b-v1-q4km",
        label = "Fast: Gemmasutra Mini 2B (Gemma 2, role-play and explicit talk)",
        tier = ModelTier.FAST,
        repo = "mradermacher/Gemmasutra-Mini-2B-v1-i1-GGUF",
        file = "Gemmasutra-Mini-2B-v1.i1-Q4_K_M.gguf",
        bytes = 1_708_583_136L,
        sha256 = "5588ce78095264076d68d6e0f4a8c5db1cda6c6f9bec972a9936352361cda7a6",
        license = "Gemma Terms of Use (Google's Gemma 2 2B and its fine-tune: you download the file yourself from Hugging Face, the app ships none; the terms and Google's prohibited use policy are yours to read there)",
        base = "google/gemma-2-2b, fine-tuned by TheDrummer for role-play (Gemma turns with a system role)",
        template = ChatTemplate.GEMMA,
        // 1.71 GB weights + 0.35 GB q8 KV cache (6144 tokens x 26 layers x 4 KV heads x 256 x 2 x 34/32 bytes) + about 0.3 GB of buffers
        ramMb = 2400,
        estTokensPerSecond = "15 to 25",
        blurb = "2.6 billion parameters, 1.7 GB. A small role-play model made to write explicit, uncensored talk in character; it knows Warcraft less well than the 12B model, writes plainer prose and forgets a long scene, but it answers in seconds.",
        speedNote = "A line starts in about 1 to 3 seconds and is done in about 3 to 5 on a recent Snapdragon once the NPC is warmed up.",
        compact = true,
    )

    /** The 12B model of the first versions: richer prose and lore, far slower. */
    val QUALITY = DialogueModelSpec(
        id = "mn-12b-mag-mell-r1-q4km",
        label = "Quality (slow): Mag-Mell 12B (Mistral Nemo, role-play and erotica)",
        tier = ModelTier.QUALITY,
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
        speedNote = "Replies take 15 to 90 seconds on most phones (it reads a prompt at 15 to 30 tokens a second and writes 4 to 6). Pick it only if you do not mind waiting.",
        compact = false,
    )

    /** Fast first: the default and the recommendation. */
    val all: List<DialogueModelSpec> = listOf(FAST, QUALITY)

    /** The ids the catalogue had before Mag-Mell (Standard 4B, Lite 1.7B): settings that hold one are moved to [QUALITY] (the player's settings were for the one big model). */
    val legacyIds: Set<String> = setOf("qwen3-4b-2507-abliterated-q4km", "qwen3-1.7b-abliterated-v2-q4km")

    /** The old models' files in files/dialogue/models (and their unfinished `.part` downloads): never loaded, offered for deletion. */
    val legacyFiles: List<String> = listOf(
        "Huihui-Qwen3-4B-Instruct-2507-abliterated.i1-Q4_K_M.gguf",
        "Huihui-Qwen3-1.7B-abliterated-v2.i1-Q4_K_M.gguf",
    )

    /** The old models' files found in [dir] (a model file or its `.part`), with their sizes. */
    fun staleFiles(dir: java.io.File): List<java.io.File> =
        legacyFiles.flatMap { listOf(java.io.File(dir, it), java.io.File(dir, "$it.part")) }.filter { it.isFile }

    /** The catalogue's model for [id]; an old Standard or Lite id gives [QUALITY] (the settings migrate), anything else null. */
    fun byId(id: String?): DialogueModelSpec? = when {
        id == null -> null
        id in legacyIds -> QUALITY
        else -> all.firstOrNull { it.id == id }
    }

    /**
     * The model in use: the one the player picked with the switch when its file is on the phone; else the Fast model when it is there
     * (the default and the recommendation); else the Quality one when only that is there (a phone that had Mag-Mell before there was a
     * choice stays on it until Fast is downloaded); else, with nothing on the phone, what the player picked or Fast (what the Download
     * button fetches).
     */
    fun active(cfg: DialogueConfig, installed: (DialogueModelSpec) -> Boolean): DialogueModelSpec {
        val picked = byId(cfg.modelId)
        if (cfg.modelPicked && picked != null && installed(picked)) return picked
        if (installed(FAST)) return FAST
        if (installed(QUALITY)) return QUALITY
        return if (cfg.modelPicked && picked != null) picked else FAST
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
    /** A [DialogueModelSpec.id] (an old Standard or Lite id is moved to the Quality model when parsed); what [DialogueModels.active] makes of it depends on [modelPicked]. */
    val modelId: String? = null,
    /** True once the player chose a model with the switch (Options -> Dialogue): until then the Fast model is used whenever it is downloaded, and the one that is there otherwise. */
    val modelPicked: Boolean = false,
    /** The thread count the first-run calibration found best for [calibratedFor] (0: not measured yet), used by Speed: Auto. */
    val calibratedThreads: Int = 0,
    val calibratedFor: String? = null,
    /** A custom model's path (modelId "custom"). */
    val customPath: String? = null,
    /** Speed (Options -> Dialogue): 0 = Auto (the phone's performance cores: [ThreadPlan.choose]), else the number of threads, 3 to 6 by hand. */
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

    /** The thread count the calibration found for [modelId], or 0 (none, or found for another model). */
    fun calibrationFor(modelId: String): Int = if (calibratedFor == modelId) calibratedThreads else 0

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).apply {
        modelId?.let { put("modelId", it) }
        put("modelPicked", modelPicked)
        if (calibratedThreads > 0 && calibratedFor != null) { put("calibratedThreads", calibratedThreads); put("calibratedFor", calibratedFor) }
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
                    modelId = if (j.has("modelId")) j.getString("modelId").let { if (it in DialogueModels.legacyIds) DialogueModels.QUALITY.id else it } else null,
                    modelPicked = j.optBoolean("modelPicked", d.modelPicked),
                    calibratedThreads = j.optInt("calibratedThreads", 0).coerceIn(0, 8),
                    calibratedFor = if (j.has("calibratedFor")) j.getString("calibratedFor") else null,
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
