package com.logie.warcraft.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientConfigTest {
    @Test
    fun newConfigHasTheSettings() {
        val text = ClientConfig.updateConfig("", linkedMapOf("realmList" to "127.0.0.1", "movie" to "0"))
        assertEquals("SET realmList \"127.0.0.1\"\r\nSET movie \"0\"\r\n", text)
    }

    @Test
    fun settingsChangeInPlaceAndTheRestStays() {
        val old = "SET locale \"enUS\"\nSET realmlist \"logon.example.com\"\nSET gxResolution \"1024x768\"\nSET Gamma \"1.0\"\n"
        val text = ClientConfig.updateConfig(old, linkedMapOf("realmList" to "127.0.0.1", "gxResolution" to "1600x720", "movie" to "0"))
        assertEquals(
            "SET locale \"enUS\"\nSET realmlist \"127.0.0.1\"\nSET gxResolution \"1600x720\"\nSET Gamma \"1.0\"\nSET movie \"0\"\n",
            text,
        )
    }

    @Test
    fun everyCopyOfASettingChanges() {
        val text = ClientConfig.updateConfig("SET realmName \"Old\"\r\nSET realmName \"Older\"\r\n", mapOf("realmName" to "ChromieCraft"))
        assertEquals("SET realmName \"ChromieCraft\"\r\nSET realmName \"ChromieCraft\"\r\n", text)
    }

    @Test
    fun otherLinesStay() {
        val text = ClientConfig.updateConfig("# a note\nSET x \"1\"\n\n", mapOf("y" to "2"))
        assertEquals("# a note\nSET x \"1\"\nSET y \"2\"\n", text)
    }

    @Test
    fun resolutionIsTheScreensShapeAt720Lines() {
        // A phone's tall screen, given either way round.
        assertEquals(1680 to 720, ClientConfig.resolution(1080, 2520))
        assertEquals(1680 to 720, ClientConfig.resolution(2520, 1080))
        // A Fold's inner screen, nearly square: no narrower than the client allows.
        assertEquals(800 to 720, ClientConfig.resolution(2184, 1968))
        // Even widths.
        assertEquals(0, ClientConfig.resolution(2400, 1081).first % 2)
    }

    @Test
    fun localeFolders() {
        assertTrue(ClientConfig.LOCALE.matches("enUS"))
        assertTrue(ClientConfig.LOCALE.matches("deDE"))
        assertFalse(ClientConfig.LOCALE.matches("Interface"))
        assertFalse(ClientConfig.LOCALE.matches("patch"))
        assertEquals("set realmlist 127.0.0.1\r\n", ClientConfig.realmlist("127.0.0.1"))
    }
}
