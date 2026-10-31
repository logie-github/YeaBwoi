package com.logie.warcraft.client

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaseModelFilesTest {
    @Test
    fun theSwitchIsTheFileTheRealmLooksFor() {
        val root = Files.createTempDirectory("base-model").toFile()
        try {
            assertFalse(BaseModelFiles.isOn(root))
            BaseModelFiles.setOn(root, true)
            assertTrue(BaseModelFiles.isOn(root))
            // where mod-base-model looks: beside the game's folder (files/client), base-model/enabled
            assertTrue(File(root, "base-model/enabled").isFile)
            BaseModelFiles.setOn(root, false)
            assertFalse(BaseModelFiles.isOn(root))
            BaseModelFiles.setOn(root, false)
            assertFalse(File(root, "base-model/enabled").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
