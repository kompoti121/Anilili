package com.miruronative.ui.watch

import com.miruronative.data.model.EpisodeItem
import com.miruronative.ui.components.blockIndexContaining
import com.miruronative.ui.components.episodeBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InPlayerEpisodeListTest {
    @Test
    fun tvPlayerControlOrderIncludesEpisodesButtonWhenHasEpisodesIsTrue() {
        val controlsWithoutEpisodes = tvPlayerControlOrder(hasEpisodes = false)
        assertFalse(controlsWithoutEpisodes.contains(TvPlayerControl.EPISODES))

        val controlsWithEpisodes = tvPlayerControlOrder(hasEpisodes = true)
        assertTrue(controlsWithEpisodes.contains(TvPlayerControl.EPISODES))
        assertEquals(TvPlayerControl.EPISODES, controlsWithEpisodes[5])
    }

    @Test
    fun episodeBlockIndexingLocatesCorrectBlockForInPlayerList() {
        val episodes = (1..250).map { number ->
            EpisodeItem(
                pipeId = "ep-$number",
                number = number.toDouble(),
                title = "Episode $number",
                image = null,
                filler = false,
            )
        }

        val blocks = episodeBlocks(episodes)
        assertEquals(3, blocks.size) // 1-100, 101-200, 201-250

        val blockIndex150 = blockIndexContaining(blocks, 150.0)
        assertEquals(1, blockIndex150)
        assertEquals("101 – 200", blocks[blockIndex150].label)

        val blockIndex220 = blockIndexContaining(blocks, 220.0)
        assertEquals(2, blockIndex220)
    }
}
