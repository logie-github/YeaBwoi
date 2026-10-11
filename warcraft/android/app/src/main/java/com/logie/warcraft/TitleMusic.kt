// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The launcher's music: Wrath of the Lich King's main title (res/raw/wow_title_music.ogg), looping
 * while the launcher is on screen. It fades in when the launcher shows and pauses when it's left,
 * when another app plays sound or a call comes. Once the game starts ([stopForGame]) it stops for
 * as long as the app runs. The note in the header turns it off and on, remembered.
 *
 * Main thread only.
 */
object TitleMusic {
    /** The track's ReplayGain (-5.5 dB): as loud as other music, not louder. */
    private const val VOLUME = 0.53f
    private const val FADE_IN_MS = 2_500L
    private const val FADE_OUT_MS = 700L
    private const val STEP_MS = 40L

    private const val PREFS = "music"
    private const val KEY_ON = "on"

    private val handler = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var level = 0f
    private var target = 0f
    private var step = 0f
    private var afterFade: (() -> Unit)? = null

    /** The launcher is on screen. */
    private var shown = false
    /** The game started: no more music until the app is opened anew. */
    private var gameStarted = false

    private val _on = MutableStateFlow(true)
    /** The user's switch. */
    val on: StateFlow<Boolean> = _on

    fun load(context: Context) {
        _on.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)
    }

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        _on.value = on
        if (on) play(context) else pause()
    }

    /** The launcher came on screen. */
    fun onShown(context: Context) {
        shown = true
        play(context)
    }

    /** The launcher went off screen (another app, the home screen, the screen off). */
    fun onHidden() {
        shown = false
        pause()
    }

    /** The game is starting: the music fades out and stops for as long as the app runs. */
    fun stopForGame() {
        gameStarted = true
        fadeTo(0f, FADE_OUT_MS) { release() }
    }

    private fun play(context: Context) {
        if (!shown || !_on.value || gameStarted) return
        val audioManager = audio ?: (context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { audio = it }
        val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(::onFocusChange, handler)
            .build()
            .also { focus = it }
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        val current = player ?: runCatching {
            MediaPlayer.create(context.applicationContext, R.raw.wow_title_music, attributes, audioManager.generateAudioSessionId())
        }.getOrNull()?.apply {
            isLooping = true
            setVolume(0f, 0f)
            level = 0f
        }?.also { player = it } ?: return
        if (!current.isPlaying) current.start()
        fadeTo(1f, FADE_IN_MS, null)
    }

    private fun pause() {
        fadeTo(0f, FADE_OUT_MS) {
            player?.let { if (it.isPlaying) it.pause() }
            focus?.let { audio?.abandonAudioFocusRequest(it) }
        }
    }

    private fun release() {
        player?.release()
        player = null
        focus?.let { audio?.abandonAudioFocusRequest(it) }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (shown && _on.value && !gameStarted) player?.let {
                if (!it.isPlaying) it.start()
                fadeTo(1f, FADE_IN_MS, null)
            }
            // A call, another app's music or video: quiet until it's done. (Ducking under a
            // notification's sound Android does by itself.)
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                fadeTo(0f, FADE_OUT_MS) { player?.let { if (it.isPlaying) it.pause() } }
        }
    }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Fades the volume to [to] over [ms], then runs [then]. A new fade replaces one under way. */
    private fun fadeTo(to: Float, ms: Long, then: (() -> Unit)?) {
        handler.removeCallbacks(fading)
        target = to
        step = (STEP_MS.toFloat() / ms).coerceAtLeast(0.001f)
        afterFade = then
        handler.post(fading)
    }

    private val fading = object : Runnable {
        override fun run() {
            val current = player
            if (current == null) {
                afterFade?.invoke()
                afterFade = null
                return
            }
            level = if (level < target) (level + step).coerceAtMost(target) else (level - step).coerceAtLeast(target)
            // Squared: a fade that sounds even to the ear.
            val volume = level * level * VOLUME
            current.setVolume(volume, volume)
            if (level != target) {
                handler.postDelayed(this, STEP_MS)
            } else {
                afterFade?.invoke()
                afterFade = null
            }
        }
    }
}
