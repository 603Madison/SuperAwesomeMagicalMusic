package com.music.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.player.AppContainer
import com.music.player.browse.SearchResults
import com.music.player.model.SourceType
import com.music.player.model.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    /** Songs, artists and albums from every source, each once (see UnifiedSearch). */
    val results: SearchResults? = null,
    /** Sources that couldn't be searched, and why. */
    val problems: List<String> = emptyList(),
    /** No source is set up to search. */
    val noSources: Boolean = false,
    /** Downloaded songs matching the query; only searched while offline. */
    val offlineResults: List<Track>? = null,
)

/**
 * Searches all the sources at once, the way streaming apps do: one set of results with the
 * likeliest match on top, whichever source each song, artist or album comes from.
 */
class SearchViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    val downloadedKeys: StateFlow<Set<String>> =
        container.downloads.completedKeys.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private var job: Job? = null

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        job?.cancel()
        if (query.isBlank()) {
            _state.update { SearchUiState() }
            return
        }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            search(query.trim())
        }
    }

    fun submit() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch { search(query) }
    }

    private suspend fun search(query: String) {
        if (!container.networkMonitor.state.value.isOnline) {
            val offline = container.database.downloads().searchCompleted(query).map { it.track }
            _state.update { it.copy(results = null, problems = emptyList(), noSources = false, searching = false, offlineResults = offline) }
            return
        }
        val sources = container.browseSources()
        if (sources.isEmpty()) {
            _state.update { it.copy(results = null, noSources = true, searching = false, offlineResults = null) }
            return
        }
        _state.update { it.copy(searching = true, offlineResults = null, noSources = false) }
        // Results show when every source has answered, or as they come once that takes a while.
        val started = System.currentTimeMillis()
        val answers = container.browsing.search(sources, query) { partial ->
            if (System.currentTimeMillis() - started > SHOW_PARTIAL_AFTER_MS) _state.update { it.copy(results = container.browsing.merge(query, partial)) }
        }
        val problems = answers.mapNotNull { answer ->
            answer.songs.exceptionOrNull()?.let { "${SourceType.valueOf(answer.source.via).label}: ${it.message ?: "search failed"}" }
        }
        _state.update { it.copy(searching = false, results = container.browsing.merge(query, answers), problems = problems) }
    }

    private companion object {
        const val DEBOUNCE_MS = 600L
        const val SHOW_PARTIAL_AFTER_MS = 2_500L
    }
}
