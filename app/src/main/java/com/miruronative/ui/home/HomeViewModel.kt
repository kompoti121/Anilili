package com.miruronative.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miruronative.data.AppGraph
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.data.model.Media
import com.miruronative.ui.UiState
import com.miruronative.ui.rethrowIfCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HomeTab(val label: String) {
    NEWEST("NEWEST"),
    POPULAR("POPULAR"),
    MOVIES("MOVIES"),
    TOP_RATED("TOP RATED"),
}

data class HomeData(
    val spotlight: List<Media>,
    val newest: List<Media>,
    val popular: List<Media>,
    val movies: List<Media>,
    val topRated: List<Media>,
) {
    fun tab(tab: HomeTab): List<Media> = when (tab) {
        HomeTab.NEWEST -> newest
        HomeTab.POPULAR -> popular
        HomeTab.MOVIES -> movies
        HomeTab.TOP_RATED -> topRated
    }
}

class HomeViewModel : ViewModel() {
    private val repo = AppGraph.repository

    private val _state = MutableStateFlow<UiState<HomeData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val tabPages = mutableMapOf<HomeTab, Int>()
    private val tabLoading = mutableMapOf<HomeTab, Boolean>()
    private var trendingPage = 1
    private var trendingLoading = false

    var selectedTab by mutableStateOf(HomeTab.POPULAR)
        private set

    init { load() }

    fun selectTab(tab: HomeTab) { selectedTab = tab }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            DiagnosticsLog.event("Home load start force=$force")
            if (force && _state.value is UiState.Success) _isRefreshing.value = true else _state.value = UiState.Loading
            try {
                val collections = repo.homeCollections(force)
                val data = HomeData(
                    spotlight = collections.spotlight,
                    newest = collections.newest,
                    popular = collections.popular,
                    movies = collections.movies,
                    topRated = collections.topRated,
                )
                tabPages.clear()
                trendingPage = 1
                DiagnosticsLog.event(
                    "Home load success spotlight=${data.spotlight.size} newest=${data.newest.size} " +
                        "popular=${data.popular.size} movies=${data.movies.size} topRated=${data.topRated.size}",
                )
                _state.value = UiState.Success(data)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                DiagnosticsLog.throwable("Home load failed", e)
                _state.value = UiState.Error(e.message ?: "Failed to load home")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadMoreTab(tab: HomeTab) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        if (tabLoading[tab] == true) return
        tabLoading[tab] = true

        viewModelScope.launch {
            try {
                val nextPage = (tabPages[tab] ?: 1) + 1
                val hideAdult = com.miruronative.data.settings.SettingsStore.hideAdultContent.value
                val newPageData = when (tab) {
                    HomeTab.POPULAR -> repo.popular(nextPage).items
                    HomeTab.NEWEST -> repo.recentlyReleased(nextPage).items
                    HomeTab.MOVIES -> repo.movies(nextPage).items
                    HomeTab.TOP_RATED -> repo.topRated(nextPage).items
                }
                if (newPageData.isNotEmpty()) {
                    tabPages[tab] = nextPage
                    val updatedList = (current.tab(tab) + newPageData).distinctBy { it.id }
                    val newData = when (tab) {
                        HomeTab.POPULAR -> current.copy(popular = updatedList)
                        HomeTab.NEWEST -> current.copy(newest = updatedList)
                        HomeTab.MOVIES -> current.copy(movies = updatedList)
                        HomeTab.TOP_RATED -> current.copy(topRated = updatedList)
                    }
                    _state.value = UiState.Success(newData)
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
            } finally {
                tabLoading[tab] = false
            }
        }
    }

    fun loadMoreTrending() {
        val current = (_state.value as? UiState.Success)?.data ?: return
        if (trendingLoading) return
        trendingLoading = true

        viewModelScope.launch {
            try {
                val nextPage = trendingPage + 1
                val newPageData = repo.trending(nextPage).items
                if (newPageData.isNotEmpty()) {
                    trendingPage = nextPage
                    val updatedList = (current.spotlight + newPageData).distinctBy { it.id }
                    _state.value = UiState.Success(current.copy(spotlight = updatedList))
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
            } finally {
                trendingLoading = false
            }
        }
    }

    fun refresh() = load(force = true)
}
