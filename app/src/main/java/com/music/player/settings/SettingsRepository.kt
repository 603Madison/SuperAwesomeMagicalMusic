package com.music.player.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.music.player.model.SourceType
import com.music.player.net.AppJson
import com.music.player.sources.radio.RadioStation
import com.music.player.sources.siriusxm.SxmStation
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import com.music.player.quality.StreamQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<AppSettings> = store.data.map { it.toAppSettings() }

    suspend fun current(): AppSettings = settings.first()

    /** Generates the Jellyfin device id on first launch. */
    suspend fun ensureDeviceId(): String {
        current().deviceId.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        store.edit { it[Keys.DEVICE_ID] = id }
        return id
    }

    suspend fun setJellyfin(config: JellyfinConfig) = store.edit {
        it[Keys.JF_URL] = config.serverUrl
        it[Keys.JF_USERNAME] = config.username
        it[Keys.JF_USER_ID] = config.userId
        it[Keys.JF_TOKEN] = config.accessToken
    }

    suspend fun setQobuz(config: QobuzConfig) = store.edit {
        it[Keys.QOBUZ_APP_ID] = config.appId
        it[Keys.QOBUZ_APP_SECRET] = config.appSecret
        it[Keys.QOBUZ_USERNAME] = config.username
        it[Keys.QOBUZ_TOKEN] = config.userAuthToken
    }

    suspend fun setSiriusXm(config: SiriusXmConfig) = store.edit {
        it[Keys.SXM_USERNAME] = config.username
        it[Keys.SXM_PASSWORD] = config.password
        if (!config.isConfigured) it.remove(Keys.SXM_SESSION)
    }

    suspend fun setSiriusXmBookmarked(channelId: String, bookmarked: Boolean) = store.edit {
        val current = it[Keys.SXM_BOOKMARKS].orEmpty()
        it[Keys.SXM_BOOKMARKS] = if (bookmarked) current + channelId else current - channelId
    }

    /** Saves a Pandora artist station, moving it to the top if it's already saved. */
    suspend fun addSiriusXmStation(station: SxmStation) = store.edit {
        val current = decodeList(it[Keys.SXM_STATIONS], SxmStation.serializer())
        it[Keys.SXM_STATIONS] = encodeList(listOf(station) + current.filter { saved -> saved.id != station.id }, SxmStation.serializer())
    }

    suspend fun removeSiriusXmStation(id: String) = store.edit {
        val current = decodeList(it[Keys.SXM_STATIONS], SxmStation.serializer())
        it[Keys.SXM_STATIONS] = encodeList(current.filter { saved -> saved.id != id }, SxmStation.serializer())
    }

    suspend fun setRadioFavorite(station: RadioStation, favorite: Boolean) = store.edit {
        val current = decodeList(it[Keys.RADIO_FAVORITES], RadioStation.serializer()).filter { saved -> saved.id != station.id }
        it[Keys.RADIO_FAVORITES] = encodeList(if (favorite) current + station else current, RadioStation.serializer())
    }

    suspend fun setRadioNearby(stations: List<RadioStation>) = store.edit {
        it[Keys.RADIO_NEARBY] = encodeList(stations, RadioStation.serializer())
    }

    private fun <T> decodeList(raw: String?, serializer: KSerializer<T>): List<T> =
        raw?.let { runCatching { AppJson.decodeFromString(ListSerializer(serializer), it) }.getOrNull() }.orEmpty()

    private fun <T> encodeList(items: List<T>, serializer: KSerializer<T>): String =
        AppJson.encodeToString(ListSerializer(serializer), items)

    /** SiriusXM's device grant and session tokens, as JSON. */
    suspend fun siriusXmSession(): String? = store.data.first()[Keys.SXM_SESSION]

    suspend fun setSiriusXmSession(json: String?) = store.edit { it.putOrRemove(Keys.SXM_SESSION, json) }

    /** The authentik user id 1.1.0 stored when signed in there; its playlists are moved over once. */
    suspend fun legacyAuthentikSubject(): String? = store.data.first()[Keys.ACCOUNT_SUBJECT]

    suspend fun clearLegacyAuthentik() = store.edit { prefs ->
        Keys.LEGACY_AUTHENTIK.forEach { prefs.remove(it) }
    }

    suspend fun setYouTubeMusicEnabled(enabled: Boolean) = store.edit { it[Keys.YTM_ENABLED] = enabled }

    suspend fun setFallbackOrder(order: List<SourceType>) = store.edit {
        it[Keys.FALLBACK_ORDER] = order.joinToString(",") { type -> type.name }
    }

    suspend fun setLidarr(config: LidarrConfig) = store.edit {
        it[Keys.LIDARR_URL] = config.serverUrl
        it[Keys.LIDARR_API_KEY] = config.apiKey
        it[Keys.LIDARR_AUTO_ADD] = config.autoAddArtists
        it.putOrRemove(Keys.LIDARR_QUALITY_PROFILE, config.qualityProfileId)
        it.putOrRemove(Keys.LIDARR_METADATA_PROFILE, config.metadataProfileId)
        it.putOrRemove(Keys.LIDARR_ROOT_FOLDER, config.rootFolderPath)
        it[Keys.LIDARR_SEARCH_MISSING] = config.searchForMissingAlbums
    }

    suspend fun setNextcloud(config: NextcloudConfig) = store.edit {
        it[Keys.NC_URL] = config.serverUrl
        it[Keys.NC_USERNAME] = config.username
        it[Keys.NC_USER_ID] = config.userId
        it[Keys.NC_APP_PASSWORD] = config.appPassword
        it[Keys.NC_FOLDER] = config.folder
        it[Keys.NC_AUTO_SYNC] = config.autoSync
    }

    suspend fun setQuality(quality: QualitySettings) = store.edit {
        it[Keys.Q_WIFI] = quality.wifi.toStored()
        it[Keys.Q_CELLULAR] = quality.cellular.toStored()
        it[Keys.Q_SATELLITE] = quality.satellite.toStored()
        it[Keys.Q_DOWNLOAD] = quality.download.toStored()
        it[Keys.Q_DATA_SAVER] = quality.dataSaver.toStored()
    }

    suspend fun setTranscodeCodec(codec: TranscodeCodec) = store.edit { it[Keys.JF_TRANSCODE_CODEC] = codec.name }

    suspend fun setDownloads(downloads: DownloadSettings) = store.edit {
        it[Keys.DL_CELLULAR] = downloads.allowCellular
        it[Keys.DL_SATELLITE] = downloads.allowSatellite
    }

    /** Identifies the Nextcloud folder playlists were last synced with. */
    suspend fun lastSyncTarget(): String? = store.data.first()[Keys.NC_SYNC_TARGET]

    suspend fun setLastSyncTarget(target: String) = store.edit { it[Keys.NC_SYNC_TARGET] = target }

    suspend fun setLoadArtworkOnSatellite(enabled: Boolean) = store.edit { it[Keys.ARTWORK_ON_SATELLITE] = enabled }

    suspend fun setBoldText(enabled: Boolean) = store.edit { it[Keys.BOLD_TEXT] = enabled }

    suspend fun setBufferAheadSeconds(seconds: Int) = store.edit { it[Keys.BUFFER_AHEAD] = seconds }

    suspend fun setPlayback(playback: PlaybackSettings) = store.edit {
        it[Keys.LEVEL_VOLUME] = playback.levelVolume
        it[Keys.SXM_FROM_SONG_START] = playback.siriusXmFromSongStart
        it[Keys.LIKED_ON_PANDORA] = playback.likedOnPandoraPlaylist
        it[Keys.RESUME_IN_CAR] = playback.resumeInCar
        it[Keys.EQ_PRESET] = playback.equalizerPreset
        it[Keys.BASS_BOOST] = playback.bassBoost
    }

    suspend fun setEqualizerPresets(names: List<String>) = store.edit {
        it[Keys.EQ_PRESETS] = encodeList(names, String.serializer())
    }

    suspend fun setCache(cache: CacheSettings) = store.edit {
        it[Keys.CACHE_ENABLED] = cache.enabled
        it[Keys.CACHE_SIZE_MB] = cache.sizeMb
        it[Keys.CACHE_AHEAD] = cache.songsAhead
        it[Keys.CACHE_CELLULAR] = cache.onCellular
    }

    suspend fun setScrobbling(scrobbling: ScrobbleSettings) = store.edit {
        it[Keys.LB_TOKEN] = scrobbling.listenBrainzToken
        it[Keys.LB_URL] = scrobbling.listenBrainzUrl
        it[Keys.LASTFM_KEY] = scrobbling.lastFmApiKey
        it[Keys.LASTFM_SECRET] = scrobbling.lastFmSecret
        it[Keys.LASTFM_USER] = scrobbling.lastFmUser
        it[Keys.LASTFM_SESSION] = scrobbling.lastFmSession
        it[Keys.SCROBBLE_RADIO] = scrobbling.includeRadio
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            deviceId = this[Keys.DEVICE_ID].orEmpty(),
            jellyfin = JellyfinConfig(
                serverUrl = this[Keys.JF_URL].orEmpty(),
                username = this[Keys.JF_USERNAME].orEmpty(),
                userId = this[Keys.JF_USER_ID].orEmpty(),
                accessToken = this[Keys.JF_TOKEN].orEmpty(),
            ),
            qobuz = QobuzConfig(
                appId = this[Keys.QOBUZ_APP_ID].orEmpty(),
                appSecret = this[Keys.QOBUZ_APP_SECRET].orEmpty(),
                username = this[Keys.QOBUZ_USERNAME].orEmpty(),
                userAuthToken = this[Keys.QOBUZ_TOKEN].orEmpty(),
            ),
            youTubeMusicEnabled = this[Keys.YTM_ENABLED] ?: defaults.youTubeMusicEnabled,
            siriusXm = SiriusXmConfig(
                username = this[Keys.SXM_USERNAME].orEmpty(),
                password = this[Keys.SXM_PASSWORD].orEmpty(),
            ),
            siriusXmBookmarks = this[Keys.SXM_BOOKMARKS].orEmpty(),
            siriusXmStations = decodeList(this[Keys.SXM_STATIONS], SxmStation.serializer()),
            radioFavorites = decodeList(this[Keys.RADIO_FAVORITES], RadioStation.serializer()),
            radioNearby = decodeList(this[Keys.RADIO_NEARBY], RadioStation.serializer()),
            fallbackOrder = parseFallbackOrder(this[Keys.FALLBACK_ORDER]) ?: defaults.fallbackOrder,
            lidarr = LidarrConfig(
                serverUrl = this[Keys.LIDARR_URL].orEmpty(),
                apiKey = this[Keys.LIDARR_API_KEY].orEmpty(),
                autoAddArtists = this[Keys.LIDARR_AUTO_ADD] ?: defaults.lidarr.autoAddArtists,
                qualityProfileId = this[Keys.LIDARR_QUALITY_PROFILE],
                metadataProfileId = this[Keys.LIDARR_METADATA_PROFILE],
                rootFolderPath = this[Keys.LIDARR_ROOT_FOLDER],
                searchForMissingAlbums = this[Keys.LIDARR_SEARCH_MISSING] ?: defaults.lidarr.searchForMissingAlbums,
            ),
            nextcloud = NextcloudConfig(
                serverUrl = this[Keys.NC_URL].orEmpty(),
                username = this[Keys.NC_USERNAME].orEmpty(),
                userId = this[Keys.NC_USER_ID].orEmpty(),
                appPassword = this[Keys.NC_APP_PASSWORD].orEmpty(),
                folder = this[Keys.NC_FOLDER]?.takeIf { it.isNotBlank() } ?: NextcloudConfig.DEFAULT_FOLDER,
                autoSync = this[Keys.NC_AUTO_SYNC] ?: defaults.nextcloud.autoSync,
            ),
            quality = QualitySettings(
                wifi = this[Keys.Q_WIFI]?.let(StreamQuality::fromStored) ?: defaults.quality.wifi,
                cellular = this[Keys.Q_CELLULAR]?.let(StreamQuality::fromStored) ?: defaults.quality.cellular,
                satellite = this[Keys.Q_SATELLITE]?.let(StreamQuality::fromStored) ?: defaults.quality.satellite,
                download = this[Keys.Q_DOWNLOAD]?.let(StreamQuality::fromStored) ?: defaults.quality.download,
                dataSaver = this[Keys.Q_DATA_SAVER]?.let(StreamQuality::fromStored) ?: defaults.quality.dataSaver,
            ),
            jellyfinTranscodeCodec = this[Keys.JF_TRANSCODE_CODEC]
                ?.let { name -> TranscodeCodec.entries.firstOrNull { it.name == name } }
                ?: defaults.jellyfinTranscodeCodec,
            downloads = DownloadSettings(
                allowCellular = this[Keys.DL_CELLULAR] ?: defaults.downloads.allowCellular,
                allowSatellite = this[Keys.DL_SATELLITE] ?: defaults.downloads.allowSatellite,
            ),
            loadArtworkOnSatellite = this[Keys.ARTWORK_ON_SATELLITE] ?: defaults.loadArtworkOnSatellite,
            boldText = this[Keys.BOLD_TEXT] ?: defaults.boldText,
            bufferAheadSeconds = this[Keys.BUFFER_AHEAD] ?: defaults.bufferAheadSeconds,
            playback = PlaybackSettings(
                levelVolume = this[Keys.LEVEL_VOLUME] ?: defaults.playback.levelVolume,
                siriusXmFromSongStart = this[Keys.SXM_FROM_SONG_START] ?: defaults.playback.siriusXmFromSongStart,
                likedOnPandoraPlaylist = this[Keys.LIKED_ON_PANDORA] ?: defaults.playback.likedOnPandoraPlaylist,
                resumeInCar = this[Keys.RESUME_IN_CAR] ?: defaults.playback.resumeInCar,
                equalizerPreset = this[Keys.EQ_PRESET] ?: defaults.playback.equalizerPreset,
                bassBoost = this[Keys.BASS_BOOST] ?: defaults.playback.bassBoost,
                equalizerPresets = decodeList(this[Keys.EQ_PRESETS], String.serializer()),
            ),
            cache = CacheSettings(
                enabled = this[Keys.CACHE_ENABLED] ?: defaults.cache.enabled,
                sizeMb = this[Keys.CACHE_SIZE_MB] ?: defaults.cache.sizeMb,
                songsAhead = this[Keys.CACHE_AHEAD] ?: defaults.cache.songsAhead,
                onCellular = this[Keys.CACHE_CELLULAR] ?: defaults.cache.onCellular,
            ),
            scrobbling = ScrobbleSettings(
                listenBrainzToken = this[Keys.LB_TOKEN].orEmpty(),
                listenBrainzUrl = this[Keys.LB_URL]?.takeIf { it.isNotBlank() } ?: ScrobbleSettings.DEFAULT_LISTENBRAINZ_URL,
                lastFmApiKey = this[Keys.LASTFM_KEY].orEmpty(),
                lastFmSecret = this[Keys.LASTFM_SECRET].orEmpty(),
                lastFmUser = this[Keys.LASTFM_USER].orEmpty(),
                lastFmSession = this[Keys.LASTFM_SESSION].orEmpty(),
                includeRadio = this[Keys.SCROBBLE_RADIO] ?: defaults.scrobbling.includeRadio,
            ),
        )
    }

    private fun parseFallbackOrder(raw: String?): List<SourceType>? {
        val parsed = raw?.split(",")?.mapNotNull { name -> SourceType.entries.firstOrNull { it.name == name } }
            ?.filter { it != SourceType.JELLYFIN && it.onDemand }
            ?.distinct()
        if (parsed.isNullOrEmpty()) return null
        // Keep any service missing from an older stored value at the end of the list.
        return parsed + SourceType.entries.filter { it != SourceType.JELLYFIN && it.onDemand && it !in parsed }
    }

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val JF_URL = stringPreferencesKey("jellyfin_url")
        val JF_USERNAME = stringPreferencesKey("jellyfin_username")
        val JF_USER_ID = stringPreferencesKey("jellyfin_user_id")
        val JF_TOKEN = stringPreferencesKey("jellyfin_token")
        val JF_TRANSCODE_CODEC = stringPreferencesKey("jellyfin_transcode_codec")
        val QOBUZ_APP_ID = stringPreferencesKey("qobuz_app_id")
        val QOBUZ_APP_SECRET = stringPreferencesKey("qobuz_app_secret")
        val QOBUZ_USERNAME = stringPreferencesKey("qobuz_username")
        val QOBUZ_TOKEN = stringPreferencesKey("qobuz_token")
        val YTM_ENABLED = booleanPreferencesKey("ytm_enabled")
        val SXM_USERNAME = stringPreferencesKey("sxm_username")
        val SXM_PASSWORD = stringPreferencesKey("sxm_password")
        val SXM_SESSION = stringPreferencesKey("sxm_session")
        val SXM_BOOKMARKS = stringSetPreferencesKey("sxm_bookmarks")
        val SXM_STATIONS = stringPreferencesKey("sxm_artist_stations")
        val RADIO_FAVORITES = stringPreferencesKey("radio_favorites")
        val RADIO_NEARBY = stringPreferencesKey("radio_nearby")
        val ACCOUNT_SUBJECT = stringPreferencesKey("account_subject")
        val LEGACY_AUTHENTIK = listOf(
            stringPreferencesKey("authentik_issuer"),
            stringPreferencesKey("authentik_client_id"),
            stringPreferencesKey("authentik_auth_state"),
            ACCOUNT_SUBJECT,
            stringPreferencesKey("account_username"),
            stringPreferencesKey("account_email"),
            stringPreferencesKey("account_name"),
        )
        val FALLBACK_ORDER = stringPreferencesKey("fallback_order")
        val LIDARR_URL = stringPreferencesKey("lidarr_url")
        val LIDARR_API_KEY = stringPreferencesKey("lidarr_api_key")
        val LIDARR_AUTO_ADD = booleanPreferencesKey("lidarr_auto_add")
        val LIDARR_QUALITY_PROFILE = intPreferencesKey("lidarr_quality_profile")
        val LIDARR_METADATA_PROFILE = intPreferencesKey("lidarr_metadata_profile")
        val LIDARR_ROOT_FOLDER = stringPreferencesKey("lidarr_root_folder")
        val LIDARR_SEARCH_MISSING = booleanPreferencesKey("lidarr_search_missing")
        val NC_URL = stringPreferencesKey("nextcloud_url")
        val NC_USERNAME = stringPreferencesKey("nextcloud_username")
        val NC_USER_ID = stringPreferencesKey("nextcloud_user_id")
        val NC_APP_PASSWORD = stringPreferencesKey("nextcloud_app_password")
        val NC_FOLDER = stringPreferencesKey("nextcloud_folder")
        val NC_AUTO_SYNC = booleanPreferencesKey("nextcloud_auto_sync")
        val NC_SYNC_TARGET = stringPreferencesKey("nextcloud_sync_target")
        val Q_WIFI = intPreferencesKey("quality_wifi")
        val Q_CELLULAR = intPreferencesKey("quality_cellular")
        val Q_SATELLITE = intPreferencesKey("quality_satellite")
        val Q_DOWNLOAD = intPreferencesKey("quality_download")
        val Q_DATA_SAVER = intPreferencesKey("quality_data_saver")
        val DL_CELLULAR = booleanPreferencesKey("download_cellular")
        val DL_SATELLITE = booleanPreferencesKey("download_satellite")
        val ARTWORK_ON_SATELLITE = booleanPreferencesKey("artwork_on_satellite")
        val BOLD_TEXT = booleanPreferencesKey("bold_text")
        val BUFFER_AHEAD = intPreferencesKey("buffer_ahead_seconds")
        val LEVEL_VOLUME = booleanPreferencesKey("level_volume")
        val SXM_FROM_SONG_START = booleanPreferencesKey("sxm_from_song_start")
        val LIKED_ON_PANDORA = booleanPreferencesKey("liked_on_pandora_playlist")
        val RESUME_IN_CAR = booleanPreferencesKey("resume_in_car")
        val EQ_PRESET = intPreferencesKey("equalizer_preset")
        val BASS_BOOST = intPreferencesKey("bass_boost")
        val EQ_PRESETS = stringPreferencesKey("equalizer_presets")
        val CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        val CACHE_SIZE_MB = intPreferencesKey("cache_size_mb")
        val CACHE_AHEAD = intPreferencesKey("cache_songs_ahead")
        val CACHE_CELLULAR = booleanPreferencesKey("cache_on_cellular")
        val LB_TOKEN = stringPreferencesKey("listenbrainz_token")
        val LB_URL = stringPreferencesKey("listenbrainz_url")
        val LASTFM_KEY = stringPreferencesKey("lastfm_api_key")
        val LASTFM_SECRET = stringPreferencesKey("lastfm_secret")
        val LASTFM_USER = stringPreferencesKey("lastfm_user")
        val LASTFM_SESSION = stringPreferencesKey("lastfm_session")
        val SCROBBLE_RADIO = booleanPreferencesKey("scrobble_radio")
    }
}
