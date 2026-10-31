package com.music.player.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.player.AppContainer
import com.music.player.sources.siriusxm.SxmArtist
import com.music.player.sources.siriusxm.SxmChannel
import com.music.player.sources.siriusxm.SxmStation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RadioUiState(
    val configured: Boolean? = null,
    val loading: Boolean = false,
    val channels: List<SxmChannel> = emptyList(),
    val error: String? = null,
    val filter: String = "",
    val bookmarks: Set<String> = emptySet(),
    val stations: List<SxmStation> = emptyList(),
    val artistSearch: ArtistSearch = ArtistSearch(),
) {
    private val matching: List<SxmChannel>
        get() {
            val query = filter.trim().lowercase()
            if (query.isEmpty()) return channels
            return channels.filter {
                it.name.lowercase().contains(query) || it.category.lowercase().contains(query) || it.number?.toString() == query
            }
        }

    val bookmarked: List<SxmChannel> get() = matching.filter { it.id in bookmarks }
    val others: List<SxmChannel> get() = matching.filter { it.id !in bookmarks }
}

/** Searching SiriusXM for an artist to start a Pandora station from. */
data class ArtistSearch(
    val running: Boolean = false,
    val results: List<SxmArtist>? = null,
    val error: String? = null,
)

class RadioViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = _state.asStateFlow()

    init {
        load(refresh = false)
        viewModelScope.launch {
            container.settings.settings.map { it.siriusXmBookmarks }.distinctUntilChanged().collect { bookmarks ->
                _state.update { it.copy(bookmarks = bookmarks) }
            }
        }
        viewModelScope.launch {
            container.settings.settings.map { it.siriusXmStations }.distinctUntilChanged().collect { stations ->
                _state.update { it.copy(stations = stations) }
            }
        }
    }

    fun removeStation(station: SxmStation) {
        viewModelScope.launch { container.settings.removeSiriusXmStation(station.id) }
    }

    fun searchArtists(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(artistSearch = ArtistSearch(running = true)) }
        viewModelScope.launch {
            val search = try {
                val artists = container.siriusXm.searchArtists(query.trim())
                ArtistSearch(results = artists, error = if (artists.isEmpty()) "No artists found for \"$query\"" else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ArtistSearch(
                    error = "SiriusXM's search didn't work (${e.message ?: e.javaClass.simpleName}). Start a station from " +
                        "the artist you're hearing instead, and send the SiriusXM diagnostics so search can be fixed.",
                )
            }
            _state.update { it.copy(artistSearch = search) }
        }
    }

    fun clearArtistSearch() = _state.update { it.copy(artistSearch = ArtistSearch()) }

    fun toggleBookmark(channel: SxmChannel) {
        viewModelScope.launch {
            container.settings.setSiriusXmBookmarked(channel.id, channel.id !in _state.value.bookmarks)
        }
    }

    fun setFilter(filter: String) = _state.update { it.copy(filter = filter) }

    fun load(refresh: Boolean) {
        viewModelScope.launch {
            val configured = container.siriusXm.isAvailable()
            _state.update { it.copy(configured = configured, loading = configured, error = null) }
            if (!configured) return@launch
            try {
                val channels = container.siriusXm.channels(refresh)
                _state.update { it.copy(loading = false, channels = channels) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load SiriusXM channels") }
            }
        }
    }
}
