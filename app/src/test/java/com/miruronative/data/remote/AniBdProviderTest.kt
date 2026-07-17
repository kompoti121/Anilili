package com.miruronative.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniBdProviderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesServerGroupsWithZeroPaddedNumbers() {
        val payload = """
            [
              {"server_name":"S-sub","id":10,"server_data":[
                {"name":"01","slug":"01","link":"21eop1web"},
                {"name":"02","slug":"02","link":"21eop2web"},
                {"name":"bad","slug":"x","link":"nope"}
              ]},
              {"server_name":"S-dub","id":11,"server_data":[{"name":"01","slug":"01","link":"21dub1"}]}
            ]
        """.trimIndent()

        val groups = AniBdParser.parseGroups(json.parseToJsonElement(payload))

        assertEquals(listOf("sub", "dub"), groups.map { it.audio })
        assertEquals(mapOf(1 to "21eop1web", 2 to "21eop2web"), groups[0].episodes)
        assertEquals("21dub1", groups[1].episodes.getValue(1))
    }

    @Test
    fun resolvesVideoUrlAgainstPlayerOrigin() {
        assertEquals(
            "https://playeng.animeapps.top/r2/cachehd/21eop1web/index.m3u8",
            AniBdParser.videoUrl(
                """const cfg = { videoUrl: "/r2/cachehd/21eop1web/index.m3u8" };""",
                "https://playeng.animeapps.top",
            ),
        )
        assertNull(AniBdParser.videoUrl("no player here", "https://playeng.animeapps.top"))
    }

    @Test
    fun extractsSoftSubtitleTracksFromPlaysubPages() {
        val html = """
            var art = { tracks: [
                { "label": "English", "file": "https://ani10.nukitashi.top/xyz/sub.vtt", "kind": "captions", "default": true }
            ], subtitle: {} };
        """.trimIndent()

        val subs = AniBdParser.trackSubtitles(html)

        assertEquals("https://ani10.nukitashi.top/xyz/sub.vtt", subs.single().url)
        assertEquals("English", subs.single().label)
        assertEquals("en", subs.single().language)
        // Hardsub play2.php pages carry an empty tracks array.
        assertTrue(AniBdParser.trackSubtitles("tracks: [],").isEmpty())
    }
}
