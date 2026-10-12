package com.logie.warcraft.addons

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WarperiaDownloadTest {
    private val waitingPage = javaClass.getResource("/warperia/download.html")!!.readText()
    private val answer = """{"success":true,"data":{"download_url":"https:\/\/warperia.com\/?wdl_token=T"}}"""

    /** The site, with a refusal for the first [refusals] downloads. */
    private inner class Site(var refusals: Int = 0) : Web {
        val log = mutableListOf<String>()
        override fun get(url: String): String { log += "get $url"; return waitingPage }
        override fun post(url: String, form: List<Pair<String, String>>, referer: String): String {
            log += "post $url ${form.joinToString("&") { "${it.first}=${it.second}" }} from $referer"
            return answer
        }
        override fun save(url: String, to: File, progress: (Long, Long) -> Unit) {
            log += "save $url"
            if (refusals-- > 0) throw HttpException(403)
            progress(10, 20)
            progress(20, 20)
        }
    }

    private val to = File("unused.zip")

    @Test
    fun waitsAsTheSiteAsksThenDownloads() {
        val site = Site()
        val steps = mutableListOf<String>()
        WarperiaDownload.fetch(site, "postal", to, onStep = { steps += it.toString() }, sleep = { steps += "sleep $it" })
        assertEquals(
            listOf(
                "get https://warperia.com/addon-wotlk/postal/download/",
                "post https://warperia.com/wp-admin/admin-ajax.php action=increment_addon_download&addon_id=1190&nonce=87d8448745 from https://warperia.com/addon-wotlk/postal/download/",
                "save https://warperia.com/?wdl_token=T",
            ),
            site.log,
        )
        assertEquals(5, steps.count { it == "sleep 1000" })
        assertEquals("Waiting(secondsLeft=5)", steps.first())
        assertTrue(steps.indexOf("Waiting(secondsLeft=1)") < steps.indexOf("Downloading(bytes=10, size=20)"))
    }

    @Test
    fun asksForTheLinkAgainOnceWhenRefused() {
        val site = Site(refusals = 1)
        WarperiaDownload.fetch(site, "postal", to, onStep = {}, sleep = {})
        assertEquals(2, site.log.count { it.startsWith("post") })
        assertEquals(2, site.log.count { it.startsWith("save") })

        val stubborn = Site(refusals = 5)
        val error = assertFailsWith<HttpException> { WarperiaDownload.fetch(stubborn, "postal", to, onStep = {}, sleep = {}) }
        assertEquals(403, error.code)
        assertEquals(2, stubborn.log.count { it.startsWith("save") })
    }
}
