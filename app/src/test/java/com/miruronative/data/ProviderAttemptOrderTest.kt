package com.miruronative.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderAttemptOrderTest {
    private val providers = listOf(
        "bonk",
        "kiwi",
        "pewe",
        "bee",
        "ally",
        "anikoto",
        "allanime",
        "animekai",
    )

    @Test
    fun `default order leads with bonk then anibd`() {
        val all = listOf("senshi", "anibd", "bonk", "kiwi", "anikoto")
        assertEquals(
            listOf("bonk", "anibd", "senshi"),
            all.sortedBy { ProviderCatalog.sortKey(it) }.take(3),
        )
        // Fallback from the default keeps the pair first, then spreads across backends.
        assertEquals(
            listOf("bonk", "anibd", "kiwi", "senshi"),
            providerAttemptOrder("bonk", all).take(4),
        )
    }

    @Test
    fun `miruro preference reaches independent backend within attempt budget`() {
        assertEquals(
            listOf("bonk", "anikoto", "kiwi", "allanime", "pewe"),
            providerAttemptOrder("bonk", providers).take(5),
        )
    }

    @Test
    fun `anivexa preference reaches independent backend within attempt budget`() {
        assertEquals(
            listOf("anikoto", "bonk", "allanime", "kiwi", "animekai"),
            providerAttemptOrder("anikoto", providers).take(5),
        )
    }
}
