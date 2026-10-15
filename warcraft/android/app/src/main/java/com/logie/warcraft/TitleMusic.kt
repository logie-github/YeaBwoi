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
 * The launcher's music, the theme of the game it shows ([Environments]): Wrath of the Lich King's
 * main title (res/raw/wow_title_music.ogg), or for Turtle WoW, Moonwhisper Coast (Turtle WoW's,
 * res/raw/turtle_title_music.ogg), looping while the launcher is on screen.
 * Switching games fades one into the other ([onGameChanged]). It fades in when the launcher shows and pauses when it's left,
 * when another app plays sound or a call comes. Once the game starts ([stopForGame]) it stops for
 * as long as the app runs. The note in the header turns it off and on, remembered.
 *
 * Main thread only.
 */
object TitleMusic {
    /** Each game's theme, at its ReplayGain (as loud as other music, not louder). */
    private enum class Track(val res: Int, val volume: Float) {
        /** -5.5 dB. */
        WRATH(R.raw.wow_title_music, 0.53f),
        /** -2.5 dB. */
        TURTLE(R.raw.turtle_title_music, 0.75f),
    }

    private const val FADE_IN_MS = 2_500L
    private const val FADE_OUT_MS = 700L
    private const val STEP_MS = 40L

    private const val PREFS = "music"
    private const val KEY_ON = "on"

    private val handler = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    private var player: MediaPlayer? = null
    /** The theme [player] plays. */
    private var playing: Track? = null
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

    /** The launcher switched games: the other game's theme fades out, this one's fades in. */
    fun onGameChanged(context: Context) {
        if (player == null || playing == trackFor(context)) return
        fadeTo(0f, FADE_OUT_MS) {
            release()
            play(context)
        }
    }

    private fun trackFor(context: Context): Track = when (Environments.current(context)) {
        Environments.Environment.TURTLE -> Track.TURTLE
        Environments.Environment.WRATH -> Track.WRATH
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
        val track = trackFor(context)
        val current = player ?: runCatching {
            MediaPlayer.create(context.applicationContext, track.res, attributes, audioManager.generateAudioSessionId())
        }.getOrNull()?.apply {
            isLooping = true
            setVolume(0f, 0f)
            level = 0f
        }?.also {
            player = it
            playing = track
        } ?: return
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
        playing = null
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
            val volume = level * level * (playing?.volume ?: 0f)
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
