package com.miruronative.ui.detail

import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.remote.KonohaEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Providers differ in how they say "no title": some leave it null, others fill it with the episode
 * number ("Episode 5"), which looked identical on screen but silently outranked the real title the
 * Konoha overlay supplies — the AniKoto episode list read "Episode 5" while the Anime page showed
 * "Well Fed, Well Regarded" for the same episode.
 */
class EpisodeMetadataMergeTest {
    private fun episode(number: Double, title: String?, image: String? = null) =
        EpisodeItem(pipeId = "p:$number", number = number, title = title, image = image, filler = false)

    private fun meta(number: Double, title: String?, still: String? = null) =
        KonohaEpisode(number = number, title = title, overview = null, air_date = null, still = still, runtime = null)

    @Test
    fun `a title that only echoes the number counts as absent`() {
        assertNull(episode(5.0, "Episode 5").distinctTitle)
        assertNull(episode(5.0, "episode 5").distinctTitle)
        assertNull(episode(5.0, "EP 5").distinctTitle)
        assertNull(episode(5.0, "5").distinctTitle)
        assertNull(episode(5.0, "   ").distinctTitle)
        assertNull(episode(5.0, null).distinctTitle)
        assertEquals("Well Fed, Well Regarded", episode(5.0, "Well Fed, Well Regarded").distinctTitle)
        // Near misses stay real titles - only an exact echo is a placeholder.
        assertEquals("Episode 5: Well Fed", episode(5.0, "Episode 5: Well Fed").distinctTitle)
        assertEquals("Episode 6", episode(5.0, "Episode 6").distinctTitle)
    }

    @Test
    fun `the overlay wins over a placeholder title`() {
        val merged = mergeEpisodeMetadata(
            base = listOf(episode(5.0, "Episode 5")),
            meta = listOf(meta(5.0, "Well Fed, Well Regarded", still = "https://cdn/still5.jpg")),
            animeId = 117612,
        )

        assertEquals("Well Fed, Well Regarded", merged.single().title)
        assertEquals("https://cdn/still5.jpg", merged.single().image)
    }

    @Test
    fun `a real title from the provider is kept`() {
        val merged = mergeEpisodeMetadata(
            base = listOf(episode(5.0, "The Provider's Own Title")),
            meta = listOf(meta(5.0, "Well Fed, Well Regarded")),
            animeId = 117612,
        )

        assertEquals("The Provider's Own Title", merged.single().title)
    }

    @Test
    fun `a placeholder survives when the overlay has nothing better`() {
        val merged = mergeEpisodeMetadata(
            base = listOf(episode(5.0, "Episode 5")),
            meta = listOf(meta(5.0, null, still = "https://cdn/still5.jpg")),
            animeId = 117612,
        )

        // Nothing to upgrade to, so the row keeps rendering as before rather than losing its label.
        assertEquals("Episode 5", merged.single().title)
        assertEquals("https://cdn/still5.jpg", merged.single().image)
    }

    @Test
    fun `episodes the overlay does not cover are untouched`() {
        val merged = mergeEpisodeMetadata(
            base = listOf(episode(5.0, "Episode 5"), episode(99.0, "Episode 99")),
            meta = listOf(meta(5.0, "Well Fed, Well Regarded")),
            animeId = 117612,
        )

        assertEquals("Well Fed, Well Regarded", merged[0].title)
        assertEquals("Episode 99", merged[1].title)
    }
}
