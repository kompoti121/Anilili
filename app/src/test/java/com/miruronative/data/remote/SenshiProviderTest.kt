package com.miruronative.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenshiProviderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesCatalogWithTitlesFillerAndSkipTimes() {
        val payload = """
            [
              {"id":17309,"ep_id":1,"mal_id":21,"ep_title":"I'm Luffy! The Man Who's Gonna Be King of the Pirates!",
               "ep_filler":false,"ep_recap":false,
               "intro_start":28.783,"intro_end":118.783,"outro_start":1387.996,"outro_end":1500},
              {"id":17310,"ep_id":2,"mal_id":21,"ep_title":"","ep_filler":true,
               "intro_start":0,"intro_end":0,"outro_start":0,"outro_end":0}
            ]
        """.trimIndent()

        val catalog = SenshiParser.parseCatalog(json.parseToJsonElement(payload))

        assertEquals(setOf(1, 2), catalog.keys)
        assertEquals("I'm Luffy! The Man Who's Gonna Be King of the Pirates!", catalog.getValue(1).title)
        assertEquals(28.783, catalog.getValue(1).skip!!.introStart!!, 0.001)
        assertEquals(1500.0, catalog.getValue(1).skip!!.outroEnd!!, 0.001)
        assertFalse(catalog.getValue(1).filler)
        // Blank title and 0..0 skip placeholders collapse to null.
        assertNull(catalog.getValue(2).title)
        assertNull(catalog.getValue(2).skip)
        assertTrue(catalog.getValue(2).filler)
    }

    @Test
    fun parsesSubtitleSidecars() {
        val base = "https://ninstream.com/tok/123/uuid"
        // filemoon variant: absolute CDN urls under `src`.
        val fileMoon = SenshiParser.parseSubtitleSidecar(
            json.parseToJsonElement("""[{"src":"https://cdn.ninjstream.xyz/x/sub_2_eng.vtt","label":"ENG","default":true}]"""),
            base,
        )
        assertEquals("https://cdn.ninjstream.xyz/x/sub_2_eng.vtt", fileMoon.single().url)
        assertEquals("en", fileMoon.single().language)

        // artplayer variant: relative .ass files under `url`, plus the site's "none" Off row.
        val artPlayer = SenshiParser.parseSubtitleSidecar(
            json.parseToJsonElement("""[{"url":"sub_2_eng.ass","html":"ENG","default":true},{"url":"none","html":"Off"}]"""),
            base,
        )
        assertEquals("$base/sub_2_eng.ass", artPlayer.single().url)
        assertEquals("ENG", artPlayer.single().label)
    }

    @Test
    fun classifiesEmbedAudioFromStatus() {
        val embeds = json.parseToJsonElement(
            """
            [
              {"url":"https://ninstream.com/a/playlist.m3u8","status":"HardSub"},
              {"url":"https://ninstream.com/b/playlist.m3u8","status":"Dub"},
              {"url":"https://ninstream.com/c/playlist.m3u8","status":null}
            ]
            """.trimIndent(),
        )

        val flags = (embeds as kotlinx.serialization.json.JsonArray)
            .map { SenshiParser.isDub(it as JsonObject) }

        assertEquals(listOf(false, true, false), flags)
    }
}
