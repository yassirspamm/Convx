/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint
import com.music.innertube.models.YTItem
import com.music.innertube.models.filterExplicit
import com.music.innertube.models.filterVideoSongs
import com.music.innertube.utils.YouTubeUrlParser
import com.convx.music.constants.HideExplicitKey
import com.convx.music.constants.HideVideoSongsKey
import com.convx.music.constants.DataSaverEnabledKey
import com.convx.music.db.MusicDatabase
import com.convx.music.db.entities.SearchHistory
import com.convx.music.utils.dataStore
import com.convx.music.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnlineSearchSuggestionViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    private val database: MusicDatabase,
) : ViewModel() {
    val query = MutableStateFlow("")
    private val _viewState = MutableStateFlow(SearchSuggestionViewState())
    val viewState = _viewState.asStateFlow()

    init {
        viewModelScope.launch {
            query
                .flatMapLatest { currentQuery ->
                    if (currentQuery.isEmpty()) historySuggestions() else searchSuggestions(currentQuery)
                }.collect {
                    _viewState.value = it
                }
        }
    }

    private fun historySuggestions(): Flow<SearchSuggestionViewState> =
        database.searchHistory().map { history -> SearchSuggestionViewState(history = history) }

    private suspend fun searchSuggestions(query: String): Flow<SearchSuggestionViewState> {
        val parsedUrl = YouTubeUrlParser.parse(query)
        val parsedItem = parsedUrl?.let { fetchParsedUrlItem(it) }
        val result = if (parsedUrl == null) YouTube.searchSuggestions(query).getOrNull() else null
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false) ||
            context.dataStore.get(DataSaverEnabledKey, false)

        return database.searchHistory(query)
            .map { it.take(3) }
            .map { history ->
                val historyQueries = history.mapTo(mutableSetOf()) { it.query }
                val suggestions = result?.queries.orEmpty().filterNot { it in historyQueries }
                val recommendedItems = result?.recommendedItems.orEmpty()
                    .distinctBy { it.id }
                    .filter { it.id != parsedItem?.id }
                    .filterExplicit(hideExplicit)
                    .filterVideoSongs(hideVideoSongs)

                SearchSuggestionViewState(
                    history = history,
                    suggestions = suggestions,
                    items = listOfNotNull(parsedItem) + recommendedItems,
                    isFromLink = parsedUrl != null,
                )
            }
    }

    private suspend fun fetchParsedUrlItem(parsedUrl: YouTubeUrlParser.ParsedUrl): YTItem? {
        return try {
            when (parsedUrl) {
                is YouTubeUrlParser.ParsedUrl.Video -> {
                    YouTube.queue(listOf(parsedUrl.id)).getOrNull()?.firstOrNull()
                }

                is YouTubeUrlParser.ParsedUrl.Artist -> {
                    YouTube.artist(parsedUrl.id).getOrNull()?.artist
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

data class SearchSuggestionViewState(
    val history: List<SearchHistory> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val items: List<YTItem> = emptyList(),
    val isFromLink: Boolean = false,
)
