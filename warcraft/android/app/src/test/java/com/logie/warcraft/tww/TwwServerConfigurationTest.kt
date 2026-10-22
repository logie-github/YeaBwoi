// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww

import android.app.Application
import com.logie.warcraft.tww.core.TwwInstall
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TwwServerConfigurationTest {
    private fun text(root: File, path: String, value: String = "test") = File(root, path).apply {
        parentFile.mkdirs(); writeText(value)
    }

    private fun exe(root: File, path: String) {
        val bytes = ByteArray(256)
        bytes[0] = 0x4d; bytes[1] = 0x5a
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x3c, 64); putInt(64, 0x00004550); putShort(68, 0x8664.toShort())
        }
        File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
    }

    private fun installation(): File {
        val root = TwwFiles.layout(RuntimeEnvironment.getApplication()).server
        root.deleteRecursively()
        val prefix = "DraconicServer"
        exe(root, "$prefix/bnetserver.exe"); exe(root, "$prefix/worldserver.exe")
        text(root, "$prefix/bnetserver.conf", "LoginDatabaseInfo=\"127.0.0.1;3306;root;test;twwauth\"\nBattlenetPort=1119\nLoginREST.Port=8081\nBindIP=\"0.0.0.0\"\nLoginREST.ExternalAddress=\"public.example\"\nLoginREST.LocalAddress=\"public.example\"\n")
        text(root, "$prefix/worldserver.conf", "RealmID=1\nDataDir=\".\"\nWorldServerPort=8085\nBindIP=\"0.0.0.0\"\nLoginDatabaseInfo=\"127.0.0.1;3306;root;test;twwauth\"\nWorldDatabaseInfo=\"127.0.0.1;3306;root;test;twwworld\"\nCharacterDatabaseInfo=\"127.0.0.1;3306;root;test;twwcharacters\"\nHotfixDatabaseInfo=\"127.0.0.1;3306;root;test;twwhotfixes\"\n")
        exe(root, "$prefix/UniServerZ/UniController.exe")
        text(root, "$prefix/UniServerZ/home/us_config/us_config.ini", "[MYSQL]\n")
        text(root, "$prefix/UniServerZ/home/us_config/us_user.ini", "[USER]\nMYSQL_TCP_PORT=3306\n")
        for (name in listOf("mysql", "old")) {
            for (tool in listOf("mysqld_z", "mysql", "mysqladmin")) exe(root, "$prefix/UniServerZ/core/$name/bin/$tool.exe")
            text(root, "$prefix/UniServerZ/core/$name/my.ini", "[mysqld]\nbind-address=::\n")
            text(root, "$prefix/UniServerZ/core/$name/data/mysql.ibd")
            for (schema in listOf("twwauth", "twwworld", "twwcharacters", "twwhotfixes"))
                text(root, "$prefix/UniServerZ/core/$name/data/$schema/table.ibd")
        }
        exe(root, "$prefix/UniServerZ/core/mysql/bin/mysqld.exe")
        text(root, "$prefix/maps/0000.map", "MAPSformat-version")
        text(root, "$prefix/vmaps/0000.vmtree", "VMAP_4.9contents")
        text(root, "$prefix/mmaps/0000.mmap", "x".repeat(28))
        text(root, "$prefix/mmaps/00000000.mmtile", "PAMM" + "x".repeat(32))
        text(root, "$prefix/dbc/Spell.db2", "WDC5data")
        return root
    }

    @Test fun configuresTheActiveDraconicDatabaseWithoutChangingBackupsOrExecutables() {
        val context = RuntimeEnvironment.getApplication()
        val root = installation()
        try {
            val unchanged = root.walkTopDown().filter { it.isFile && it.extension in setOf("exe", "ibd") }
                .associateWith { it.readBytes() }
            val config = File(root, "DraconicServer/UniServerZ/core/mysql/my.ini")
            val original = config.readText()
            TwwServer.configureLocal(context)
            val report = TwwFiles.report(context)
            assertTrue(report.canBootstrap)
            assertEquals(listOf(TwwInstall.UNKNOWN_BUILD_ISSUE), report.detected?.issues)
            assertEquals(TwwInstall.Status.INSTALLED, report.database.status)
            assertTrue(config.readText().contains("datadir=S:/DraconicServer/UniServerZ/core/mysql/data"))
            assertTrue(config.readText().contains("bind-address=127.0.0.1"))
            assertTrue(config.readText().contains("port=3306"))
            assertEquals(original, File(config.path + ".before-local").readText())
            assertEquals(original, File(root, "DraconicServer/UniServerZ/core/old/my.ini").readText())
            for ((file, bytes) in unchanged) assertArrayEquals(file.path, bytes, file.readBytes())
            TwwServer.configureLocal(context)
            assertEquals(original, File(config.path + ".before-local").readText())
            assertTrue(TwwFiles.report(context).canBootstrap)
        } finally { root.deleteRecursively() }
    }

    @Test fun missingActiveSchemaIsReportedBeforeAnyConfigurationIsChanged() {
        val root = installation()
        try {
            File(root, "DraconicServer/UniServerZ/core/mysql/data/twwworld").deleteRecursively()
            val config = File(root, "DraconicServer/UniServerZ/core/mysql/my.ini")
            val before = config.readText()
            try {
                TwwServer.configureLocal(RuntimeEnvironment.getApplication())
                fail("Missing active tables must block configuration")
            } catch (error: IOException) {
                assertTrue(error.message.orEmpty().contains("Initialized database: twwworld"))
            }
            assertEquals(before, config.readText())
            assertFalse(File(config.path + ".before-local").exists())
        } finally { root.deleteRecursively() }
    }
}
