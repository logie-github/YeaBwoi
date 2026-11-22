package com.logie.wifecraft.dialogue

import java.io.File

/** Gets each piece of an answer from the native side as UTF-8 bytes (whole characters); false ends the answer. */
fun interface PieceSink {
    fun onPiece(bytes: ByteArray): Boolean
}

/**
 * The JNI door into libwcdialogue.so (src/main/cpp/dialogue): one llama.cpp model and context for the whole process. Loading
 * the library can fail (an unsupported CPU, a stripped build): [available] is then false and the game goes on without the
 * dialogue model. Every function but [nativeCancel] and [nativeInfo] must be called from one thread (the scheduler's worker).
 * Text crosses as UTF-8 bytes. Compiled by CI for the phone; the native core behind it was run on the desktop
 * (engine-test/dialogue_test.cpp).
 */
object NativeDialogue {
    @JvmField @Volatile var available: Boolean = false
    @JvmField @Volatile var loadError: String? = null

    init {
        try {
            System.loadLibrary("wcdialogue")
            available = true
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
        }
    }

    external fun nativeLoad(path: ByteArray, threads: Int, threadsBatch: Int, ctx: Int, slots: Int, ubatch: Int, kvBits: Int, nice: Int, stateDir: ByteArray): String?
    external fun nativeLoadProgress(): Float
    external fun nativeUnload()
    external fun nativeGenerate(
        ticket: Long, key: ByteArray, prompt: ByteArray, maxTokens: Int, temperature: Float, topP: Float, minP: Float, topK: Int,
        repeatPenalty: Float, seed: Int, minShare: Int, chunkDelayMs: Int, stops: Array<ByteArray>, sink: PieceSink,
    ): String
    external fun nativeCancel(ticket: Long)
    external fun nativeReset(key: ByteArray)
    external fun nativeResetAll()
    external fun nativeFlush(): Int
    external fun nativeInfo(): String?
    external fun nativeCountTokens(text: ByteArray): Int
}

/** What the loaded model says about itself and its memory (NativeDialogue.nativeInfo). */
data class ModelInfo(
    val description: String, val sizeBytes: Long, val params: Long, val trainContext: Int, val context: Int, val slots: Int,
    val liveSlots: Int, val liveTokens: Int, val residentBytes: Long,
) {
    companion object {
        fun parse(s: String?): ModelInfo? {
            val f = s?.split('\t') ?: return null
            if (f.size < 9) return null
            return ModelInfo(f[0], f[1].toLongOrNull() ?: 0, f[2].toLongOrNull() ?: 0, f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: 0,
                f[5].toIntOrNull() ?: 0, f[6].toIntOrNull() ?: 0, f[7].toIntOrNull() ?: 0, f[8].toLongOrNull() ?: 0)
        }
    }
}

/** The native core as a [DialogueBackend] for the scheduler. */
class NativeBackend(private val stateDir: File? = null) : DialogueBackend {
    @Volatile private var isLoaded = false
    override val loaded: Boolean get() = isLoaded

    /** Loads the model (blocking, seconds: call off the main thread). Null when it worked, else why not. */
    fun load(path: File, threads: Int, ctx: Int, slots: Int, kvBits: Int = 8, ubatch: Int = 128, nice: Int = 8): String? {
        if (!NativeDialogue.available) return "the dialogue library is not available (${NativeDialogue.loadError ?: "not built for this phone"})"
        if (!path.isFile) return "the model file is not there"
        stateDir?.mkdirs()
        val err = NativeDialogue.nativeLoad(path.path.toByteArray(), threads, threads + 1, ctx, slots, ubatch, kvBits, nice, (stateDir?.path ?: "").toByteArray())
        isLoaded = err == null
        return err
    }

    fun info(): ModelInfo? = if (isLoaded) ModelInfo.parse(NativeDialogue.nativeInfo()) else null
    fun countTokens(text: String): Int = if (isLoaded) NativeDialogue.nativeCountTokens(text.toByteArray()) else -1

    override fun generate(job: BackendJob): BackendResult {
        if (!isLoaded) return BackendResult(DialogueFinish.ERROR, "no model is loaded")
        // pieces are whole UTF-8 characters, so each decodes on its own
        val sink = PieceSink { b -> job.sink(String(b, Charsets.UTF_8)) }
        val line = NativeDialogue.nativeGenerate(
            job.ticket, job.npcId.toByteArray(), job.prompt.toByteArray(), job.maxTokens, job.temperature, job.topP, 0.05f, 40,
            job.repeatPenalty, job.seed, 24, job.chunkDelayMs, job.stop.map { it.toByteArray() }.toTypedArray(), sink,
        )
        return parseResult(line)
    }

    override fun cancel(ticket: Long) { if (isLoaded) NativeDialogue.nativeCancel(ticket) }
    override fun reset(npcId: String) { if (isLoaded) NativeDialogue.nativeReset(npcId.toByteArray()) }
    override fun resetAll() { if (isLoaded) NativeDialogue.nativeResetAll() }
    override fun flush() { if (isLoaded) NativeDialogue.nativeFlush() }
    override fun close() { if (isLoaded) { isLoaded = false; NativeDialogue.nativeUnload() } }

    companion object {
        /** "finish \t prompt \t reused \t shared \t restored \t generated \t prefillMs \t decodeMs \t error" (cpp/dialogue/dialogue_jni.cpp). */
        fun parseResult(line: String): BackendResult {
            val f = line.split('\t', limit = 9)
            val finish = when (f[0]) {
                "eos" -> DialogueFinish.EOS
                "length" -> DialogueFinish.LENGTH
                "stop" -> DialogueFinish.STOP
                "cancelled" -> DialogueFinish.CANCELLED
                else -> DialogueFinish.ERROR
            }
            fun i(k: Int) = f.getOrNull(k)?.toIntOrNull() ?: 0
            fun d(k: Int) = f.getOrNull(k)?.toDoubleOrNull() ?: 0.0
            return BackendResult(finish, f.getOrNull(8)?.ifEmpty { null }, i(1), i(2), i(3), i(4), i(5), d(6), d(7))
        }
    }
}
