package com.miruronative.data

/**
 * Provider classification across both streaming backends:
 * - **Miruro** pipe providers (via the on-device WebView bridge)
 * - **Anivexa** providers (via an Anivexa-API instance over HTTP)
 * A provider name is globally unique, so routing is by name.
 */
object ProviderCatalog {
    enum class Source { MIRURO, ANIVEXA }

    // Miruro pipe providers.
    private val miruroOrder = listOf(
        "bonk", "kiwi", "pewe", "bee", "ally", "moo", "hop", // native HLS
        "nun", "bun", "twin", "cog", "telli",                 // iframe embeds
    )
    private val miruroEmbed = setOf("nun", "bun", "twin", "cog", "telli")

    // Anivexa providers we query (reliable, self-hosted sources).
    val anivexaProviders = listOf(
        "senshi", "anibd", "anikoto", "allanime", "animekai", "reanime", "anizone", "animegg", "anineko", "2dhive",
    )

    // Default row order: bonk (Miruro pipe) then anibd (Anivexa) lead as the two default
    // sources — independent backends, both fast and reliable — with senshi right behind.
    // A user's saved favourite provider always overrides this order.
    private val leaders = listOf("bonk", "anibd", "senshi")
    private val order = leaders + (miruroOrder + anivexaProviders).filterNot { it in leaders }

    fun sourceOf(provider: String): Source =
        if (provider in anivexaProviders) Source.ANIVEXA else Source.MIRURO

    /** Only Miruro has provider-level iframe embeds; Anivexa decides embed per-stream. */
    fun isEmbed(provider: String): Boolean = provider in miruroEmbed
    fun isNative(provider: String): Boolean = !isEmbed(provider)

    fun sortKey(provider: String): Int =
        order.indexOf(provider).let { if (it >= 0) it else Int.MAX_VALUE }

    fun label(provider: String): String = when (provider) {
        "anibd" -> "AniBD"
        "2dhive" -> "2Dhive"
        "allanime" -> "AllAnime"
        "animekai" -> "AnimeKai"
        else -> provider.replaceFirstChar { it.uppercase() }
    }
}
