package com.music.player.settings

import com.music.player.model.SourceType
import com.music.player.net.NetworkState
import com.music.player.quality.NetworkClass
import com.music.player.quality.StreamQuality
import com.music.player.sources.radio.RadioStation
import com.music.player.sources.siriusxm.SxmStation




data class NextcloudConfig(
    val serverUrl: String = "",
    /** The login name used with the app password. */
    val username: String = "",
    /** Nextcloud's internal user id (used in WebDAV paths); may differ from the login, e.g. for e-mail logins. */
    val userId: String = "",
    val appPassword: String = "",
    val folder: String = DEFAULT_FOLDER,
    val autoSync: Boolean = true,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && username.isNotBlank() && appPassword.isNotBlank()

    val davUser: String get() = userId.ifBlank { username }

    /**
     * Who playlists belong to: the signed-in Nextcloud account (which may itself sign in through
     * an SSO provider such as authentik), or null when Nextcloud isn't connected.
     */
    val owner: String?
        get() = if (isConfigured) "$davUser@${serverUrl.substringAfter("://").trimEnd('/')}" else null

    companion object {
        const val DEFAULT_FOLDER = "Music/Playlists"
    }
}


data class QualitySettings(
    val wifi: StreamQuality = StreamQuality.Original,
    val cellular: StreamQuality = StreamQuality.Capped(192),
    val satellite: StreamQuality = StreamQuality.Capped(64),
    val download: StreamQuality = StreamQuality.Original,
    /** The most to stream while Android's Data Saver is on (on metered networks). */
    val dataSaver: StreamQuality = StreamQuality.Capped(96),
) {
    /** The bitrate for the current connection: its network's setting, lowered further by Data Saver. */
    fun forState(state: NetworkState): StreamQuality {
        val network = forNetwork(state.networkClass)
        return if (state.savingData && network.isHigherThan(dataSaver)) dataSaver else network
    }

    fun forNetwork(networkClass: NetworkClass): StreamQuality = when (networkClass) {
        NetworkClass.WIFI -> wifi
        NetworkClass.CELLULAR -> cellular
        NetworkClass.SATELLITE -> satellite
        // Offline only plays downloads; the value is irrelevant but keep it conservative.
        NetworkClass.NONE -> satellite
    }
}


data class DownloadSettings(
    val allowCellular: Boolean = false,
    val allowSatellite: Boolean = false,
)

/** How music plays, beyond where it comes from. */
data class PlaybackSettings(
    /** Even out loudness between songs and services (see LoudnessProcessor). */
    val levelVolume: Boolean = true,
    /** On SiriusXM channels, start at the beginning of the song that's on, as SiriusXM's app does. */
    val siriusXmFromSongStart: Boolean = true,
    /** Thumbs up on a Pandora station song also saves it to the "Liked on Pandora" playlist. */
    val likedOnPandoraPlaylist: Boolean = false,
    /** Start playing again (where you left off) when Android Auto connects. */
    val resumeInCar: Boolean = false,
    /** The phone's equalizer preset to use (its index), or -1 for none. */
    val equalizerPreset: Int = -1,
    /** Bass boost strength, 0 (off) to 1000. */
    val bassBoost: Int = 0,
    /** The phone's equalizer presets, as last read by the player. */
    val equalizerPresets: List<String> = emptyList(),
)

/** Songs kept in the playback cache so dead zones don't interrupt them (see PlaybackCache). */
data class CacheSettings(
    val enabled: Boolean = true,
    val sizeMb: Int = 1024,
    /** How many upcoming songs to fetch ahead of time while the connection is good. */
    val songsAhead: Int = 3,
    /** Fetch ahead on cellular too, not just Wi-Fi (never on satellite or with Data Saver on). */
    val onCellular: Boolean = true,
) {
    companion object {
        val SIZES_MB = listOf(512, 1024, 2048, 4096)
        val SONGS_AHEAD = listOf(2, 3, 5, 10)
    }
}


data class AppSettings(
    val deviceId: String = "",
    val jellyfin: JellyfinConfig = JellyfinConfig(),
    val qobuz: QobuzConfig = QobuzConfig(),
    val youTubeMusicEnabled: Boolean = true,
    val siriusXm: SiriusXmConfig = SiriusXmConfig(),
    /** Ids of bookmarked SiriusXM channels, shown first. */
    val siriusXmBookmarks: Set<String> = emptySet(),
    /** Order in which streaming services are tried when Jellyfin does not have a song. */
    val fallbackOrder: List<SourceType> = listOf(SourceType.QOBUZ, SourceType.YOUTUBE_MUSIC),
    val lidarr: LidarrConfig = LidarrConfig(),
    val nextcloud: NextcloudConfig = NextcloudConfig(),
    val quality: QualitySettings = QualitySettings(),
    val jellyfinTranscodeCodec: TranscodeCodec = TranscodeCodec.MP3,
    val downloads: DownloadSettings = DownloadSettings(),
    val loadArtworkOnSatellite: Boolean = false,
    /** Pandora artist stations started on SiriusXM, newest first. */
    val siriusXmStations: List<SxmStation> = emptyList(),
    /** Favourite radio stations, shown first. */
    val radioFavorites: List<RadioStation> = emptyList(),
    /** The stations found near you last time, so Android Auto can list them without asking for location. */
    val radioNearby: List<RadioStation> = emptyList(),
    /** How much music to load ahead of what's playing, running on into the next songs. */
    val bufferAheadSeconds: Int = DEFAULT_BUFFER_AHEAD_SECONDS,
    val playback: PlaybackSettings = PlaybackSettings(),
    val cache: CacheSettings = CacheSettings(),
    val scrobbling: ScrobbleSettings = ScrobbleSettings(),
) {
    companion object {
        const val DEFAULT_BUFFER_AHEAD_SECONDS = 300
        val BUFFER_AHEAD_STEPS = listOf(30, 60, 120, 300, 600, 900, 1200, 1800)
    }
}
