package com.miruronative.ui.detail

import com.miruronative.data.model.Media
import com.miruronative.data.model.NextAiringEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailEpisodeCatalogTest {
    @Test
    fun completedAnimeUsesAniListTotal() {
        val episodes = anilistEpisodeCatalog(Media(id = 10, episodes = 3, status = "FINISHED"))

        assertEquals(listOf(1.0, 2.0, 3.0), episodes.map { it.number })
        assertEquals(listOf("anilist:10:1", "anilist:10:2", "anilist:10:3"), episodes.map { it.pipeId })
    }

    @Test
    fun airingAnimeStopsBeforeNextScheduledEpisode() {
        val episodes = anilistEpisodeCatalog(
            Media(id = 11, episodes = 12, nextAiringEpisode = NextAiringEpisode(episode = 4)),
        )

        assertEquals(listOf(1.0, 2.0, 3.0), episodes.map { it.number })
    }

    @Test
    fun nextAiringCountWorksWhenAniListTotalIsMissing() {
        val episodes = anilistEpisodeCatalog(
            Media(id = 12, episodes = null, nextAiringEpisode = NextAiringEpisode(episode = 3)),
        )

        assertEquals(listOf(1.0, 2.0), episodes.map { it.number })
    }

    @Test
    fun absentAniListEpisodeMetadataProducesEmptyCatalog() {
        assertTrue(anilistEpisodeCatalog(Media(id = 13)).isEmpty())
    }
}
