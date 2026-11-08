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
    /** Downloads wait for Wi-Fi (or another unmetered network). */
    val downloadOnWifiOnly: Boolean = true,
    val levelling: Boolean = true,
    /** Finds MusicCast speakers on the Wi-Fi, and lets the person's devices play on them. */
    val speakers: Boolean = true,
    /** Speakers' addresses typed in, for networks where they can't be found by themselves. */
    val speakerAddresses: List<String> = emptyList(),
) {
    val signedIn: Boolean get() = server.isNotEmpty() && token != null

    /** The cap for [network] (0 is no cap); Android's Data Saver keeps it to 128 kbps at most. */
    fun kbpsFor(network: NetworkClass, savingData: Boolean): Int {
        val cap = when (network) {
            NetworkClass.SATELLITE -> kbpsSatellite
            NetworkClass.CELLULAR -> kbpsCellular
            else -> kbpsWifi
        }
        return if (savingData) (if (cap == 0) DATA_SAVER_KBPS else minOf(cap, DATA_SAVER_KBPS)) else cap
    }

    companion object {
        const val DATA_SAVER_KBPS = 128
    }
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
        prefs[DOWNLOAD_WIFI_ONLY] = next.downloadOnWifiOnly
        prefs[LEVELLING] = next.levelling
        prefs[SPEAKERS] = next.speakers
        prefs[SPEAKER_ADDRESSES] = next.speakerAddresses.joinToString("\n")
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
        downloadOnWifiOnly = prefs[DOWNLOAD_WIFI_ONLY] ?: true,
        levelling = prefs[LEVELLING] ?: true,
        speakers = prefs[SPEAKERS] ?: true,
        speakerAddresses = prefs[SPEAKER_ADDRESSES].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() },
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
        val DOWNLOAD_WIFI_ONLY = booleanPreferencesKey("download_wifi_only")
        val LEVELLING = booleanPreferencesKey("levelling")
        val SPEAKERS = booleanPreferencesKey("speakers")
        val SPEAKER_ADDRESSES = stringPreferencesKey("speaker_addresses")
    }
}
