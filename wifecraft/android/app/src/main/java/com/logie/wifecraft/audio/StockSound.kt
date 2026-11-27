package com.logie.wifecraft.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * The stockings' fabric sounds on the phone (the maths is StockSoundCore): a small low-latency AudioTrack fed by a background thread that mixes
 * the friction voice (its target set by [friction] from the tension and its slope) and the rip ([rip], played over it once). [stop] ends the thread
 * and frees the track: the screen calls it when the mode ends, the sound switch goes off or the app pauses. Not run on a phone.
 */
class StockSound {
    @Volatile private var target = Friction(0f, 1500f, 2f)
    @Volatile private var ripFrom = -1
    @Volatile private var running = false
    private var thread: Thread? = null
    private val ripData = StockSoundCore.renderRip()

    val playing: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "stock-sound").also { it.isDaemon = true; it.start() }
    }

    /** The friction for the tension [tension] changing at [slope] per second. */
    fun friction(tension: Float, slope: Float, pulling: Boolean) { target = StockSoundCore.friction(tension, slope, pulling) }

    /** A rip, now. */
    fun rip() { ripFrom = 0 }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(400) } }
        thread = null
        target = Friction(0f, 1500f, 2f)
        ripFrom = -1
    }

    private fun loop() {
        val rate = StockSoundCore.RATE
        val block = 512
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { running = false; return }
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuf, block * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) { running = false; return }
        val voice = FrictionVoice(3, rate)
        val buf = ShortArray(block)
        try {
            track.play()
            while (running) {
                voice.render(target, buf, block)
                var at = ripFrom
                if (at >= 0) {
                    for (i in 0 until block) {
                        if (at >= ripData.size) { at = -1; break }
                        buf[i] = (buf[i] + ripData[at++]).coerceIn(-32767, 32767).toShort()
                    }
                    ripFrom = at
                }
                track.write(buf, 0, block)
            }
        } catch (e: Exception) {
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            running = false
        }
    }
}
