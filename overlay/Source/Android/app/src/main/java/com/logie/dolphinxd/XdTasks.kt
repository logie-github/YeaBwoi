// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.databinding.XdViewTaskBinding
import org.dolphinemu.dolphinemu.utils.Log
import kotlin.math.roundToInt

/** A task failed in a way the player should be told about, with [message] as the explanation. */
class XdTaskException(message: String) : Exception(message)

/** The player pressed Cancel. */
class XdTaskCancelledException : Exception()

/**
 * Runs one long import or patch at a time, independent of any activity, so it keeps going when
 * the screen rotates or the player moves between Dolphin XD's screens.
 */
object XdTasks {
    data class State(
        val title: String,
        val message: String = "",
        /** 0..1, or null while the length is unknown. */
        val progress: Float? = null,
        val finished: Boolean = false,
        val result: String? = null,
        val error: String? = null,
        val cancelled: Boolean = false,
    )

    class Progress internal constructor(private val title: String) {
        @Volatile
        var isCancelled = false
            internal set

        fun update(message: String, progress: Float? = null) {
            if (!isCancelled) {
                mutableState.value = State(title, message, progress?.coerceIn(0f, 1f))
            }
        }

        fun throwIfCancelled() {
            if (isCancelled) throw XdTaskCancelledException()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<State?>(null)
    private var current: Progress? = null

    val state: StateFlow<State?> = mutableState

    val isRunning: Boolean
        get() = mutableState.value?.finished == false

    /** Starts [work] unless another task is running. Its return value is shown when it's done. */
    fun start(title: String, work: suspend (Progress) -> String): Boolean {
        if (isRunning) return false
        val progress = Progress(title)
        current = progress
        mutableState.value = State(title)
        scope.launch {
            mutableState.value = try {
                State(title, finished = true, result = work(progress))
            } catch (_: XdTaskCancelledException) {
                State(title, finished = true, cancelled = true)
            } catch (e: XdTaskException) {
                State(title, finished = true, error = e.message)
            } catch (e: Exception) {
                Log.error("[Dolphin XD] $title failed: $e")
                State(title, finished = true, error = e.message ?: e.toString())
            }
        }
        return true
    }

    fun cancel() {
        current?.isCancelled = true
        mutableState.value?.let {
            if (!it.finished) mutableState.value = it.copy(message = "…")
        }
    }

    /**
     * Shows the running task in [card] (if given), keeps the screen on while it runs, and shows
     * the result in a dialog when it finishes while [activity] is visible.
     */
    fun observe(
        activity: AppCompatActivity,
        card: XdViewTaskBinding?,
        onChanged: (State?) -> Unit = {},
        onFinished: () -> Unit = {},
    ) {
        card?.xdTaskCancel?.setOnClickListener { cancel() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.collect { state ->
                    val running = state != null && !state.finished
                    if (running) {
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    card?.let { bind(it, state) }
                    onChanged(state)
                    if (state != null && state.finished) {
                        // Only one screen shows the result, even if two are briefly visible.
                        if (mutableState.compareAndSet(state, null)) {
                            showResult(activity, state)
                        }
                        onFinished()
                    }
                }
            }
        }
    }

    private fun bind(card: XdViewTaskBinding, state: State?) {
        if (state == null || state.finished) {
            card.root.visibility = View.GONE
            return
        }
        card.root.visibility = View.VISIBLE
        card.xdTaskTitle.text = state.title
        val progress = state.progress
        if (progress == null) {
            card.xdTaskMessage.text = state.message
            card.xdTaskProgress.isIndeterminate = true
        } else {
            val percent = (progress * 100).roundToInt()
            card.xdTaskMessage.text =
                card.root.context.getString(R.string.xd_task_percent, state.message, percent)
            card.xdTaskProgress.isIndeterminate = false
            card.xdTaskProgress.setProgressCompat((progress * 1000).roundToInt(), true)
        }
    }

    private fun showResult(activity: AppCompatActivity, state: State) {
        if (state.cancelled) {
            android.widget.Toast.makeText(activity, R.string.xd_task_cancelled, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (state.error != null) activity.getString(R.string.xd_task_failed) else state.title)
            .setMessage(state.error ?: state.result)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
