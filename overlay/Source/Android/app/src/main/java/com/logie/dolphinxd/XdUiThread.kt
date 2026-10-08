// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch

/** Runs Dolphin config changes on the main thread from background tasks. */
object XdUiThread {
    fun runBlocking(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val done = CountDownLatch(1)
        var error: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                done.countDown()
            }
        }
        done.await()
        error?.let { throw it }
    }
}
