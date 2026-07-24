package com.miruronative.data.model

import com.miruronative.data.ProviderCatalog
import kotlinx.serialization.Serializable

enum class Category(val api: String) {
    SUB("sub"),
    DUB("dub");

    companion object {
        fun from(api: String): Category = if (api.equals("dub", true)) DUB else SUB
    }
}

@Serializable
data class EpisodeItem(
    /** Raw pipe id — passed straight back to the `sources` endpoint. */
    val pipeId: String,
    val number: Double,
    val title: String?,
    val image: String?,
    val filler: Boolean,
) {
    val displayNumber: String get() = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()

    /**
     * The episode's own title, or null when the source only echoed the number back ("Episode 6",
     * "EP 6", "6"). Several providers fill the field that way rather than leaving it empty, and
     * such a title must not shadow a real one from the metadata overlay or get printed twice in a
     * row that already shows the number.
     */
    val distinctTitle: String? get() {
        val trimmed = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val echoesNumber = trimmed.equals("Episode $displayNumber", ignoreCase = true) ||
            trimmed.equals("EP $displayNumber", ignoreCase = true) ||
            trimmed == displayNumber
        return trimmed.takeUnless { echoesNumber }
    }
}

@Serializable
data class ProviderData(
    val name: String,
    val sub: List<EpisodeItem>,
    val dub: List<EpisodeItem>,
) {
    val isEmbed: Boolean get() = ProviderCatalog.isEmbed(name)
    fun episodes(category: Category): List<EpisodeItem> = if (category == Category.SUB) sub else dub
    fun hasCategory(category: Category): Boolean = episodes(category).isNotEmpty()
    val categories: List<Category> get() = buildList {
        if (sub.isNotEmpty()) add(Category.SUB)
        if (dub.isNotEmpty()) add(Category.DUB)
    }
}

@Serializable
data class EpisodesResult(val providers: List<ProviderData>) {
    val providerNames: List<String> get() = providers.map { it.name }
    fun provider(name: String): ProviderData? = providers.firstOrNull { it.name == name }
    val isEmpty: Boolean get() = providers.all { it.sub.isEmpty() && it.dub.isEmpty() }
}

data class StreamItem(
    val url: String,
    val type: String,
    val quality: String?,
    val audio: String?,
    val referer: String?,
    val isActive: Boolean,
    val width: Int?,
    val height: Int?,
    val playlistKey: String? = null,
    /**
     * Extra request headers this stream's CDN needs on every manifest and segment fetch, beyond
     * the Referer/Origin pair derived from [referer]. Carries short-lived per-playback tokens
     * (Hentai Haven's `X-Video-*` trio), so it is resolved fresh at playback rather than cached.
     */
    val headers: Map<String, String> = emptyMap(),
    /**
     * Play this source over the plain HTTP transport instead of Cronet. Hentai Haven's CDN trips
     * an assertion inside `CronetDataSource.read` a second into playback ("Source error"), while
     * the identical manifest plays clean on `DefaultHttpDataSource` — measured on device, and the
     * segments themselves verify as valid fMP4 either way.
     */
    val avoidCronet: Boolean = false,
) {
    val isHls: Boolean get() = type.equals("hls", true) || url.contains(".m3u8")
    val isEmbed: Boolean get() = type.equals("embed", true)
    val label: String get() = quality ?: height?.let { "${it}p" } ?: "auto"
}

data class SubtitleItem(val url: String, val label: String, val language: String)

@Serializable
data class SkipTimes(
    val introStart: Double?,
    val introEnd: Double?,
    val outroStart: Double?,
    val outroEnd: Double?,
)

data class SourcesResult(
    val streams: List<StreamItem>,
    val subtitles: List<SubtitleItem>,
    val skip: SkipTimes?,
    val download: String?,
    /**
     * How far these subtitles need shifting to line up with the stream, in milliseconds. Non-zero
     * only where a provider was caught handing out a file cut for a different encode of the same
     * episode; the player seeds its subtitle delay from it.
     */
    val subtitleOffsetMs: Long = 0L,
) {
    val hlsStreams: List<StreamItem> get() = streams.filter { it.isHls }
    val embedStreams: List<StreamItem> get() = streams.filter { it.isEmbed }
}
