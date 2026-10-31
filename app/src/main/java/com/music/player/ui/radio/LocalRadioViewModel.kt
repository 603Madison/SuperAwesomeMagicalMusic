package com.music.player.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.player.AppContainer
import com.music.player.location.CoarseLocation
import com.music.player.sources.radio.RadioStation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalRadioUiState(
    val favorites: List<RadioStation> = emptyList(),
    /** Stations near you, from the last search (kept between launches). */
    val nearby: List<RadioStation> = emptyList(),
    val searchResults: List<RadioStation>? = null,
    val loading: Boolean = false,
    val message: String? = null,
    /** Where you were when nearby stations were found, for distances. */
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val favoriteIds: Set<String> get() = favorites.mapTo(HashSet()) { it.id }

    fun distanceKm(station: RadioStation): Int? {
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        val stationLat = station.latitude ?: return null
        val stationLon = station.longitude ?: return null
        return CoarseLocation.distanceKm(lat, lon, stationLat, stationLon).toInt()
    }
}

class LocalRadioViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LocalRadioUiState())
    val state: StateFlow<LocalRadioUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.settings.settings.collect { settings ->
                _state.update { it.copy(favorites = settings.radioFavorites, nearby = settings.radioNearby) }
            }
        }
    }

    /** Finds stations near you; the caller has asked for location permission first. */
    fun findNearby() {
        _state.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            try {
                val location = CoarseLocation.current(container.context)
                if (location == null) {
                    _state.update {
                        it.copy(loading = false, message = "Your location isn't available. Turn on location, or search for stations by name.")
                    }
                    return@launch
                }
                val stations = container.radio.nearby(location.latitude, location.longitude)
                container.settings.setRadioNearby(stations.take(NEARBY_KEPT))
                _state.update {
                    it.copy(
                        loading = false,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        message = if (stations.isEmpty()) "No stations listed within 80 km of you. Try searching by name." else null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = "Couldn't find stations: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return clearSearch()
        _state.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            try {
                val results = container.radio.search(query.trim())
                _state.update {
                    it.copy(loading = false, searchResults = results, message = if (results.isEmpty()) "No stations called \"$query\"" else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = "Search failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun clearSearch() = _state.update { it.copy(searchResults = null, message = null) }

    fun toggleFavorite(station: RadioStation) {
        viewModelScope.launch { container.settings.setRadioFavorite(station, station.id !in _state.value.favoriteIds) }
    }

    fun locationDenied() =
        _state.update { it.copy(message = "Without location, search for stations by name or city instead.") }

    private companion object {
        const val NEARBY_KEPT = 60
    }
}
