package com.miruronative.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.Media
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
)

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
                val initial = DetailData(
                    info = info,
                    episodes = anilistEpisodeCatalog(info),
                    preferredCategory = if (SettingsStore.preferDub.value) Category.DUB else Category.SUB,
                )
                _state.value = UiState.Success(initial)

                val series = runCatching { repo.animeSeries(info) }.getOrDefault(listOf(info))
                if (loadedId == id) {
                    _state.value = UiState.Success(initial.copy(series = series, seriesLoading = false))
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
