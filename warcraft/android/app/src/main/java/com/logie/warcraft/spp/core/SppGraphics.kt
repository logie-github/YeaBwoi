// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp.core

/**
 * The graphics API the 7.3.5 client draws with (its Config.wtf's gxApi): it has Direct3D 9, Direct3D 11
 * and OpenGL renderers. On a phone they run through Winlator's DXVK (Direct3D) or its OpenGL driver;
 * one the drivers can't give it stops the client at "Game Initialization Failed!" (a phone's, with
 * Direct3D 11). Direct3D 9 comes first: the phone's 3.3.5a game draws through DXVK's Direct3D 9 on
 * the same drivers. A start that fails moves on to the next ([next]), told by the client's own log of
 * its graphics' start (Logs/gx.log: [failure]).
 */
object SppGraphics {
    const val D3D9 = "D3D9"
    const val D3D11 = "D3D11"
    const val OPENGL = "OpenGL"
    val ORDER = listOf(D3D9, D3D11, OPENGL)

    /** A game that ran this long started its graphics: its end isn't their failure. */
    const val START_SECONDS = 180L

    fun name(api: String) = when (api) {
        D3D11 -> "Direct3D 11"
        OPENGL -> "OpenGL"
        else -> "Direct3D 9"
    }

    /** [api] if it's one of [ORDER], else the first. */
    fun valid(api: String?) = api?.takeIf { it in ORDER } ?: ORDER.first()

    /** The API tried after [api] failed to start, null after the last. */
    fun next(api: String): String? = ORDER.getOrNull(ORDER.indexOf(valid(api)) + 1)

    /** What the client's gx.log ([log]) says when its graphics didn't start (its last such line), else null. */
    fun failure(log: String): String? = log.lineSequence().map { it.trim() }
        .lastOrNull { line -> FAILURES.any { line.contains(it, ignoreCase = true) } }

    private val FAILURES = listOf(
        "failed to create device",
        "not supported on adapter",
        "unable to LoadLibrary",
        "unable to GetProcAddress",
        "unable to d3dCreateProc",
        "Failed to get DXGI Adapter Description",
        "Failed to get adapter identifier",
    )
}
