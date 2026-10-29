package com.music.player.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.music.player.model.SourceType
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
            ),
            jellyfinTranscodeCodec = this[Keys.JF_TRANSCODE_CODEC]
                ?.let { name -> TranscodeCodec.entries.firstOrNull { it.name == name } }
                ?: defaults.jellyfinTranscodeCodec,
            downloads = DownloadSettings(
                allowCellular = this[Keys.DL_CELLULAR] ?: defaults.downloads.allowCellular,
                allowSatellite = this[Keys.DL_SATELLITE] ?: defaults.downloads.allowSatellite,
            ),
            loadArtworkOnSatellite = this[Keys.ARTWORK_ON_SATELLITE] ?: defaults.loadArtworkOnSatellite,
        )
    }

    private fun parseFallbackOrder(raw: String?): List<SourceType>? {
        val parsed = raw?.split(",")?.mapNotNull { name -> SourceType.entries.firstOrNull { it.name == name } }
            ?.filter { it != SourceType.JELLYFIN }
            ?.distinct()
        if (parsed.isNullOrEmpty()) return null
        // Keep any service missing from an older stored value at the end of the list.
        return parsed + SourceType.entries.filter { it != SourceType.JELLYFIN && it !in parsed }
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
        val DL_CELLULAR = booleanPreferencesKey("download_cellular")
        val DL_SATELLITE = booleanPreferencesKey("download_satellite")
        val ARTWORK_ON_SATELLITE = booleanPreferencesKey("artwork_on_satellite")
    }
}
