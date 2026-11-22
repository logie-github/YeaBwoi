package com.logie.wifecraft.creator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Makes the HD textures of the game's own files named by HD_IDS (comma separated file ids) from HD_FILES into HD_OUT, and prints each one's id. Does nothing without them. */
class HdTexturesRealTest {
    @Test fun makesTheRealTextures() {
        val files = System.getenv("HD_FILES") ?: return
        val out = System.getenv("HD_OUT") ?: return
        val ids = System.getenv("HD_IDS")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return
        val game = File(out, "game").apply { mkdirs() }
        val cache = HdTextureCache(File(out, "hdtex"), game) { true }
        for (id in ids) {
            val t0 = System.nanoTime()
            val hd = cache.resolve(id, File(files, id.toString()))
            println("HD $id -> $hd in ${(System.nanoTime() - t0) / 1_000_000} ms")
            assertTrue(hd != id)
        }
    }
}
