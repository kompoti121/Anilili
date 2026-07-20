package com.miruronative.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.SERIES_AIRING_ORDER
import com.miruronative.data.seasonNeighbors
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.Media
import com.miruronative.data.remote.KonohaEpisode
import java.time.LocalDate
import com.miruronative.data.settings.SettingsStore
import com.miruronative.ui.UiState
import com.miruronative.ui.rethrowIfCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailData(
    val info: Media,
    /** Provider-independent episode catalog derived from cached/AniList anime metadata. */
    val episodes: List<EpisodeItem>,
    /** Playback preference only; server and audio controls belong to the watch screen. */
    val preferredCategory: Category,
    val series: List<Media> = listOf(info),
    val seriesLoading: Boolean = true,
    /** Season whose episodes the Episodes tab currently shows; defaults to this page's anime. */
    val selectedSeasonId: Int = info.id,
    val seasonEpisodesLoading: Boolean = false,
) {
    /**
     * Episodic entries of the prequel/sequel chain, in airing order — one detail page serves the
     * whole series and the Episodes tab filters between its seasons. Movies and specials stay on
     * the Related tab.
     */
    val seasons: List<Media>
        get() = series.filter { it.id == info.id || it.format in SEASON_FORMATS }
}

private val SEASON_FORMATS = setOf("TV", "TV_SHORT", "ONA")

class DetailViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<DetailData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private var loadedId: Int? = null

    fun load(id: Int, force: Boolean = false) {
        if (!force && loadedId == id && _state.value is UiState.Success) return
        loadedId = id
        viewModelScope.launch {
            if (force && _state.value is UiState.Success) {
                _isRefreshing.value = true
            } else {
                _state.value = UiState.Loading
            }
            try {
                // animeInfo is cache-first and fetches AniList only when local metadata is absent
                // or stale. The episode tab therefore follows the same source of truth as Home.
                SettingsStore.awaitLoaded()
                val info = repo.animeInfo(id, force = force) ?: error("Anime not found")
                // Seasons show instantly: the anime's own relations (already part of the cached
                // info) seed the list with its direct prequel/sequel neighbors, and the full
                // walked chain replaces it below once animeSeries returns (cached per member,
                // so revisits of any season resolve it immediately too).
                val seededSeries = (listOf(info) + info.seasonNeighbors())
                    .distinctBy(Media::id)
                    .sortedWith(SERIES_AIRING_ORDER)
                val initial = DetailData(
                    info = info,
                    episodes = anilistEpisodeCatalog(info),
                    preferredCategory = if (SettingsStore.preferDub.value) Category.DUB else Category.SUB,
                    series = seededSeries,
                )
                _state.value = UiState.Success(initial)
                launchEpisodeEnrichment(info.id)

                val series = runCatching { repo.animeSeries(info) }.getOrDefault(seededSeries)
                if (loadedId == id) {
                    val current = (_state.value as? UiState.Success)?.data ?: initial
                    _state.value = UiState.Success(current.copy(series = series, seriesLoading = false))
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                _state.value = UiState.Error(e.message ?: "Failed to load")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refresh(id: Int) = load(id, force = true)

    /** Swaps the Episodes tab to another season of the same series without leaving this page. */
    fun selectSeason(seasonId: Int) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        if (current.selectedSeasonId == seasonId) return
        if (seasonId == current.info.id) {
            _state.value = UiState.Success(
                current.copy(
                    selectedSeasonId = seasonId,
                    episodes = anilistEpisodeCatalog(current.info),
                    seasonEpisodesLoading = false,
                ),
            )
            return
        }
        _state.value = UiState.Success(
            current.copy(selectedSeasonId = seasonId, episodes = emptyList(), seasonEpisodesLoading = true),
        )
        viewModelScope.launch {
            // animeInfo is cache-first; season entries discovered by animeSeries are usually
            // already cached, so this rarely hits the network.
            val seasonInfo = runCatching { repo.animeInfo(seasonId) }.getOrNull()
                ?: current.series.firstOrNull { it.id == seasonId }
            val episodes = seasonInfo?.let(::anilistEpisodeCatalog).orEmpty()
            val latest = (_state.value as? UiState.Success)?.data ?: return@launch
            if (latest.selectedSeasonId == seasonId) {
                _state.value = UiState.Success(latest.copy(episodes = episodes, seasonEpisodesLoading = false))
                launchEpisodeEnrichment(seasonId)
            }
        }
    }

    /**
     * Overlays Konoha CDN metadata (episode titles, TMDB stills) onto the count-based catalog.
     * Fast and rate-limit-free, but strictly cosmetic: any failure leaves the plain list as-is.
     */
    private fun launchEpisodeEnrichment(seasonId: Int) {
        viewModelScope.launch {
            val meta = repo.konohaEpisodes(seasonId)
            if (meta.isEmpty()) return@launch
            val data = (_state.value as? UiState.Success)?.data ?: return@launch
            if (data.selectedSeasonId != seasonId) return@launch
            _state.value = UiState.Success(
                data.copy(episodes = mergeEpisodeMetadata(data.episodes, meta, seasonId)),
            )
        }
    }
}

/**
 * Fills titles and thumbnails from Konoha into the synthetic AniList catalog by episode number.
 * When AniList had no usable count (e.g. RELEASING with unknown totals), the aired Konoha
 * entries become the catalog themselves.
 */
internal fun mergeEpisodeMetadata(
    base: List<EpisodeItem>,
    meta: List<KonohaEpisode>,
    animeId: Int,
): List<EpisodeItem> {
    if (meta.isEmpty()) return base
    val byNumber = meta.mapNotNull { ep -> ep.number?.let { it to ep } }.toMap()
    if (base.isNotEmpty()) {
        return base.map { episode ->
            val extra = byNumber[episode.number] ?: return@map episode
            episode.copy(
                // A provider that filled the title with "Episode 5" counts as having none, or its
                // placeholder would outrank the real title this overlay exists to supply.
                title = episode.distinctTitle ?: extra.title ?: episode.title,
                image = episode.image ?: extra.still,
            )
        }
    }
    val today = LocalDate.now()
    return meta
        .filter { ep ->
            ep.number != null && ep.number >= 1 &&
                (ep.air_date == null || runCatching { !LocalDate.parse(ep.air_date).isAfter(today) }.getOrDefault(true))
        }
        .sortedBy { it.number }
        .map { ep ->
            EpisodeItem(
                pipeId = "anilist:$animeId:${ep.number?.toInt()}",
                number = ep.number ?: 0.0,
                title = ep.title,
                image = ep.still,
                filler = false,
            )
        }
}

/**
 * AniList exposes series-level episode counts rather than stream-provider rows. For currently
 * airing anime, the next scheduled episode gives the released count; completed shows use the
 * total. The stable synthetic id is UI-only and is never sent to a playback provider.
 */
internal fun anilistEpisodeCatalog(info: Media): List<EpisodeItem> {
    val releasedBeforeNext = info.nextAiringEpisode?.episode?.minus(1)?.coerceAtLeast(0)
    val count = when {
        releasedBeforeNext != null && info.episodes != null -> minOf(releasedBeforeNext, info.episodes)
        releasedBeforeNext != null -> releasedBeforeNext
        else -> info.episodes ?: 0
    }.coerceAtLeast(0)

    return (1..count).map { number ->
        EpisodeItem(
            pipeId = "anilist:${info.id}:$number",
            number = number.toDouble(),
            title = null,
            image = null,
            filler = false,
        )
    }
}
