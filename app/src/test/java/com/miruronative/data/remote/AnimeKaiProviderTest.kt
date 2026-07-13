package com.miruronative.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeKaiProviderTest {
    @Test
    fun parsesSearchCardsAndMalIdentity() {
        val html = """
            <div class="aitem">
              <a class="poster" href="/watch/jujutsu-kaisen-2nd-season/ep-1"></a>
              <a class="title d-title" data-en="Jujutsu Kaisen 2nd Season">JJK 2</a>
            </div>
            <a href="https://myanimelist.net/anime/51009">MAL</a>
        """.trimIndent()

        val card = AnimeKaiParser.parseCards(html).single()

        assertEquals("jujutsu-kaisen-2nd-season", card.slug)
        assertEquals("Jujutsu Kaisen 2nd Season", card.title)
        assertEquals(51009, AnimeKaiParser.malId(html))
    }

    @Test
    fun parsesLanguageServersAndPlayerAssets() {
        val subtitle = "https%3A%2F%2Fcdn.test%2Fenglish.vtt"
        val html = """
            <div class="server-items lang-group" data-id="hsub">
              <span class="server-video" data-video="https://vivibebe.site/e/abc?sub=$subtitle&amp;sub_1=English">Vid</span>
            </div>
            <div class="server-items lang-group" data-id="dub">
              <span class="server-video" data-video="https://vivibebe.site/e/xyz">Vid</span>
            </div>
        """.trimIndent()

        val servers = AnimeKaiParser.parseServers(html)
        val subtitles = AnimeKaiParser.embedSubtitles(servers.first().videoUrl)

        assertEquals(listOf("hsub", "dub"), servers.map { it.language })
        assertEquals("https://cdn.test/english.vtt", subtitles.single().url)
        assertEquals("en", subtitles.single().language)
        assertEquals(
            "https://cdn.test/master.m3u8?token=abc",
            AnimeKaiParser.findHls("const src = \"https:\\/\\/cdn.test\\/master.m3u8?token=abc\";"),
        )
    }
}
