package com.miruronative.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wrapping around Hentai Haven's stream is the fragile part — it is obfuscation, so it is what
 * upstream is most likely to change silently. These cover it without touching the network.
 */
class HentaiHavenParserTest {
    @Test
    fun rot13LeavesBase64PunctuationAlone() {
        // The whole scheme depends on this: `_r` runs over base64 text, so digits, `+`, `/` and
        // the `=` padding have to survive or the decode that follows corrupts.
        assertEquals("nopqrstuvwxyzabcdefghijklm", HentaiHavenParser.rot13("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("NOPQRSTUVWXYZABCDEFGHIJKLM", HentaiHavenParser.rot13("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
        assertEquals("0123456789+/=", HentaiHavenParser.rot13("0123456789+/="))
    }

    @Test
    fun rot13IsItsOwnInverse() {
        val value = "Onaji Zemi no Someya-san +/=42"
        assertEquals(value, HentaiHavenParser.rot13(HentaiHavenParser.rot13(value)))
    }

    /** Encoded with the site's own scheme: base64-then-ROT13, three rounds, `sha512-` prefixed. */
    @Test
    fun decodesSecureTokenToItsJson() {
        val token = "sha512-pUceF3WXBGWWq01Xpxt5D0M4qJIRZRIJEIEkH0MEEGWXFH9YGQAGZxy3GHclFQyQ" +
            "Eau1MHDjEIMSIUSHExueZxcWG0gZrwugERqCFaRlImWirHIKoyIGFRtlDJuWH1cfE0uvZT5II0ykE0IXGRqBBD=="
        val decoded = HentaiHavenParser.decodeSecureToken(token)
        assertEquals("""{"en":"PAYLOAD-EN","iv":"PAYLOAD-IV","host":"hentaihaven.xxx"}""", decoded)
    }

    @Test
    fun rejectsATokenThatIsNotTheEncodedConfig() {
        // A real digest would decode to noise rather than JSON; better to fail than feed the
        // player API garbage.
        assertNull(HentaiHavenParser.decodeSecureToken("sha512-not-actually-a-token"))
    }

    @Test
    fun readsSecureTokenFromPlayerPage() {
        val html = """<meta name="robots" content="noindex" />
            <meta name="x-secure-token" content="sha512-AbCdEf==" />"""
        assertEquals("sha512-AbCdEf==", HentaiHavenParser.secureToken(html))
    }

    @Test
    fun readsPlayerDataParamFromEpisodeIframe() {
        val html = """<iframe src="https://hentaihaven.xxx/wp-content/plugins/player-logic/""" +
            """player.php?data=VWJJVkJ0TG1yWlZv&lang=en" allowfullscreen></iframe>"""
        assertEquals("VWJJVkJ0TG1yWlZv", HentaiHavenParser.playerDataParam(html))
    }

    @Test
    fun collectsEpisodeNumbersFromSeriesPage() {
        val html = """
            <option data-redirect="https://hentaihaven.xxx/watch/some-slug/episode-1">Episode 1</option>
            <option data-redirect="https://hentaihaven.xxx/watch/some-slug/episode-2">Episode 2</option>
            <a href="https://hentaihaven.xxx/watch/some-slug/episode-3">Episode 3</a>
        """
        assertEquals(setOf(1, 2, 3), HentaiHavenParser.episodeNumbers(html, "some-slug"))
    }

    @Test
    fun episodeNumbersIgnoreOtherSeriesLinkedInSidebar() {
        // A real series page carries "Popular" and "See More" widgets that link straight to other
        // series' episodes. Counting those gave a two-episode title a phantom third episode.
        val html = """
            <a href="https://hentaihaven.xxx/watch/furachi/episode-1">Episode 1</a>
            <a href="https://hentaihaven.xxx/watch/furachi/episode-2">Episode 2</a>
            <aside class="widget popular">
              <a href="https://hentaihaven.xxx/watch/onaji-zemi-no-someya-san/episode-3">Episode 3</a>
              <a href="https://hentaihaven.xxx/watch/nuki-nuki-zupposism/episode-1">Episode 1</a>
            </aside>
        """
        assertEquals(setOf(1, 2), HentaiHavenParser.episodeNumbers(html, "furachi"))
    }

    @Test
    fun searchSlugsDropWordPressFeedEntry() {
        val html = """
            <a href="https://hentaihaven.xxx/watch/feed/">Feed</a>
            <a href="https://hentaihaven.xxx/watch/onaji-zemi-no-someya-san/">Result</a>
            <a href="https://hentaihaven.xxx/watch/onaji-zemi-no-someya-san/">Duplicate</a>
        """
        assertEquals(listOf("onaji-zemi-no-someya-san"), HentaiHavenParser.searchSlugs(html))
    }

    @Test
    fun searchQueryDropsTrailingPunctuationAniListCarries() {
        // Measured against the live site: the trailing "." returns zero results, and so does "…".
        assertEquals(
            "Onaji Zemi no Someya-san",
            HentaiHavenParser.searchQuery("Onaji Zemi no Someya-san ga Sexy Joyuu Datta Hanashi."),
        )
        assertEquals("Gomu o Tsukete to", HentaiHavenParser.searchQuery("Gomu o Tsukete to Iimashita yo ne…"))
    }

    @Test
    fun searchQueryKeepsInternalPunctuation() {
        // Flattening "Someya-san" to "Someya san" also returns zero results, so the hyphen has to
        // survive into the query even though the scoring afterwards ignores it.
        assertTrue(HentaiHavenParser.searchQuery("Onaji Zemi no Someya-san ga Sexy").contains("Someya-san"))
    }

    @Test
    fun searchQueryLeavesShortTitlesWhole() {
        assertEquals("Furachi", HentaiHavenParser.searchQuery("Furachi"))
        assertEquals("Toriko no Chigiri", HentaiHavenParser.searchQuery("Toriko no Chigiri"))
    }

    @Test
    fun slugMatchesTheAniListRomajiItCameFrom() {
        // How a series is bound to an AniList entry, since Hentai Haven carries no MAL or
        // AniList id. Punctuation is the only thing the two disagree on here.
        val score = titleSimilarity(
            "Onaji Zemi no Someya-san ga Sexy Joyuu Datta Hanashi",
            HentaiHavenProvider.slugAsTitle("onaji-zemi-no-someya-san-ga-sexy-joyuu-datta-hanashi"),
        )
        assertTrue("expected a confident match, got $score", score >= 0.85)
    }

    @Test
    fun unrelatedTitleDoesNotClearTheMatchBar() {
        val score = titleSimilarity(
            "Frieren: Beyond Journey's End",
            HentaiHavenProvider.slugAsTitle("onaji-zemi-no-someya-san-ga-sexy-joyuu-datta-hanashi"),
        )
        assertTrue("a wrong match is worse than none, got $score", score < 0.85)
    }
}
