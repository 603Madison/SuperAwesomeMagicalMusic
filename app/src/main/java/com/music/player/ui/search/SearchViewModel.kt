package com.music.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.player.AppContainer
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SectionState {
    data object Loading : SectionState
    data object NotConfigured : SectionState
    data class Results(val tracks: List<Track>) : SectionState
    data class Error(val message: String) : SectionState
}

data class SearchUiState(
    val query: String = "",
    val order: List<SourceType> = emptyList(),
    val sections: Map<SourceType, SectionState> = emptyMap(),
    /** Results to show per source: streaming results omit songs already in the Jellyfin library. */
    val visible: Map<SourceType, List<Track>> = emptyMap(),
    /** Downloaded songs matching the query; only searched while offline. */
    val offlineResults: List<Track>? = null,
) {
    fun withSection(type: SourceType, section: SectionState): SearchUiState {
        val sections = sections + (type to section)
        val library = (sections[SourceType.JELLYFIN] as? SectionState.Results)?.tracks.orEmpty()
        val visible = sections.mapValues { (source, state) ->
            val tracks = (state as? SectionState.Results)?.tracks.orEmpty()
            if (source == SourceType.JELLYFIN) tracks
            else tracks.filter { candidate -> library.none { TrackMatcher.matches(it, candidate) } }
        }
        return copy(sections = sections, visible = visible)
    }
}

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

    private suspend fun search(query: String) = coroutineScope {
        if (!container.networkMonitor.state.value.isOnline) {
            val offline = container.database.downloads().searchCompleted(query).map { it.track }
            _state.update { it.copy(order = emptyList(), sections = emptyMap(), visible = emptyMap(), offlineResults = offline) }
            return@coroutineScope
        }
        val order = listOf(SourceType.JELLYFIN) + container.settings.current().fallbackOrder
        _state.update { state ->
            state.copy(
                order = order,
                offlineResults = null,
                sections = order.associateWith { SectionState.Loading },
                visible = emptyMap(),
            )
        }
        for (type in order) {
            launch {
                val source = container.catalog.source(type)
                val result = try {
                    if (source.isAvailable()) SectionState.Results(source.search(query).distinctBy { it.key }) else SectionState.NotConfigured
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SectionState.Error(e.message ?: "Search failed")
                }
                _state.update { it.withSection(type, result) }
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 600L
    }
}
