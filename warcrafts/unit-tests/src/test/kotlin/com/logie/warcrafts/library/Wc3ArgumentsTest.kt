package com.logie.warcrafts.library

import kotlin.test.Test
import kotlin.test.assertEquals

class Wc3ArgumentsTest {
    @Test
    fun `the game starts fullscreen in 1_30's words and 1_31's`() {
        assertEquals("-windowmode fullscreen -nativefullscr", Wc3Arguments.of("", openGl = false))
    }

    @Test
    fun `OpenGL is added after the user's own arguments`() {
        assertEquals(
            "-loadfile Maps/x.w3x -windowmode fullscreen -nativefullscr -opengl -graphicsapi OpenGL",
            Wc3Arguments.of("  -loadfile Maps/x.w3x ", openGl = true),
        )
    }

    @Test
    fun `a window mode the user typed takes the place of fullscreen`() {
        assertEquals("-window", Wc3Arguments.of("-window", openGl = false))
        assertEquals("-windowmode windowedfullscreen", Wc3Arguments.of("-windowmode windowedfullscreen", openGl = false))
        assertEquals("-FULLSCREEN", Wc3Arguments.of("-FULLSCREEN", openGl = false))
    }

    @Test
    fun `a renderer the user typed isn't doubled`() {
        assertEquals("-d3d -windowmode fullscreen -nativefullscr", Wc3Arguments.of("-d3d", openGl = true))
    }

    @Test
    fun `a map named like a flag isn't taken for one`() {
        assertEquals(
            "-loadfile window-maps/a.w3m -windowmode fullscreen -nativefullscr",
            Wc3Arguments.of("-loadfile window-maps/a.w3m", openGl = false),
        )
    }
}
