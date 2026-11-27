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
    /** The DJ on the person's own stations speaks (otherwise its words are only shown). */
    val djVoice: Boolean = true,
    /** Finds MusicCast and Sonos speakers on the Wi-Fi, and lets the person's devices play on them. */
    val speakers: Boolean = true,
    /** Speakers' addresses typed in (MusicCast or Sonos), for networks where they can't be found by themselves. */
    val speakerAddresses: List<String> = emptyList(),
    /** Light, dark, or as the phone is. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** The phone's wallpaper colours (Android 12 and later) instead of the app's own violet. */
    val wallpaperColors: Boolean = false,
    /** All text in Lexend Bold, as the phone's own Bold text setting draws it. */
    val boldText: Boolean = false,
    /** The colours (unless the wallpaper's are used). */
    val palette: Palette = Palette.AURORA,
    /**
     * The person signed out themselves: the app doesn't sign straight back in through
     * SuperAwesomeMagicalOrchestrator until they sign in again, by any means.
     */
    val signedOutOnPurpose: Boolean = false,
    /** Tell SuperAwesomeMagicalOrchestrator on this phone what's playing, so other devices can carry on with it. */
    val sharePlaying: Boolean = true,
) {
    val signedIn: Boolean get() = server.isNotEmpty() && token != null

    /**
     * The cap for [network] (0 is no cap). Android's Data Saver isn't followed: Android can't tell
     * an app whether Data Saver is on (it gives the same answer when the app's own "Background
     * data" is off), and the choice for mobile data covers it.
     */
    fun kbpsFor(network: NetworkClass): Int = when (network) {
        NetworkClass.SATELLITE -> kbpsSatellite
        NetworkClass.CELLULAR -> kbpsCellular
        else -> kbpsWifi
    }
}

/** Light, dark, or as the phone is. */
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The themes, as on the web player and the desktop app, with the two colours each shows in Settings. */
enum class Palette(val label: String, val swatchA: Long, val swatchB: Long) {
    AURORA("Aurora", 0xFF6A3FD6, 0xFF12A37C),
    VIOLET("Violet", 0xFF5B3FD1, 0xFF8E3F5C),
    OCEAN("Ocean", 0xFF1D5FC9, 0xFF0E8FA3),
    EMBER("Ember", 0xFFB4471A, 0xFF9C3F60),
}

class Prefs(private val context: Context) {
    val settings: Flow<Settings> = context.store.data.map(::read)

    suspend fun current(): Settings = settings.first()

    suspend fun signIn(server: String, token: String, user: User, serverName: String) = context.store.edit {
        it[SERVER] = server
        it[TOKEN] = token
        it[USER] = ApiJson.encodeToString(User.serializer(), user)
        it[SERVER_NAME] = serverName
        it.remove(SIGNED_OUT_ON_PURPOSE)
    }

    /** [onPurpose]: the person asked to (rather than the server ending the sign-in). */
    suspend fun signOut(onPurpose: Boolean = true) {
        // What the person agreed to with the orchestrator goes with the sign-in.
        com.music.connect.orchestrator.OrchestratorLink.forget(context)
        signOutHere(onPurpose)
    }

    private suspend fun signOutHere(onPurpose: Boolean) = context.store.edit {
        it.remove(TOKEN)
        it.remove(USER)
        if (onPurpose) it[SIGNED_OUT_ON_PURPOSE] = true
    }

    suspend fun update(transform: (Settings) -> Settings) = context.store.edit { prefs ->
        val next = transform(read(prefs))
        prefs[KBPS_WIFI] = next.kbpsWifi
        prefs[KBPS_CELLULAR] = next.kbpsCellular
        prefs[KBPS_SATELLITE] = next.kbpsSatellite
        prefs[KBPS_DOWNLOAD] = next.kbpsDownload
        prefs[DOWNLOAD_WIFI_ONLY] = next.downloadOnWifiOnly
        prefs[LEVELLING] = next.levelling
        prefs[DJ_VOICE] = next.djVoice
        prefs[SPEAKERS] = next.speakers
        prefs[SPEAKER_ADDRESSES] = next.speakerAddresses.joinToString("\n")
        prefs[THEME_MODE] = next.themeMode.name
        prefs[WALLPAPER_COLORS] = next.wallpaperColors
        prefs[BOLD_TEXT] = next.boldText
        prefs[PALETTE] = next.palette.name
        prefs[SHARE_PLAYING] = next.sharePlaying
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
        djVoice = prefs[DJ_VOICE] ?: true,
        speakers = prefs[SPEAKERS] ?: true,
        speakerAddresses = prefs[SPEAKER_ADDRESSES].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() },
        themeMode = ThemeMode.entries.firstOrNull { it.name == prefs[THEME_MODE] } ?: ThemeMode.SYSTEM,
        wallpaperColors = prefs[WALLPAPER_COLORS] ?: false,
        boldText = prefs[BOLD_TEXT] ?: false,
        palette = Palette.entries.firstOrNull { it.name == prefs[PALETTE] } ?: Palette.AURORA,
        signedOutOnPurpose = prefs[SIGNED_OUT_ON_PURPOSE] ?: false,
        sharePlaying = prefs[SHARE_PLAYING] ?: true,
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
        val DJ_VOICE = booleanPreferencesKey("dj_voice")
        val SPEAKERS = booleanPreferencesKey("speakers")
        val SPEAKER_ADDRESSES = stringPreferencesKey("speaker_addresses")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val WALLPAPER_COLORS = booleanPreferencesKey("wallpaper_colors")
        val BOLD_TEXT = booleanPreferencesKey("bold_text")
        val PALETTE = stringPreferencesKey("palette")
        val SIGNED_OUT_ON_PURPOSE = booleanPreferencesKey("signed_out_on_purpose")
        val SHARE_PLAYING = booleanPreferencesKey("share_playing")
    }
}
