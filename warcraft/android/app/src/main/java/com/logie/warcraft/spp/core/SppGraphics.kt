// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp.core

/**
 * The graphics API the 7.3.5 client draws with: it has Direct3D 9, Direct3D 11 and OpenGL renderers
 * (its table of APIs; GLL, Metal and the null device aren't supported on Windows), which on a phone run
 * through Winlator's DXVK (Direct3D) or its OpenGL driver. Direct3D 9 comes first: the phone's 3.3.5a
 * game draws through DXVK's Direct3D 9 on the same drivers. The client takes its gxApi setting only
 * once videoOptionsVersion is 15 (below, it resets it to "auto" at every start, which picks Direct3D 11
 * through DXVK), so the API is given on its command line too ([argument]: -d3d, -d3d11, -opengl,
 * which override the setting). A start whose graphics fail moves on to the next ([next]), told by the
 * client's log of its graphics' start (Logs/gx.log, written anew at each: [failure]); a failure that
 * comes before them is its start-up protection's ("Game Initialization Failed!"), not theirs.
 */
object SppGraphics {
    const val D3D9 = "D3D9"
    const val D3D11 = "D3D11"
    const val OPENGL = "OpenGL"
    val ORDER = listOf(D3D9, D3D11, OPENGL)

    /** The client's command-line switch for [api] (it overrides the setting). */
    fun argument(api: String) = when (valid(api)) {
        D3D11 -> "-d3d11"
        OPENGL -> "-opengl"
        else -> "-d3d"
    }

    /** A start the client's graphics log doesn't show, ended this soon: its start-up protection stopped it. */
    const val PROTECTION_SECONDS = 180L

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

    /** The lines the client writes to gx.log when its graphics device doesn't start (the decrypted client's). */
    private val FAILURES = listOf(
        "ReportDeviceCreationFailure",
        "no output device available",
        "ConsoleDeviceInitialize(): output device does not",
        "failed to create device",
        "unable to LoadLibrary",
        "unable to GetProcAddress",
        "unable to d3dCreateProc",
        "ChoosePixelFormat failed",
        "SetPixelFormat failed",
        "pfd does not match requested",
        "pixelFormat does not match requested",
    )
}
