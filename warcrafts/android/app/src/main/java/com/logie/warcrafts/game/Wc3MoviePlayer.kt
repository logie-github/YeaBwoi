// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.logie.warcrafts.library.Wc3Movie
import com.winlator.core.ProcessHelper
import java.io.File
import java.io.RandomAccessFile

/**
 * Warcraft III's films, played by the app ([Wc3Movie]: under Wine the game shows them black). When
 * the game opens one of its films (its open files, [Wc3Files]), the game is paused
 * (its processes stopped, as Winlator does when the app goes to the background), the film plays
 * over it with Android's own player (Media3, the phone's MPEG-4 decoder), and when it ends, or is
 * tapped or skipped with Back, the game goes on and is sent Escape, which skips its own (black)
 * playing of the same film.
 */
@OptIn(UnstableApi::class)
class Wc3MoviePlayer(
    private val activity: Activity,
    private val parent: ViewGroup,
    /** The game is to skip its own playing of the film (Escape). */
    private val skipInGame: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var running = true

    private var player: ExoPlayer? = null
    private var screen: FrameLayout? = null
    /** The film playing, by its path. */
    private var playing: String? = null
    /** The film just played: not played again until the game has let go of it. */
    private var played: String? = null
    private var paused = emptyList<Int>()

    /** Whether a film is playing (the game paused under it). */
    val active: Boolean get() = playing != null

    fun stop() {
        running = false
        if (active) finish(skip = false)
    }

    /** Back or a tap while a film plays: it ends there, and the game goes on. */
    fun skip() {
        if (active) finish(skip = true)
    }

    /** The film the game has open now ([Wc3Files.film]), or null. */
    fun seen(film: String?) {
        if (!running) return
        if (film != played) played = null
        if (film == null || active || film == played) return
        play(film)
    }

    private fun play(path: String) {
        val file = File(path)
        val patches = runCatching { RandomAccessFile(file, "r").use { Wc3Movie.patches(it) } }.getOrNull()
        if (patches == null) {
            Wc3Report.note("film ${file.name}: not one the app can play, left to the game")
            played = path
            return
        }
        playing = path
        pauseGame()
        Wc3Report.note("film ${file.name}: played by the app, the game paused")

        val surface = SurfaceView(activity)
        val frame = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            setOnClickListener { skip() }
            addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
        parent.addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screen = frame

        val source = ProgressiveMediaSource.Factory({ PatchedFile(patches) }, DefaultExtractorsFactory())
        val exo = ExoPlayer.Builder(activity).setMediaSourceFactory(source).build()
        exo.setVideoSurfaceView(surface)
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = fit(surface, frame, videoSize)

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) finish(skip = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                Wc3Report.note("film ${file.name}: the phone couldn't play it (${error.errorCodeName}: ${error.cause?.message ?: error.message})")
                finish(skip = true)
            }
        })
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.prepare()
        exo.play()
        player = exo
    }

    /** The film as big as the screen allows at its own shape (800x368: bars above and below). */
    private fun fit(surface: SurfaceView, frame: FrameLayout, size: VideoSize) {
        if (size.width <= 0 || size.height <= 0 || frame.width <= 0 || frame.height <= 0) return
        val aspect = size.width * size.pixelWidthHeightRatio / size.height
        val (w, h) = if (frame.width / aspect <= frame.height) frame.width to (frame.width / aspect).toInt() else (frame.height * aspect).toInt() to frame.height
        surface.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }

    private fun finish(skip: Boolean) {
        val path = playing ?: return
        player?.release()
        player = null
        screen?.let { parent.removeView(it) }
        screen = null
        playing = null
        played = path
        resumeGame()
        // The game had begun its own playing of the film (black): Escape skips it.
        if (skip) main.postDelayed({ skipInGame() }, 150)
        Wc3Report.note("film ${File(path).name}: done, the game goes on")
    }

    /** The game's processes stopped (Wine's and the programs under it), as Winlator does in the background. */
    private fun pauseGame() {
        paused = runCatching {
            ProcessHelper.getChildProcesses()
                .filter { it.guestProcess && it.state != ProcessHelper.PState.STOPPED }
                .map { it.pid }
        }.getOrDefault(emptyList())
        for (pid in paused.reversed()) ProcessHelper.suspendProcess(pid)
    }

    private fun resumeGame() {
        for (pid in paused) ProcessHelper.resumeProcess(pid)
        paused = emptyList()
    }

    /** A film file read with [Wc3Movie]'s changes, so that Android's AVI reader plays it. */
    private class PatchedFile(private val patches: List<Wc3Movie.Patch>) : BaseDataSource(false) {
        private val file = FileDataSource()
        private var position = 0L

        override fun open(dataSpec: DataSpec): Long {
            position = dataSpec.position
            transferInitializing(dataSpec)
            val length = file.open(dataSpec)
            transferStarted(dataSpec)
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = file.read(buffer, offset, length)
            if (n > 0) {
                Wc3Movie.apply(buffer, offset, n, position, patches)
                position += n
                bytesTransferred(n)
            }
            return n
        }

        override fun getUri(): Uri? = file.uri

        override fun close() {
            file.close()
            transferEnded()
        }
    }

}
