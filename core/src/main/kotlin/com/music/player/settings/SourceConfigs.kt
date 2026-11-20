package com.music.player.settings

// Settings for the services music comes from, shared by the apps and the server.

data class JellyfinConfig(
    val serverUrl: String = "",
    val username: String = "",
    val userId: String = "",
    val accessToken: String = "",
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && userId.isNotBlank() && accessToken.isNotBlank()
}

/**
 * A server that speaks the Subsonic API. Either a username and password (sent as a salted token,
 * or as the password itself for servers that can't check tokens, see [auth]) or an API key
 * (OpenSubsonic servers that offer them).
 */
data class SubsonicConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
    val auth: SubsonicAuth = SubsonicAuth.TOKEN,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && (apiKey.isNotBlank() || (username.isNotBlank() && password.isNotBlank()))
}

/** How requests to a Subsonic server prove who's asking. */
enum class SubsonicAuth {
    /** md5(password + salt) with a new salt every time: the password itself is never sent. */
    TOKEN,

    /** The password (hex-encoded), for servers that can't check tokens (LDAP accounts, say). */
    PASSWORD,

    /** OpenSubsonic's API keys. */
    API_KEY,
}

data class QobuzConfig(
    val appId: String = "",
    val appSecret: String = "",
    val username: String = "",
    val userAuthToken: String = "",
) {
    val isConfigured: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank() && userAuthToken.isNotBlank()
}

data class LidarrConfig(
    val serverUrl: String = "",
    val apiKey: String = "",
    /** Add an artist to Lidarr when one of their songs is played from outside Jellyfin. */
    val autoAddArtists: Boolean = true,
    /** Null means "use the root folder's default, else the first profile on the server". */
    val qualityProfileId: Int? = null,
    val metadataProfileId: Int? = null,
    val rootFolderPath: String? = null,
    val searchForMissingAlbums: Boolean = true,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
}

data class SiriusXmConfig(
    val username: String = "",
    /** Kept so the app can sign in again when SiriusXM's session expires. */
    val password: String = "",
) {
    val isConfigured: Boolean get() = username.isNotBlank() && password.isNotBlank()
}

/** Where listens are scrobbled. */
data class ScrobbleSettings(
    val listenBrainzToken: String = "",
    /** ListenBrainz itself, or a self-hosted server with its API (e.g. Maloja, Koito). */
    val listenBrainzUrl: String = DEFAULT_LISTENBRAINZ_URL,
    val lastFmApiKey: String = "",
    val lastFmSecret: String = "",
    val lastFmUser: String = "",
    /** Last.fm's session key from signing in (the password itself isn't kept). */
    val lastFmSession: String = "",
    /** Also scrobble songs heard on SiriusXM channels and Pandora stations. */
    val includeRadio: Boolean = true,
) {
    val listenBrainzEnabled: Boolean get() = listenBrainzToken.isNotBlank()
    val lastFmEnabled: Boolean get() = lastFmApiKey.isNotBlank() && lastFmSecret.isNotBlank() && lastFmSession.isNotBlank()
    val enabled: Boolean get() = listenBrainzEnabled || lastFmEnabled

    companion object {
        const val DEFAULT_LISTENBRAINZ_URL = "https://api.listenbrainz.org"
    }
}

enum class TranscodeCodec(val label: String, val container: String, val codec: String) {
    MP3("MP3 (most compatible)", "mp3", "mp3"),
    AAC("AAC (better at low bitrates)", "aac", "aac"),
}
