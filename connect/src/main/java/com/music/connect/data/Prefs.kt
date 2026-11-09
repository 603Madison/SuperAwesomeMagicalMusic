package com.music.connect.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.music.connect.net.ApiJson
import com.music.connect.net.User
import com.music.player.quality.NetworkClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("connect")

/** The app's settings, kept on the phone. */
data class Settings(
    /** The server's address, e.g. https://music.example.com; empty before signing in. */
    val server: String = "",
    /** The app's sign-in token (a long-lived one, revocable from the server's Settings). */
    val token: String? = null,
    val user: User? = null,
    val serverName: String = "",
    /** Bitrate caps (kbps, 0 for the original quality) for each kind of network. */
    val kbpsWifi: Int = 0,
    val kbpsCellular: Int = 320,
    val kbpsSatellite: Int = 64,
    /** The quality songs are downloaded at. */
    val kbpsDownload: Int = 0,
    /**
     * The most to stream while Android restricts this app's mobile data (Data Saver). 0, the
     * default: the network's own choice stands.
     */
    val kbpsDataSaver: Int = 0,
    /** Downloads wait for Wi-Fi (or another unmetered network). */
    val downloadOnWifiOnly: Boolean = true,
    val levelling: Boolean = true,
    /** Finds MusicCast speakers on the Wi-Fi, and lets the person's devices play on them. */
    val speakers: Boolean = true,
    /** Speakers' addresses typed in, for networks where they can't be found by themselves. */
    val speakerAddresses: List<String> = emptyList(),
    /**
     * YouTube Music songs are fetched by the phone itself, over its own connection, for a server
     * YouTube turns away; the server's copy is the fallback. Off by default: through the server
     * is quicker to start.
     */
    val youtubeOnPhone: Boolean = false,
) {
    val signedIn: Boolean get() = server.isNotEmpty() && token != null

    /** The cap for [network] (0 is no cap), and no more than [kbpsDataSaver] while Data Saver is on. */
    fun kbpsFor(network: NetworkClass, savingData: Boolean): Int {
        val cap = when (network) {
            NetworkClass.SATELLITE -> kbpsSatellite
            NetworkClass.CELLULAR -> kbpsCellular
            else -> kbpsWifi
        }
        if (!savingData || kbpsDataSaver == 0) return cap
        return if (cap == 0) kbpsDataSaver else minOf(cap, kbpsDataSaver)
    }

    /** Whether Data Saver is what's holding [network]'s quality down right now. */
    fun dataSaverLowers(network: NetworkClass, savingData: Boolean): Boolean = kbpsFor(network, savingData) != kbpsFor(network, false)
}

class Prefs(private val context: Context) {
    val settings: Flow<Settings> = context.store.data.map(::read)

    suspend fun current(): Settings = settings.first()

    suspend fun signIn(server: String, token: String, user: User, serverName: String) = context.store.edit {
        it[SERVER] = server
        it[TOKEN] = token
        it[USER] = ApiJson.encodeToString(User.serializer(), user)
        it[SERVER_NAME] = serverName
    }

    suspend fun signOut() = context.store.edit {
        it.remove(TOKEN)
        it.remove(USER)
    }

    suspend fun update(transform: (Settings) -> Settings) = context.store.edit { prefs ->
        val next = transform(read(prefs))
        prefs[KBPS_WIFI] = next.kbpsWifi
        prefs[KBPS_CELLULAR] = next.kbpsCellular
        prefs[KBPS_SATELLITE] = next.kbpsSatellite
        prefs[KBPS_DOWNLOAD] = next.kbpsDownload
        prefs[KBPS_DATA_SAVER] = next.kbpsDataSaver
        prefs[DOWNLOAD_WIFI_ONLY] = next.downloadOnWifiOnly
        prefs[LEVELLING] = next.levelling
        prefs[SPEAKERS] = next.speakers
        prefs[SPEAKER_ADDRESSES] = next.speakerAddresses.joinToString("\n")
        prefs[YOUTUBE_ON_PHONE] = next.youtubeOnPhone
        next.user?.let { prefs[USER] = ApiJson.encodeToString(User.serializer(), it) }
    }

    private fun read(prefs: Preferences) = Settings(
        server = prefs[SERVER].orEmpty(),
        token = prefs[TOKEN],
        user = prefs[USER]?.let { runCatching { ApiJson.decodeFromString(User.serializer(), it) }.getOrNull() },
        serverName = prefs[SERVER_NAME].orEmpty(),
        kbpsWifi = prefs[KBPS_WIFI] ?: 0,
        kbpsCellular = prefs[KBPS_CELLULAR] ?: 320,
        kbpsSatellite = prefs[KBPS_SATELLITE] ?: 64,
        kbpsDownload = prefs[KBPS_DOWNLOAD] ?: 0,
        kbpsDataSaver = prefs[KBPS_DATA_SAVER] ?: 0,
        downloadOnWifiOnly = prefs[DOWNLOAD_WIFI_ONLY] ?: true,
        levelling = prefs[LEVELLING] ?: true,
        speakers = prefs[SPEAKERS] ?: true,
        speakerAddresses = prefs[SPEAKER_ADDRESSES].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() },
        youtubeOnPhone = prefs[YOUTUBE_ON_PHONE] ?: false,
    )

    private companion object {
        val SERVER = stringPreferencesKey("server")
        val TOKEN = stringPreferencesKey("token")
        val USER = stringPreferencesKey("user")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val KBPS_WIFI = intPreferencesKey("kbps_wifi")
        val KBPS_CELLULAR = intPreferencesKey("kbps_cellular")
        val KBPS_SATELLITE = intPreferencesKey("kbps_satellite")
        val KBPS_DOWNLOAD = intPreferencesKey("kbps_download")
        // Every setting is saved whenever one changes, so 1.19's default (128 kbps) was saved
        // for anyone who changed anything: a new key leaves it behind. (Likewise 1.20.0's
        // "youtube_on_phone", on by default then.)
        val KBPS_DATA_SAVER = intPreferencesKey("kbps_data_saver_choice")
        val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only")
        val LEVELLING = booleanPreferencesKey("levelling")
        val SPEAKERS = booleanPreferencesKey("speakers")
        val SPEAKER_ADDRESSES = stringPreferencesKey("speaker_addresses")
        val YOUTUBE_ON_PHONE = booleanPreferencesKey("youtube_direct")
    }
}
