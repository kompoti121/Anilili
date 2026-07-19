package com.miruronative.ui.watch

import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.EpisodesResult
import com.miruronative.data.model.ProviderData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSourceOptionsTest {
    @Test
    fun excludesServersWithoutCurrentEpisodeAndAudioModesUnavailableOnIt() {
        val catalog = EpisodesResult(
            listOf(
                ProviderData(
                    name = "kiwi",
                    sub = listOf(episode(1)),
                    dub = listOf(episode(3)),
                ),
                ProviderData(
                    name = "senshi",
                    sub = listOf(episode(1), episode(2)),
                    dub = listOf(episode(2)),
                ),
            ),
        )

        val options = availableSourceOptions(catalog, number = 2.0)

        assertFalse(options.any { it.provider == "kiwi" })
        assertEquals(setOf("sub", "dub"), options.filter { it.provider == "senshi" }.map { it.category.api }.toSet())
        assertTrue(options.all { it.hasCurrentEpisode })
    }

    @Test
    fun fastServersShowImmediatelyWhileSlowScrapersWaitForConfirmation() {
        val candidates = listOf(
            option("senshi"), // fast (API-backed Anivexa)
            option("kiwi"),   // fast (Miruro pipe native HLS)
            option("reanime"), // slow scraper
            option("animegg"), // slow scraper
        )

        // Nothing confirmed yet (first paint), nothing proven unavailable.
        val shown = visibleSourceOptions(candidates, isConfirmed = { false }, isUnavailable = { false })

        assertEquals(setOf("senshi", "kiwi"), shown.map { it.provider }.toSet())
    }

    @Test
    fun confirmedSlowScraperAppearsAndFailedFastServerIsHidden() {
        val candidates = listOf(option("senshi"), option("reanime"))

        val shown = visibleSourceOptions(
            candidates,
            isConfirmed = { it.provider == "reanime" }, // validated playable
            isUnavailable = { it.provider == "senshi" }, // fast but proven dead this episode
        )

        assertEquals(listOf("reanime"), shown.map { it.provider })
    }

    private fun option(provider: String) = WatchSourceOption(
        provider = provider,
        category = com.miruronative.data.model.Category.SUB,
        hasCurrentEpisode = true,
        episodeCount = 1,
    )

    private fun episode(number: Int) = EpisodeItem(
        pipeId = "episode-$number",
        number = number.toDouble(),
        title = null,
        image = null,
        filler = false,
    )
}
