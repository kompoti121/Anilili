package com.miruronative.ui.components

import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.settings.EpisodeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeBrowserTest {

    @Test
    fun `a long-runner splits into hundred-episode blocks with a ragged tail`() {
        val blocks = episodeBlocks(episodes(1..1122))

        assertEquals(12, blocks.size)
        assertEquals("1 – 100", blocks.first().label)
        assertEquals("101 – 200", blocks[1].label)
        assertEquals("1101 – 1122", blocks.last().label)
        assertEquals(100, blocks.first().episodes.size)
        assertEquals(22, blocks.last().episodes.size)
        assertEquals(1122, blocks.sumOf { it.episodes.size })
    }

    @Test
    fun `blocks are labelled by real episode numbers, not by position`() {
        // A continuation season that picks up mid-series must not claim to start at 1.
        val blocks = episodeBlocks(episodes(1051..1180))

        assertEquals(2, blocks.size)
        assertEquals("1051 – 1150", blocks.first().label)
        assertEquals("1151 – 1180", blocks.last().label)
    }

    @Test
    fun `a normal season is a single block and a lone episode is not a range`() {
        assertEquals(1, episodeBlocks(episodes(1..12)).size)
        assertEquals("1 – 12", episodeBlocks(episodes(1..12)).single().label)
        assertEquals("1", episodeBlocks(episodes(1..1)).single().label)
        assertTrue(episodeBlocks(emptyList()).isEmpty())
    }

    @Test
    fun `decimal and gapped episodes stay inside even blocks`() {
        val ragged = listOf(episode(1), episode(2), EpisodeItem("ep-2.5", 2.5, null, null, false))

        val blocks = episodeBlocks(ragged, blockSize = 2)

        assertEquals(listOf("1 – 2", "2.5"), blocks.map(EpisodeBlock::label))
    }

    @Test
    fun `filtering by number jumps straight to an episode deep in the list`() {
        val all = episodes(1..1122)

        val hits = filterEpisodes(all, "1050")

        assertEquals(listOf(1050.0), hits.map(EpisodeItem::number))
    }

    @Test
    fun `a number prefix keeps every episode that starts with it`() {
        val hits = filterEpisodes(episodes(1..120), "10")

        assertEquals(listOf(10.0, 100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 106.0, 107.0, 108.0, 109.0), hits.map(EpisodeItem::number))
    }

    @Test
    fun `a number match outranks a title that merely contains the digits`() {
        // One Piece names episodes after bounties, so "500" hits "500,000,000" hundreds of
        // episodes away. Typing a number is a jump; that episode has to come first.
        val all = listOf(
            episode(351, "A 500,000,000 Berry Bounty!"),
            episode(500, "Freedom Taken Away!"),
            episode(681, "The 500 Million Man"),
        )

        assertEquals(listOf(500.0, 351.0, 681.0), filterEpisodes(all, "500").map(EpisodeItem::number))
    }

    @Test
    fun `filtering also matches titles, case-insensitively`() {
        val all = listOf(
            episode(1, "Romance Dawn"),
            episode(2, "They Call Him Straw Hat Luffy"),
            episode(3, null),
        )

        assertEquals(listOf(1.0), filterEpisodes(all, "romance").map(EpisodeItem::number))
        assertEquals(listOf(2.0), filterEpisodes(all, "Straw Hat").map(EpisodeItem::number))
    }

    @Test
    fun `a title that only echoes the number is not treated as a title`() {
        // distinctTitle drops "Episode 7"; filtering on "seven" must not match it.
        val all = listOf(episode(7, "Episode 7"))

        assertTrue(filterEpisodes(all, "seven").isEmpty())
        assertEquals(listOf(7.0), filterEpisodes(all, "7").map(EpisodeItem::number))
    }

    @Test
    fun `a blank query leaves the list untouched`() {
        val all = episodes(1..12)

        assertEquals(all, filterEpisodes(all, ""))
        assertEquals(all, filterEpisodes(all, "   "))
    }

    @Test
    fun `the block containing the resumed episode is the one that opens`() {
        val blocks = episodeBlocks(episodes(1..1122))

        assertEquals(10, blockIndexContaining(blocks, 1050.0))
        assertEquals(0, blockIndexContaining(blocks, 1.0))
        assertEquals(11, blockIndexContaining(blocks, 1122.0))
    }

    @Test
    fun `an unknown or absent episode falls back to the first block`() {
        val blocks = episodeBlocks(episodes(1..1122))

        assertEquals(0, blockIndexContaining(blocks, null))
        assertEquals(0, blockIndexContaining(blocks, 9999.0))
        assertEquals(0, blockIndexContaining(emptyList(), 5.0))
    }

    @Test
    fun `the layout toggle round-trips through storage`() {
        assertEquals(EpisodeLayout.GRID, EpisodeLayout.LIST.toggled())
        assertEquals(EpisodeLayout.LIST, EpisodeLayout.GRID.toggled())
        assertEquals(EpisodeLayout.GRID, EpisodeLayout.fromStored(EpisodeLayout.GRID.storedValue))
        assertEquals(EpisodeLayout.LIST, EpisodeLayout.fromStored(null))
        assertEquals(EpisodeLayout.LIST, EpisodeLayout.fromStored("nonsense"))
    }

    private fun episodes(range: IntRange) = range.map { episode(it) }

    private fun episode(number: Int, title: String? = null) = EpisodeItem(
        pipeId = "ep-$number",
        number = number.toDouble(),
        title = title,
        image = null,
        filler = false,
    )
}
