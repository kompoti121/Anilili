package com.miruronative.data.remote

import com.miruronative.data.model.Media
import com.miruronative.data.model.MediaTitle
import com.miruronative.data.model.StreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HanimeCatalogTest {

    @Test
    fun `slug splits into a series and an episode number`() {
        assertEquals("yabai-fukushuu-yami-site", hanimeSeriesSlug("yabai-fukushuu-yami-site-2"))
        assertEquals(2, hanimeEpisodeNumber("yabai-fukushuu-yami-site-2"))
        assertEquals("rin-x-sen-ran-sem-cross-mix", hanimeSeriesSlug("rin-x-sen-ran-sem-cross-mix-1"))
        assertEquals(12, hanimeEpisodeNumber("stringendo-angel-tachi-no-private-lesson-12"))
    }

    @Test
    fun `an unnumbered slug is a one-shot, not episode zero`() {
        // 6% of the catalogue carries no number; those are single videos and must still play.
        assertEquals("uba", hanimeSeriesSlug("uba"))
        assertEquals(1, hanimeEpisodeNumber("uba"))
    }

    @Test
    fun `a number inside the slug is not mistaken for an episode`() {
        assertEquals("night-shift-nurses-2-karte", hanimeSeriesSlug("night-shift-nurses-2-karte"))
        assertEquals(1, hanimeEpisodeNumber("night-shift-nurses-2-karte"))
    }

    @Test
    fun `normalising folds away the punctuation the two catalogues disagree on`() {
        // Real divergences: hanime writes these without AniList's trailing dot and star.
        assertEquals(
            normalizeHanimeTitle("Maki-chan to Nau."),
            normalizeHanimeTitle("Maki-chan to Nau"),
        )
        assertEquals(normalizeHanimeTitle("I☆Can"), normalizeHanimeTitle("I Can"))
        assertEquals("ningyou no yakata", normalizeHanimeTitle("  Ningyou   no  Yakata  "))
    }

    @Test
    fun `episode numbers come off titles as well as slugs`() {
        assertEquals("Yabai! Fukushuu Yami Site", hanimeTitleWithoutEpisode("Yabai! Fukushuu Yami Site 2"))
        assertEquals("Uba", hanimeTitleWithoutEpisode("Uba"))
    }

    @Test
    fun `similarity is 1 for identical titles and near-1 for punctuation drift`() {
        assertEquals(1.0, titleSimilarity("Ningyou no Yakata", "Ningyou no Yakata"), 0.0001)
        assertEquals(1.0, titleSimilarity("Maki-chan to Nau.", "Maki-chan to Nau"), 0.0001)
        assertTrue(titleSimilarity("Kibun Kibun", "Kibun Kibun") >= HANIME_MATCH_THRESHOLD)
    }

    @Test
    fun `similarity stays low for different shows`() {
        assertTrue(titleSimilarity("Ningyou no Yakata", "Magical Girl Elena") < 0.4)
        assertEquals(0.0, titleSimilarity("", "anything"), 0.0001)
    }

    @Test
    fun `a series matches its AniList entry and returns episodes in order`() {
        val catalogue = listOf(
            video(1, "Yabai! Fukushuu Yami Site 2", "yabai-fukushuu-yami-site-2"),
            video(2, "Yabai! Fukushuu Yami Site 1", "yabai-fukushuu-yami-site-1"),
            video(3, "Ningyou no Yakata", "ningyou-no-yakata"),
        )

        val match = matchHanimeSeries(mediaTitled(romaji = "Yabai! Fukushuu Yami Site"), catalogue)

        assertNotNull(match)
        assertEquals("yabai-fukushuu-yami-site", match!!.seriesSlug)
        assertEquals(listOf(1, 2), match.episodes.map(HanimeVideo::episodeNumber))
    }

    @Test
    fun `the Japanese title rescues a series hanime lists in English`() {
        // This is the fallback that took the measured match rate from 70% to 80%.
        val catalogue = listOf(
            video(1, "Magical Girl Elena 1", "magical-girl-elena-1", searchTitles = "Mahou Shoujo Elena"),
        )

        val byEnglish = matchHanimeSeries(mediaTitled(romaji = "Mahou Shoujo Elena"), catalogue)

        assertNotNull(byEnglish)
        assertEquals("magical-girl-elena", byEnglish!!.seriesSlug)
    }

    @Test
    fun `a title that merely looks similar is refused rather than guessed`() {
        val catalogue = listOf(video(1, "Ningyou no Yakata", "ningyou-no-yakata"))

        assertNull(matchHanimeSeries(mediaTitled(romaji = "Magical Girl Elena"), catalogue))
        assertNull(matchHanimeSeries(mediaTitled(romaji = "Ningyou"), catalogue))
    }

    @Test
    fun `matching an empty or untitled side yields nothing`() {
        assertNull(matchHanimeSeries(mediaTitled(romaji = "Anything"), emptyList()))
        assertNull(matchHanimeSeries(Media(id = 1), listOf(video(1, "Uba", "uba"))))
    }

    @Test
    fun `the best of several candidate series wins`() {
        val catalogue = listOf(
            video(1, "Kibun Kibun", "kibun-kibun"),
            video(2, "Kibun Kibun Extra", "kibun-kibun-extra"),
        )

        val match = matchHanimeSeries(mediaTitled(romaji = "Kibun Kibun"), catalogue)

        assertEquals("kibun-kibun", match?.seriesSlug)
    }

    @Test
    fun `catalogue ids live in a namespace AniList can never collide with`() {
        // AniList ids are always positive, so negating hanime's own id is unambiguous.
        assertEquals(-5, hanimeMediaId(5))
        assertTrue(isHanimeMediaId(hanimeMediaId(5)))
        assertEquals(false, isHanimeMediaId(21))
        assertEquals(5, hanimeVideoId(hanimeMediaId(5)))
    }

    @Test
    fun `a series becomes one media entry carrying its episodes and tags`() {
        val episodes = listOf(
            video(9, "Yabai! Fukushuu Yami Site 2", "yabai-fukushuu-yami-site-2", tags = listOf("bondage")),
            video(8, "Yabai! Fukushuu Yami Site 1", "yabai-fukushuu-yami-site-1", tags = listOf("bondage", "toys")),
        )

        val media = hanimeSeriesAsMedia(episodes)!!

        assertEquals("Yabai! Fukushuu Yami Site", media.title.romaji)
        assertEquals(2, media.episodes)
        assertEquals(hanimeMediaId(8), media.id)   // keyed on episode 1, not whichever came first
        assertTrue(media.isAdult)
        assertEquals(listOf(HANIME_GENRE), media.genres)
        assertEquals(listOf("bondage", "toys"), media.tags.map { it.name })
        assertTrue(media.tags.all { it.isAdult })
    }

    @Test
    fun `the release year is lifted out of the ISO stamp`() {
        assertEquals(2014, hanimeReleaseYear("2014-04-25T00:00:05.000Z"))
        assertNull(hanimeReleaseYear(""))
        assertNull(hanimeReleaseYear("not-a-date"))
    }

    @Test
    fun `searching the catalogue ranks exact over prefix over fuzzy`() {
        val catalogue = listOf(
            video(1, "Kibun Kibun", "kibun-kibun"),
            video(2, "Kibun Kibun Extra", "kibun-kibun-extra"),
            video(3, "Ningyou no Yakata", "ningyou-no-yakata"),
        )

        val hits = searchHanimeCatalogue("kibun kibun", catalogue)

        assertEquals("Kibun Kibun", hits.first().title.romaji)
        assertEquals(2, hits.size)                       // the unrelated title is not a result
        assertTrue(hits.all(com.miruronative.data.model.Media::isAdult))
    }

    @Test
    fun `an unrelated query returns nothing rather than the closest thing`() {
        val catalogue = listOf(video(1, "Ningyou no Yakata", "ningyou-no-yakata"))

        assertTrue(searchHanimeCatalogue("attack on titan", catalogue).isEmpty())
        assertTrue(searchHanimeCatalogue("   ", catalogue).isEmpty())
    }

    @Test
    fun `streams are ordered sharpest first`() {
        val streams = listOf(stream(720), stream(1080), stream(480), stream(null))

        assertEquals(listOf(1080, 720, 480, null), sortHanimeStreams(streams).map(StreamItem::height))
    }

    private fun mediaTitled(romaji: String? = null, english: String? = null, native: String? = null) =
        Media(id = 1, title = MediaTitle(romaji = romaji, english = english, native = native))

    private fun video(
        id: Int,
        name: String,
        slug: String,
        searchTitles: String = "",
        tags: List<String> = emptyList(),
    ) = HanimeVideo(id = id, name = name, slug = slug, searchTitles = searchTitles, tags = tags)

    private fun stream(height: Int?) = StreamItem(
        url = "https://example/$height.m3u8",
        type = "hls",
        quality = "${height ?: "auto"}p",
        audio = null,
        referer = null,
        isActive = false,
        width = null,
        height = height,
    )
}
